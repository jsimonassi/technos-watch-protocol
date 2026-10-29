package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.ConnectionState.BLUETOOTH_OFF
import br.com.grupotechnos.watch.protocol.domain.ConnectionState.CONFIGURING
import br.com.grupotechnos.watch.protocol.domain.ConnectionState.CONNECTING
import br.com.grupotechnos.watch.protocol.domain.ConnectionState.DISCONNECTED
import br.com.grupotechnos.watch.protocol.domain.ConnectionState.READY
import br.com.grupotechnos.watch.protocol.domain.ConnectionState.RECONNECTING
import br.com.grupotechnos.watch.protocol.domain.StepsSample
import br.com.grupotechnos.watch.protocol.domain.WatchErrorCodes
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.testing.FakeTransport
import br.com.grupotechnos.watch.protocol.testing.SessionEvent
import br.com.grupotechnos.watch.protocol.testing.WatchReply
import br.com.grupotechnos.watch.protocol.transport.DiscoveredService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchSessionLifecycleTest {
	@Test
	fun pairHappyPathGoesThroughEveryStateAndCompletes() = runSession { h ->
		h.session.pair(1, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(
			listOf("CONNECTING", "CONFIGURING", "HANDSHAKING", "HANDSHAKING[BIND]", "HANDSHAKING[LOGIN]", "READY"),
			h.listener.stateTrail(),
		)
		assertEquals(SessionEvent.Completed(1), h.listener.terminal(1))
		assertEquals(
			listOf("connect($ADDRESS,false)", "discoverServices", "enableNotifications(${Profiles.SMA_NUS.notify})", "requestMtu(247)"),
			h.transport.calls.filter { it != "write" },
		)
		assertEquals(listOf("03/01", "03/03"), h.watch.receivedLabels())
		assertEquals(ADDRESS, h.session.currentAddress)
		assertEquals(FIT_GPS, h.session.currentProfileId)
		// Acerto de hora automático só depois do postLoginDelayMs.
		h.advance(2_000)
		assertEquals(listOf("03/01", "03/03"), h.watch.receivedLabels())
		h.advance(1_001)
		assertEquals(listOf("03/01", "03/03", "02/01"), h.watch.receivedLabels())
		// O automático não gera callback para o host.
		assertEquals(1, h.listener.events.count { it.terminalId != null })
		h.assertNoUnexpectedErrors()
		h.session.close()
	}

	@Test
	fun everyPacketIsLoggedWithTheDriverSummary() = runSession { h ->
		h.pairReady()
		val tx = h.logger.frames.filter { it.direction == FrameDirection.TX }
		val rx = h.logger.frames.filter { it.direction == FrameDirection.RX }
		assertEquals(h.transport.writes.size, tx.size)
		assertTrue(tx.first().summary.contains("03/01 BIND"), tx.first().summary)
		assertTrue(tx.all { it.characteristic == Profiles.SMA_NUS.write })
		assertTrue(rx.isNotEmpty() && rx.all { it.characteristic == Profiles.SMA_NUS.notify })
		// O L1 chega sozinho numa notificação: o resumo dele traz o seq; as continuações vêm sem resumo.
		assertTrue(rx.any { it.summary.startsWith("ACK seq=") }, rx.toString())
		assertTrue(rx.any { it.summary.contains("header len=1") }, rx.toString())
		h.session.close()
	}

	@Test
	fun droppedNotificationsGoToTheLogButNeverToTheRecording() = runSession { h ->
		h.pairReady()
		val recorded = h.logger.frames.size
		// Outra characteristic com o link aberto, e a characteristic certa depois que a conexão acabou.
		h.transport.events.onNotification(Profiles.SMA_ROUND.notify, byteArrayOf(0xAB.toByte(), 0x10, 0, 0, 0, 0, 0, 7))
		h.run()
		h.session.disconnect(2, forget = false)
		h.run()
		h.transport.events.onNotification(Profiles.SMA_NUS.notify, byteArrayOf(0xAB.toByte(), 0x10, 0, 0, 0, 0, 0, 8))
		h.run()
		assertEquals(recorded, h.logger.frames.size, "recorded: ${h.logger.frames.drop(recorded)}")
		assertEquals(
			listOf(
				"notification on ${Profiles.SMA_ROUND.notify} without a matching link; dropped AB10000000000007",
				"notification on ${Profiles.SMA_NUS.notify} without a matching link; dropped AB10000000000008",
			),
			h.logger.withTag("Session/onNotification"),
		)
		h.assertNoUnexpectedErrors()
		h.session.close()
	}

	@Test
	fun ff60IsPreferredOverNusWhenBothExist() = runSession { h ->
		h.transport.services = listOf(FakeTransport.serviceOf(Profiles.SMA_NUS), FakeTransport.serviceOf(Profiles.SMA_ROUND))
		h.transport.notifyCharacteristic = Profiles.SMA_ROUND.notify
		h.pairReady()
		assertTrue(h.logger.frames.filter { it.direction == FrameDirection.TX }.all { it.characteristic == Profiles.SMA_ROUND.write })
		assertTrue(h.transport.calls.contains("enableNotifications(${Profiles.SMA_ROUND.notify})"))
		h.session.close()
	}

	@Test
	fun incompleteFf60FallsBackToNus() = runSession { h ->
		val round = FakeTransport.serviceOf(Profiles.SMA_ROUND)
		h.transport.services = listOf(DiscoveredService(round.uuid, round.characteristics.take(1)), FakeTransport.serviceOf(Profiles.SMA_NUS))
		h.pairReady()
		assertTrue(h.transport.calls.contains("enableNotifications(${Profiles.SMA_NUS.notify})"))
		h.session.close()
	}

	@Test
	fun connectTimeoutDisconnectsAndFails() = runSession { h ->
		h.transport.connectBehavior = { _, _ -> false }
		h.session.pair(1, ADDRESS, FIT_GPS)
		h.advance(29_999)
		assertEquals(CONNECTING, h.session.state)
		assertTrue(h.listener.terminals(1).isEmpty())
		h.advance(2)
		assertEquals(WatchErrorCodes.CONNECT_TIMEOUT, h.listener.errorCode(1))
		assertEquals(DISCONNECTED, h.session.state)
		assertEquals(1, h.transport.disconnectCount())
		assertEquals(listOf(CONNECTING, DISCONNECTED), h.listener.states())
		h.session.close()
	}

	@Test
	fun gattNotSupportedDisconnects() = runSession { h ->
		h.transport.services = listOf(DiscoveredService("0000180a-0000-1000-8000-00805f9b34fb", emptyList()))
		h.session.pair(1, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(WatchErrorCodes.GATT_NOT_SUPPORTED, h.listener.errorCode(1))
		assertEquals(DISCONNECTED, h.session.state)
		assertEquals(1, h.transport.disconnectCount())
		assertTrue(h.transport.writes.isEmpty())
		h.session.close()
	}

	@Test
	fun notificationFailureIsATransportError() = runSession { h ->
		h.transport.notificationsStatus = 133
		h.session.pair(1, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(WatchErrorCodes.TRANSPORT, h.listener.errorCode(1))
		assertEquals(DISCONNECTED, h.session.state)
		h.session.close()
	}

	@Test
	fun unansweredMtuFallsBackTo23AndFragmentsIn20() = runSession { h ->
		h.transport.mtuReply = null
		h.session.pair(1, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(CONFIGURING, h.session.state)
		h.advance(2_000)
		assertEquals(READY, h.session.state)
		// BIND tem 45 bytes: 20 + 20 + 5 com MTU 23.
		assertEquals(listOf(20, 20, 5), h.transport.writes.take(3).map { it.size })
		assertTrue(h.logger.contains("MTU not answered"))
		h.session.close()
	}

	@Test
	fun bindRefusedFailsWithHandshakeFailed() = runSession { h ->
		h.watch.on(0x03, 0x01) { WatchReply(responses = listOf(h.watch.frame(0x03, 0x02, 0x01))) }
		h.session.pair(1, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(WatchErrorCodes.HANDSHAKE_FAILED, h.listener.errorCode(1))
		assertEquals(DISCONNECTED, h.session.state)
		assertEquals(listOf("03/01"), h.watch.receivedLabels())
		assertEquals(1, h.transport.connects.size)
		h.session.close()
	}

	@Test
	fun bindWithoutAnswerTimesOutAfterTheLongBindTimeoutWithoutReconnecting() = runSession { h ->
		h.watch.on(0x03, 0x01) { WatchReply() }
		h.session.pair(1, ADDRESS, FIT_GPS)
		h.advance(59_000)
		assertTrue(h.listener.terminals(1).isEmpty())
		assertEquals(ConnectionState.HANDSHAKING, h.session.state)
		h.advance(1_100)
		assertEquals(WatchErrorCodes.HANDSHAKE_FAILED, h.listener.errorCode(1))
		assertTrue((h.listener.terminal(1) as SessionEvent.Error).message.contains("TIMEOUT"))
		assertEquals(1, h.transport.connects.size)
		h.session.close()
	}

	@Test
	fun linkDropDuringFirstPairHandshakeFailsWithDisconnected() = runSession { h ->
		h.watch.on(0x03, 0x01) { WatchReply() }
		h.session.pair(1, ADDRESS, FIT_GPS)
		h.advance(10_000)
		h.transport.dropLink()
		h.run()
		assertEquals(WatchErrorCodes.DISCONNECTED, h.listener.errorCode(1))
		assertEquals(DISCONNECTED, h.session.state)
		h.advance(120_000)
		assertEquals(1, h.transport.connects.size, "first pair must not reconnect")
		h.assertNoUnexpectedErrors()
		h.session.close()
	}

	@Test
	fun unknownProfileIsRejected() = runSession { h ->
		h.session.pair(1, ADDRESS, "nope")
		h.run()
		assertEquals(WatchErrorCodes.UNSUPPORTED_DEVICE, h.listener.errorCode(1))
		assertTrue(h.transport.calls.isEmpty())
		h.session.close()
	}

	@Test
	fun dropFromReadyReconnectsWithLoginOnly() = runSession { h ->
		h.pairReady()
		h.listener.clear()
		val before = h.watch.received.size
		h.transport.dropLink()
		h.run()
		assertEquals(
			listOf("RECONNECTING", "CONFIGURING", "HANDSHAKING", "HANDSHAKING[LOGIN]", "READY"),
			h.listener.stateTrail(),
		)
		assertEquals(ADDRESS to true, h.transport.connects.last())
		assertEquals(listOf("03/03"), h.watch.receivedLabels().drop(before))
		h.assertNoUnexpectedErrors()
		h.session.close()
	}

	@Test
	fun pendingReconnectWaitsWithoutTimeout() = runSession { h ->
		h.pairReady()
		h.transport.connectBehavior = { _, auto -> !auto }
		h.transport.dropLink()
		h.advance(10 * 60_000)
		assertEquals(RECONNECTING, h.session.state)
		assertEquals(2, h.transport.connects.size)
		h.transport.completeConnect()
		h.run()
		assertEquals(READY, h.session.state)
		h.session.close()
	}

	@Test
	fun reconnectApiUsesLoginOnlyAndCompletes() = runSession { h ->
		h.session.reconnect(5, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(SessionEvent.Completed(5), h.listener.terminal(5))
		assertEquals(listOf("RECONNECTING", "CONFIGURING", "HANDSHAKING", "HANDSHAKING[LOGIN]", "READY"), h.listener.stateTrail())
		assertEquals(listOf(ADDRESS to true), h.transport.connects)
		assertEquals(listOf("03/03"), h.watch.receivedLabels())
		// Pedir de novo com a sessão pronta termina na hora.
		h.session.reconnect(6, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(SessionEvent.Completed(6), h.listener.terminal(6))
		assertEquals(1, h.transport.connects.size)
		h.session.close()
	}

	@Test
	fun failedReconnectAttemptRetriesWithBackoff() = runSession { h ->
		// Serviço do perfil ausente na descoberta: numa reconexão é falha recuperável (cache GATT do Android).
		h.transport.services = emptyList()
		h.session.reconnect(5, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(RECONNECTING, h.session.state)
		assertTrue(h.listener.terminals(5).isEmpty(), "a reconnect never fails by itself")
		assertEquals(1, h.transport.connects.size)
		h.advance(1_999)
		assertEquals(1, h.transport.connects.size)
		h.advance(1)
		assertEquals(2, h.transport.connects.size)
		// Segunda falha: espera 4 s.
		h.advance(3_999)
		assertEquals(2, h.transport.connects.size)
		h.advance(1)
		assertEquals(3, h.transport.connects.size)
		assertTrue(h.transport.connects.all { it.second })
		// Disconnect encerra o ciclo e cancela o pedido.
		h.session.disconnect(9, forget = false)
		h.run()
		assertEquals(WatchErrorCodes.CANCELLED, h.listener.errorCode(5))
		assertEquals(SessionEvent.Completed(9), h.listener.terminal(9))
		h.advance(120_000)
		assertEquals(3, h.transport.connects.size)
		assertEquals(DISCONNECTED, h.session.state)
		h.session.close()
	}

	@Test
	fun refusedReconnectLoginStopsWithoutBind() = runSession { h ->
		h.watch.on(0x03, 0x03) { WatchReply(responses = listOf(h.watch.frame(0x03, 0x04, 0x01))) }
		h.session.reconnect(5, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(WatchErrorCodes.HANDSHAKE_FAILED, h.listener.errorCode(5))
		assertEquals(DISCONNECTED, h.session.state)
		// Um LOGIN com cada formato de payload e nenhum BIND em segundo plano.
		assertEquals(listOf("03/03", "03/03"), h.watch.receivedLabels())
		h.advance(120_000)
		h.transport.setBluetooth(false)
		h.transport.setBluetooth(true)
		h.advance(120_000)
		assertEquals(1, h.transport.connects.size, "a refused LOGIN must not loop")
		assertTrue(h.watch.receivedOf(0x03, 0x01).isEmpty())
		// O host pode pedir de novo: um LOGIN por pedido, ainda sem BIND.
		h.watch.installHappyPath()
		h.session.reconnect(6, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(SessionEvent.Completed(6), h.listener.terminal(6))
		assertEquals(READY, h.session.state)
		h.assertNoUnexpectedErrors()
		h.session.close()
	}

	@Test
	fun linkDropBeforeReadyOnReconnectBacksOff() = runSession { h ->
		h.pairReady()
		// LOGIN sem resposta: a tentativa fica em HANDSHAKING até o link cair.
		h.watch.on(0x03, 0x03) { WatchReply() }
		h.transport.dropLink()
		h.run()
		assertEquals(2, h.transport.connects.size, "a drop from READY reconnects at once")
		h.advance(1_000)
		h.transport.dropLink()
		h.run()
		assertEquals(RECONNECTING, h.session.state)
		h.advance(1_999)
		assertEquals(2, h.transport.connects.size)
		h.advance(1)
		assertEquals(3, h.transport.connects.size)
		h.advance(1_000)
		h.transport.dropLink()
		h.run()
		h.advance(3_999)
		assertEquals(3, h.transport.connects.size)
		h.advance(1)
		assertEquals(4, h.transport.connects.size)
		assertTrue(h.watch.receivedOf(0x03, 0x01).size == 1, "only the first pair binds")
		h.session.close()
	}

	@Test
	fun bluetoothOffFailsCommandsAndOnReconnectsWithLogin() = runSession { h ->
		h.pairReady()
		h.watch.on(0x02, 0x08) { WatchReply() }
		h.session.readBattery(2)
		h.session.readDeviceInfo(3)
		h.run()
		h.transport.setBluetooth(false)
		h.run()
		assertEquals(BLUETOOTH_OFF, h.session.state)
		assertEquals(WatchErrorCodes.DISCONNECTED, h.listener.errorCode(2))
		assertEquals(WatchErrorCodes.DISCONNECTED, h.listener.errorCode(3))
		h.session.readBattery(4)
		h.run()
		assertEquals(WatchErrorCodes.NOT_READY, h.listener.errorCode(4))
		h.watch.installHappyPath()
		val before = h.watch.received.size
		h.transport.setBluetooth(true)
		h.run()
		assertEquals(READY, h.session.state)
		assertEquals(listOf("03/03"), h.watch.receivedLabels().drop(before))
		assertEquals(ADDRESS to true, h.transport.connects.last())
		h.assertNoUnexpectedErrors()
		h.session.close()
	}

	@Test
	fun bluetoothOffDuringPairFailsItAndDoesNotReconnect() = runSession { h ->
		h.watch.on(0x03, 0x01) { WatchReply() }
		h.session.pair(1, ADDRESS, FIT_GPS)
		h.advance(1_000)
		h.transport.setBluetooth(false)
		h.run()
		assertEquals(WatchErrorCodes.DISCONNECTED, h.listener.errorCode(1))
		h.transport.setBluetooth(true)
		h.run()
		assertEquals(DISCONNECTED, h.session.state)
		assertEquals(1, h.transport.connects.size)
		h.session.pair(2, ADDRESS, FIT_GPS)
		h.run()
		h.transport.setBluetooth(false)
		h.run()
		h.session.pair(3, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(WatchErrorCodes.TRANSPORT, h.listener.errorCode(3))
		h.session.close()
	}

	@Test
	fun disconnectWithForgetSendsUnbindAndClearsEverything(): TestResult {
		val store = InMemorySampleStore()
		store.saveSteps(listOf(StepsSample(1_000L, 10, 0, 1, 2.0)))
		return runSession(store) { h ->
			h.pairReady()
			h.session.disconnect(9, forget = true)
			h.run()
			assertEquals("03/05", h.watch.receivedLabels().last())
			assertEquals(SessionEvent.Completed(9), h.listener.terminal(9))
			assertEquals(DISCONNECTED, h.session.state)
			assertTrue(store.loadSteps().isEmpty())
			assertTrue(h.session.getStepsDays(30).isEmpty())
			assertNull(h.session.currentAddress)
			assertNull(h.session.currentProfileId)
			h.transport.setBluetooth(false)
			h.transport.setBluetooth(true)
			h.advance(120_000)
			assertEquals(1, h.transport.connects.size)
			h.assertNoUnexpectedErrors()
			h.session.close()
		}
	}

	@Test
	fun forgetCompletesEvenWhenUnbindIsNotAcknowledged() = runSession { h ->
		h.pairReady()
		h.watch.on(0x03, 0x05) { WatchReply(ack = false) }
		h.session.disconnect(9, forget = true)
		h.advance(3_999)
		assertTrue(h.listener.terminals(9).isEmpty())
		h.advance(2)
		assertEquals(SessionEvent.Completed(9), h.listener.terminal(9))
		assertEquals(DISCONNECTED, h.session.state)
		assertTrue(h.logger.contains("UNBIND not acknowledged"))
		h.session.close()
	}

	@Test
	fun disconnectWithoutForgetKeepsStoreAndDoesNotUnbind() = runSession { h ->
		h.pairReady()
		h.sportPages += SessionHarness.sportPage(Triple(SessionHarness.oct(28, 9), 0, 500))
		h.session.refreshSteps(2)
		h.run()
		h.session.disconnect(9, forget = false)
		h.run()
		assertTrue("03/05" !in h.watch.receivedLabels())
		assertEquals(SessionEvent.Completed(9), h.listener.terminal(9))
		assertEquals(1, h.session.getStepsDays(0).size)
		assertEquals(ADDRESS, h.session.currentAddress)
		h.transport.dropLink()
		h.advance(120_000)
		assertEquals(1, h.transport.connects.size)
		h.session.close()
	}

	@Test
	fun newPairSupersedesAPendingOne() = runSession { h ->
		h.transport.connectBehavior = { _, _ -> false }
		h.session.pair(1, ADDRESS, FIT_GPS)
		h.run()
		h.transport.connectBehavior = { _, _ -> true }
		h.session.pair(2, "11:22:33:44:55:66", FIT_GPS)
		h.run()
		assertEquals(WatchErrorCodes.CANCELLED, h.listener.errorCode(1))
		assertEquals(SessionEvent.Completed(2), h.listener.terminal(2))
		assertEquals("11:22:33:44:55:66", h.session.currentAddress)
		// O onDisconnected do link antigo chega depois do disconnect e não derruba o novo.
		assertEquals(READY, h.session.state)
		h.assertNoUnexpectedErrors()
		h.session.close()
	}

	@Test
	fun pairOnAReadySessionTearsDownAndBindsAgain() = runSession { h ->
		h.pairReady()
		h.session.pair(2, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(SessionEvent.Completed(2), h.listener.terminal(2))
		assertEquals(READY, h.session.state)
		assertEquals(2, h.watch.receivedOf(0x03, 0x01).size)
		assertEquals(1, h.transport.disconnectCount())
		h.session.close()
	}

	@Test
	fun closeCancelsPendingAndLaterRequests() = runSession { h ->
		h.watch.on(0x03, 0x01) { WatchReply() }
		h.session.pair(1, ADDRESS, FIT_GPS)
		h.advance(1_000)
		h.session.close()
		h.run()
		assertEquals(WatchErrorCodes.CANCELLED, h.listener.errorCode(1))
		assertEquals(DISCONNECTED, h.session.state)
		h.session.readBattery(2)
		assertEquals(WatchErrorCodes.CANCELLED, h.listener.errorCode(2))
	}
}
