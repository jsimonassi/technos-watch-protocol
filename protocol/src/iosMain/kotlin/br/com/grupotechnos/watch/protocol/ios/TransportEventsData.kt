package br.com.grupotechnos.watch.protocol.ios

import br.com.grupotechnos.watch.protocol.transport.TransportEvents
import platform.Foundation.NSData

/**
 * Uma notificação chegou, com o valor como `NSData` (no Swift, `Data`), exatamente como o `CBPeripheral` entregou.
 *
 * Mesmo efeito de `TransportEvents.onNotification(characteristic, value)`: copia os bytes ([toByteArray]) e enfileira
 * um evento só. Como na versão com `ByteArray`, o host chama uma vez por notificação, na ordem de chegada, de
 * qualquer thread (no iOS, a fila de delegate do `CBCentralManager`); a chamada não bloqueia.
 *
 * No Swift: `session.events.onNotification(characteristic: uuid, data: value)`. O rótulo é `data:`, e não `value:`,
 * para não colidir no Objective-C com o método que recebe `KotlinByteArray`.
 *
 * @param characteristic UUID da characteristic em qualquer forma; a sessão normaliza.
 * @param data valor da notificação; a sessão fica com uma cópia, então o host pode reutilizar o buffer.
 */
public fun TransportEvents.onNotification(characteristic: String, data: NSData) {
	onNotification(characteristic, data.toByteArray())
}

/**
 * Resultado de `Transport.read`, com o valor como `NSData` (no Swift, `Data?`).
 *
 * Mesmo efeito de `TransportEvents.onReadCompleted(requestId, value, status)`: copia os bytes e enfileira um evento só.
 *
 * No Swift: `session.events.onReadCompleted(requestId: id, data: characteristic.value, status: 0)`.
 *
 * @param requestId o mesmo `requestId` do `Transport.read`.
 * @param data valor lido, ou `null` em caso de falha.
 * @param status `GattStatus.SUCCESS` (0) ou o código de erro nativo.
 */
public fun TransportEvents.onReadCompleted(requestId: Int, data: NSData?, status: Int) {
	onReadCompleted(requestId, data?.toByteArray(), status)
}
