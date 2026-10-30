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
 * Os passos imitam o acumulado do dia do fitGPS 0.2.1 (`docs/decisoes.md`, ADR 1): crescem dentro do dia e recomeçam
 * depois da meia-noite, o que é suposição (quando o fitGPS zera ainda não foi visto, T5). Os números exercitam as
 * regras do mapper e da agregação: duas páginas (a primeira com 20 registros, o que força a segunda), horas repetidas em
 * que vale o último registro (13:59:59 e 14:20; o 14:20 muda o total de 28/10, e os dois mudam a soma das horas) e um
 * registro de exercício (mode 32, às 14:25) que fica de fora; se entrasse, seria o último da hora 14 e mudaria o total.
 * Os totais esperados estão em [EXPECTED_STEPS]; a conferência independente está em `docs/fixtures.md`, seção 5.
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

	/**
	 * Acumulado do dia em cada hora cheia da página 1, mode 0: 27/10 de 19h a 23h e 28/10 de 00h a 14h. Supõe-se que o
	 * contador recomece do zero depois da meia-noite (T5).
	 */
	private val PAGE1_STEPS: List<Int> = listOf(
		6_120, 6_480, 6_950, 7_010, 7_040,
		0, 0, 0, 12, 12, 12, 230, 1_180, 2_350, 2_890, 3_020, 3_410, 4_170, 4_400, 4_520,
	)

	/** Passos de cada hora que sobrevivem ao mapper, em ordem: a página 2 troca 13h por 4500 e 14h por 4810. */
	val EXPECTED_HOURLY_STEPS: List<Int> = PAGE1_STEPS.dropLast(2) + listOf(4_500, 4_810)

	/** Total de passos por dia com o perfil do fitGPS (`DAY_CUMULATIVE`): a amostra de mais passos do dia. */
	val EXPECTED_STEPS: Map<String, Int> = mapOf(
		// 19h..23h de 27/10: o acumulado às 23h.
		"2026-10-27" to 7_040,
		// 00h..14h de 28/10: o acumulado às 14:20 (página 2), que substituiu o das 14:00.
		"2026-10-28" to 4_810,
	)

	/**
	 * O que a soma das horas (`HOURLY_SUM`, a regra do Core de produção) daria com as mesmas amostras: o acumulado
	 * contado de novo a cada hora.
	 */
	val EXPECTED_STEPS_IF_HOURLY_SUM: Map<String, Int> = mapOf(
		"2026-10-27" to EXPECTED_HOURLY_STEPS.take(5).sum(),
		"2026-10-28" to EXPECTED_HOURLY_STEPS.drop(5).sum(),
	)

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

	/** 20 registros horários, mode 0: 27/10 19h até 28/10 14h, com os passos de [PAGE1_STEPS]. */
	private fun sportPage1(): ByteArray {
		val writer = ByteWriter()
		val first = SmaLocalTime.toSecondsSince2000(LocalDateTime(2026, 10, 27, 19, 0, 0))
		PAGE1_STEPS.forEachIndexed { index, steps -> writer.u32(first + index * 3_600L).u8(0).u24(steps) }
		return writer.toByteArray()
	}

	/**
	 * 3 registros, do mais antigo para o mais novo dentro da página: 13:59:59 com 4500 (substitui 13h), 14:20 com 4810
	 * (substitui 14h) e o início de um exercício às 14:25 (mode 32, ignorado) com o acumulado de então, 4830.
	 *
	 * Entre as páginas a ordem não é cronológica, de propósito (já era assim antes da regeneração): o 13:59:59
	 * chega depois do 14:00:00 da página 1, ordem que a gravação real não mostrou (o fitGPS 0.2.1 entregou do mais
	 * antigo para o mais novo). Assim uma hora já passada (13h) ainda é trocada por um registro que chega depois de
	 * uma hora mais nova (14h); o último de cada hora continua sendo o maior dela.
	 */
	private fun sportPage2(): ByteArray =
		ByteWriter()
			.u32(SmaLocalTime.toSecondsSince2000(LocalDateTime(2026, 10, 28, 13, 59, 59))).u8(0).u24(4_500)
			.u32(SmaLocalTime.toSecondsSince2000(LocalDateTime(2026, 10, 28, 14, 20, 0))).u8(0).u24(4_810)
			.u32(SmaLocalTime.toSecondsSince2000(LocalDateTime(2026, 10, 28, 14, 25, 0))).u8(32).u24(4_830)
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
