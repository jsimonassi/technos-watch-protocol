package br.com.grupotechnos.watch.protocol.testing

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_specific
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_set_specific
import platform.darwin.dispatch_sync
import platform.posix.pthread_threadid_np
import kotlin.concurrent.AtomicInt

internal actual fun createHostQueue(label: String): HostQueue = DispatchHostQueue(label)

/** `pthread_threadid_np`: o id de 64 bits que o sistema dá à thread (o mesmo do Xcode e do `os_log`). */
@OptIn(ExperimentalForeignApi::class)
internal actual fun currentThreadId(): Long = memScoped {
	val id = alloc<ULongVar>()
	pthread_threadid_np(null, id.ptr)
	id.value.toLong()
}

/**
 * Fila serial do GCD, como a fila de delegate que o host passa ao `CBCentralManager(delegate:queue:)`.
 *
 * A chave de `dispatch_queue_set_specific` é o endereço de um inteiro alocado só para isso: `dispatch_get_specific`
 * devolve o valor associado à fila que está rodando o bloco atual (ou `NULL`), o que identifica a fila mesmo quando o
 * GCD troca a thread que a executa.
 */
@OptIn(ExperimentalForeignApi::class)
private class DispatchHostQueue(label: String) : HostQueue {
	private val queue = dispatch_queue_create(label, null)
	private val key = nativeHeap.alloc<IntVar>()
	private val closed = AtomicInt(0)
	private val errors = mutableListOf<String>()

	init {
		dispatch_queue_set_specific(queue, key.ptr, key.ptr, null)
	}

	override fun post(block: () -> Unit) {
		if (closed.value != 0) return
		dispatch_async(queue) {
			try {
				block()
			} catch (error: Throwable) {
				// Uma exceção que escapasse do bloco do GCD encerraria o processo de teste inteiro.
				errors += "${error::class.simpleName}: ${error.message}"
			}
		}
	}

	override fun isCurrent(): Boolean = dispatch_get_specific(key.ptr) == key.ptr

	override fun <T> call(block: () -> T): T {
		var result: Result<T>? = null
		dispatch_sync(queue) { result = runCatching(block) }
		return result!!.getOrThrow()
	}

	override fun failures(): List<String> = call { errors.toList() }

	override fun close() {
		if (!closed.compareAndSet(0, 1)) return
		// Espera os blocos pendentes antes de liberar a chave que eles consultam.
		dispatch_sync(queue) {}
		nativeHeap.free(key)
	}
}
