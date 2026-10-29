package br.com.grupotechnos.watch.protocol.sma

import br.com.grupotechnos.watch.protocol.profile.BindPayloadKind
import br.com.grupotechnos.watch.protocol.sma.commands.SmaBatteryPayload
import br.com.grupotechnos.watch.protocol.sma.commands.SmaBindPayload
import br.com.grupotechnos.watch.protocol.sma.commands.SmaCommand
import br.com.grupotechnos.watch.protocol.sma.commands.SmaCommandStatus
import br.com.grupotechnos.watch.protocol.sma.commands.SmaTimeCodec
import br.com.grupotechnos.watch.protocol.sma.commands.SmaTimezoneCodec
import br.com.grupotechnos.watch.protocol.sma.commands.SmaUserInfoCodec
import br.com.grupotechnos.watch.protocol.sma.commands.SmaVersionPayload
import br.com.grupotechnos.watch.protocol.util.ByteReader
import br.com.grupotechnos.watch.protocol.util.Hex
import kotlinx.datetime.LocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmaTimeCodecTest {
	@Test
	fun goldenValue() {
		assertEquals(0x6AB8E780L, SmaTimeCodec.pack(2026, 10, 28, 14, 30, 0))
		assertContentEquals(Hex.decode("6A B8 E7 80"), SmaTimeCodec.encode(LocalDateTime(2026, 10, 28, 14, 30, 0)))
	}

	@Test
	fun edges() {
		assertEquals(0x00420000L, SmaTimeCodec.pack(2000, 1, 1, 0, 0, 0))
		assertEquals(0xFF3F7EFBL, SmaTimeCodec.pack(2063, 12, 31, 23, 59, 59))
		assertEquals(0x6AB97EFBL, SmaTimeCodec.pack(2026, 10, 28, 23, 59, 59))
		assertEquals(LocalDateTime(2063, 12, 31, 23, 59, 59), SmaTimeCodec.decode(Hex.decode("FF 3F 7E FB")))
	}

	@Test
	fun rejectsYearsOutsideTheSixBitField() {
		assertFailsWith<IllegalArgumentException> { SmaTimeCodec.pack(1999, 12, 31, 23, 59, 59) }
		assertFailsWith<IllegalArgumentException> { SmaTimeCodec.pack(2064, 1, 1, 0, 0, 0) }
		assertFailsWith<IllegalArgumentException> { SmaTimeCodec.decode(ByteArray(3)) }
	}

	@Test
	fun propertyMatchesAndroidByteExpressions() {
		// SmaTime.toByteArray (SmaTime.java:26-33), copiado byte a byte.
		val random = Random(2026)
		repeat(5000) {
			val year = random.nextInt(0, 64)
			val month = random.nextInt(1, 13)
			val day = random.nextInt(1, 29)
			val hour = random.nextInt(0, 24)
			val minute = random.nextInt(0, 60)
			val second = random.nextInt(0, 60)
			val android = byteArrayOf(
				(month shr 2 or (year shl 2)).toByte(),
				(hour shr 4 or (day shl 1) or (month shl 6)).toByte(),
				(minute shr 2 or (hour shl 4)).toByte(),
				(second or (minute shl 6)).toByte(),
			)
			val local = LocalDateTime(2000 + year, month, day, hour, minute, second)
			assertContentEquals(android, SmaTimeCodec.encode(local))
			assertEquals(local, SmaTimeCodec.decode(android))
		}
	}
}

class SmaTimezoneCodecTest {
	@Test
	fun quartersOfAnHourTruncatedTowardZero() {
		assertContentEquals(Hex.decode("F4"), SmaTimezoneCodec.encode(-3 * 3600))
		assertContentEquals(Hex.decode("F2"), SmaTimezoneCodec.encode(-(3 * 3600 + 30 * 60)))
		assertContentEquals(Hex.decode("16"), SmaTimezoneCodec.encode(5 * 3600 + 30 * 60))
		assertContentEquals(Hex.decode("17"), SmaTimezoneCodec.encode(5 * 3600 + 45 * 60))
		assertContentEquals(Hex.decode("38"), SmaTimezoneCodec.encode(14 * 3600))
		assertContentEquals(Hex.decode("00"), SmaTimezoneCodec.encode(0))
		// -10 min: Java e Objective-C truncam em direção a zero, então vira 0 (não -1).
		assertContentEquals(Hex.decode("00"), SmaTimezoneCodec.encode(-600))
	}

	@Test
	fun offsetsAboveOneDayBecomeZeroLikeAndroid() {
		assertContentEquals(Hex.decode("00"), SmaTimezoneCodec.encode(25 * 3600))
		assertEquals(-3 * 3600, SmaTimezoneCodec.decode(Hex.decode("F4")))
	}
}

class SmaUserInfoCodecTest {
	@Test
	fun fixedCoreUserFollowsIosHalfCentimetre() {
		// 1 << 31 | 30 << 24 | 347 << 15 | 120 << 5
		assertContentEquals(Hex.decode("9E AD 8F 00"), SmaUserInfoCodec.encode(male = true, age = 30, heightCm = 173.5, weightKg = 60.0))
	}

