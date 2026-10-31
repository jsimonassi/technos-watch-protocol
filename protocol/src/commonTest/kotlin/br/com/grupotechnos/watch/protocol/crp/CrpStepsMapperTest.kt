package br.com.grupotechnos.watch.protocol.crp

import br.com.grupotechnos.watch.protocol.profile.StepsAggregation
import br.com.grupotechnos.watch.protocol.session.StepsAggregator
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Meias horas e totais do dia → amostras ([CrpStepsMapper]; `docs/protocolo-crp.md`, seção 8). */
class CrpStepsMapperTest {
	private val tz = "America/Sao_Paulo"

	/** 15:43 de 2026-10-31: meia hora 31 (15:30). */
	private val now = Instant.parse("2026-10-31T15:43:00-03:00").toEpochMilliseconds()

	private fun at(iso: String): Long = Instant.parse(iso).toEpochMilliseconds()

	@Test
	fun currentHalfHourIsTheRemainderOfFee1() {
		assertEquals(31, CrpStepsMapper.currentIndex(now, tz))
		val today = IntArray(48).also { it[16] = 120; it[17] = 350; it[31] = 999; it[40] = 5 }
		val samples = CrpStepsMapper.map(today, null, CrpStepsTotals(600, 450, 21, null), null, now, tz)
		// 31 meias horas fechadas (0..30), a atual e os totais.
		assertEquals(33, samples.size)
		val current = samples.single { it.mode == CrpStepsMapper.MODE_LIVE_REMAINDER }
		assertEquals(at("2026-10-31T15:30:00-03:00"), current.atEpochMillis)
		assertEquals(600 - 470, current.steps)
		// As meias horas a partir da atual vindas do histórico são ignoradas (sdk:j/q0.java:31-49).
		assertTrue(samples.none { it.atEpochMillis == at("2026-10-31T20:00:00-03:00") })
		val day = StepsAggregator.day("2026-10-31", samples, StepsAggregation.HOURLY_SUM, tz)
		assertEquals(Triple(600, 21, 450.0), Triple(day.steps, day.calories, day.distanceMeters))
	}

	@Test
	fun dayTotalsSampleIsOneSecondAfterMidnight() {
		val samples = CrpStepsMapper.map(IntArray(48), null, CrpStepsTotals(10, 8, 1, null), null, now, tz)
		val totals = samples.single { it.mode == CrpStepsMapper.MODE_DAY_TOTALS }
		assertEquals(at("2026-10-31T00:00:01-03:00"), totals.atEpochMillis)
		assertEquals(0, totals.steps)
		assertEquals(1, totals.calories)
		assertEquals(8.0, totals.distanceMeters)
		assertTrue(samples.filter { it.mode == CrpStepsMapper.MODE_BUCKET }.all { it.calories == 0 && it.distanceMeters == 0.0 })
	}

	@Test
	fun dayWithoutStepsCreatesNoSamples() {
		assertEquals(emptyList(), CrpStepsMapper.map(IntArray(48), IntArray(48), CrpStepsTotals(0, 0, 0, null), null, now, tz))
		assertEquals(emptyList(), CrpStepsMapper.map(IntArray(48), IntArray(48), null, null, now, tz))
	}

	@Test
	fun yesterdayWithoutTotalsHasNoTotalsSample() {
		val yesterday = IntArray(48).also { it[0] = 7; it[47] = 3 }
		val samples = CrpStepsMapper.map(null, yesterday, null, null, now, tz)
		assertEquals(48, samples.size)
		assertTrue(samples.all { it.mode == CrpStepsMapper.MODE_BUCKET })
		assertEquals(at("2026-10-30T00:00:00-03:00"), samples.first().atEpochMillis)
		assertEquals(at("2026-10-30T23:30:00-03:00"), samples.last().atEpochMillis)
		val withTotals = CrpStepsMapper.map(null, yesterday, null, CrpStepsTotals(10, 9, 1, null), now, tz)
		assertEquals(at("2026-10-30T00:00:01-03:00"), withTotals.single { it.mode == CrpStepsMapper.MODE_DAY_TOTALS }.atEpochMillis)
	}

	@Test
	fun liveUpdateAcrossAHalfHour() {
		val today = IntArray(48).also { it[10] = 100 }
		val state = CrpStepsMapper.liveState(today, CrpStepsTotals(130, 100, 5, null), now, tz)
		assertEquals(31, state.index)
		assertEquals(30, state.lastRemainder)
		val same = assertIs<LiveUpdate.Samples>(CrpStepsMapper.liveUpdate(state, CrpStepsTotals(150, 110, 6, null), now + 60_000, tz))
		assertEquals(50, same.state.lastRemainder)
		assertEquals(50, same.samples.single { it.mode == CrpStepsMapper.MODE_LIVE_REMAINDER }.steps)
		// 16:05 (meia hora 32): a 31 fecha com o último resto (50) e a 32 começa com o que passou disso.
		val next = assertIs<LiveUpdate.Samples>(
			CrpStepsMapper.liveUpdate(same.state, CrpStepsTotals(170, 120, 7, null), at("2026-10-31T16:05:00-03:00"), tz),
		)
		assertEquals(32, next.state.index)
		assertEquals(50, next.state.completed[31])
		assertEquals(20, next.state.lastRemainder)
		val current = next.samples.single { it.mode == CrpStepsMapper.MODE_LIVE_REMAINDER }
		assertEquals(at("2026-10-31T16:00:00-03:00"), current.atEpochMillis)
		val totals = next.samples.single { it.mode == CrpStepsMapper.MODE_DAY_TOTALS }
		assertEquals(Pair(7, 120.0), Pair(totals.calories, totals.distanceMeters))
		// Saltando meias horas: as puladas ficam 0.
		val skip = assertIs<LiveUpdate.Samples>(
			CrpStepsMapper.liveUpdate(next.state, CrpStepsTotals(200, 130, 8, null), at("2026-10-31T17:40:00-03:00"), tz),
		)
		assertEquals(35, skip.state.index)
		assertEquals(20, skip.state.completed[32])
		assertEquals(listOf(0, 0), listOf(skip.state.completed[33], skip.state.completed[34]))
		assertEquals(200 - 100 - 50 - 20, skip.state.lastRemainder)
	}

	@Test
	fun liveUpdateOnANewDayAsksForARefresh() {
		val state = CrpStepsMapper.liveState(IntArray(48), CrpStepsTotals(10, 8, 1, null), now, tz)
		assertEquals(LiveUpdate.DataChanged, CrpStepsMapper.liveUpdate(state, CrpStepsTotals(3, 2, 0, null), at("2026-11-01T00:01:00-03:00"), tz))
		assertEquals(LiveUpdate.Ignore, CrpStepsMapper.liveUpdate(state, CrpStepsTotals(0, 0, 0, null), now, tz))
		assertEquals("2026-10-31", state.dayKey)
		assertEquals(TimeFormat.dayKey(now, tz), state.dayKey)
	}
}
