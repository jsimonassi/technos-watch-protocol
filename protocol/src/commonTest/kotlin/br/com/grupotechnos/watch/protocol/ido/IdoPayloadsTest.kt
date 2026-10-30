package br.com.grupotechnos.watch.protocol.ido

import br.com.grupotechnos.watch.protocol.profile.DeviceRegistry
import br.com.grupotechnos.watch.protocol.profile.Family
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.profile.StepsAggregation
import br.com.grupotechnos.watch.protocol.util.Hex
import kotlinx.datetime.LocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Regras do codec iDO além dos vetores dourados: fuso, dia da semana, autenticação, recepção e perfil. */
class IdoPayloadsTest {
	private val hour = 3_600_000L

	@Test
	fun fractionalTimeZoneIsHundredthsOfAnHourWithWestAbove1200() {
		assertEquals(1500, IdoTimeCodec.timeZoneValue(-3 * hour, fractionalZone = true))
		assertEquals(1550, IdoTimeCodec.timeZoneValue(-(3 * hour + hour / 2), fractionalZone = true))
		assertEquals(2400, IdoTimeCodec.timeZoneValue(-12 * hour, fractionalZone = true))
		assertEquals(0, IdoTimeCodec.timeZoneValue(0, fractionalZone = true))
		assertEquals(550, IdoTimeCodec.timeZoneValue(5 * hour + hour / 2, fractionalZone = true))
		assertEquals(575, IdoTimeCodec.timeZoneValue(5 * hour + 45 * 60_000L, fractionalZone = true))
		assertEquals(1400, IdoTimeCodec.timeZoneValue(14 * hour, fractionalZone = true))
	}

	@Test
	fun wholeHourTimeZoneHasWestAbove12() {
		assertEquals(15, IdoTimeCodec.timeZoneValue(-3 * hour, fractionalZone = false))
		assertEquals(17, IdoTimeCodec.timeZoneValue(-5 * hour, fractionalZone = false))
		assertEquals(9, IdoTimeCodec.timeZoneValue(9 * hour, fractionalZone = false))
		assertEquals(0, IdoTimeCodec.timeZoneValue(0, fractionalZone = false))
	}

	@Test
	fun weekStartsOnMondayAtZero() {
		// 2026-10-26 é uma segunda-feira; 2026-11-01, um domingo.
		val days = (26..31).map { LocalDateTime(2026, 10, it, 12, 0, 0) } + LocalDateTime(2026, 11, 1, 12, 0, 0)
		assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), days.map { IdoTimeCodec.weekDay(it) })
		assertEquals(3, IdoTimeCodec.encode(LocalDateTime(2026, 10, 29, 0, 0, 0), 0, fractionalZone = true)[7].toInt())
	}

	@Test
	fun setTimeWritesYearLittleEndianAndZoneLast() {
		val payload = IdoTimeCodec.encode(LocalDateTime(2027, 1, 2, 3, 4, 5), offsetMillis = 9 * hour, fractionalZone = true)
		// 2027 = 0x07eb; sábado = 5; +9 h = 900 = 0x0384.
		assertEquals("EB0701020304050500000000" + "8403", Hex.encode(payload))
	}

	@Test
	fun authReplyIsSecondHalfThenXorOfBothHalves() {
		val random = Random(20261029)
		repeat(50) {
			val challenge = random.nextBytes(12)
			val version = random.nextInt(0, 256)
			val reply = IdoAuth.reply(version, challenge)
			assertEquals(14, reply.size)
			assertEquals(12, reply[0].toInt())
			assertEquals(version, reply[1].toInt() and 0xFF)
			for (i in 0 until 6) {
				assertEquals(challenge[6 + i], reply[2 + i], "byte ${2 + i}")
				assertEquals((challenge[i].toInt() xor challenge[6 + i].toInt()).toByte(), reply[8 + i], "byte ${8 + i}")
			}
		}
	}

	@Test
	fun authReplyNeedsTwelveBytes() {
		assertFailsWith<IllegalArgumentException> { IdoAuth.reply(0, ByteArray(11)) }
		assertFailsWith<IllegalArgumentException> { IdoAuth.reply(0, ByteArray(13)) }
	}

	@Test
	fun shortRepliesArePaddedWithZerosAndV3PacketsAreSetApart() {
		val padded = assertIs<IdoDecoded.Frame>(IdoFrameCodec.decode(Hex.decode("0301"))).frame
		assertEquals(20, padded.bytes.size)
		assertEquals(0, padded.u8(2))
		val long = assertIs<IdoDecoded.Frame>(IdoFrameCodec.decode(ByteArray(24) { 1 })).frame
		assertEquals(24, long.bytes.size)
		assertEquals(IdoDecoded.V3, IdoFrameCodec.decode(Hex.decode("33daaddaad0142001a00")))
		assertEquals(IdoDecoded.V3, IdoFrameCodec.decode(Hex.decode("33")))
		assertEquals(IdoDecoded.TooShort, IdoFrameCodec.decode(Hex.decode("02")))
		assertEquals(IdoDecoded.TooShort, IdoFrameCodec.decode(ByteArray(0)))
	}

	@Test
	fun framesNeverExceedTwentyBytes() {
		assertEquals(20, IdoFrameCodec.encode(0x02, 0x01, ByteArray(18)).size)
		assertFailsWith<IllegalArgumentException> { IdoFrameCodec.encode(0x02, 0x01, ByteArray(19)) }
		assertFailsWith<IllegalArgumentException> { IdoFrameCodec.encode(0x100, 0x01) }
	}

	@Test
	fun describeNamesKnownCommands() {
		assertEquals("02/01 GET_BASIC_INFO len=2", IdoFrameCodec.describe(Hex.decode("0201")))
		assertEquals("04/05 BIND_AUTH len=20", IdoFrameCodec.describe(ByteArray(20).also { it[0] = 4; it[1] = 5 }))
		assertEquals("07/40 UNKNOWN len=3", IdoFrameCodec.describe(Hex.decode("074001")))
		assertEquals("V3 len=5", IdoFrameCodec.describe(Hex.decode("33daaddaad")))
		assertEquals("", IdoFrameCodec.describe(Hex.decode("02")))
	}

	@Test
	fun lifeUltraGpsIsIdentifiedByItsNames() {
		for (name in listOf("Life Ultra GPS", "Smart Watch 6010")) {
			assertEquals(Profiles.MORMAII_LIFE_ULTRA_GPS, DeviceRegistry.identify(name, emptyList()), name)
		}
		assertNull(DeviceRegistry.identify("Life Ultra", emptyList()))
		val profile = DeviceRegistry.byId("lifeultragps") ?: error("profile missing")
		assertEquals(Family.IDO, profile.family)
		assertEquals("Life Ultra GPS", profile.appName)
		assertEquals(StepsAggregation.DAY_CUMULATIVE, profile.quirks.stepsAggregation)
		assertEquals(listOf("00000af2-0000-1000-8000-00805f9b34fb"), profile.gattCandidates.single().extraNotify)
		assertEquals(emptyList(), Profiles.MORMAII_FIT_GPS.gattCandidates.flatMap { it.extraNotify })
	}

	@Test
	fun bindRequestIsTheCapturedPayload() {
		assertContentEquals(Hex.decode("f1010102020100"), IdoBindPayload.REQUEST)
	}
}
