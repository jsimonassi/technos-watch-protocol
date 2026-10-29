package br.com.grupotechnos.watch.protocol.sma

import br.com.grupotechnos.watch.protocol.domain.SleepStageType
import br.com.grupotechnos.watch.protocol.sma.data.SmaLocalTime
import br.com.grupotechnos.watch.protocol.sma.data.SmaSleepAnalyzer
import br.com.grupotechnos.watch.protocol.sma.data.SmaSleepAnalyzer.Point
import br.com.grupotechnos.watch.protocol.sma.data.SmaSleepMode
import br.com.grupotechnos.watch.protocol.sma.data.SmaSleepRecord
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Casos que espelham `SmaSleep.analyseSleeps` e `getSleepStatus` (`SmaSleep.java:17-115`). */
class SmaSleepAnalyzerTest {
	private val zone = "America/Sao_Paulo"

	private fun seconds(day: Int, hour: Int, minute: Int, month: Int = 10): Long =
		SmaLocalTime.toSecondsSince2000(LocalDateTime(2026, month, day, hour, minute, 0))

	private fun point(day: Int, hour: Int, minute: Int, mode: Int, soft: Int = 0, strong: Int = 0) =
		Point(SmaLocalTime.toLocalMillis(seconds(day, hour, minute)), mode, soft, strong)

	private fun record(day: Int, hour: Int, minute: Int, mode: Int, soft: Int = 0, strong: Int = 0) =
		SmaSleepRecord(seconds(day, hour, minute), mode, soft, strong)

	private fun describe(points: List<Point>): List<String> = points.map {
		val local = SmaLocalTime.localMillisToWallClock(it.time)
		"${local.dayOfMonth} ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')} m${it.mode}"
	}

	@Test
	fun fewerThanTwoRecordsGiveNothing() {
		assertTrue(SmaSleepAnalyzer.analyseSleeps(listOf(point(27, 23, 0, 17))).isEmpty())
		assertTrue(SmaSleepAnalyzer.analyze(listOf(record(27, 23, 0, 17)), zone).isEmpty())
	}

	@Test
	fun fullNightCoversEveryRule() {
		val input = listOf(
			point(28, 6, 0, SmaSleepMode.END),
			point(27, 23, 0, SmaSleepMode.START),
			point(28, 0, 0, SmaSleepMode.LIGHT),
			point(28, 1, 0, SmaSleepMode.DEEP),
			point(28, 3, 0, SmaSleepMode.DEEP, strong = 5),
			point(28, 4, 0, SmaSleepMode.DEEP, soft = 4),
		)
		val result = SmaSleepAnalyzer.analyseSleeps(input)
		assertEquals(
			listOf("27 23:00 m2", "27 23:15 m1", "28 00:00 m2", "28 01:00 m1", "28 02:45 m3", "28 03:45 m2", "28 06:00 m34"),
			describe(result),
		)
		// A entrada não é alterada (a SDK altera; o port trabalha em cópias).
		assertEquals(SmaSleepMode.START, input[1].mode)
		// duração 23:00→06:00 = 420; profundo 45 + 105 = 150; leve 15 + 60 + 135 = 210; acordado = 420 - 150 - 210.
		assertContentEquals(intArrayOf(420, 150, 210, 60), SmaSleepAnalyzer.sleepStatus(result))
	}

	@Test
	fun consecutiveEqualModesCollapse() {
		val result = SmaSleepAnalyzer.analyseSleeps(
			listOf(
				point(27, 23, 0, SmaSleepMode.START),
				point(28, 0, 0, SmaSleepMode.DEEP),
				point(28, 1, 0, SmaSleepMode.DEEP),
				point(28, 2, 0, SmaSleepMode.LIGHT),
				point(28, 3, 0, SmaSleepMode.END),
			),
		)
		// O DEEP de 23:15 (START) absorve os DEEP de 00:00 e 01:00.
		assertEquals(listOf("27 23:00 m2", "27 23:15 m1", "28 02:00 m2", "28 03:00 m34"), describe(result))
	}

