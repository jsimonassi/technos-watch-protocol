package br.com.grupotechnos.watch.protocol.testing

import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

// Compilado por jvmTest e por androidUnitTest (os dois rodam na JVM); ver protocol/build.gradle.kts.

internal actual fun createHostQueue(label: String): HostQueue = ExecutorHostQueue(label)

@Suppress("DEPRECATION") // Thread.getId: o JDK 17 do toolchain não tem threadId().
internal actual fun currentThreadId(): Long = Thread.currentThread().id

/** Uma thread só, como a thread de callbacks de um host Android. */
private class ExecutorHostQueue(label: String) : HostQueue {
	@Volatile
	private var thread: Thread? = null
	private val errors: MutableList<String> = Collections.synchronizedList(mutableListOf())
	private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, label).also {
			it.isDaemon = true
			thread = it
		}
	}

	override fun post(block: () -> Unit) {
		try {
			executor.execute {
				try {
					block()
				} catch (error: Throwable) {
					errors += "${error::class.simpleName}: ${error.message}"
				}
			}
		} catch (_: RejectedExecutionException) {
			// Fila já fechada: o evento atrasado é descartado, como num host que já liberou o Bluetooth.
		}
	}

	override fun isCurrent(): Boolean = Thread.currentThread() === thread

	override fun <T> call(block: () -> T): T = executor.submit(Callable { block() }).get()

	override fun failures(): List<String> = synchronized(errors) { errors.toList() }

	override fun close() {
		executor.shutdown()
	}
}
