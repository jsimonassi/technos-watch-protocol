package br.com.grupotechnos.watch.protocol.ido

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
import br.com.grupotechnos.watch.protocol.transport.WriteType
import br.com.grupotechnos.watch.protocol.util.Hex
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlin.concurrent.Volatile

/**
 * Driver da família iDO (Mormaii Life Ultra GPS) sobre um [DriverLink], com o protocolo legado de frames de até 20
 * bytes (`docs/protocolo-ido.md`). Tudo o que ele faz foi conferido contra o log da SDK iDO 2.69.38 de produção com o
 * mesmo relógio (`20261029.log`, 2026-10-29; as linhas estão no KDoc de cada comando em [IdoCommand] e nos payloads).
 *
 * **Envio.** Um frame por escrita, sempre [WriteType.WITHOUT_RESPONSE] (ATT Write Command), que é o que a SDK usa em
 * produção; o link da sessão espera a confirmação do host (`onCharacteristicWrite`) antes da escrita seguinte, como a
 * fila da SDK. Um pedido de cada vez: a sessão serializa as chamadas.
 *
 * **Recepção.** [start] consome `link.notifications` (as characteristics `0AF7` e `0AF2`). A resposta repete o
 * `[cmd][key]` do pedido em andamento ([IdoFrameCodec]); pacotes do protocolo V3 (`0x33`) e frames que não respondem ao
 * pedido em andamento vão para o log em DEBUG e são descartados. O driver não produz avisos ([notices] é vazio).
 *
 * **Pedidos.** Cada um espera a resposta em `quirks.commandTimeoutMs`; leituras e acerto de hora têm uma retentativa
 * em timeout (a SDK reenvia até 3 vezes); bind e autenticação não ([IdoCommand.retryable]) e esperam
 * `quirks.bindTimeoutMs`, porque a resposta da autenticação só sai depois do toque na tela do relógio.
 *
 * **Handshake.**
 * - [HandshakeMode.FIRST_PAIR] (fase `BIND`): `02/01`, `04/01` e, quando o relógio manda o desafio de 12 bytes, a
 *   resposta `04/05` ([IdoAuth]); o vínculo vale quando chega `04/05` com `auth_code` 0 (linhas 87-165 do log).
 *   `bind_ret_code` 2 (já vinculado) também vale, como na SDK; outro código, `auth_code` diferente de 0 ou timeout é
 *   recusa (`HANDSHAKE_FAILED`, pela sessão). Depois de uma recusa o relógio não aceita outro bind no mesmo link: a
 *   sessão já fecha o link quando o pareamento falha.
 * - [HandshakeMode.RECONNECT] (fase `LOGIN`): só `02/01`, e o relógio tem de responder `pairFlag` 1. Este relógio não
 *   pede autenticação na reconexão (`ex_table_main8_encrypted_auth` e `support_encrypted_code_resend_04_05` falsos na
 *   tabela de funções, linhas 592 e 789). `pairFlag` 0 quer dizer que o relógio desfez o vínculo do lado dele: falha com
 *   `HANDSHAKE_FAILED`, como o `onDeviceInNotBindStatus` da SDK, e a sessão desiste sem reconectar sozinha.
 *
 * **Passos.** A PoC lê só os totais do dia ao vivo (`02/A0`); o histórico por hora da SDK vem do protocolo V3, fora da
 * PoC. [readSteps] devolve uma amostra no início da hora local corrente com o acumulado do dia: relida na mesma hora, a
 * amostra é substituída (a sessão mescla pelo instante), e o perfil soma os dias em `DAY_CUMULATIVE`, então
 * `refreshSteps` e `syncHistory` dão os passos e a distância do dia que a tela do relógio mostra. As calorias são as
 * totais do `02/A0` (repouso + atividade), não as de atividade da tela ([IdoLiveData]).
 *
 * **Fora.** Sono (sem bytes no legado; lista vazia), avisos de mudança de dados, desvínculo (os bytes não aparecem no log
 * da SDK, que monta o comando na biblioteca nativa; [unbind] falha sem escrever nada) e o protocolo V3.
 */
internal class IdoDriver(private val link: DriverLink) : DeviceDriver {
	private val quirks = link.profile.quirks

	@Volatile
	private var pending: PendingRequest? = null

	@Volatile
	private var closed = false

	private var receiveJob: Job? = null

	override val notices: Flow<DriverNotice> = emptyFlow()

	override fun start(scope: CoroutineScope) {
		if (receiveJob != null || closed) return
		receiveJob = scope.launch { receiveLoop() }
	}

	override fun describe(value: ByteArray): String = IdoFrameCodec.describe(value)

