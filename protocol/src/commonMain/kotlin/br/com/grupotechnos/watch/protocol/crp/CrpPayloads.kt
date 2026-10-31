package br.com.grupotechnos.watch.protocol.crp

import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Um pedido CRP pronto para o [CrpFrameCodec]: `cmd` e payload (`docs/protocolo-crp.md`, seção 6).
 *
 * @property cmd byte de comando.
 * @property payload bytes depois do `cmd`.
 */
internal class CrpRequest(val cmd: Int, val payload: ByteArray) {
	companion object {
		/** `5A 00`: versão do protocolo (`sdk:f/a0.java:21-23`). */
		fun protocolVersion(): CrpRequest = CrpRequest(CrpCommand.PROTOCOL_INFO, byteArrayOf(CrpCommand.SUB_PROTOCOL_VERSION.toByte()))

		/** `5A 01`: versão do firmware (`sdk:f/a0.java:17-19`). */
		fun firmware(): CrpRequest = CrpRequest(CrpCommand.PROTOCOL_INFO, byteArrayOf(CrpCommand.SUB_FIRMWARE.toByte()))

		/** `B7 0E`: consulta de SPP (`sdk:f/a0.java:64-68`). */
		fun sppQuery(): CrpRequest = CrpRequest(CrpCommand.SPP_QUERY, byteArrayOf(0x0E))

		/** `BB 16 00`: contagem de emojis (`sdk:f/d2.java:11-17`). */
		fun emojiCount(): CrpRequest = CrpRequest(CrpCommand.SETTINGS, byteArrayOf(CrpCommand.SUB_EMOJI_COUNT.toByte(), 0x00))

		/** `31` + hora ([CrpTimeCodec]). */
		fun setTime(nowEpochMillis: Long, timeZoneId: String): CrpRequest =
			CrpRequest(CrpCommand.SET_TIME, CrpTimeCodec.encode(nowEpochMillis, timeZoneId))

		/** `BB 07 00` + fuso ([CrpTimeZoneCodec]). */
		fun timeZone(offsetSeconds: Int): CrpRequest = CrpRequest(CrpCommand.SETTINGS, CrpTimeZoneCodec.encode(offsetSeconds))

		/** `59 pp`: página [page] dos passos por meia hora (00 e 01 hoje; 02 e 03 ontem). */
		fun stepsPage(page: Int): CrpRequest = CrpRequest(CrpCommand.STEPS_HALF_HOURS, byteArrayOf(page.toByte()))

		/** `5D pp`: distância por meia hora (só catálogo; o P85 não responde). */
		fun distancePage(page: Int): CrpRequest = CrpRequest(CrpCommand.DISTANCE_HALF_HOURS, byteArrayOf(page.toByte()))

		/** `5C pp`: calorias por meia hora (só catálogo; o P85 não responde). */
		fun caloriesPage(page: Int): CrpRequest = CrpRequest(CrpCommand.CALORIES_HALF_HOURS, byteArrayOf(page.toByte()))

		/** `33 ss`: histórico legado ([CrpCommand.LEGACY_HISTORY]). */
		fun legacyHistory(sub: Int): CrpRequest = CrpRequest(CrpCommand.LEGACY_HISTORY, byteArrayOf(sub.toByte()))

		/** `32`: sono de hoje, sem payload. */
		fun sleepToday(): CrpRequest = CrpRequest(CrpCommand.SLEEP_TODAY, ByteArray(0))

		/** `F8`: remove o vínculo no relógio, sem payload. */
		fun removeBond(): CrpRequest = CrpRequest(CrpCommand.REMOVE_BOND, ByteArray(0))

		/** `51 02`: reset de fábrica (fase 2; não enviado). */
		fun factoryReset(): CrpRequest = CrpRequest(CrpCommand.DEVICE_CONTROL, byteArrayOf(0x02))

		/** `17 01` (24 h) ou `17 00` (12 h) (fase 2; não enviado). */
		fun timeFormat(twentyFourHours: Boolean): CrpRequest =
			CrpRequest(CrpCommand.TIME_FORMAT, byteArrayOf(if (twentyFourHours) 0x01 else 0x00))
	}
}

/** Texto ASCII do relógio (2A29, 2A28, respostas `5A 00` e `5A 01`). */
internal object CrpText {
	/** Bytes como texto ISO-8859-1 (um caractere por byte, nunca falha), sem `NUL` e espaços nas pontas. */
	fun decode(bytes: ByteArray): String = clean(CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString())

	/** [text] sem `NUL` e espaços nas pontas. */
	fun clean(text: String): String = text.trim { it == '\u0000' || it.isWhitespace() }
}

