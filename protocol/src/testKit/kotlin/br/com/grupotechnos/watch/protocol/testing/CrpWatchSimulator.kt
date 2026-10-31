package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.crp.CrpCommand
import br.com.grupotechnos.watch.protocol.crp.CrpFrame
import br.com.grupotechnos.watch.protocol.crp.CrpFrameCodec
import br.com.grupotechnos.watch.protocol.crp.CrpGatt
import br.com.grupotechnos.watch.protocol.crp.CrpReassembler
import br.com.grupotechnos.watch.protocol.crp.CrpStepsTotals
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.util.Hex

/**
 * Relógio CRP (Condor Reebok, P85) simulado em nível de byte: recebe os pacotes que o telefone escreve em
 * [onPhoneWrite], remonta os frames e responde por `(cmd, sub)` em [sink] (characteristic, bytes); responde às leituras
 * GATT em [onPhoneRead].
 *
 * As respostas padrão são as do P85 na captura do app de produção
 * (`fixtures/reference/condorreebok/MOY-VS02-2.0.0/condor-app-pair-sync.jsonl`): `5A 00` com `MOYOUNG-V2`, `5A 01` com
 * `MOY-VS02-2.0.0`, páginas `59 00..03` com 24 meias horas zeradas, `32` vazio (sem sono), e silêncio ao `5D`, `5C`,
 * `B6`, `B7 0E`, `BB`, `31` e `F8`. Leituras: 2A29 `MOYOUNG-V2`, 2A28 `MOY-VS02-2.0.0`, 2A19 `0x32` (50 %), FEE1 com 9
 * zeros. O `33` não responde por padrão ([sleepLegacy] `null`), porque o app de produção nunca o mandou ao P85.
 *
 * @param schedule agenda uma ação depois de um tempo (não usado nas respostas padrão, que saem na hora).
 * @param sink entrega uma notificação do relógio (characteristic, bytes).
 */
