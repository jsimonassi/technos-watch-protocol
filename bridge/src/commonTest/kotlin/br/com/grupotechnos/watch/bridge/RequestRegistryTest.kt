package br.com.grupotechnos.watch.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Liquidação única por `requestId`. Portado do host Android (mesmas asserções), mais a volta do contador e threads. */
class RequestRegistryTest {
	@Test
	fun takeReturnsTheEntryOnlyOnce() {
		val registry = RequestRegistry<String>()
		val id = registry.register("getBatteryStatus", "promise-1")
		assertTrue(id > 0)
		assertEquals(RequestRegistry.Entry(id, "getBatteryStatus", "promise-1"), registry.take(id))
		assertNull(registry.take(id))
	}

	@Test
	fun idsAreUniqueAndNeverNoRequestId() {
		val registry = RequestRegistry<String>()
		val ids = (1..100).map { if (it % 2 == 0) registry.register("m", null) else registry.nextId() }
		assertEquals(ids.size, ids.toSet().size)
		ids.forEach { assertNotEquals(-1, it) }
		assertEquals(50, registry.size)
	}

	@Test
	fun unknownIdIsIgnored() {
		val registry = RequestRegistry<String>()
		assertNull(registry.take(-1))
		assertNull(registry.take(registry.nextId()))
	}

	@Test
	fun drainEmptiesTheRegistry() {
		val registry = RequestRegistry<String>()
		registry.register("a", "p1")
		registry.register("b", "p2")
		assertEquals(setOf("p1", "p2"), registry.drain().mapNotNull { it.token }.toSet())
		assertEquals(0, registry.size)
	}

	@Test
	fun peekDoesNotRemove() {
		val registry = RequestRegistry<String>()
		val id = registry.register("syncHistory", "p")
		assertEquals("syncHistory", registry.peek(id)?.method)
		assertEquals(1, registry.size)
		assertEquals("p", registry.take(id)?.token)
	}

	@Test
	fun counterWrapsToOneAfterIntMaxValue() {
		// O AtomicInteger do host antigo dava a volta e recomeçava em 1, sem ids negativos.
		val registry = RequestRegistry<String>(firstId = Int.MAX_VALUE)
		assertEquals(Int.MAX_VALUE, registry.nextId())
		assertEquals(1, registry.register("m", "p"))
		assertEquals(2, registry.nextId())
	}

	@Test
	fun concurrentRegistrationsGetDistinctIds() = runBlocking {
		val registry = RequestRegistry<String>()
		val ids = withTimeout(10_000) {
			(1..4).map { worker ->
				async(Dispatchers.Default) { (1..1_000).map { registry.register("m$worker", "p") } }
			}.awaitAll().flatten()
		}
		assertEquals(4_000, ids.toSet().size)
		assertEquals(4_000, registry.size)
		assertEquals(4_000, registry.drain().size)
	}
}
