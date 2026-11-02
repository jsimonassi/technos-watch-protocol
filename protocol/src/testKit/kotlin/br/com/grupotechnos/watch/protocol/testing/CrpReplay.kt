package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.crp.CrpCommand
import br.com.grupotechnos.watch.protocol.crp.CrpFrameCodec
import br.com.grupotechnos.watch.protocol.crp.CrpGatt
import br.com.grupotechnos.watch.protocol.crp.CrpReassembler
import br.com.grupotechnos.watch.protocol.crp.CrpStepsTotals
import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.session.InMemorySampleStore
import br.com.grupotechnos.watch.protocol.transport.GattUuid
import kotlinx.coroutines.test.TestScope

/**
 * Deduz, dos pedidos de uma gravação CRP feita com esta sessão, as chamadas que o host fez (par do [ReplayScenario], que
 * é só do SMA, e do [IdoReplayScenario]).
 *
 * Um pedido é um frame TX (remontado) ou uma leitura (READ). Rótulos: `"R:2a29"`, `"R:2a28"`, `"R:2a19"`, `"R:fee1"`
 * para as leituras e `cmd[/sub]` para os frames (`"5A/00"`, `"B7/0E"`, `"BB/16"`, `"31"`, `"BB/07"`, `"59/00"`,
 * `"33/01"`, `"32"`, `"F8"`). Regras, na ordem:
 * - `[R:2a29] 5A/00 [B7/0E BB/16]` é um handshake: o primeiro da gravação é [ReplayAction.Pair]; um no meio é
 *   [ReplayAction.Pair] depois de um `F8` e [ReplayAction.BluetoothCycle] nos outros casos.
 * - O primeiro `31 BB/07` depois de um handshake é o acerto de hora automático e não vira ação; nos outros lugares,
 *   [ReplayAction.SetTime].
 * - `R:2a19` → ReadBattery; `5A/01 [R:2a28]` → ReadDeviceInfo; `F8` → Forget.
 * - `R:fee1 59/00 59/01 59/02 59/03 [33/01]` seguido de `32 [33/03 [33/04]]` → `Sync([STEPS, SLEEP])`; sem o `32`,
 *   `Sync([STEPS])`; `32 [33/03 [33/04]]` sozinho → `Sync([SLEEP])`. Um `refreshSteps` gera os mesmos bytes de um
 *   `Sync([STEPS])` ([ReplayAction.RefreshSteps] fica para os roteiros escritos à mão).
 * - **Releitura pedida por FEE1.** Uma notificação de FEE1 com totais diferentes de zero, numa conexão pronta que ainda
 *   não leu as meias horas, faz o `CrpDriver` pedir uma releitura (`DriverNotice.DataChanged`), que a sessão faz sozinha
 *   2 s depois (`refreshSteps` interno). A leitura de passos seguinte da gravação (`R:fee1 59/00 … [33/01]`, sem `32`) é
 *   essa releitura e não vira ação. No replay, os RX saem logo depois do pedido anterior e a releitura acontece dentro do
 *   tempo da ação anterior, antes da ação seguinte; por isso ela precisa ser o pedido seguinte da gravação. Se for outro
 *   pedido (um `F8`, um handshake, um `Sync` com sono, o fim da gravação), o replay não tem como reproduzir a ordem e a
 *   dedução falha com mensagem. FEE1 antes do fim do handshake (o driver ainda não está pronto), depois de um `F8` e
 *   durante uma leitura de passos (o driver ignora) não conta; durante a leitura de um `Sync([SLEEP])` conta (o driver
 *   não está lendo passos), e a releitura vem logo depois do sono. Depois de uma leitura de passos, FEE1 vira passos ao
 *   vivo e não gera pedido. A virada do dia (a outra causa de releitura no driver) não é modelada.
 * - O resto falha com mensagem.
 */
internal object CrpReplayScenario {
	/** Rótulo de cada pedido (TX remontado ou READ), em ordem. */
	fun requests(recording: Recording): List<String> = timeline(recording).requests.map { it.label }