/**
 * Hora do `31` (`sdk:f/v1.java:18-45`; `docs/protocolo-crp.md`, seção 7): o relógio de parede do telefone, truncado no
 * segundo, relido como se fosse GMT+8, em segundos u32 big-endian, mais o byte fixo `08`. O resultado não depende do
 * fuso do telefone: o fuso vai à parte no `BB 07 00` ([CrpTimeZoneCodec]). Vetor da captura: 15:43:19 local →
 * `6A E5 9C 17 08` (`fixtures/reference/condorreebok/MOY-VS02-2.0.0/condor-app-pair-sync.jsonl`).
 */
internal object CrpTimeCodec {
	/** O byte fixo depois dos segundos (GMT+8). */
	const val GMT_PLUS_8: Int = 0x08

	private val GMT8 = UtcOffset(hours = 8).asTimeZone()

	/** Payload do `31` para o instante [nowEpochMillis] no fuso [timeZoneId]. */
	fun encode(nowEpochMillis: Long, timeZoneId: String): ByteArray {
		val local = TimeFormat.toLocalWallClock(nowEpochMillis, timeZoneId)
		val truncated = LocalDateTime(local.year, local.monthNumber, local.dayOfMonth, local.hour, local.minute, local.second)
		val seconds = truncated.toInstant(GMT8).epochSeconds
		require(seconds in 0..0xFFFF_FFFFL) { "time $truncated does not fit in u32 seconds" }
		return byteArrayOf(
			(seconds ushr 24).toByte(),
			(seconds ushr 16).toByte(),
			(seconds ushr 8).toByte(),
			seconds.toByte(),
			GMT_PLUS_8.toByte(),
		)
	}

	/** Relógio de parede que um payload do `31` representa (o inverso de [encode]). */
	fun decodeWallClock(payload: ByteArray): LocalDateTime {
		require(payload.size >= 4) { "SET_TIME payload too short: ${payload.size}" }
		val seconds = ((payload[0].toLong() and 0xFF) shl 24) or ((payload[1].toLong() and 0xFF) shl 16) or
			((payload[2].toLong() and 0xFF) shl 8) or (payload[3].toLong() and 0xFF)
		return Instant.fromEpochSeconds(seconds).toLocalDateTime(GMT8)
	}
}

/**
 * Fuso do `BB 07 00` (`sdk:f/v1.java:74-85`): o offset do telefone em segundos, com horário de verão, em int32
 * little-endian. Vetor da captura: −10800 s → `D0 D5 FF FF`.
 */
internal object CrpTimeZoneCodec {
	/** Offset de [timeZoneId] no instante [nowEpochMillis], em segundos. */
	fun offsetSeconds(nowEpochMillis: Long, timeZoneId: String): Int =
		TimeZone.of(timeZoneId).offsetAt(Instant.fromEpochMilliseconds(nowEpochMillis)).totalSeconds

	/** Payload completo do `BB`: `07 00` + [offsetSeconds] em int32 LE. */
	fun encode(offsetSeconds: Int): ByteArray = byteArrayOf(
		CrpCommand.SUB_TIME_ZONE.toByte(),
		0x00,
		offsetSeconds.toByte(),
		(offsetSeconds shr 8).toByte(),
		(offsetSeconds shr 16).toByte(),
		(offsetSeconds shr 24).toByte(),
	)

	/** Offset em segundos de um payload `07 00 xx xx xx xx`. */
	fun decode(payload: ByteArray): Int {
		require(payload.size >= 6) { "TIME_ZONE payload too short: ${payload.size}" }
		return (payload[2].toInt() and 0xFF) or ((payload[3].toInt() and 0xFF) shl 8) or
			((payload[4].toInt() and 0xFF) shl 16) or (payload[5].toInt() shl 24)
	}
}

/**
 * Totais de passos de um dia: o valor de FEE1 (hoje) e a resposta `33 01` (ontem, sem o primeiro byte).
 *
 * Formato (`sdk:j/p0.java:12-35`): tamanho múltiplo de 3 e pelo menos 9 bytes; `[passos u24 LE][distância u24 LE, m]
 * [kcal u24 LE]` e, com 12 bytes, `[tempo u24 LE]`. Um campo com os três bytes em `FF` vale 0 (`sdk:g1/d.java:127-141`).
 * Vetores: a fixture `condor-app-reconnect-sync-walk.jsonl` traz `10 00 00 0D 00 00 01 00 00` (16 passos, 13 m, 1 kcal)
 * no começo da caminhada e `4D 00 00 3F 00 00 04 00 00` (77, 63, 4) no fim.
 *
 * @property steps passos do dia.
 * @property distanceMeters distância do dia em metros.
 * @property calories calorias do dia em kcal.
 * @property activeTime quarto campo (tempo), quando o relógio manda 12 bytes; a unidade não é documentada.
 */
