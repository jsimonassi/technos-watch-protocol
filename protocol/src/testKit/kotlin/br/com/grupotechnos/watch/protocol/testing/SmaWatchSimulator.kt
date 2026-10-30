package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.sma.codec.SmaDecoded
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrame
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrameCodec
import br.com.grupotechnos.watch.protocol.util.ByteReader

/** O que o relógio simulado faz ao receber um frame do telefone. */
internal data class WatchReply(
	val ack: Boolean = true,
	val responses: List<SmaFrame> = emptyList(),
	val responseBeforeAck: Boolean = false,
	val nack: Boolean = false,
)

/**
 * Relógio SMA simulado em nível de byte, sem dependência de link: recebe cada pacote que o telefone escreve em
 * [onPhoneWrite] e entrega as notificações em [sink].
 *
 * Remonta os frames do telefone, guarda os ACK/NACK recebidos e responde com o handler configurado para cada
 * `cmd/key`; sem handler, responde só ACK. As respostas saem fragmentadas como o firmware faz
 * (`SmaManager.java:506-528`): o L1 de 8 bytes numa notificação e o resto em pedaços de 20.
 *
 * É usado pelo `FakeDriverLink` dos testes do driver e pelo [FakeTransport] dos testes da sessão.
 */
internal class SmaWatchSimulator(private val sink: (ByteArray) -> Unit) {
	/** Frames de dados recebidos do telefone, em ordem. */
	val received = mutableListOf<SmaFrame>()

	/** Seqs que o telefone confirmou com ACK. */
	val phoneAcks = mutableListOf<Int>()

	/** Seqs que o telefone recusou com NACK. */
	val phoneNacks = mutableListOf<Int>()

	/** Gancho chamado para cada pacote cru que chega do telefone, antes da remontagem. */
	var onPacket: ((ByteArray) -> Unit)? = null

	private val handlers = mutableMapOf<Pair<Int, Int>, (SmaFrame) -> WatchReply>()
	private var buffer: ByteArray? = null
	private var sequence = 0x0100

	/** Respostas de um `cmd/key`. */
	fun on(cmd: Int, key: Int, handler: (SmaFrame) -> WatchReply) {
		handlers[cmd to key] = handler
	}

	/** Relógio "feliz": BIND/LOGIN aceitos, bateria [battery], versão `1.2.3`/`4.5.6`. */
	fun installHappyPath(battery: Int = 85) {
		on(0x03, 0x01) { WatchReply(responses = listOf(frame(0x03, 0x02, 0x00))) }
		on(0x03, 0x03) { WatchReply(responses = listOf(frame(0x03, 0x04, 0x00))) }
		on(0x02, 0x08) { WatchReply(responses = listOf(frame(0x02, 0x09, battery))) }
		on(0x02, 0x0A) { WatchReply(responses = listOf(frame(0x02, 0x0B, 1, 2, 3, 4, 5, 6))) }
	}

	/** Frame de dados do relógio com seq próprio. */
	fun frame(cmd: Int, key: Int, vararg value: Int): SmaFrame =
		SmaFrame(nextSequence(), cmd, key, ByteArray(value.size) { value[it].toByte() })

	/** Frame de dados do relógio com value cru. */
	fun frameOf(cmd: Int, key: Int, value: ByteArray): SmaFrame = SmaFrame(nextSequence(), cmd, key, value)

	/** Envia um frame espontâneo (aviso) fragmentado como o firmware. */
	fun send(frame: SmaFrame) {
		for (packet in firmwareFragments(SmaFrameCodec.encode(frame))) sink(packet)
	}

	/** Frames recebidos com este `cmd/key`. */
	fun receivedOf(cmd: Int, key: Int): List<SmaFrame> = received.filter { it.cmd == cmd && it.key == key }

	/** `cmd/key` de cada frame recebido, em ordem (ex.: `"03/01"`). */
	fun receivedLabels(): List<String> = received.map { SmaFrameCodec.cmdKey(it.cmd, it.key) }

	/** Entrega um pacote escrito pelo telefone. */
	fun onPhoneWrite(packet: ByteArray) {
		onPacket?.invoke(packet)
		if (SmaFrameCodec.isControl(packet)) {
			val seq = ByteReader.u16At(packet, 6)
			if ((packet[1].toInt() and SmaFrameCodec.FLAG_ERROR) != 0) phoneNacks += seq else phoneAcks += seq
			return
		}
		val data = buffer?.plus(packet) ?: packet
		val total = SmaFrameCodec.frameLength(data) ?: error("phone sent a fragment that does not start a frame")
		if (data.size < total) {
			buffer = data
			return
		}
		buffer = null
		check(data.size == total) { "phone glued two frames in one write" }
		val decoded = SmaFrameCodec.decode(data)
		check(decoded is SmaDecoded.Data) { "phone sent an invalid frame: $decoded" }
		val frame = decoded.frame
		received += frame
		val reply = handlers[frame.cmd to frame.key]?.invoke(frame) ?: WatchReply()
		val control = if (reply.nack) SmaFrameCodec.nack(frame.sequence) else SmaFrameCodec.ack(frame.sequence)
		if (reply.responseBeforeAck) {
			reply.responses.forEach { send(it) }
			if (reply.ack) sink(control)
		} else {
			if (reply.ack) sink(control)
			reply.responses.forEach { send(it) }
		}
	}

	private fun nextSequence(): Int = sequence++ and 0xFFFF

	companion object {
		/** L1 de 8 bytes numa notificação e o corpo em pedaços de 20, como o firmware. */
		fun firmwareFragments(frame: ByteArray): List<ByteArray> {
			val packets = mutableListOf(frame.copyOfRange(0, SmaFrameCodec.HEADER_SIZE))
			var start = SmaFrameCodec.HEADER_SIZE
			while (start < frame.size) {
				val end = minOf(start + 20, frame.size)
				packets += frame.copyOfRange(start, end)
				start = end
			}
			return packets
		}
	}
}
