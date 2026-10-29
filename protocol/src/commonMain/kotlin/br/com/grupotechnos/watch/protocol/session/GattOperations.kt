package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.WatchException
import br.com.grupotechnos.watch.protocol.transport.TransportEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Registro das operações GATT em andamento (write, read, notificação, MTU), casadas pelo `requestId` interno.
 *
 * Os ids daqui são da sessão, não do host: o host recebe `requestId` em `Transport.write` e devolve o mesmo id em
 * `TransportEvents.onWriteCompleted`. São únicos durante a vida da sessão (não recomeçam a cada conexão), então uma
 * confirmação atrasada de um link que já caiu nunca completa uma operação do link novo; ela só é registrada no log.
 *
 * Confinado à thread da sessão: [run], [complete] e [failAll] são chamados só dali, por isso não há trava.
 */
internal class GattOperations {
	private var nextId = 1
	private val pending = HashMap<Int, CompletableDeferred<TransportEvent>>()

	/** Quantas operações esperam confirmação agora (para testes e log). */
	val inFlight: Int
		get() = pending.size

	/**
	 * Registra uma operação, chama [start] com o id dela e suspende até o evento de conclusão.
	 *
	 * O id é registrado **antes** de [start], porque um host (ou o `FakeTransport`) pode chamar o evento de conclusão
	 * de dentro da própria chamada.
	 *
	 * @return o evento de conclusão, ou `null` se [timeoutMs] passar antes.
	 * @throws WatchException o erro passado a [failAll] quando o link cai com a operação pendente.
	 */
	suspend fun run(timeoutMs: Long, start: (Int) -> Unit): TransportEvent? {
		val id = allocate()
		val deferred = CompletableDeferred<TransportEvent>()
		pending[id] = deferred
		try {
			start(id)
			return withTimeoutOrNull(timeoutMs) { deferred.await() }
		} finally {
			pending.remove(id)
		}
	}

	/** Entrega a conclusão da operação [id]. Retorna `false` se ninguém espera por ela (operação expirada ou de link antigo). */
	fun complete(id: Int, event: TransportEvent): Boolean {
		val deferred = pending.remove(id) ?: return false
		return deferred.complete(event)
	}

	/** Falha todas as operações pendentes com [error]. Chamado quando o link cai ou a sessão fecha. */
	fun failAll(error: WatchException) {
		val all = pending.values.toList()
		pending.clear()
		all.forEach { it.completeExceptionally(error) }
	}

	private fun allocate(): Int {
		val id = nextId
		nextId = if (nextId == Int.MAX_VALUE) 1 else nextId + 1
		return id
	}
}
