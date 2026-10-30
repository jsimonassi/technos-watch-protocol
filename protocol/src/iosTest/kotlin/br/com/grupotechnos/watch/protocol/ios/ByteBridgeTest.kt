package br.com.grupotechnos.watch.protocol.ios

import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.testing.ReplayScenario
import br.com.grupotechnos.watch.protocol.testing.SyntheticFitGpsFixture
import br.com.grupotechnos.watch.protocol.testing.runReplay
import br.com.grupotechnos.watch.protocol.transport.TransportEvent
import br.com.grupotechnos.watch.protocol.transport.TransportEvents
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMutableData
import platform.Foundation.create
import platform.Foundation.dataWithLength
import platform.Foundation.increaseLengthBy
import platform.Foundation.subdataWithRange
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A ponte de bytes do iOS (`ByteBridge.kt`, `TransportEventsData.kt`). Os bytes do `NSData` são lidos direto do ponteiro
 * `bytes`, sem passar por [toByteArray], para a conferência não depender da função testada.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalCoroutinesApi::class)
class ByteBridgeTest {
	/** Os bytes de [data] lidos pelo ponteiro `bytes`, um a um. */
	private fun rawBytes(data: NSData): ByteArray {
		val size = data.length.toInt()
		if (size == 0) return ByteArray(0)
		val pointer = data.bytes!!.reinterpret<ByteVar>()
		return ByteArray(size) { pointer[it] }
	}

