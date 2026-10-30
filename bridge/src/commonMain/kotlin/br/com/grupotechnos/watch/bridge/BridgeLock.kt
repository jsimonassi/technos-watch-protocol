package br.com.grupotechnos.watch.bridge

/**
 * Trava **reentrante** de plataforma, usada por [PendingEvents] e [RequestRegistry].
 *
 * Por que uma trava própria e não outra coisa:
 * - [PendingEvents] precisa de reentrância: um log emitido de dentro de [PendingEvents.Sink.emit] volta a chamar
 *   [PendingEvents.emit] na mesma thread, e o `synchronized` da JVM do host Android antigo já deixava isso passar.
 *   `kotlin.concurrent.atomics` (Kotlin 2.1.20) só tem atômicos, nenhuma trava; o `Mutex` do kotlinx-coroutines só
 *   serve a código `suspend` e não é reentrante.
 * - As travas do `kotlinx-atomicfu` chegariam só como dependência transitiva do kotlinx-coroutines, um detalhe de
 *   implementação dele, e o :bridge nem depende de coroutines em produção.
 * - Um `expect`/`actual` de duas linhas por plataforma usa a trava reentrante que cada uma já tem
 *   (`java.util.concurrent.locks.ReentrantLock` na JVM e no Android, `NSRecursiveLock` no iOS), sem dependência nova.
 *   É uma função `expect`, e não uma classe `expect`, para não depender das classes `expect`/`actual` (Beta no 2.1.20).
 */
internal interface BridgeLock {
	/** Espera a trava; a mesma thread pode pegá-la de novo sem travar (cada [lock] pede um [unlock]). */
	fun lock()

	/** Solta uma vez a trava pega por [lock]. */
	fun unlock()
}

/** Uma trava reentrante nova. */
internal expect fun newReentrantLock(): BridgeLock

/** Roda [block] com a trava pega e a solta no fim, inclusive quando [block] lança. */
internal inline fun <T> BridgeLock.withLock(block: () -> T): T {
	lock()
	try {
		return block()
	} finally {
		unlock()
	}
}
