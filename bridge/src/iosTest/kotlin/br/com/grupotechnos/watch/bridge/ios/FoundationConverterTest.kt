package br.com.grupotechnos.watch.bridge.ios

import br.com.grupotechnos.watch.bridge.EventMapper
import br.com.grupotechnos.watch.protocol.domain.Battery
import br.com.grupotechnos.watch.protocol.log.LogLevel
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFBooleanGetTypeID
import platform.CoreFoundation.CFBooleanGetValue
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFNullGetTypeID
import platform.CoreFoundation.CFNumberGetTypeID
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringGetTypeID
import platform.CoreFoundation.CFTypeID
import platform.CoreFoundation.CFTypeRef
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSArray
import platform.Foundation.NSDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.create
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O conversor para Foundation, no simulador. O que importa é o que o React Native enxerga: ele separa `true`/`false` de
 * número pelo tipo Core Foundation (`CFGetTypeID(value) == CFBooleanGetTypeID()`), então é isso que se confere aqui,
 * e não só o valor.
 *
 * **Como olhar sem enganar o teste.** Ler o valor com `objectForKey` o traz de volta para o Kotlin como `Any?`, e o
 * Kotlin/Native converte nessa fronteira: `NSNull` vira `null`, `NSString` vira `String`, um `CFBoolean` vira
 * `Boolean` e um `NSNumber`, o número do Kotlin. Um `CFGetTypeID` feito depois disso mede o objeto que o Kotlin/Native
 * recriou, e não o que está no dicionário. Por isso o tipo é lido pelo Core Foundation (`CFDictionaryGetValue`,
 * `CFArrayGetValueAtIndex`), que devolve o ponteiro cru guardado, como o React Native o lê.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class FoundationConverterTest {
	@Test
	fun booleansBecomeCFBooleansAndNotNumbers() {
		val dictionary = FoundationConverter.toDictionary(mapOf("yes" to true, "no" to false, "one" to 1, "zero" to 0))
		assertEquals(CFBooleanGetTypeID(), typeIdAt(dictionary, "yes"))
		assertEquals(CFBooleanGetTypeID(), typeIdAt(dictionary, "no"))
		assertEquals(true, booleanAt(dictionary, "yes"))
		assertEquals(false, booleanAt(dictionary, "no"))
		// 1 e 0 continuam números: o JS recebe 1, e não true.
		assertEquals(CFNumberGetTypeID(), typeIdAt(dictionary, "one"))
		assertEquals(CFNumberGetTypeID(), typeIdAt(dictionary, "zero"))
	}

	@Test
	fun aKotlinMapHandedOverAsIsHasNoCFBooleans() {
		// Controle: o Map do Kotlin entregue sem o conversor (o proxy NSDictionary do Kotlin/Native) guarda o Boolean como
		// KotlinBoolean, que não é CFBoolean, e o RN o leria como número. É o motivo de o conversor existir, e prova que
		// a leitura pelo Core Foundation distingue os dois casos.
		val proxy = mapOf("yes" to true) as NSDictionary
		assertEquals(CFNumberGetTypeID(), typeIdAt(proxy, "yes"))
	}

	@Test
	fun numbersKeepTheirValues() {
		val dictionary = FoundationConverter.toDictionary(
			mapOf(
				"int" to -61,
				"maxInt" to Int.MAX_VALUE,
				"double" to 3012.5,
				"epochMillis" to 1_793_208_600_000L,
				"float" to 1.5f,
			),
		)
		assertEquals(-61, (dictionary.objectForKey("int") as NSNumber).intValue)
		assertEquals(Int.MAX_VALUE, (dictionary.objectForKey("maxInt") as NSNumber).intValue)
		assertEquals(3012.5, (dictionary.objectForKey("double") as NSNumber).doubleValue)
		// Long vira double, como o putDouble do Android: o JS só tem number.
		assertEquals(1_793_208_600_000.0, (dictionary.objectForKey("epochMillis") as NSNumber).doubleValue)
		assertEquals(1.5, (dictionary.objectForKey("float") as NSNumber).doubleValue)
		for (key in listOf("int", "maxInt", "double", "epochMillis", "float")) {
			assertEquals(CFNumberGetTypeID(), typeIdAt(dictionary, key), key)
		}
	}

	@Test
	fun stringsAndNullsBecomeNSStringAndNSNull() {
		val dictionary = FoundationConverter.toDictionary(mapOf("text" to "85%", "empty" to "", "missing" to null))
		assertEquals(CFStringGetTypeID(), typeIdAt(dictionary, "text"))
		assertEquals("85%", dictionary.objectForKey("text"))
		assertEquals("", dictionary.objectForKey("empty"))
		// A chave com null existe e vale NSNull, como o putNull do Android (o JS recebe null, não undefined).
		assertEquals(3UL, dictionary.count)
		assertEquals(CFNullGetTypeID(), typeIdAt(dictionary, "missing"))
	}

	@Test
	fun nestedMapsAndListsAreConvertedAllTheWayDown() {
		val dictionary = FoundationConverter.toDictionary(
			mapOf(
				"sleep" to mapOf(
					"items" to listOf(mapOf("typeItem" to 3, "deep" to true), null, listOf(1.5, "x")),
					"respiratoryRateItems" to emptyList<Any?>(),
				),
				"empty" to emptyMap<String, Any?>(),
			),
		)
		val sleep = dictionary.objectForKey("sleep") as NSDictionary
		val items = sleep.objectForKey("items") as NSArray
		assertEquals(3UL, items.count)
		val first = items.objectAtIndex(0UL) as NSDictionary
		assertEquals(3, (first.objectForKey("typeItem") as NSNumber).intValue)
		assertEquals(CFBooleanGetTypeID(), typeIdAt(first, "deep"))
		assertEquals(CFNullGetTypeID(), typeIdAt(items, 1))
		val inner = items.objectAtIndex(2UL) as NSArray
		assertEquals(1.5, (inner.objectAtIndex(0UL) as NSNumber).doubleValue)
		assertEquals("x", inner.objectAtIndex(1UL))
		assertEquals(CFStringGetTypeID(), typeIdAt(inner, 1))
		assertEquals(0UL, (sleep.objectForKey("respiratoryRateItems") as NSArray).count)
		assertEquals(0UL, (dictionary.objectForKey("empty") as NSDictionary).count)
	}

	@Test
	fun emptyContainersAndLooseValues() {
		assertEquals(0UL, FoundationConverter.toDictionary(emptyMap()).count)
		assertEquals(0UL, FoundationConverter.toArray(emptyList<Any?>()).count)
		// Valor solto (resolve de isConnected): uma lista de um elemento, e o host lê o elemento fora do Kotlin.
		for ((value, typeId) in listOf(null to CFNullGetTypeID(), true to CFBooleanGetTypeID(), 7 to CFNumberGetTypeID())) {
			val wrapped = FoundationConverter.toArray(listOf(value))
			assertEquals(1UL, wrapped.count)
			assertEquals(typeId, typeIdAt(wrapped, 0), "loose $value")
		}
		val array = FoundationConverter.toArray(listOf(1, true, "a", null))
		assertEquals(CFNumberGetTypeID(), typeIdAt(array, 0))
		assertEquals(CFBooleanGetTypeID(), typeIdAt(array, 1))
		assertEquals(CFStringGetTypeID(), typeIdAt(array, 2))
		assertEquals("a", array.objectAtIndex(2UL))
		assertEquals(CFNullGetTypeID(), typeIdAt(array, 3))
	}

	@Test
	fun unexpectedValuesBecomeTheirTextLikeOnAndroid() {
		val dictionary = FoundationConverter.toDictionary(mapOf("level" to LogLevel.WARN, "chars" to 'c'))
		assertEquals("WARN", dictionary.objectForKey("level"))
		assertEquals("c", dictionary.objectForKey("chars"))
		assertEquals(CFStringGetTypeID(), typeIdAt(dictionary, "level"))
	}

	@Test
	fun realPayloadsFromTheEventMapper() {
		val battery = FoundationConverter.toDictionary(EventMapper.battery(Battery(85)))
		assertEquals(85, (battery.objectForKey("percentage") as NSNumber).intValue)
		assertEquals(CFBooleanGetTypeID(), typeIdAt(battery, "charging"))
		assertEquals(false, booleanAt(battery, "charging"))
		assertEquals("85%", battery.objectForKey("battery"))
		val log = FoundationConverter.toDictionary(EventMapper.log(1_793_208_600_000L, LogLevel.DEBUG, "t", "m", null, "AB"))
		assertEquals(1_793_208_600_000.0, (log.objectForKey("ts") as NSNumber).doubleValue)
		assertEquals(CFNullGetTypeID(), typeIdAt(log, "dir"))
		assertTrue(log.objectForKey("hex") == "AB")
	}

	/** O tipo Core Foundation do valor guardado em [key], lido sem passar pelo Kotlin (ver o KDoc da classe). */
	private fun typeIdAt(dictionary: NSDictionary, key: String): CFTypeID = withValueAt(dictionary, key) { CFGetTypeID(it) }

	/** O tipo Core Foundation do elemento [index] de [array], lido sem passar pelo Kotlin. */
	private fun typeIdAt(array: NSArray, index: Int): CFTypeID =
		retained(array) { cfArray -> CFGetTypeID(CFArrayGetValueAtIndex(cfArray?.reinterpret(), index.convert())) }

	/** O valor do `CFBoolean` guardado em [key]. */
	private fun booleanAt(dictionary: NSDictionary, key: String): Boolean =
		withValueAt(dictionary, key) { CFBooleanGetValue(it?.reinterpret()) }

	private fun <T> withValueAt(dictionary: NSDictionary, key: String, block: (CFTypeRef?) -> T): T =
		retained(dictionary) { cfDictionary ->
			retained(NSString.create(string = key)) { cfKey -> block(CFDictionaryGetValue(cfDictionary?.reinterpret(), cfKey)) }
		}

	private fun <T> retained(value: Any, block: (CFTypeRef?) -> T): T {
		val reference = CFBridgingRetain(value)
		try {
			return block(reference)
		} finally {
			CFRelease(reference)
		}
	}
}
