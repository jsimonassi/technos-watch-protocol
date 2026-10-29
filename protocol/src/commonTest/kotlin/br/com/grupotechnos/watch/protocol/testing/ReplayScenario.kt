package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.session.InMemorySampleStore
import br.com.grupotechnos.watch.protocol.session.WatchSession
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrameCodec
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
}

/**
 * Deduz, dos frames TX de uma gravação feita com esta sessão, as chamadas que o host fez. É o que permite que toda
 * gravação em `fixtures/real/` vire teste sem roteiro escrito à mão (`docs/fixtures.md`, seção "Replay").
 *
 * Regras, na ordem dos frames de dados TX (ACK/NACK do telefone são ignorados):
 * - `03/01` primeiro → [ReplayAction.Pair]; `03/03` primeiro → [ReplayAction.Reconnect]. Todos os `03/01`/`03/03`
 *   seguidos fazem parte do handshake (retentativa, fallback com USER_ID).
 * - O primeiro `02/01` logo depois do handshake é o acerto de hora automático da sessão e não vira ação.
 * - `02/01` → SetTime; `02/08` → ReadBattery; `02/0A` → ReadDeviceInfo; `03/05` → Forget.
 * - Uma sequência de `05/41` (páginas), seguida ou não de `05/45`, → `Sync([STEPS])` ou `Sync([STEPS, SLEEP])`;
 *   `05/45` sozinho → `Sync([SLEEP])`. Um `refreshSteps` gera os mesmos bytes de um `Sync([STEPS])`.
 * - Qualquer outro `cmd/key` falha com mensagem: a gravação tem algo que a sessão não sabe pedir.
 */
internal object ReplayScenario {
	/** `cmd/key` de cada frame de dados TX, remontando frames que ocupam várias escritas. */
	fun txFrames(recording: Recording): List<String> {
		val frames = mutableListOf<String>()
		var buffer: ByteArray? = null
		for (packet in recording.packets) {
			if (packet.direction != FrameDirection.TX) continue
			if (buffer == null && SmaFrameCodec.isControl(packet.value)) continue
			val data = buffer?.plus(packet.value) ?: packet.value
			val total = SmaFrameCodec.frameLength(data)
				?: throw IllegalArgumentException("TX packet does not start a frame: $packet in ${recording.source}")
			if (data.size < total) {
				buffer = data
				continue
			}
			buffer = null
			frames += SmaFrameCodec.cmdKey(ByteReader.u8At(data, 8), ByteReader.u8At(data, 10))
		}
		return frames
	}

	fun deduce(recording: Recording): List<ReplayAction> {
		val frames = txFrames(recording)
		require(frames.isNotEmpty()) { "no TX frames in ${recording.source}" }
		val actions = mutableListOf<ReplayAction>()
		var i = 0
		actions += when (frames[0]) {
			"03/01" -> ReplayAction.Pair
			"03/03" -> ReplayAction.Reconnect
			else -> throw IllegalArgumentException("recording must start with BIND or LOGIN, got ${frames[0]} in ${recording.source}")
		}
		while (i < frames.size && (frames[i] == "03/01" || frames[i] == "03/03")) i++
		if (i < frames.size && frames[i] == "02/01") i++
		while (i < frames.size) {
			when (frames[i]) {
				"02/01" -> { actions += ReplayAction.SetTime; i++ }
				"02/08" -> { actions += ReplayAction.ReadBattery; i++ }
				"02/0A" -> { actions += ReplayAction.ReadDeviceInfo; i++ }
				"03/05" -> { actions += ReplayAction.Forget; i++ }
				"05/41" -> {
					while (i < frames.size && frames[i] == "05/41") i++
					val withSleep = i < frames.size && frames[i] == "05/45"
					while (i < frames.size && frames[i] == "05/45") i++
					actions += ReplayAction.Sync(if (withSleep) listOf(DataKind.STEPS, DataKind.SLEEP) else listOf(DataKind.STEPS))
				}
				"05/45" -> {
					while (i < frames.size && frames[i] == "05/45") i++
					actions += ReplayAction.Sync(listOf(DataKind.SLEEP))
				}
				else -> throw IllegalArgumentException("unsupported TX ${frames[i]} in ${recording.source}")
			}
		}
		return actions
	}
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
 * Não fecha a sessão: o teste confere o que quiser e chama `outcome.session.close()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.runReplay(
	recording: Recording,
	actions: List<ReplayAction> = ReplayScenario.deduce(recording),
	strict: Boolean = true,
): ReplayOutcome {
	val transport = FakeTransport()
	val replay = ReplayDriver(recording, transport, strict)
	val listener = RecordingListener()
	val logger = SessionTestLogger()
	val store = InMemorySampleStore()
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
		}
		advanceTimeBy(ACTION_BUDGET_MS)
		runCurrent()
	}
	return ReplayOutcome(session, transport, replay, listener, logger, store, clock, ids)
}
