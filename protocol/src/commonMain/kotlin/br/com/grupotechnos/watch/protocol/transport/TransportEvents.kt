package br.com.grupotechnos.watch.protocol.transport

/**
 * Entrada de eventos do host para a sessão.
 *
 * O host obtém a instância em `WatchSession.events` e chama estes métodos a partir dos callbacks da pilha BLE.
 * Os métodos são não bloqueantes e podem ser chamados de qualquer thread: cada chamada só enfileira um evento,
 * que a sessão processa na sua própria thread.
 *
 * `status` segue [GattStatus]: `0` é sucesso, qualquer outro valor é falha.
 *
 * No iOS, [onNotification] e [onReadCompleted] também aceitam `NSData` (no Swift, `Data`): são as sobrecargas
 * `onNotification(characteristic:data:)` e `onReadCompleted(requestId:data:status:)` do pacote
 * `br.com.grupotechnos.watch.protocol.ios`, que copiam os bytes e chamam estes métodos.
 */
public class TransportEvents internal constructor(
	private val sink: (TransportEvent) -> Unit,
) {
	/** O link GATT abriu. */
	public fun onConnected() {
		sink(TransportEvent.Connected)
	}

	/**
	 * O link fechou, por pedido (`Transport.disconnect`) ou por queda.
	 *
	 * @param status status nativo da desconexão, só para log.
	 * @param message descrição curta, só para log.
	 */
	public fun onDisconnected(status: Int, message: String) {
		sink(TransportEvent.Disconnected(status, message))
	}

	/** Resultado de `Transport.discoverServices`. Lista vazia com [status] diferente de 0 indica falha. */
	public fun onServicesDiscovered(services: List<DiscoveredService>, status: Int) {
		sink(TransportEvent.ServicesDiscovered(services, status))
	}

	/** Resultado de `Transport.enableNotifications`. */
	public fun onNotificationsEnabled(requestId: Int, status: Int) {
		sink(TransportEvent.NotificationsEnabled(requestId, status))
	}

	/** Resultado de `Transport.requestMtu`, com o MTU ATT efetivo do link. */
	public fun onMtuChanged(requestId: Int, mtu: Int, status: Int) {
		sink(TransportEvent.MtuChanged(requestId, mtu, status))
	}

	/** Resultado de `Transport.write`. */
	public fun onWriteCompleted(requestId: Int, status: Int) {
		sink(TransportEvent.WriteCompleted(requestId, status))
	}

	/** Resultado de `Transport.read`. [value] é `null` em caso de falha. */
	public fun onReadCompleted(requestId: Int, value: ByteArray?, status: Int) {
		sink(TransportEvent.ReadCompleted(requestId, value, status))
	}

	/**
	 * Uma notificação chegou. O host entrega o valor exatamente como recebeu, um evento por notificação,
	 * na ordem de chegada. O host deve copiar o array se a plataforma reutilizar o buffer.
	 */
	public fun onNotification(characteristic: String, value: ByteArray) {
		sink(TransportEvent.Notification(GattUuid.normalize(characteristic), value))
	}

	/**
	 * O Bluetooth do telefone foi ligado ou desligado.
	 *
	 * **Desligado (contrato B, `docs/decisoes.md`, ADR 18).** Este é o único evento: o host **não** chama
	 * [onDisconnected] pelos links abertos nem pelas conexões pendentes que o desligamento derrubou, nem antes nem
	 * depois, e um `Transport.disconnect` seguinte também não responde até um `Transport.connect` novo. A sessão
	 * derruba a tentativa, vai para `BLUETOOTH_OFF` e não espera nenhum `onDisconnected`. Um [onDisconnected] entregue
	 * antes deste evento (o stack reportou a queda antes do aviso de Bluetooth desligado) é uma queda comum; um
	 * entregue depois só gera um aviso no log, mas, se chegar quando o Bluetooth já voltou, a sessão o toma pela falha
	 * da conexão nova e espera para tentar de novo.
	 *
	 * **Ligado.** Com relógio pareado e sem `disconnect` do usuário, a sessão reconecta sozinha (`RECONNECTING`).
	 */
	public fun onBluetoothStateChanged(enabled: Boolean) {
		sink(TransportEvent.BluetoothStateChanged(enabled))
	}
}

/**
 * Eventos do transporte, já como valores, para a sessão processar em ordem.
 */
internal sealed interface TransportEvent {
	data object Connected : TransportEvent

	data class Disconnected(val status: Int, val message: String) : TransportEvent

	data class ServicesDiscovered(val services: List<DiscoveredService>, val status: Int) : TransportEvent

	data class NotificationsEnabled(val requestId: Int, val status: Int) : TransportEvent

	data class MtuChanged(val requestId: Int, val mtu: Int, val status: Int) : TransportEvent

	data class WriteCompleted(val requestId: Int, val status: Int) : TransportEvent

	class ReadCompleted(val requestId: Int, val value: ByteArray?, val status: Int) : TransportEvent

	class Notification(val characteristic: String, val value: ByteArray) : TransportEvent

	data class BluetoothStateChanged(val enabled: Boolean) : TransportEvent
}
