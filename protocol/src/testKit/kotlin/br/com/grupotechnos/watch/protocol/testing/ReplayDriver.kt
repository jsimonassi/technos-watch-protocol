package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrameCodec
import br.com.grupotechnos.watch.protocol.transport.GattStatus
import br.com.grupotechnos.watch.protocol.transport.GattUuid
import br.com.grupotechnos.watch.protocol.util.ByteReader
import br.com.grupotechnos.watch.protocol.util.Hex

/**
 * Faz o [FakeTransport] se comportar como o relógio de uma [Recording].
 *
 * Um "pedido" é um TX ou uma leitura (READ). A cada TX da sessão, compara com o próximo pacote da gravação, que tem de
 * ser TX; a cada leitura ([FakeTransport.onRead]), responde com o próximo pacote, que tem de ser um READ da mesma
 * characteristic (senão, divergência e `NOT_FOUND`). Depois de cada pedido, emite na ordem todos os RX gravados até o
 * pedido seguinte, cada um na characteristic gravada ([FakeTransport.notifyOn]). RX anteriores ao primeiro pedido saem
 * quando as notificações são ligadas. Os tempos (`atMs`) são ignorados: as respostas saem na hora, e o tempo virtual do
 * teste é que conta para os timeouts.
 *
 * **Modo estrito** ([strict] = `true`): os bytes precisam bater depois de [TxMasker], que zera só o que muda legitimamente
 * entre a gravação e o replay: o seq (bytes 6..7) de todo frame e, no `02/01` SET_TIME, o value (a hora) e o CRC
 * (bytes 4..5), que cobre o value. Nos outros frames o CRC fica: ele cobre só o L2, que é igual entre gravação e
 * replay, então uma regressão do CRC no encoder aparece contra uma gravação real. Fora do modo estrito, só a ordem
 * TX/RX é conferida.
 *
 * Divergências não lançam exceção (isso viraria erro de protocolo dentro da sessão e esconderia a causa): vão para
 * [mismatches], que o teste confere no fim junto com [remaining].
 *
 * A máscara padrão é a do SMA ([TxMasker]); uma família com outro frame passa a dela em `newMasker` (o iDO usa a
 * `IdoTxMasker`). Duas instâncias são criadas, uma para o gravado e outra para o enviado, porque a máscara guarda estado.
 */
internal class ReplayDriver(
	private val recording: Recording,
	private val transport: FakeTransport,
	private val strict: Boolean = true,
	newMasker: () -> PacketMasker = { TxMasker() },
) {
	private var index = 0
	private val expectedMasker = newMasker()
	private val actualMasker = newMasker()

	/** Divergências encontradas, já descritas para a mensagem do teste. */
	val mismatches = mutableListOf<String>()

	/** TX gravados que a sessão escreveu iguais ao gravado (depois da máscara; fora do modo estrito, só na ordem). */
	var matchedTx: Int = 0
		private set

	/** Total de TX da gravação. */
	val recordedTx: Int = recording.packets.count { it.direction == FrameDirection.TX }

	/** Total de leituras (READ) da gravação. */
	val recordedReads: Int = recording.packets.count { it.direction == FrameDirection.READ }

	/** Leituras da sessão respondidas com o READ gravado da mesma characteristic. */
	var matchedReads: Int = 0
		private set

	/** Pacotes da gravação ainda não consumidos. */
	val remaining: List<RecordedPacket>
		get() = recording.packets.drop(index)

	/** TX da gravação que a sessão ainda não escreveu. */
	val remainingTx: List<RecordedPacket>
		get() = remaining.filter { it.direction == FrameDirection.TX }

	/** Leituras da gravação que a sessão ainda não fez. */
	val remainingReads: List<RecordedPacket>
		get() = remaining.filter { it.direction == FrameDirection.READ }

	init {
		transport.onWrite = ::onWrite
		transport.onRead = { _, characteristic -> onRead(characteristic) }
		transport.afterRead = ::flushAfterRead
		transport.onNotificationsEnabled = ::emitRxUntilNextTx
	}

	private fun onRead(characteristic: String): ReadReply {
		val expected = recording.packets.getOrNull(index)
		val wanted = GattUuid.normalize(characteristic)
		if (expected == null || expected.direction != FrameDirection.READ || GattUuid.normalize(expected.characteristic) != wanted) {
			mismatches += "unexpected READ of $characteristic (next recorded: $expected) in ${recording.source}"
			return ReadReply(null, GattStatus.NOT_FOUND)
		}
		index++
		matchedReads++
		val reply = ReadReply.ok(expected.value)
		// Os RX gravados depois da leitura saem depois da resposta dela, como no rádio.
		pendingAfterRead = true
		return reply
	}

	/** `true` entre uma leitura respondida e a próxima entrega de RX ([flushAfterRead]). */
	private var pendingAfterRead = false

	/**
	 * Emite os RX que a gravação tem depois da última leitura respondida. Chamado pelo [FakeTransport] depois de entregar
	 * o `onReadCompleted`, para a ordem ser a da gravação (resposta da leitura, depois as notificações).
	 */
	fun flushAfterRead() {
		if (!pendingAfterRead) return
		pendingAfterRead = false
		emitRxUntilNextTx()
	}

	private fun onWrite(value: ByteArray) {
		val expected = recording.packets.getOrNull(index)
		if (expected == null || expected.direction != FrameDirection.TX) {
			mismatches += "unexpected TX #${txCount()} ${Hex.encode(value)} (next recorded: $expected) in ${recording.source}"
			return
		}
		index++
		if (strict) {
			val want = expectedMasker.mask(expected.value)
			val got = actualMasker.mask(value)
			if (want.contentEquals(got)) {
				matchedTx++
			} else {
				mismatches += "TX #${txCount()} differs in ${recording.source}\n  recorded ${Hex.encode(expected.value)}\n  sent     ${Hex.encode(value)}"
			}
		} else {
			matchedTx++
		}
		emitRxUntilNextTx()
	}

	private fun emitRxUntilNextTx() {
		while (true) {
			val next = recording.packets.getOrNull(index) ?: return
			if (next.direction != FrameDirection.RX) return
			index++
			transport.notifyOn(next.characteristic, next.value)
		}
	}

	private fun txCount(): Int = recording.packets.take(index).count { it.direction == FrameDirection.TX }

	/** Mensagem pronta para `assertTrue`: vazia quando a gravação foi consumida sem divergência. */
	fun problems(): List<String> {
		val problems = mismatches.toMutableList()
		if (remainingTx.isNotEmpty()) problems += "${remainingTx.size} recorded TX never sent, first: ${remainingTx.first()}"
		if (remainingReads.isNotEmpty()) problems += "${remainingReads.size} recorded READ never done, first: ${remainingReads.first()}"
		return problems
	}
}

