package br.com.grupotechnos.watch.protocol.sma.codec

import br.com.grupotechnos.watch.protocol.util.ByteReader
import br.com.grupotechnos.watch.protocol.util.ByteWriter

/**
 * Codificação e decodificação do frame SMA (`docs/protocolo-sma.md`, seções 2 e 3).
 *
 * ```
 * L1 (8 bytes)                                   L2 (5 + N bytes)
 * [0] AB  [1] flags  [2-3] len(L2)  [4-5] CRC    [8] cmd  [9] 00  [10] key  [11-12] N  [13..] value
 *                                   [6-7] seq
 * ```
 * Todos os campos multibyte em big-endian. `len(L2) = N + 5`; tamanho total = `len(L2) + 8`.
 * O CRC-16/ARC ([Crc16Arc]) cobre só o L2, por isso o sequence id não entra no CRC.
 *
 * Fontes: montagem em `SmaBleHelper.getBytes` (`SmaBleHelper.java:8-30`) e `SmaBusinessTool getSpliceCmd`
 * (`SmaBusinessTool.m:108-134`); verificação em `SmaManager.parseData` (`SmaManager.java:530-542`) e
 * `SmaBusinessTool checkCRC16` (`SmaBusinessTool.m:237-243`); ACK/NACK em `SmaManager.returnACK`
 * (`SmaManager.java:1029-1037`) e `SmaBusinessTool setAckCmdSeqId/setNackCmdSeqId` (`SmaBusinessTool.m:286-321`).
 *
 * Diferente da SDK Android, o tamanho é lido sem sinal (`SmaBleHelper.java:43` faz `res[2] << 8` com `byte`
 * com sinal, o que quebraria frames acima de 32 KiB) e frames acima de [MAX_FRAME_SIZE] são rejeitados.
 */
internal object SmaFrameCodec {
	/** Primeiro byte de todo frame. */
	const val MAGIC: Int = 0xAB

	/** Tamanho do L1. */
	const val HEADER_SIZE: Int = 8

	/** Tamanho do cabeçalho do L2 (`cmd`, `00`, `key`, `len` uint16). */
	const val L2_HEADER_SIZE: Int = 5

	/** Bit de "frame de controle" no byte de flags: `0x10` = ACK. */
	const val FLAG_ACK: Int = 0x10

	/** Bit de erro no byte de flags: junto com [FLAG_ACK], `0x30` = NACK (só a SDK iOS envia, `SmaBusinessTool.m:312`). */
	const val FLAG_ERROR: Int = 0x20

	/**
	 * Maior frame aceito. É o do maior comando conhecido, o stream EPO `05/60` (2033 bytes de bloco + 2 de índice
	 * + 13 de cabeçalhos = 2048; `SmaManager.java:1172-1234`). Também protege contra um tamanho corrompido
	 * que faria o reassembler esperar 64 KiB.
	 */
	const val MAX_FRAME_SIZE: Int = 2048

	/** Maior `value` que cabe em [MAX_FRAME_SIZE]. */
	const val MAX_VALUE_SIZE: Int = MAX_FRAME_SIZE - HEADER_SIZE - L2_HEADER_SIZE

	/** Tamanho de ACK e NACK. */
	const val CONTROL_SIZE: Int = HEADER_SIZE

	/**
	 * Bytes do frame de dados.
	 *
	 * @throws IllegalArgumentException se o `value` passar de [MAX_VALUE_SIZE].
	 */
	fun encode(frame: SmaFrame): ByteArray {
		val valueSize = frame.value.size
		require(valueSize <= MAX_VALUE_SIZE) { "value of $valueSize bytes exceeds $MAX_VALUE_SIZE" }
		val l2Size = valueSize + L2_HEADER_SIZE
		val l2 = ByteWriter(l2Size)
			.u8(frame.cmd)
			.u8(0x00)
			.u8(frame.key)
			.u16(valueSize)
			.bytes(frame.value)
			.toByteArray()
		return ByteWriter(HEADER_SIZE + l2Size)
			.u8(MAGIC)
			.u8(frame.flags)
			.u16(l2Size)
			.u16(Crc16Arc.compute(l2))
			.u16(frame.sequence)
			.bytes(l2)
			.toByteArray()
	}

	/** Atalho para [encode] de um frame de dados novo. */
	fun encode(sequence: Int, cmd: Int, key: Int, value: ByteArray = ByteArray(0)): ByteArray =
		encode(SmaFrame(sequence, cmd, key, value))

	/** ACK do frame [sequence]: `AB 10 00 00 00 00 SEQ_HI SEQ_LO`. */
	fun ack(sequence: Int): ByteArray = control(FLAG_ACK, sequence)

	/** NACK do frame [sequence]: `AB 30 00 00 00 00 SEQ_HI SEQ_LO`. */
	fun nack(sequence: Int): ByteArray = control(FLAG_ACK or FLAG_ERROR, sequence)

