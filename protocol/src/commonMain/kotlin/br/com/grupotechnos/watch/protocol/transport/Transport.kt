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
 * - Ciclo de vida do link (`docs/arquitetura.md`, seção 5): um `onDisconnected` por [disconnect] quando havia link
 *   aberto ou conexão pendente, nenhum quando não havia (`docs/decisoes.md`, ADR 12).
 * - **Bluetooth desligado (contrato B, ADR 18).** O host entrega `onBluetoothStateChanged(false)` e **nenhum**
 *   `onDisconnected` pelos links abertos e conexões pendentes que o desligamento derrubou, nem antes nem depois.
 *   Depois disso, [disconnect] não emite nada até um novo [connect]. Um `onDisconnected` que o host já tinha entregado
 *   antes do `onBluetoothStateChanged(false)` (o stack reportou a queda antes do aviso) é uma queda comum, tratada
 *   como sempre. É o que o `CoreBluetooth` faz (`poweredOff` sem `didDisconnectPeripheral`); no Android, o host
 *   precisa suprimir os `onDisconnected` que o `BluetoothGatt` entrega ao desligar.
 */
public interface Transport {
	/**
	 * Abre o link com o relógio.
	 *
	 * Termina com `onConnected` ou, se falhar sem chegar a conectar, com exatamente um `onDisconnected`: a conexão
	 * direta que falha e o `connect` que o host recusa na hora (Bluetooth desligado, sem permissão, endereço inválido).
	 * Só a conexão pendente (`autoReconnect = true`) que falha antes de conectar pode ficar sem evento, porque o host a
	 * mantém até o relógio voltar ou até um [disconnect]. Sem esse evento, a reconexão, que não tem timeout, ficaria
	 * presa (`docs/arquitetura.md`, seção 5).
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
	 *
	 * Depois de um `onBluetoothStateChanged(false)`, nada está aberto nem pendente: `disconnect` não emite nada até o
	 * próximo [connect] (contrato B, ADR 18). A sessão ainda chama `disconnect` nesse momento, só para o host liberar o
	 * que for dele (no Android, fechar o `BluetoothGatt`), e não espera resposta.
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
	 *
	 * O valor devolvido decide o tamanho de cada escrita: a sessão corta os frames em `mtu - 3` (ou em
	 * `Quirks.maxFragment`, quando o perfil fixa um), nunca abaixo de 20.
	 *
	 * **iOS.** O app não negocia o MTU: o host responde na hora com
	 * `peripheral.maximumWriteValueLength(for: .withoutResponse) + 3`, que reflete o MTU ATT real do link, e **nunca**
	 * com o valor de `.withResponse` (512, o limite de uma escrita longa com Prepare/Execute Write, que não depende do
	 * MTU): com ele a sessão mandaria o BIND de 45 bytes numa escrita só, que firmwares com Nordic UART costumam recusar.
	 * O tipo de escrita continua `.withResponse`. Com o fitGPS 0.2.1, que negociou MTU 23 no Android, os dois sistemas
	 * mandam fragmentos de 20 bytes (`docs/decisoes.md`, ADR 5, emenda).
	 */
	public fun requestMtu(mtu: Int, requestId: Int)

	/** Escreve [value] na characteristic. Termina com `onWriteCompleted(requestId, status)`. */
	public fun write(service: String, characteristic: String, value: ByteArray, type: WriteType, requestId: Int)

	/** Lê a characteristic. Termina com `onReadCompleted(requestId, value, status)`. */
	public fun read(service: String, characteristic: String, requestId: Int)
}