	@Test
	fun sameLayoutAsAndroidExpressions() {
		// SmaUserInfo.toByteArray (SmaUserInfo.java:9-16), com altura inteira para não esbarrar no truncamento Android.
		val random = Random(5)
		repeat(2000) {
			val gender = random.nextInt(0, 2)
			val age = random.nextInt(0, 128)
			val height = random.nextInt(0, 256)
			val weightHalves = random.nextInt(0, 1024)
			val weight = weightHalves / 2.0f
			val android = byteArrayOf(
				(gender shl 7 or age).toByte(),
				(height * 2 shr 1).toByte(),
				((weight * 2.0f).toInt() shr 3 or (height * 2 shl 7)).toByte(),
				((weight * 2.0f).toInt() shl 5).toByte(),
			)
			assertContentEquals(android, SmaUserInfoCodec.encode(gender == 1, age, height.toDouble(), weight.toDouble()))
		}
	}

	@Test
	fun rejectsOutOfRange() {
		assertFailsWith<IllegalArgumentException> { SmaUserInfoCodec.encode(true, 128, 170.0, 60.0) }
		assertFailsWith<IllegalArgumentException> { SmaUserInfoCodec.encode(true, 30, 256.0, 60.0) }
		assertFailsWith<IllegalArgumentException> { SmaUserInfoCodec.encode(true, 30, 170.0, 512.0) }
	}
}

class SmaSimplePayloadsTest {
	@Test
	fun bindPayloads() {
		val ff = SmaBindPayload.encode(BindPayloadKind.ANDROID_FF, 99)
		assertEquals(32, ff.size)
		assertEquals(0xFF.toByte(), ff[0])
		assertTrue(ff.drop(1).all { it.toInt() == 0 })
		val uid = SmaBindPayload.encode(BindPayloadKind.USER_ID, 0x01020304)
		assertEquals(32, uid.size)
		assertEquals(0x01020304L, ByteReader.u32At(uid, 0))
		assertTrue(uid.drop(4).all { it.toInt() == 0 })
	}

	@Test
	fun bindAndLoginAcceptance() {
		assertTrue(SmaBindPayload.isBindAccepted(byteArrayOf(0)))
		assertFalse(SmaBindPayload.isBindAccepted(byteArrayOf(1)))
		assertFalse(SmaBindPayload.isBindAccepted(ByteArray(0)))
		assertTrue(SmaBindPayload.isLoginAccepted(byteArrayOf(0)))
		assertTrue(SmaBindPayload.isLoginAccepted(ByteArray(0)))
		assertFalse(SmaBindPayload.isLoginAccepted(byteArrayOf(1)))
	}

	@Test
	fun version() {
		val full = SmaVersionPayload.decode(Hex.decode("01 02 03 04 05 06"))
		assertEquals("1.2.3", full.firmware)
		assertEquals("4.5.6", full.bleVersion)
		val short = SmaVersionPayload.decode(Hex.decode("0A FF 00"))
		assertEquals("10.255.0", short.firmware)
		assertNull(short.bleVersion)
		assertFailsWith<IllegalArgumentException> { SmaVersionPayload.decode(Hex.decode("01 02")) }
	}

	@Test
	fun battery() {
		assertEquals(85, SmaBatteryPayload.decode(byteArrayOf(85)))
		assertEquals(200, SmaBatteryPayload.decode(byteArrayOf(200.toByte())))
		assertFailsWith<IllegalArgumentException> { SmaBatteryPayload.decode(ByteArray(0)) }
	}
}

class SmaCommandTest {
	@Test
	fun catalogueIsUniqueAndConsistent() {
		val keys = SmaCommand.entries.map { it.cmd to it.key }
		assertEquals(keys.size, keys.toSet().size)
		for (command in SmaCommand.entries) {
			command.responseKey?.let { assertEquals(command.key + 1, it, command.name) }
		}
	}

	@Test
	fun namesForLog() {
		assertEquals("02/01 SET_TIME", SmaCommand.label(0x02, 0x01))
		assertEquals("02/09 BATTERY_BACK", SmaCommand.label(0x02, 0x09))
		assertEquals("05/42 SPORT_BACK", SmaCommand.label(0x05, 0x42))
		assertEquals("04/62 BLE_CONNECTION", SmaCommand.label(0x04, 0x62))
		assertEquals("02/99", SmaCommand.label(0x02, 0x99))
	}

	@Test
	fun pocStatus() {
		val sent = SmaCommand.entries.filter { it.status == SmaCommandStatus.SENT }.map { it.name }.toSet()
		assertEquals(setOf("BIND", "LOGIN", "UNBIND", "SET_TIME", "BATTERY", "VERSION", "SPORT", "SLEEP"), sent)
		assertEquals(SmaCommandStatus.NOT_SENT, SmaCommand.SYNC_SWITCH.status)
		assertEquals(SmaCommandStatus.CODEC_ONLY, SmaCommand.TIMEZONE.status)
		assertEquals(SmaCommandStatus.CODEC_ONLY, SmaCommand.USER_INFO.status)
	}
}
