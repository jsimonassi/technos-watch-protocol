package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.StepsSample
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.log.WatchLogger
import br.com.grupotechnos.watch.protocol.profile.StepsAggregation
import br.com.grupotechnos.watch.protocol.testing.FakeTransport
import br.com.grupotechnos.watch.protocol.testing.SessionTestLogger
import br.com.grupotechnos.watch.protocol.testing.TestClock
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryStoreTest {
	private val zone = "America/Sao_Paulo"

	/** `historyRetentionDays` do fitGPS. */
	private val retentionDays = 30

	private fun at(day: Int, hour: Int) = TimeFormat.fromLocalWallClock(LocalDateTime(2026, 10, day, hour, 0, 0), zone)

	private fun sample(day: Int, hour: Int, steps: Int) = StepsSample(at(day, hour), steps, 0, steps / 10, steps * 0.5)

	@Test
	fun hourlySumAddsEveryHour() {
		val days = StepsAggregator.byDay(
			listOf(sample(28, 8, 100), sample(28, 9, 200), sample(27, 23, 50)),
			StepsAggregation.HOURLY_SUM,
			zone,
		)
		assertEquals(listOf("2026-10-27", "2026-10-28"), days.map { it.dayKey })
		assertEquals(listOf(50, 300), days.map { it.steps })
		assertEquals(30, days[1].calories)
		assertEquals(150.0, days[1].distanceMeters)
		assertEquals("2026-10-27T00:00:00-03:00", days[0].startOfDayIso)
	}

	@Test
	fun dayCumulativeTakesTheLargestSample() {
		val day = StepsAggregator.byDay(
			listOf(sample(28, 8, 100), sample(28, 12, 900), sample(28, 13, 40)),
			StepsAggregation.DAY_CUMULATIVE,
			zone,
		).single()
		assertEquals(900, day.steps)
		assertEquals(90, day.calories)
		assertEquals(450.0, day.distanceMeters)
	}

	@Test
	fun dayKeyFollowsThePhoneTimeZone() {
		// 2026-10-28 01:00 em São Paulo é 2026-10-28 04:00 UTC, mas 2026-10-27 21:00 em Los Angeles.
		val samples = listOf(sample(28, 1, 10))
		assertEquals("2026-10-28", StepsAggregator.byDay(samples, StepsAggregation.HOURLY_SUM, zone).single().dayKey)
		assertEquals("2026-10-27", StepsAggregator.byDay(samples, StepsAggregation.HOURLY_SUM, "America/Los_Angeles").single().dayKey)
	}

	@Test
	fun mergeReplacesByInstantAndPersists() {
		val store = InMemorySampleStore()
		val history = HistoryStore(store, TestClock.at(NOW_ISO), SessionTestLogger())
		assertEquals(2, history.mergeSteps(listOf(sample(28, 8, 100), sample(28, 9, 200)), retentionDays))
		val snapshot = history.stepsSnapshot()
		assertEquals(1, history.mergeSteps(listOf(sample(28, 9, 250), sample(28, 8, 100)), retentionDays))
		assertEquals(listOf(100, 250), store.loadSteps().map { it.steps })
		// O instantâneo antigo não muda: quem estava lendo continua vendo uma lista inteira.
		assertEquals(listOf(100, 200), snapshot.map { it.steps })
	}

	@Test
	fun mergeDropsSamplesAfterTodayAndBeforeTheRetentionWindow() {
		// Agora é 2026-10-28 14:30 em São Paulo; com 30 dias de retenção, o dia mais velho mantido é 2026-09-28.
		val logger = SessionTestLogger()
		val store = InMemorySampleStore()
		val history = HistoryStore(store, TestClock.at(NOW_ISO), logger)
		val lateToday = sample(28, 23, 10)
		val oldestKept = StepsSample(TimeFormat.fromLocalWallClock(LocalDateTime(2026, 9, 28, 0, 0, 0), zone), 20, 0, 2, 10.0)
		val tomorrow = sample(29, 0, 30)
		val farFuture = StepsSample(TimeFormat.fromLocalWallClock(LocalDateTime(2063, 1, 1, 8, 0, 0), zone), 40, 0, 4, 20.0)
		val tooOld = StepsSample(TimeFormat.fromLocalWallClock(LocalDateTime(2026, 9, 27, 23, 0, 0), zone), 50, 0, 5, 25.0)
		assertEquals(2, history.mergeSteps(listOf(lateToday, tomorrow, oldestKept, farFuture, tooOld), retentionDays))
		assertEquals(listOf(oldestKept, lateToday), history.stepsSnapshot())
		assertEquals(listOf(oldestKept, lateToday), store.loadSteps())
		val logged = logger.messages.single { it.second == "Session/mergeSteps" }
		assertEquals(LogLevel.WARN, logged.first)
		assertEquals("dropped 3 of 5 step samples: 2 after today (2026-10-28) and more than 3 h ahead, 1 before 2026-09-28", logged.third)
		// Amanhã nunca vira dia pendente, nem quando a data chega.
		val later = HistoryStore(store, TestClock.at("2026-10-29T10:00:00-03:00"), SessionTestLogger())
		assertEquals(listOf("2026-09-28", "2026-10-28"), later.stepsDays(3, StepsAggregation.HOURLY_SUM).map { it.dayKey })
		// Só amostras velhas: INFO, porque um relógio com mais de 30 dias guardados é normal.
		val infoLogger = SessionTestLogger()
		val fresh = HistoryStore(InMemorySampleStore(), TestClock.at(NOW_ISO), infoLogger)
		assertEquals(0, fresh.mergeSteps(listOf(tooOld), retentionDays))
		assertEquals(LogLevel.INFO, infoLogger.messages.single { it.second == "Session/mergeSteps" }.first)
	}

	@Test
	fun failingStoreIsLoggedAndTheSessionKeepsGoing() {
		val logger = SessionTestLogger()
		val store = object : SampleStore by InMemorySampleStore() {
			override fun loadSteps(): List<StepsSample> = throw IllegalStateException("disk gone")

			override fun saveSteps(samples: List<StepsSample>) = throw IllegalStateException("disk full")
		}
		val history = HistoryStore(store, TestClock.at(NOW_ISO), logger)
		history.mergeSteps(listOf(sample(28, 8, 100)), retentionDays)
		assertEquals(100, history.stepsDays(0, StepsAggregation.HOURLY_SUM).single().steps)
		assertTrue(logger.contains("disk gone"))
		assertTrue(logger.contains("disk full"))
	}

	@Test
	fun todayIsZeroWhenThereIsNoSampleAndIgnoresConfirmation() {
		val history = HistoryStore(InMemorySampleStore(), TestClock.at(NOW_ISO), SessionTestLogger())
		assertEquals(0, history.today(StepsAggregation.HOURLY_SUM).steps)
		history.mergeSteps(listOf(sample(28, 8, 100)), retentionDays)
		history.confirm(DataKind.STEPS, listOf("2026-10-28"))
		assertEquals(100, history.today(StepsAggregation.HOURLY_SUM).steps)
		assertTrue(history.stepsDays(0, StepsAggregation.HOURLY_SUM).isEmpty())
	}

	@Test
	fun unconfirmedDaysOlderThanTheWindowAreStillReturned() {
		// Seis dias sem abrir o app e o app pede 3: os dias −6 a −4 ainda não foram enviados e precisam voltar.
		val logger = SessionTestLogger()
		val history = HistoryStore(InMemorySampleStore(), TestClock.at(NOW_ISO), logger)
		history.mergeSteps((22..28).map { day -> sample(day, 10, day * 10) }, retentionDays)
		history.confirm(DataKind.STEPS, listOf("2026-10-23"))
		val days = history.stepsDays(3, StepsAggregation.HOURLY_SUM)
		assertEquals(listOf("2026-10-22", "2026-10-24", "2026-10-25", "2026-10-26", "2026-10-27", "2026-10-28"), days.map { it.dayKey })
		assertTrue(logger.contains("also returning unconfirmed days [2026-10-22, 2026-10-24]"))
		val sleepStart = at(23, 6)
		history.mergeSleep(
			listOf(
				SleepSession("2026-10-23", sleepStart, sleepStart + 60_000, "", "", emptyList(), 1, 0, 0, 0, 1),
			),
		)
		assertEquals(listOf("2026-10-23"), history.sleepSessions(3).map { it.dayKey })
	}

	@Test
	fun aThrowingLoggerNeverEscapesTheHistory() {
		// O logger do host não deve lançar; se lançar, a carga, a mescla, a poda, a gravação e as consultas continuam.
		val logger = ThrowingLogger()
		val store = object : SampleStore by InMemorySampleStore() {
			override fun loadSleep(): List<SleepSession> = throw IllegalStateException("disk gone")

			override fun saveSleep(sessions: List<SleepSession>) = throw IllegalStateException("disk full")
		}
		val history = HistoryStore(store, TestClock.at(NOW_ISO), logger)
		val afterLoad = logger.calls
		assertTrue(afterLoad > 0, "the failing load did not log")
		val tooOld = StepsSample(TimeFormat.fromLocalWallClock(LocalDateTime(2026, 9, 1, 8, 0, 0), zone), 5, 0, 0, 1.0)
		val farFuture = sample(30, 8, 7)
		assertEquals(2, history.mergeSteps(listOf(sample(26, 8, 100), sample(28, 8, 200), tooOld, farFuture), retentionDays))
		val sleepStart = at(27, 6)
		assertEquals(1, history.mergeSleep(listOf(SleepSession("2026-10-27", sleepStart, sleepStart + 60_000, "", "", emptyList(), 1, 0, 0, 0, 1))))
		history.confirm(DataKind.STEPS, listOf("not-a-day", "2026-10-25"))
		assertEquals(setOf("2026-10-25"), history.confirmedDays(DataKind.STEPS))
		assertEquals(listOf("2026-10-26", "2026-10-28"), history.stepsDays(0, StepsAggregation.HOURLY_SUM).map { it.dayKey })
		assertEquals(listOf("2026-10-27"), history.sleepSessions(0).map { it.dayKey })
		history.prune(1)
		assertEquals(listOf("2026-10-28"), history.stepsDays(0, StepsAggregation.HOURLY_SUM).map { it.dayKey })
		// Cada caminho acima registrou, e o logger lançou nos seis: a janela da mescla (uma antiga e uma do futuro), a
		// gravação do sono que falhou, o dia inválido, os dias anteriores à janela (as duas consultas) e a poda dos passos
		// do dia 26 (o sono do dia 27 fica).
		assertEquals(6, logger.calls - afterLoad, "log calls after the load")
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	@Test
	fun aThrowingLoggerNeverEscapesTheSynchronousSessionCalls() = runTest {
		// getStepsDays, getSleepSessions, confirmUploaded e o construtor rodam na thread de quem chama: uma exceção do
		// logger ali chegaria direto no host (no Android, na thread do TurboModule).
		val clock = TestClock.at(NOW_ISO)
		val oldDay = at(20, 10)
		val store = InMemorySampleStore().apply {
			saveSteps(listOf(StepsSample(oldDay, 100, 0, 10, 50.0)))
			saveSleep(listOf(SleepSession("2026-10-20", oldDay, oldDay + 60_000, "", "", emptyList(), 1, 0, 0, 0, 1)))
		}
		val failing = object : SampleStore by store {
			override fun loadConfirmedDays(kind: DataKind): List<String> = throw IllegalStateException("disk gone")
		}
		val logger = ThrowingLogger()
		val transport = FakeTransport()
		val session = WatchSession(transport, logger, failing, clock, StandardTestDispatcher(testScheduler))
		transport.events = session.events
		try {
			assertTrue(logger.calls > 0, "the failing load did not log")
			assertEquals(listOf("2026-10-20"), session.getStepsDays(0).map { it.dayKey })
			assertEquals(listOf("2026-10-20"), session.getSleepSessions(0).map { it.dayKey })
			session.confirmUploaded(DataKind.STEPS, listOf("not-a-day", "2026-10-20"))
			assertEquals(emptyList(), session.getStepsDays(0))
		} finally {
			session.close()
			runCurrent()
		}
	}

	/** Um logger que lança em toda chamada, contando quantas houve. */
	private class ThrowingLogger : WatchLogger {
		var calls = 0

		override fun log(level: LogLevel, tag: String, message: String) {
			calls++
			throw IllegalStateException("logger down: $tag")
		}

		override fun frame(direction: FrameDirection, characteristic: String, value: ByteArray, summary: String) {
			calls++
			throw IllegalStateException("logger down: frame")
		}
	}
}
