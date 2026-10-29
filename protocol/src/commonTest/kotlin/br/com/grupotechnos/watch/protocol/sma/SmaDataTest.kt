package br.com.grupotechnos.watch.protocol.sma

import br.com.grupotechnos.watch.protocol.sma.data.SmaLocalTime
import br.com.grupotechnos.watch.protocol.sma.data.SmaRecordCodec
import br.com.grupotechnos.watch.protocol.sma.data.SmaSleepRecord
import br.com.grupotechnos.watch.protocol.sma.data.SmaSportRecord
import br.com.grupotechnos.watch.protocol.sma.data.SmaStepsMapper
import br.com.grupotechnos.watch.protocol.util.Hex
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Fixtures hex de registros `05/42` e `05/46` (timestamps calculados à parte: segundos desde 2000-01-01 00:00). */
class SmaRecordDecodingTest {
	@Test
	fun sportRecordsFromHex() {
		// 2026-10-28 14:05:00 = 0x3274BD8C, mode 0, 1234 passos; 2026-10-28 15:00:00 = 0x3274CA70, mode 32, 70000 passos.
		val value = Hex.decode("32 74 BD 8C 00 00 04 D2  32 74 CA 70 20 01 11 70")
		val decoded = SmaRecordCodec.decodeSport(value)
		assertEquals(
			listOf(SmaSportRecord(846_511_500, 0, 1234), SmaSportRecord(846_514_800, 32, 70_000)),
			decoded.records,
		)
		assertEquals(0, decoded.trailingBytes)
		assertEquals(LocalDateTime(2026, 10, 28, 14, 5, 0), SmaLocalTime.toLocalWallClock(846_511_500))
	}

	@Test
	fun sleepRecordsFromHex() {
		// 2026-10-27 23:00:00 = 0x3273E970: START (17); depois LIGHT com soft 4 e strong 1.
		val value = Hex.decode("32 73 E9 70 11 00 00  32 74 C3 68 02 04 01")
		assertEquals(
			listOf(SmaSleepRecord(846_457_200, 17, 0, 0), SmaSleepRecord(846_513_000, 2, 4, 1)),
			SmaRecordCodec.decodeSleep(value).records,
		)
	}

	@Test
	fun trailingBytesAreCountedNotDecoded() {
		val decoded = SmaRecordCodec.decodeSport(Hex.decode("32 74 BD 8C 00 00 04 D2 FF FF"))
		assertEquals(1, decoded.records.size)
		assertEquals(2, decoded.trailingBytes)
		assertEquals(0, SmaRecordCodec.decodeSleep(ByteArray(0)).records.size)
	}

	@Test
	fun localTimeRoundTrip() {
		val local = LocalDateTime(2026, 10, 28, 14, 30, 0)
		assertEquals(846_513_000, SmaLocalTime.toSecondsSince2000(local))
		assertEquals(local, SmaLocalTime.toLocalWallClock(846_513_000))
		assertEquals(LocalDateTime(2000, 1, 1, 0, 0, 0), SmaLocalTime.toLocalWallClock(0))
	}
}

class SmaStepsMapperTest {
	private val zone = "America/Sao_Paulo"

	private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
		SmaLocalTime.toSecondsSince2000(LocalDateTime(year, month, day, hour, minute, 0))

	@Test
	fun onlyModeZeroBucketedToTheLocalHour() {
		val samples = SmaStepsMapper.map(
			listOf(
				SmaSportRecord(at(2026, 10, 28, 14, 5), 0, 100),
				SmaSportRecord(at(2026, 10, 28, 14, 10), 32, 999),
				SmaSportRecord(at(2026, 10, 28, 15, 59), 0, 300),
			),
			zone,
		)
		assertEquals(2, samples.size)
		assertEquals("2026-10-28T14:00:00-03:00", TimeFormat.isoWithOffset(samples[0].atEpochMillis, zone))
		assertEquals("2026-10-28T15:00:00-03:00", TimeFormat.isoWithOffset(samples[1].atEpochMillis, zone))
		assertEquals(listOf(100, 300), samples.map { it.steps })
		assertEquals(listOf(0, 0), samples.map { it.mode })
	}

