package br.com.grupotechnos.watch.protocol.crp

import br.com.grupotechnos.watch.protocol.util.Hex
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Payloads CRP (`docs/protocolo-crp.md`, seção 7). */
class CrpPayloadsTest {
	@Test
	fun fee1WithNineAndTwelveBytes() {
		// Vetor sintético da fase 0: 1234 passos, 890 m, 45 kcal.
		assertEquals(CrpStepsTotals(1234, 890, 45, null), CrpStepsTotals.decode(Hex.decode("D204007A03002D0000")))
		assertEquals(CrpStepsTotals(1234, 890, 45, 600), CrpStepsTotals.decode(Hex.decode("D204007A03002D0000580200")))
		// Um campo todo FF vale 0 (sdk:g1/d.java:127-141).
		assertEquals(CrpStepsTotals(0, 890, 0, null), CrpStepsTotals.decode(Hex.decode("FFFFFF7A0300FFFFFF")))
		assertNull(CrpStepsTotals.decode(Hex.decode("0102030405060708")))
		assertNull(CrpStepsTotals.decode(Hex.decode("010203040506070809AA")))
		assertTrue(CrpStepsTotals.decode(ByteArray(9))!!.isZero)
	}

	@Test
	fun batteryAppliesTheChargingRule() {
		assertEquals(CrpBatteryReading(0x32, 50, false), CrpBatteryReading.decode(byteArrayOf(0x32)))
		assertEquals(CrpBatteryReading(0x9B, 55, true), CrpBatteryReading.decode(byteArrayOf(0x9B.toByte())))
		val over = assertNotNull(CrpBatteryReading.decode(byteArrayOf(0xFF.toByte())))
		assertEquals(100, over.percent)
		assertTrue(over.clamped)
		assertNull(CrpBatteryReading.decode(ByteArray(0)))
	}

	@Test
	fun stepsPageIsU16LittleEndian() {
		// Vetor sintético da fase 0: meias horas 16 (08:00) = 120 e 17 (08:30) = 350.
		val payload = Hex.decode("00" + "0000".repeat(16) + "7800" + "5E01" + "0000".repeat(6))
		val page = assertNotNull(CrpCategoryPage.decodeSteps(payload))
		assertEquals(0, page.page)
		assertEquals(24, page.values.size)
		assertEquals(120L, page.values[16])
		assertEquals(350L, page.values[17])
		assertNull(CrpCategoryPage.decodeSteps(Hex.decode("00010203")))
		assertNull(CrpCategoryPage.decodeSteps(ByteArray(0)))
	}

	@Test
	fun distanceAndCaloriesPagesAreU32LittleEndian() {
		// 5D: centímetros; 5C: kcal × 10000 (sdk:j/m.java:78-90; sdk:j/j.java:78-90).
		val page = assertNotNull(CrpCategoryPage.decodeU32(Hex.decode("02" + "10270000" + "40420F00")))
		assertEquals(2, page.page)
		assertEquals(listOf(10_000L, 1_000_000L), page.values.toList())
		assertNull(CrpCategoryPage.decodeU32(Hex.decode("02102700")))
	}

	@Test
	fun legacyYesterdayTotals() {
		// 33 01 + passos, metros, kcal (sdk:m/a.java:3093-3104 com sdk:j/p0.java:12-35).
		val frame = CrpReassembler { error(it) }.accept(Hex.decode("FEEA200F3301" + "E80300" + "D00700" + "320000")).single()
		assertEquals(CrpStepsTotals(1000, 2000, 50, null), CrpStepsTotals.decode(frame.payloadFrom(1)))
		// B6 00 01: passos, kcal × 10000 e cm em u32 (sdk:j/p0.java:37-60).
		assertEquals(
			CrpStepsTotals(1000, 2000, 50, null),
			CrpStepsTotals.decodeHistoryNew(Hex.decode("E8030000" + "20A10700" + "400D0300")),
		)
	}

	@Test
	fun classicStateNotices() {
		assertEquals(false, CrpClassicState.classicConnected(Hex.decode("010100")))
		assertEquals(true, CrpClassicState.classicConnected(Hex.decode("010101")))
		assertNull(CrpClassicState.classicConnected(Hex.decode("0201")))
		assertEquals("sub=02 value=00", CrpClassicState.describe(Hex.decode("0200")))
		assertEquals("raw=1 bytes", CrpClassicState.describe(Hex.decode("07")))
	}

	@Test
	fun timeAndZoneRoundTrip() {
		for (iso in listOf("2026-10-31T15:43:19-03:00", "2026-01-01T00:00:00-03:00", "2026-07-04T23:59:59-03:00")) {
			val now = Instant.parse(iso).toEpochMilliseconds()
			val payload = CrpTimeCodec.encode(now, "America/Sao_Paulo")
			assertEquals(5, payload.size)
			assertEquals(0x08, payload[4].toInt())
			val local = LocalDateTime.parse(iso.substring(0, 19))
			assertEquals(local, CrpTimeCodec.decodeWallClock(payload), iso)
		}
		// O mesmo relógio de parede dá os mesmos bytes em qualquer fuso: o fuso vai no BB 07 00.
		val sp = CrpTimeCodec.encode(Instant.parse("2026-10-31T15:43:19-03:00").toEpochMilliseconds(), "America/Sao_Paulo")
		val tokyo = CrpTimeCodec.encode(Instant.parse("2026-10-31T15:43:19+09:00").toEpochMilliseconds(), "Asia/Tokyo")
		assertEquals(Hex.encode(sp), Hex.encode(tokyo))
		for (offset in listOf(-10_800, 0, 19_800, 32_400, -34_200)) {
			assertEquals(offset, CrpTimeZoneCodec.decode(CrpTimeZoneCodec.encode(offset)))
		}
		assertEquals(19_800, CrpTimeZoneCodec.offsetSeconds(0, "Asia/Kolkata"))
	}

	@Test
	fun textDropsNulAndSpaces() {
		assertEquals("MOY-VS02-2.0.0", CrpText.decode(Hex.decode("4D4F592D565330322D322E302E30000000")))
		assertEquals("MOYOUNG-V2", CrpText.decode(Hex.decode("204D4F594F554E472D563200")))
		assertEquals("", CrpText.decode(ByteArray(3)))
	}
}
