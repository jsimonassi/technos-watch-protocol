package br.com.grupotechnos.watch.protocol.sma

import br.com.grupotechnos.watch.protocol.profile.BindPayloadKind
import br.com.grupotechnos.watch.protocol.sma.codec.SmaDecoded
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrame
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrameCodec
import br.com.grupotechnos.watch.protocol.sma.commands.SmaBindPayload
import br.com.grupotechnos.watch.protocol.sma.commands.SmaCommand
import br.com.grupotechnos.watch.protocol.sma.commands.SmaTimeCodec
import br.com.grupotechnos.watch.protocol.sma.commands.SmaTimezoneCodec
import br.com.grupotechnos.watch.protocol.sma.commands.SmaUnbindPayload
import br.com.grupotechnos.watch.protocol.util.Hex
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Os 10 vetores dourados de `docs/protocolo-sma.md`, seção 9. Cada vetor foi conferido de forma independente
 * (CRC-16/ARC bit a bit em Python) antes de entrar aqui. Mudou um byte do catálogo: atualize a doc e este teste juntos.
 */
class SmaFrameGoldenTest {
	private class Vector(val name: String, val frame: SmaFrame, val hex: String)

	private val zeros31 = " 00".repeat(31)

	private val vectors = listOf(
		Vector(
			"BIND",
			SmaFrame(1, SmaCommand.BIND.cmd, SmaCommand.BIND.key, SmaBindPayload.encode(BindPayloadKind.ANDROID_FF, 1)),
			"AB 00 00 25 19 FB 00 01 03 00 01 00 20 FF$zeros31",
		),
		Vector(
			"LOGIN",
			SmaFrame(2, SmaCommand.LOGIN.cmd, SmaCommand.LOGIN.key, SmaBindPayload.encode(BindPayloadKind.ANDROID_FF, 1)),
			"AB 00 00 25 D9 A0 00 02 03 00 03 00 20 FF$zeros31",
		),
		Vector(
			"SET_TIME 2026-10-28 14:30:00",
			SmaFrame(3, 0x02, 0x01, SmaTimeCodec.encode(LocalDateTime(2026, 10, 28, 14, 30, 0))),
			"AB 00 00 09 CD 2F 00 03 02 00 01 00 04 6A B8 E7 80",
		),
		Vector(
			"TIMEZONE -3h",
			SmaFrame(4, 0x02, 0x68, SmaTimezoneCodec.encode(-3 * 3600)),
			"AB 00 00 06 95 1D 00 04 02 00 68 00 01 F4",
		),
		Vector("BATTERY", SmaFrame(5, 0x02, 0x08, ByteArray(0)), "AB 00 00 05 02 F8 00 05 02 00 08 00 00"),
		Vector("VERSION", SmaFrame(6, 0x02, 0x0A, ByteArray(0)), "AB 00 00 05 C2 59 00 06 02 00 0A 00 00"),
		Vector("SPORT", SmaFrame(7, 0x05, 0x41, ByteArray(0)), "AB 00 00 05 14 9C 00 07 05 00 41 00 00"),
		Vector("SLEEP", SmaFrame(8, 0x05, 0x45, ByteArray(0)), "AB 00 00 05 D5 DD 00 08 05 00 45 00 00"),
		Vector("UNBIND", SmaFrame(9, 0x03, 0x05, SmaUnbindPayload.encode()), "AB 00 00 06 6F 01 00 09 03 00 05 00 01 00"),
	)

	@Test
	fun encodesEveryVector() {
		for (vector in vectors) {
			assertEquals(Hex.encode(Hex.decode(vector.hex)), Hex.encode(SmaFrameCodec.encode(vector.frame)), vector.name)
		}
	}

	@Test
	fun decodesEveryVector() {
		for (vector in vectors) {
			assertEquals(SmaDecoded.Data(vector.frame), SmaFrameCodec.decode(Hex.decode(vector.hex)), vector.name)
		}
	}

	@Test
	fun ackVectorBothWays() {
		val hex = "AB 10 00 00 00 00 12 34"
		assertEquals(Hex.encode(Hex.decode(hex)), Hex.encode(SmaFrameCodec.ack(0x1234)))
		assertEquals(SmaDecoded.Ack(0x1234), SmaFrameCodec.decode(Hex.decode(hex)))
	}

	@Test
	fun nackBothWays() {
		val hex = "AB 30 00 00 00 00 12 34"
		assertEquals(Hex.encode(Hex.decode(hex)), Hex.encode(SmaFrameCodec.nack(0x1234)))
		assertEquals(SmaDecoded.Nack(0x1234), SmaFrameCodec.decode(Hex.decode(hex)))
	}

	@Test
	fun userIdBindPayloadMatchesIosLayout() {
		// bindBloacUserID com userId 1 (SmaBLE.m:559-570; SMAService.m:1093): 00 00 00 01 + 28 zeros.
		val frame = SmaFrame(1, 0x03, 0x01, SmaBindPayload.encode(BindPayloadKind.USER_ID, 1))
		assertEquals(
			Hex.encode(Hex.decode("AB 00 00 25 FC 4A 00 01 03 00 01 00 20 00 00 00 01" + " 00".repeat(28))),
			Hex.encode(SmaFrameCodec.encode(frame)),
		)
	}
}
