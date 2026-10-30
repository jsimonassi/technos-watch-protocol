package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.session.InMemorySampleStore
import br.com.grupotechnos.watch.protocol.session.WatchSession
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrameCodec
import br.com.grupotechnos.watch.protocol.sma.codec.SmaReassembler
import br.com.grupotechnos.watch.protocol.sma.codec.SmaReassemblyEvent
import br.com.grupotechnos.watch.protocol.sma.commands.SmaCommand
import br.com.grupotechnos.watch.protocol.sma.data.SmaRecordCodec
import br.com.grupotechnos.watch.protocol.sma.data.SmaSleepRecord
import br.com.grupotechnos.watch.protocol.sma.data.SmaSportRecord
import br.com.grupotechnos.watch.protocol.util.ByteReader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/** Uma chamada do host deduzida de uma gravação. */
internal sealed interface ReplayAction {
	data object Pair : ReplayAction
	data object Reconnect : ReplayAction
	data object SetTime : ReplayAction
	data object ReadBattery : ReplayAction
	data object ReadDeviceInfo : ReplayAction
	data class Sync(val kinds: List<DataKind>) : ReplayAction
	data object Forget : ReplayAction

	/**
	 * Bluetooth do telefone desligado e religado, seguido do `reconnect` que o host faz quando o Bluetooth volta
	 * (`CoreBridge.onBluetoothTurnedOn` no `:bridge`, que o `CoreGraph` do `react-native-technos-core-next` chama). Vem
	 * de um `03/03` no meio da gravação.
	 *
	 * Uma queda do link com reconexão automática, ou um `disconnect` seguido de "Reconnect last", geram os mesmos bytes
	 * (só LOGIN e o acerto de hora automático); o ciclo de Bluetooth reproduz todos eles. Pelo contrato B
	 * (`docs/decisoes.md`, ADR 18), o host fica mudo no desligamento: o [FakeTransport] não entrega `onDisconnected`.
	 */
	data object BluetoothCycle : ReplayAction
}

/**
 * Um frame de dados do telefone, já remontado.
 *
 * @property cmdKey `cmd/key` em hexadecimal (`"05/41"`).
 * @property fullPage só nas leituras de histórico (`05/41`, `05/45`): `true` quando a resposta gravada trouxe
 * [SmaRecordCodec.MAX_RECORDS_PER_PAGE] registros, ou seja, quando o driver pediu a página seguinte.
 */
internal data class TxFrame(val cmdKey: String, val fullPage: Boolean)

/**
 * Deduz, dos frames de uma gravação feita com esta sessão, as chamadas que o host fez. É o que permite que toda
 * gravação em `fixtures/real/` vire teste sem roteiro escrito à mão (`docs/fixtures.md`, seção "Replay").
 *
 * Regras, na ordem dos frames de dados TX (ACK/NACK do telefone são ignorados):
 * - `03/01` primeiro → [ReplayAction.Pair]; `03/03` primeiro → [ReplayAction.Reconnect]. Todos os `03/01`/`03/03`
 *   seguidos fazem parte do handshake (retentativa, fallback com USER_ID).
 * - O primeiro `02/01` logo depois de cada handshake é o acerto de hora automático da sessão e não vira ação.
 * - `02/01` → SetTime; `02/08` → ReadBattery; `02/0A` → ReadDeviceInfo; `03/05` → Forget.
 * - `03/03` no meio do arquivo → [ReplayAction.BluetoothCycle]; `03/01` no meio do arquivo (pareamento de novo depois
 *   de um Forget) → [ReplayAction.Pair]. Os dois consomem o handshake e o acerto de hora que vêm junto.
 * - Uma leitura de histórico é uma sequência de `05/41` (ou `05/45`) que termina na primeira página com menos de
 *   [SmaRecordCodec.MAX_RECORDS_PER_PAGE] registros, a mesma regra do `SmaDriver.readPages`. Por isso dois "Refresh
 *   steps" seguidos viram duas ações, e não uma. Passos seguidos de sono → `Sync([STEPS, SLEEP])`; só passos →
 *   `Sync([STEPS])`; só sono → `Sync([SLEEP])`. Um `refreshSteps` gera os mesmos bytes de um `Sync([STEPS])`.
 * - Qualquer outro `cmd/key` falha com mensagem: a gravação tem algo que a sessão não sabe pedir.
 */
internal object ReplayScenario {
	/** `cmd/key` de cada frame de dados TX, remontando frames que ocupam várias escritas. */
	fun txFrames(recording: Recording): List<String> = txRequests(recording).map { it.cmdKey }

