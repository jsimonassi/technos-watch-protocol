package br.com.grupotechnos.watch.protocol.crp

import br.com.grupotechnos.watch.protocol.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Regras do [CrpFrameCodec]: bloco, byte 2, comprimento de 12 bits e fragmentos (`docs/protocolo-crp.md`, seção 3). */
class CrpFrameCodecTest {
	@Test
	fun blockSizeFollowsTheSdkRule() {
		// sdk:l/a.java:51-57: min(MTU, 250) − 3 arredondado para baixo a múltiplo de 4.
		assertEquals(20, CrpFrameCodec.blockSize(23, CrpProtocolVersion.V2))
		assertEquals(180, CrpFrameCodec.blockSize(185, CrpProtocolVersion.V2))
		assertEquals(244, CrpFrameCodec.blockSize(247, CrpProtocolVersion.V2))
		assertEquals(244, CrpFrameCodec.blockSize(512, CrpProtocolVersion.V2))
		assertEquals(244, CrpFrameCodec.blockSize(517, CrpProtocolVersion.V3))
		assertEquals(20, CrpFrameCodec.blockSize(517, CrpProtocolVersion.V1))
	}

	@Test
	fun byte2CarriesTheHighLengthBits() {
		val big = CrpFrameCodec.encode(0x74, ByteArray(300) { it.toByte() }, 244)
		assertEquals(305, big.size)
		assertEquals("FEEA2131", Hex.encode(big.copyOfRange(0, 4)))
		assertEquals(305, CrpFrameCodec.totalLength(big))
		val small = CrpFrameCodec.encode(0x5A, byteArrayOf(0), 20)
		assertEquals("FEEA10065A00", Hex.encode(small))
	}

	@Test
	fun block20RefusesFramesAbove255() {
		assertFailsWith<IllegalArgumentException> { CrpFrameCodec.encode(0x74, ByteArray(251), 20) }
		assertEquals(255, CrpFrameCodec.encode(0x74, ByteArray(250), 20).size)
		assertFailsWith<IllegalArgumentException> { CrpFrameCodec.encode(0x74, ByteArray(CrpFrameCodec.MAX_TOTAL), 244) }
		assertFailsWith<IllegalArgumentException> { CrpFrameCodec.encode(0x100, ByteArray(0), 244) }
	}

	@Test
	fun framesAreCutInRawBlocks() {
		val frame = CrpFrameCodec.encode(0x74, ByteArray(40) { it.toByte() }, 20)
		val parts = CrpFrameCodec.fragments(frame, 20)
		assertEquals(listOf(20, 20, 5), parts.map { it.size })
		assertEquals(Hex.encode(frame), parts.joinToString("") { Hex.encode(it) })
		assertEquals(1, CrpFrameCodec.fragments(CrpFrameCodec.encode(0x5A, byteArrayOf(0), 244), 244).size)
	}

	@Test
	fun totalLengthRejectsWhatIsNotAFrameStart() {
		assertNull(CrpFrameCodec.totalLength(Hex.decode("FEEA2006")))
		assertNull(CrpFrameCodec.totalLength(Hex.decode("FEEB20065A")))
		assertNull(CrpFrameCodec.totalLength(Hex.decode("FEEA40065A")))
		assertNull(CrpFrameCodec.totalLength(Hex.decode("FEEA20045A")))
		assertEquals(0x90, CrpFrameCodec.totalLength(Hex.decode("FEEA10905A")))
		assertEquals(0x106, CrpFrameCodec.totalLength(Hex.decode("FEEA31065A")))
	}

	@Test
	fun describeNamesTheCommandWithoutThePayload() {
		assertEquals("5A/00 PROTOCOL_VERSION len=6 b2=20", CrpFrameCodec.describe(Hex.decode("FEEA20065A00")))
		assertEquals("32 SLEEP_TODAY len=5 b2=20", CrpFrameCodec.describe(Hex.decode("FEEA200532")))
		assertEquals("B6/01/00 HISTORY len=7 b2=20", CrpFrameCodec.describe(Hex.decode("FEEA2007B60100")))
		assertEquals("41 UNKNOWN len=6 b2=20", CrpFrameCodec.describe(Hex.decode("FEEA20064101")))
		assertEquals("", CrpFrameCodec.describe(Hex.decode("000102")))
	}

	@Test
	fun protocolVersionUsesContainsAndTrimsNul() {
		assertEquals(CrpProtocolVersion.V2, CrpProtocolVersion.parse("MOYOUNG-V2"))
		assertEquals(CrpProtocolVersion.V2, CrpProtocolVersion.parse(" MOYOUNG-V2\u0000\u0000"))
		assertEquals(CrpProtocolVersion.V3, CrpProtocolVersion.parse("XMOYOUNG-V3"))
		assertEquals(CrpProtocolVersion.V1, CrpProtocolVersion.parse("MOYOUNG"))
		assertEquals(CrpProtocolVersion.V1, CrpProtocolVersion.parse(""))
	}

	@Test
	fun postDelayCopiesTheSdkQueue() {
		assertEquals(1_000, CrpCommand.postDelayMs(0x33, 200))
		assertEquals(500, CrpCommand.postDelayMs(0x12, 200))
		assertEquals(500, CrpCommand.postDelayMs(0x35, 200))
		assertEquals(50, CrpCommand.postDelayMs(0x67, 200))
		assertEquals(200, CrpCommand.postDelayMs(0x5A, 200))
		assertEquals(200, CrpCommand.postDelayMs(0x32, 200))
	}
}