	@Test
	fun withoutStartAndEndTheyAreSynthesised() {
		val result = SmaSleepAnalyzer.analyseSleeps(
			listOf(
				point(27, 22, 0, SmaSleepMode.LIGHT),
				point(27, 23, 0, SmaSleepMode.DEEP),
				point(28, 1, 0, SmaSleepMode.LIGHT),
			),
		)
		// Sem END o último vira 34 (:53-55); sem START o primeiro vira LIGHT e um DEEP entra 15 min depois (:57-63).
		assertEquals(listOf("27 22:00 m2", "27 22:15 m1", "27 23:00 m1", "28 01:00 m34"), describe(result))
		assertContentEquals(intArrayOf(180, 165, 15, 0), SmaSleepAnalyzer.sleepStatus(result))
	}

	@Test
	fun startDiscardsEverythingBeforeIt() {
		val result = SmaSleepAnalyzer.analyseSleeps(
			listOf(
				point(27, 20, 0, SmaSleepMode.LIGHT),
				point(27, 21, 0, SmaSleepMode.DEEP),
				point(27, 22, 0, SmaSleepMode.START),
				point(27, 23, 0, SmaSleepMode.END),
			),
		)
		assertEquals(listOf("27 22:00 m2", "27 22:15 m1", "27 23:00 m34"), describe(result))
	}

	@Test
	fun ruleOrderStartThenEndThenStrongThenSoft() {
		val result = SmaSleepAnalyzer.analyseSleeps(
			listOf(
				point(27, 22, 0, SmaSleepMode.START, strong = 9),
				point(28, 1, 0, SmaSleepMode.DEEP, soft = 5, strong = 5),
				point(28, 2, 0, SmaSleepMode.LIGHT, soft = 3),
				point(28, 5, 0, SmaSleepMode.END, strong = 9),
			),
		)
		// START ignora strong; strong vence soft; END ignora strong e não é deslocado.
		assertEquals(listOf("27 22:00 m2", "27 22:15 m1", "28 00:45 m3", "28 01:45 m2", "28 05:00 m34"), describe(result))
	}

	@Test
	fun sessionFromRecordsWithStagesAndTotals() {
		val sessions = SmaSleepAnalyzer.analyze(
			listOf(
				record(27, 23, 0, SmaSleepMode.START),
				record(28, 0, 0, SmaSleepMode.LIGHT),
				record(28, 1, 0, SmaSleepMode.DEEP),
				record(28, 3, 0, SmaSleepMode.DEEP, strong = 5),
				record(28, 4, 0, SmaSleepMode.DEEP, soft = 4),
				record(28, 6, 0, SmaSleepMode.END),
			),
			zone,
		)
		val session = sessions.single()
		assertEquals("2026-10-28", session.dayKey)
		assertEquals("2026-10-27T23:00:00-03:00", session.startIso)
		assertEquals("2026-10-28T06:00:00-03:00", session.endIso)
		assertEquals(
			listOf(
				SleepStageType.LIGHT to "2026-10-27T23:00:00-03:00",
				SleepStageType.DEEP to "2026-10-27T23:15:00-03:00",
				SleepStageType.LIGHT to "2026-10-28T00:00:00-03:00",
				SleepStageType.DEEP to "2026-10-28T01:00:00-03:00",
				SleepStageType.AWAKE to "2026-10-28T02:45:00-03:00",
				SleepStageType.LIGHT to "2026-10-28T03:45:00-03:00",
			),
			session.stages.map { it.type to it.startIso },
		)
		assertEquals(session.endEpochMillis, session.stages.last().endEpochMillis)
		session.stages.zipWithNext().forEach { (a, b) -> assertEquals(a.endEpochMillis, b.startEpochMillis) }
		assertEquals(listOf(420, 150, 210, 0, 60), listOf(session.totalMinutes, session.deepMinutes, session.lightMinutes, session.remMinutes, session.awakeMinutes))
	}

