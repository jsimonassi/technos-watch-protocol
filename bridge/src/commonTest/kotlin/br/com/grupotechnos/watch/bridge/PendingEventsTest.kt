package br.com.grupotechnos.watch.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fila de eventos até o módulo poder emitir. Portado do host Android (as asserções são as mesmas); roda nos quatro
 * targets, então os testes de concorrência também passam pela trava do iOS (`NSRecursiveLock`).
 */
class PendingEventsTest {
	private class FakeSink(var ready: Boolean) : PendingEvents.Sink<Int> {
		val received = mutableListOf<Pair<String, Int>>()
		var failNext = false

		override fun canEmit(): Boolean = ready

		override fun emit(name: String, payload: Int) {
			if (failNext) {
				failNext = false
				throw IllegalStateException("module died")
			}
			received += name to payload
		}
	}

	@Test
	fun queuesUntilAttachedAndReady() {
		val events = PendingEvents<Int>()
		events.emit("a", 1)
		val sink = FakeSink(ready = false)
		events.attach(sink)
		events.emit("b", 2)
		assertEquals(2, events.size)
		sink.ready = true
		events.emit("c", 3)
		assertEquals(listOf("a" to 1, "b" to 2, "c" to 3), sink.received)
		assertEquals(0, events.size)
	}

	@Test
	fun eventsWithoutQueueAreDroppedWhenNotReady() {
		val events = PendingEvents<Int>()
		events.emit("onLog", 1, queueIfNotReady = false)
		val sink = FakeSink(ready = true)
		events.attach(sink)
		assertEquals(emptyList(), sink.received)
	}

	@Test
	fun capacityDropsTheOldest() {
		val events = PendingEvents<Int>(capacity = 2)
		events.emit("a", 1)
		events.emit("b", 2)
		events.emit("c", 3)
		val sink = FakeSink(ready = true)
		events.attach(sink)
		assertEquals(listOf("b" to 2, "c" to 3), sink.received)
	}

	@Test
	fun failedEmitGoesBackToTheQueueInOrder() {
		val events = PendingEvents<Int>()
		val sink = FakeSink(ready = true)
		events.attach(sink)
		sink.failNext = true
		events.emit("a", 1)
		assertEquals(1, events.size)
		events.emit("b", 2)
		assertEquals(listOf("a" to 1, "b" to 2), sink.received)
	}

	@Test
	fun detachOnlyRemovesTheCurrentSink() {
		val events = PendingEvents<Int>()
		val old = FakeSink(ready = true)
		val current = FakeSink(ready = true)
		events.attach(old)
		events.attach(current)
		events.detach(old)
		events.emit("a", 1)
		assertEquals(listOf("a" to 1), current.received)
		events.detach(current)
		events.emit("b", 2)
		assertEquals(1, events.size)
	}

	@Test
	fun eventFromAnotherThreadNeverOvertakesAQueuedOne() = runBlocking {
		// "a" sai da fila e a entrega dele demora; "b" chega de outra thread nesse meio-tempo e precisa vir depois.
		val started = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		val received = mutableListOf<String>()
		val receivedLock = newReentrantLock()
		val sink = object : PendingEvents.Sink<Int> {
			override fun canEmit(): Boolean = true

			override fun emit(name: String, payload: Int) {
				if (name == "a") {
					started.complete(Unit)
					// Espera bloqueando a thread, como uma entrega lenta: a trava da fila continua presa.
					runBlocking { withTimeout(5_000) { release.await() } }
				}
				receivedLock.withLock { received += name }
			}
		}
		val events = PendingEvents<Int>()
		events.emit("a", 1)
		withTimeout(10_000) {
			val flusher = launch(Dispatchers.Default) { events.attach(sink) }
			started.await()
			val other = launch(Dispatchers.Default) { events.emit("b", 2) }
			// Tempo real para "b" passar na frente se a entrega saísse da trava.
			delay(200)
			release.complete(Unit)
			flusher.join()
			other.join()
		}
		assertEquals(listOf("a", "b"), receivedLock.withLock { received.toList() })
	}

	@Test
	fun emittingFromInsideTheSinkDoesNotDeadlock() = runBlocking {
		// O log do host chama emit("onLog") de dentro de Sink.emit, na mesma thread: a trava tem de ser reentrante.
		val events = PendingEvents<Int>()
		val received = mutableListOf<String>()
		val sink = object : PendingEvents.Sink<Int> {
			override fun canEmit(): Boolean = true

			override fun emit(name: String, payload: Int) {
				received += name
				if (name == "onConnectStateChanged") events.emit("onLog", payload, queueIfNotReady = false)
			}
		}
		events.attach(sink)
		// Fora do escopo do runBlocking: se a trava não for reentrante, a thread fica presa para sempre, e o teste tem de
		// falhar no prazo em vez de esperar por ela.
		val emitted = CoroutineScope(Dispatchers.Default + Job()).async {
			events.emit("onConnectStateChanged", 1)
			true
		}
		assertEquals(true, withTimeout(5_000) { emitted.await() })
		assertEquals(listOf("onConnectStateChanged", "onLog"), received)
		assertEquals(0, events.size)
	}
}
