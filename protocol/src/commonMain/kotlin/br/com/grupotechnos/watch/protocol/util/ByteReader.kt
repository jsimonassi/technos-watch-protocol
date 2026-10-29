package br.com.grupotechnos.watch.protocol.util

/**
 * Leitura sequencial de inteiros sem sinal em big-endian.
 *
 * Todos os protocolos suportados até aqui (SMA) usam big-endian em todos os campos multibyte
 * (ver `docs/protocolo-sma.md`, seção 2). Os valores voltam como `Int`/`Long` não negativos, sem o
 * problema de extensão de sinal de `Byte` que a SDK Android original tem em `SmaBleHelper.java:43`.
 *
 * Ler além do fim lança [IndexOutOfBoundsException]; quem decodifica dados vindos do relógio deve
 * conferir o tamanho antes (o codec SMA confere e devolve um erro tipado).
 *
 * @param bytes buffer lido; não é copiado.
 * @param start posição inicial da leitura.
 */
internal class ByteReader(private val bytes: ByteArray, start: Int = 0) {
	/** Posição do próximo byte a ser lido. */
	var position: Int = start
		private set

	/** Bytes que ainda podem ser lidos. */
	val remaining: Int
		get() = bytes.size - position

	/** Um byte sem sinal (0..255). */
	fun u8(): Int {
		ensure(1)
		return bytes[position++].toInt() and 0xFF
	}

	/** Dois bytes big-endian sem sinal (0..65535). */
	fun u16(): Int {
		ensure(2)
		val value = u16At(bytes, position)
		position += 2
		return value
	}

	/** Três bytes big-endian sem sinal (0..16777215). É o tamanho do campo de passos do SMA. */
	fun u24(): Int {
		ensure(3)
		val value = u24At(bytes, position)
		position += 3
		return value
	}

	/** Quatro bytes big-endian sem sinal (0..4294967295), em `Long` para não perder o bit mais alto. */
	fun u32(): Long {
		ensure(4)
		val value = u32At(bytes, position)
		position += 4
		return value
	}

	/** Copia os próximos [count] bytes. */
	fun bytes(count: Int): ByteArray {
		ensure(count)
		val slice = bytes.copyOfRange(position, position + count)
		position += count
		return slice
	}

	/** Pula [count] bytes. */
	fun skip(count: Int) {
		ensure(count)
		position += count
	}

	private fun ensure(count: Int) {
		if (count < 0 || position + count > bytes.size) {
			throw IndexOutOfBoundsException("read of $count bytes at position $position in a buffer of ${bytes.size}")
		}
	}

	companion object {
		/** Byte sem sinal em [offset], sem criar um leitor. */
		fun u8At(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

		/** `u16` big-endian em [offset], sem criar um leitor. */
		fun u16At(bytes: ByteArray, offset: Int): Int = (u8At(bytes, offset) shl 8) or u8At(bytes, offset + 1)

		/** `u24` big-endian em [offset], sem criar um leitor. */
		fun u24At(bytes: ByteArray, offset: Int): Int =
			(u8At(bytes, offset) shl 16) or (u8At(bytes, offset + 1) shl 8) or u8At(bytes, offset + 2)

		/** `u32` big-endian em [offset], sem criar um leitor. */
		fun u32At(bytes: ByteArray, offset: Int): Long =
			(u8At(bytes, offset).toLong() shl 24) or (u24At(bytes, offset + 1).toLong())
	}
}
