package br.com.grupotechnos.watch.protocol.crp

import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.StepsSample
import br.com.grupotechnos.watch.protocol.domain.WatchErrorCodes
import br.com.grupotechnos.watch.protocol.domain.WatchException
import br.com.grupotechnos.watch.protocol.driver.CharacteristicNotification
import br.com.grupotechnos.watch.protocol.driver.DeviceDriver
import br.com.grupotechnos.watch.protocol.driver.DriverBattery
import br.com.grupotechnos.watch.protocol.driver.DriverDeviceInfo
import br.com.grupotechnos.watch.protocol.driver.DriverLink
import br.com.grupotechnos.watch.protocol.driver.DriverNotice
import br.com.grupotechnos.watch.protocol.driver.HandshakeMode
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.transport.GattUuid
import br.com.grupotechnos.watch.protocol.transport.WriteType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/**
 * Driver da família CRP (Moyoung; na Condor, o Condor Reebok P85) sobre um [DriverLink] (`docs/protocolo-crp.md`).
 * Fontes: a SDK Android descompilada (`sdk:` = `com/crrepa/...`, crpblelib 2.0.3) e a captura HCI do app Condor de
 * produção com o P85 (`fixtures/reference/condorreebok/MOY-VS02-2.0.0/`).
 *
 * **Envio** ([send]). Um pedido de cada vez, sob um [Mutex]: registra o pedido em andamento antes de escrever (a resposta
 * pode chegar antes da confirmação da escrita), escreve os pedaços do frame com [FRAGMENT_GAP_MS] entre eles
 * (`sdk:h/d.java:121-133`), sempre [WriteType.WITHOUT_RESPONSE] (o `FEE2` do P85 só declara write-without-response, e a
 * captura mostra ATT Write Command), espera o atraso da fila da SDK ([CrpCommand.postDelayMs]) e, se o pedido tem
 * resposta, espera `quirks.commandTimeoutMs`. Mais restrito que a SDK, que encadeia vários pedidos: aqui não há
 * ambiguidade de casamento nem a fila travada por um `32` sem resposta.
 *
 * **Recepção.** [start] lança dois consumidores: o de `FEE3` remonta os frames ([CrpReassembler], com descarte da parcial
 * depois de `quirks.fragmentGapTimeoutMs`) e completa o pedido em andamento por `(cmd, sub)`; `F9` vai para o log em INFO,
 * `77`, `87`, `B7` e `BB` em DEBUG e o resto em DEBUG como não pedido. O de `sideNotifications` trata FEE1 (passos ao
 * vivo: [DriverNotice.StepsUpdated] ou [DriverNotice.DataChanged]), 2A19 ([DriverNotice.BatteryReported]) e 2A37 (log).
 *
 * **Releitura pedida por FEE1** ([requestRefresh]). Sem as meias horas desta conexão (nenhum [readSteps], dia virado ou
 * leitura que falhou), FEE1 pede uma releitura com um único [DriverNotice.DataChanged] e segura os pedidos seguintes até
 * um [readSteps] terminar ou [REFRESH_RETRY_MS] passar. Andando, FEE1 notifica cerca de uma vez por segundo
 * (`condor-app-reconnect-sync-walk.jsonl`, atMs 114170 a 124147, nenhum intervalo de 2 s), e o debounce do
 * `DataChanged` na sessão reinicia a cada aviso: sem a trava, a releitura só sairia depois da caminhada. Uma leitura que
 * falha também segura os pedidos por [REFRESH_RETRY_MS], para não reler a cada rajada.
 *
 * **Handshake** (fase [PHASE_VERSION], igual no primeiro pareamento e na reconexão): espera [POST_CCCD_DELAY_MS]
 * (`sdk:h/d.java:45-56`), lê 2A29, manda sempre `5A 00` (como a captura; vale o texto do 2A29), deduz a versão
 * ([CrpProtocolVersion.parse]), recalcula o bloco e, no V2/V3, manda `B7 0E` e `BB 16 00` sem esperar resposta. Sem
 * texto nenhum, `WatchException(TIMEOUT)`: a sessão traduz para `HANDSHAKE_FAILED` no primeiro pareamento e tenta de
 * novo na reconexão. Não há vínculo de aplicação nem confirmação na tela.
 *
 * **Passos** ([readSteps]): FEE1 (totais de hoje), páginas `59 00`, `59 01` (hoje) e `59 02`, `59 03` (ontem), e o
 * `33 01` (totais de ontem), sondado uma vez por conexão: sem resposta, o legado fica desligado até a próxima conexão.
 * As meias horas viram amostras pelo [CrpStepsMapper]; `5D`/`5C` não são pedidos (o P85 não responde).
 *
 * **Sono** ([readSleep]): `32` (aceita `32` ou `B6 01 00`; sem resposta, segue sem o sono de hoje) e, com o legado
 * ligado, `33 03` e `33 04`. A montagem é a do [CrpSleepAssembler].
 *
 * **Esquecer** ([unbind]): `F8`, sem esperar resposta (o sucesso na SDK é o fim da escrita, `sdk:m/f.java:213-220`).
 */
