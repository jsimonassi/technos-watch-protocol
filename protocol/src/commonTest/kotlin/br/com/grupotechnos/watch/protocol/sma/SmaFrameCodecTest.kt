package br.com.grupotechnos.watch.protocol.sma

import br.com.grupotechnos.watch.protocol.sma.codec.Crc16Arc
import br.com.grupotechnos.watch.protocol.sma.codec.SmaDecodeError
import br.com.grupotechnos.watch.protocol.sma.codec.SmaDecoded
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrame
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrameCodec
import br.com.grupotechnos.watch.protocol.sma.codec.SmaSequence
import br.com.grupotechnos.watch.protocol.util.Hex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Crc16ArcTest {
	@Test
	fun standardCheckValue() {
		assertEquals(0xBB3D, Crc16Arc.compute("123456789".encodeToByteArray()))
	}

	@Test
	fun matchesHardcodedIosOtaFrame() {
		// SmaBusinessTool.m:204-222 grava 00 6C à mão como CRC do L2 01 00 01 00 00.
		assertEquals(0x006C, Crc16Arc.compute(Hex.decode("01 00 01 00 00")))
	}

	@Test
	fun emptyInputIsZero() {
		assertEquals(0, Crc16Arc.compute(ByteArray(0)))
	}

	@Test
	fun tableMatchesSdkTables() {
		// Primeira linha: SmaBleHelper.java:6 (0, 49345, 49537, 320, ...) e SmaBusinessTool.m:16.
		assertEquals(listOf(0x0000, 0xC0C1, 0xC181, 0x0140, 0xC301, 0x03C0, 0x0280, 0xC241), Crc16Arc.table.take(8))
		// Última linha: SmaBusinessTool.m:47.
		assertEquals(listOf(0x8201, 0x42C0, 0x4380, 0x8341, 0x4100, 0x81C1, 0x8081, 0x4040), Crc16Arc.table.takeLast(8))
	}

	@Test
	fun offsetAndLength() {
		val bytes = Hex.decode("FF FF 31 32 33 34 35 36 37 38 39 FF")
		assertEquals(0xBB3D, Crc16Arc.compute(bytes, offset = 2, length = 9))
	}
}

class SmaFrameCodecTest {
	private val battery = Hex.decode("AB 00 00 05 02 F8 00 05 02 00 08 00 00")

	@Test
	fun rejectsBadMagic() {
		val bytes = battery.copyOf().also { it[0] = 0xAC.toByte() }
		assertEquals(SmaDecodeError.BAD_MAGIC, (SmaFrameCodec.decode(bytes) as SmaDecoded.Invalid).error)
		assertEquals(SmaDecodeError.BAD_MAGIC, (SmaFrameCodec.decode(ByteArray(0)) as SmaDecoded.Invalid).error)
	}

	@Test
	fun rejectsTruncated() {
		val short = SmaFrameCodec.decode(battery.copyOf(12)) as SmaDecoded.Invalid
		assertEquals(SmaDecodeError.TRUNCATED, short.error)
		assertEquals(5, short.sequence)
		assertEquals(SmaDecodeError.TRUNCATED, (SmaFrameCodec.decode(battery.copyOf(5)) as SmaDecoded.Invalid).error)
	}

	@Test
	fun rejectsTrailingBytes() {
		val result = SmaFrameCodec.decode(battery + byteArrayOf(0)) as SmaDecoded.Invalid
		assertEquals(SmaDecodeError.BAD_LENGTH, result.error)
	}

	@Test
	fun rejectsCrcMismatchKeepingSequence() {
		val bytes = battery.copyOf().also { it[10] = 0x09 }
		val result = SmaFrameCodec.decode(bytes) as SmaDecoded.Invalid
		assertEquals(SmaDecodeError.CRC_MISMATCH, result.error)
		assertEquals(5, result.sequence)
	}

