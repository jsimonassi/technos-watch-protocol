package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.StepsSample
import br.com.grupotechnos.watch.protocol.profile.StepsAggregation
import br.com.grupotechnos.watch.protocol.testing.SessionTestLogger
import br.com.grupotechnos.watch.protocol.testing.TestClock
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryStoreTest {
	private val zone = "America/Sao_Paulo"

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
		assertEquals(2, history.mergeSteps(listOf(sample(28, 8, 100), sample(28, 9, 200))))
		val snapshot = history.stepsSnapshot()
		assertEquals(1, history.mergeSteps(listOf(sample(28, 9, 250), sample(28, 8, 100))))
		assertEquals(listOf(100, 250), store.loadSteps().map { it.steps })
		// O instantâneo antigo não muda: quem estava lendo continua vendo uma lista inteira.
		assertEquals(listOf(100, 200), snapshot.map { it.steps })
	}

	@Test
	fun failingStoreIsLoggedAndTheSessionKeepsGoing() {
		val logger = SessionTestLogger()
		val store = object : SampleStore by InMemorySampleStore() {
			override fun loadSteps(): List<StepsSample> = throw IllegalStateException("disk gone")

			override fun saveSteps(samples: List<StepsSample>) = throw IllegalStateException("disk full")
		}
		val history = HistoryStore(store, TestClock.at(NOW_ISO), logger)
		history.mergeSteps(listOf(sample(28, 8, 100)))
		assertEquals(100, history.stepsDays(0, StepsAggregation.HOURLY_SUM).single().steps)
		assertTrue(logger.contains("disk gone"))
		assertTrue(logger.contains("disk full"))
	}

	@Test
	fun todayIsZeroWhenThereIsNoSampleAndIgnoresConfirmation() {
		val history = HistoryStore(InMemorySampleStore(), TestClock.at(NOW_ISO), SessionTestLogger())
		assertEquals(0, history.today(StepsAggregation.HOURLY_SUM).steps)
		history.mergeSteps(listOf(sample(28, 8, 100)))
		history.confirm(DataKind.STEPS, listOf("2026-10-28"))
		assertEquals(100, history.today(StepsAggregation.HOURLY_SUM).steps)
		assertTrue(history.stepsDays(0, StepsAggregation.HOURLY_SUM).isEmpty())
	}

	@Test
	fun unconfirmedDaysOlderThanTheWindowAreStillReturned() {
		// Seis dias sem abrir o app e o app pede 3: os dias −6 a −4 ainda não foram enviados e precisam voltar.
		val logger = SessionTestLogger()
		val history = HistoryStore(InMemorySampleStore(), TestClock.at(NOW_ISO), logger)
		history.mergeSteps((22..28).map { day -> sample(day, 10, day * 10) })
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
}
