package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.ConnectionState.CONFIGURING
import br.com.grupotechnos.watch.protocol.domain.ConnectionState.CONNECTING
import br.com.grupotechnos.watch.protocol.domain.ConnectionState.DISCONNECTED
import br.com.grupotechnos.watch.protocol.domain.ConnectionState.HANDSHAKING
import br.com.grupotechnos.watch.protocol.domain.ConnectionState.READY
import br.com.grupotechnos.watch.protocol.domain.ConnectionState.RECONNECTING
import br.com.grupotechnos.watch.protocol.domain.WatchErrorCodes
import br.com.grupotechnos.watch.protocol.testing.SessionEvent
import br.com.grupotechnos.watch.protocol.testing.WatchReply
import br.com.grupotechnos.watch.protocol.transport.GattStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GattStatus.PAIRING_REMOVED` (ADR 23): o relógio apagou o vínculo do sistema que o telefone guarda, e nenhuma tentativa
 * passa até o usuário esquecer o relógio nas configurações de Bluetooth. A sessão termina a tentativa com
 * `PAIRING_REMOVED` em toda etapa em que o status pode chegar, vai para `DISCONNECTED` e não reconecta sozinha. O caso
 * real: o Life Ultra GPS no primeiro teste no iPhone (2026-10-30), `didFailToConnect` com
 * `CBError.peerRemovedPairingInformation` em toda tentativa.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingRemovedTest {
	private fun SessionHarness.error(requestId: Int): SessionEvent.Error = listener.terminal(requestId) as SessionEvent.Error

	@Test
	fun pairRefusedByTheSystemEndsWithPairingRemovedAndNeverRetries() = runSession { h ->
		h.transport.connectBehavior = { _, _ -> false }
		h.session.pair(1, ADDRESS, FIT_GPS)
		h.run()
		h.transport.dropLink(GattStatus.PAIRING_REMOVED, "Connect failed (status=-4)")
		h.run()
		val error = h.error(1)
		assertEquals(WatchErrorCodes.PAIRING_REMOVED, error.code)
		assertTrue(error.message.startsWith(SessionEngine.PAIRING_REMOVED_HINT), error.message)
		assertTrue(error.message.contains("status=-4"), error.message)
		assertEquals(listOf(CONNECTING, DISCONNECTED), h.listener.states())
		assertEquals(0, h.transport.disconnectCount(), "the host already ended the connection")
		h.advance(120_000)
		assertEquals(1, h.transport.connects.size)
		assertEquals(1, h.listener.terminals(1).size, "the connect timeout must not fire after the failure")
		h.assertNoUnexpectedErrors()
	}

	@Test
	fun pendingReconnectRefusedByTheSystemStopsInsteadOfBackingOff() = runSession { h ->
		h.transport.connectBehavior = { _, auto -> !auto }
		h.session.reconnect(5, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(RECONNECTING, h.session.state)
		h.transport.dropLink(GattStatus.PAIRING_REMOVED, "Connect failed (status=-4)")
		h.run()
		assertEquals(WatchErrorCodes.PAIRING_REMOVED, h.listener.errorCode(5))
		assertEquals(DISCONNECTED, h.session.state)
		h.advance(120_000)
		assertEquals(1, h.transport.connects.size, "a removed pairing must not loop")
		h.transport.setBluetooth(false)
		h.transport.setBluetooth(true)
		h.advance(120_000)
		assertEquals(1, h.transport.connects.size, "not even when Bluetooth comes back")
		// Depois de o usuário esquecer o relógio nos ajustes, o host pede de novo: uma tentativa por pedido.
		h.transport.connectBehavior = { _, _ -> true }
		h.session.reconnect(6, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(SessionEvent.Completed(6), h.listener.terminal(6))
		assertEquals(READY, h.session.state)
		h.assertNoUnexpectedErrors()
	}

	@Test
	fun dropFromReadyWithPairingRemovedReportsWithoutARequestAndDoesNotReconnect() = runSession { h ->
		h.pairReady()
		h.watch.on(0x02, 0x08) { WatchReply() }
		h.session.readBattery(2)
		h.run()
		h.listener.clear()
		h.transport.dropLink(GattStatus.PAIRING_REMOVED, "Link lost (status=-4)")
		h.run()
		assertEquals(WatchErrorCodes.DISCONNECTED, h.listener.errorCode(2), "the command fails like in any drop")
		assertEquals(listOf(DISCONNECTED), h.listener.states(), "no RECONNECTING")
		val spontaneous = h.error(WatchSession.NO_REQUEST_ID)
		assertEquals(WatchErrorCodes.PAIRING_REMOVED, spontaneous.code)
		assertTrue(spontaneous.message.startsWith(SessionEngine.PAIRING_REMOVED_HINT), spontaneous.message)
		h.advance(120_000)
		assertEquals(1, h.transport.connects.size)
		h.assertNoUnexpectedErrors()
	}

	@Test
	fun automaticReconnectAfterADropStopsWithAnErrorWithoutARequest() = runSession { h ->
		h.pairReady()
		h.transport.connectBehavior = { _, auto -> !auto }
		h.transport.dropLink()
		h.run()
		assertEquals(RECONNECTING, h.session.state)
		h.listener.clear()
		h.transport.dropLink(GattStatus.PAIRING_REMOVED, "Connect failed (status=-4)")
		h.run()
		assertEquals(listOf(DISCONNECTED), h.listener.states())
		assertEquals(WatchErrorCodes.PAIRING_REMOVED, h.error(WatchSession.NO_REQUEST_ID).code)
		h.advance(120_000)
		assertEquals(2, h.transport.connects.size)
		h.assertNoUnexpectedErrors()
	}

	@Test
	fun linkDropWithPairingRemovedDuringTheReconnectHandshakeStops() = runSession { h ->
		h.watch.on(0x03, 0x03) { WatchReply() }
		h.session.reconnect(5, ADDRESS, FIT_GPS)
		h.advance(1_000)
		assertEquals(HANDSHAKING, h.session.state)
		h.transport.dropLink(GattStatus.PAIRING_REMOVED, "Link lost (status=-4)")
		h.run()
		assertEquals(WatchErrorCodes.PAIRING_REMOVED, h.listener.errorCode(5))
		assertEquals(DISCONNECTED, h.session.state)
		h.advance(120_000)
		assertEquals(1, h.transport.connects.size)
		h.assertNoUnexpectedErrors()
	}

	@Test
	fun pairingRemovedReportedByAConfigurationOperationClosesTheLinkAndStops() = runSession { h ->
		// Descoberta numa reconexão (que de outro jeito tentaria de novo com espera).
		h.transport.discoveryStatus = GattStatus.PAIRING_REMOVED
		h.session.reconnect(5, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(WatchErrorCodes.PAIRING_REMOVED, h.listener.errorCode(5))
		assertEquals(listOf(RECONNECTING, CONFIGURING, DISCONNECTED), h.listener.states())
		assertEquals(1, h.transport.disconnectCount(), "the link was open: the session closes it")
		h.advance(120_000)
		assertEquals(1, h.transport.connects.size)
		// CCCD no primeiro pareamento (que de outro jeito sairia como TRANSPORT).
		h.transport.discoveryStatus = GattStatus.SUCCESS
		h.transport.notificationsStatus = GattStatus.PAIRING_REMOVED
		h.session.pair(6, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(WatchErrorCodes.PAIRING_REMOVED, h.listener.errorCode(6))
		assertEquals(DISCONNECTED, h.session.state)
		h.assertNoUnexpectedErrors()
	}

	@Test
	fun otherConnectFailuresKeepTheirBehaviour() = runSession { h ->
		h.transport.connectBehavior = { _, auto -> !auto }
		h.session.reconnect(5, ADDRESS, FIT_GPS)
		h.run()
		h.transport.dropLink(133, "Connect failed (status=133)")
		h.run()
		assertEquals(RECONNECTING, h.session.state)
		assertTrue(h.listener.terminals(5).isEmpty())
		h.advance(2_000)
		assertEquals(2, h.transport.connects.size, "an ordinary pending failure still backs off and retries")
		h.assertNoUnexpectedErrors()
	}
}
