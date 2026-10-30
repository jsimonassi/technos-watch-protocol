package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.Battery
import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.DeviceInfo
import br.com.grupotechnos.watch.protocol.domain.SleepStageType
import br.com.grupotechnos.watch.protocol.testing.RecordingLoader
import br.com.grupotechnos.watch.protocol.testing.REPLAY_ADDRESS
import br.com.grupotechnos.watch.protocol.testing.ReplayAction
import br.com.grupotechnos.watch.protocol.testing.ReplayScenario
import br.com.grupotechnos.watch.protocol.testing.SessionEvent
import br.com.grupotechnos.watch.protocol.testing.runReplay
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A primeira gravação do fitGPS 0.2.1 num iPhone ([IOS_FIT_GPS_RECORDING], 2026-10-30) pela sessão inteira, com o que
 * ela trouxe de novo: a primeira página de sono real (`05/46`, 9 registros).
 *
 * **Sono.** O relógio ficou parado na mesa a noite toda, sem ser usado (o usuário não dormiu com ele), e mesmo assim
 * gravou uma noite: START às 21:58:00 de 29/10, estágios a partir de 00:09:38 e registros até 09:30:05, minutos antes do
 * Sync, sem nenhum END. O resultado esperado é o da SDK: o `SmaSleep.java` da SDK SMA decompilada, rodado à parte sobre
 * os mesmos 9 registros, deu os pontos LIGHT 21:58:00, DEEP 22:13:00, AWAKE 00:09:38, LIGHT 00:14:38, DEEP 00:24:38,
 * LIGHT 09:00:00, LIGHT 09:15:00, AWAKE 09:29:29 e END 09:30:05, e `getSleepStatus` = `[692, 632, 54, 6]`
 * (`docs/sma-dados.md`, seção 6). O 09:29:29 chega depois do 09:29:38 na página e é ordenado antes, como na SDK.
 *
 * Os passos da mesma gravação (o `mode 16` com o total de ontem) estão no `RealFixturesStepsTest`.
 *
 * Está no `commonTest`: roda nos quatro targets e imprime uma linha `SLEEP` no `system-out` do relatório XML, ao lado
 * da linha `REPLAY` do `RealFixturesReplayTest` (`docs/fixtures.md`, seção 4.1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealFixturesSleepTest {
	private val zone = "America/Sao_Paulo"

	@Test
	fun iosRecordingGivesTheNightTheSdkGives() = runTest {
		val recording = RecordingLoader.loadFixture(IOS_FIT_GPS_RECORDING)
		val actions = ReplayScenario.deduce(recording)
		val outcome = runReplay(recording, actions)
		try {
			assertEquals(
				listOf(
					ReplayAction.Pair,
					ReplayAction.ReadDeviceInfo,
					ReplayAction.ReadDeviceInfo,
					ReplayAction.ReadBattery,
					ReplayAction.SetTime,
					ReplayAction.Sync(listOf(DataKind.STEPS)),
					ReplayAction.Sync(listOf(DataKind.STEPS, DataKind.SLEEP)),
				),
				actions,
			)
			assertEquals(emptyList(), outcome.replay.problems())
			assertEquals(22, outcome.replay.recordedTx)
			assertEquals(22, outcome.replay.matchedTx)
			assertEquals(ConnectionState.READY, outcome.session.state)
			val ids = outcome.requestIds.map { it.second }
			assertTrue("HANDSHAKING[BIND]" in outcome.listener.stateTrail())
			assertEquals(SessionEvent.Completed(ids[0]), outcome.listener.terminal(ids[0]))
			assertEquals(
				SessionEvent.Info(ids[1], DeviceInfo("fitgps", REPLAY_ADDRESS, "Mormaii_fitGPS", "0.2.1", null)),
				outcome.listener.terminal(ids[1]),
			)
			assertEquals(SessionEvent.BatteryLevel(ids[3], Battery(35)), outcome.listener.terminal(ids[3]))
			assertEquals(SessionEvent.SyncFinished(ids[6]), outcome.listener.terminal(ids[6]))

			val night = outcome.session.getSleepSessions(3).single()
			assertEquals("2026-10-30", night.dayKey)
			assertEquals("2026-10-29T21:58:00-03:00", night.startIso)
			assertEquals("2026-10-30T09:30:05-03:00", night.endIso)
			assertEquals(
				listOf(
					Triple(SleepStageType.LIGHT, "2026-10-29T21:58:00-03:00", "2026-10-29T22:13:00-03:00"),
					Triple(SleepStageType.DEEP, "2026-10-29T22:13:00-03:00", "2026-10-30T00:09:38-03:00"),
					Triple(SleepStageType.AWAKE, "2026-10-30T00:09:38-03:00", "2026-10-30T00:14:38-03:00"),
					Triple(SleepStageType.LIGHT, "2026-10-30T00:14:38-03:00", "2026-10-30T00:24:38-03:00"),
					Triple(SleepStageType.DEEP, "2026-10-30T00:24:38-03:00", "2026-10-30T09:00:00-03:00"),
					// Os dois LIGHT da SDK (09:00:00 e 09:15:00) viram um estágio só.
					Triple(SleepStageType.LIGHT, "2026-10-30T09:00:00-03:00", "2026-10-30T09:29:29-03:00"),
					Triple(SleepStageType.AWAKE, "2026-10-30T09:29:29-03:00", "2026-10-30T09:30:05-03:00"),
				),
				night.stages.map { Triple(it.type, it.startIso, it.endIso) },
			)
			night.stages.forEach { stage ->
				assertEquals(stage.startIso, TimeFormat.isoWithOffset(stage.startEpochMillis, zone))
				assertEquals(stage.endIso, TimeFormat.isoWithOffset(stage.endEpochMillis, zone))
			}
			// getSleepStatus da SDK: [692, 632, 54, 6]; o SMA não tem REM.
			assertEquals(
				listOf(692, 632, 54, 0, 6),
				listOf(night.totalMinutes, night.deepMinutes, night.lightMinutes, night.remMinutes, night.awakeMinutes),
			)
			println(
				"SLEEP $IOS_FIT_GPS_RECORDING: ${night.dayKey} ${night.startIso} -> ${night.endIso}, total ${night.totalMinutes}, " +
					"deep ${night.deepMinutes}, light ${night.lightMinutes}, awake ${night.awakeMinutes}, ${night.stages.size} stages",
			)
		} finally {
			outcome.session.close()
			runCurrent()
		}
	}
}

/** A gravação do fitGPS 0.2.1 feita no iPhone (2026-10-30), caminho relativo a `fixtures/`. */
internal const val IOS_FIT_GPS_RECORDING: String = "real/fitgps/0.2.1/ios-pair-info-time-steps-sleep-sync.jsonl"
