package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.WatchErrorCodes
import br.com.grupotechnos.watch.protocol.domain.WatchException
import br.com.grupotechnos.watch.protocol.driver.CharacteristicNotification
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
 * - Todo pacote escrito, notificado ou lido vai para `WatchLogger.frame` (TX, RX e READ), com o resumo de [describer]
 *   (o `DeviceDriver.describe(characteristic, value)` do driver da conexão). É a fonte das gravações `.jsonl`
 *   (`docs/fixtures.md`). Uma leitura que falha não é registrada como pacote.
 * - Notificações de `gatt.notify` e `gatt.extraNotify` vão para [notifications] (o canal de frames); as de
 *   `gatt.sideNotify` vão para [sideNotifications], com a characteristic de origem.
 * - Status GATT diferente de sucesso ou falta de confirmação do host em [operationTimeoutMs] viram
 *   `WatchException(TRANSPORT)`. Depois de [close] (queda do link), qualquer chamada falha com
 *   `WatchException(DISCONNECTED)` e as que estavam suspensas são acordadas pelo `GattOperations.failAll` da sessão.
 *
 * O link é criado assim que o candidato GATT é escolhido, antes de ligar as notificações, para que uma notificação
 * que chegue antes do driver existir fique guardada em [notifications] (ou [sideNotifications]) em vez de se perder.
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
	private val sideIncoming = Channel<CharacteristicNotification>(Channel.UNLIMITED)
	private val mutex = Mutex()

	@Volatile
	private var closed = false

	/** MTU efetivo; a sessão atualiza depois da negociação, antes de criar o driver. */
	@Volatile
	override var mtu: Int = DEFAULT_MTU

	/** Resumo de cada pacote (characteristic, bytes) para o log; vazio até o driver existir. */
	@Volatile
	var describer: (String, ByteArray) -> String = { _, _ -> "" }

	override val notifications: ReceiveChannel<ByteArray>
		get() = incoming

	override val sideNotifications: ReceiveChannel<CharacteristicNotification>
		get() = sideIncoming

	/** `true` depois de [close]. */
	val isClosed: Boolean
		get() = closed

	/** `true` se [characteristic] é a `gatt.notify`, uma das `gatt.extraNotify` ou uma das `gatt.sideNotify` deste link. */
	fun listensTo(characteristic: String): Boolean =
		characteristic == gatt.notify || characteristic in gatt.extraNotify || gatt.sideNotify.any { it.characteristic == characteristic }

	/**
	 * Entrega uma notificação de [characteristic] (conferida antes com [listensTo]). Chamado pelo roteador de eventos da
	 * sessão; o pacote vai para o log com a characteristic de origem. As de `gatt.notify` e `gatt.extraNotify` seguem para
	 * [notifications]; as de `gatt.sideNotify`, para [sideNotifications].
	 */
	fun deliver(characteristic: String, value: ByteArray) {
		logger.frame(FrameDirection.RX, characteristic, value, describe(characteristic, value))
		if (closed) return
		if (characteristic == gatt.notify || characteristic in gatt.extraNotify) {
			incoming.trySend(value)
		} else {
			sideIncoming.trySend(CharacteristicNotification(characteristic, value))
		}
	}

	override suspend fun write(value: ByteArray, type: WriteType) {
		mutex.withLock {
			ensureOpen("write")
			val copy = value.copyOf()
			logger.frame(FrameDirection.TX, gatt.write, copy, describe(gatt.write, copy))
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
			logger.frame(FrameDirection.READ, characteristic, value, describe(characteristic, value))
			value
		}

	/** Fecha o link: [notifications] e [sideNotifications] terminam e novas chamadas falham com `DISCONNECTED`. Idempotente. */
	fun close() {
		if (closed) return
		closed = true
		incoming.close()
		sideIncoming.close()
	}

	private fun ensureOpen(operation: String) {
		if (closed) throw WatchException(WatchErrorCodes.DISCONNECTED, "link closed before $operation")
	}

	private fun describe(characteristic: String, value: ByteArray): String =
		try {
			describer(characteristic, value)
		} catch (error: Exception) {
			logger.log(LogLevel.WARN, "Session/describe", "describe failed: ${error.message}")
			""
		}

	companion object {
		/** MTU ATT mínimo do Bluetooth LE, usado quando a negociação falha ou não responde. */
		const val DEFAULT_MTU: Int = 23
	}
}
