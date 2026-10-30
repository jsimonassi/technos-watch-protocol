package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.profile.BindPayloadKind
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFragmenter
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrameCodec
import br.com.grupotechnos.watch.protocol.sma.commands.SmaBindPayload
import br.com.grupotechnos.watch.protocol.sma.commands.SmaTimeCodec
import br.com.grupotechnos.watch.protocol.sma.data.SmaLocalTime
import br.com.grupotechnos.watch.protocol.sma.data.SmaSleepMode
import br.com.grupotechnos.watch.protocol.util.ByteWriter
import kotlinx.datetime.LocalDateTime

/**
 * Gerador da gravação sintética `fixtures/synthetic/fitgps-pair-sync.jsonl` (procedência em `docs/fixtures.md`).
 *
 * Monta, frame a frame com o codec SMA (não rodando a sessão), a conversa que se espera entre esta biblioteca e um
 * fitGPS num pareamento seguido de acerto de hora, bateria, versão e sync de passos e sono:
 * - TX: cada frame do telefone numa escrita só (MTU 247 → fragmento de 244, `SmaFragmenter`), seq de 1 em diante;
 *   o ACK de cada frame de dados recebido logo depois dele.
 * - RX: o ACK do relógio para cada pedido e, nos GETs, a resposta com o L1 de 8 bytes numa notificação própria e o
 *   corpo em pedaços de 20 (`SmaManager.java:506-528`), seq do relógio a partir de `0x0100`.
 *
 * Os números de passos foram escolhidos para exercitar as regras do mapper: duas páginas (a primeira com 20
 * registros, o que força a segunda), horas repetidas em que vale o último registro e um registro de exercício
 * (mode 32) que fica de fora. Os totais esperados estão em [EXPECTED_STEPS] e são conferidos à mão no teste.
 */
internal object SyntheticFitGpsFixture {
	const val RELATIVE_PATH: String = "synthetic/fitgps-pair-sync.jsonl"

	val header = RecordingHeader(
		device = "Mormaii_fitGPS",
		firmware = "1.2.3",
		platform = "synthetic",
		mtu = 247,
		recordedAt = "2026-10-28T14:30:00-03:00",
		timeZone = "America/Sao_Paulo",
	)

	/** Total de passos por dia que a gravação deve produzir (ver KDoc do objeto). */
	val EXPECTED_STEPS: Map<String, Int> = mapOf(
		// 19h..23h de 27/10: 100 + 110 + 120 + 130 + 140.
		"2026-10-27" to 600,
		// 00h..14h de 28/10: 150..290 (soma 3300), com 13h trocada por 500 e 14h trocada por 777 na página 2.
		"2026-10-28" to 3300 - 280 - 290 + 500 + 777,
	)

	/** Passos de cada hora que sobrevivem ao mapper, para conferir calorias e distância amostra a amostra. */
	val EXPECTED_HOURLY_STEPS: List<Int> = (0..19).map { 100 + 10 * it }.let { it.dropLast(2) + listOf(500, 777) }

	private val write = Profiles.SMA_NUS.write
	private val notify = Profiles.SMA_NUS.notify

	fun build(): Recording {
		val conversation = Conversation()
		val bind = SmaBindPayload.encode(BindPayloadKind.ANDROID_FF, 1)
		conversation.request(0x03, 0x01, bind, response = 0x03 to byteArrayOf(0x00), watchDelayMs = 1_500)
		conversation.request(0x03, 0x03, bind, response = 0x03 to byteArrayOf(0x00))
		// Acerto de hora automático da sessão (postLoginDelayMs = 3 s) e depois o pedido explícito do host.
		conversation.pause(3_000)
		conversation.request(0x02, 0x01, SmaTimeCodec.encode(LocalDateTime(2026, 10, 28, 14, 30, 3)))
		conversation.request(0x02, 0x01, SmaTimeCodec.encode(LocalDateTime(2026, 10, 28, 14, 30, 4)))
		conversation.request(0x02, 0x08, ByteArray(0), response = 0x02 to byteArrayOf(85))
		conversation.request(0x02, 0x0A, ByteArray(0), response = 0x02 to byteArrayOf(1, 2, 3, 4, 5, 6))
		conversation.request(0x05, 0x41, ByteArray(0), response = 0x05 to sportPage1())
		conversation.request(0x05, 0x41, ByteArray(0), response = 0x05 to sportPage2())
		conversation.request(0x05, 0x45, ByteArray(0), response = 0x05 to sleepPage())
		return Recording(header, conversation.packets, "generated")
	}

