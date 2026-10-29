package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.session.SessionHarness.Companion.oct
import br.com.grupotechnos.watch.protocol.session.SessionHarness.Companion.sportPage
import br.com.grupotechnos.watch.protocol.testing.WatchReply
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A regra do `requestId` (`WatchSessionListener`, `docs/arquitetura.md`): cada pedido termina com exatamente um
 * callback terminal, em todos os caminhos — sucesso, recusa imediata, timeout, queda do link, Bluetooth desligado,
 * `disconnect` e `close`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalCallbackInvariantTest {
	/** Dispara um de cada comando com ids a partir de [first] e devolve os ids usados. */
	private fun SessionHarness.everyCommand(first: Int): List<Int> {
		val ids = (first until first + 7).toList()
		session.readDeviceInfo(ids[0])
		session.readBattery(ids[1])
		session.setTime(ids[2])
		session.refreshSteps(ids[3])
		session.syncHistory(ids[4], emptyList())
		session.syncHistory(ids[5], listOf(DataKind.STEPS))
		session.syncHistory(ids[6], listOf(DataKind.SLEEP))
		return ids
	}

	@Test
	fun everyRequestGetsExactlyOneTerminal() = runSession { h ->
		val ids = mutableListOf<Int>()

		// Antes de parear: recusa imediata.
		ids += h.everyCommand(100)
		h.run()

		// Pareamento e sucesso de tudo.
		h.session.pair(1, ADDRESS, FIT_GPS)
		ids += 1
		h.run()
		h.sportPages += sportPage(Triple(oct(28, 9), 0, 100))
		ids += h.everyCommand(200)
		h.advance(10_000)

		// Timeouts de GET e de sono.
		h.watch.on(0x02, 0x08) { WatchReply() }
		h.watch.on(0x05, 0x45) { WatchReply() }
		ids += h.everyCommand(300)
		h.advance(60_000)

		// Queda do link com fila cheia; a reconexão (com LOGIN) termina o reconnect pedido no meio.
		h.watch.on(0x02, 0x0A) { WatchReply() }
		ids += h.everyCommand(400)
		h.advance(100)
		h.transport.dropLink()
		h.session.reconnect(2, ADDRESS, FIT_GPS)
		ids += 2
		h.advance(10_000)

		// Bluetooth desligado com comandos na fila, reconnect pedido sem Bluetooth, religado.
		ids += h.everyCommand(500)
		h.advance(100)
		h.transport.setBluetooth(false)
		h.session.reconnect(3, ADDRESS, FIT_GPS)
		ids += 3
		h.run()
		h.transport.setBluetooth(true)
		h.advance(10_000)

		// Pair substituído por outro pair, e disconnect com forget no meio de comandos.
		h.transport.connectBehavior = { _, _ -> false }
		h.session.pair(4, ADDRESS, FIT_GPS)
		h.run()
		h.transport.connectBehavior = { _, _ -> true }
		h.session.pair(5, ADDRESS, FIT_GPS)
		ids += listOf(4, 5)
		h.advance(10_000)
		ids += h.everyCommand(600)
		h.session.disconnect(6, forget = true)
		ids += 6
		h.advance(10_000)

		// Disconnect sem conexão e close com pedidos pendentes e posteriores.
		h.session.disconnect(7, forget = false)
		h.session.pair(8, ADDRESS, FIT_GPS)
		h.session.readBattery(9)
		ids += listOf(7, 8, 9)
		h.session.close()
		h.run()
		h.session.readDeviceInfo(10)
		ids += 10

		h.listener.assertOneTerminalEach(ids)
		// Os comandos internos (acerto de hora, releitura) nunca vazam terminal com NO_REQUEST_ID.
		assertEquals(0, h.listener.terminals(WatchSession.NO_REQUEST_ID).size)
		h.assertNoUnexpectedErrors()
	}
}
