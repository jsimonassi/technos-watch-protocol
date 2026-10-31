package br.com.grupotechnos.watch.protocol.crp

import br.com.grupotechnos.watch.protocol.domain.SleepStageType
import br.com.grupotechnos.watch.protocol.util.Hex
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Montagem do sono CRP igual à SDK ([CrpSleepAssembler]; `sdk:j/o0.java:24-133`). */
class CrpSleepAssemblerTest {
	private val tz = "America/Sao_Paulo"
	private val wakeDay = LocalDate(2026, 10, 31)

	@Test
	fun phaseZeroLegacyVector() {
		val records = assertNotNull(CrpSleepPayload.decodeLegacy(Hex.decode("011705021732" + "01010A00061E")))
		val session = assertNotNull(CrpSleepAssembler.assemble(records, wakeDay, tz, legacyHourWrap = true))
		assertEquals(365, session.lightMinutes)
		assertEquals(80, session.deepMinutes)
		assertEquals(0, session.remMinutes)
		assertEquals(0, session.awakeMinutes)
		assertEquals(445, session.totalMinutes)
		assertEquals("2026-10-31", session.dayKey)
		assertEquals("2026-10-30T23:05:00-03:00", session.startIso)
		assertEquals("2026-10-31T06:30:00-03:00", session.endIso)
		assertEquals(listOf(SleepStageType.LIGHT, SleepStageType.DEEP, SleepStageType.LIGHT), session.stages.map { it.type })
		assertEquals(listOf(45L, 80L, 320L), session.stages.map { (it.endEpochMillis - it.startEpochMillis) / 60_000 })
	}

	@Test
	fun b6VectorGivesTheSameSession() {
		val legacy = CrpSleepAssembler.assemble(CrpSleepPayload.decodeLegacy(Hex.decode("011705021732" + "01010A00061E"))!!, wakeDay, tz, true)
		val modern = CrpSleepAssembler.assemble(CrpSleepPayload.decodeNew(Hex.decode("711572C911286078"))!!, wakeDay, tz, false)
		assertEquals(legacy, modern)
	}

	@Test
	fun eightPmRollsTheSleepDateForward() {
		val at21 = Instant.parse("2026-10-31T21:00:00-03:00").toEpochMilliseconds()
		val at19 = Instant.parse("2026-10-31T19:59:00-03:00").toEpochMilliseconds()
		val at20 = Instant.parse("2026-10-31T20:00:00-03:00").toEpochMilliseconds()
		assertEquals(LocalDate(2026, 11, 1), CrpSleepAssembler.sleepDate(at21, tz, 0))
		assertEquals(LocalDate(2026, 10, 31), CrpSleepAssembler.sleepDate(at21, tz, 1))
		assertEquals(LocalDate(2026, 10, 31), CrpSleepAssembler.sleepDate(at19, tz, 0))
		assertEquals(LocalDate(2026, 11, 1), CrpSleepAssembler.sleepDate(at20, tz, 0))
		assertEquals(LocalDate(2026, 10, 29), CrpSleepAssembler.sleepDate(at19, tz, 2))
	}

	@Test
	fun negativeRecordIsSkippedWithoutMovingTheReference() {
		// Um estágio de 0 min (01:00 leve, 01:00 profundo) sai; os seguintes continuam do mesmo ponto.
		val records = listOf(CrpSleepRecord(1, 1, 0), CrpSleepRecord(2, 1, 0), CrpSleepRecord(2, 2, 0), CrpSleepRecord(0, 3, 0))
		val session = assertNotNull(CrpSleepAssembler.assemble(records, wakeDay, tz, true))
		assertEquals(listOf(SleepStageType.DEEP, SleepStageType.DEEP), session.stages.map { it.type })
		assertEquals(120, session.deepMinutes)
		// Duração negativa: mesma hora, minuto menor que o anterior (sem virada, porque a hora não diminuiu).
		val withNegative = listOf(CrpSleepRecord(1, 2, 30), CrpSleepRecord(2, 2, 10), CrpSleepRecord(0, 3, 0))
		val skipped = assertNotNull(CrpSleepAssembler.assemble(withNegative, wakeDay, tz, true))
		// O 02:10 é ignorado: o leve vai de 02:30 a 03:00 e o estado 2 nunca abre.
		assertEquals(listOf(SleepStageType.LIGHT), skipped.stages.map { it.type })
		assertEquals(30, skipped.lightMinutes)
		assertEquals("2026-10-31T02:30:00-03:00", skipped.startIso)
	}

	@Test
	fun emptyOrSingleRecordGivesNoSession() {
		assertNull(CrpSleepAssembler.assemble(emptyList(), wakeDay, tz, true))
		assertNull(CrpSleepAssembler.assemble(listOf(CrpSleepRecord(1, 23, 0)), wakeDay, tz, true))
	}

	@Test
	fun unknownStateCountsAsAwake() {
		val warnings = mutableListOf<String>()
		val session = assertNotNull(
			CrpSleepAssembler.assemble(listOf(CrpSleepRecord(7, 1, 0), CrpSleepRecord(0, 1, 20)), wakeDay, tz, true) { warnings += it },
		)
		assertEquals(SleepStageType.AWAKE, session.stages.single().type)
		assertEquals(20, session.awakeMinutes)
		assertEquals(0, session.totalMinutes)
		assertTrue(warnings.single().contains("unknown sleep state 7"))
	}

	@Test
	fun remFromTheWatchCountsInTheTotal() {
		val session = assertNotNull(
			CrpSleepAssembler.assemble(
				listOf(CrpSleepRecord(1, 22, 0), CrpSleepRecord(3, 23, 0), CrpSleepRecord(0, 23, 30), CrpSleepRecord(1, 23, 40)),
				wakeDay,
				tz,
				true,
			),
		)
		assertEquals(60, session.lightMinutes)
		assertEquals(30, session.remMinutes)
		assertEquals(10, session.awakeMinutes)
		assertEquals(90, session.totalMinutes)
		assertEquals("2026-10-30T22:00:00-03:00", TimeFormat.isoWithOffset(session.startEpochMillis, tz))
	}
}