	override suspend fun handshake(mode: HandshakeMode, onPhase: (String) -> Unit) {
		when (mode) {
			HandshakeMode.FIRST_PAIR -> {
				onPhase(PHASE_BIND)
				val info = readBasicInfo()
				log(LogLevel.INFO, "handshake", "basic info before bind: ${describe(info)}")
				bind()
			}
			HandshakeMode.RECONNECT -> {
				onPhase(PHASE_LOGIN)
				val info = readBasicInfo()
				if (!info.bound) {
					throw WatchException(
						WatchErrorCodes.HANDSHAKE_FAILED,
						"watch reports pairFlag ${info.pairFlag} (not bound on its side); pair again",
					)
				}
				log(LogLevel.INFO, "handshake", "reconnected to a bound watch: ${describe(info)}")
			}
		}
	}

	override suspend fun readDeviceInfo(): DriverDeviceInfo {
		val info = readBasicInfo()
		val firmware = IdoFirmwarePayload.decode(request(IdoCommand.GET_FIRMWARE))
		log(LogLevel.INFO, "readDeviceInfo", "firmware $firmware, ${describe(info)}")
		// Os bytes da pilha BT3 (5-11 do 02/EB) não são lidos: neste relógio vêm zerados (BT_flag 0, linhas 5777-5788).
		return DriverDeviceInfo(firmware = firmware, bleVersion = null)
	}

	override suspend fun readBattery(): Int {
		val info = readBasicInfo()
		val percent = info.batteryPercent
		if (percent > 100) log(LogLevel.WARN, "readBattery", "battery $percent% above 100, clamped")
		return percent.coerceIn(0, 100)
	}

	override suspend fun setTime(nowEpochMillis: Long, timeZoneId: String) {
		val local = TimeFormat.toLocalWallClock(nowEpochMillis, timeZoneId)
		val offsetMillis = TimeZone.of(timeZoneId).offsetAt(Instant.fromEpochMilliseconds(nowEpochMillis)).totalSeconds * 1000L
		val payload = try {
			IdoTimeCodec.encode(local, offsetMillis, fractionalZone = FRACTIONAL_TIME_ZONE)
		} catch (error: IllegalArgumentException) {
			throw WatchException(WatchErrorCodes.PROTOCOL, "Phone time $local cannot be encoded: ${error.message}", error)
		}
		val reply = request(IdoCommand.SET_TIME, payload)
		if (!IdoTimeCodec.accepted(reply)) {
			throw WatchException(WatchErrorCodes.PROTOCOL, "SET_TIME refused, status=${reply.u8(2)}")
		}
		log(LogLevel.INFO, "setTime", "watch time set to ${TimeFormat.formatLocal(local)} ($timeZoneId)")
	}

	override suspend fun enableDataChangeNotices() {
		log(LogLevel.INFO, "enableDataChangeNotices", "iDO legacy protocol has no data-change notice in the PoC; nothing sent")
	}

	override suspend fun readSteps(): List<StepsSample> {
		val live = IdoLiveData.decode(request(IdoCommand.GET_LIVE_DATA))
		val zone = link.clock.timeZoneId()
		val now = TimeFormat.toLocalWallClock(link.clock.nowEpochMillis(), zone)
		val hourStart = TimeFormat.fromLocalWallClock(LocalDateTime(now.year, now.monthNumber, now.dayOfMonth, now.hour, 0, 0), zone)
		log(LogLevel.INFO, "readSteps", "live day totals: ${live.steps} steps, ${live.calories} kcal, ${live.distanceMeters} m")
		return listOf(
			StepsSample(
				atEpochMillis = hourStart,
				steps = live.steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
				mode = LIVE_MODE,
				calories = live.calories.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
				distanceMeters = live.distanceMeters.toDouble(),
			),
		)
	}

	override suspend fun readSleep(): List<SleepSession> {
		log(LogLevel.INFO, "readSleep", "sleep needs the V3 sync, outside the PoC; no records")
		return emptyList()
	}

	override suspend fun unbind() {
		throw WatchException(
			WatchErrorCodes.PROTOCOL,
			"iDO unbind bytes are unknown (built inside the vendor native library); nothing sent, the watch keeps its bond",
		)
	}

	override fun close() {
		if (closed) return
		closed = true
		failPending("driver closed")
		receiveJob?.cancel()
	}

	private suspend fun readBasicInfo(): IdoBasicInfo = IdoBasicInfo.decode(request(IdoCommand.GET_BASIC_INFO))

	private suspend fun bind() {
		val reply = IdoBindReply.decode(request(IdoCommand.BIND, IdoBindPayload.REQUEST, quirks.bindTimeoutMs))
		when (reply.code) {
			IdoBindPayload.CODE_ALREADY_BOUND -> {
				log(LogLevel.INFO, "handshake", "watch answered bind_ret_code 2 (already bound); bind done")
				return
			}
			IdoBindPayload.CODE_SUCCESS -> Unit
			else -> throw WatchException(WatchErrorCodes.HANDSHAKE_FAILED, "bind refused, bind_ret_code=${reply.code}")
		}
		val challenge = reply.challenge
		if (challenge == null) {
			log(LogLevel.INFO, "handshake", "bind accepted without encrypted auth (auth_length ${reply.authLength})")
			return
		}
		log(LogLevel.INFO, "handshake", "bind accepted; answering the auth challenge and waiting for the confirmation on the watch")
		val auth = request(IdoCommand.BIND_AUTH, IdoAuth.reply(reply.encryptedVersion, challenge), quirks.bindTimeoutMs)
		val code = IdoAuth.authCode(auth)
		if (code != 0) throw WatchException(WatchErrorCodes.HANDSHAKE_FAILED, "bind auth refused, auth_code=$code")
		log(LogLevel.INFO, "handshake", "bind confirmed on the watch")
	}

