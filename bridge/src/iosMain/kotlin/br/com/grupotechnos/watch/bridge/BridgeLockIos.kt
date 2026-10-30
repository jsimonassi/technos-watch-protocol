package br.com.grupotechnos.watch.bridge

import platform.Foundation.NSRecursiveLock

internal actual fun newReentrantLock(): BridgeLock = RecursiveLock()

/**
 * `NSRecursiveLock` do Foundation: reentrante por thread. Um `NSLock` comum travaria quando um log emitido de dentro de
 * `PendingEvents.Sink.emit` volta a chamar `PendingEvents.emit` na mesma thread.
 */
private class RecursiveLock : BridgeLock {
	private val lock = NSRecursiveLock()

	override fun lock() {
		lock.lock()
	}

	override fun unlock() {
		lock.unlock()
	}
}
