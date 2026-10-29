package br.com.grupotechnos.watch.protocol.sma.codec

import br.com.grupotechnos.watch.protocol.util.ByteReader

/**
 * Remontagem dos frames que o relógio envia em várias notificações (`docs/protocolo-sma.md`, seção 4).
 *
 * Não tem relógio próprio: cada chamada de [accept] recebe o instante atual (`nowMs`, monotônico, em
 * milissegundos), para que os testes controlem o tempo.
 *
 * Regras, na ordem em que são aplicadas a cada notificação:
 * 1. **ACK/NACK**: uma notificação de exatamente 8 bytes com `[0] == 0xAB` e `[1] & 0x10 != 0` é controle,
 *    qualquer que seja o estado do buffer, e nunca entra no corpo de um frame. A SDK Android só reconhece ACK
 *    com o buffer vazio (`SmaManager.java:508-516`); aqui a regra vale sempre, porque um ACK no meio de um
 *    frame em andamento corromperia o corpo e não há frame de dados de 8 bytes (o menor tem 13).
 * 2. **Intervalo**: se há frame em andamento e a última notificação dele chegou há mais de
 *    `fragmentGapTimeoutMs`, o pedaço é descartado ([SmaReassemblyEvent.Dropped] com [DropReason.GAP_TIMEOUT])
 *    e a notificação atual é tratada como início. É o timeout de 8 s entre fragmentos da SDK iOS
 *    (`SmaBLE.m:199-203` e `receiveTimeOut` em `SmaBLE.m:3282-3288`); a Android não tem e trava até reconectar.
 * 3. **`AB` solto**: uma notificação de 1 byte `0xAB` sem frame em andamento é ignorada, como no iOS
 *    (`SmaBLE.m:134-136`). Com frame em andamento ela é corpo, como no Android (`SmaManager.java:519-520`):
 *    o iOS a descartaria mesmo no meio do frame, o que travaria um frame legítimo cujo último byte é `0xAB`;
 *    tratá-la como corpo, no pior caso, gera um CRC errado, que vira NACK e é recuperável.
 * 4. **Início de frame**: bytes antes do primeiro `0xAB` são lixo e são descartados ([DropReason.GARBAGE]).
 *    O esperado é o L1 de 8 bytes numa notificação própria e o corpo nas seguintes (o Android só funciona assim,
 *    `SmaManager.java:506-528`); cabeçalho e corpo na mesma notificação são aceitos como tolerância (o iOS aceita,
 *    `SmaBLE.m:177-194`), assim como mais de um frame na mesma notificação.
 * 5. **Tamanho**: o total vem do L1 (`len(L2) + 8`); acima de [SmaFrameCodec.MAX_FRAME_SIZE] o resto da
 *    notificação é descartado ([DropReason.OVERSIZED]).
 * 6. **Frame completo**: é decodificado por [SmaFrameCodec.decode]. CRC certo vira [SmaReassemblyEvent.Frame];
 *    CRC errado ou tamanhos incoerentes viram [SmaReassemblyEvent.Corrupted] com o seq, para o driver
 *    responder NACK como a SDK iOS faz (`SmaBLE.m:227, 611-620`).
 *
 * Não é thread-safe: o driver chama sempre da mesma corrotina de recepção.
 *
 * @param fragmentGapTimeoutMs maior intervalo aceito entre duas notificações do mesmo frame (`Quirks.fragmentGapTimeoutMs`).
 */
internal class SmaReassembler(private val fragmentGapTimeoutMs: Long) {
	private var buffer: ByteArray? = null
	private var lastPacketAtMs: Long = 0

	/** `true` enquanto há um frame começado e incompleto. */
	val inProgress: Boolean
		get() = buffer != null

	/** Quantos bytes do frame em andamento já chegaram. */
	val bufferedBytes: Int
		get() = buffer?.size ?: 0