	/**
	 * `true` se o pacote é um ACK ou NACK completo: exatamente 8 bytes, `0xAB` e bit `0x10` nas flags.
	 * É a mesma condição de `SmaManager.receiveData` (`SmaManager.java:508-516`).
	 */
	fun isControl(packet: ByteArray): Boolean =
		packet.size == CONTROL_SIZE && (packet[0].toInt() and 0xFF) == MAGIC &&
			(packet[1].toInt() and FLAG_ACK) != 0

	/**
	 * Tamanho total do frame que começa em [offset], lido do L1 (`len(L2) + 8`), ou `null` se ainda não há
	 * 4 bytes ou se o byte inicial não é `0xAB`.
	 */
	fun frameLength(bytes: ByteArray, offset: Int = 0): Int? {
		if (bytes.size - offset < 4) return null
		if ((bytes[offset].toInt() and 0xFF) != MAGIC) return null
		return ByteReader.u16At(bytes, offset + 2) + HEADER_SIZE
	}

	/** Decodifica um frame completo (dados, ACK ou NACK). Nunca lança: erros voltam em [SmaDecoded.Invalid]. */
	fun decode(bytes: ByteArray): SmaDecoded {
		if (bytes.isEmpty() || (bytes[0].toInt() and 0xFF) != MAGIC) {
			return SmaDecoded.Invalid(SmaDecodeError.BAD_MAGIC, null, "first byte is not AB")
		}
		if (bytes.size < HEADER_SIZE) {
			return SmaDecoded.Invalid(SmaDecodeError.TRUNCATED, null, "${bytes.size} bytes, header needs $HEADER_SIZE")
		}
		val flags = bytes[1].toInt() and 0xFF
		val l2Size = ByteReader.u16At(bytes, 2)
		val sequence = ByteReader.u16At(bytes, 6)
		if (flags and FLAG_ACK != 0) {
			if (bytes.size != CONTROL_SIZE) {
				return SmaDecoded.Invalid(SmaDecodeError.BAD_LENGTH, sequence, "control frame with ${bytes.size} bytes")
			}
			return if (flags and FLAG_ERROR != 0) SmaDecoded.Nack(sequence) else SmaDecoded.Ack(sequence)
		}
		val total = l2Size + HEADER_SIZE
		if (total > MAX_FRAME_SIZE) {
			return SmaDecoded.Invalid(SmaDecodeError.OVERSIZED, sequence, "declared $total bytes")
		}
		if (bytes.size < total) {
			return SmaDecoded.Invalid(SmaDecodeError.TRUNCATED, sequence, "${bytes.size} of $total bytes")
		}
		if (bytes.size > total) {
			return SmaDecoded.Invalid(SmaDecodeError.BAD_LENGTH, sequence, "${bytes.size - total} trailing bytes")
		}
		if (l2Size < L2_HEADER_SIZE) {
			return SmaDecoded.Invalid(SmaDecodeError.BAD_LENGTH, sequence, "L2 with $l2Size bytes")
		}
		val expectedCrc = ByteReader.u16At(bytes, 4)
		val actualCrc = Crc16Arc.compute(bytes, HEADER_SIZE, l2Size)
		if (expectedCrc != actualCrc) {
			return SmaDecoded.Invalid(
				SmaDecodeError.CRC_MISMATCH,
				sequence,
				"crc ${hex16(expectedCrc)} != computed ${hex16(actualCrc)}",
			)
		}
		val valueSize = ByteReader.u16At(bytes, HEADER_SIZE + 3)
		if (valueSize + L2_HEADER_SIZE != l2Size) {
			return SmaDecoded.Invalid(SmaDecodeError.BAD_LENGTH, sequence, "value length $valueSize in L2 of $l2Size")
		}
		val value = bytes.copyOfRange(HEADER_SIZE + L2_HEADER_SIZE, total)
		val cmd = bytes[HEADER_SIZE].toInt() and 0xFF
		val key = bytes[HEADER_SIZE + 2].toInt() and 0xFF
		return SmaDecoded.Data(SmaFrame(sequence, cmd, key, value, flags))
	}

	/** `"02/01"`: cmd e key em hexadecimal maiúsculo, como no catálogo da documentação. */
	fun cmdKey(cmd: Int, key: Int): String = hex8(cmd) + "/" + hex8(key)

	private fun control(flags: Int, sequence: Int): ByteArray {
		require(sequence in 0..0xFFFF) { "sequence out of uint16: $sequence" }
		return ByteWriter(CONTROL_SIZE)
			.u8(MAGIC)
			.u8(flags)
			.u16(0)
			.u16(0)
			.u16(sequence)
			.toByteArray()
	}

	private fun hex8(value: Int): String = value.toString(16).uppercase().padStart(2, '0')

	private fun hex16(value: Int): String = value.toString(16).uppercase().padStart(4, '0')
}
