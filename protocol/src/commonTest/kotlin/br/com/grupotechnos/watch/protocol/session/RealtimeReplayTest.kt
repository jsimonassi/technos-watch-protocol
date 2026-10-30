package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.testing.Fixtures
import br.com.grupotechnos.watch.protocol.testing.RealtimeReplay
import br.com.grupotechnos.watch.protocol.testing.Recording
import br.com.grupotechnos.watch.protocol.testing.RecordingLoader
import br.com.grupotechnos.watch.protocol.testing.ReplayAction
import br.com.grupotechnos.watch.protocol.testing.ReplayScenario
import br.com.grupotechnos.watch.protocol.testing.SessionEvent
import br.com.grupotechnos.watch.protocol.testing.StoreSnapshot
import br.com.grupotechnos.watch.protocol.testing.runReplay
import br.com.grupotechnos.watch.protocol.testing.storeSnapshotOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Teste de fumaça do dispatcher de produção: a gravação real inteira com `WatchSession(transport, logger, store, clock)`
 * (o construtor público, `Dispatchers.Default.limitedParallelism(1)`), em tempo real, com todo evento do host entregue
 * por uma fila serial própria ([RealtimeReplay]). Roda nos quatro targets; no iOS a fila é um `dispatch_queue_create`
 * serial, como a fila de delegate do `CoreBluetooth`.
 *
 * Confere o que o replay em tempo virtual não alcança (`docs/arquitetura.md`, seção 4):
 * - os mesmos TX (estrito) e o mesmo histórico guardado que o replay virtual;
 * - todo evento do `TransportEvents` chegou da fila do host e retornou rápido (só enfileira);
 * - nenhum callback do listener rodou na fila do host;
 * - depois do `close`, nenhum callback mais, nem com um evento atrasado do host.
 *
 * "Nunca dois callbacks ao mesmo tempo" também é conferido, mas aqui é só observação: o replay é pergunta e resposta e
 * uma corrotina só consome a caixa, então os callbacks saem em série com qualquer dispatcher (trocar o
 * `limitedParallelism(1)` por `Dispatchers.Default` não falha este teste). Quem guarda o dispatcher serial é o
 * [SessionThreadingTest].
 *
 * Imprime `REALTIME-REPLAY <caminho>: TX matched <a>/<b>, final state <estado>, <duração> ms` e uma linha com as
 * threads observadas, no `system-out` do relatório de cada tarefa de teste.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeReplayTest {
	@Test
	fun realRecordingReplaysOnTheProductionDispatcherWithEventsFromAHostQueue() {
		val recording = RecordingLoader.loadFixture(COMMITTED_REAL_RECORDING)
		val actions = ReplayScenario.deduce(recording)
		val (expected, expectedTerminals) = virtualReplay(recording, actions)

		val outcome = runBlocking { RealtimeReplay(recording).run(actions) }

		val listenerThreads = outcome.callbacks.map { it.threadId }.distinct()
		val hostThreads = outcome.events.map { it.threadId }.distinct()
		val slowest = outcome.events.maxOf { it.took }
		val logsOnQueue = outcome.logs.filter { it.onHostQueue }
		val onQueue = outcome.callbacks.filter { it.onHostQueue }
		println(
			"REALTIME-REPLAY ${Fixtures.relative(recording.source)}: TX matched ${outcome.matchedTx}/${outcome.recordedTx}, " +
				"final state ${outcome.finalState}, ${outcome.duration.inWholeMilliseconds} ms",
		)
		println(
			"REALTIME-REPLAY threads: ${outcome.callbacks.size} listener callbacks on ${listenerThreads.size} OS thread(s), " +
				"at most ${outcome.maxConcurrentCallbacks} at a time, ${onQueue.size} on the host queue; ${outcome.events.size} " +
				"TransportEvents calls on ${hostThreads.size} host queue thread(s), slowest ${slowest.inWholeMicroseconds} us; " +
				"${logsOnQueue.size} log call(s) on the host queue ${logsOnQueue.map { it.tag + " " + it.message }}",
		)

		assertTrue(outcome.problems.isEmpty(), outcome.problems.joinToString("\n"))
		assertEquals(outcome.recordedTx, outcome.matchedTx, "TX matched")
		assertEquals(expectedFinalState(actions), outcome.finalState)
		for ((action, id) in outcome.requestIds) {
			val terminals = outcome.callbacks.filter { it.event.terminalId == id }.map { it.event }
			assertEquals(1, terminals.size, "$action #$id terminals: $terminals")
			assertTrue(terminals.single() !is SessionEvent.Error, "$action #$id failed: ${terminals.single()}")
		}
		assertEquals(expected, outcome.snapshot, "store content differs from the virtual-time replay")
		val terminals = outcome.requestIds.map { (_, id) -> outcome.callbacks.first { it.event.terminalId == id }.event }
		assertEquals(expectedTerminals, terminals, "terminal callbacks differ from the virtual-time replay")

		val offQueue = outcome.events.filter { !it.onHostQueue }
		assertTrue(offQueue.isEmpty(), "TransportEvents called outside the host queue: ${offQueue.size} of ${outcome.events.size} calls")
		// "Na fila do host" é a identidade da fila, não da thread: no iOS o Dispatchers.Default também roda no pool do GCD,
		// então a mesma thread do sistema serve à fila do host e à sessão em momentos diferentes (nunca ao mesmo tempo).
		assertTrue(onQueue.isEmpty(), "listener callbacks on the host queue: ${onQueue.map { it.event }}")
		// Observação desta carga, não prova do dispatcher (ver o KDoc da classe e o SessionThreadingTest).
		assertEquals(1, outcome.maxConcurrentCallbacks, "listener callbacks ran concurrently")
		assertTrue(slowest < 100.milliseconds, "a TransportEvents call took $slowest to return on the host queue")
		assertTrue(outcome.hostFailures.isEmpty(), outcome.hostFailures.toString())

		assertTrue(outcome.callbacksAfterClose.isEmpty(), "callbacks after close: ${outcome.callbacksAfterClose.map { it.event }}")
		// Na fila do host, o logger só roda depois do close: o evento atrasado é descartado com um DEBUG na thread de quem o
		// entregou (`SessionEngine.post`). Fora dela, as consultas síncronas também logam na thread de quem chama
		// (`SessionThreadingTest.synchronousCallsLogOnTheCallersThread`).
		val unexpectedLogs = logsOnQueue.filterNot { it.tag == "Session/post" && it.message.startsWith("session closed") }
		assertTrue(unexpectedLogs.isEmpty(), "logger called on the host queue: ${unexpectedLogs.map { it.tag + " " + it.message }}")
	}

	/** O mesmo replay em tempo virtual: o histórico guardado e o terminal de cada pedido, na ordem das ações. */
	private fun virtualReplay(recording: Recording, actions: List<ReplayAction>): Pair<StoreSnapshot, List<SessionEvent>> {
		var result: Pair<StoreSnapshot, List<SessionEvent>>? = null
		runTest {
			val outcome = runReplay(recording, actions)
			try {
				assertTrue(outcome.replay.problems().isEmpty(), outcome.replay.problems().joinToString("\n"))
				result = storeSnapshotOf(outcome) to outcome.requestIds.map { (_, id) -> outcome.listener.terminal(id) }
			} finally {
				outcome.session.close()
				runCurrent()
			}
		}
		return checkNotNull(result)
	}
}
