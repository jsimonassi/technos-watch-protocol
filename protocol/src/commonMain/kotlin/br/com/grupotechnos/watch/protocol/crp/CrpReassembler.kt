package br.com.grupotechnos.watch.protocol.crp

/**
 * Remonta as notificações de `FEE3` em frames CRP (`docs/protocolo-crp.md`, seção 3).
 *
 * Regras (as diferenças em relação à SDK estão no fim):
 * - Um pacote que começa com `FE EA` e tem cabeçalho válido ([CrpFrameCodec.totalLength]) começa um frame novo. Se havia
 *   um frame pela metade, ele é descartado com aviso (ressincronização). Se o pacote já tem o frame inteiro, o frame
 *   sai na hora e o que sobra depois do comprimento declarado é descartado com aviso.
 * - Um pacote que não começa um frame é continuação: com um frame pela metade, é concatenado até completar o total (o
 *   excesso é descartado com aviso); sem frame pela metade, é descartado com aviso.
 * - [reset] descarta o frame pela metade. O driver chama quando passa `Quirks.fragmentGapTimeoutMs` sem o pacote
 *   seguinte, e cria um remontador novo a cada conexão.
 *
 * Diferenças em relação à SDK Android (`sdk:m/a.java:3700-3738`), todas de propósito: lá o comprimento é lido com sinal
 * quando `b2` é `0x10` (frames de 128 bytes ou mais lançam exceção), o `FE EA` só é conferido com o remontador ocioso
 * (sem ressincronização), não há timeout e o estado não é zerado entre conexões (o objeto vive entre elas,
 * `sdk:h/d.java:39-41`).
 *
 * Não é thread-safe: o driver chama sempre da mesma corrotina de recepção.
 *
 * @param onDrop recebe a descrição de cada pedaço descartado, para o log (WARN no driver).
 */
internal class CrpReassembler(private val onDrop: (String) -> Unit) {
	private var buffer: ByteArray? = null
	private var expected = 0

	/** `true` quando há um frame pela metade esperando continuação. */
	val hasPartial: Boolean
		get() = buffer != null

	/** Entrega um pacote notificado; devolve os frames que ele completou (zero ou um). */
	fun accept(packet: ByteArray): List<CrpFrame> {
		val total = CrpFrameCodec.totalLength(packet)
		if (total != null) {
			val partial = buffer
			if (partial != null) {
				onDrop("discarded a partial frame of ${partial.size}/$expected bytes: a new frame started (resync)")
				reset()
			}
			if (packet.size >= total) {
				if (packet.size > total) onDrop("discarded ${packet.size - total} bytes after a complete ${packet.size}-byte packet")
				return listOf(frameOf(packet, total))
			}
			buffer = packet.copyOf()
			expected = total
			return emptyList()
		}
		val partial = buffer
		if (partial == null) {
			onDrop("discarded a ${packet.size}-byte continuation without a frame start")
			return emptyList()
		}
		val joined = partial + packet
		if (joined.size < expected) {
			buffer = joined
			return emptyList()
		}
		if (joined.size > expected) onDrop("discarded ${joined.size - expected} bytes past the declared length $expected")
		val total2 = expected
		reset()
		return listOf(frameOf(joined, total2))
	}

	/** Descarta o frame pela metade, se houver. */
	fun reset() {
		buffer = null
		expected = 0
	}

	private fun frameOf(bytes: ByteArray, total: Int): CrpFrame =
		CrpFrame(bytes[4].toInt() and 0xFF, bytes.copyOfRange(CrpFrameCodec.HEADER_SIZE, total))
}