internal class CrpDriver(private val link: DriverLink) : DeviceDriver {
	private val quirks = link.profile.quirks
	private val mutex = Mutex()
	private val noticeChannel = Channel<DriverNotice>(capacity = NOTICE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
	private val reassembler = CrpReassembler { message -> log(LogLevel.WARN, "receive", message) }
	private val notifyCharacteristic = GattUuid.normalize(link.gatt.notify)
	private val writeCharacteristic = GattUuid.normalize(link.gatt.write)

	@Volatile
	private var pending: PendingRequest? = null

	@Volatile
	private var closed = false

	@Volatile
	private var ready = false

	@Volatile
	private var blockSize: Int = CrpFrameCodec.blockSize(link.mtu, CrpProtocolVersion.V2)

	private var protocolText: String? = null

	/** `null` = ainda não sondado nesta conexão; `false` = o relógio não respondeu ao `33`. */
	private var legacyHistory: Boolean? = null

	private var liveState: LiveStepsState? = null
	private var readingSteps = false

	/** Trava dos pedidos de releitura ([requestRefresh]); ativa enquanto um pedido não foi atendido ou depois de uma falha. */
	private var refreshHold: Job? = null
	private var scope: CoroutineScope? = null
	private var receiveJob: Job? = null
	private var sideJob: Job? = null
	private var gapJob: Job? = null

	override val notices: Flow<DriverNotice> = noticeChannel.receiveAsFlow()

	override fun start(scope: CoroutineScope) {
		if (receiveJob != null || closed) return
		this.scope = scope
		receiveJob = scope.launch { receiveLoop() }
		sideJob = scope.launch { sideLoop() }
	}

	override fun describe(value: ByteArray): String = CrpFrameCodec.describe(value)

	override fun describe(characteristic: String, value: ByteArray): String = when (GattUuid.normalize(characteristic)) {
		writeCharacteristic, notifyCharacteristic -> CrpFrameCodec.describe(value)
		CrpGatt.STEPS_TODAY -> CrpStepsTotals.decode(value)?.let { "STEPS_TODAY steps=${it.steps} m=${it.distanceMeters} kcal=${it.calories}" }
			?: "STEPS_TODAY len=${value.size}"
		CrpGatt.BATTERY_LEVEL -> CrpBatteryReading.decode(value)?.let { "BATTERY raw=${it.raw}" } ?: "BATTERY len=0"
		CrpGatt.HEART_RATE -> if (value.size >= 2) "HEART_RATE bpm=${value[1].toInt() and 0xFF}" else "HEART_RATE len=${value.size}"
		CrpGatt.PROTOCOL_VERSION -> "PROTOCOL_VERSION_TEXT ${CrpText.decode(value)}"
		CrpGatt.FIRMWARE_REVISION -> "FIRMWARE_TEXT ${CrpText.decode(value)}"
		else -> CrpFrameCodec.describe(value)
	}

	override suspend fun handshake(mode: HandshakeMode, onPhase: (String) -> Unit) {
		onPhase(PHASE_VERSION)
		delay(POST_CCCD_DELAY_MS)
		val text = readTextOrNull(CrpGatt.DEVICE_INFO_SERVICE, CrpGatt.PROTOCOL_VERSION)
		val request = CrpRequest.protocolVersion()
		val reply = send(request) { it.isCmd(CrpCommand.PROTOCOL_INFO, CrpCommand.SUB_PROTOCOL_VERSION) }
		val replyText = reply?.let { CrpText.decode(it.payloadFrom(1)) }?.takeIf { it.isNotEmpty() }
		val chosen = text ?: replyText
			?: throw WatchException(WatchErrorCodes.TIMEOUT, "no protocol version (2A29 and 5A 00)")
		if (text != null && replyText != null && text != replyText) {
			log(LogLevel.INFO, "handshake", "2A29 says '$text' and 5A 00 says '$replyText'; using 2A29")
		}
		val version = CrpProtocolVersion.parse(chosen)
		protocolText = chosen
		blockSize = CrpFrameCodec.blockSize(link.mtu, version)
		log(LogLevel.INFO, "handshake", "protocol '$chosen' -> $version, mtu ${link.mtu}, block $blockSize (${mode.name})")
		if (version != CrpProtocolVersion.V1) {
			send(CrpRequest.sppQuery())
			send(CrpRequest.emojiCount())
		}
		ready = true
	}

	override suspend fun readDeviceInfo(): DriverDeviceInfo {
		val reply = send(CrpRequest.firmware()) { it.isCmd(CrpCommand.PROTOCOL_INFO, CrpCommand.SUB_FIRMWARE) }
		var firmware = reply?.let { CrpText.decode(it.payloadFrom(1)) }?.takeIf { it.isNotEmpty() }
		if (firmware == null) {
			log(LogLevel.WARN, "readDeviceInfo", "5A 01 not answered (or empty); reading 2A28")
			firmware = readTextOrNull(CrpGatt.DEVICE_INFO_SERVICE, CrpGatt.FIRMWARE_REVISION)
				?: throw WatchException(WatchErrorCodes.TIMEOUT, "no firmware version (5A 01 and 2A28)")
		}
		val ble = protocolText ?: readTextOrNull(CrpGatt.DEVICE_INFO_SERVICE, CrpGatt.PROTOCOL_VERSION)?.also { protocolText = it }
		log(LogLevel.INFO, "readDeviceInfo", "firmware $firmware, protocol $ble")
		return DriverDeviceInfo(firmware = firmware, bleVersion = ble)
	}

	override suspend fun readBattery(): Int = readBatteryStatus().percent

	override suspend fun readBatteryStatus(): DriverBattery {
		val reading = CrpBatteryReading.decode(link.read(CrpGatt.BATTERY_SERVICE, CrpGatt.BATTERY_LEVEL))
			?: throw WatchException(WatchErrorCodes.PROTOCOL, "empty battery level (2A19)")
		if (reading.clamped) log(LogLevel.WARN, "readBattery", "battery raw ${reading.raw} gives more than 100%; clamped")
		return DriverBattery(reading.percent, reading.charging)
	}

	override suspend fun setTime(nowEpochMillis: Long, timeZoneId: String) {
		val time = try {
			CrpRequest.setTime(nowEpochMillis, timeZoneId)
		} catch (error: IllegalArgumentException) {
			throw WatchException(WatchErrorCodes.PROTOCOL, "phone time cannot be encoded: ${error.message}", error)
		}
		val offset = CrpTimeZoneCodec.offsetSeconds(nowEpochMillis, timeZoneId)
		send(time)
		send(CrpRequest.timeZone(offset))
		log(LogLevel.INFO, "setTime", "watch time set (${CrpTimeCodec.decodeWallClock(time.payload)}, offset $offset s, $timeZoneId)")
	}

	override suspend fun enableDataChangeNotices() {
		log(LogLevel.INFO, "enableDataChangeNotices", "FEE1 and 2A19 notifications come from the session CCCDs; nothing sent")
	}

	override suspend fun readSteps(): List<StepsSample> {
		readingSteps = true
		var succeeded = false
		try {
			val now = link.clock.nowEpochMillis()
			val tz = link.clock.timeZoneId()
			val totals = readTotalsOrNull()
			val today = readStepsDay(firstPage = 0x00)
			val yesterday = readStepsDay(firstPage = 0x02)
			val yesterdayTotals = if (legacyHistory != false) probeYesterdayTotals() else null
			val samples = CrpStepsMapper.map(today, yesterday, totals, yesterdayTotals, now, tz)
			liveState = CrpStepsMapper.liveState(today, totals, now, tz)
			log(
				LogLevel.INFO,
				"readSteps",
				"today ${today.sum()} steps in half hours, FEE1 $totals, yesterday ${yesterday.sum()} steps, " +
					"yesterday totals $yesterdayTotals -> ${samples.size} samples",
			)
			succeeded = true
			return samples
		} finally {
			readingSteps = false
			refreshHold?.cancel()
			// Com o estado montado, FEE1 volta a virar passos ao vivo; depois de uma falha, a trava evita reler a cada rajada.
			refreshHold = if (succeeded) null else holdRefresh()
		}
	}

	override suspend fun readSleep(): List<SleepSession> {
		val now = link.clock.nowEpochMillis()
		val tz = link.clock.timeZoneId()
		val sessions = mutableListOf<SleepSession>()
		val today = send(CrpRequest.sleepToday()) {
			it.cmd == CrpCommand.SLEEP_TODAY || it.isHistory(CrpCommand.HISTORY_SLEEP, 0)
		}
		if (today == null) {
			log(LogLevel.WARN, "readSleep", "no answer to 32 after ${quirks.commandTimeoutMs} ms; no sleep for today")
		} else {
			sleepOf(today, daysAgo = 0, now, tz)?.let { sessions += it }
		}
		if (legacyHistory != false) {
			val yesterday = send(CrpRequest.legacyHistory(CrpCommand.SUB_SLEEP_YESTERDAY)) {
				it.isCmd(CrpCommand.LEGACY_HISTORY, CrpCommand.SUB_SLEEP_YESTERDAY) || it.isHistory(CrpCommand.HISTORY_SLEEP, 1)
			}
			if (yesterday == null) {
				disableLegacy("33 03")
			} else {
				legacyHistory = true
				sleepOf(yesterday, daysAgo = 1, now, tz)?.let { sessions += it }
				val twoDaysAgo = send(CrpRequest.legacyHistory(CrpCommand.SUB_SLEEP_TWO_DAYS_AGO)) {
					it.isCmd(CrpCommand.LEGACY_HISTORY, CrpCommand.SUB_SLEEP_TWO_DAYS_AGO) || it.isHistory(CrpCommand.HISTORY_SLEEP, 2)
				}
				if (twoDaysAgo == null) {
					log(LogLevel.WARN, "readSleep", "no answer to 33 04; no sleep for two days ago")
				} else {
					sleepOf(twoDaysAgo, daysAgo = 2, now, tz)?.let { sessions += it }
				}
			}
		}
		log(LogLevel.INFO, "readSleep", "${sessions.size} sleep sessions")
		return sessions
	}

	override suspend fun unbind() {
		send(CrpRequest.removeBond())
		log(LogLevel.INFO, "unbind", "F8 written (the SDK treats the end of the write as success)")
	}

	override fun close() {
		if (closed) return
		closed = true
		ready = false
		failPending("driver closed")
		noticeChannel.close()
		gapJob?.cancel()
		refreshHold?.cancel()
		receiveJob?.cancel()
		sideJob?.cancel()
	}

	/**
	 * Escreve o frame de [request] e, com [expect], espera o primeiro frame que casar (ver o KDoc da classe). Devolve
	 * `null` sem [expect] ou quando a resposta não chega em `quirks.commandTimeoutMs` (contados depois do atraso da fila);
	 * quem chama decide entre `TIMEOUT` e tolerar.
	 */
	private suspend fun send(request: CrpRequest, expect: ((CrpFrame) -> Boolean)? = null): CrpFrame? {
		if (closed) throw WatchException(WatchErrorCodes.DISCONNECTED, "CRP driver is closed")
		return mutex.withLock {
			val waiting = expect?.let { PendingRequest(it) }
			if (waiting != null) pending = waiting
			try {
				val frame = try {
					CrpFrameCodec.encode(request.cmd, request.payload, blockSize)
				} catch (error: IllegalArgumentException) {
					throw WatchException(WatchErrorCodes.PROTOCOL, "cannot encode ${CrpCommand.label(request.cmd, request.payload)}: ${error.message}", error)
				}
				val parts = CrpFrameCodec.fragments(frame, blockSize)
				logFrame("TX ${CrpCommand.label(request.cmd, request.payload)} len=${frame.size}")
				parts.forEachIndexed { index, part ->
					if (index > 0) delay(FRAGMENT_GAP_MS)
					link.write(part, WriteType.WITHOUT_RESPONSE)
				}
				delay(CrpCommand.postDelayMs(request.cmd, quirks.commandGapMs))
				if (waiting == null) {
					null
				} else {
					withTimeoutOrNull(quirks.commandTimeoutMs) { waiting.response.await() }
				}
			} finally {
				if (waiting != null && pending === waiting) pending = null
			}
		}
	}

	private suspend fun readTextOrNull(service: String, characteristic: String): String? =
		try {
			CrpText.decode(link.read(service, characteristic)).takeIf { it.isNotEmpty() }
		} catch (error: CancellationException) {
			throw error
		} catch (error: WatchException) {
			if (error.code == WatchErrorCodes.DISCONNECTED) throw error
			log(LogLevel.WARN, "read", "read of $characteristic failed (${error.code}: ${error.message})")
			null
		}

	private suspend fun readTotalsOrNull(): CrpStepsTotals? {
		val value = try {
			link.read(link.gatt.service, CrpGatt.STEPS_TODAY)
		} catch (error: CancellationException) {
			throw error
		} catch (error: WatchException) {
			if (error.code == WatchErrorCodes.DISCONNECTED) throw error
			log(LogLevel.WARN, "readSteps", "FEE1 read failed (${error.code}: ${error.message}); no day totals")
			return null
		}
		return CrpStepsTotals.decode(value).also {
			if (it == null) log(LogLevel.WARN, "readSteps", "FEE1 with ${value.size} bytes is not a steps record; no day totals")
		}
	}

	/** As duas páginas de um dia (`59 first` e `59 first+1`) em 48 meias horas; página sem resposta → `TIMEOUT`. */
	private suspend fun readStepsDay(firstPage: Int): IntArray {
		val day = IntArray(CrpStepsMapper.BUCKETS_PER_DAY)
		for (offset in 0..1) {
			val page = firstPage + offset
			val reply = send(CrpRequest.stepsPage(page)) { it.isCmd(CrpCommand.STEPS_HALF_HOURS, page) }
				?: throw WatchException(WatchErrorCodes.TIMEOUT, "no response for 59 ${hex2(page)} after ${quirks.commandTimeoutMs} ms")
			val decoded = CrpCategoryPage.decodeSteps(reply.payload)
				?: throw WatchException(WatchErrorCodes.PROTOCOL, "59 ${hex2(page)} with ${reply.payload.size} bytes is not a steps page")
			if (decoded.values.size != CrpCategoryPage.HALF_HOURS_PER_PAGE) {
				log(LogLevel.WARN, "readSteps", "59 ${hex2(page)} has ${decoded.values.size} half hours (expected ${CrpCategoryPage.HALF_HOURS_PER_PAGE})")
			}
			for (i in 0 until minOf(decoded.values.size, CrpCategoryPage.HALF_HOURS_PER_PAGE)) {
				day[offset * CrpCategoryPage.HALF_HOURS_PER_PAGE + i] = decoded.values[i].toInt()
			}
		}
		return day
	}

	/** `33 01` (aceita `B6 00 01`); sem resposta, o legado fica desligado nesta conexão. */
	private suspend fun probeYesterdayTotals(): CrpStepsTotals? {
		val reply = send(CrpRequest.legacyHistory(CrpCommand.SUB_STEPS_YESTERDAY)) {
			it.isCmd(CrpCommand.LEGACY_HISTORY, CrpCommand.SUB_STEPS_YESTERDAY) || it.isHistory(CrpCommand.HISTORY_STEPS, 1)
		}
		if (reply == null) {
			disableLegacy("33 01")
			return null
		}
		legacyHistory = true
		val totals = if (reply.cmd == CrpCommand.HISTORY) {
			CrpStepsTotals.decodeHistoryNew(reply.payloadFrom(2))
		} else {
			CrpStepsTotals.decode(reply.payloadFrom(1))
		}
		if (totals == null) log(LogLevel.WARN, "readSteps", "yesterday totals with ${reply.payload.size} bytes ignored")
		return totals
	}

	private fun disableLegacy(what: String) {
		legacyHistory = false
		log(LogLevel.INFO, "legacyHistory", "no answer to $what; 0x33 history disabled for this connection")
	}

	private fun sleepOf(frame: CrpFrame, daysAgo: Int, now: Long, tz: String): SleepSession? {
		val legacy = frame.cmd != CrpCommand.HISTORY
		val bytes = when (frame.cmd) {
			CrpCommand.SLEEP_TODAY -> frame.payload
			CrpCommand.LEGACY_HISTORY -> frame.payloadFrom(1)
			else -> frame.payloadFrom(2)
		}
		val records = (if (legacy) CrpSleepPayload.decodeLegacy(bytes) else CrpSleepPayload.decodeNew(bytes))
		if (records == null) {
			log(LogLevel.WARN, "readSleep", "${CrpCommand.label(frame.cmd, frame.payload)} with ${bytes.size} record bytes ignored")
			return null
		}
		val wakeDay = CrpSleepAssembler.sleepDate(now, tz, daysAgo)
		val session = CrpSleepAssembler.assemble(records, wakeDay, tz, legacyHourWrap = legacy) { warning ->
			log(LogLevel.WARN, "readSleep", warning)
		}
		log(LogLevel.INFO, "readSleep", "${records.size} records for $wakeDay -> ${session?.let { "${it.totalMinutes} min" } ?: "no session"}")
		return session
	}

	private suspend fun receiveLoop() {
		try {
			for (packet in link.notifications) {
				try {
					for (frame in reassembler.accept(packet)) handleFrame(frame)
					armGapTimer()
				} catch (error: CancellationException) {
					throw error
				} catch (error: Exception) {
					log(LogLevel.ERROR, "receive", "failed to handle notification: ${error.message}")
				}
			}
		} finally {
			failPending("notification channel closed")
		}
	}

	/** Com um frame pela metade, (re)arma o descarte depois de `quirks.fragmentGapTimeoutMs`; sem ele, desarma. */
	private fun armGapTimer() {
		gapJob?.cancel()
		gapJob = null
		if (!reassembler.hasPartial) return
		gapJob = scope?.launch {
			delay(quirks.fragmentGapTimeoutMs)
			if (reassembler.hasPartial) {
				log(LogLevel.WARN, "receive", "partial frame discarded after ${quirks.fragmentGapTimeoutMs} ms without continuation")
				reassembler.reset()
			}
		}
	}

	private fun handleFrame(frame: CrpFrame) {
		val label = CrpCommand.label(frame.cmd, frame.payload)
		logFrame("RX $label len=${frame.payload.size + CrpFrameCodec.HEADER_SIZE}")
		val request = pending
		if (request != null && request.matches(frame)) {
			request.response.complete(frame)
			return
		}
		when (frame.cmd) {
			CrpCommand.CLASSIC_STATE -> log(LogLevel.INFO, "classicState", "watch reports ${CrpClassicState.describe(frame.payload)}")
			CrpCommand.BIND_NOTICE, CrpCommand.BIND_NOTICE_ALT, CrpCommand.SPP_QUERY, CrpCommand.SETTINGS ->
				log(LogLevel.DEBUG, "receive", "notice $label (${frame.payload.size} bytes)")
			else -> log(LogLevel.DEBUG, "receive", "unsolicited $label (${frame.payload.size} bytes) ignored")
		}
	}

	private suspend fun sideLoop() {
		for (notification in link.sideNotifications) {
			try {
				handleSide(notification)
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				log(LogLevel.ERROR, "receive", "failed to handle ${notification.characteristic}: ${error.message}")
			}
		}
	}

	private fun handleSide(notification: CharacteristicNotification) {
		when (GattUuid.normalize(notification.characteristic)) {
			CrpGatt.STEPS_TODAY -> onStepsNotification(notification.value)
			CrpGatt.BATTERY_LEVEL -> onBatteryNotification(notification.value)
			CrpGatt.HEART_RATE -> log(
				LogLevel.DEBUG,
				"heartRate",
				if (notification.value.size >= 2) "hr=${notification.value[1].toInt() and 0xFF}" else "hr notification of ${notification.value.size} bytes",
			)
			else -> log(LogLevel.DEBUG, "receive", "notification on ${notification.characteristic} ignored")
		}
	}

	private fun onStepsNotification(value: ByteArray) {
		val totals = CrpStepsTotals.decode(value)
		if (totals == null) {
			log(LogLevel.WARN, "liveSteps", "FEE1 notification with ${value.size} bytes ignored")
			return
		}
		if (!ready) {
			log(LogLevel.DEBUG, "liveSteps", "FEE1 before the handshake ignored: $totals")
			return
		}
		if (totals.isZero) {
			log(LogLevel.DEBUG, "liveSteps", "FEE1 with zero totals ignored")
			return
		}
		val state = liveState
		if (state == null) {
			if (readingSteps) {
				log(LogLevel.DEBUG, "liveSteps", "FEE1 during readSteps ignored (the read builds the state)")
			} else {
				requestRefresh("FEE1 $totals without half hours read in this connection")
			}
			return
		}
		val now = link.clock.nowEpochMillis()
		val tz = link.clock.timeZoneId()
		when (val update = CrpStepsMapper.liveUpdate(state, totals, now, tz)) {
			LiveUpdate.DataChanged -> {
				liveState = null
				requestRefresh("day changed since the last read")
			}
			LiveUpdate.Ignore -> log(LogLevel.DEBUG, "liveSteps", "FEE1 with zero totals ignored")
			is LiveUpdate.Samples -> {
				liveState = update.state
				log(LogLevel.DEBUG, "liveSteps", "FEE1 $totals -> half hour ${update.state.index} = ${update.state.lastRemainder}")
				noticeChannel.trySend(DriverNotice.StepsUpdated(update.samples))
			}
		}
	}

	/**
	 * Um [DriverNotice.DataChanged] por releitura: com a trava ativa (pedido ainda não atendido, ou falha recente), só
	 * DEBUG. A trava cai quando um [readSteps] termina com sucesso ou depois de [REFRESH_RETRY_MS].
	 */
	private fun requestRefresh(reason: String) {
		if (refreshHold?.isActive == true) {
			log(LogLevel.DEBUG, "liveSteps", "$reason; refresh already requested")
			return
		}
		log(LogLevel.INFO, "liveSteps", "$reason; asking for a refresh")
		noticeChannel.trySend(DriverNotice.DataChanged)
		refreshHold = holdRefresh()
	}

	private fun holdRefresh(): Job? = scope?.launch { delay(REFRESH_RETRY_MS) }

	private fun onBatteryNotification(value: ByteArray) {
		val reading = CrpBatteryReading.decode(value)
		if (reading == null) {
			log(LogLevel.WARN, "battery", "empty 2A19 notification ignored")
			return
		}
		if (!ready) {
			log(LogLevel.DEBUG, "battery", "2A19 before the handshake ignored: raw ${reading.raw}")
			return
		}
		if (reading.clamped) log(LogLevel.WARN, "battery", "battery raw ${reading.raw} gives more than 100%; clamped")
		noticeChannel.trySend(DriverNotice.BatteryReported(DriverBattery(reading.percent, reading.charging)))
	}

	private fun failPending(reason: String) {
		pending?.response?.completeExceptionally(WatchException(WatchErrorCodes.DISCONNECTED, reason))
	}

	private fun log(level: LogLevel, method: String, message: String) {
		link.logger.log(level, "CrpDriver/$method", message)
	}

	/**
	 * Uma linha `CrpDriver/frame` em DEBUG por frame em cada sentido (`TX 5A/00 PROTOCOL_VERSION len=6`). Os pacotes crus
	 * saem uma vez só, pelo `WatchLogger.frame` do link da sessão, que é a fonte das gravações.
	 */
	private fun logFrame(message: String) {
		log(LogLevel.DEBUG, "frame", message)
	}

	private fun hex2(value: Int): String = value.toString(16).uppercase().padStart(2, '0')

	/** Pedido em andamento: a resposta é o primeiro frame que [matches] aceita. */
	private class PendingRequest(val matches: (CrpFrame) -> Boolean) {
		val response = CompletableDeferred<CrpFrame>()
	}

	companion object {
		/** Fase reportada em `onPhase` no primeiro pareamento e na reconexão. */
		const val PHASE_VERSION: String = "VERSION"

		/** Espera depois da última CCCD antes de ler a versão (`sdk:h/d.java:45-56`). */
		const val POST_CCCD_DELAY_MS: Long = 300

		/** Espera entre dois pedaços do mesmo frame (`sdk:h/d.java:121-133`, o `sleep` de 20 ms no `onCharacteristicWrite`). */
		const val FRAGMENT_GAP_MS: Long = 20

		/**
		 * Tempo em que um pedido de releitura feito por FEE1 segura os seguintes sem que um [readSteps] termine (pedido que
		 * a sessão não atendeu, ou leitura que falhou). Uma releitura leva cerca de 6 s (FEE1, quatro páginas e o `33 01`,
		 * que o P85 da captura deixa vencer em 4 s); 30 s dão folga e limitam as tentativas a duas por minuto.
		 */
		const val REFRESH_RETRY_MS: Long = 30_000

		private const val NOTICE_BUFFER = 64
	}
}