	@Test
	fun lastRecordInArrivalOrderWinsTheHour() {
		// SMAService.java:448: HashMap.put na ordem da lista, então vale o último que chegou, não o mais tardio.
		val samples = SmaStepsMapper.map(
			listOf(
				SmaSportRecord(at(2026, 10, 28, 14, 5), 0, 100),
				SmaSportRecord(at(2026, 10, 28, 14, 55), 0, 250),
				SmaSportRecord(at(2026, 10, 28, 14, 30), 0, 180),
			),
			zone,
		)
		assertEquals(listOf(180), samples.map { it.steps })
	}

	@Test
	fun estimatesPerSample() {
		val sample = SmaStepsMapper.map(listOf(SmaSportRecord(at(2026, 10, 28, 9, 0), 0, 1000)), zone).single()
		assertEquals(33, sample.calories)
		assertEquals(780.75, sample.distanceMeters)
	}

	@Test
	fun sortedByInstant() {
		val samples = SmaStepsMapper.map(
			listOf(SmaSportRecord(at(2026, 10, 28, 16, 0), 0, 3), SmaSportRecord(at(2026, 10, 28, 8, 0), 0, 1)),
			zone,
		)
		assertEquals(listOf(1, 3), samples.map { it.steps })
	}

	@Test
	fun hourThatDoesNotExistIsShiftedForward() {
		// 2018-11-04 00:00 não existiu em São Paulo (início do horário de verão): a hora 00 vira 01:00 -02:00.
		val sample = SmaStepsMapper.map(listOf(SmaSportRecord(at(2018, 11, 4, 0, 30), 0, 10)), zone).single()
		assertEquals("2018-11-04T01:00:00-02:00", TimeFormat.isoWithOffset(sample.atEpochMillis, zone))
	}

	@Test
	fun summerTimeUsesTheRealOffsetNotTheRawOne() {
		// O Core subtraía getRawOffset() (-03:00) também no verão; aqui 2018-12-01 10:00 local é 12:00Z.
		val sample = SmaStepsMapper.map(listOf(SmaSportRecord(at(2018, 12, 1, 10, 0), 0, 10)), zone).single()
		assertEquals(TimeFormat.fromLocalWallClock(LocalDateTime(2018, 12, 1, 12, 0, 0), "UTC"), sample.atEpochMillis)
	}
}

class SmaEstimatesTest {
	/** Valores impressos rodando as expressões de `SmaBleUtils.java:89-95` na JVM (passos → kcal g1, kcal g0, metros). */
	private val sdk = listOf(
		Triple(0, 0 to 0, 0.0),
		Triple(1, 0 to 0, 0.7807499766349792),
		Triple(37, 1 to 1, 28.88775062561035),
		Triple(100, 3 to 2, 78.07499694824219),
		Triple(1000, 33 to 27, 780.75),
		Triple(1234, 40 to 34, 963.4454956054688),
		Triple(5000, 165 to 138, 3903.75),
		Triple(9999, 329 to 275, 7806.71923828125),
		Triple(10000, 330 to 276, 7807.5),
		Triple(12345, 407 to 340, 9638.3583984375),
		Triple(16_777_215, 553_648 to 463_051, 1.3098811E7),
	)

	@Test
	fun sameNumbersAsTheSdkFormulas() {
		for ((steps, calories, meters) in sdk) {
			assertEquals(calories.first, SmaEstimates.calories(steps), "calories g1 for $steps")
			assertEquals(calories.second, SmaEstimates.calories(steps, gender = 0), "calories g0 for $steps")
			assertEquals(meters, SmaEstimates.distanceMeters(steps), "meters for $steps")
		}
	}

	@Test
	fun distanceIsInKilometres() {
		assertEquals(0.78075f, SmaEstimates.distanceKm(1000))
	}
}
