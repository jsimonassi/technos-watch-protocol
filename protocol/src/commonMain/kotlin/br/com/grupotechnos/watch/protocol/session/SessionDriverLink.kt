package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.WatchErrorCodes
import br.com.grupotechnos.watch.protocol.domain.WatchException
import br.com.grupotechnos.watch.protocol.driver.DriverLink
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.log.WatchLogger
import br.com.grupotechnos.watch.protocol.profile.DeviceProfile
import br.com.grupotechnos.watch.protocol.profile.GattCandidate
import br.com.grupotechnos.watch.protocol.transport.GattStatus
import br.com.grupotechnos.watch.protocol.transport.Transport
import br.com.grupotechnos.watch.protocol.transport.TransportEvent
import br.com.grupotechnos.watch.protocol.transport.WriteType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Implementação de [DriverLink] da sessão: um link GATT já configurado, que vive enquanto durar uma conexão.
 *
 * - [write] e [read] passam por um [Mutex], então duas corrotinas do driver (o pedido em andamento e o ACK que a
 *   recepção escreve) nunca viram duas operações GATT ao mesmo tempo. O Android descarta operações concorrentes
 *   sem avisar; serializar aqui não dispensa a fila do host, só evita que a sessão dependa dela.
 * - Todo pacote escrito ou notificado vai para `WatchLogger.frame`, com o resumo de [describer] (o
 *   `DeviceDriver.describe` do driver da conexão). É a fonte das gravações `.jsonl` (`docs/fixtures.md`).
 * - Status GATT diferente de sucesso ou falta de confirmação do host em [operationTimeoutMs] viram
 *   `WatchException(TRANSPORT)`. Depois de [close] (queda do link), qualquer chamada falha com
 *   `WatchException(DISCONNECTED)` e as que estavam suspensas são acordadas pelo `GattOperations.failAll` da sessão.
 *
 * O link é criado assim que o candidato GATT é escolhido, antes de ligar as notificações, para que uma notificação
 * que chegue antes do driver existir fique guardada em [notifications] em vez de se perder.
 *
 * Confinado à thread da sessão, exceto as leituras de [mtu] e [describer], que são `@Volatile`.
 *
 * @param operationTimeoutMs rede de segurança para um host que nunca confirma uma operação; o host já tem o próprio
 * timeout (4 s no `GattQueue` do Core Android), então este deve ser maior.
 */
internal class SessionDriverLink(
	override val profile: DeviceProfile,
	override val gatt: GattCandidate,
	override val clock: WatchClock,
	override val logger: WatchLogger,
	private val transport: Transport,
	private val operations: GattOperations,
	private val operationTimeoutMs: Long,
) : DriverLink {
	private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
	private val mutex = Mutex()

	@Volatile
	private var closed = false

	/** MTU efetivo; a sessão atualiza depois da negociação, antes de criar o driver. */
	@Volatile
	override var mtu: Int = DEFAULT_MTU

	/** Resumo de cada pacote para o log; vazio até o driver existir. */
	@Volatile
	var describer: (ByteArray) -> String = { "" }

	override val notifications: ReceiveChannel<ByteArray>
		get() = incoming

	/** `true` depois de [close]. */
	val isClosed: Boolean
		get() = closed

	/** Entrega uma notificação da characteristic `gatt.notify`. Chamado pelo roteador de eventos da sessão. */
	fun deliver(value: ByteArray) {
		logger.frame(FrameDirection.RX, gatt.notify, value, describe(value))
		if (closed) return
		incoming.trySend(value)
	}

	override suspend fun write(value: ByteArray, type: WriteType) {
		mutex.withLock {
			ensureOpen("write")
			val copy = value.copyOf()
			logger.frame(FrameDirection.TX, gatt.write, copy, describe(copy))
			val result = operations.run(operationTimeoutMs) { id ->
				transport.write(gatt.service, gatt.write, copy, type, id)
			} ?: throw WatchException(WatchErrorCodes.TRANSPORT, "write on ${gatt.write} not confirmed after $operationTimeoutMs ms")
			val status = (result as? TransportEvent.WriteCompleted)?.status ?: GattStatus.SUCCESS
			if (status != GattStatus.SUCCESS) {
				throw WatchException(WatchErrorCodes.TRANSPORT, "write on ${gatt.write} failed, status=$status")
			}
		}
	}

	override suspend fun read(service: String, characteristic: String): ByteArray =
		mutex.withLock {
			ensureOpen("read")
			val result = operations.run(operationTimeoutMs) { id -> transport.read(service, characteristic, id) }
				?: throw WatchException(WatchErrorCodes.TRANSPORT, "read of $characteristic not confirmed after $operationTimeoutMs ms")
			val read = result as? TransportEvent.ReadCompleted
				?: throw WatchException(WatchErrorCodes.TRANSPORT, "read of $characteristic completed with an unexpected event")
			val value = read.value
			if (read.status != GattStatus.SUCCESS || value == null) {
				throw WatchException(WatchErrorCodes.TRANSPORT, "read of $characteristic failed, status=${read.status}")
			}
			value
		}

	/** Fecha o link: [notifications] termina e novas chamadas falham com `DISCONNECTED`. Idempotente. */
	fun close() {
		if (closed) return
		closed = true
		incoming.close()
	}

	private fun ensureOpen(operation: String) {
		if (closed) throw WatchException(WatchErrorCodes.DISCONNECTED, "link closed before $operation")
	}

	private fun describe(value: ByteArray): String =
		try {
			describer(value)
		} catch (error: Exception) {
			logger.log(LogLevel.WARN, "Session/describe", "describe failed: ${error.message}")
			""
		}

	companion object {
		/** MTU ATT mínimo do Bluetooth LE, usado quando a negociação falha ou não responde. */
		const val DEFAULT_MTU: Int = 23
	}
}