	/** Um `NSData` montado pelo `initWithBytes:length:`, sem passar por [toNSData]. */
	private fun nsDataOf(bytes: ByteArray): NSData =
		if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }

	@Test
	fun roundTripKeepsEveryByte() {
		// Um ACK do relógio seguido de bytes nas bordas do sinal; o último não é zero, para um corte no fim aparecer.
		val bytes = byteArrayOf(0xAB.toByte(), 0x10, 0x00, 0x00, 0x00, 0x00, 0x01, 0x07, 0x7F, 0x80.toByte(), 0xFF.toByte())
		val data = bytes.toNSData()
		assertEquals(bytes.size.toULong(), data.length)
		assertContentEquals(bytes, rawBytes(data))
		assertContentEquals(bytes, data.toByteArray())
	}

	@Test
	fun emptyConvertsSafelyBothWays() {
		val empty = ByteArray(0).toNSData()
		assertEquals(0uL, empty.length)
		assertEquals(0, NSData().toByteArray().size)
		// Um NSData vazio pode ter `bytes` nulo; a conversão não pode lê-lo.
		assertEquals(0, NSData.create(bytes = null, length = 0uL).toByteArray().size)
		assertEquals(0, NSMutableData.dataWithLength(0uL)!!.toByteArray().size)
		assertEquals(0, empty.toByteArray().size)
	}

	@Test
	fun allByteValuesSurviveWithTheirSign() {
		val all = ByteArray(256) { it.toByte() }
		val data = all.toNSData()
		val raw = rawBytes(data)
		for (value in 0..255) {
			assertEquals(value, raw[value].toInt() and 0xFF, "byte $value in the NSData")
		}
		val back = data.toByteArray()
		assertContentEquals(all, back)
		assertEquals(-1, back[255].toInt(), "0xFF is -1 as a Kotlin Byte")
		assertEquals(-128, back[128].toInt(), "0x80 is -128 as a Kotlin Byte")
	}

	@Test
	fun largeBufferConvertsBothWays() {
		val size = 64 * 1024
		val bytes = ByteArray(size) { ((it * 31 + 7) xor (it ushr 8)).toByte() }
		val data = bytes.toNSData()
		assertEquals(size.toULong(), data.length)
		assertContentEquals(bytes, rawBytes(data))
		assertContentEquals(bytes, nsDataOf(bytes).toByteArray())
	}

	@Test
	fun byteArrayToNSDataCopies() {
		val bytes = byteArrayOf(1, 2, 3, 4)
		val data = bytes.toNSData()
		bytes[0] = 99
		bytes.fill(0, 1)
		assertContentEquals(byteArrayOf(1, 2, 3, 4), rawBytes(data))
	}

	@Test
	fun nsDataToByteArrayCopies() {
		val mutable = NSMutableData.dataWithLength(4uL)!!
		val pointer = mutable.mutableBytes!!.reinterpret<ByteVar>()
		for (i in 0 until 4) pointer[i] = (i + 1).toByte()
		val bytes = mutable.toByteArray()
		pointer[0] = 99
		mutable.increaseLengthBy(8uL)
		assertContentEquals(byteArrayOf(1, 2, 3, 4), bytes)
		bytes[1] = 42
		assertEquals(2, mutable.mutableBytes!!.reinterpret<ByteVar>()[1].toInt(), "changing the array must not touch the NSData")
	}

	@Test
	fun subdataConvertsOnlyItsRange() {
		val base = nsDataOf(ByteArray(100) { it.toByte() })
		val slice = base.subdataWithRange(NSMakeRange(10uL, 20uL))
		assertContentEquals(ByteArray(20) { (it + 10).toByte() }, slice.toByteArray())
		val tail = base.subdataWithRange(NSMakeRange(99uL, 1uL))
		assertContentEquals(byteArrayOf(99), tail.toByteArray())
		assertEquals(0, base.subdataWithRange(NSMakeRange(50uL, 0uL)).toByteArray().size)
	}

	@Test
	fun nsDataOverloadsEnqueueTheSameEventsAsTheByteArrayOnes() {
		val viaBytes = mutableListOf<TransportEvent>()
		val viaData = mutableListOf<TransportEvent>()
		val bytesEvents = TransportEvents { viaBytes += it }
		val dataEvents = TransportEvents { viaData += it }
		val packet = ByteArray(20) { (0xF0 + it).toByte() }
		val characteristic = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
		// Tipado de propósito: um `null` literal casaria com o método membro (`ByteArray?`), que ganha da extensão, e a
		// sobrecarga com `NSData?` nunca rodaria.
		val noData: NSData? = null

		bytesEvents.onNotification(characteristic, packet)
		dataEvents.onNotification(characteristic, nsDataOf(packet))
		bytesEvents.onReadCompleted(7, packet, 0)
		dataEvents.onReadCompleted(7, nsDataOf(packet), 0)
		bytesEvents.onReadCompleted(8, null, 133)
		dataEvents.onReadCompleted(8, noData, 133)
		bytesEvents.onReadCompleted(9, ByteArray(0), 0)
		dataEvents.onReadCompleted(9, NSData(), 0)

		assertEquals(viaBytes.size, viaData.size)
		for ((expected, actual) in viaBytes.zip(viaData)) {
			when (expected) {
				is TransportEvent.Notification -> {
					actual as TransportEvent.Notification
					assertEquals(expected.characteristic, actual.characteristic)
					assertEquals("6e400003-b5a3-f393-e0a9-e50e24dcca9e", actual.characteristic)
					assertContentEquals(expected.value, actual.value)
				}
				is TransportEvent.ReadCompleted -> {
					actual as TransportEvent.ReadCompleted
					assertEquals(expected.requestId, actual.requestId)
					assertEquals(expected.status, actual.status)
					if (expected.value == null) assertNull(actual.value) else assertContentEquals(expected.value, actual.value)
				}
				else -> error("unexpected $expected")
			}
		}
	}

	@Test
	fun notificationKeepsACopyOfTheHostBuffer() {
		val captured = mutableListOf<TransportEvent>()
		val events = TransportEvents { captured += it }
		val buffer = NSMutableData.dataWithLength(3uL)!!
		val pointer = buffer.mutableBytes!!.reinterpret<ByteVar>()
		pointer[0] = 0xAB.toByte()
		events.onNotification("6e400003-b5a3-f393-e0a9-e50e24dcca9e", buffer)
		// O CoreBluetooth reutiliza o valor da characteristic: a sessão já tem de estar com a cópia.
		pointer[0] = 0
		assertContentEquals(byteArrayOf(0xAB.toByte(), 0, 0), (captured.single() as TransportEvent.Notification).value)
	}

	@Test
	fun syntheticRecordingReplaysWithNotificationsDeliveredAsNSData() = runTest {
		val recording = SyntheticFitGpsFixture.build()
		val delivered = mutableListOf<ByteArray>()
		val outcome = runReplay(recording, ReplayScenario.deduce(recording)) { transport ->
			transport.deliverNotification = { characteristic, value ->
				delivered += value.copyOf()
				transport.events.onNotification(characteristic, value.toNSData())
			}
		}
		try {
			assertTrue(outcome.replay.problems().isEmpty(), outcome.replay.problems().joinToString("\n"))
			assertEquals(outcome.replay.recordedTx, outcome.replay.matchedTx)
			val recordedRx = recording.packets.filter { it.direction == FrameDirection.RX }
			assertEquals(recordedRx.size, delivered.size, "every recorded RX went through the NSData overload")
			val loggedRx = outcome.logger.frames.filter { it.direction == FrameDirection.RX }
			assertEquals(recordedRx.map { it.value.toList() }, loggedRx.map { it.value.toList() }, "the session saw the recorded RX bytes")
			assertEquals(SyntheticFitGpsFixture.EXPECTED_STEPS.values.toList(), outcome.session.getStepsDays(3).map { it.steps })
		} finally {
			outcome.session.close()
			runCurrent()
		}
	}
}
