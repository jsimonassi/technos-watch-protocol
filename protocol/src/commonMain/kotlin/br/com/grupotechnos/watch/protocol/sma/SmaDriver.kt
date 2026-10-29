package br.com.grupotechnos.watch.protocol.sma

import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.StepsSample
import br.com.grupotechnos.watch.protocol.domain.WatchErrorCodes
import br.com.grupotechnos.watch.protocol.domain.WatchException
import br.com.grupotechnos.watch.protocol.driver.DeviceDriver
import br.com.grupotechnos.watch.protocol.driver.DriverDeviceInfo
import br.com.grupotechnos.watch.protocol.driver.DriverLink
import br.com.grupotechnos.watch.protocol.driver.DriverNotice
import br.com.grupotechnos.watch.protocol.driver.HandshakeMode
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.profile.BindPayloadKind
import br.com.grupotechnos.watch.protocol.sma.codec.SmaDecoded
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFragmenter
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrame
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrameCodec
import br.com.grupotechnos.watch.protocol.sma.codec.SmaReassembler
import br.com.grupotechnos.watch.protocol.sma.codec.SmaReassemblyEvent
import br.com.grupotechnos.watch.protocol.sma.codec.SmaSequence
import br.com.grupotechnos.watch.protocol.sma.commands.SmaBatteryPayload
import br.com.grupotechnos.watch.protocol.sma.commands.SmaBindPayload
import br.com.grupotechnos.watch.protocol.sma.commands.SmaCmd
import br.com.grupotechnos.watch.protocol.sma.commands.SmaCommand
import br.com.grupotechnos.watch.protocol.sma.commands.SmaCommandKind
import br.com.grupotechnos.watch.protocol.sma.commands.SmaTimeCodec
import br.com.grupotechnos.watch.protocol.sma.commands.SmaUnbindPayload
import br.com.grupotechnos.watch.protocol.sma.commands.SmaVersionPayload
import br.com.grupotechnos.watch.protocol.sma.data.SmaRecordCodec
import br.com.grupotechnos.watch.protocol.sma.data.SmaSleepAnalyzer
import br.com.grupotechnos.watch.protocol.sma.data.SmaStepsMapper
import br.com.grupotechnos.watch.protocol.transport.WriteType
import br.com.grupotechnos.watch.protocol.util.ByteReader
import br.com.grupotechnos.watch.protocol.util.Hex
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * Driver da família SMA sobre um [DriverLink] (`docs/protocolo-sma.md`, seções 4 e 5).
 *
 * **Envio.** Cada frame é escrito como todos os seus fragmentos ([SmaFragmenter]) dentro de um [Mutex] do driver,
 * com [WriteType.WITH_RESPONSE] (o iOS usa sempre com resposta nos frames de protocolo, `SmaBLE.m:3336`). O mesmo
 * mutex protege os ACK/NACK que a corrotina de recepção escreve, então um ACK nunca se intercala com os fragmentos
 * de outro frame: o relógio remonta pelo tamanho do L1 e um ACK no meio corromperia o corpo.
 *
 * **Recepção.** [start] lança a corrotina que consome `link.notifications` → [SmaReassembler]:
 * - ACK/NACK do relógio completam a espera de ACK do pedido em andamento (o seq só é conferido para log; nenhuma
 *   SDK confere, `SmaManager.java:508-516`, `SmaBLE.m:163-166`). A exceção é o ACK atrasado de um pedido cuja
 *   resposta já valeu como ACK: esse é descartado, para não confirmar o pedido seguinte no lugar dele;
 * - frame com CRC certo: primeiro escreve `ACK(seq)`, depois entrega: resposta do pedido em andamento (casada por
 *   `cmd` e `key + 1`), aviso ([DriverNotice]) ou log de inesperado. ACK antes de entregar é o que as duas SDKs
 *   fazem (`SmaManager.java:540-542`, `SmaBLE.m:227`) e é provavelmente o que avança o cursor do histórico;
 * - CRC errado: escreve `NACK(seq)` (como o iOS, `SmaBLE.m:611-620`), descarta e registra.
 *
 * **Pedidos.** Um de cada vez (a sessão serializa). Espera o ACK em `quirks.commandTimeoutMs`; nos GETs, espera
 * depois a resposta no mesmo prazo, e uma resposta que chega antes do ACK vale como ACK (a SDK iOS libera a fila de
 * GET pela resposta, `SmaBLE.m:228-240`). SET e handshake têm uma retentativa em timeout ou NACK, como a fila iOS
 * (`SmaBLE.m:3302-3317`), mas com seq **novo**: a fila iOS reenvia os mesmos bytes, com o mesmo seq
 * (`SmaBusinessTool.m:118-126`, `SmaBLE.m:3330-3337`), e a Android não reenvia (`SmaMessenger.java:36-96`). Seq novo
 * é escolha desta biblioteca (`docs/protocolo-sma.md`, seções 4 e 10). GET não tem retentativa, porque reenviar um
 * `05/41` cuja resposta só atrasou pode fazer o relógio avançar o cursor e uma página se perder
 * (`docs/protocolo-sma.md`, seção 8). Antes do LOGIN só `cmd 0x03` é enviado (`SmaManager.java:1245`); o resto
 * falha com `NOT_READY` sem escrever nada.
 *
 * **Concorrência.** Pensado para rodar no dispatcher de paralelismo 1 da sessão; o estado compartilhado entre a
 * corrotina de recepção e os pedidos é `@Volatile` e as esperas usam [CompletableDeferred].
 *
 * **Log.** Uma linha `SmaDriver/frame` em DEBUG por frame de dados completo em cada sentido ([logFrame]), pelo
 * `WatchLogger.log`. O driver nunca chama `WatchLogger.frame`: os pacotes crus saem uma vez só, pelo link da sessão.
 *
 * @param nowMs relógio monotônico em milissegundos para o intervalo entre fragmentos; os testes passam o tempo
 * virtual do `TestCoroutineScheduler`.
 */