	private suspend fun request(
		command: IdoCommand,
		payload: ByteArray = EMPTY,
		responseTimeoutMs: Long = quirks.commandTimeoutMs,
	): IdoFrame {
		val bytes = IdoFrameCodec.encode(command.cmd, command.key, payload)
		val attempts = if (command.retryable) 2 else 1
		for (attempt in 1..attempts) {
			if (closed) throw WatchException(WatchErrorCodes.DISCONNECTED, "iDO driver is closed")
			val request = PendingRequest(command.cmd, command.key)
			// Antes da escrita: a resposta pode chegar antes do callback dela (`20261029.log` linhas 96, 120 e 121).
			pending = request
			try {
				logFrame("TX ${IdoCommand.label(command.cmd, command.key)} len=${bytes.size}")
				link.write(bytes, WriteType.WITHOUT_RESPONSE)
				val response = withTimeoutOrNull(responseTimeoutMs) { request.response.await() }
				if (response != null) return response
				if (attempt < attempts) log(LogLevel.WARN, "request", "no response for ${command.name} after $responseTimeoutMs ms; retrying once")
			} finally {
				if (pending === request) pending = null
			}
		}
		throw WatchException(WatchErrorCodes.TIMEOUT, "no response for ${command.name} after $responseTimeoutMs ms")
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
			failPending("notification channel closed")
		}
	}

	private fun handlePacket(packet: ByteArray) {
		when (val decoded = IdoFrameCodec.decode(packet)) {
			IdoDecoded.V3 -> log(LogLevel.DEBUG, "receive", "V3 packet ignored (${packet.size} bytes)")
			IdoDecoded.TooShort -> log(LogLevel.DEBUG, "receive", "packet too short ignored: ${Hex.encode(packet)}")
			is IdoDecoded.Frame -> {
				val frame = decoded.frame
				logFrame("RX ${IdoCommand.label(frame.cmd, frame.key)} len=${packet.size}")
				val request = pending
				if (request != null && frame.cmd == request.cmd && frame.key == request.key) {
					request.response.complete(frame)
				} else {
					log(LogLevel.DEBUG, "receive", "unsolicited ${IdoCommand.label(frame.cmd, frame.key)} ignored: ${Hex.encode(packet)}")
				}
			}
		}
	}

	private fun failPending(reason: String) {
		pending?.response?.completeExceptionally(WatchException(WatchErrorCodes.DISCONNECTED, reason))
	}

	private fun describe(info: IdoBasicInfo): String =
		"deviceId=${info.deviceId} battery=${info.batteryPercent}% battStatus=${info.batteryStatus} pairFlag=${info.pairFlag} " +
			"platform=${info.platform} gpsPlatform=${info.gpsPlatform}"

	private fun log(level: LogLevel, method: String, message: String) {
		link.logger.log(level, "IdoDriver/$method", message)
	}

	/**
	 * Uma linha `IdoDriver/frame` em DEBUG por frame em cada sentido (`TX 02/01 GET_BASIC_INFO len=2`). Os pacotes crus
	 * saem uma vez só, pelo `WatchLogger.frame` do link da sessão, que é a fonte das gravações.
	 */
	private fun logFrame(message: String) {
		log(LogLevel.DEBUG, "frame", message)
	}

	/** Pedido em andamento: a resposta é o primeiro frame com o mesmo `cmd/key`. */
	private class PendingRequest(val cmd: Int, val key: Int) {
		val response = CompletableDeferred<IdoFrame>()
	}

	companion object {
		/** Fase reportada em `onPhase` no primeiro pareamento (informação básica, bind e confirmação na tela). */
		const val PHASE_BIND: String = "BIND"

		/** Fase reportada em `onPhase` na reconexão (só a informação básica). */
		const val PHASE_LOGIN: String = "LOGIN"

		/** `mode` da amostra de passos ao vivo (o mesmo `mode 0` de registro normal do SMA). */
		const val LIVE_MODE: Int = 0

		/**
		 * Fuso em centésimos de hora no `03/01`: o Life Ultra GPS, único perfil iDO, tem `v2_support_set_time_zone_float`
		 * (linha 931). Um modelo iDO sem esse recurso precisaria de um quirk.
		 */
		const val FRACTIONAL_TIME_ZONE: Boolean = true

		private val EMPTY = ByteArray(0)
	}
}
