package br.com.grupotechnos.watch.protocol.util

/**
 * Conversão entre bytes e texto hexadecimal, usada em log, fixtures e testes.
 */
internal object Hex {
	private const val DIGITS = "0123456789ABCDEF"

	/** Hexadecimal maiúsculo sem separadores (`"AB0000"`). */
	fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
		for (byte in bytes) {
			val value = byte.toInt() and 0xFF
			append(DIGITS[value ushr 4])
			append(DIGITS[value and 0x0F])
		}
	}

	/** Hexadecimal com espaço entre bytes (`"AB 00 00"`), para log. */
	fun encodeSpaced(bytes: ByteArray): String = bytes.joinToString(" ") { byte ->
		val value = byte.toInt() and 0xFF
		"${DIGITS[value ushr 4]}${DIGITS[value and 0x0F]}"
	}

	/** Aceita maiúsculas, minúsculas, espaços e `:`. Lança [IllegalArgumentException] se o texto for inválido. */
	fun decode(text: String): ByteArray {
		val clean = text.filterNot { it == ' ' || it == ':' || it == '\n' || it == '\t' }
		require(clean.length % 2 == 0) { "hex with an odd number of digits: $text" }
		return ByteArray(clean.length / 2) { index ->
			val high = digit(clean[index * 2])
			val low = digit(clean[index * 2 + 1])
			((high shl 4) or low).toByte()
		}
	}

	private fun digit(char: Char): Int = when (char) {
		in '0'..'9' -> char - '0'
		in 'a'..'f' -> char - 'a' + 10
		in 'A'..'F' -> char - 'A' + 10
		else -> throw IllegalArgumentException("invalid hex digit: $char")
	}
}