internal class CrpWatchSimulator(
	private val schedule: (delayMs: Long, action: () -> Unit) -> Unit = { _, action -> action() },
	private val sink: (characteristic: String, value: ByteArray) -> Unit,
) {
	/** Texto de 2A29; `null` = a leitura falha (`NOT_FOUND`). */
	var protocolText: String? = PROTOCOL_TEXT

	/** Texto da resposta `5A 00`. */
	var protocolReplyText: String = PROTOCOL_TEXT

	/** Texto de 2A28; `null` = a leitura falha. */
	var firmwareText: String? = FIRMWARE_TEXT

	/** Texto da resposta `5A 01`. */
	var firmwareReplyText: String = FIRMWARE_TEXT

	/** Páginas do `59` por número de página (00 e 01 hoje; 02 e 03 ontem); página ausente = 24 zeros. */
	var stepsPages: MutableMap<Int, IntArray> = mutableMapOf()

	/** Valor de FEE1 (leitura). */
	var fee1: CrpStepsTotals = CrpStepsTotals(0, 0, 0, null)

	/** Byte de 2A19 (0x32 = 50 %; acima de 100 = carregando). */
	var battery: Int = 0x32

	/** Registros da resposta ao `32` (formato legado, ou novo com [replyB6Instead]); `null` = sem resposta. */
	var sleepToday: ByteArray? = ByteArray(0)

	/**
	 * Respostas ao `33` por sub (03 e 04 sono, formato legado ou novo com [replyB6Instead]); `null` = o relógio não
	 * responde a nenhum `33`. Com mapa, o `33 01` responde [yesterdayTotals] e um sub ausente responde vazio.
	 */
	var sleepLegacy: Map<Int, ByteArray>? = null

	/** Totais de ontem da resposta ao `33 01` (só com [sleepLegacy] diferente de `null`). */
	var yesterdayTotals: CrpStepsTotals = CrpStepsTotals(0, 0, 0, null)

	/** Responde o sono e o histórico com `B6` (formato novo) em vez de `32`/`33`. */
	var replyB6Instead: Boolean = false

	/** `(cmd, sub)` sem resposta; `sub = null` silencia todos os subs do `cmd`. */
	val silent: MutableSet<Pair<Int, Int?>> = mutableSetOf()

	/** Characteristics cuja leitura falha. */
	val unreadable: MutableSet<String> = mutableSetOf()

	/** Byte 2 das respostas (`0x10` ou `0x20`; os bits altos do comprimento entram no nibble baixo). */
	var responseByte2: Int = 0x20

	/** Corta cada resposta em pedaços deste tamanho (`null` = uma notificação por frame). */
	var splitResponsesAt: Int? = null

	/** Frames recebidos do telefone, já remontados, na ordem. */
	val frames: MutableList<CrpFrame> = mutableListOf()

	/** Characteristics lidas pelo telefone, na ordem. */
	val reads: MutableList<String> = mutableListOf()

	/** Pedaços descartados pela remontagem do lado do relógio (deve ficar vazio). */
	val txProblems: MutableList<String> = mutableListOf()

	private val reassembler = CrpReassembler { txProblems += it }

	/** Rótulo `cmd[/sub]` de cada frame recebido (ex.: `"5A/00"`, `"32"`, `"B6/01/00"`). */
	fun labels(): List<String> = frames.map { CrpCommand.label(it.cmd, it.payload).substringBefore(' ') }

	/** Frames recebidos com este [cmd]. */
	fun framesOf(cmd: Int): List<CrpFrame> = frames.filter { it.cmd == cmd }

	/** Entrega um pacote escrito pelo telefone em `fee2`. */
	fun onPhoneWrite(packet: ByteArray) {
		for (frame in reassembler.accept(packet)) {
			frames += frame
			respond(frame)
		}
	}

	/** Responde uma leitura GATT de [characteristic]. */
	fun onPhoneRead(characteristic: String): ReadReply {
		reads += characteristic
		if (characteristic in unreadable) return ReadReply.NOT_FOUND
		return when (characteristic) {
			CrpGatt.PROTOCOL_VERSION -> protocolText?.let { ReadReply.ok(it.encodeToByteArray()) } ?: ReadReply.NOT_FOUND
			CrpGatt.FIRMWARE_REVISION -> firmwareText?.let { ReadReply.ok(it.encodeToByteArray()) } ?: ReadReply.NOT_FOUND
			CrpGatt.BATTERY_LEVEL -> ReadReply.ok(byteArrayOf(battery.toByte()))
			CrpGatt.STEPS_TODAY -> ReadReply.ok(encodeTotals(fee1))
			else -> ReadReply.NOT_FOUND
		}
	}

	/** Notificação de FEE1 com [totals] (passos ao vivo). */
	fun pushSteps(totals: CrpStepsTotals) {
		sink(CrpGatt.STEPS_TODAY, encodeTotals(totals))
	}

	/** Notificação de 2A19 com [raw] (aviso de bateria). */
	fun pushBattery(raw: Int) {
		sink(CrpGatt.BATTERY_LEVEL, byteArrayOf(raw.toByte()))
	}

	/** Frame `F9` + [payload] em `fee3` (estado do Bluetooth clássico). */
	fun pushF9(payload: ByteArray) {
		emitFrame(CrpCommand.CLASSIC_STATE, payload)
	}

	/** Notificação de 2A37 com flags `06` e [bpm]. */
	fun pushHeartRate(bpm: Int) {
		sink(CrpGatt.HEART_RATE, byteArrayOf(0x06, bpm.toByte()))
	}

	/** Um frame qualquer do relógio em `fee3` (ruído, confirmações). */
	fun emitFrame(cmd: Int, payload: ByteArray) {
		val total = payload.size + CrpFrameCodec.HEADER_SIZE
		val frame = ByteArray(total)
		frame[0] = 0xFE.toByte()
		frame[1] = 0xEA.toByte()
		frame[2] = (responseByte2 or (total shr 8)).toByte()
		frame[3] = (total and 0xFF).toByte()
		frame[4] = cmd.toByte()
		payload.copyInto(frame, CrpFrameCodec.HEADER_SIZE)
		val size = splitResponsesAt
		if (size == null || size >= frame.size) {
			sink(Profiles.CRP_FEEA.notify, frame)
		} else {
			for (start in frame.indices step size) sink(Profiles.CRP_FEEA.notify, frame.copyOfRange(start, minOf(frame.size, start + size)))
		}
	}

	private fun respond(frame: CrpFrame) {
		val sub = frame.payload.getOrNull(0)?.let { it.toInt() and 0xFF }
		if ((frame.cmd to null) in silent || (frame.cmd to sub) in silent) return
		when (frame.cmd) {
			CrpCommand.PROTOCOL_INFO -> when (sub) {
				CrpCommand.SUB_PROTOCOL_VERSION -> reply(CrpCommand.PROTOCOL_INFO, byteArrayOf(0x00) + protocolReplyText.encodeToByteArray())
				CrpCommand.SUB_FIRMWARE -> reply(CrpCommand.PROTOCOL_INFO, byteArrayOf(0x01) + firmwareReplyText.encodeToByteArray())
			}
			CrpCommand.STEPS_HALF_HOURS -> if (sub != null) reply(CrpCommand.STEPS_HALF_HOURS, stepsPage(sub))
			CrpCommand.SLEEP_TODAY -> sleepToday?.let { records ->
				if (replyB6Instead) {
					reply(CrpCommand.HISTORY, byteArrayOf(CrpCommand.HISTORY_SLEEP.toByte(), 0x00) + records)
				} else {
					reply(CrpCommand.SLEEP_TODAY, records)
				}
			}
			CrpCommand.LEGACY_HISTORY -> {
				val legacy = sleepLegacy ?: return
				when (sub) {
					CrpCommand.SUB_STEPS_YESTERDAY -> if (replyB6Instead) {
						reply(CrpCommand.HISTORY, byteArrayOf(CrpCommand.HISTORY_STEPS.toByte(), 0x01) + encodeTotalsNew(yesterdayTotals))
					} else {
						reply(CrpCommand.LEGACY_HISTORY, byteArrayOf(0x01) + encodeTotals(yesterdayTotals))
					}
					CrpCommand.SUB_SLEEP_YESTERDAY, CrpCommand.SUB_SLEEP_TWO_DAYS_AGO -> {
						val records = legacy[sub] ?: ByteArray(0)
						if (replyB6Instead) {
							reply(CrpCommand.HISTORY, byteArrayOf(CrpCommand.HISTORY_SLEEP.toByte(), (sub - 2).toByte()) + records)
						} else {
							reply(CrpCommand.LEGACY_HISTORY, byteArrayOf(sub.toByte()) + records)
						}
					}
				}
			}
		}
	}

	private fun reply(cmd: Int, payload: ByteArray) {
		schedule(0) { emitFrame(cmd, payload) }
	}

	private fun stepsPage(page: Int): ByteArray {
		val values = stepsPages[page] ?: IntArray(HALF_HOURS)
		val out = ByteArray(1 + 2 * HALF_HOURS)
		out[0] = page.toByte()
		for (i in 0 until HALF_HOURS) {
			val value = values.getOrElse(i) { 0 }
			out[1 + 2 * i] = value.toByte()
			out[2 + 2 * i] = (value shr 8).toByte()
		}
		return out
	}

	companion object {
		/** Texto de 2A29 e da resposta `5A 00` do P85 da captura. */
		const val PROTOCOL_TEXT: String = "MOYOUNG-V2"

		/** Texto de 2A28 e da resposta `5A 01` do P85 da captura. */
		const val FIRMWARE_TEXT: String = "MOY-VS02-2.0.0"

		/** Meias horas por página do `59`. */
		const val HALF_HOURS: Int = 24

		/** Totais no formato de FEE1 (9 bytes, u24 LE). */
		fun encodeTotals(totals: CrpStepsTotals): ByteArray {
			val out = ByteArray(9)
			for ((index, value) in listOf(totals.steps, totals.distanceMeters, totals.calories).withIndex()) {
				out[index * 3] = value.toByte()
				out[index * 3 + 1] = (value shr 8).toByte()
				out[index * 3 + 2] = (value shr 16).toByte()
			}
			return out
		}

		/** Totais no formato do `B6 00 dd` (passos, kcal × 10000 e cm em u32 LE). */
		fun encodeTotalsNew(totals: CrpStepsTotals): ByteArray {
			val out = ByteArray(12)
			for ((index, value) in listOf(totals.steps.toLong(), totals.calories * 10_000L, totals.distanceMeters * 100L).withIndex()) {
				for (b in 0 until 4) out[index * 4 + b] = (value ushr (8 * b)).toByte()
			}
			return out
		}

		/** `[estado, hora, minuto]` legados de cada tripla. */
		fun legacySleep(vararg records: Triple<Int, Int, Int>): ByteArray =
			records.flatMap { (state, hour, minute) -> listOf(state.toByte(), hour.toByte(), minute.toByte()) }.toByteArray()

		/** Registros no formato novo (u16 LE: estado, hora << 4, minuto << 10). */
		fun newSleep(vararg records: Triple<Int, Int, Int>): ByteArray =
			records.flatMap { (state, hour, minute) ->
				val word = state or (hour shl 4) or (minute shl 10)
				listOf(word.toByte(), (word shr 8).toByte())
			}.toByteArray()

		/** Hex de um pacote, para mensagens. */
		fun hex(bytes: ByteArray): String = Hex.encode(bytes)
	}
}
