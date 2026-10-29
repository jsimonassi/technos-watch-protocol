package br.com.grupotechnos.watch.protocol.transport

/**
 * Operações BLE que o host executa a pedido da sessão.
 *
 * É a única parte do sistema que fala com a pilha Bluetooth da plataforma (`BluetoothGatt` no Android,
 * `CBCentralManager`/`CBPeripheral` no iOS). Não conhece protocolo de fabricante: recebe e entrega bytes.
 *
 * Contrato:
 * - Todas as funções retornam imediatamente. O resultado chega pelo [TransportEvents] da sessão.
 * - A sessão nunca pede duas operações GATT ao mesmo tempo (write, read, notificação, MTU);
 *   mesmo assim o host deve serializá-las, porque o Android descarta operações concorrentes sem avisar.
 * - Toda operação com `requestId` termina com exatamente um evento com o mesmo `requestId`, com sucesso ou com erro.
 * - UUIDs chegam sempre normalizados por [GattUuid.normalize].
 * - Pode ser chamada de qualquer thread; o host faz o despacho para a thread do Bluetooth.
 */
public interface Transport {
	/**
	 * Abre o link com o relógio.
	 *
	 * @param address MAC no Android, UUID do periférico no iOS.
	 * @param autoReconnect `false` no primeiro pareamento (conexão direta, rápida); `true` em reconexão,
	 * quando o host deve manter uma conexão pendente até o relógio voltar ao alcance
	 * (`autoConnect = true` no Android, `connectPeripheral` sem timeout no iOS).
	 * Se já houver um link aberto ou pendente para outro endereço, o host o fecha antes.
	 */
	public fun connect(address: String, autoReconnect: Boolean)

	/**
	 * Fecha o link e cancela qualquer conexão pendente.
	 *
	 * Termina com exatamente um `onDisconnected` quando havia link aberto ou conexão pendente, e com nenhum quando não
	 * havia. A sessão conta essas respostas para não confundi-las com a queda de uma conexão nova
	 * (`docs/decisoes.md`, ADR 12).
	 */
	public fun disconnect()

	/** Descobre serviços e characteristics. Termina com `onServicesDiscovered`. */
	public fun discoverServices()

	/**
	 * Liga as notificações da characteristic (no Android, inclui escrever `01 00` no descritor CCCD `0x2902`).
	 * Termina com `onNotificationsEnabled(requestId, status)`.
	 */
	public fun enableNotifications(service: String, characteristic: String, requestId: Int)

	/**
	 * Pede um MTU ATT maior. Termina com `onMtuChanged(requestId, mtu, status)`.
	 * No iOS, onde o MTU não é negociável pelo app, o host responde com o MTU efetivo do link.
	 */
	public fun requestMtu(mtu: Int, requestId: Int)

	/** Escreve [value] na characteristic. Termina com `onWriteCompleted(requestId, status)`. */
	public fun write(service: String, characteristic: String, value: ByteArray, type: WriteType, requestId: Int)

	/** Lê a characteristic. Termina com `onReadCompleted(requestId, value, status)`. */
	public fun read(service: String, characteristic: String, requestId: Int)
}
