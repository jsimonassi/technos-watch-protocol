package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.Battery
import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.DeviceInfo
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.profile.StepsAggregation
import br.com.grupotechnos.watch.protocol.sma.SmaEstimates
import br.com.grupotechnos.watch.protocol.testing.REPLAY_ADDRESS
import br.com.grupotechnos.watch.protocol.testing.Recording
import br.com.grupotechnos.watch.protocol.testing.RecordingLoader
import br.com.grupotechnos.watch.protocol.testing.ReplayAction
import br.com.grupotechnos.watch.protocol.testing.ReplayScenario
import br.com.grupotechnos.watch.protocol.testing.SessionEvent
import br.com.grupotechnos.watch.protocol.testing.SyntheticFitGpsFixture
import br.com.grupotechnos.watch.protocol.testing.runReplay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Critério A2a do plano: a sessão inteira (FakeTransport → SessionEngine → SmaDriver → codec) reproduz a gravação
 * sintética do fitGPS, sem nenhum TX fora do esperado, e chega nos totais de passos conferidos de forma independente
 * (`docs/fixtures.md`, seção 5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmaSessionReplayTest {
	private val fixture: File = File(RecordingLoader.fixturesDir(), SyntheticFitGpsFixture.RELATIVE_PATH)

	private val actions = listOf(
		ReplayAction.Pair,
		ReplayAction.SetTime,
		ReplayAction.ReadBattery,
		ReplayAction.ReadDeviceInfo,
		ReplayAction.Sync(listOf(DataKind.STEPS, DataKind.SLEEP)),
	)

	@Test
	fun committedFixtureMatchesTheGenerator() {
		val generated = SyntheticFitGpsFixture.build().toJsonl()
		if (System.getenv("FIXTURES_REGENERATE") == "1") {
			fixture.parentFile.mkdirs()
			fixture.writeText(generated)
		}
		assertEquals(generated, fixture.readText(), "regenerate with FIXTURES_REGENERATE=1 ./gradlew :protocol:jvmTest")
	}

	@Test
	fun scenarioIsDeducedFromTheTxFrames() {
		val recording = RecordingLoader.load(fixture.path)
		assertEquals(
			listOf("03/01", "03/03", "02/01", "02/01", "02/08", "02/0A", "05/41", "05/41", "05/45"),
			ReplayScenario.txFrames(recording),
		)
		assertEquals(actions, ReplayScenario.deduce(recording))
	}

	@Test
	fun pairSetTimeBatteryVersionSyncReplays() = runTest {
		val recording = RecordingLoader.load(fixture.path)
		val outcome = runReplay(recording, actions)
		try {
			assertTrue(outcome.replay.problems().isEmpty(), outcome.replay.problems().joinToString("\n"))
			assertTrue(outcome.replay.remaining.isEmpty(), "unconsumed: ${outcome.replay.remaining}")
			assertEquals(
				listOf(
					"CONNECTING", "CONFIGURING", "HANDSHAKING", "HANDSHAKING[BIND]", "HANDSHAKING[LOGIN]", "READY",
					"SYNCING", "READY",
				),
				outcome.listener.stateTrail(),
			)
			val ids = outcome.requestIds.map { it.second }
			assertEquals(SessionEvent.Completed(ids[0]), outcome.listener.terminal(ids[0]))
			assertEquals(SessionEvent.Completed(ids[1]), outcome.listener.terminal(ids[1]))
			assertEquals(SessionEvent.BatteryLevel(ids[2], Battery(85)), outcome.listener.terminal(ids[2]))
			assertEquals(
				SessionEvent.Info(ids[3], DeviceInfo("fitgps", REPLAY_ADDRESS, "Mormaii_fitGPS", "1.2.3", "4.5.6")),
				outcome.listener.terminal(ids[3]),
			)
			assertEquals(SessionEvent.SyncFinished(ids[4]), outcome.listener.terminal(ids[4]))
			assertEquals(
				listOf(0, 50, 100),
				outcome.listener.events.filterIsInstance<SessionEvent.SyncProgress>().map { it.percent },
			)

			// Perfil do fitGPS (DAY_CUMULATIVE): cada dia é a amostra de mais passos, com as estimativas dela.
			val days = outcome.session.getStepsDays(3)
			val expected = SyntheticFitGpsFixture.EXPECTED_STEPS
			assertEquals(expected.keys.toList(), days.map { it.dayKey })
			assertEquals(expected.values.toList(), days.map { it.steps })
			assertEquals(listOf("2026-10-27T00:00:00-03:00", "2026-10-28T00:00:00-03:00"), days.map { it.startOfDayIso })
			assertEquals(expected.values.map { SmaEstimates.calories(it) }, days.map { it.calories })
			assertEquals(expected.values.map { SmaEstimates.distanceMeters(it) }, days.map { it.distanceMeters })
			assertEquals(SyntheticFitGpsFixture.EXPECTED_HOURLY_STEPS, outcome.store.loadSteps().map { it.steps })
			// A soma das horas, regra do Core de produção, contaria o acumulado de novo a cada hora (ADR 1).
			assertEquals(
				SyntheticFitGpsFixture.EXPECTED_STEPS_IF_HOURLY_SUM.values.toList(),
				StepsAggregator.byDay(outcome.store.loadSteps(), StepsAggregation.HOURLY_SUM, recording.header.timeZone).map { it.steps },
			)

			// Sono é exploratório: só confere que a noite gravada vira uma sessão do dia em que a pessoa acordou.
			val sleep = outcome.session.getSleepSessions(1)
			assertEquals(listOf("2026-10-28"), sleep.map { it.dayKey })

			// Cada frame de dados recebido foi confirmado com ACK antes do pedido seguinte (a gravação exige isso).
			val txAcks = outcome.logger.frames.count { it.direction == FrameDirection.TX && it.summary.startsWith("ACK") }
			assertEquals(7, txAcks)
			outcome.listener.assertOneTerminalEach(ids)
			assertTrue(outcome.logger.unexpected().isEmpty(), outcome.logger.unexpected().toString())
		} finally {
			outcome.session.close()
			runCurrent()
		}
	}

	@Test
	fun strictReplayCatchesAMissingOrChangedTx() = runTest {
		val recording = RecordingLoader.load(fixture.path)
		// Uma gravação em que o relógio esperava a versão antes da bateria: a sessão manda bateria primeiro.
		val swapped = Recording(recording.header, swapBatteryAndVersion(recording), "swapped")
		val outcome = runReplay(swapped, actions)
		try {
			assertTrue(outcome.replay.problems().any { it.contains("differs") }, outcome.replay.problems().toString())
		} finally {
			outcome.session.close()
			runCurrent()
		}
	}

	/** Troca o bloco de pacotes do pedido de bateria pelo do pedido de versão. */
	private fun swapBatteryAndVersion(recording: Recording): List<br.com.grupotechnos.watch.protocol.testing.RecordedPacket> {
		val packets = recording.packets
		fun startOf(key: Int) = packets.indexOfFirst {
			it.direction == FrameDirection.TX && it.value.size > 10 && it.value[8].toInt() == 0x02 && it.value[10].toInt() == key
		}
		val battery = startOf(0x08)
		val version = startOf(0x0A)
		val afterVersion = packets.indexOfFirst { it.direction == FrameDirection.TX && it.value.size > 10 && it.value[8].toInt() == 0x05 }
		return packets.subList(0, battery) + packets.subList(version, afterVersion) + packets.subList(battery, version) +
			packets.subList(afterVersion, packets.size)
	}
}