	@Test
	fun rejectsInnerLengthMismatchEvenWithGoodCrc() {
		// L2 de 6 bytes declarando value de 0: CRC certo, tamanhos incoerentes.
		val l2 = Hex.decode("02 00 08 00 00 55")
		val crc = Crc16Arc.compute(l2)
		val bytes = Hex.decode("AB 00 00 06") + byteArrayOf((crc ushr 8).toByte(), crc.toByte(), 0, 1) + l2
		assertEquals(SmaDecodeError.BAD_LENGTH, (SmaFrameCodec.decode(bytes) as SmaDecoded.Invalid).error)
	}

	@Test
	fun rejectsOversized() {
		val header = Hex.decode("AB 00 07 F9 00 00 00 01")
		assertEquals(2049, SmaFrameCodec.frameLength(header))
		assertEquals(SmaDecodeError.OVERSIZED, (SmaFrameCodec.decode(header) as SmaDecoded.Invalid).error)
	}

	@Test
	fun largestFrameIs2048Bytes() {
		val bytes = SmaFrameCodec.encode(1, 0x05, 0x60, ByteArray(SmaFrameCodec.MAX_VALUE_SIZE))
		assertEquals(2048, bytes.size)
		assertIs<SmaDecoded.Data>(SmaFrameCodec.decode(bytes))
		assertFailsWith<IllegalArgumentException> { SmaFrameCodec.encode(1, 0x05, 0x60, ByteArray(SmaFrameCodec.MAX_VALUE_SIZE + 1)) }
	}

	@Test
	fun controlDetection() {
		assertTrue(SmaFrameCodec.isControl(SmaFrameCodec.ack(1)))
		assertTrue(SmaFrameCodec.isControl(SmaFrameCodec.nack(1)))
		assertFalse(SmaFrameCodec.isControl(battery.copyOf(8)))
		assertFalse(SmaFrameCodec.isControl(battery))
	}

	@Test
	fun frameLengthNeedsFourBytesAndMagic() {
		assertNull(SmaFrameCodec.frameLength(Hex.decode("AB 00 00")))
		assertNull(SmaFrameCodec.frameLength(Hex.decode("AC 00 00 05")))
		assertEquals(13, SmaFrameCodec.frameLength(battery))
	}

	@Test
	fun unsignedLengthAbove127() {
		// O bug de SmaBleHelper.java:43 (res[2] << 8 com sinal) não se repete: 0x80 no byte alto do tamanho.
		val header = Hex.decode("AB 00 80 00 00 00 00 01")
		assertEquals(0x8000 + 8, SmaFrameCodec.frameLength(header))
	}

	@Test
	fun propertyEncodeDecodeIdentity() {
		val random = Random(20261028)
		repeat(2000) {
			val frame = SmaFrame(
				sequence = random.nextInt(0, 0x10000),
				cmd = random.nextInt(0, 256),
				key = random.nextInt(0, 256),
				value = random.nextBytes(random.nextInt(0, 400)),
			)
			val bytes = SmaFrameCodec.encode(frame)
			assertEquals(frame.value.size + 13, bytes.size)
			assertEquals(SmaDecoded.Data(frame), SmaFrameCodec.decode(bytes))
		}
	}

	@Test
	fun propertyAnySingleByteCorruptionInL2IsDetected() {
		val random = Random(7)
		repeat(500) {
			val bytes = SmaFrameCodec.encode(random.nextInt(0, 0x10000), 0x05, 0x42, random.nextBytes(random.nextInt(1, 160)))
			val index = random.nextInt(SmaFrameCodec.HEADER_SIZE, bytes.size)
			bytes[index] = (bytes[index].toInt() xor (1 shl random.nextInt(0, 8))).toByte()
			assertIs<SmaDecoded.Invalid>(SmaFrameCodec.decode(bytes))
		}
	}
}

class SmaSequenceTest {
	@Test
	fun startsAtOneAndWrapsAfterFfff() {
		val sequence = SmaSequence()
		assertEquals(1, sequence.next())
		assertEquals(2, sequence.next())
		val nearEnd = SmaSequence(first = 0xFFFE)
		assertEquals(listOf(0xFFFE, 0xFFFF, 0x0000, 0x0001), List(4) { nearEnd.next() })
	}
}
