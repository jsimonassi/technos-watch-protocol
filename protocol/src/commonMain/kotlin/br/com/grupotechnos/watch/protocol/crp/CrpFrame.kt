package br.com.grupotechnos.watch.protocol.crp

import br.com.grupotechnos.watch.protocol.util.Hex

/**
 * Um frame CRP já remontado: `FE EA | b2 | len | cmd | payload` sem o cabeçalho (`docs/protocolo-crp.md`, seção 3).
 *
 * Não há sequência, CRC nem ACK de aplicação: a resposta repete o `cmd` do pedido e, nos comandos multiplexados, o
 * primeiro byte do payload (o "sub"; dois bytes no `B6`). Ver [CrpCommand].
 *
 * @property cmd quinto byte do frame (sem sinal).
 * @property payload bytes depois do `cmd`, até o comprimento declarado no cabeçalho; não é copiado.
 */
internal class CrpFrame(val cmd: Int, val payload: ByteArray) {
	/** Byte sem sinal do payload em [offset], ou `null` se o payload for menor. */
	fun byteAt(offset: Int): Int? = if (offset in payload.indices) payload[offset].toInt() and 0xFF else null

	/** O payload a partir de [offset] (vazio se [offset] passar do fim). */
	fun payloadFrom(offset: Int): ByteArray = if (offset >= payload.size) ByteArray(0) else payload.copyOfRange(offset, payload.size)

	/** `true` se o frame é do [cmd] com o primeiro byte do payload igual a [sub]. */
	fun isCmd(cmd: Int, sub: Int): Boolean = this.cmd == cmd && byteAt(0) == sub

	/** `true` se o frame é um `B6` com os dois primeiros bytes do payload iguais a [kind] e [day]. */
	fun isHistory(kind: Int, day: Int): Boolean = cmd == CrpCommand.HISTORY && byteAt(0) == kind && byteAt(1) == day

	override fun toString(): String = "${CrpCommand.label(cmd, payload)} payload=${Hex.encode(payload)}"
}

/**
 * Versão do protocolo do relógio, que decide o tamanho de bloco e as consultas extras do handshake.
 *
 * - [V1]: bloco de 20 bytes sempre, sem `B7 0E` nem `BB 16 00` (`sdk:e/f.java:16-22`, `sdk:e/f.java:48`).
 * - [V2]: bloco pelo MTU e as duas consultas depois do handshake (`sdk:h/d.java:151-168`). É o do P85 (`MOYOUNG-V2`,
 *   `fixtures/reference/condorreebok/MOY-VS02-2.0.0/condor-app-pair-sync.jsonl`, leitura de 2A29 em `atMs` 1535).
 * - [V3]: como o V2 no Android; o iOS marca o byte 2 com `0x30` (`iOS-bin@0x82764-0x827a8`), o que o driver não faz.
 */
internal enum class CrpProtocolVersion {
	/** Qualquer texto sem `MOYOUNG-V2` nem `MOYOUNG-V3`. */
	V1,

	/** Texto com `MOYOUNG-V2`. */
	V2,

	/** Texto com `MOYOUNG-V3`. */
	V3,
	;

	companion object {
		/**
		 * Versão de um texto de 2A29 (ou da resposta `5A 00`). Tira `NUL` e espaços das pontas e procura `MOYOUNG-V3` e
		 * depois `MOYOUNG-V2` em qualquer posição: é a regra do iOS (`contains`, `iOS-bin@0x82548-0x8262c`), que cobre a do
		 * Android (`equals`, `sdk:j/h0.java:13-20`). Qualquer outro texto é [V1].
		 */
		fun parse(text: String): CrpProtocolVersion {
			val clean = CrpText.clean(text)
			return when {
				clean.contains("MOYOUNG-V3") -> V3
				clean.contains("MOYOUNG-V2") -> V2
				else -> V1
			}
		}
	}
}

/**
 * Codec do frame CRP (`docs/protocolo-crp.md`, seção 3).
 *
 * **Formato.** `FE EA | b2 | len | cmd | payload`. `len` é o total do frame com o cabeçalho (payload + 5), com os 4 bits
 * baixos de `b2` como bits altos (comprimento de 12 bits). `b2` vale `0x10` quando o bloco de escrita é de 20 bytes e
 * `0x20 + (total >> 8)` nos outros casos (`sdk:f/b2.java:9-37`). Não há checksum, sequência nem criptografia.
 *
 * **Bloco.** O frame vai em pedaços crus do tamanho do bloco, sem cabeçalho por pedaço. O bloco é 20 até a versão do
 * protocolo ser V2/V3 com MTU negociado; depois, `min(MTU, 250) − 3` arredondado para baixo a múltiplo de 4
 * (`sdk:l/a.java:51-57`): MTU 23 → 20, 185 → 180, 247/512/517 → 244.
 *
 * **Recepção.** O primeiro pacote de um frame começa com `FE EA`; o nibble alto de `b2` é 1, 2 ou 3 (o iOS usa 3 no V3,
 * `iOS-bin@0x82764-0x827a8`) e o comprimento é lido **sem sinal** (a SDK Android lê o `len` com sinal quando `b2` é
 * `0x10`, `sdk:m/a.java:3736-3738`, e perde frames de 128 bytes ou mais). A remontagem é do [CrpReassembler].
 */