internal data class CrpStepsTotals(val steps: Int, val distanceMeters: Int, val calories: Int, val activeTime: Int?) {
	/** `true` quando passos, distância e calorias são 0. */
	val isZero: Boolean
		get() = steps == 0 && distanceMeters == 0 && calories == 0

	companion object {
		/** Lê o formato de FEE1/`33`; `null` com tamanho que não é múltiplo de 3 ou menor que 9. */
		fun decode(bytes: ByteArray): CrpStepsTotals? {
			if (bytes.size < 9 || bytes.size % 3 != 0) return null
			return CrpStepsTotals(
				steps = u24leOrZero(bytes, 0),
				distanceMeters = u24leOrZero(bytes, 3),
				calories = u24leOrZero(bytes, 6),
				activeTime = if (bytes.size > 9) u24leOrZero(bytes, 9) else null,
			)
		}

		/**
		 * Lê o formato novo do `B6 00 dd` (sem os dois primeiros bytes): `[passos u32][kcal × 10000 u32][cm u32]` (+ tempo),
		 * tamanho múltiplo de 4 e pelo menos 12. Como a SDK, só os 24 bits baixos de cada campo são lidos
		 * (`sdk:j/p0.java:37-60`). O P85 não responde ao `B6`; só tolerado como resposta ao `33 01`.
		 */
		fun decodeHistoryNew(bytes: ByteArray): CrpStepsTotals? {
			if (bytes.size < 12 || bytes.size % 4 != 0) return null
			return CrpStepsTotals(
				steps = u24leOrZero(bytes, 0),
				distanceMeters = u24leOrZero(bytes, 8) / 100,
				calories = u24leOrZero(bytes, 4) / 10_000,
				activeTime = if (bytes.size > 12) u24leOrZero(bytes, 12) else null,
			)
		}

		private fun u24leOrZero(bytes: ByteArray, offset: Int): Int {
			val b0 = bytes[offset].toInt() and 0xFF
			val b1 = bytes[offset + 1].toInt() and 0xFF
			val b2 = bytes[offset + 2].toInt() and 0xFF
			if (b0 == 0xFF && b1 == 0xFF && b2 == 0xFF) return 0
			return b0 or (b1 shl 8) or (b2 shl 16)
		}
	}
}

/**
 * Leitura (ou notificação) de 2A19: um byte. Acima de 100 quer dizer carregando, com nível = valor − 100: a regra é do
 * Core de produção (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/CRPService.java:1814-1822`), não da SDK, e
 * ainda não foi vista no P85 (a captura só tem `0x32` e `0x31`).
 *
 * @property raw o byte como veio.
 * @property percent o nível, já limitado a 0..100.
 * @property charging `true` quando [raw] passa de 100.
 */
internal data class CrpBatteryReading(val raw: Int, val percent: Int, val charging: Boolean) {
	/** `true` quando o nível calculado passou de 100 e foi limitado. */
	val clamped: Boolean
		get() = (if (charging) raw - CHARGING_OFFSET else raw) > 100

	companion object {
		/** Valor somado ao nível quando o relógio está carregando. */
		const val CHARGING_OFFSET: Int = 100

		/** Lê o primeiro byte; `null` sem nenhum byte. */
		fun decode(bytes: ByteArray): CrpBatteryReading? {
			if (bytes.isEmpty()) return null
			val raw = bytes[0].toInt() and 0xFF
			val charging = raw > CHARGING_OFFSET
			val level = if (charging) raw - CHARGING_OFFSET else raw
			return CrpBatteryReading(raw, level.coerceIn(0, 100), charging)
		}
	}
}

/**
 * Página de uma categoria por meia hora (`59`, `5D`, `5C`): `[página] + valores` (`sdk:j/q0.java:78-108`;
 * `sdk:j/m.java:78-90`; `sdk:j/j.java:78-90`).
 *
 * @property page o primeiro byte do payload (00 e 01 hoje; 02 e 03 ontem).
 * @property values um valor por meia hora, na ordem.
 */