	/** Processa uma notificação e devolve o que ela completou, na ordem (normalmente zero ou um evento). */
	fun accept(packet: ByteArray, nowMs: Long): List<SmaReassemblyEvent> {
		val events = ArrayList<SmaReassemblyEvent>(1)
		if (SmaFrameCodec.isControl(packet)) {
			val sequence = ByteReader.u16At(packet, 6)
			val nack = (packet[1].toInt() and SmaFrameCodec.FLAG_ERROR) != 0
			events += SmaReassemblyEvent.Control(sequence, nack)
			return events
		}
		val partial = buffer
		if (partial != null && nowMs - lastPacketAtMs > fragmentGapTimeoutMs) {
			events += SmaReassemblyEvent.Dropped(
				DropReason.GAP_TIMEOUT,
				partial.size,
				"${nowMs - lastPacketAtMs} ms since last fragment, limit $fragmentGapTimeoutMs ms",
			)
			buffer = null
		}
		if (packet.isEmpty()) return events
		val current = buffer
		if (current == null && packet.size == 1 && (packet[0].toInt() and 0xFF) == SmaFrameCodec.MAGIC) {
			events += SmaReassemblyEvent.Dropped(DropReason.STRAY_AB, 1, "lone AB notification")
			return events
		}
		lastPacketAtMs = nowMs
		buffer = null
		process(if (current == null) packet else current + packet, events)
		return events
	}

	/** Descarta o frame em andamento, se houver (ex.: o link caiu). */
	fun reset() {
		buffer = null
	}

	private fun process(input: ByteArray, events: MutableList<SmaReassemblyEvent>) {
		var offset = 0
		while (offset < input.size) {
			if ((input[offset].toInt() and 0xFF) != SmaFrameCodec.MAGIC) {
				val next = indexOfMagic(input, offset)
				events += SmaReassemblyEvent.Dropped(DropReason.GARBAGE, next - offset, "bytes before AB")
				offset = next
				continue
			}
			val available = input.size - offset
			val total = SmaFrameCodec.frameLength(input, offset)
			if (total == null) {
				buffer = input.copyOfRange(offset, input.size)
				return
			}
			if (total > SmaFrameCodec.MAX_FRAME_SIZE) {
				events += SmaReassemblyEvent.Dropped(DropReason.OVERSIZED, available, "declared $total bytes")
				return
			}
			if (available < total) {
				buffer = input.copyOfRange(offset, input.size)
				return
			}
			val raw = input.copyOfRange(offset, offset + total)
			events += when (val decoded = SmaFrameCodec.decode(raw)) {
				is SmaDecoded.Data -> SmaReassemblyEvent.Frame(decoded.frame, raw)
				is SmaDecoded.Ack -> SmaReassemblyEvent.Control(decoded.sequence, nack = false)
				is SmaDecoded.Nack -> SmaReassemblyEvent.Control(decoded.sequence, nack = true)
				is SmaDecoded.Invalid -> SmaReassemblyEvent.Corrupted(decoded.sequence, decoded.error, decoded.detail, raw)
			}
			offset += total
		}
	}

	private fun indexOfMagic(input: ByteArray, from: Int): Int {
		for (index in from until input.size) {
			if ((input[index].toInt() and 0xFF) == SmaFrameCodec.MAGIC) return index
		}
		return input.size
	}
}

/** Saídas do [SmaReassembler]. */
internal sealed interface SmaReassemblyEvent {
	/** ACK (`nack = false`) ou NACK do relógio para o frame [sequence] que enviamos. */
	data class Control(val sequence: Int, val nack: Boolean) : SmaReassemblyEvent

	/** Frame de dados completo e com CRC certo; [raw] são os bytes remontados. */
	class Frame(val frame: SmaFrame, val raw: ByteArray) : SmaReassemblyEvent

	/**
	 * Frame completo mas inválido (CRC errado ou tamanhos incoerentes).
	 *
	 * @property sequence seq do L1, para o NACK; `null` se nem o cabeçalho pôde ser lido.
	 */
	class Corrupted(val sequence: Int?, val error: SmaDecodeError, val detail: String, val raw: ByteArray) : SmaReassemblyEvent

	/** Bytes descartados sem formar frame. */
	data class Dropped(val reason: DropReason, val byteCount: Int, val detail: String) : SmaReassemblyEvent
}

/** Motivos de descarte em [SmaReassemblyEvent.Dropped]. */
internal enum class DropReason {
	/** Bytes antes de um `0xAB` sem frame em andamento. */
	GARBAGE,

	/** Notificação de 1 byte `0xAB` sem frame em andamento. */
	STRAY_AB,

	/** O frame em andamento ficou mais que `fragmentGapTimeoutMs` sem receber fragmento. */
	GAP_TIMEOUT,

	/** O L1 declarou um frame maior que [SmaFrameCodec.MAX_FRAME_SIZE]. */
	OVERSIZED,
}