	fun deduce(recording: Recording): List<ReplayAction> {
		val timeline = timeline(recording)
		val steps = timeline.requests
		val requests = steps.map { it.label }
		require(requests.isNotEmpty()) { "no requests in ${recording.source}" }
		val actions = mutableListOf<ReplayAction>()
		var i = 0
		var afterForget = false
		var first = true
		// Estado do driver da conexão atual que decide se FEE1 pede releitura.
		var ready = false
		var halfHoursRead = false
		var refreshAsked = -1

		fun at(offset: Int = 0): String? = requests.getOrNull(i + offset)

		fun isHandshakeStart(): Boolean = at() == PROTOCOL_VERSION || (at() == READ_PROTOCOL && at(1) == PROTOCOL_VERSION)

		/** Aplica as notificações de FEE1 gravadas antes do pedido [index] (ou do fim, com `index == requests.size`). */
		fun liveStepsBefore(index: Int) {
			val live = if (index < steps.size) steps[index].liveStepsBefore else timeline.liveStepsAtTheEnd
			if (live && ready && !halfHoursRead && refreshAsked < 0) refreshAsked = index
		}

		fun requireNoPendingRefresh(what: String) {
			require(refreshAsked < 0) {
				"FEE1 before request $refreshAsked asked for a refresh, but the recording continues with $what at $i; " +
					"the replay cannot place the internal refresh in ${recording.source}"
			}
		}

		fun handshake() {
			if (at() == READ_PROTOCOL) i++
			i++ // 5A/00
			if (at() == SPP && at(1) == EMOJI) i += 2
			ready = true
			if (at() == SET_TIME && at(1) == TIME_ZONE) {
				// Só conta a FEE1 entre o `31` e o `BB 07`: a gravada logo depois do `BB 16` sai, no replay, dentro do atraso
				// daquele envio, com o driver ainda não pronto.
				liveStepsBefore(i + 1)
				i += 2
			}
		}

		fun sleep() {
			i++ // 32
			if (at() == SLEEP_YESTERDAY) {
				// FEE1 no meio da leitura de sono conta: o driver não está lendo passos. Só pede releitura num `Sync([SLEEP])`;
				// num `Sync([STEPS, SLEEP])` as meias horas já foram lidas.
				liveStepsBefore(i)
				i++
				if (at() == SLEEP_TWO_DAYS_AGO) {
					liveStepsBefore(i)
					i++
				}
			}
		}

		while (i < requests.size) {
			if (isHandshakeStart()) {
				// As notificações antes do handshake são da conexão anterior (ou de nenhuma, depois de um F8).
				liveStepsBefore(i)
				requireNoPendingRefresh("a handshake")
				actions += when {
					first || afterForget -> ReplayAction.Pair
					else -> ReplayAction.BluetoothCycle
				}
				first = false
				afterForget = false
				ready = false
				halfHoursRead = false
				handshake()
				continue
			}
			if (first) throw IllegalArgumentException("CRP recording must start with the handshake, got ${at()} in ${recording.source}")
			liveStepsBefore(i)
			if (at() != READ_STEPS) requireNoPendingRefresh(at().toString())
			when {
				at() == SET_TIME && at(1) == TIME_ZONE -> {
					actions += ReplayAction.SetTime
					i++
					liveStepsBefore(i)
					i++
				}
				at() == READ_BATTERY -> {
					actions += ReplayAction.ReadBattery
					i++
				}
				at() == FIRMWARE -> {
					actions += ReplayAction.ReadDeviceInfo
					i++
					if (at() == READ_FIRMWARE) {
						liveStepsBefore(i)
						i++
					}
				}
				at() == READ_STEPS -> {
					i++
					for (page in STEPS_PAGES) {
						require(at() == page) { "steps read expects $page, got ${at()} at request $i in ${recording.source}" }
						i++
					}
					if (at() == STEPS_YESTERDAY) i++
					// FEE1 durante a leitura não conta (o driver ignora) e, depois dela, vira passos ao vivo.
					halfHoursRead = true
					val withSleep = at() == SLEEP_TODAY
					if (withSleep) {
						requireNoPendingRefresh("a sync with sleep")
						sleep()
					}
					if (refreshAsked >= 0) {
						refreshAsked = -1
					} else {
						actions += ReplayAction.Sync(if (withSleep) listOf(DataKind.STEPS, DataKind.SLEEP) else listOf(DataKind.STEPS))
					}
				}
				at() == SLEEP_TODAY -> {
					sleep()
					actions += ReplayAction.Sync(listOf(DataKind.SLEEP))
				}
				at() == REMOVE_BOND -> {
					actions += ReplayAction.Forget
					afterForget = true
					ready = false
					i++
				}
				else -> throw IllegalArgumentException("unsupported CRP request ${at()} at $i in ${recording.source}")
			}
		}
		liveStepsBefore(requests.size)
		requireNoPendingRefresh("the end of the recording")
		return actions
	}

	/** Um pedido e se, entre o pedido anterior e ele, o relógio notificou FEE1 com totais diferentes de zero. */
	private class Request(val label: String, val liveStepsBefore: Boolean)

