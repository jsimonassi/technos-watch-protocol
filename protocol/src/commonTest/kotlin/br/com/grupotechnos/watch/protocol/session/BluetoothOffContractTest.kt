package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.ConnectionState.BLUETOOTH_OFF
import br.com.grupotechnos.watch.protocol.domain.ConnectionState.DISCONNECTED
import br.com.grupotechnos.watch.protocol.domain.ConnectionState.READY
import br.com.grupotechnos.watch.protocol.domain.ConnectionState.RECONNECTING
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.testing.SessionEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contrato B do Bluetooth desligado (`docs/decisoes.md`, ADR 18): o host entrega só `onBluetoothStateChanged(false)`,
 * nenhum `onDisconnected` pelo que caiu, e `disconnect` não responde nada até um `connect` novo. O [FakeTransport]
 * (`testing/`) se comporta assim.
 *
 * O risco que estes testes cobrem: se a sessão contasse o `disconnect` de limpeza do Bluetooth desligado como uma
 * desconexão esperada (ADR 12), a primeira queda real depois de religar seria engolida como "a resposta esperada", e a
 * reconexão, que não tem timeout, ficaria presa em `RECONNECTING` sem nunca tentar de novo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothOffContractTest {
	@Test
	fun failedPendingConnectAfterBluetoothComesBackIsNotSwallowed() = runSession { h ->
		h.pairReady()
		h.transport.setBluetooth(false)
		h.run()
		assertEquals(BLUETOOTH_OFF, h.session.state)
		// O relógio ainda não voltou ao alcance: a reconexão fica pendente no host.
		h.transport.connectBehavior = { _, _ -> false }
		h.transport.setBluetooth(true)
		h.run()
		assertEquals(RECONNECTING, h.session.state)
		assertEquals(listOf(ADDRESS to false, ADDRESS to true), h.transport.connects)
		assertTrue(h.transport.pending)
		// O host derruba a conexão pendente (GATT 133). Não havia resposta esperada: é uma falha real.
		h.transport.dropLink(status = 133, message = "pending connect failed")
		h.run()
		assertNotSwallowed(h)
		assertEquals(listOf("next attempt in 2000 ms (failure #1)"), h.logger.withTag("Session/scheduleReconnect"))
		h.advance(1_999)
		assertEquals(2, h.transport.connects.size)
		h.advance(1)
		assertEquals(3, h.transport.connects.size, "the usual backoff retry")
		assertEquals(RECONNECTING, h.session.state)
		h.transport.completeConnect()
		h.run()
		assertEquals(READY, h.session.state)
		h.assertNoUnexpectedErrors()
		h.session.close()
	}

	@Test
	fun bluetoothOffWhileReconnectingWithAPendingConnectionDoesNotSwallowTheNextFailure() = runSession { h ->
		h.pairReady()
		h.transport.connectBehavior = { _, auto -> !auto }
		h.transport.dropLink()
		h.run()
		assertEquals(RECONNECTING, h.session.state)
		assertTrue(h.transport.pending)
		assertEquals(2, h.transport.connects.size)
		h.transport.setBluetooth(false)
		h.run()
		assertEquals(BLUETOOTH_OFF, h.session.state)
		h.transport.setBluetooth(true)
		h.run()
		assertEquals(RECONNECTING, h.session.state)
		assertEquals(3, h.transport.connects.size)
		h.transport.dropLink(status = 133, message = "pending connect failed")
		h.run()
		assertNotSwallowed(h)
		h.advance(1_999)
		assertEquals(3, h.transport.connects.size)
		h.advance(1)
		assertEquals(4, h.transport.connects.size, "the usual backoff retry")
		h.transport.completeConnect()
		h.run()
		assertEquals(READY, h.session.state)
		h.assertNoUnexpectedErrors()
		h.session.close()
	}

	@Test
	fun dropReportedJustBeforeBluetoothOffIsANormalDropAndNothingGetsStuck() = runSession { h ->
		h.pairReady()
		h.listener.clear()
		// O stack reporta a queda antes do aviso de Bluetooth desligado; a reconexão imediata fica pendente.
		h.transport.connectBehavior = { _, auto -> !auto }
		h.transport.dropLink(status = 8, message = "Bluetooth turned off")
		h.run()
		assertEquals(RECONNECTING, h.session.state, "a drop from READY reconnects at once")
		assertEquals(2, h.transport.connects.size)
		h.transport.setBluetooth(false)
		h.run()
		assertEquals(listOf("RECONNECTING", "BLUETOOTH_OFF"), h.listener.stateTrail())
		// Religado: a reconexão começa, e uma falha dela tem a espera de sempre.
		h.transport.setBluetooth(true)
		h.run()
		assertEquals(RECONNECTING, h.session.state)
		assertEquals(3, h.transport.connects.size)
		h.transport.dropLink(status = 133, message = "pending connect failed")
		h.run()
		assertNotSwallowed(h)
		h.advance(2_000)
		assertEquals(4, h.transport.connects.size)
		h.transport.completeConnect()
		h.run()
		assertEquals(READY, h.session.state)
		h.assertNoUnexpectedErrors()
		h.session.close()
	}

	@Test
	fun disconnectRacingBluetoothOffDoesNotSwallowTheNextFailure() = runSession { h ->
		h.pairReady()
		h.listener.clear()
		// No Android, a thread do GATT pode tratar o STATE_OFF antes do `disconnect` do JS: o `disconnect` da sessão
		// conta uma resposta (ADR 12) que o host, mudo pelo contrato B, nunca entrega. O desligamento encontra o
		// transporte já inativo e ainda assim tem de zerar essa contagem.
		h.session.disconnect(50, forget = false)
		h.transport.setBluetooth(false)
		h.run()
		assertEquals(listOf("DISCONNECTED", "BLUETOOTH_OFF"), h.listener.stateTrail())
		assertEquals(SessionEvent.Completed(50), h.listener.terminal(50))
		h.transport.setBluetooth(true)
		h.run()
		assertEquals(DISCONNECTED, h.session.state, "after a user disconnect, bluetooth on does not reconnect")
		h.transport.connectBehavior = { _, _ -> false }
		h.session.reconnect(51, ADDRESS, FIT_GPS)
		h.run()
		assertEquals(2, h.transport.connects.size)
		h.transport.dropLink(status = 133, message = "pending connect failed")
		h.run()
		assertNotSwallowed(h)
		h.advance(2_000)
		assertEquals(3, h.transport.connects.size, "the usual backoff retry")
		h.transport.completeConnect()
		h.run()
		assertEquals(READY, h.session.state)
		assertEquals(SessionEvent.Completed(51), h.listener.terminal(51))
		h.assertNoUnexpectedErrors()
		h.session.close()
	}

	@Test
	fun strayDisconnectAfterBluetoothOffIsOnlyAWarning() = runSession { h ->
		// Um host fora do contrato B (o Android da gravação de 2026-10-29 entregou mais dois onDisconnected depois do
		// desligamento) não muda o estado nem a reconexão depois de religar.
		h.pairReady()
		h.transport.setBluetooth(false)
		h.run()
		h.transport.events.onDisconnected(0, "Disconnected on request")
		h.run()
		assertEquals(BLUETOOTH_OFF, h.session.state)
		val warning = h.logger.messages.single { it.second == "Session/onDisconnected" }
		assertEquals(LogLevel.WARN, warning.first)
		assertTrue(warning.third.contains("with no connection attempt (state=BLUETOOTH_OFF)"), warning.third)
		h.transport.setBluetooth(true)
		h.run()
		assertEquals(READY, h.session.state)
		h.assertNoUnexpectedErrors()
		h.session.close()
	}

	/** A queda depois de religar foi tratada como falha (espera crescente), não descartada como resposta esperada. */
	private fun assertNotSwallowed(h: SessionHarness) {
		assertTrue(
			h.logger.withTag("Session/onDisconnected").none { it.startsWith("expected disconnect") },
			"the drop was swallowed as an expected disconnect: ${h.logger.withTag("Session/onDisconnected")}",
		)
		assertEquals(RECONNECTING, h.session.state)
	}
}
