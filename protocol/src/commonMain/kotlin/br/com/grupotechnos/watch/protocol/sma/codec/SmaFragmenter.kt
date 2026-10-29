package br.com.grupotechnos.watch.protocol.sma.codec

import br.com.grupotechnos.watch.protocol.profile.Quirks

/**
 * Corte de um frame em escritas GATT (`docs/protocolo-sma.md`, seção 1).
 *
 * O frame é cortado em pedaços de tamanho fixo, na ordem, sem cabeçalho por fragmento: o primeiro pedaço
 * começa com `0xAB` e os outros são bytes crus. É o que as duas SDKs fazem: Android em pedaços de `mtu - 3`
 * (`SmaMessenger.java:222-250`, com `mMtu = mtu - 3` em `SmaManager.java:305`) e iOS em pedaços fixos de 20
 * (`SmaBLE.m:579-605` para o BIND de 45 bytes em 20 + 20 + 5).
 */
internal object SmaFragmenter {
	/**
	 * Menor payload de escrita garantido pelo BLE: ATT_MTU mínimo 23 menos 3 bytes de cabeçalho ATT.
	 * Um MTU informado abaixo disso é tratado como 23.
	 */
	const val MIN_FRAGMENT: Int = 20

	/**
	 * Tamanho de cada escrita: `quirks.maxFragment` quando definido, senão `mtu - 3`; nunca menos que [MIN_FRAGMENT].
	 */
	fun fragmentSize(quirks: Quirks, mtu: Int): Int = (quirks.maxFragment ?: (mtu - 3)).coerceAtLeast(MIN_FRAGMENT)

	/** Pedaços de até [fragmentSize] bytes cuja concatenação é [frame]. Um frame vazio gera lista vazia. */
	fun split(frame: ByteArray, fragmentSize: Int): List<ByteArray> {
		require(fragmentSize > 0) { "fragmentSize must be positive: $fragmentSize" }
		if (frame.isEmpty()) return emptyList()
		val fragments = ArrayList<ByteArray>((frame.size + fragmentSize - 1) / fragmentSize)
		var start = 0
		while (start < frame.size) {
			val end = minOf(start + fragmentSize, frame.size)
			fragments += frame.copyOfRange(start, end)
			start = end
		}
		return fragments
	}
}