	/** Como [txFrames], com o tamanho da página de resposta de cada leitura de histórico ([TxFrame.fullPage]). */
	fun txRequests(recording: Recording): List<TxFrame> {
		val frames = dataFrames(recording)
		return frames.mapIndexedNotNull { index, frame ->
			if (frame.direction != FrameDirection.TX) return@mapIndexedNotNull null
			val recordSize = historyRecordSize(frame.cmd, frame.key)
			val responseSize = if (recordSize == null) null else responseValueSize(frames, index)
			val full = recordSize != null && responseSize != null && responseSize / recordSize == SmaRecordCodec.MAX_RECORDS_PER_PAGE
			TxFrame(SmaFrameCodec.cmdKey(frame.cmd, frame.key), full)
		}
	}

	fun deduce(recording: Recording): List<ReplayAction> {
		val frames = txRequests(recording)
		require(frames.isNotEmpty()) { "no TX frames in ${recording.source}" }
		val actions = mutableListOf<ReplayAction>()
		var i = 0
		actions += when (frames[0].cmdKey) {
			BIND -> ReplayAction.Pair
			LOGIN -> ReplayAction.Reconnect
			else -> throw IllegalArgumentException("recording must start with BIND or LOGIN, got ${frames[0].cmdKey} in ${recording.source}")
		}

		fun skipHandshake() {
			while (i < frames.size && (frames[i].cmdKey == BIND || frames[i].cmdKey == LOGIN)) i++
			if (i < frames.size && frames[i].cmdKey == SET_TIME) i++
		}

		fun readHistory(cmdKey: String) {
			while (i < frames.size && frames[i].cmdKey == cmdKey) {
				val full = frames[i].fullPage
				i++
				if (!full) return
			}
		}

		skipHandshake()
		while (i < frames.size) {
			when (frames[i].cmdKey) {
				SET_TIME -> { actions += ReplayAction.SetTime; i++ }
				"02/08" -> { actions += ReplayAction.ReadBattery; i++ }
				"02/0A" -> { actions += ReplayAction.ReadDeviceInfo; i++ }
				"03/05" -> { actions += ReplayAction.Forget; i++ }
				LOGIN -> { actions += ReplayAction.BluetoothCycle; skipHandshake() }
				BIND -> { actions += ReplayAction.Pair; skipHandshake() }
				SPORT -> {
					readHistory(SPORT)
					val withSleep = i < frames.size && frames[i].cmdKey == SLEEP
					if (withSleep) readHistory(SLEEP)
					actions += ReplayAction.Sync(if (withSleep) listOf(DataKind.STEPS, DataKind.SLEEP) else listOf(DataKind.STEPS))
				}
				SLEEP -> {
					readHistory(SLEEP)
					actions += ReplayAction.Sync(listOf(DataKind.SLEEP))
				}
				else -> throw IllegalArgumentException("unsupported TX ${frames[i].cmdKey} in ${recording.source}")
			}
		}
		return actions
	}

	/** Um frame de dados remontado, de qualquer direção. */
	private class DataFrame(val direction: FrameDirection, val cmd: Int, val key: Int, val valueSize: Int)

	/**
	 * Frames de dados das duas direções, na ordem da gravação; ACK/NACK ficam de fora.
	 *
	 * TX: o telefone escreve cada frame inteiro antes do próximo (mutex do driver), então um pacote que não começa um
	 * frame é erro da gravação. RX: remontado pelo mesmo [SmaReassembler] da sessão, com o `atMs` gravado como relógio;
	 * o que ele descarta (lixo, CRC errado) não vira frame, como na sessão.
	 */
	private fun dataFrames(recording: Recording): List<DataFrame> {
		val frames = mutableListOf<DataFrame>()
		val rx = SmaReassembler(Profiles.MORMAII_FIT_GPS.quirks.fragmentGapTimeoutMs)
		var tx: ByteArray? = null
		for (packet in recording.packets) {
			if (packet.direction == FrameDirection.RX) {
				for (event in rx.accept(packet.value, packet.atMs)) {
					if (event is SmaReassemblyEvent.Frame) {
						frames += DataFrame(FrameDirection.RX, event.frame.cmd, event.frame.key, event.frame.value.size)
					}
				}
				continue
			}
			if (tx == null && SmaFrameCodec.isControl(packet.value)) continue
			val data = tx?.plus(packet.value) ?: packet.value
			val total = SmaFrameCodec.frameLength(data)
				?: throw IllegalArgumentException("TX packet does not start a frame: $packet in ${recording.source}")
			if (data.size < total) {
				tx = data
				continue
			}
			tx = null
			frames += DataFrame(FrameDirection.TX, ByteReader.u8At(data, 8), ByteReader.u8At(data, 10), ByteReader.u16At(data, 11))
		}
		return frames
	}

	/** Tamanho do value da resposta (`cmd`, `key + 1`) ao pedido em [index], antes do próximo frame TX; `null` sem resposta. */
	private fun responseValueSize(frames: List<DataFrame>, index: Int): Int? {
		val request = frames[index]
		for (next in frames.subList(index + 1, frames.size)) {
			if (next.direction == FrameDirection.TX) return null
			if (next.cmd == request.cmd && next.key == request.key + 1) return next.valueSize
		}
		return null
	}

