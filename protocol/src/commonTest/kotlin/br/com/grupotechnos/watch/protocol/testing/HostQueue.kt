package br.com.grupotechnos.watch.protocol.testing

/**
 * Uma fila serial do "host", como a fila de delegate do `CBCentralManager` no iOS ou a thread dos callbacks do
 * `BluetoothGatt` no Android. O teste de tempo real ([RealtimeReplay]) entrega por ela todo evento do `TransportEvents`,
 * para a sessão receber os eventos de uma thread que não é a dela.
 *
 * Implementações (`expect`/`actual`):
 * - iOS: `dispatch_queue_create` serial; [isCurrent] usa `dispatch_get_specific`, porque o GCD não fixa uma thread por
 *   fila (a mesma fila roda em threads diferentes do pool, e a mesma thread serve a outras filas);
 * - JVM e unit Android: `Executors.newSingleThreadExecutor`, uma thread só; [isCurrent] compara a thread.
 */
internal interface HostQueue {
	/** Enfileira [block] e retorna. Uma exceção do bloco não derruba a fila: fica em [failures]. */
	fun post(block: () -> Unit)

	/** `true` quando quem chama está rodando um bloco desta fila. */
	fun isCurrent(): Boolean

	/** Roda [block] na fila e espera o resultado. Nunca chamar de dentro da própria fila. */
	fun <T> call(block: () -> T): T

	/** Exceções lançadas pelos blocos de [post], descritas. Lido pela própria fila, então chamar fora dela. */
	fun failures(): List<String>

	/** Libera a fila. Blocos já enfileirados ainda rodam; [post] depois disso é ignorado. */
	fun close()
}

/** Uma [HostQueue] nova com o rótulo [label] (aparece no nome da thread ou da fila nos diagnósticos). */
internal expect fun createHostQueue(label: String): HostQueue

/** Identificador da thread do sistema que está rodando agora (para registrar onde cada callback rodou). */
internal expect fun currentThreadId(): Long
