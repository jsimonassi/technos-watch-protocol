package br.com.grupotechnos.watch.protocol.util

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

/**
 * Conversões de data e hora usadas no domínio e nos drivers.
 *
 * A API pública do módulo não expõe `kotlinx-datetime` (Swift não consome bem esses tipos); ela trabalha com
 * epoch millis e strings ISO. Este objeto é o único lugar que faz a ponte, para que todo o módulo formate
 * datas do mesmo jeito.
 *
 * Formatos produzidos:
 * - ISO com offset: `yyyy-MM-dd'T'HH:mm:ssXXX`, sempre com segundos e sempre com offset numérico, inclusive
 *   em UTC (`"+00:00"`, nunca `"Z"`). É o formato que o Core atual entrega ao app em `onHistoryPedometer.date`
 *   (`yyyy-MM-dd'T'HH:mm:ssZZZZZ` no `SMAHelper.getDateString` e no `ISOFormatter` do iOS), com a diferença de
 *   que o iOS escreveria `Z` em UTC; o app faz `moment(date)` e aceita os dois.
 * - Chave de dia: `yyyy-MM-dd` no fuso dado.
 *
 * Horário de verão: a conversão de horário local de parede para instante segue o `kotlinx-datetime`. Numa
 * lacuna (ex.: `2018-11-04T00:30` em `America/Sao_Paulo`, que não existiu) o horário é empurrado para frente
 * pela duração da lacuna; numa sobreposição (ex.: `2026-10-25T01:30` em `Europe/Lisbon`, que existiu duas
 * vezes) vale o offset anterior à transição, ou seja, a primeira ocorrência. Os testes `TimeFormatTest`
 * fixam esse comportamento.
 *
 * Offsets históricos com segundos (LMT, antes de ~1914) são escritos só com horas e minutos; nenhum relógio
 * SMA grava datas anteriores a 2000 (época do protocolo, `docs/protocolo-sma.md`, seção 7).
 */
internal object TimeFormat {
	/** Instante em ISO-8601 com o offset do fuso naquele instante (ex.: `"2026-10-28T00:00:00-03:00"`). */
	fun isoWithOffset(epochMillis: Long, timeZoneId: String): String {
		val zone = TimeZone.of(timeZoneId)
		val instant = Instant.fromEpochMilliseconds(epochMillis)
		val local = instant.toLocalDateTime(zone)
		val offsetSeconds = zone.offsetAt(instant).totalSeconds
		return formatLocal(local) + formatOffset(offsetSeconds)
	}

	/** Dia local (`yyyy-MM-dd`) do instante no fuso. */
	fun dayKey(epochMillis: Long, timeZoneId: String): String =
		formatDate(Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.of(timeZoneId)).date)

	/**
	 * Início do dia local [dayKey] no fuso, em epoch millis. Num dia em que a meia-noite não existe
	 * (início do horário de verão à meia-noite, como no Brasil até 2019) é o primeiro instante do dia.
	 *
	 * @throws IllegalArgumentException se [dayKey] não estiver no formato `yyyy-MM-dd`.
	 */
	fun startOfDayEpochMillis(dayKey: String, timeZoneId: String): Long =
		parseDayKey(dayKey).atStartOfDayIn(TimeZone.of(timeZoneId)).toEpochMilliseconds()

	/** [startOfDayEpochMillis] formatado com [isoWithOffset]. */
	fun startOfDayIso(dayKey: String, timeZoneId: String): String =
		isoWithOffset(startOfDayEpochMillis(dayKey, timeZoneId), timeZoneId)

	/** Horário de parede local do instante no fuso. */
	fun toLocalWallClock(epochMillis: Long, timeZoneId: String): LocalDateTime =
		Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.of(timeZoneId))

	/** Instante do horário de parede local no fuso (regras de lacuna e sobreposição no KDoc do objeto). */
	fun fromLocalWallClock(local: LocalDateTime, timeZoneId: String): Long =
		local.toInstant(TimeZone.of(timeZoneId)).toEpochMilliseconds()

	/** Horário local sem offset: `yyyy-MM-dd'T'HH:mm:ss`. */
	fun formatLocal(local: LocalDateTime): String =
		formatDate(local.date) + "T" + pad2(local.hour) + ":" + pad2(local.minute) + ":" + pad2(local.second)

	/** Data no formato `yyyy-MM-dd`. */
	fun formatDate(date: LocalDate): String =
		date.year.toString().padStart(4, '0') + "-" + pad2(date.monthNumber) + "-" + pad2(date.dayOfMonth)

	/**
	 * Lê uma chave de dia `yyyy-MM-dd`.
	 *
	 * @throws IllegalArgumentException se o texto não for uma data válida nesse formato.
	 */
	fun parseDayKey(dayKey: String): LocalDate {
		require(DAY_KEY.matches(dayKey)) { "invalid day key: $dayKey" }
		return try {
			LocalDate.parse(dayKey)
		} catch (error: IllegalArgumentException) {
			throw IllegalArgumentException("invalid day key: $dayKey", error)
		}
	}

	/** Offset em segundos como `±HH:MM`; segundos residuais (LMT) são descartados. */
	fun formatOffset(totalSeconds: Int): String {
		val sign = if (totalSeconds < 0) "-" else "+"
		val absolute = abs(totalSeconds)
		return sign + pad2(absolute / 3600) + ":" + pad2((absolute % 3600) / 60)
	}

	private val DAY_KEY = Regex("""\d{4}-\d{2}-\d{2}""")

	private fun pad2(value: Int): String = value.toString().padStart(2, '0')
}