	/** Tamanho do registro da resposta de uma leitura de histórico, ou `null` se o `cmd/key` não é uma. */
	private fun historyRecordSize(cmd: Int, key: Int): Int? =
		when (SmaCommand.byCmdKey(cmd, key)) {
			SmaCommand.SPORT -> SmaSportRecord.SIZE
			SmaCommand.SLEEP -> SmaSleepRecord.SIZE
			else -> null
		}

	private const val BIND = "03/01"
	private const val LOGIN = "03/03"
	private const val SET_TIME = "02/01"
	private const val SPORT = "05/41"
	private const val SLEEP = "05/45"
}

/** Resultado de [runReplay]. */
internal class ReplayOutcome(
	val session: WatchSession,
	val transport: FakeTransport,
	val replay: ReplayDriver,
	val listener: RecordingListener,
	val logger: SessionTestLogger,
	val store: InMemorySampleStore,
	val clock: TestClock,
	/** `requestId` usado em cada ação, na ordem. */
	val requestIds: List<Pair<ReplayAction, Int>>,
)

/** Endereço usado nos replays. */
internal const val REPLAY_ADDRESS: String = "AA:BB:CC:DD:EE:FF"

/** Tempo virtual dado a cada ação: cobre o `bindTimeoutMs` (60 s) com folga, sem depender de `advanceUntilIdle`. */
private const val ACTION_BUDGET_MS: Long = 120_000

/**
 * Roda [actions] numa `WatchSession` nova cujo relógio é a gravação (via [ReplayDriver]), com a hora do relógio
 * igual a `recordedAt`. Cada ação ganha [ACTION_BUDGET_MS] de tempo virtual; o `advanceUntilIdle` não serve aqui
 * porque uma reconexão que falha agenda tentativas para sempre.
 *
 * O host falso responde ao pedido de MTU com o `mtu` do cabeçalho da gravação, e não com 247: num link de 23 a sessão
 * corta o BIND em 20 + 20 + 5, e só assim os TX batem com o que foi gravado.
 *
 * Não fecha a sessão: o teste confere o que quiser e chama `outcome.session.close()`.
 *
 * @param store histórico com que a sessão começa; o padrão é vazio. Passar o store de outro replay continua o histórico
 * de uma gravação anterior do mesmo relógio (os totais do dia com duas gravações, `RealFixturesStepsTest`).
 * @param configure ajuste do [FakeTransport] antes da primeira ação (o teste do iOS entrega os RX pela sobrecarga com
 * `NSData`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.runReplay(
	recording: Recording,
	actions: List<ReplayAction> = ReplayScenario.deduce(recording),
	strict: Boolean = true,
	store: InMemorySampleStore = InMemorySampleStore(),
	configure: (FakeTransport) -> Unit = {},
): ReplayOutcome {
	val transport = FakeTransport()
	transport.mtuReply = recording.header.mtu
	configure(transport)
	val replay = ReplayDriver(recording, transport, strict)
	val listener = RecordingListener()
	val logger = SessionTestLogger()
	val clock = TestClock(recording.header.recordedAtEpochMillis, recording.header.timeZone)
	val session = WatchSession(transport, logger, store, clock, StandardTestDispatcher(testScheduler))
	transport.events = session.events
	session.setListener(listener)
	val ids = mutableListOf<Pair<ReplayAction, Int>>()
	var nextId = 1
	for (action in actions) {
		val id = nextId++
		ids += action to id
		val profile = Profiles.MORMAII_FIT_GPS.id
		when (action) {
			ReplayAction.Pair -> session.pair(id, REPLAY_ADDRESS, profile)
			ReplayAction.Reconnect -> session.reconnect(id, REPLAY_ADDRESS, profile)
			ReplayAction.SetTime -> session.setTime(id)
			ReplayAction.ReadBattery -> session.readBattery(id)
			ReplayAction.ReadDeviceInfo -> session.readDeviceInfo(id)
			is ReplayAction.Sync -> session.syncHistory(id, action.kinds)
			ReplayAction.Forget -> session.disconnect(id, forget = true)
			ReplayAction.BluetoothCycle -> {
				transport.setBluetooth(false)
				runCurrent()
				transport.setBluetooth(true)
				// A sessão já reconecta sozinha ao religar; o reconnect do host só assume o pedido da tentativa em curso.
				session.reconnect(id, REPLAY_ADDRESS, profile)
			}
		}
		advanceTimeBy(ACTION_BUDGET_MS)
		runCurrent()
	}
	return ReplayOutcome(session, transport, replay, listener, logger, store, clock, ids)
}
