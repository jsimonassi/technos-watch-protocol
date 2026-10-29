package br.com.grupotechnos.watch.protocol.util

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ByteReaderWriterTest {
	@Test
	fun bigEndianRoundTrip() {
		val bytes = ByteWriter(2)
			.u8(0xAB)
			.u16(0x1234)
			.u24(0xFEDCBA)
			.u32(0xFFFFFFFEL)
			.bytes(byteArrayOf(1, 2))
			.zeros(2)
			.toByteArray()
		assertEquals("AB1234FEDCBAFFFFFFFE01020000", Hex.encode(bytes))
		val reader = ByteReader(bytes)
		assertEquals(0xAB, reader.u8())
		assertEquals(0x1234, reader.u16())
		assertEquals(0xFEDCBA, reader.u24())
		assertEquals(0xFFFFFFFEL, reader.u32())
		assertContentEquals(byteArrayOf(1, 2), reader.bytes(2))
		reader.skip(2)
		assertEquals(0, reader.remaining)
		assertFailsWith<IndexOutOfBoundsException> { reader.u8() }
	}

	@Test
	fun staticReadersAreUnsigned() {
		val bytes = Hex.decode("FF FF FF FF")
		assertEquals(0xFF, ByteReader.u8At(bytes, 0))
		assertEquals(0xFFFF, ByteReader.u16At(bytes, 0))
		assertEquals(0xFFFFFF, ByteReader.u24At(bytes, 0))
		assertEquals(0xFFFFFFFFL, ByteReader.u32At(bytes, 0))
	}

	@Test
	fun writerRejectsValuesThatDoNotFit() {
		assertFailsWith<IllegalArgumentException> { ByteWriter().u8(256) }
		assertFailsWith<IllegalArgumentException> { ByteWriter().u16(-1) }
		assertFailsWith<IllegalArgumentException> { ByteWriter().u24(0x1000000) }
		assertFailsWith<IllegalArgumentException> { ByteWriter().u32(0x100000000L) }
	}

	@Test
	fun hexRoundTrip() {
		assertEquals("AB 00 7F", Hex.encodeSpaced(Hex.decode("ab:00:7f")))
		assertFailsWith<IllegalArgumentException> { Hex.decode("ABC") }
	}
}

class TimeFormatTest {
	private val saoPaulo = "America/Sao_Paulo"
	private val lisbon = "Europe/Lisbon"

	private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
		TimeFormat.fromLocalWallClock(LocalDateTime(year, month, day, hour, minute, 0), "UTC")

	@Test
	fun utcUsesNumericOffset() {
		assertEquals("1970-01-01T00:00:00+00:00", TimeFormat.isoWithOffset(0, "UTC"))
		assertEquals("2026-10-28T12:34:56+00:00", TimeFormat.isoWithOffset(utc(2026, 10, 28, 12, 34) + 56_000 + 789, "UTC"))
	}

	@Test
	fun saoPauloWithoutDstToday() {
		assertEquals("2026-10-28T00:00:00-03:00", TimeFormat.isoWithOffset(utc(2026, 10, 28, 3), saoPaulo))
		assertEquals("2026-10-27", TimeFormat.dayKey(utc(2026, 10, 28, 2, 59), saoPaulo))
		assertEquals("2026-10-28", TimeFormat.dayKey(utc(2026, 10, 28, 3), saoPaulo))
		assertEquals(utc(2026, 10, 28, 3), TimeFormat.startOfDayEpochMillis("2026-10-28", saoPaulo))
		assertEquals("2026-10-28T00:00:00-03:00", TimeFormat.startOfDayIso("2026-10-28", saoPaulo))
	}

	@Test
	fun saoPaulo2018DstStartHasNoMidnight() {
		// Horário de verão começou em 2018-11-04 à meia-noite: 00:00 pulou para 01:00 (-02:00).
		assertEquals(utc(2018, 11, 4, 3), TimeFormat.startOfDayEpochMillis("2018-11-04", saoPaulo))
		assertEquals("2018-11-04T01:00:00-02:00", TimeFormat.startOfDayIso("2018-11-04", saoPaulo))
		assertEquals(utc(2018, 11, 4, 3, 30), TimeFormat.fromLocalWallClock(LocalDateTime(2018, 11, 4, 0, 30, 0), saoPaulo))
		assertEquals("2018-12-01T00:00:00-02:00", TimeFormat.startOfDayIso("2018-12-01", saoPaulo))
	}

	@Test
	fun saoPaulo2019DstEndOverlapUsesEarlierOffset() {
		// Em 2019-02-17 00:00 (-02:00) o relógio voltou para 2019-02-16 23:00 (-03:00): 23:30 existiu duas vezes.
		assertEquals(utc(2019, 2, 17, 1, 30), TimeFormat.fromLocalWallClock(LocalDateTime(2019, 2, 16, 23, 30, 0), saoPaulo))
		assertEquals("2019-02-16T23:30:00-03:00", TimeFormat.isoWithOffset(utc(2019, 2, 17, 2, 30), saoPaulo))
	}

	@Test
	fun lisbonSummerWinterGapAndOverlap() {
		assertEquals("2026-07-01T13:00:00+01:00", TimeFormat.isoWithOffset(utc(2026, 7, 1, 12), lisbon))
		assertEquals("2026-01-15T12:00:00+00:00", TimeFormat.isoWithOffset(utc(2026, 1, 15, 12), lisbon))
		// 2026-03-29 01:00 WET → 02:00 WEST: 01:30 não existiu e é empurrado uma hora para frente.
		assertEquals(utc(2026, 3, 29, 1, 30), TimeFormat.fromLocalWallClock(LocalDateTime(2026, 3, 29, 1, 30, 0), lisbon))
		// 2026-10-25 02:00 WEST → 01:00 WET: 01:30 existiu duas vezes; vale a primeira (+01:00).
		assertEquals(utc(2026, 10, 25, 0, 30), TimeFormat.fromLocalWallClock(LocalDateTime(2026, 10, 25, 1, 30, 0), lisbon))
	}

	@Test
	fun offsetsWithMinutes() {
		assertEquals("+05:45", TimeFormat.formatOffset(5 * 3600 + 45 * 60))
		assertEquals("-03:30", TimeFormat.formatOffset(-(3 * 3600 + 30 * 60)))
		assertEquals("2026-10-28T05:45:00+05:45", TimeFormat.isoWithOffset(utc(2026, 10, 28, 0), "Asia/Kathmandu"))
	}

	@Test
	fun wallClockRoundTrip() {
		val local = LocalDateTime(2026, 10, 28, 14, 30, 0)
		val epoch = TimeFormat.fromLocalWallClock(local, saoPaulo)
		assertEquals(local, TimeFormat.toLocalWallClock(epoch, saoPaulo))
		assertEquals("2026-10-28T14:30:00", TimeFormat.formatLocal(local))
	}

	@Test
	fun invalidDayKeys() {
		assertFailsWith<IllegalArgumentException> { TimeFormat.startOfDayEpochMillis("2026-13-01", saoPaulo) }
		assertFailsWith<IllegalArgumentException> { TimeFormat.startOfDayEpochMillis("28/10/2026", saoPaulo) }
		assertFailsWith<IllegalArgumentException> { TimeFormat.parseDayKey("2026-2-1") }
	}
}
