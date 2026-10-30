package br.com.grupotechnos.watch.bridge

/**
 * Comandos em andamento, indexados pelo `requestId` que vai para a `WatchSession`.
 *
 * A sessão garante exatamente um callback terminal por `requestId` (`WatchSessionListener`); quem recebe o
 * terminal chama [take] e liquida a Promise. Genérico em [T] para ser testado sem React Native
 * (no host, `T` é o que liquida a Promise do JS, ou `null` para comandos internos sem Promise).
 *
 * Ids começam em 1 e nunca valem `WatchSession.NO_REQUEST_ID` (-1). Thread-safe (uma trava reentrante de plataforma):
 * os métodos do módulo rodam na thread de módulos nativos e os terminais chegam na thread da sessão.
 */
public class RequestRegistry<T : Any> internal constructor(firstId: Int) {
	/** Um registro vazio; o primeiro id é 1. */
	public constructor() : this(1)

	/**
	 * Um comando em andamento.
	 *
	 * @property requestId o id que foi para a `WatchSession`.
	 * @property method nome do método JS, usado no `onError.method`.
	 * @property token o que liquida a Promise, ou `null` para um comando sem Promise.
	 */
	public data class Entry<T>(val requestId: Int, val method: String, val token: T?)

	private val lock: BridgeLock = newReentrantLock()
	private var next: Int = firstId

	// Em ordem de registro: o drain devolve na ordem em que os comandos entraram.
	private val pending = LinkedHashMap<Int, Entry<T>>()

	/** Aloca um id e guarda o comando até o terminal. */
	public fun register(method: String, token: T?): Int = lock.withLock {
		val id = allocate()
		pending[id] = Entry(id, method, token)
		id
	}

	/**
	 * Aloca um id sem guardar nada: para comandos que o próprio Core dispara (reconexão automática).
	 * O terminal desses ids não acha entrada, e o `onError` sai com `method = null`.
	 */
	public fun nextId(): Int = lock.withLock { allocate() }

	/** Remove e devolve o comando, ou `null` se o id não for conhecido (evento espontâneo ou já liquidado). */
	public fun take(requestId: Int): Entry<T>? = lock.withLock { pending.remove(requestId) }

	/** Lê sem remover (para callbacks não terminais, como progresso de sync). */
	public fun peek(requestId: Int): Entry<T>? = lock.withLock { pending[requestId] }

	/** Remove e devolve todos os comandos, na ordem de registro (usado quando o módulo é destruído). */
	public fun drain(): List<Entry<T>> = lock.withLock {
		val all = pending.values.toList()
		pending.clear()
		all
	}

	/** Quantos comandos estão em andamento. */
	public val size: Int
		get() = lock.withLock { pending.size }

	// Chamado com a trava pega. Depois de Int.MAX_VALUE volta a 1 em vez de produzir ids negativos (e nunca -1).
	private fun allocate(): Int {
		val id = next
		next = if (id == Int.MAX_VALUE) 1 else id + 1
		return id
	}
}
