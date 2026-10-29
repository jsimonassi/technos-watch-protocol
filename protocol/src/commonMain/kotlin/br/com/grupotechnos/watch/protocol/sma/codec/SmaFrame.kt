package br.com.grupotechnos.watch.protocol.sma.codec

import br.com.grupotechnos.watch.protocol.util.Hex

/**
 * Um frame de dados SMA já decodificado (L1 + L2, ver `docs/protocolo-sma.md`, seção 2).
 *
 * Frames de controle (ACK/NACK) não são representados aqui: eles só têm L1 e chegam como
 * [SmaDecoded.Ack]/[SmaDecoded.Nack].
 *
 * @property sequence sequence id do L1 (bytes 6-7, uint16). Nos frames que enviamos vem de [SmaSequence];
 * nos que o relógio envia é o contador dele, que copiamos no ACK.
 * @property cmd família do comando (L2 byte 0; ex.: `0x02` SET, `0x03` CONNECT, `0x05` DATA).
 * @property key comando dentro da família (L2 byte 2). Respostas usam `key + 1`.
 * @property value payload (L2 a partir do byte 5), com tamanho declarado no L2 bytes 3-4.
 * @property flags byte 1 do L1. É sempre `0x00` nos frames de dados conhecidos; guardado só para log.
 */
internal class SmaFrame(
	val sequence: Int,
	val cmd: Int,
	val key: Int,
	val value: ByteArray,
	val flags: Int = 0,
) {
	init {
		require(sequence in 0..0xFFFF) { "sequence out of uint16: $sequence" }
		require(cmd in 0..0xFF) { "cmd out of uint8: $cmd" }
		require(key in 0..0xFF) { "key out of uint8: $key" }
		require(flags in 0..0xFF && flags and SmaFrameCodec.FLAG_ACK == 0) { "invalid data frame flags: $flags" }
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is SmaFrame) return false
		return sequence == other.sequence && cmd == other.cmd && key == other.key && flags == other.flags &&
			value.contentEquals(other.value)
	}

	override fun hashCode(): Int {
		var result = sequence
		result = 31 * result + cmd
		result = 31 * result + key
		result = 31 * result + flags
		result = 31 * result + value.contentHashCode()
		return result
	}

	override fun toString(): String =
		"SmaFrame(seq=$sequence, ${SmaFrameCodec.cmdKey(cmd, key)}, flags=$flags, value=${Hex.encode(value)})"
}

/**
 * Resultado de [SmaFrameCodec.decode].
 */
internal sealed interface SmaDecoded {
	/** Frame de dados com CRC correto. */
	data class Data(val frame: SmaFrame) : SmaDecoded

	/** ACK de 8 bytes (`AB 10 00 00 00 00 SEQ`). */
	data class Ack(val sequence: Int) : SmaDecoded

	/** NACK de 8 bytes (`AB 30 00 00 00 00 SEQ`). */
	data class Nack(val sequence: Int) : SmaDecoded

	/**
	 * Bytes que não formam um frame válido.
	 *
	 * @property sequence sequence id do L1 quando o cabeçalho foi lido; é o que vai no NACK.
	 */
	data class Invalid(val error: SmaDecodeError, val sequence: Int?, val detail: String) : SmaDecoded
}

/** Motivos de rejeição de um frame. */
internal enum class SmaDecodeError {
	/** O primeiro byte não é `0xAB`. */
	BAD_MAGIC,

	/** Menos bytes do que o L1 declara (ou menos que o próprio L1). */
	TRUNCATED,

	/** O L1 declara um frame maior que [SmaFrameCodec.MAX_FRAME_SIZE]. */
	OVERSIZED,

	/** Tamanhos incoerentes: bytes sobrando, L2 menor que 5 bytes ou `len(value)` diferente de `len(L2) - 5`. */
	BAD_LENGTH,

	/** O CRC-16/ARC do L2 não bate com o dos bytes 4-5. */
	CRC_MISMATCH,
}
