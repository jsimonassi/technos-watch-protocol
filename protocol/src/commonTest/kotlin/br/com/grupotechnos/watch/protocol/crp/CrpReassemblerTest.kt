package br.com.grupotechnos.watch.protocol.crp

import br.com.grupotechnos.watch.protocol.util.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Remontagem de `FEE3` ([CrpReassembler]; `docs/protocolo-crp.md`, seção 3). */
class CrpReassemblerTest {
	private val drops = mutableListOf<String>()
	private val reassembler = CrpReassembler { drops += it }

	@Test
	fun frameInSeveralPackets() {
		// Uma página 59 (54 bytes) com MTU 23 chega em 20 + 20 + 14.
		val frame = Hex.decode("FEEA1036" + "5900" + "0100".repeat(24))
		assertEquals(emptyList(), reassembler.accept(frame.copyOfRange(0, 20)))
		assertTrue(reassembler.hasPartial)
		assertEquals(emptyList(), reassembler.accept(frame.copyOfRange(20, 40)))
		val out = reassembler.accept(frame.copyOfRange(40, 54))
		assertEquals(1, out.size)
		assertEquals(0x59, out.single().cmd)
		assertEquals(49, out.single().payload.size)
		assertFalse(reassembler.hasPartial)
		assertEquals(emptyList(), drops)
	}

	@Test
	fun highNibbles123AreAccepted() {
		for (b2 in listOf("10", "20", "30")) {
			val out = reassembler.accept(Hex.decode("FEEA${b2}065A00"))
			assertEquals(0x5A, out.single().cmd, b2)
		}
		val long = ByteArray(0x105).also { it[0] = 0xFE.toByte(); it[1] = 0xEA.toByte(); it[2] = 0x21; it[3] = 0x05; it[4] = 0x74 }
		assertEquals(0x100, reassembler.accept(long).single().payload.size)
	}

	@Test
	fun lengthAbove127IsUnsigned() {
		// A SDK lê o len com sinal quando b2 é 0x10 (sdk:m/a.java:3736-3738); aqui 0x90 é 144.
		val frame = ByteArray(0x90).also { it[0] = 0xFE.toByte(); it[1] = 0xEA.toByte(); it[2] = 0x10; it[3] = 0x90.toByte(); it[4] = 0x35 }
		assertEquals(emptyList(), reassembler.accept(frame.copyOfRange(0, 20)))
		var out: List<CrpFrame> = emptyList()
		var offset = 20
		while (offset < frame.size) {
			out = reassembler.accept(frame.copyOfRange(offset, minOf(frame.size, offset + 20)))
			offset += 20
		}
		assertEquals(0x90 - 5, out.single().payload.size)
	}

	@Test
	fun frameStartInTheMiddleResynchronizes() {
		assertEquals(emptyList(), reassembler.accept(Hex.decode("FEEA2036590000000000")))
		val out = reassembler.accept(Hex.decode("FEEA20065A01"))
		assertEquals(0x5A, out.single().cmd)
		assertEquals(1, drops.size)
		assertTrue(drops.single().contains("resync"))
	}

	@Test
	fun excessAfterTheDeclaredLengthIsDropped() {
		val out = reassembler.accept(Hex.decode("FEEA20065A00FFFF"))
		assertEquals("00", Hex.encode(out.single().payload))
		assertEquals(1, drops.size)
		reassembler.accept(Hex.decode("FEEA2008F901"))
		val joined = reassembler.accept(Hex.decode("0100AABB"))
		assertEquals("F9", joined.single().cmd.toString(16).uppercase())
		assertEquals("010100", Hex.encode(joined.single().payload))
		assertEquals(2, drops.size)
	}

	@Test
	fun looseContinuationIsDropped() {
		assertEquals(emptyList(), reassembler.accept(Hex.decode("0102030405")))
		assertEquals(1, drops.size)
		assertFalse(reassembler.hasPartial)
	}

	@Test
	fun resetDiscardsThePartial() {
		reassembler.accept(Hex.decode("FEEA2036590000"))
		reassembler.reset()
		assertFalse(reassembler.hasPartial)
		assertEquals(emptyList(), reassembler.accept(Hex.decode("0000")))
		assertEquals(1, drops.size)
	}
}
