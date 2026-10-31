package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.log.FrameJsonl
import br.com.grupotechnos.watch.protocol.util.Hex
import kotlinx.datetime.Instant

/**
 * Cabeçalho de uma gravação `.jsonl` (`docs/fixtures.md`, formato; mesmo conteúdo de `FrameJsonl.header`).
 *
 * @property recordedAt início da gravação, ISO-8601 com offset, com ou sem milissegundos
 * (`2026-10-29T08:14:10.933-03:00`); o replay usa como hora do [TestClock].
 * @property timeZone fuso IANA do telefone durante a gravação.
 */
internal data class RecordingHeader(
	val device: String,
	val firmware: String?,
	val platform: String,
	val mtu: Int,
	val recordedAt: String,
	val timeZone: String,
) {
	val recordedAtEpochMillis: Long
		get() = Instant.parse(recordedAt).toEpochMilliseconds()
}

/** Um pacote GATT cru gravado (um fragmento, não um frame remontado), ou o valor de uma leitura ([FrameDirection.READ]). */
internal class RecordedPacket(
	val direction: FrameDirection,
	val atMs: Long,
	val characteristic: String,
	val value: ByteArray,
) {
	override fun toString(): String = "$direction@$atMs ${Hex.encode(value)}"
}

/** Uma gravação inteira. [source] é o caminho do arquivo, só para mensagens de erro. */
internal class Recording(
	val header: RecordingHeader,
	val packets: List<RecordedPacket>,
	val source: String,
) {
	/** O texto `.jsonl`, gerado com [FrameJsonl] (o mesmo formatador que o host usa em produção). */
	fun toJsonl(): String = buildString {
		append(FrameJsonl.header(header.device, header.firmware, header.platform, header.mtu, header.recordedAt, header.timeZone))
		append('\n')
		for (packet in packets) {
			append(FrameJsonl.line(packet.direction, packet.atMs, packet.characteristic, packet.value))
			append('\n')
		}
	}
}