	@Test
	fun adjacentStagesOfTheSameTypeAreMerged() {
		val session = SmaSleepAnalyzer.analyze(
			listOf(
				record(27, 22, 0, SmaSleepMode.LIGHT),
				record(27, 23, 0, SmaSleepMode.DEEP),
				record(28, 1, 0, SmaSleepMode.LIGHT),
			),
			zone,
		).single()
		assertEquals(
			listOf(SleepStageType.LIGHT to "2026-10-27T22:00:00-03:00", SleepStageType.DEEP to "2026-10-27T22:15:00-03:00"),
			session.stages.map { it.type to it.startIso },
		)
		assertEquals("2026-10-28T01:00:00-03:00", session.stages.last().endIso)
	}

	@Test
	fun recordsAreSplitIntoNightsAtEachEnd() {
		val sessions = SmaSleepAnalyzer.analyze(
			listOf(
				record(26, 23, 0, SmaSleepMode.START),
				record(27, 6, 0, SmaSleepMode.END),
				record(27, 22, 30, SmaSleepMode.START),
				record(28, 7, 0, SmaSleepMode.END),
			),
			zone,
		)
		assertEquals(listOf("2026-10-27", "2026-10-28"), sessions.map { it.dayKey })
		assertEquals(listOf(420, 510), sessions.map { it.totalMinutes })
	}

	@Test
	fun lateWakeUpStaysInItsNight() {
		// Acordar às 10:30: com o corte fixo de 10:00 o END ia para a noite seguinte e esta terminava às 08:30.
		val sessions = SmaSleepAnalyzer.analyze(
			listOf(
				record(27, 23, 0, SmaSleepMode.START),
				record(28, 3, 0, SmaSleepMode.DEEP),
				record(28, 8, 30, SmaSleepMode.LIGHT),
				record(28, 10, 30, SmaSleepMode.END),
				record(28, 23, 0, SmaSleepMode.START),
				record(29, 6, 0, SmaSleepMode.END),
			),
			zone,
		)
		assertEquals(listOf("2026-10-28", "2026-10-29"), sessions.map { it.dayKey })
		val late = sessions.first()
		assertEquals("2026-10-28T10:30:00-03:00", late.endIso)
		// A fórmula da SDK daria 630 − 1380 < 0; vale o intervalo real 23:00 → 10:30.
		assertEquals(listOf(690, 555, 135, 0), listOf(late.totalMinutes, late.deepMinutes, late.lightMinutes, late.awakeMinutes))
		assertEquals("2026-10-28T23:00:00-03:00", sessions[1].startIso)
		assertEquals(420, sessions[1].totalMinutes)
	}

	@Test
	fun longGapSplitsNightsWithoutEnd() {
		val nights = SmaSleepAnalyzer.nights(
			listOf(
				record(27, 23, 0, SmaSleepMode.LIGHT),
				record(28, 1, 0, SmaSleepMode.DEEP),
				record(28, 6, 0, SmaSleepMode.LIGHT),
				// 17 h depois, sem END no meio: outra noite.
				record(28, 23, 0, SmaSleepMode.LIGHT),
				record(29, 2, 0, SmaSleepMode.DEEP),
				// 6 h sem registro no meio da noite ainda é a mesma noite.
				record(29, 8, 0, SmaSleepMode.LIGHT),
			),
		)
		assertEquals(listOf(3, 3), nights.map { it.size })
		val sessions = SmaSleepAnalyzer.analyze(
			listOf(
				record(27, 23, 0, SmaSleepMode.LIGHT),
				record(28, 1, 0, SmaSleepMode.DEEP),
				record(28, 6, 0, SmaSleepMode.LIGHT),
				record(28, 23, 0, SmaSleepMode.LIGHT),
				record(29, 2, 0, SmaSleepMode.DEEP),
				record(29, 8, 0, SmaSleepMode.LIGHT),
			),
			zone,
		)
		assertEquals(listOf("2026-10-28", "2026-10-29"), sessions.map { it.dayKey })
	}

	@Test
	fun recordsOutOfOrderAreSortedBeforeGrouping() {
		val sessions = SmaSleepAnalyzer.analyze(
			listOf(
				record(28, 7, 0, SmaSleepMode.END),
				record(27, 22, 30, SmaSleepMode.START),
			),
			zone,
		)
		assertEquals("2026-10-27T22:30:00-03:00", sessions.single().startIso)
	}
}
