package br.com.grupotechnos.watch.bridge.ios

import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFArrayAppendValue
import platform.CoreFoundation.CFArrayCreateMutable
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNull
import platform.CoreFoundation.kCFNumberFloat64Type
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.CoreFoundation.kCFTypeArrayCallBacks
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSArray
import platform.Foundation.NSDictionary
import platform.Foundation.NSNull

/**
 * Converte os payloads do contrato JS (`Map<String, Any?>`/`List<Any?>` do `EventMapper`) em objetos Foundation de
 * verdade, para o host iOS entregar ao React Native (`emitOnX:`, `resolve(...)`).
 *
 * É o par do `WritableConverter` do Android, com as mesmas regras de tipo.
 *
 * **Por que não entregar o `Map` direto.** Um `Map` do Kotlin chega no Objective-C como um `NSDictionary` que é um
 * proxy do Kotlin/Native, e um `Boolean` dentro dele chega como `KotlinBoolean`, uma subclasse de `NSNumber` que não é
 * o `__NSCFBoolean` (`CFBoolean`). O React Native decide entre `true`/`false` e número olhando essa classe
 * (`[value isKindOfClass:[@YES class]]`, o mesmo que `CFGetTypeID(value) == CFBooleanGetTypeID()`); um `KotlinBoolean`
 * vira `1`/`0` no JavaScript, e `charging: false` chegaria como `charging: 0`.
 *
 * **Por que montar pelo Core Foundation.** O Kotlin/Native converte toda referência Objective-C que entra no Kotlin,
 * inclusive o retorno tipado de um método: `NSNumber.numberWithBool(true)` já é um `kotlin.Boolean` dentro do Kotlin, e
 * volta para o Objective-C como `KotlinBoolean`. Nenhuma variável Kotlin consegue guardar um `CFBoolean`. Por isso os
 * dicionários e arrays são criados e preenchidos com ponteiros crus (`CFDictionarySetValue`, `CFArrayAppendValue`), com
 * as constantes `kCFBooleanTrue`/`kCFBooleanFalse` e números de `CFNumberCreate`, e só o contêiner de fora passa para o
 * Kotlin (que devolve ao Objective-C o mesmo objeto, sem tocar no que tem dentro):
 *
 * | Kotlin | Core Foundation / Foundation | JavaScript |
 * |---|---|---|
 * | `null` | `kCFNull` (`NSNull`) | `null` |
 * | `String` | `NSString` | `string` |
 * | `Boolean` | `kCFBooleanTrue`/`kCFBooleanFalse` (`__NSCFBoolean`) | `boolean` |
 * | `Int` | `CFNumber` `kCFNumberSInt32Type` | `number` |
 * | `Long`, `Double`, `Float` e outros `Number` | `CFNumber` `kCFNumberFloat64Type` (como `putDouble` no Android) | `number` |
 * | `Map` | `NSDictionary` (chaves com `toString()`) | objeto |
 * | `List` | `NSArray` | array |
 * | um `NSNull` que já veio pronto | `kCFNull` | `null` |
 * | qualquer outra coisa | `NSString` do `toString()`, em vez de derrubar o processo | `string` |
 *
 * **Valor solto.** Pelo mesmo motivo não existe um `toObject(value)`: um `Boolean` devolvido por uma função Kotlin
 * chega ao Objective-C sempre como `KotlinBoolean`. Para resolver uma Promise com um valor solto (o `isConnected`), o host
 * converte uma lista de um elemento e passa o elemento: no Swift, `FoundationConverter.shared.toArray(values: [value])[0]`;
 * no Objective-C, `[[TWPFoundationConverter.shared toArrayValues:@[value]] firstObject]`. O elemento é lido pelo
 * Objective-C/Swift, fora do Kotlin, e continua `__NSCFBoolean`.
 *
 * Toda conversão copia e percorre a estrutura inteira; os resultados são `NSMutableDictionary`/`NSMutableArray`
 * novos, que o host pode guardar. Pode ser chamado de qualquer thread.
 *
 * No Swift: `FoundationConverter.shared.toDictionary(payload: map)`. No Objective-C:
 * `[TWPFoundationConverter.shared toDictionaryPayload:map]`.
 */
@OptIn(ExperimentalForeignApi::class)
public object FoundationConverter {
	/**
	 * Um payload de evento ou de retorno como `NSDictionary` (no Swift, `[AnyHashable: Any]`).
	 *
	 * No Swift: `FoundationConverter.shared.toDictionary(payload:)`.
	 */
	public fun toDictionary(payload: Map<String, Any?>): NSDictionary =
		CFBridgingRelease(retainedDictionary(payload)) as NSDictionary

	/**
	 * Uma lista (ex.: o retorno de `getLogs`, ou um valor solto embrulhado numa lista de um elemento) como `NSArray`
	 * (no Swift, `[Any]`).
	 *
	 * No Swift: `FoundationConverter.shared.toArray(values:)`.
	 */
	public fun toArray(values: List<*>): NSArray = CFBridgingRelease(retainedArray(values)) as NSArray

	// Cada função `retained*` devolve uma referência com +1; quem guarda num contêiner solta a sua depois (o contêiner
	// retém a dele, pelos callbacks kCFType*).

	private fun retainedDictionary(source: Map<*, *>): CFTypeRef? {
		val out = CFDictionaryCreateMutable(
			null,
			0,
			kCFTypeDictionaryKeyCallBacks.ptr,
			kCFTypeDictionaryValueCallBacks.ptr,
		)
		for ((key, value) in source) {
			val cfKey = retainedString(key.toString())
			val cfValue = retainedValue(value)
			CFDictionarySetValue(out, cfKey, cfValue)
			CFRelease(cfValue)
			CFRelease(cfKey)
		}
		return out?.reinterpret()
	}

	private fun retainedArray(source: List<*>): CFTypeRef? {
		val out = CFArrayCreateMutable(null, 0, kCFTypeArrayCallBacks.ptr)
		for (value in source) {
			val cfValue = retainedValue(value)
			CFArrayAppendValue(out, cfValue)
			CFRelease(cfValue)
		}
		return out?.reinterpret()
	}

	// A ordem segue a do WritableConverter do Android: Boolean antes de Number, Int antes dos outros números.
	private fun retainedValue(value: Any?): CFTypeRef? = when (value) {
		null -> CFRetain(kCFNull)
		is String -> retainedString(value)
		is Boolean -> CFRetain(if (value) kCFBooleanTrue else kCFBooleanFalse)
		is Int -> retainedInt(value)
		is Long -> retainedDouble(value.toDouble())
		is Double -> retainedDouble(value)
		is Float -> retainedDouble(value.toDouble())
		is Number -> retainedDouble(value.toDouble())
		is Map<*, *> -> retainedDictionary(value)
		is List<*> -> retainedArray(value)
		is NSNull -> CFRetain(kCFNull)
		else -> retainedString(value.toString())
	}

	// O Kotlin/Native converte a String num NSString na passagem; o CFBridgingRetain fica com +1 dele.
	private fun retainedString(value: String): CFTypeRef? = CFBridgingRetain(value)

	private fun retainedInt(value: Int): CFTypeRef? = memScoped {
		val cell = alloc<IntVar>()
		cell.value = value
		CFNumberCreate(null, kCFNumberSInt32Type.convert(), cell.ptr)?.reinterpret()
	}

	private fun retainedDouble(value: Double): CFTypeRef? = memScoped {
		val cell = alloc<DoubleVar>()
		cell.value = value
		CFNumberCreate(null, kCFNumberFloat64Type.convert(), cell.ptr)?.reinterpret()
	}
}
