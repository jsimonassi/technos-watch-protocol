package br.com.grupotechnos.watch.protocol.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.getBytes
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Copia os bytes para um `NSData` novo, que no Swift chega como `Data`.
 *
 * É o caminho dos bytes que a sessão entrega ao host: o `value` de `Transport.write` e de `WatchLogger.frame` chega
 * no Swift como `KotlinByteArray`, e o host faz `value.toData()` antes de passar para o `CBPeripheral`.
 *
 * - **Cópia**: o `NSData` não compartilha memória com o array; mudar o array depois não muda o resultado.
 * - **Vazio**: um array vazio vira um `NSData` de tamanho 0, sem tocar no endereço do array (um array vazio não tem
 *   elemento 0 para fixar).
 *
 * No Swift: `value.toData()` (método do `KotlinByteArray`). No Objective-C: `[value toNSData]`.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalObjCName::class)
@ObjCName("toNSData", swiftName = "toData")
public fun ByteArray.toNSData(): NSData {
	if (isEmpty()) return NSData()
	// initWithBytes:length: copia o buffer; o pin só precisa durar a chamada.
	return usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
}

/**
 * Copia os bytes de um `NSData` (no Swift, `Data`) para um `ByteArray` novo.
 *
 * O host raramente precisa disto direto: as sobrecargas de `TransportEvents` com `data:` (`onNotification` e
 * `onReadCompleted`) já fazem a cópia. Serve para o resto da API que recebe bytes, como `FrameJsonl.line`.
 *
 * - **Cópia**: o array não compartilha memória com o `NSData`; mudar um `NSMutableData` depois não muda o resultado.
 * - **Vazio**: um `NSData` de tamanho 0 vira um array vazio sem ler o ponteiro `bytes`, que pode ser nulo nesse caso.
 * - **Pedaço**: um `NSData` que é recorte de outro (`subdataWithRange`) copia só o recorte.
 *
 * No Swift: `ByteBridgeKt.toByteArray(data)`. No Objective-C: `[TWPByteBridgeKt toByteArray:data]`.
 *
 * @throws IllegalArgumentException se o `NSData` tiver mais de `Int.MAX_VALUE` bytes (o limite de um `ByteArray`).
 */
@OptIn(ExperimentalForeignApi::class)
public fun NSData.toByteArray(): ByteArray {
	val length = this.length
	require(length <= Int.MAX_VALUE.toULong()) { "NSData too large for a ByteArray: $length bytes" }
	val size = length.toInt()
	val out = ByteArray(size)
	if (size == 0) return out
	// getBytes:length: copia do começo deste NSData, inclusive quando ele é um recorte de outro buffer.
	out.usePinned { pinned -> getBytes(pinned.addressOf(0), length) }
	return out
}
