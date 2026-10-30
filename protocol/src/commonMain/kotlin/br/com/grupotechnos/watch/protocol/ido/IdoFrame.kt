package br.com.grupotechnos.watch.protocol.ido

/**
 * Um frame legado iDO recebido do relógio, já completado com zeros até [IdoFrameCodec.MAX_FRAME] bytes.
 *
 * O formato é `[cmd][key][payload]`, sem tamanho, sequência nem CRC (`docs/protocolo-ido.md`, seção 2). Os offsets dos
 * campos (`bytes[7]` é a bateria da informação básica, por exemplo) contam a partir do `cmd`, como no log da SDK
 * (`20261029.log`, linhas `receive <=`).
 *
 * @property bytes o frame inteiro, com pelo menos [IdoFrameCodec.MAX_FRAME] bytes; não é copiado.
 */
internal class IdoFrame(val bytes: ByteArray) {
	/** Primeiro byte: o grupo do comando. */
	val cmd: Int
		get() = bytes[0].toInt() and 0xFF

	/** Segundo byte: o comando dentro do grupo. */
	val key: Int
		get() = bytes[1].toInt() and 0xFF

	/** Byte sem sinal em [offset]. */
	fun u8(offset: Int): Int = bytes[offset].toInt() and 0xFF

	/** `u16` little-endian em [offset]. */
	fun u16le(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

	/** `u32` little-endian em [offset], em `Long` para não perder o bit mais alto. */
	fun u32le(offset: Int): Long = u16le(offset).toLong() or (u16le(offset + 2).toLong() shl 16)
}

/** Resultado de [IdoFrameCodec.decode] para uma notificação crua. */
internal sealed interface IdoDecoded {
	/** Frame legado (`[cmd][key]...`), já completado com zeros. */
	class Frame(val frame: IdoFrame) : IdoDecoded

	/** Pacote do protocolo V3 (primeiro byte `0x33`): não usado pela PoC, descartado. */
	data object V3 : IdoDecoded

	/** Pacote curto demais para ter `cmd` e `key`. */
	data object TooShort : IdoDecoded
}

/**
 * Codec do frame legado iDO (`docs/protocolo-ido.md`, seção 2).
 *
 * - **Envio**: `[cmd][key][payload]`, no máximo 20 bytes, uma escrita GATT por frame. É o que a biblioteca nativa da
 *   SDK monta (`protocol_write_set_cmd_key`, limite `PROTOCOL_TX_MAX_SIZE` = 20) e o que o log mostra em cada
 *   `send =>` (`20261029.log` linhas 96, 144, 154, 5360, 5745, 5774, 5833).
 * - **Recepção**: a resposta repete `[cmd][key]`. Um pacote com menos de 20 bytes é completado com zeros antes do
 *   parse, como a SDK faz antes de entregar à biblioteca nativa; por isso os parsers leem por offset e um campo que
 *   não veio vale 0 (o `02/EB` chega com 12 bytes, linha 5776; o `04/05` com 20, linha 158).
 * - **V3**: todo pacote do protocolo V3 começa com `0x33`: a SDK manda cada pedaço como `0x33` seguido de até
 *   `MTU - 1` bytes, o primeiro com `DA AD DA AD` (linha 330), e na recepção trata à parte, sem completar, todo pacote
 *   que começa com `0x33`. Aqui eles são descartados, porque a PoC não usa o V3.
 */
internal object IdoFrameCodec {
	/** Tamanho máximo de um frame legado e tamanho a que a recepção completa os pacotes curtos. */
	const val MAX_FRAME: Int = 20

	/** Primeiro byte de todo pacote do protocolo V3. */
	const val V3_FIRST_BYTE: Int = 0x33

	/**
	 * Monta o frame `[cmd][key][payload]`.
	 *
	 * @throws IllegalArgumentException se o frame passar de [MAX_FRAME] bytes ou `cmd`/`key` não couberem num byte.
	 */
	fun encode(cmd: Int, key: Int, payload: ByteArray = ByteArray(0)): ByteArray {
		require(cmd in 0..0xFF && key in 0..0xFF) { "cmd/key out of range: $cmd/$key" }
		require(2 + payload.size <= MAX_FRAME) { "iDO frame of ${2 + payload.size} bytes exceeds $MAX_FRAME" }
		val frame = ByteArray(2 + payload.size)
		frame[0] = cmd.toByte()
		frame[1] = key.toByte()
		payload.copyInto(frame, destinationOffset = 2)
		return frame
	}

	/** Classifica uma notificação e, se for frame legado, completa com zeros até [MAX_FRAME] bytes. */
	fun decode(packet: ByteArray): IdoDecoded {
		if (packet.isNotEmpty() && (packet[0].toInt() and 0xFF) == V3_FIRST_BYTE) return IdoDecoded.V3
		if (packet.size < 2) return IdoDecoded.TooShort
		val padded = if (packet.size >= MAX_FRAME) packet.copyOf() else packet.copyOf(MAX_FRAME)
		return IdoDecoded.Frame(IdoFrame(padded))
	}

	/** `cmd/key` em hexadecimal maiúsculo (ex.: `"02/A0"`). */
	fun cmdKey(cmd: Int, key: Int): String = hex2(cmd) + "/" + hex2(key)

	/**
	 * Resumo de um pacote cru para o log (TX ou RX): `"02/01 GET_BASIC_INFO len=20"`, `"V3"` ou vazio para um pacote
	 * sem `cmd`/`key`.
	 */
	fun describe(packet: ByteArray): String {
		if (packet.isNotEmpty() && (packet[0].toInt() and 0xFF) == V3_FIRST_BYTE) return "V3 len=${packet.size}"
		if (packet.size < 2) return ""
		val cmd = packet[0].toInt() and 0xFF
		val key = packet[1].toInt() and 0xFF
		return "${IdoCommand.label(cmd, key)} len=${packet.size}"
	}

	private fun hex2(value: Int): String = value.toString(16).uppercase().padStart(2, '0')
}
