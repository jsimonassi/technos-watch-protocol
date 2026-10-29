package br.com.grupotechnos.watch.protocol.util

/**
 * Montagem sequencial de bytes em big-endian, par de [ByteReader].
 *
 * Os métodos validam a faixa do valor: um valor que não cabe no campo é erro de programação
 * (lança [IllegalArgumentException]) em vez de ser truncado em silêncio, como acontece nos
 * `(byte)` da SDK Android original.
 *
 * @param initialCapacity capacidade inicial do buffer interno; cresce sob demanda.
 */
internal class ByteWriter(initialCapacity: Int = 32) {
	private var buffer = ByteArray(initialCapacity.coerceAtLeast(1))

	/** Quantidade de bytes escritos até agora. */
	var size: Int = 0
		private set

	/** Um byte sem sinal (0..255). */
	fun u8(value: Int): ByteWriter {
		require(value in 0..0xFF) { "u8 out of range: $value" }
		put(value)
		return this
	}

	/** Dois bytes big-endian (0..65535). */
	fun u16(value: Int): ByteWriter {
		require(value in 0..0xFFFF) { "u16 out of range: $value" }
		put(value ushr 8)
		put(value)
		return this
	}

	/** Três bytes big-endian (0..16777215). */
	fun u24(value: Int): ByteWriter {
		require(value in 0..0xFFFFFF) { "u24 out of range: $value" }
		put(value ushr 16)
		put(value ushr 8)
		put(value)
		return this
	}

	/** Quatro bytes big-endian (0..4294967295). */
	fun u32(value: Long): ByteWriter {
		require(value in 0L..0xFFFFFFFFL) { "u32 out of range: $value" }
		put((value ushr 24).toInt())
		put((value ushr 16).toInt())
		put((value ushr 8).toInt())
		put(value.toInt())
		return this
	}

	/** Bytes crus, copiados. */
	fun bytes(value: ByteArray): ByteWriter {
		ensureCapacity(size + value.size)
		value.copyInto(buffer, destinationOffset = size)
		size += value.size
		return this
	}

	/** [count] bytes `00`. */
	fun zeros(count: Int): ByteWriter {
		require(count >= 0) { "negative count: $count" }
		ensureCapacity(size + count)
		size += count
		return this
	}

	/** Cópia do que foi escrito. */
	fun toByteArray(): ByteArray = buffer.copyOf(size)

	private fun put(value: Int) {
		ensureCapacity(size + 1)
		buffer[size++] = (value and 0xFF).toByte()
	}

	private fun ensureCapacity(needed: Int) {
		if (needed <= buffer.size) return
		var newSize = buffer.size * 2
		while (newSize < needed) newSize *= 2
		buffer = buffer.copyOf(newSize)
	}
}