	/** 20 registros horários, mode 0: 27/10 19h até 28/10 14h, passos 100, 110, …, 290. */
	private fun sportPage1(): ByteArray {
		val writer = ByteWriter()
		val first = SmaLocalTime.toSecondsSince2000(LocalDateTime(2026, 10, 27, 19, 0, 0))
		repeat(20) { index -> writer.u32(first + index * 3_600L).u8(0).u24(100 + 10 * index) }
		return writer.toByteArray()
	}

	/** 3 registros: 14:20 (substitui 14h), exercício às 10:15 (ignorado) e 13:59:59 (substitui 13h). */
	private fun sportPage2(): ByteArray =
		ByteWriter()
			.u32(SmaLocalTime.toSecondsSince2000(LocalDateTime(2026, 10, 28, 14, 20, 0))).u8(0).u24(777)
			.u32(SmaLocalTime.toSecondsSince2000(LocalDateTime(2026, 10, 28, 10, 15, 0))).u8(32).u24(5_000)
			.u32(SmaLocalTime.toSecondsSince2000(LocalDateTime(2026, 10, 28, 13, 59, 59))).u8(0).u24(500)
			.toByteArray()

	/** Uma noite: dormiu 23:00, leve, profundo à 01:00, leve às 03:00, acordou 06:30. */
	private fun sleepPage(): ByteArray {
		val writer = ByteWriter()
		listOf(
			LocalDateTime(2026, 10, 27, 23, 0, 0) to SmaSleepMode.START,
			LocalDateTime(2026, 10, 27, 23, 30, 0) to SmaSleepMode.LIGHT,
			LocalDateTime(2026, 10, 28, 1, 0, 0) to SmaSleepMode.DEEP,
			LocalDateTime(2026, 10, 28, 3, 0, 0) to SmaSleepMode.LIGHT,
			LocalDateTime(2026, 10, 28, 6, 30, 0) to SmaSleepMode.END,
		).forEach { (local, mode) -> writer.u32(SmaLocalTime.toSecondsSince2000(local)).u8(mode).u8(0).u8(0) }
		return writer.toByteArray()
	}

	/** Acumula os pacotes com tempos crescentes (cosméticos: o replay não usa `atMs`). */
	private class Conversation {
		val packets = mutableListOf<RecordedPacket>()
		private var phoneSeq = 1
		private var watchSeq = 0x0100
		private var atMs = 0L

		fun pause(ms: Long) {
			atMs += ms
		}

		fun request(cmd: Int, key: Int, value: ByteArray, response: Pair<Int, ByteArray>? = null, watchDelayMs: Long = 0) {
			val seq = phoneSeq++
			for (fragment in SmaFragmenter.split(SmaFrameCodec.encode(seq, cmd, key, value), 244)) tx(fragment)
			rx(SmaFrameCodec.ack(seq))
			if (response == null) return
			atMs += watchDelayMs
			val (responseCmd, responseValue) = response
			val frame = SmaFrameCodec.encode(watchSeq, responseCmd, key + 1, responseValue)
			for (packet in SmaWatchSimulator.firmwareFragments(frame)) rx(packet)
			tx(SmaFrameCodec.ack(watchSeq))
			watchSeq++
		}

		private fun tx(bytes: ByteArray) = add(FrameDirection.TX, write, bytes)

		private fun rx(bytes: ByteArray) = add(FrameDirection.RX, notify, bytes)

		private fun add(direction: FrameDirection, characteristic: String, bytes: ByteArray) {
			atMs += 20
			packets += RecordedPacket(direction, atMs, characteristic, bytes)
		}
	}
}