internal class SmaDriver(
	private val link: DriverLink,
	private val nowMs: () -> Long = monotonicMillis(),
) : DeviceDriver {
	private val quirks = link.profile.quirks
	private val sequence = SmaSequence()
	private val reassembler = SmaReassembler(quirks.fragmentGapTimeoutMs)
	private val writeMutex = Mutex()
	private val noticeChannel = Channel<DriverNotice>(capacity = NOTICE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

	@Volatile
	private var pending: PendingRequest? = null

	@Volatile
	private var loggedIn = false

	/** Seq do último pedido cuja resposta chegou antes do ACK; o ACK dele, se vier depois, é descartado. */
	@Volatile
	private var answeredBeforeAck: Int? = null

	@Volatile
	private var closed = false

	private var receiveJob: Job? = null

	override val notices: Flow<DriverNotice> = noticeChannel.receiveAsFlow()

	override fun start(scope: CoroutineScope) {
		if (receiveJob != null || closed) return
		receiveJob = scope.launch { receiveLoop() }
	}

	override fun describe(value: ByteArray): String {
		if (value.size < SmaFrameCodec.HEADER_SIZE || ByteReader.u8At(value, 0) != SmaFrameCodec.MAGIC) return ""
		val flags = ByteReader.u8At(value, 1)
		val seq = ByteReader.u16At(value, 6)
		if (flags and SmaFrameCodec.FLAG_ACK != 0) {
			return if (flags and SmaFrameCodec.FLAG_ERROR != 0) "NACK seq=$seq" else "ACK seq=$seq"
		}
		val l2Size = ByteReader.u16At(value, 2)
		if (l2Size < SmaFrameCodec.L2_HEADER_SIZE) return "seq=$seq bad-length l2=$l2Size"
		val valueSize = l2Size - SmaFrameCodec.L2_HEADER_SIZE
		if (value.size < SmaFrameCodec.HEADER_SIZE + 3) return "seq=$seq header len=$valueSize"
		val cmd = ByteReader.u8At(value, SmaFrameCodec.HEADER_SIZE)
		val key = ByteReader.u8At(value, SmaFrameCodec.HEADER_SIZE + 2)
		val summary = "seq=$seq ${SmaCommand.label(cmd, key)} len=$valueSize"
		if (value.size != l2Size + SmaFrameCodec.HEADER_SIZE) return summary
		val crcOk = SmaFrameCodec.decode(value) is SmaDecoded.Data
		return summary + if (crcOk) " crc=ok" else " crc=bad"
	}

	/**
	 * BIND + LOGIN ([HandshakeMode.FIRST_PAIR]) ou só LOGIN ([HandshakeMode.RECONNECT]), com a recuperação de LOGIN
	 * recusado de `docs/protocolo-sma.md`, seção 5:
	 * - **FIRST_PAIR** (só vem de um `pair` pedido pelo usuário): com `ANDROID_FF` recusado, refaz **uma vez** o
	 *   vínculo completo no formato `USER_ID` (risco 3 do plano: o relógio pode ter sido vinculado pelo app iOS).
	 * - **RECONNECT** (pode rodar em segundo plano, a cada queda): **nunca** envia BIND, porque o BIND pode pedir
	 *   confirmação na tela do relógio. Com o LOGIN recusado, tenta um LOGIN com o outro formato de payload; é o que
	 *   reencontra um relógio que o fallback acima vinculou com `USER_ID`, sem precisar guardar o formato entre
	 *   conexões (o driver vive uma conexão só). Recusado de novo → `HANDSHAKE_FAILED`, e a sessão desiste.
	 */
	override suspend fun handshake(mode: HandshakeMode, onPhase: (String) -> Unit) {
		val kind = quirks.bindPayload
		if (runHandshake(mode, kind, onPhase)) return
		when (mode) {
			HandshakeMode.FIRST_PAIR -> if (kind == BindPayloadKind.ANDROID_FF) {
				log(LogLevel.WARN, "handshake", "LOGIN refused with ANDROID_FF payload; retrying FIRST_PAIR with USER_ID ${quirks.bindUserId}")
				if (runHandshake(HandshakeMode.FIRST_PAIR, BindPayloadKind.USER_ID, onPhase)) return
			}
			HandshakeMode.RECONNECT -> {
				val other = if (kind == BindPayloadKind.ANDROID_FF) BindPayloadKind.USER_ID else BindPayloadKind.ANDROID_FF
				log(LogLevel.WARN, "handshake", "LOGIN refused with $kind payload; trying LOGIN with $other (no BIND on reconnect)")
				if (runHandshake(HandshakeMode.RECONNECT, other, onPhase)) return
			}
		}
		throw WatchException(WatchErrorCodes.HANDSHAKE_FAILED, "LOGIN refused by the watch ($mode)")
	}

	override suspend fun readDeviceInfo(): DriverDeviceInfo {
		val response = requireResponse(SmaCommand.VERSION, EMPTY)
		val version = decodeOrProtocolError("VERSION_BACK", response) { SmaVersionPayload.decode(it) }
		return DriverDeviceInfo(version.firmware, version.bleVersion)
	}

	override suspend fun readBattery(): Int {
		val response = requireResponse(SmaCommand.BATTERY, EMPTY)
		val percent = decodeOrProtocolError("BATTERY_BACK", response) { SmaBatteryPayload.decode(it) }
		if (percent > 100) log(LogLevel.WARN, "readBattery", "battery $percent% above 100, clamped")
		return percent.coerceIn(0, 100)
	}

	override suspend fun setTime(nowEpochMillis: Long, timeZoneId: String) {
		val local = TimeFormat.toLocalWallClock(nowEpochMillis, timeZoneId)
		val value = try {
			SmaTimeCodec.encode(local)
		} catch (error: IllegalArgumentException) {
			throw WatchException(WatchErrorCodes.PROTOCOL, "Phone time $local cannot be encoded: ${error.message}", error)
		}
		request(SmaCommand.SET_TIME, value)
		log(LogLevel.INFO, "setTime", "watch time set to ${TimeFormat.formatLocal(local)} ($timeZoneId)")
	}

	override suspend fun enableDataChangeNotices() {
		// O payload de 02/6F (SET_SYNC_SWITCH) não aparece em nenhuma das SDKs (só a constante, SmaManager.java:1730);
		// enviar um palpite poderia ser interpretado pelo firmware como outro ajuste.
		log(LogLevel.INFO, "enableDataChangeNotices", "02/6F SYNC_SWITCH not sent: payload unknown in both SDKs")
	}

	override suspend fun readSteps(): List<StepsSample> {
		val records = readPages(SmaCommand.SPORT) { SmaRecordCodec.decodeSport(it) }
		return SmaStepsMapper.map(records, link.clock.timeZoneId())
	}

	override suspend fun readSleep(): List<SleepSession> {
		val records = readPages(SmaCommand.SLEEP) { SmaRecordCodec.decodeSleep(it) }
		return SmaSleepAnalyzer.analyze(records, link.clock.timeZoneId())
	}

	override suspend fun unbind() {
		request(SmaCommand.UNBIND, SmaUnbindPayload.encode())
		loggedIn = false
	}

	override fun close() {
		if (closed) return
		closed = true
		loggedIn = false
		failPending("driver closed")
		noticeChannel.close()
		receiveJob?.cancel()
	}

	private suspend fun runHandshake(mode: HandshakeMode, kind: BindPayloadKind, onPhase: (String) -> Unit): Boolean {
		loggedIn = false
		val payload = SmaBindPayload.encode(kind, quirks.bindUserId)
		if (mode == HandshakeMode.FIRST_PAIR) {
			onPhase(PHASE_BIND)
			val bind = requireResponse(SmaCommand.BIND, payload, quirks.bindTimeoutMs)
			if (!SmaBindPayload.isBindAccepted(bind.value)) {
				throw WatchException(WatchErrorCodes.HANDSHAKE_FAILED, "BIND refused, value=${Hex.encode(bind.value)}")
			}
		}
		onPhase(PHASE_LOGIN)
		val login = requireResponse(SmaCommand.LOGIN, payload)
		if (!SmaBindPayload.isLoginAccepted(login.value)) {
			log(LogLevel.WARN, "handshake", "LOGIN refused ($kind), value=${Hex.encode(login.value)}")
			return false
		}
		loggedIn = true
		log(LogLevel.INFO, "handshake", "logged in ($mode, $kind)")
		return true
	}

	/**
	 * Lê todas as páginas de um histórico: repete a mesma key enquanto a resposta trouxer exatamente
	 * [SmaRecordCodec.MAX_RECORDS_PER_PAGE] registros (`SmaManager.onDataRead`, `SmaManager.java:1408-1438`;
	 * o iOS refaz o pedido quando o value tem 160/140 bytes, `SmaBLE.m:2706-2708, 2935-2937`). Qualquer outra
	 * contagem, inclusive zero, encerra.
	 *
	 * Duas proteções que as SDKs não têm, para o caso de a hipótese "o cursor avança com o nosso ACK" estar errada:
	 * uma página idêntica à anterior encerra a leitura sem duplicar, e há um teto de [MAX_PAGES] páginas.
	 */
	private suspend fun <T> readPages(
		command: SmaCommand,
		decode: (ByteArray) -> SmaRecordCodec.Decoded<T>,
	): List<T> {
		val records = ArrayList<T>()
		var previous: ByteArray? = null
		var page = 0
		while (true) {
			page++
			val response = requireResponse(command, EMPTY)
			if (previous != null && response.value.isNotEmpty() && response.value.contentEquals(previous)) {
				log(LogLevel.WARN, "readPages", "${command.name} page $page repeats the previous one; stopping")
				break
			}
			val decoded = decode(response.value)
			if (decoded.trailingBytes != 0) {
				log(LogLevel.WARN, "readPages", "${command.name} page $page has ${decoded.trailingBytes} trailing bytes")
			}
			records += decoded.records
			log(LogLevel.DEBUG, "readPages", "${command.name} page $page: ${decoded.records.size} records")
			if (decoded.records.size != SmaRecordCodec.MAX_RECORDS_PER_PAGE) break
			if (page >= MAX_PAGES) {
				log(LogLevel.WARN, "readPages", "${command.name} stopped after $MAX_PAGES pages")
				break
			}
			previous = response.value
		}
		return records
	}

	private suspend fun requireResponse(
		command: SmaCommand,
		value: ByteArray,
		responseTimeoutMs: Long = quirks.commandTimeoutMs,
	): SmaFrame = request(command, value, responseTimeoutMs)
		?: throw WatchException(WatchErrorCodes.PROTOCOL, "${command.name} has no response key")

	private suspend fun request(
		command: SmaCommand,
		value: ByteArray,
		responseTimeoutMs: Long = quirks.commandTimeoutMs,
	): SmaFrame? {
		if (closed) throw WatchException(WatchErrorCodes.DISCONNECTED, "SMA driver is closed")
		if (!loggedIn && command.cmd != SmaCmd.CONNECT) {
			throw WatchException(WatchErrorCodes.NOT_READY, "${command.name} refused before LOGIN")
		}
		val retryable = command.kind == SmaCommandKind.SET || command.kind == SmaCommandKind.HANDSHAKE
		val attempts = if (retryable) 2 else 1
		var failure: WatchException? = null
		for (attempt in 1..attempts) {
			when (val outcome = attempt(command, value, responseTimeoutMs)) {
				is Outcome.Success -> return outcome.response
				is Outcome.Failure -> {
					failure = outcome.error
					if (!outcome.retryable || attempt == attempts) break
					log(LogLevel.WARN, "request", "${outcome.error.message}; retrying once")
				}
			}
		}
		throw failure ?: WatchException(WatchErrorCodes.PROTOCOL, "${command.name} failed")
	}

	private suspend fun attempt(command: SmaCommand, value: ByteArray, responseTimeoutMs: Long): Outcome {
		val seq = sequence.next()
		val bytes = SmaFrameCodec.encode(seq, command.cmd, command.key, value)
		val label = "${SmaCommand.label(command.cmd, command.key)} seq=$seq"
		val request = PendingRequest(command.cmd, command.responseKey, seq)
		pending = request
		try {
			logFrame("TX seq=$seq ${SmaCommand.label(command.cmd, command.key)} len=${value.size}")
			sendFrame(bytes)
			val acked = withTimeoutOrNull(quirks.commandTimeoutMs) { request.acked.await() }
				?: return Outcome.Failure(timeout("No ACK for $label after ${quirks.commandTimeoutMs} ms"), retryable = true)
			if (!acked) {
				return Outcome.Failure(WatchException(WatchErrorCodes.PROTOCOL, "NACK for $label"), retryable = true)
			}
			if (command.responseKey == null) return Outcome.Success(null)
			val response = withTimeoutOrNull(responseTimeoutMs) { request.response.await() }
				?: return Outcome.Failure(
					timeout("No response for $label after $responseTimeoutMs ms"),
					// O BIND já espera bindTimeoutMs pela confirmação na tela; repetir dobraria a espera.
					retryable = command != SmaCommand.BIND,
				)
			return Outcome.Success(response)
		} finally {
			if (pending === request) pending = null
		}
	}

	private suspend fun sendFrame(bytes: ByteArray) {
		val fragmentSize = SmaFragmenter.fragmentSize(quirks, link.mtu)
		writeMutex.withLock {
			for (fragment in SmaFragmenter.split(bytes, fragmentSize)) {
				link.write(fragment, WriteType.WITH_RESPONSE)
			}
		}
	}

	private suspend fun writeControl(bytes: ByteArray) {
		writeMutex.withLock { link.write(bytes, WriteType.WITH_RESPONSE) }
	}

	private suspend fun receiveLoop() {
		try {
			for (packet in link.notifications) {
				try {
					handlePacket(packet)
				} catch (error: CancellationException) {
					throw error
				} catch (error: Exception) {
					log(LogLevel.ERROR, "receive", "failed to handle notification: ${error.message}")
				}
			}
		} finally {
			reassembler.reset()
			failPending("notification channel closed")
		}
	}

	private suspend fun handlePacket(packet: ByteArray) {
		for (event in reassembler.accept(packet, nowMs())) {
			when (event) {
				is SmaReassemblyEvent.Control -> onControl(event)
				is SmaReassemblyEvent.Frame -> {
					val frame = event.frame
					logFrame("RX seq=${frame.sequence} ${SmaCommand.label(frame.cmd, frame.key)} len=${frame.value.size} crc=ok")
					writeControl(SmaFrameCodec.ack(frame.sequence))
					dispatch(frame)
				}
				is SmaReassemblyEvent.Corrupted -> {
					log(LogLevel.WARN, "receive", "corrupted frame (${event.error}): ${event.detail}; raw=${Hex.encode(event.raw)}")
					event.sequence?.let { writeControl(SmaFrameCodec.nack(it)) }
				}
				is SmaReassemblyEvent.Dropped ->
					log(LogLevel.WARN, "receive", "dropped ${event.byteCount} bytes (${event.reason}): ${event.detail}")
			}
		}
	}

	private fun onControl(event: SmaReassemblyEvent.Control) {
		val kind = if (event.nack) "NACK" else "ACK"
		if (event.sequence == answeredBeforeAck) {
			// ACK atrasado de um pedido que a resposta já liberou: creditá-lo ao pedido seguinte daria um SET por
			// confirmado sem o relógio ter confirmado, e todo ACK depois dele ficaria deslocado em um.
			answeredBeforeAck = null
			log(LogLevel.DEBUG, "receive", "late $kind seq=${event.sequence} for a request already answered; ignored")
			return
		}
		val request = pending
		if (request == null) {
			log(LogLevel.DEBUG, "receive", "$kind seq=${event.sequence} with no request in flight")
			return
		}
		if (event.sequence != request.sequence) {
			log(LogLevel.DEBUG, "receive", "$kind seq=${event.sequence} while waiting seq=${request.sequence}")
		}
		request.acked.complete(!event.nack)
	}

	private fun dispatch(frame: SmaFrame) {
		val request = pending
		if (request != null && request.responseKey != null && frame.cmd == request.cmd && frame.key == request.responseKey) {
			if (!request.acked.isCompleted) {
				log(LogLevel.DEBUG, "receive", "response seq=${frame.sequence} arrived before the ACK; counting it as ACK")
				answeredBeforeAck = request.sequence
				request.acked.complete(true)
			}
			request.response.complete(frame)
			return
		}
		val notice = noticeOf(frame) ?: return
		noticeChannel.trySend(notice)
	}

	private fun noticeOf(frame: SmaFrame): DriverNotice? {
		val label = SmaCommand.label(frame.cmd, frame.key)
		return when (SmaCommand.byCmdKey(frame.cmd, frame.key)) {
			SmaCommand.DATA_CHANGED -> {
				val flag = if (frame.value.isEmpty()) "none" else ByteReader.u8At(frame.value, 0).toString()
				log(LogLevel.INFO, "receive", "$label flag=$flag")
				DriverNotice.DataChanged
			}
			SmaCommand.BLE_CONNECTION -> {
				if (frame.value.isEmpty()) {
					log(LogLevel.WARN, "receive", "$label without value; ignored")
					null
				} else {
					DriverNotice.LinkReported(connected = frame.value[0].toInt() == 0)
				}
			}
			else -> {
				log(LogLevel.WARN, "receive", "unexpected frame seq=${frame.sequence} $label value=${Hex.encode(frame.value)}")
				null
			}
		}
	}

	private fun failPending(reason: String) {
		val request = pending ?: return
		val error = WatchException(WatchErrorCodes.DISCONNECTED, reason)
		request.acked.completeExceptionally(error)
		request.response.completeExceptionally(error)
	}

	private inline fun <T> decodeOrProtocolError(name: String, frame: SmaFrame, decode: (ByteArray) -> T): T =
		try {
			decode(frame.value)
		} catch (error: IllegalArgumentException) {
			throw WatchException(WatchErrorCodes.PROTOCOL, "$name invalid (${Hex.encode(frame.value)}): ${error.message}", error)
		}

	private fun timeout(message: String) = WatchException(WatchErrorCodes.TIMEOUT, message)

	private fun log(level: LogLevel, method: String, message: String) {
		link.logger.log(level, "SmaDriver/$method", message)
	}

	/**
	 * Uma linha `SmaDriver/frame` em DEBUG por frame de dados completo, nos dois sentidos (ACK/NACK ficam de fora):
	 * `TX seq=<n> <cmd/key NOME> len=<value>` antes de escrever os fragmentos e `RX seq=<n> <cmd/key NOME> len=<value>
	 * crc=ok` depois de remontar, antes do ACK. Os pacotes crus continuam saindo só em `WatchLogger.frame`, pelo
	 * `DriverLink`, uma vez cada: é de lá que o host grava a fixture, e chamar `frame` aqui duplicaria os pacotes da
	 * gravação. A linha existe porque o RX cru só traz o resumo no primeiro fragmento (o L1), sem `cmd/key` nem CRC.
	 */
	private fun logFrame(message: String) {
		log(LogLevel.DEBUG, "frame", message)
	}

	/** Pedido em andamento: espera de ACK (`true` = ACK, `false` = NACK) e, nos GETs, da resposta `key + 1`. */
	private class PendingRequest(val cmd: Int, val responseKey: Int?, val sequence: Int) {
		val acked = CompletableDeferred<Boolean>()
		val response = CompletableDeferred<SmaFrame>()
	}

	/** Resultado de uma tentativa de envio. */
	private sealed interface Outcome {
		class Success(val response: SmaFrame?) : Outcome

		class Failure(val error: WatchException, val retryable: Boolean) : Outcome
	}

	companion object {
		/** Fase reportada em `onPhase` durante o BIND. */
		const val PHASE_BIND: String = "BIND"

		/** Fase reportada em `onPhase` durante o LOGIN. */
		const val PHASE_LOGIN: String = "LOGIN"

		/** Teto de páginas por leitura de histórico (20 000 registros, mais de dois anos de amostras horárias). */
		const val MAX_PAGES: Int = 1000

		private const val NOTICE_BUFFER = 64

		private val EMPTY = ByteArray(0)

		private fun monotonicMillis(): () -> Long {
			val start = TimeSource.Monotonic.markNow()
			return { start.elapsedNow().inWholeMilliseconds }
		}
	}
}
