package br.com.grupotechnos.watch.bridge

import java.util.concurrent.locks.ReentrantLock

// Source set jvmSharedMain: o grupo jvmShared (JVM e Android) do template de hierarquia; ver bridge/build.gradle.kts.

internal actual fun newReentrantLock(): BridgeLock = JvmReentrantLock()

/** `ReentrantLock` da JVM: reentrante como o `synchronized` que o host Android usava antes. */
private class JvmReentrantLock : BridgeLock {
	private val lock = ReentrantLock()

	override fun lock() {
		lock.lock()
	}

	override fun unlock() {
		lock.unlock()
	}
}
