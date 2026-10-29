package br.com.grupotechnos.watch.protocol.sma

import br.com.grupotechnos.watch.protocol.domain.WatchErrorCodes
import br.com.grupotechnos.watch.protocol.domain.WatchException
import br.com.grupotechnos.watch.protocol.driver.DriverNotice
import br.com.grupotechnos.watch.protocol.driver.HandshakeMode
import br.com.grupotechnos.watch.protocol.profile.BindPayloadKind
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrame
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrameCodec
import br.com.grupotechnos.watch.protocol.sma.commands.SmaBindPayload
import br.com.grupotechnos.watch.protocol.sma.data.SmaLocalTime
import br.com.grupotechnos.watch.protocol.util.ByteWriter
import br.com.grupotechnos.watch.protocol.util.Hex
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SmaDriverTest {
	private val zone = "America/Sao_Paulo"

	private fun TestScope.newDriver(link: FakeDriverLink): SmaDriver {
		val driver = SmaDriver(link) { testScheduler.currentTime }
		driver.start(backgroundScope)
		return driver
	}

	private suspend fun SmaDriver.login() = handshake(HandshakeMode.RECONNECT) {}

	private fun sportPage(firstHour: LocalDateTime, count: Int): ByteArray {
		val writer = ByteWriter()
		val base = SmaLocalTime.toSecondsSince2000(firstHour)
		repeat(count) { index ->
			writer.u32(base + index * 3600L).u8(0).u24(100 + index)
		}
		return writer.toByteArray()
	}

	@Test
	fun firstPairSendsBindThenLogin() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		val driver = newDriver(link)
		val phases = mutableListOf<String>()
		driver.handshake(HandshakeMode.FIRST_PAIR) { phases += it }
		assertEquals(listOf("BIND", "LOGIN"), phases)
		assertEquals(listOf(0x01, 0x03), link.watch.received.map { it.key })
		assertContentEquals(SmaBindPayload.encode(BindPayloadKind.ANDROID_FF, 1), link.watch.received[0].value)
		assertEquals(listOf(1, 2), link.watch.received.map { it.sequence })
		// Com MTU 23 o BIND de 45 bytes sai em 20 + 20 + 5.
		assertEquals(listOf(20, 20, 5), link.writes.take(3).map { it.size })
		assertEquals(85, driver.readBattery())
	}

	@Test
	fun reconnectSendsOnlyLogin() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		val driver = newDriver(link)
		val phases = mutableListOf<String>()
		driver.handshake(HandshakeMode.RECONNECT) { phases += it }
		assertEquals(listOf("LOGIN"), phases)
		assertEquals(listOf(0x03 to 0x03), link.watch.received.map { it.cmd to it.key })
	}

	@Test
	fun bindRefusedFailsTheHandshake() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		link.watch.on(0x03, 0x01) { WatchReply(responses = listOf(link.watch.frame(0x03, 0x02, 0x01))) }
		val driver = newDriver(link)
		val error = assertFailsWith<WatchException> { driver.handshake(HandshakeMode.FIRST_PAIR) {} }
		assertEquals(WatchErrorCodes.HANDSHAKE_FAILED, error.code)
		assertTrue(link.watch.receivedOf(0x03, 0x03).isEmpty())
	}

	@Test
	fun loginRefusedWithFfFallsBackToUserIdFirstPairOnce() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		link.watch.on(0x03, 0x03) { frame ->
			val accepted = frame.value[0].toInt() != -1
			WatchReply(responses = listOf(link.watch.frame(0x03, 0x04, if (accepted) 0 else 1)))
		}
		val driver = newDriver(link)
		val phases = mutableListOf<String>()
		driver.handshake(HandshakeMode.FIRST_PAIR) { phases += it }
		assertEquals(listOf("BIND", "LOGIN", "BIND", "LOGIN"), phases)
		val userId = SmaBindPayload.encode(BindPayloadKind.USER_ID, 1)
		assertEquals(listOf(0x01, 0x03, 0x01, 0x03), link.watch.received.map { it.key })
		assertContentEquals(userId, link.watch.received[2].value)
		assertContentEquals(userId, link.watch.received[3].value)
		assertTrue(link.logger.contains("retrying FIRST_PAIR with USER_ID"))
		assertEquals(85, driver.readBattery())
	}

	@Test
	fun reconnectTriesTheOtherLoginPayloadWithoutBind() = runTest {
		// Relógio vinculado com USER_ID (pelo fallback acima ou pelo app iOS): recusa o LOGIN com FF.
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		link.watch.on(0x03, 0x03) { frame ->
			val accepted = frame.value[0].toInt() != -1
			WatchReply(responses = listOf(link.watch.frame(0x03, 0x04, if (accepted) 0 else 1)))
		}
		val driver = newDriver(link)
		val phases = mutableListOf<String>()
		driver.handshake(HandshakeMode.RECONNECT) { phases += it }
		assertEquals(listOf("LOGIN", "LOGIN"), phases)
		assertEquals(listOf(0x03, 0x03), link.watch.received.map { it.key })
		assertContentEquals(SmaBindPayload.encode(BindPayloadKind.ANDROID_FF, 1), link.watch.received[0].value)
		assertContentEquals(SmaBindPayload.encode(BindPayloadKind.USER_ID, 1), link.watch.received[1].value)
		assertEquals(85, driver.readBattery())
	}

	@Test
	fun reconnectRefusedWithBothPayloadsNeverBinds() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		link.watch.on(0x03, 0x03) { WatchReply(responses = listOf(link.watch.frame(0x03, 0x04, 0x01))) }
		val driver = newDriver(link)
		val error = assertFailsWith<WatchException> { driver.handshake(HandshakeMode.RECONNECT) {} }
		assertEquals(WatchErrorCodes.HANDSHAKE_FAILED, error.code)
		assertEquals(listOf("03/03", "03/03"), link.watch.receivedLabels())
		assertTrue(link.watch.receivedOf(0x03, 0x01).isEmpty(), "a reconnect must never send BIND")
	}

	@Test
	fun loginRefusedEvenWithUserIdFails() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		link.watch.on(0x03, 0x03) { WatchReply(responses = listOf(link.watch.frame(0x03, 0x04, 0x01))) }
		val driver = newDriver(link)
		val error = assertFailsWith<WatchException> { driver.handshake(HandshakeMode.FIRST_PAIR) {} }
		assertEquals(WatchErrorCodes.HANDSHAKE_FAILED, error.code)
		// LOGIN(FF) recusado → BIND(uid) + LOGIN(uid) recusado; nada além disso.
		assertEquals(listOf(0x01, 0x03, 0x01, 0x03), link.watch.received.map { it.key })
		assertFailsWith<WatchException> { driver.readBattery() }
	}

	@Test
	fun loginAcceptsEmptyValueLikeIos() = runTest {
		val link = FakeDriverLink()
		link.watch.on(0x03, 0x03) { WatchReply(responses = listOf(link.watch.frame(0x03, 0x04))) }
		val driver = newDriver(link)
		driver.login()
		assertEquals(1, link.watch.received.size)
	}

	@Test
	fun bindWaitsForTheLongBindTimeoutWithoutRetry() = runTest {
		val link = FakeDriverLink()
		link.watch.on(0x03, 0x01) { WatchReply() }
		val driver = newDriver(link)
		val error = assertFailsWith<WatchException> { driver.handshake(HandshakeMode.FIRST_PAIR) {} }
		assertEquals(WatchErrorCodes.TIMEOUT, error.code)
		assertEquals(1, link.watch.receivedOf(0x03, 0x01).size)
		assertTrue(testScheduler.currentTime >= 60_000)
	}

	@Test
	fun commandsOtherThanConnectAreRefusedBeforeLogin() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		val driver = newDriver(link)
		val error = assertFailsWith<WatchException> { driver.readBattery() }
		assertEquals(WatchErrorCodes.NOT_READY, error.code)
		assertFailsWith<WatchException> { driver.setTime(0, zone) }
		assertFailsWith<WatchException> { driver.readSteps() }
		assertTrue(link.writes.isEmpty())
	}

	@Test
	fun batteryAndVersion() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath(battery = 42)
		val driver = newDriver(link)
		driver.login()
		assertEquals(42, driver.readBattery())
		val info = driver.readDeviceInfo()
		assertEquals("1.2.3", info.firmware)
		assertEquals("4.5.6", info.bleVersion)
		link.watch.on(0x02, 0x0A) { WatchReply(responses = listOf(link.watch.frame(0x02, 0x0B, 9, 8, 7))) }
		val short = driver.readDeviceInfo()
		assertEquals("9.8.7", short.firmware)
		assertNull(short.bleVersion)
	}

	@Test
	fun emptyBatteryResponseIsAProtocolError() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		link.watch.on(0x02, 0x08) { WatchReply(responses = listOf(link.watch.frame(0x02, 0x09))) }
		val driver = newDriver(link)
		driver.login()
		assertEquals(WatchErrorCodes.PROTOCOL, assertFailsWith<WatchException> { driver.readBattery() }.code)
	}

	@Test
	fun setTimeWritesThePackedLocalWallClock() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		val driver = newDriver(link)
		driver.login()
		val now = TimeFormat.fromLocalWallClock(LocalDateTime(2026, 10, 28, 14, 30, 0), zone)
		driver.setTime(now, zone)
		val frame = link.watch.receivedOf(0x02, 0x01).single()
		assertContentEquals(Hex.decode("6A B8 E7 80"), frame.value)
		assertEquals(
			Hex.encode(Hex.decode("AB 00 00 09 CD 2F")),
			Hex.encode(SmaFrameCodec.encode(frame).copyOfRange(0, 6)),
		)
	}

	@Test
	fun setRetriesOnceWithNewSequence() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		var calls = 0
		link.watch.on(0x02, 0x01) { WatchReply(ack = ++calls > 1) }
		val driver = newDriver(link)
		driver.login()
		val start = testScheduler.currentTime
		driver.setTime(0L + 1_800_000_000_000L, zone)
		val frames = link.watch.receivedOf(0x02, 0x01)
		assertEquals(2, frames.size)
		assertTrue(frames[0].sequence != frames[1].sequence)
		assertTrue(testScheduler.currentTime - start >= 4_000)
	}

	@Test
	fun setFailsAfterTheSingleRetry() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		link.watch.on(0x02, 0x01) { WatchReply(ack = false) }
		val driver = newDriver(link)
		driver.login()
		val error = assertFailsWith<WatchException> { driver.setTime(1_800_000_000_000L, zone) }
		assertEquals(WatchErrorCodes.TIMEOUT, error.code)
		assertEquals(2, link.watch.receivedOf(0x02, 0x01).size)
	}

	@Test
	fun nackOnSetTriggersTheSingleRetry() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		var calls = 0
		link.watch.on(0x02, 0x01) { WatchReply(nack = ++calls == 1) }
		val driver = newDriver(link)
		driver.login()
		driver.setTime(1_800_000_000_000L, zone)
		assertEquals(2, link.watch.receivedOf(0x02, 0x01).size)
	}

	@Test
	fun nackOnGetIsAProtocolErrorWithoutRetry() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		link.watch.on(0x02, 0x08) { WatchReply(nack = true) }
		val driver = newDriver(link)
		driver.login()
		assertEquals(WatchErrorCodes.PROTOCOL, assertFailsWith<WatchException> { driver.readBattery() }.code)
		assertEquals(1, link.watch.receivedOf(0x02, 0x08).size)
	}

	@Test
	fun getTimeoutWithoutAckIsNotRetried() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		link.watch.on(0x02, 0x08) { WatchReply(ack = false) }
		val driver = newDriver(link)
		driver.login()
		val start = testScheduler.currentTime
		val error = assertFailsWith<WatchException> { driver.readBattery() }
		assertEquals(WatchErrorCodes.TIMEOUT, error.code)
		assertEquals(1, link.watch.receivedOf(0x02, 0x08).size)
		assertTrue(testScheduler.currentTime - start in 4_000..4_100)
	}

	@Test
	fun getTimeoutWithAckButNoResponseIsNotRetried() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		link.watch.on(0x05, 0x41) { WatchReply() }
		val driver = newDriver(link)
		driver.login()
		val error = assertFailsWith<WatchException> { driver.readSteps() }
		assertEquals(WatchErrorCodes.TIMEOUT, error.code)
		assertEquals(1, link.watch.receivedOf(0x05, 0x41).size)
	}

	@Test
	fun responseBeforeAckCountsAsAck() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		link.watch.on(0x02, 0x08) {
			WatchReply(ack = false, responses = listOf(link.watch.frame(0x02, 0x09, 77)), responseBeforeAck = true)
		}
		val driver = newDriver(link)
		driver.login()
		assertEquals(77, driver.readBattery())
	}

	@Test
	fun lateAckOfAnAnsweredGetIsNotCreditedToTheNextSet() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		// Bateria: a resposta chega antes e o ACK fica para depois.
		link.watch.on(0x02, 0x08) {
			WatchReply(ack = false, responses = listOf(link.watch.frame(0x02, 0x09, 77)), responseBeforeAck = true)
		}
		var setTimeWrites = 0
		link.watch.on(0x02, 0x01) {
			setTimeWrites++
			if (setTimeWrites == 1) {
				// O ACK atrasado da bateria chega só depois de o SET ser escrito, e o SET não é confirmado.
				link.notify(SmaFrameCodec.ack(link.watch.receivedOf(0x02, 0x08).single().sequence))
				WatchReply(ack = false)
			} else {
				WatchReply()
			}
		}
		val driver = newDriver(link)
		driver.login()
		assertEquals(77, driver.readBattery())
		driver.setTime(TimeFormat.fromLocalWallClock(LocalDateTime(2026, 10, 28, 9, 0, 0), zone), zone)
		// Sem descartar o ACK atrasado, o primeiro SET daria sucesso sem confirmação e não haveria retentativa.
		assertEquals(2, link.watch.receivedOf(0x02, 0x01).size)
		assertTrue(link.logger.contains("late ACK seq="))
	}

	@Test
	fun ackIsWrittenBeforeTheResponseIsDelivered() = runTest {
		val link = FakeDriverLink(writeLatencyMs = 5)
		link.watch.installHappyPath()
		var responseSeq = -1
		link.watch.on(0x02, 0x08) {
			val response = link.watch.frame(0x02, 0x09, 50)
			responseSeq = response.sequence
			WatchReply(responses = listOf(response))
		}
		val driver = newDriver(link)
		driver.login()
		assertEquals(50, driver.readBattery())
		// A escrita do ACK só é registrada quando termina; se a entrega viesse antes, ele ainda não estaria aqui.
		assertEquals(responseSeq, link.watch.phoneAcks.last())
		assertContentEquals(SmaFrameCodec.ack(responseSeq), link.writes.last())
	}

	@Test
	fun ackNeverInterleavesWithAMultiFragmentFrame() = runTest {
		val link = FakeDriverLink(writeLatencyMs = 3)
		link.watch.installHappyPath()
		var noticeSeq = -1
		link.watch.onPacket = { packet ->
			// Assim que o primeiro fragmento do BIND chega, o relógio manda um aviso, que o driver precisa confirmar.
			if (noticeSeq < 0 && packet.size == 20 && packet[8].toInt() == 0x03 && packet[10].toInt() == 0x01) {
				val notice = link.watch.frame(0x02, 0x70, 1)
				noticeSeq = notice.sequence
				link.watch.send(notice)
			}
		}
		val driver = newDriver(link)
		driver.handshake(HandshakeMode.FIRST_PAIR) {}
		assertEquals(listOf(20, 20, 5, 8), link.writes.take(4).map { it.size })
		assertContentEquals(SmaFrameCodec.ack(noticeSeq), link.writes[3])
	}

	@Test
	fun sportPaginationReadsUntilAShortPage() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		val pages = ArrayDeque(
			listOf(
				sportPage(LocalDateTime(2026, 10, 26, 0, 0, 0), 20),
				sportPage(LocalDateTime(2026, 10, 26, 20, 0, 0), 20),
				sportPage(LocalDateTime(2026, 10, 27, 16, 0, 0), 5),
			),
		)
		link.watch.on(0x05, 0x41) { WatchReply(responses = listOf(link.watch.frameOf(0x05, 0x42, pages.removeFirst()))) }
		val driver = newDriver(link)
		driver.login()
		val samples = driver.readSteps()
		assertEquals(3, link.watch.receivedOf(0x05, 0x41).size)
		assertEquals(45, samples.size)
		assertEquals("2026-10-26T00:00:00-03:00", TimeFormat.isoWithOffset(samples.first().atEpochMillis, zone))
		assertEquals("2026-10-27T20:00:00-03:00", TimeFormat.isoWithOffset(samples.last().atEpochMillis, zone))
		assertEquals(100, samples.first().steps)
		// Cada resposta de página foi confirmada pelo telefone.
		assertEquals(3, link.watch.phoneAcks.size - 1)
	}

	@Test
	fun sleepPaginationStopsOnEmptyValue() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		val base = SmaLocalTime.toSecondsSince2000(LocalDateTime(2026, 10, 27, 22, 0, 0))
		val full = ByteWriter().apply {
			u32(base).u8(17).u8(0).u8(0)
			for (index in 1 until 19) u32(base + index * 900L).u8(if (index % 2 == 0) 1 else 2).u8(0).u8(0)
			u32(base + 19 * 900L).u8(34).u8(0).u8(0)
		}.toByteArray()
		val pages = ArrayDeque(listOf(full, ByteArray(0)))
		link.watch.on(0x05, 0x45) { WatchReply(responses = listOf(link.watch.frameOf(0x05, 0x46, pages.removeFirst()))) }
		val driver = newDriver(link)
		driver.login()
		val sessions = driver.readSleep()
		assertEquals(2, link.watch.receivedOf(0x05, 0x45).size)
		assertEquals("2026-10-28", sessions.single().dayKey)
	}

	@Test
	fun emptyHistoryIsAnEmptyList() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		link.watch.on(0x05, 0x41) { WatchReply(responses = listOf(link.watch.frameOf(0x05, 0x42, ByteArray(0)))) }
		link.watch.on(0x05, 0x45) { WatchReply(responses = listOf(link.watch.frameOf(0x05, 0x46, ByteArray(0)))) }
		val driver = newDriver(link)
		driver.login()
		assertTrue(driver.readSteps().isEmpty())
		assertTrue(driver.readSleep().isEmpty())
	}

	@Test
	fun repeatedPageStopsPagination() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		val page = sportPage(LocalDateTime(2026, 10, 26, 0, 0, 0), 20)
		link.watch.on(0x05, 0x41) { WatchReply(responses = listOf(link.watch.frameOf(0x05, 0x42, page))) }
		val driver = newDriver(link)
		driver.login()
		assertEquals(20, driver.readSteps().size)
		assertEquals(2, link.watch.receivedOf(0x05, 0x41).size)
		assertTrue(link.logger.contains("repeats the previous one"))
	}

	@Test
	fun noticesAreAckedAndEmitted() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		val driver = newDriver(link)
		driver.login()
		val collected = async { driver.notices.take(3).toList() }
		runCurrent()
		val dataChanged = link.watch.frame(0x02, 0x70, 1)
		link.watch.send(dataChanged)
		link.watch.send(link.watch.frame(0x04, 0x62, 0))
		link.watch.send(link.watch.frame(0x04, 0x62, 1))
		assertEquals(
			listOf(DriverNotice.DataChanged, DriverNotice.LinkReported(true), DriverNotice.LinkReported(false)),
			collected.await(),
		)
		assertTrue(dataChanged.sequence in link.watch.phoneAcks)
	}

	@Test
	fun unexpectedFrameIsAckedAndLogged() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		val driver = newDriver(link)
		driver.login()
		val frame = link.watch.frame(0x02, 0x67)
		link.watch.send(frame)
		advanceTimeBy(100)
		assertTrue(frame.sequence in link.watch.phoneAcks)
		assertTrue(link.logger.contains("unexpected frame"))
	}

	@Test
	fun corruptedFrameIsNacked() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		val driver = newDriver(link)
		driver.login()
		val bytes = SmaFrameCodec.encode(SmaFrame(0x0999, 0x02, 0x70, byteArrayOf(1)))
		bytes[bytes.size - 1] = 7
		FakeSmaWatch.firmwareFragments(bytes).forEach { link.notify(it) }
		advanceTimeBy(100)
		assertEquals(listOf(0x0999), link.watch.phoneNacks)
	}

	@Test
	fun closeFailsThePendingRequest() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		link.watch.on(0x02, 0x08) { WatchReply(ack = false) }
		val driver = newDriver(link)
		driver.login()
		val result = async { runCatching { driver.readBattery() } }
		advanceTimeBy(1_000)
		driver.close()
		driver.close()
		val error = result.await().exceptionOrNull() as WatchException
		assertEquals(WatchErrorCodes.DISCONNECTED, error.code)
		assertEquals(WatchErrorCodes.DISCONNECTED, assertFailsWith<WatchException> { driver.readBattery() }.code)
	}

	@Test
	fun linkDropFailsThePendingRequest() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		link.watch.on(0x02, 0x0A) { WatchReply() }
		val driver = newDriver(link)
		driver.login()
		val result = async { runCatching { driver.readDeviceInfo() } }
		advanceTimeBy(500)
		link.drop()
		val error = result.await().exceptionOrNull() as WatchException
		assertEquals(WatchErrorCodes.DISCONNECTED, error.code)
	}

	@Test
	fun unbindSendsZeroValue() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		val driver = newDriver(link)
		driver.login()
		driver.unbind()
		assertContentEquals(byteArrayOf(0), link.watch.receivedOf(0x03, 0x05).single().value)
		assertEquals(WatchErrorCodes.NOT_READY, assertFailsWith<WatchException> { driver.readBattery() }.code)
	}

	@Test
	fun enableDataChangeNoticesSendsNothing() = runTest {
		val link = FakeDriverLink()
		link.watch.installHappyPath()
		val driver = newDriver(link)
		driver.login()
		val before = link.writes.size
		driver.enableDataChangeNotices()
		assertEquals(before, link.writes.size)
		assertTrue(link.logger.contains("02/6F SYNC_SWITCH not sent"))
	}

	@Test
	fun noticesFlowEndsAfterClose() = runTest {
		val link = FakeDriverLink()
		val driver = newDriver(link)
		val first = async { runCatching { driver.notices.first() } }
		runCurrent()
		driver.close()
		assertTrue(first.await().isFailure)
	}

	@Test
	fun describeSummarisesPackets() {
		val driver = SmaDriver(FakeDriverLink())
		assertEquals("seq=3 02/01 SET_TIME len=4 crc=ok", driver.describe(Hex.decode("AB 00 00 09 CD 2F 00 03 02 00 01 00 04 6A B8 E7 80")))
		assertEquals("seq=3 02/01 SET_TIME len=4 crc=bad", driver.describe(Hex.decode("AB 00 00 09 CD 2F 00 03 02 00 01 00 04 6A B8 E7 81")))
		assertEquals("ACK seq=4660", driver.describe(Hex.decode("AB 10 00 00 00 00 12 34")))
		assertEquals("NACK seq=4660", driver.describe(Hex.decode("AB 30 00 00 00 00 12 34")))
		assertEquals("seq=1 header len=160", driver.describe(Hex.decode("AB 00 00 A5 12 34 00 01")))
		assertEquals("seq=1 03/01 BIND len=32", driver.describe(Hex.decode("AB 00 00 25 19 FB 00 01 03 00 01 00 20 FF 00 00 00 00 00 00")))
		assertEquals("", driver.describe(Hex.decode("00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")))
		assertEquals("", driver.describe(Hex.decode("AB 00 00")))
	}
}