internal class CrpCategoryPage(val page: Int, val values: LongArray) {
	companion object {
		/** Meias horas por página no P85 (a resposta tem `[página] + 48 bytes`). */
		const val HALF_HOURS_PER_PAGE: Int = 24

		/** Página do `59`: 24 × u16 LE no P85; `null` sem página ou com um número ímpar de bytes de valores. */
		fun decodeSteps(payload: ByteArray): CrpCategoryPage? {
			if (payload.isEmpty() || (payload.size - 1) % 2 != 0) return null
			val count = (payload.size - 1) / 2
			return CrpCategoryPage(
				payload[0].toInt() and 0xFF,
				LongArray(count) { i -> ((payload[1 + 2 * i].toInt() and 0xFF) or ((payload[2 + 2 * i].toInt() and 0xFF) shl 8)).toLong() },
			)
		}

		/**
		 * Página do `5D` (centímetros) ou do `5C` (kcal × 10000): u32 LE por meia hora. Só codec: o P85 não responde a
		 * nenhum dos dois na captura.
		 */
		fun decodeU32(payload: ByteArray): CrpCategoryPage? {
			if (payload.isEmpty() || (payload.size - 1) % 4 != 0) return null
			val count = (payload.size - 1) / 4
			return CrpCategoryPage(
				payload[0].toInt() and 0xFF,
				LongArray(count) { i ->
					var value = 0L
					for (b in 3 downTo 0) value = (value shl 8) or (payload[1 + 4 * i + b].toLong() and 0xFF)
					value
				},
			)
		}
	}
}

/**
 * Um registro de sono: o estado começa em `hora:minuto` (relógio de parede do relógio).
 *
 * @property state 0 acordado, 1 leve, 2 profundo, 3 REM (`sdk:j/o0.java:59-72`).
 * @property hour hora como veio (no formato novo, até 63).
 * @property minute minuto como veio.
 */
internal data class CrpSleepRecord(val state: Int, val hour: Int, val minute: Int)

/** Decodificação dos dois formatos de registros de sono (`docs/protocolo-crp.md`, seção 9). */
internal object CrpSleepPayload {
	/**
	 * Formato legado (`32` e `33 03`/`33 04` sem o primeiro byte): `[estado, hora, minuto] × N`; `null` quando o tamanho
	 * não é múltiplo de 3 (`sdk:j/o0.java:27`). Vazio = sem sono. Lê os bytes sem sinal (a SDK lê com sinal,
	 * `sdk:j/o0.java:37-38`).
	 */
	fun decodeLegacy(bytes: ByteArray): List<CrpSleepRecord>? {
		if (bytes.size % 3 != 0) return null
		return (bytes.indices step 3).map { i ->
			CrpSleepRecord(bytes[i].toInt() and 0xFF, bytes[i + 1].toInt() and 0xFF, bytes[i + 2].toInt() and 0xFF)
		}
	}

	/**
	 * Formato novo (`B6 01 dd` sem os dois primeiros bytes): u16 LE × N, com estado nos bits 0-3, hora nos bits 4-9 e
	 * minuto nos bits 10-15 (`sdk:j/o0.java:98-133`); `null` quando o tamanho é ímpar.
	 */
	fun decodeNew(bytes: ByteArray): List<CrpSleepRecord>? {
		if (bytes.size % 2 != 0) return null
		return (bytes.indices step 2).map { i ->
			val word = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
			CrpSleepRecord(word and 0x0F, (word and 0x3F0) shr 4, (word and 0xFC00) shr 10)
		}
	}
}

/**
 * Notificação `F9` (só RX), que a SDK 2.0.3 não trata. O significado vem da captura do app de produção: `F9 01 01 xx`
 * acompanha o Bluetooth clássico (00 desconectado, 01 conectado: muda de 00 para 01 quando o vínculo BR/EDR fecha) e
 * `F9 02 01`/`F9 02 00` chegam periodicamente (o 00 veio antes de uma queda). Só vai para o log.
 */
internal object CrpClassicState {
	/** `"classic=01"`, `"sub=02 value=01"` ou `"raw=<n> bytes"` para outro formato. */
	fun describe(payload: ByteArray): String {
		val b0 = payload.getOrNull(0)?.toInt()?.and(0xFF)
		return when {
			b0 == 0x01 && payload.size >= 3 -> "classic=" + hex2(payload[2].toInt() and 0xFF)
			b0 == 0x02 && payload.size >= 2 -> "sub=02 value=" + hex2(payload[1].toInt() and 0xFF)
			else -> "raw=${payload.size} bytes"
		}
	}

	/** `true`/`false` para `F9 01 01 01`/`F9 01 01 00`; `null` para outro formato. */
	fun classicConnected(payload: ByteArray): Boolean? {
		if (payload.size < 3 || (payload[0].toInt() and 0xFF) != 0x01) return null
		return (payload[2].toInt() and 0xFF) == 0x01
	}

	private fun hex2(value: Int): String = value.toString(16).uppercase().padStart(2, '0')
}