/** Zera, num pacote TX, os campos que mudam legitimamente entre a gravação e o replay. Pode guardar estado. */
internal fun interface PacketMasker {
	/** Cópia de [packet] com os campos variáveis zerados. */
	fun mask(packet: ByteArray): ByteArray
}

/**
 * Máscara dos campos que mudam entre gravação e replay num fluxo de pacotes TX SMA.
 *
 * Mantém estado entre pacotes porque um frame pode ocupar várias escritas: o value de SET_TIME pode cair numa
 * continuação quando o fragmento é pequeno. Bytes mascarados viram `00`.
 * - ACK/NACK (8 bytes, flag `0x10`): seq.
 * - Início de frame de dados: seq; se for `02/01`, também o CRC e o value (offset 13 em diante, até o fim do frame).
 */
internal class TxMasker : PacketMasker {
	private var remaining = 0
	private var offset = 0
	private var valueFrom = -1

	override fun mask(packet: ByteArray): ByteArray {
		val out = packet.copyOf()
		if (remaining > 0) {
			for (i in out.indices) if (valueFrom >= 0 && offset + i >= valueFrom) out[i] = 0
			offset += out.size
			remaining -= out.size
			if (remaining <= 0) reset()
			return out
		}
		if (out.size < SmaFrameCodec.HEADER_SIZE || ByteReader.u8At(out, 0) != SmaFrameCodec.MAGIC) return out
		if (SmaFrameCodec.isControl(out)) {
			out[6] = 0
			out[7] = 0
			return out
		}
		out[6] = 0
		out[7] = 0
		val total = SmaFrameCodec.HEADER_SIZE + ByteReader.u16At(out, 2)
		val isSetTime = out.size > 10 && ByteReader.u8At(out, 8) == 0x02 && ByteReader.u8At(out, 10) == 0x01
		if (isSetTime) {
			out[4] = 0
			out[5] = 0
		}
		valueFrom = if (isSetTime) SET_TIME_VALUE_OFFSET else -1
		for (i in out.indices) if (valueFrom >= 0 && i >= valueFrom) out[i] = 0
		offset = out.size
		remaining = total - out.size
		if (remaining <= 0) reset()
		return out
	}

	private fun reset() {
		remaining = 0
		offset = 0
		valueFrom = -1
	}

	private companion object {
		/** L1 (8) + cmd, `00`, key, len (5). */
		const val SET_TIME_VALUE_OFFSET = 13
	}
}
