package br.com.grupotechnos.watch.protocol.sma

import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.sma.codec.DropReason
import br.com.grupotechnos.watch.protocol.sma.codec.SmaDecodeError
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFragmenter
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrame
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrameCodec
import br.com.grupotechnos.watch.protocol.sma.codec.SmaReassembler
import br.com.grupotechnos.watch.protocol.sma.codec.SmaReassemblyEvent
import br.com.grupotechnos.watch.protocol.util.Hex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmaReassemblerTest {
	private val gap = 8_000L

	private fun sportFrame(records: Int, seq: Int = 0x0101): SmaFrame =
		SmaFrame(seq, 0x05, 0x42, ByteArray(records * 8) { (it * 7).toByte() })

	@Test
	fun headerAndBodyInSeparateNotifications() {
		val reassembler = SmaReassembler(gap)
		val frame = SmaFrame(0x0102, 0x02, 0x09, byteArrayOf(0x55))
		val packets = FakeSmaWatch.firmwareFragments(SmaFrameCodec.encode(frame))
		assertEquals(listOf(8, 6), packets.map { it.size })
		assertTrue(reassembler.accept(packets[0], 0).isEmpty())
		assertTrue(reassembler.inProgress)
		val events = reassembler.accept(packets[1], 10)
		assertEquals(frame, (events.single() as SmaReassemblyEvent.Frame).frame)
		assertFalse(reassembler.inProgress)
	}

	@Test
	fun fullSportPageInManyFragments() {
		val reassembler = SmaReassembler(gap)
		val frame = sportFrame(20)
		val packets = FakeSmaWatch.firmwareFragments(SmaFrameCodec.encode(frame))
		assertEquals(1 + 9, packets.size)
		val events = packets.flatMapIndexed { index, packet -> reassembler.accept(packet, index * 30L) }
		assertEquals(frame, (events.single() as SmaReassemblyEvent.Frame).frame)
	}

	@Test
	fun headerAndBodyTogetherIsTolerated() {
		val reassembler = SmaReassembler(gap)
		val frame = sportFrame(3)
		val events = reassembler.accept(SmaFrameCodec.encode(frame), 0)
		assertEquals(frame, (events.single() as SmaReassemblyEvent.Frame).frame)
	}

	@Test
	fun twoFramesInOneNotification() {
		val reassembler = SmaReassembler(gap)
		val first = sportFrame(1, seq = 1)
		val second = sportFrame(2, seq = 2)
		val events = reassembler.accept(SmaFrameCodec.encode(first) + SmaFrameCodec.encode(second), 0)
		assertEquals(listOf(first, second), events.map { (it as SmaReassemblyEvent.Frame).frame })
	}

	@Test
	fun loneAbIsIgnoredWhenIdle() {
		val reassembler = SmaReassembler(gap)
		val events = reassembler.accept(byteArrayOf(0xAB.toByte()), 0)
		assertEquals(DropReason.STRAY_AB, (events.single() as SmaReassemblyEvent.Dropped).reason)
		assertFalse(reassembler.inProgress)
	}

	@Test
	fun loneAbInsideFrameIsBody() {
		val reassembler = SmaReassembler(gap)
		val frame = SmaFrame(9, 0x02, 0x0B, byteArrayOf(1, 2, 0xAB.toByte()))
		val bytes = SmaFrameCodec.encode(frame)
		val events = mutableListOf<SmaReassemblyEvent>()
		events += reassembler.accept(bytes.copyOfRange(0, bytes.size - 1), 0)
		events += reassembler.accept(byteArrayOf(0xAB.toByte()), 5)
		assertEquals(frame, (events.single() as SmaReassemblyEvent.Frame).frame)
	}

	@Test
	fun ackBetweenFragmentsNeverEntersTheBody() {
		val reassembler = SmaReassembler(gap)
		val frame = sportFrame(4)
		val packets = FakeSmaWatch.firmwareFragments(SmaFrameCodec.encode(frame))
		val events = mutableListOf<SmaReassemblyEvent>()
		events += reassembler.accept(packets[0], 0)
		events += reassembler.accept(SmaFrameCodec.ack(0x0042), 1)
		events += reassembler.accept(SmaFrameCodec.nack(0x0043), 2)
		packets.drop(1).forEach { events += reassembler.accept(it, 3) }
		assertEquals(SmaReassemblyEvent.Control(0x0042, nack = false), events[0])
		assertEquals(SmaReassemblyEvent.Control(0x0043, nack = true), events[1])
		assertEquals(frame, (events[2] as SmaReassemblyEvent.Frame).frame)
		assertEquals(3, events.size)
	}

	@Test
	fun badCrcIsReportedWithSequence() {
		val reassembler = SmaReassembler(gap)
		val bytes = SmaFrameCodec.encode(sportFrame(2, seq = 0x0777))
		bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
		val event = reassembler.accept(bytes, 0).single() as SmaReassemblyEvent.Corrupted
		assertEquals(SmaDecodeError.CRC_MISMATCH, event.error)
		assertEquals(0x0777, event.sequence)
	}

	@Test
	fun gapAboveTimeoutDropsPartialFrame() {
		val reassembler = SmaReassembler(gap)
		val packets = FakeSmaWatch.firmwareFragments(SmaFrameCodec.encode(sportFrame(2)))
		reassembler.accept(packets[0], 1_000)
		val events = reassembler.accept(packets[1], 1_000 + gap + 1)
		assertEquals(DropReason.GAP_TIMEOUT, (events[0] as SmaReassemblyEvent.Dropped).reason)
		assertEquals(DropReason.GARBAGE, (events[1] as SmaReassemblyEvent.Dropped).reason)
		assertFalse(reassembler.inProgress)
	}

	@Test
	fun gapExactlyAtTimeoutIsAccepted() {
		val reassembler = SmaReassembler(gap)
		val frame = SmaFrame(3, 0x02, 0x09, byteArrayOf(0x40))
		val packets = FakeSmaWatch.firmwareFragments(SmaFrameCodec.encode(frame))
		reassembler.accept(packets[0], 0)
		assertIs<SmaReassemblyEvent.Frame>(reassembler.accept(packets[1], gap).single())
	}

	@Test
	fun garbageBeforeMagicIsSkipped() {
		val reassembler = SmaReassembler(gap)
		val frame = sportFrame(1)
		val events = reassembler.accept(Hex.decode("01 02 03") + SmaFrameCodec.encode(frame), 0)
		val dropped = events[0] as SmaReassemblyEvent.Dropped
		assertEquals(DropReason.GARBAGE, dropped.reason)
		assertEquals(3, dropped.byteCount)
		assertEquals(frame, (events[1] as SmaReassemblyEvent.Frame).frame)
	}

	@Test
	fun oversizedHeaderIsDropped() {
		val reassembler = SmaReassembler(gap)
		val event = reassembler.accept(Hex.decode("AB 00 FF FF 00 00 00 01"), 0).single() as SmaReassemblyEvent.Dropped
		assertEquals(DropReason.OVERSIZED, event.reason)
		assertFalse(reassembler.inProgress)
	}

	@Test
	fun shortHeaderIsBufferedUntilComplete() {
		val reassembler = SmaReassembler(gap)
		val frame = SmaFrame(5, 0x02, 0x09, byteArrayOf(0x33))
		val bytes = SmaFrameCodec.encode(frame)
		assertTrue(reassembler.accept(bytes.copyOfRange(0, 3), 0).isEmpty())
		assertTrue(reassembler.accept(bytes.copyOfRange(3, 9), 1).isEmpty())
		assertEquals(frame, (reassembler.accept(bytes.copyOfRange(9, bytes.size), 2).single() as SmaReassemblyEvent.Frame).frame)
	}

	@Test
	fun propertyAnyPartitionReconstructsTheFrame() {
		val random = Random(42)
		repeat(1000) {
			val reassembler = SmaReassembler(gap)
			val frame = SmaFrame(random.nextInt(0, 0x10000), 0x05, 0x42, random.nextBytes(random.nextInt(0, 300)))
			val bytes = SmaFrameCodec.encode(frame)
			val events = mutableListOf<SmaReassemblyEvent>()
			var start = 0
			var now = 0L
			while (start < bytes.size) {
				var end = minOf(bytes.size, start + random.nextInt(1, 40))
				// Uma notificação de 1 byte AB isolada no início seria descartada de propósito (regra do AB solto).
				if (start == 0 && end == 1) end = 2
				// Um pedaço de 8 bytes que pareça ACK (AB + bit 0x10) é controle por regra; o firmware nunca corta assim.
				if (end - start == 8 && SmaFrameCodec.isControl(bytes.copyOfRange(start, end))) end = start + 7
				events += reassembler.accept(bytes.copyOfRange(start, end), now)
				now += random.nextLong(0, 100)
				start = end
			}
			assertEquals(frame, (events.single() as SmaReassemblyEvent.Frame).frame)
		}
	}
}

