package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.Battery
import br.com.grupotechnos.watch.protocol.domain.ConnectionState.READY
import br.com.grupotechnos.watch.protocol.domain.ConnectionState.SYNCING
import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.DeviceInfo
import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.StepsSample
import br.com.grupotechnos.watch.protocol.domain.WatchErrorCodes
import br.com.grupotechnos.watch.protocol.session.SessionHarness.Companion.oct
import br.com.grupotechnos.watch.protocol.session.SessionHarness.Companion.sleepPage
import br.com.grupotechnos.watch.protocol.session.SessionHarness.Companion.sportPage
import br.com.grupotechnos.watch.protocol.sma.SmaEstimates
import br.com.grupotechnos.watch.protocol.sma.commands.SmaTimeCodec
import br.com.grupotechnos.watch.protocol.sma.data.SmaSleepMode
import br.com.grupotechnos.watch.protocol.testing.SessionEvent
import br.com.grupotechnos.watch.protocol.testing.TestClock
import br.com.grupotechnos.watch.protocol.testing.WatchReply
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchSessionCommandsTest {
	private val zone = "America/Sao_Paulo"

	private fun at(local: LocalDateTime): Long = TimeFormat.fromLocalWallClock(local, zone)

	@Test
	fun commandsBeforeReadyFailWithNotReady() = runSession { h ->
		h.session.readBattery(1)
		h.session.readDeviceInfo(2)
		h.session.setTime(3)
		h.session.refreshSteps(4)
		h.session.syncHistory(5, listOf(DataKind.STEPS))
		h.run()
		for (id in 1..5) assertEquals(WatchErrorCodes.NOT_READY, h.listener.errorCode(id))
		// Também durante o handshake.
		h.watch.on(0x03, 0x01) { WatchReply() }
		h.session.pair(6, ADDRESS, FIT_GPS)
		h.advance(100)
		h.session.readBattery(7)
		h.run()
		assertEquals(WatchErrorCodes.NOT_READY, h.listener.errorCode(7))
		assertTrue(h.watch.receivedOf(0x02, 0x08).isEmpty())
		h.session.close()
	}

	@Test
	fun readDeviceInfoAndBattery() = runSession { h ->
		h.pairReady()
		h.session.readDeviceInfo(2)
		h.session.readBattery(3)
		h.run()
		assertEquals(SessionEvent.Info(2, DeviceInfo(FIT_GPS, ADDRESS, "Mormaii_fitGPS", "1.2.3", "4.5.6")), h.listener.terminal(2))
		assertEquals(SessionEvent.BatteryLevel(3, Battery(85)), h.listener.terminal(3))
		h.session.close()
	}

	@Test
	fun setTimeSendsThePhoneWallClock() = runSession { h ->
		h.pairReady()
		h.clock.nowEpochMillis = at(LocalDateTime(2026, 10, 28, 15, 0, 7))
		h.session.setTime(2)
		h.run()
		assertEquals(SessionEvent.Completed(2), h.listener.terminal(2))
		val sent = h.watch.receivedOf(0x02, 0x01).last()
		assertEquals(LocalDateTime(2026, 10, 28, 15, 0, 7), SmaTimeCodec.decode(sent.value))
		h.session.close()
	}

	@Test
	fun commandsRunOneAtATimeInArrivalOrder() = runSession { h ->
		h.pairReady()
		val before = h.watch.received.size
		h.session.readBattery(2)
		h.session.readDeviceInfo(3)
		h.session.setTime(4)
		h.session.readBattery(5)
		h.run()
		assertEquals(listOf("02/08", "02/0A", "02/01", "02/08"), h.watch.receivedLabels().drop(before))
		assertEquals(listOf(2, 3, 4, 5), h.listener.events.mapNotNull { it.terminalId }.filter { it > 1 })
		h.session.close()
	}

	@Test
	fun dropFailsInFlightAndQueuedCommandsWithDisconnected() = runSession { h ->
		h.pairReady()
		h.watch.on(0x02, 0x08) { WatchReply() }
		h.session.readBattery(2)
		h.session.readDeviceInfo(3)
		h.session.syncHistory(4, emptyList())
		h.advance(1_000)
		h.transport.dropLink()
		h.run()
		for (id in 2..4) assertEquals(WatchErrorCodes.DISCONNECTED, h.listener.errorCode(id))
		assertEquals(READY, h.session.state)
		h.assertNoUnexpectedErrors()
		h.session.close()
	}

	@Test
	fun getTimeoutFailsTheCommandWithoutRetry() = runSession { h ->
		h.pairReady()
		h.watch.on(0x02, 0x08) { WatchReply() }
		h.session.readBattery(2)
		h.advance(4_001)
		assertEquals(WatchErrorCodes.TIMEOUT, h.listener.errorCode(2))
		assertEquals(1, h.watch.receivedOf(0x02, 0x08).size)
		assertEquals(READY, h.session.state)
		h.session.close()
	}

	@Test
	fun refreshStepsSumsTheHoursOfToday() = runSession { h ->
		h.pairReady()
		h.sportPages += sportPage(
			Triple(oct(27, 22), 0, 999),
			Triple(oct(28, 8), 0, 300),
			Triple(oct(28, 9, 10), 0, 400),
			Triple(oct(28, 9, 50), 0, 450),
			Triple(oct(28, 10), 32, 5_000),
		)
		h.session.refreshSteps(2)
		h.run()
		val live = h.listener.terminal(2) as SessionEvent.LiveSteps
		// 09:10 e 09:50 caem na mesma hora: vale o último (450). Mode 32 fica de fora. Ontem não entra.
		assertEquals(750, live.today.steps)
		assertEquals("2026-10-28", live.today.dayKey)
		assertEquals("2026-10-28T00:00:00-03:00", live.today.startOfDayIso)
		assertEquals(SmaEstimates.calories(300) + SmaEstimates.calories(450), live.today.calories)
		assertEquals(SmaEstimates.distanceMeters(300) + SmaEstimates.distanceMeters(450), live.today.distanceMeters)
		assertEquals(listOf("2026-10-27", "2026-10-28"), h.session.getStepsDays(1).map { it.dayKey })
		assertEquals(listOf(999, 750), h.session.getStepsDays(1).map { it.steps })
		h.session.close()
	}

	@Test
	fun refreshStepsKeepsSamplesWithWrongDatesOutOfTheHistory() = runSession { h ->
		h.pairReady()
		h.sportPages += sportPage(
			Triple(oct(28, 8), 0, 300),
			// Relógio com a data errada: amanhã, e um dia fora dos 30 de retenção.
			Triple(oct(29, 9), 0, 400),
			Triple(LocalDateTime(2026, 9, 1, 10, 0, 0), 0, 500),
		)
		h.session.refreshSteps(2)
		h.run()
		assertEquals(300, (h.listener.terminal(2) as SessionEvent.LiveSteps).today.steps)
		assertEquals(listOf(300), h.store.loadSteps().map { it.steps })
		assertTrue(h.logger.contains("dropped 2 of 3 step samples: 1 after today (2026-10-28) and more than 3 h ahead, 1 before 2026-09-28"))
		// Quando a data errada chega, ela não aparece como dia pendente.
		h.clock.nowEpochMillis = at(LocalDateTime(2026, 10, 29, 12, 0, 0))
		assertEquals(listOf("2026-10-28"), h.session.getStepsDays(3).map { it.dayKey })
		h.session.close()
	}

	@Test
	fun stepsFromAWatchStillInTheOldTimeZoneAreKeptAcrossMidnight(): TestResult {
		// T6 de `docs/pendencias.md`: o telefone já está em Manaus (UTC-4), 23:30 do dia 28, e o relógio ainda em São
		// Paulo (UTC-3), porque o acerto de hora só vem na próxima conexão. O registro ao vivo sai 00:30 do dia 29 e vira
		// 29 00:00 em Manaus, 30 min à frente de agora: são passos reais, e o relógio não os devolve de novo (H2).
		val manaus = "America/Manaus"
		return runSession(clock = TestClock.at("2026-10-28T23:30:00-04:00", manaus)) { h ->
			h.pairReady()
			h.sportPages += sportPage(
				Triple(oct(29, 0, 30), 0, 120),
				// 03:30 do relógio vira 29 03:00 em Manaus, 3,5 h à frente: acima da folga, é data errada.
				Triple(oct(29, 3, 30), 0, 900),
			)
			h.session.refreshSteps(2)
			h.run()
			assertEquals(0, (h.listener.terminal(2) as SessionEvent.LiveSteps).today.steps)
			assertEquals(
				listOf(TimeFormat.fromLocalWallClock(oct(29, 0), manaus)),
				h.store.loadSteps().map { it.atEpochMillis },
			)
			assertTrue(h.logger.contains("dropped 1 of 2 step samples: 1 after today (2026-10-28) and more than 3 h ahead"))
			assertTrue(h.logger.contains("kept 1 step samples after today (2026-10-28) within 3 h of now"))
			// No dia seguinte, os passos aparecem no dia 29.
			h.clock.nowEpochMillis = TimeFormat.fromLocalWallClock(oct(29, 12), manaus)
			assertEquals(listOf("2026-10-29" to 120), h.session.getStepsDays(3).map { it.dayKey to it.steps })
			h.session.close()
		}
	}

	@Test
	fun syncReportsProgressMergesAndReturnsToReady() = runSession { h ->
		h.pairReady()
		h.sportPages += sportPage(Triple(oct(28, 8), 0, 300), Triple(oct(28, 9), 0, 100))
		h.sleepPages += sleepPage(
			oct(27, 23) to SmaSleepMode.START,
			oct(28, 1) to SmaSleepMode.DEEP,
			oct(28, 3) to SmaSleepMode.LIGHT,
			oct(28, 6, 30) to SmaSleepMode.END,
		)
		h.listener.clear()
		h.session.syncHistory(2, listOf(DataKind.SLEEP, DataKind.STEPS))
		h.run()
		assertEquals(
			listOf(
				SessionEvent.State(SYNCING, null),
				SessionEvent.SyncStarted(2),
				SessionEvent.SyncProgress(2, 0),
				SessionEvent.SyncProgress(2, 50),
				SessionEvent.SyncProgress(2, 100),
				SessionEvent.State(READY, null),
				SessionEvent.SyncFinished(2),
			),
			h.listener.events,
		)
		assertEquals(listOf("05/41", "05/45"), h.watch.receivedLabels().filter { it.startsWith("05/") })
		assertEquals(listOf(at(oct(28, 8)), at(oct(28, 9))), h.store.loadSteps().map { it.atEpochMillis })
		assertEquals(400, h.session.getStepsDays(0).single().steps)
		val sleep = h.session.getSleepSessions(1)
		assertEquals(1, sleep.size)
		assertEquals("2026-10-28", sleep.single().dayKey)
		assertEquals(sleep, h.store.loadSleep())

		// Segunda leitura: a hora 09 mudou e a 10 é nova; a 08 não voltou e continua no store.
		h.sportPages += sportPage(Triple(oct(28, 9), 0, 250), Triple(oct(28, 10), 0, 50))
		h.session.syncHistory(3, listOf(DataKind.STEPS))
		h.run()
		assertTrue(h.listener.events.contains(SessionEvent.SyncProgress(3, 100)))
		assertEquals(SessionEvent.SyncFinished(3), h.listener.terminal(3))
		assertEquals(listOf(300, 250, 50), h.store.loadSteps().map { it.steps })
		assertEquals(600, h.session.getStepsDays(0).single().steps)
		h.session.close()
	}

	@Test
	fun sleepFailureDoesNotFailTheSync() = runSession { h ->
		h.pairReady()
		h.watch.on(0x05, 0x45) { WatchReply() }
		h.sportPages += sportPage(Triple(oct(28, 8), 0, 300))
		h.session.syncHistory(2, emptyList())
		h.advance(4_001)
		assertEquals(SessionEvent.SyncFinished(2), h.listener.terminal(2))
		assertTrue(h.logger.contains("sleep read failed"))
		assertEquals(300, h.session.getStepsDays(0).single().steps)
		assertEquals(READY, h.session.state)
		h.session.close()
	}

	@Test
	fun stepsFailureFailsTheSyncAndRestoresReady() = runSession { h ->
		h.pairReady()
		h.watch.on(0x05, 0x41) { WatchReply() }
		h.session.syncHistory(2, listOf(DataKind.STEPS, DataKind.SLEEP))
		h.advance(4_001)
		assertEquals(WatchErrorCodes.TIMEOUT, h.listener.errorCode(2))
		assertEquals(READY, h.session.state)
		assertTrue(h.watch.receivedOf(0x05, 0x45).isEmpty())
		assertEquals(1, h.watch.receivedOf(0x05, 0x41).size, "GET is never retried")
		h.session.close()
	}

	@Test
	fun dataChangedNoticesAreDebouncedIntoOneRefresh() = runSession { h ->
		h.pairReady()
		h.sportPages += sportPage(Triple(oct(28, 14), 0, 123))
		repeat(3) {
			h.watch.send(h.watch.frame(0x02, 0x70, 0x01))
			h.advance(500)
		}
		assertTrue(h.watch.receivedOf(0x05, 0x41).isEmpty())
		h.advance(1_499)
		assertTrue(h.watch.receivedOf(0x05, 0x41).isEmpty())
		h.advance(2)
		assertEquals(1, h.watch.receivedOf(0x05, 0x41).size)
		val live = h.listener.events.filterIsInstance<SessionEvent.LiveSteps>().single()
		assertEquals(WatchSession.NO_REQUEST_ID, live.requestId)
		assertEquals(123, live.today.steps)
		h.session.close()
	}

	@Test
	fun syncPrunesSamplesOlderThanTheRetention(): TestResult {
		val store = InMemorySampleStore()
		val old = at(LocalDateTime(2026, 9, 27, 10, 0, 0))
		val kept = at(LocalDateTime(2026, 9, 28, 10, 0, 0))
		store.saveSteps(listOf(StepsSample(old, 10, 0, 0, 0.0), StepsSample(kept, 20, 0, 0, 0.0)))
		store.saveConfirmedDays(DataKind.STEPS, listOf("2026-09-27", "2026-09-28"))
		store.saveSleep(listOf(sleepSession("2026-09-20"), sleepSession("2026-10-27")))
		return runSession(store) { h ->
			h.pairReady()
			h.session.syncHistory(2, listOf(DataKind.STEPS))
			h.run()
			// Retenção de 30 dias a partir de 2026-10-28: 2026-09-28 é o mais antigo que fica.
			assertEquals(listOf(kept), store.loadSteps().map { it.atEpochMillis })
			assertEquals(listOf("2026-09-28"), store.loadConfirmedDays(DataKind.STEPS))
			assertEquals(listOf("2026-10-27"), store.loadSleep().map { it.dayKey })
			h.session.close()
		}
	}

	@Test
	fun confirmUploadedHidesDaysImmediatelyAndPersists(): TestResult {
		val store = InMemorySampleStore()
		store.saveSteps(
			listOf(
				StepsSample(at(oct(25, 10)), 10, 0, 1, 1.0),
				StepsSample(at(oct(26, 10)), 20, 0, 1, 1.0),
				StepsSample(at(oct(27, 10)), 30, 0, 1, 1.0),
				StepsSample(at(oct(28, 10)), 40, 0, 1, 1.0),
			),
		)
		return runSession(store) { h ->
			// Carregado do store no construtor, sem relógio conectado.
			assertEquals(listOf("2026-10-25", "2026-10-26", "2026-10-27", "2026-10-28"), h.session.getStepsDays(2).map { it.dayKey })
			h.session.confirmUploaded(DataKind.STEPS, listOf("2026-10-25", "2026-10-26", "2026-10-27", "not-a-day"))
			assertEquals(listOf("2026-10-28"), h.session.getStepsDays(2).map { it.dayKey })
			assertTrue(store.loadConfirmedDays(DataKind.STEPS).isEmpty(), "persisted only on the session thread")
			h.run()
			assertEquals(listOf("2026-10-25", "2026-10-26", "2026-10-27"), store.loadConfirmedDays(DataKind.STEPS).sorted())
			assertTrue(h.logger.contains("ignored invalid day keys"))
			// Sono tem o próprio conjunto.
			assertTrue(store.loadConfirmedDays(DataKind.SLEEP).isEmpty())
			h.session.close()
		}
	}

	@Test
	fun daysBeforeDoesNotHideUnconfirmedDays(): TestResult {
		val store = InMemorySampleStore()
		store.saveSteps(
			listOf(
				StepsSample(at(oct(20, 10)), 10, 0, 1, 1.0),
				StepsSample(at(oct(28, 0)), 5, 0, 1, 1.0),
				StepsSample(at(oct(29, 0)), 7, 0, 1, 1.0),
			),
		)
		return runSession(store, clock = TestClock.at(NOW_ISO)) { h ->
			// Como os Cores atuais: o dia 20 não foi enviado e volta mesmo pedindo 3 dias (ADR 15); o dia 29 é futuro.
			assertEquals(listOf("2026-10-20", "2026-10-28"), h.session.getStepsDays(3).map { it.dayKey })
			h.session.confirmUploaded(DataKind.STEPS, listOf("2026-10-20"))
			assertEquals(listOf("2026-10-28"), h.session.getStepsDays(3).map { it.dayKey })
			// Virou o dia: "hoje" vem do relógio de parede na hora da consulta, e o 29 passa a valer.
			h.clock.nowEpochMillis = at(LocalDateTime(2026, 11, 1, 0, 5, 0))
			assertEquals(listOf("2026-10-28", "2026-10-29"), h.session.getStepsDays(3).map { it.dayKey })
			h.session.close()
		}
	}

	private fun sleepSession(dayKey: String): SleepSession {
		val start = TimeFormat.startOfDayEpochMillis(dayKey, zone)
		return SleepSession(dayKey, start, start + 60_000, "", "", emptyList(), 1, 0, 0, 0, 1)
	}
}