	/** Os pedidos da gravação e se houve FEE1 com totais depois do último. */
	private class Timeline(val requests: List<Request>, val liveStepsAtTheEnd: Boolean)

	private fun timeline(recording: Recording): Timeline {
		val out = mutableListOf<Request>()
		var live = false
		val tx = CrpReassembler { message -> throw IllegalArgumentException("TX $message in ${recording.source}") }
		for (packet in recording.packets) {
			when (packet.direction) {
				FrameDirection.READ -> {
					out += Request("R:" + shortUuid(packet.characteristic), live)
					live = false
				}
				FrameDirection.TX -> for (frame in tx.accept(packet.value)) {
					out += Request(CrpCommand.label(frame.cmd, frame.payload).substringBefore(' '), live)
					live = false
				}
				FrameDirection.RX -> if (isLiveSteps(packet)) live = true
			}
		}
		return Timeline(out, live)
	}

	private fun isLiveSteps(packet: RecordedPacket): Boolean =
		GattUuid.normalize(packet.characteristic) == CrpGatt.STEPS_TODAY && CrpStepsTotals.decode(packet.value)?.isZero == false

	private fun shortUuid(uuid: String): String {
		val normalized = GattUuid.normalize(uuid)
		return if (normalized.endsWith(BASE_SUFFIX) && normalized.startsWith("0000")) normalized.substring(4, 8) else normalized
	}

	private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
	private val READ_PROTOCOL = "R:" + CrpGatt.PROTOCOL_VERSION.substring(4, 8)
	private val READ_FIRMWARE = "R:" + CrpGatt.FIRMWARE_REVISION.substring(4, 8)
	private val READ_BATTERY = "R:" + CrpGatt.BATTERY_LEVEL.substring(4, 8)
	private val READ_STEPS = "R:" + CrpGatt.STEPS_TODAY.substring(4, 8)
	private const val PROTOCOL_VERSION = "5A/00"
	private const val FIRMWARE = "5A/01"
	private const val SPP = "B7/0E"
	private const val EMOJI = "BB/16"
	private const val SET_TIME = "31"
	private const val TIME_ZONE = "BB/07"
	private val STEPS_PAGES = listOf("59/00", "59/01", "59/02", "59/03")
	private const val STEPS_YESTERDAY = "33/01"
	private const val SLEEP_TODAY = "32"
	private const val SLEEP_YESTERDAY = "33/03"
	private const val SLEEP_TWO_DAYS_AGO = "33/04"
	private const val REMOVE_BOND = "F8"
}

/**
 * Máscara CRP para o replay estrito: no frame `31` (acerto de hora, `len` `0x0A`) zera os bytes 5 a 8, os segundos que
 * dependem do relógio do replay (parado no `recordedAt`). O byte `08` e todos os outros frames (inclusive o fuso
 * `BB 07 00`, que depende só do fuso do cabeçalho) são comparados inteiros. Todo frame da fase 1 cabe num pacote
 * (bloco de 20 ou 244), então a máscara não guarda estado.
 */
internal class CrpTxMasker : PacketMasker {
	override fun mask(packet: ByteArray): ByteArray {
		val out = packet.copyOf()
		val isSetTime = CrpFrameCodec.totalLength(out) == SET_TIME_LENGTH && out.size >= SET_TIME_LENGTH &&
			(out[4].toInt() and 0xFF) == CrpCommand.SET_TIME
		if (isSetTime) for (i in 5..8) out[i] = 0
		return out
	}

	private companion object {
		/** `FE EA b2 0A 31` + 4 bytes de segundos + `08`. */
		const val SET_TIME_LENGTH = 0x0A
	}
}

/**
 * [runReplay] de uma gravação CRP: perfil Condor Reebok, máscara [CrpTxMasker] e o [FakeTransport] com a descoberta do
 * P85 ([FakeTransport.crp]) e as notificações em `fee3`. As leituras são respondidas pelos READ gravados e cada RX sai na
 * characteristic gravada ([ReplayDriver]).
 */
internal fun TestScope.runCrpReplay(
	recording: Recording,
	actions: List<ReplayAction> = CrpReplayScenario.deduce(recording),
	strict: Boolean = true,
	store: InMemorySampleStore = InMemorySampleStore(),
): ReplayOutcome =
	runReplay(
		recording,
		actions = actions,
		strict = strict,
		store = store,
		profileId = Profiles.CONDOR_REEBOK.id,
		newMasker = { CrpTxMasker() },
		configure = { transport ->
			transport.services = FakeTransport.crp().services
			transport.notifyCharacteristic = Profiles.CRP_FEEA.notify
		},
	)