class SmaFragmenterTest {
	@Test
	fun fragmentSizeRules() {
		val quirks = Profiles.MORMAII_FIT_GPS.quirks
		assertEquals(244, SmaFragmenter.fragmentSize(quirks, 247))
		assertEquals(20, SmaFragmenter.fragmentSize(quirks, 23))
		assertEquals(20, SmaFragmenter.fragmentSize(quirks.copy(maxFragment = 20), 247))
		assertEquals(20, SmaFragmenter.fragmentSize(quirks, 10))
	}

	@Test
	fun bindFrameLikeIos() {
		// SmaBLE.m:579-605: BIND de 45 bytes em 20 + 20 + 5.
		val bind = SmaFrameCodec.encode(1, 0x03, 0x01, ByteArray(32))
		assertEquals(listOf(20, 20, 5), SmaFragmenter.split(bind, 20).map { it.size })
		assertEquals(0xAB.toByte(), SmaFragmenter.split(bind, 20)[0][0])
	}

	@Test
	fun emptyAndInvalid() {
		assertTrue(SmaFragmenter.split(ByteArray(0), 20).isEmpty())
		assertFailsWith<IllegalArgumentException> { SmaFragmenter.split(ByteArray(3), 0) }
	}

	@Test
	fun propertyConcatOfSplitIsTheFrame() {
		val random = Random(99)
		for (size in 20..509) {
			val frame = SmaFrameCodec.encode(random.nextInt(0, 0x10000), 0x05, 0x60, random.nextBytes(random.nextInt(0, 1200)))
			val fragments = SmaFragmenter.split(frame, size)
			assertTrue(fragments.all { it.size in 1..size })
			assertEquals((frame.size + size - 1) / size, fragments.size)
			assertContentEquals(frame, fragments.fold(ByteArray(0)) { acc, part -> acc + part })
		}
	}
}