internal object CrpFrameCodec {
	/** `FE EA`, `b2`, `len` e `cmd`. */
	const val HEADER_SIZE: Int = 5

	/** Bloco de escrita padrão (sem MTU negociado, ou V1). */
	const val MIN_BLOCK: Int = 20

	/** Primeiro byte de todo frame. */
	const val MAGIC_0: Int = 0xFE

	/** Segundo byte de todo frame. */
	const val MAGIC_1: Int = 0xEA

	/** Teto do MTU considerado no cálculo do bloco (`sdk:l/a.java:52-54`). */
	const val MAX_MTU_FOR_BLOCK: Int = 250

	/** Maior total que o comprimento de 12 bits representa. */
	const val MAX_TOTAL: Int = 0xFFF

	/**
	 * Tamanho do bloco de escrita: [MIN_BLOCK] no V1; senão `max(20, x − x % 4)`, com `x = min(mtu, 250) − 3`
	 * (`sdk:l/a.java:51-57`).
	 */
	fun blockSize(mtu: Int, version: CrpProtocolVersion): Int {
		if (version == CrpProtocolVersion.V1) return MIN_BLOCK
		val x = minOf(mtu, MAX_MTU_FOR_BLOCK) - 3
		return maxOf(MIN_BLOCK, x - x % 4)
	}

	/**
	 * Monta o frame inteiro de [cmd] com [payload] para um bloco de [blockSize] (`sdk:f/b2.java:9-37`).
	 *
	 * @throws IllegalArgumentException se `cmd` não couber num byte, se o total passar de [MAX_TOTAL] ou se o bloco for
	 * de 20 e o total passar de 255 (o `b2 = 0x10` não tem onde guardar os bits altos do comprimento).
	 */
	fun encode(cmd: Int, payload: ByteArray, blockSize: Int): ByteArray {
		require(cmd in 0..0xFF) { "cmd out of range: $cmd" }
		val total = payload.size + HEADER_SIZE
		require(total <= MAX_TOTAL) { "CRP frame of $total bytes exceeds $MAX_TOTAL" }
		val b2 = if (blockSize == MIN_BLOCK) {
			require(total <= 0xFF) { "CRP frame of $total bytes cannot use a $MIN_BLOCK-byte block (b2=0x10 holds 255 at most)" }
			0x10
		} else {
			0x20 + (total shr 8)
		}
		val frame = ByteArray(total)
		frame[0] = MAGIC_0.toByte()
		frame[1] = MAGIC_1.toByte()
		frame[2] = b2.toByte()
		frame[3] = (total and 0xFF).toByte()
		frame[4] = cmd.toByte()
		payload.copyInto(frame, destinationOffset = HEADER_SIZE)
		return frame
	}

	/** Corta [frame] em pedaços de até [blockSize] bytes, na ordem; cada pedaço é uma escrita GATT. */
	fun fragments(frame: ByteArray, blockSize: Int): List<ByteArray> {
		require(blockSize > 0) { "block size must be positive: $blockSize" }
		if (frame.size <= blockSize) return listOf(frame.copyOf())
		return (frame.indices step blockSize).map { start -> frame.copyOfRange(start, minOf(frame.size, start + blockSize)) }
	}

	/** `true` se [packet] começa com `FE EA`. */
	fun startsWithMagic(packet: ByteArray): Boolean =
		packet.size >= 2 && (packet[0].toInt() and 0xFF) == MAGIC_0 && (packet[1].toInt() and 0xFF) == MAGIC_1

	/**
	 * Total declarado no cabeçalho de [packet], ou `null` se ele não começa um frame: menos de [HEADER_SIZE] bytes, sem
	 * `FE EA`, nibble alto de `b2` fora de 1..3 ou total menor que o cabeçalho. O comprimento é
	 * `((b2 & 0x0F) shl 8) or len`, sem sinal.
	 */
	fun totalLength(packet: ByteArray): Int? {
		if (packet.size < HEADER_SIZE || !startsWithMagic(packet)) return null
		val b2 = packet[2].toInt() and 0xFF
		if ((b2 shr 4) !in 1..3) return null
		val total = ((b2 and 0x0F) shl 8) or (packet[3].toInt() and 0xFF)
		return if (total < HEADER_SIZE) null else total
	}

	/**
	 * Resumo de um pacote cru de `FEE2`/`FEE3` para o log: `"5A/00 PROTOCOL_VERSION len=6 b2=20"`. Um pacote que não
	 * começa um frame (continuação) dá texto vazio. Nunca inclui o payload (os frames de notificação e de clima levam
	 * texto do usuário).
	 */
	fun describe(packet: ByteArray): String {
		val total = totalLength(packet) ?: return ""
		val cmd = packet[4].toInt() and 0xFF
		val payload = packet.copyOfRange(HEADER_SIZE, minOf(packet.size, total))
		val b2 = (packet[2].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
		return "${CrpCommand.label(cmd, payload)} len=$total b2=$b2"
	}
}
