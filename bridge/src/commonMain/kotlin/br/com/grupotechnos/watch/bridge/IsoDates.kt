package br.com.grupotechnos.watch.bridge

import kotlinx.datetime.DateTimeArithmeticException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

/**
 * As datas do contrato JS em código comum, reproduzindo o `java.time` que o host Android usava, caractere por caractere.
 *
 * O `kotlinx-datetime` faz a aritmética de fuso (início do dia, instante → dia local, buraco de horário de verão); a
 * leitura e a escrita do texto são feitas aqui, à mão, porque os formatos do `kotlinx-datetime` não aceitam e não
 * recusam as mesmas entradas que o `java.time` (caixa, frações, offsets curtos, o modo SMART do `ofPattern`). Cada
 * gramática abaixo cita o formatador que ela reproduz; o `IsoDatesDifferentialTest` (`jvmTest`) compara este código com
 * o `java.time` em todos os dias de 1900 a 2100 em nove fusos e num corpus gerado de `registered_at`.
 *
 * Diferença deliberada: quando o texto é válido mas o instante cai fora do intervalo de datas (anos perto de
 * ±999.999.999 com offset), o `java.time` lança `DateTimeException` em `atZoneSameInstant`, e o host antigo deixava a
 * exceção subir; aqui o resultado é `null`. No iOS, o intervalo de datas do `kotlinx-datetime` é menor que o do
 * `java.time` (no simulador, `+999999-12-31` e `-999999-01-01` passam e `+1000000-01-01` não); um ano fora dele também
 * dá `null` (e [startOfDayIso] lança `IllegalArgumentException`, que o `@Throws` do `EventMapper` entrega ao Swift).
 */
internal object IsoDates {
	private const val MAX_YEAR: Long = 999_999_999L

	// Teto para acumular dígitos de ano sem estourar o Long: qualquer valor acima já é um ano inválido.
	private const val SATURATED: Long = 1_000_000_000_000L
	private const val MAX_OFFSET_SECONDS: Int = 18 * 3600

	/**
	 * Início do dia local de [dayKey] (`yyyy-MM-dd`) em ISO com offset, ex.: `"2026-10-28T00:00:00-03:00"`.
	 *
	 * Reproduz `LocalDate.parse(dayKey).atStartOfDay(zone).format(ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))`:
	 * - dia cuja meia-noite não existe (buraco do horário de verão, ex.: 2018-11-04 em `America/Sao_Paulo`): o fim do
	 *   buraco, com o offset de depois (`2018-11-04T01:00:00-02:00`); meia-noite repetida: o offset de antes;
	 * - offset zero (UTC, Lisboa no inverno) sai `Z`; offset com segundos (hora média local antiga) perde os segundos.
	 *
	 * @throws IllegalArgumentException se [dayKey] não for uma data ISO (`LocalDate.parse` lançava
	 * `DateTimeParseException`).
	 */
	fun startOfDayIso(dayKey: String, zone: TimeZone): String {
		val cursor = Cursor(dayKey, caseInsensitive = false)
		val date = cursor.isoDate()?.takeIf { cursor.atEnd() }?.let(::strictDate)
			?: throw IllegalArgumentException("Invalid day key: $dayKey")
		val instant = date.atStartOfDayIn(zone)
		return formatOffsetDateTime(instant.toLocalDateTime(zone), zone.offsetAt(instant).totalSeconds)
	}

	/**
	 * Dia local (`yyyy-MM-dd`, como `LocalDate.toString`) de um `registered_at`, ou `null` se não for possível ler.
	 *
	 * Tenta, em ordem, as mesmas leituras do host antigo, depois de `trim()`; a primeira que aceita o texto decide:
	 * 1. `OffsetDateTime.parse(text)` (`ISO_OFFSET_DATE_TIME`), convertido para [zone];
	 * 2. `ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]XX")`, o offset legado sem dois-pontos (`-0300`), convertido para [zone];
	 * 3. `LocalDateTime.parse(text)`: hora local, o dia é o do texto;
	 * 4. `LocalDate.parse(text)`.
	 */
	fun dayKey(registeredAt: String, zone: TimeZone): String? {
		val text = registeredAt.trim()
		if (text.isEmpty()) return null
		isoOffsetDateTime(text)?.let { return localDay(it, zone) }
		legacyOffsetDateTime(text)?.let { return localDay(it, zone) }
		isoLocalDateTime(text)?.let { return formatLocalDate(it.date) }
		isoLocalDate(text)?.let { return formatLocalDate(it) }
		return null
	}

	// region leitura

	/** Uma data-hora lida e resolvida, com o offset do texto. */
	private class OffsetDateTimeValue(val dateTime: LocalDateTime, val offsetSeconds: Int)

	/** `ISO_OFFSET_DATE_TIME`: sem caixa, data e hora estritas, offset `+HH:mm:ss` leniente ou `Z`, resolução STRICT. */
	private fun isoOffsetDateTime(text: String): OffsetDateTimeValue? {
		val cursor = Cursor(text, caseInsensitive = true)
		val date = cursor.isoDate() ?: return null
		if (!cursor.literal('T')) return null
		val time = cursor.isoTime() ?: return null
		val offset = cursor.lenientOffsetId() ?: return null
		if (!cursor.atEnd()) return null
		val dateTime = strictDateTime(date, time) ?: return null
		return OffsetDateTimeValue(dateTime, offset.takeIf { abs(it) <= MAX_OFFSET_SECONDS } ?: return null)
	}

	/**
	 * `ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]XX")`: com caixa, ano da era, segundos obrigatórios, fração de exatamente 3
	 * dígitos, offset `+HHMM` estrito ou `Z`, resolução SMART (dia 29 a 31 além do fim do mês vira o último dia, e
	 * `24:00:00` vira a meia-noite do dia seguinte).
	 */
	private fun legacyOffsetDateTime(text: String): OffsetDateTimeValue? {
		val cursor = Cursor(text, caseInsensitive = false)
		val date = cursor.legacyDate() ?: return null
		if (!cursor.literal('T')) return null
		val hour = cursor.twoDigits() ?: return null
		if (!cursor.literal(':')) return null
		val minute = cursor.twoDigits() ?: return null
		if (!cursor.literal(':')) return null
		val second = cursor.twoDigits() ?: return null
		var nano = 0
		val beforeFraction = cursor.pos
		if (cursor.literal('.')) {
			val millis = cursor.digits(exactly = 3)
			if (millis == null) cursor.pos = beforeFraction else nano = millis * 1_000_000
		}
		val offset = cursor.strictOffsetHhmm() ?: return null
		if (!cursor.atEnd()) return null
		val dateTime = smartDateTime(date, RawTime(hour, minute, second, nano)) ?: return null
		return OffsetDateTimeValue(dateTime, offset.takeIf { abs(it) <= MAX_OFFSET_SECONDS } ?: return null)
	}

	/** `ISO_LOCAL_DATE_TIME`: sem caixa, resolução STRICT. */
	private fun isoLocalDateTime(text: String): LocalDateTime? {
		val cursor = Cursor(text, caseInsensitive = true)
		val date = cursor.isoDate() ?: return null
		if (!cursor.literal('T')) return null
		val time = cursor.isoTime() ?: return null
		if (!cursor.atEnd()) return null
		return strictDateTime(date, time)
	}

	/** `ISO_LOCAL_DATE`, resolução STRICT. */
	private fun isoLocalDate(text: String): LocalDate? {
		val cursor = Cursor(text, caseInsensitive = false)
		val date = cursor.isoDate() ?: return null
		if (!cursor.atEnd()) return null
		return strictDate(date)
	}

	/** Campos de data como foram lidos, antes da resolução. */
	private class RawDate(val year: Long, val month: Int, val day: Int)

	/** Campos de hora como foram lidos, antes da resolução (sem segundos ou sem fração: zero, como no `java.time`). */
	private class RawTime(val hour: Int, val minute: Int, val second: Int, val nano: Int)

	/**
	 * Leitura sobre o texto, com as regras do `DateTimeFormatter` em modo estrito: sem retrocesso fora das seções
	 * opcionais, dígitos só ASCII, e cada literal comparado com ou sem caixa conforme o formatador.
	 */
	private class Cursor(private val text: String, private val caseInsensitive: Boolean) {
		var pos: Int = 0

		fun atEnd(): Boolean = pos == text.length

		/** `CharLiteralPrinterParser`: sem caixa, vale a comparação de `Character.toUpperCase`/`toLowerCase`. */
		fun literal(expected: Char): Boolean {
			if (pos == text.length) return false
			val actual = text[pos]
			if (actual != expected) {
				if (!caseInsensitive) return false
				if (actual.uppercaseChar() != expected.uppercaseChar() && actual.lowercaseChar() != expected.lowercaseChar()) {
					return false
				}
			}
			pos++
			return true
		}

		/** `appendValue(campo, 2)`: exatamente dois dígitos, sem sinal. */
		fun twoDigits(): Int? = digits(exactly = 2)

		/** Exatamente [exactly] dígitos ASCII (campos de largura fixa e a fração `SSS`). */
		fun digits(exactly: Int): Int? {
			if (pos + exactly > text.length) return null
			var value = 0
			for (index in pos until pos + exactly) {
				val digit = digit(text[index])
				if (digit < 0) return null
				value = value * 10 + digit
			}
			pos += exactly
			return value
		}

		/** `ISO_LOCAL_DATE`: ano `YEAR` de 4 a 10 dígitos (`SignStyle.EXCEEDS_PAD`), `-MM-dd`. */
		fun isoDate(): RawDate? {
			val year = year(maxDigits = 10) ?: return null
			return monthAndDay(year)
		}

		/** `yyyy-MM-dd` do `ofPattern`: ano da era de 4 a 19 dígitos (`SignStyle.EXCEEDS_PAD`); só 1 a 999.999.999 vale. */
		fun legacyDate(): RawDate? {
			val yearOfEra = year(maxDigits = 19) ?: return null
			// Sem campo de era, o modo SMART toma o ano da era como ano; 0 e negativos não passam na validação.
			if (yearOfEra < 1 || yearOfEra > MAX_YEAR) return null
			return monthAndDay(yearOfEra)
		}

		private fun monthAndDay(year: Long): RawDate? {
			if (!literal('-')) return null
			val month = twoDigits() ?: return null
			if (!literal('-')) return null
			val day = twoDigits() ?: return null
			return RawDate(year, month, day)
		}

		/**
		 * `NumberPrinterParser` com mínimo 4 e `SignStyle.EXCEEDS_PAD`, em modo estrito: `+` só com mais de 4 dígitos, mais
		 * de 4 dígitos só com sinal, `-` com qualquer largura mas nunca zero (`-0000`).
		 */
		fun year(maxDigits: Int): Long? {
			if (pos == text.length) return null
			var negative = false
			var positive = false
			when (text[pos]) {
				'+' -> positive = true
				'-' -> negative = true
			}
			val start = if (negative || positive) pos + 1 else pos
			if (start + 4 > text.length) return null
			val end = minOf(start + maxDigits, text.length)
			var cursor = start
			var total = 0L
			while (cursor < end) {
				val digit = digit(text[cursor])
				if (digit < 0) break
				total = minOf(total * 10 + digit, SATURATED)
				cursor++
			}
			val count = cursor - start
			if (count < 4) return null
			if (negative) {
				if (total == 0L) return null
				total = -total
			} else if (positive) {
				if (count <= 4) return null
			} else if (count > 4) {
				return null
			}
			pos = cursor
			return total
		}

		/** `ISO_LOCAL_TIME`: `HH:mm`, depois `:ss` opcional e, dentro dele, `.` com 0 a 9 dígitos opcional. */
		fun isoTime(): RawTime? {
			val hour = twoDigits() ?: return null
			if (!literal(':')) return null
			val minute = twoDigits() ?: return null
			var second = 0
			var nano = 0
			val beforeSeconds = pos
			if (literal(':')) {
				val parsed = twoDigits()
				if (parsed == null) {
					// A seção opcional falhou inteira: volta para antes do ':'.
					pos = beforeSeconds
				} else {
					second = parsed
					// `appendFraction(NANO_OF_SECOND, 0, 9, true)`: com o ponto, de 0 a 9 dígitos (o ponto sozinho vale 0).
					if (pos < text.length && text[pos] == '.') {
						pos++
						var count = 0
						var fraction = 0
						while (count < 9 && pos < text.length) {
							val digit = digit(text[pos])
							if (digit < 0) break
							fraction = fraction * 10 + digit
							count++
							pos++
						}
						repeat(9 - count) { fraction *= 10 }
						nano = fraction
					}
				}
			}
			return RawTime(hour, minute, second, nano)
		}

		/**
		 * `appendOffsetId()` (`+HH:MM:ss`, `Z`) em modo leniente, como no `ISO_OFFSET_DATE_TIME`: `Z` (sem caixa aqui), ou
		 * sinal, hora de dois dígitos e, opcionais, `:mm` e `:ss`. Hora acima de 23 é erro.
		 *
		 * @return o offset em segundos, ainda sem a checagem de ±18 h.
		 */
		fun lenientOffsetId(): Int? {
			if (pos == text.length) return null
			if (literal('Z')) return 0
			val sign = signOf(text[pos]) ?: return null
			var cursor = pos + 1
			val hour = offsetPair(cursor, colon = false) ?: return null
			cursor += 2
			var minute = 0
			var second = 0
			val parsedMinute = offsetPair(cursor, colon = true)
			if (parsedMinute != null) {
				minute = parsedMinute
				cursor += 3
				val parsedSecond = offsetPair(cursor, colon = true)
				if (parsedSecond != null) {
					second = parsedSecond
					cursor += 3
				}
			}
			if (hour > 23) return null
			pos = cursor
			return sign * (hour * 3600 + minute * 60 + second)
		}

		/** `XX` do `ofPattern` (`+HHMM`, `Z`), estrito e com caixa: `Z`, ou sinal e quatro dígitos. */
		fun strictOffsetHhmm(): Int? {
			if (pos == text.length) return null
			if (text[pos] == 'Z') {
				pos++
				return 0
			}
			val sign = signOf(text[pos]) ?: return null
			val hour = offsetPair(pos + 1, colon = false) ?: return null
			val minute = offsetPair(pos + 3, colon = false) ?: return null
			if (hour > 23) return null
			pos += 5
			return sign * (hour * 3600 + minute * 60)
		}

		/** `OffsetIdPrinterParser.parseDigits`: `:` antes quando [colon], dois dígitos, valor até 59. */
		private fun offsetPair(at: Int, colon: Boolean): Int? {
			var cursor = at
			if (colon) {
				if (cursor + 1 > text.length || text[cursor] != ':') return null
				cursor++
			}
			if (cursor + 2 > text.length) return null
			val first = digit(text[cursor])
			val second = digit(text[cursor + 1])
			if (first < 0 || second < 0) return null
			val value = first * 10 + second
			return value.takeIf { it <= 59 }
		}

		private fun signOf(char: Char): Int? = when (char) {
			'+' -> 1
			'-' -> -1
			else -> null
		}

		private fun digit(char: Char): Int = if (char in '0'..'9') char - '0' else -1
	}

	// endregion

	// region resolução

	/** `ResolverStyle.STRICT`: mês 1 a 12, dia que existe no mês, ano até ±999.999.999. */
	private fun strictDate(raw: RawDate): LocalDate? {
		if (raw.year < -MAX_YEAR || raw.year > MAX_YEAR) return null
		if (raw.month !in 1..12) return null
		if (raw.day < 1 || raw.day > lengthOfMonth(raw.year, raw.month)) return null
		return localDate(raw.year, raw.month, raw.day)
	}

	/** `ResolverStyle.STRICT` para data e hora: hora 0 a 23, minuto e segundo 0 a 59. */
	private fun strictDateTime(rawDate: RawDate, time: RawTime): LocalDateTime? {
		val date = strictDate(rawDate) ?: return null
		if (time.hour > 23 || time.minute > 59 || time.second > 59) return null
		return localDateTime(date, time.hour, time.minute, time.second, time.nano)
	}

	/**
	 * `ResolverStyle.SMART` (o padrão do `ofPattern`): o dia 1 a 31 que passa do fim do mês vira o último dia do mês, e
	 * `24:00:00` (com fração zero) vira a meia-noite do dia seguinte.
	 */
	private fun smartDateTime(rawDate: RawDate, time: RawTime): LocalDateTime? {
		if (rawDate.month !in 1..12) return null
		if (rawDate.day !in 1..31) return null
		val day = minOf(rawDate.day, lengthOfMonth(rawDate.year, rawDate.month))
		val date = localDate(rawDate.year, rawDate.month, day) ?: return null
		if (time.minute > 59) return null
		if (time.hour == 24 && time.minute == 0 && time.second == 0 && time.nano == 0) {
			val next = try {
				date.plus(1, DateTimeUnit.DAY)
			} catch (_: DateTimeArithmeticException) {
				return null
			} catch (_: IllegalArgumentException) {
				return null
			}
			return localDateTime(next, 0, 0, 0, 0)
		}
		if (time.hour > 23 || time.second > 59) return null
		return localDateTime(date, time.hour, time.minute, time.second, time.nano)
	}

	private fun lengthOfMonth(year: Long, month: Int): Int = when (month) {
		2 -> if (isLeap(year)) 29 else 28
		4, 6, 9, 11 -> 30
		else -> 31
	}

	// IsoChronology.isLeapYear, inclusive para anos negativos (calendário gregoriano proléptico).
	private fun isLeap(year: Long): Boolean = (year and 3L) == 0L && (year % 100L != 0L || year % 400L == 0L)

	private fun localDate(year: Long, month: Int, day: Int): LocalDate? =
		try {
			LocalDate(year.toInt(), month, day)
		} catch (_: IllegalArgumentException) {
			null
		}

	private fun localDateTime(date: LocalDate, hour: Int, minute: Int, second: Int, nano: Int): LocalDateTime? =
		try {
			LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, hour, minute, second, nano)
		} catch (_: IllegalArgumentException) {
			null
		}

	/** O dia, no fuso [zone], do instante de [value] (`atZoneSameInstant(zone).toLocalDate()`). */
	private fun localDay(value: OffsetDateTimeValue, zone: TimeZone): String? =
		try {
			val instant: Instant = value.dateTime.toInstant(UtcOffset(seconds = value.offsetSeconds))
			formatLocalDate(instant.toLocalDateTime(zone).date)
		} catch (_: DateTimeArithmeticException) {
			null
		} catch (_: IllegalArgumentException) {
			null
		}

	// endregion

	// region escrita

	/** `LocalDate.toString()`: 4 dígitos com zeros, `-` antes de ano negativo, `+` antes de ano acima de 9999. */
	private fun formatLocalDate(date: LocalDate): String = buildString {
		val year = date.year
		val absYear = abs(year)
		if (absYear < 1000) {
			if (year < 0) append('-')
			append(absYear.toString().padStart(4, '0'))
		} else {
			if (year > 9999) append('+')
			append(year)
		}
		append('-').append(twoDigits(date.monthNumber))
		append('-').append(twoDigits(date.dayOfMonth))
	}

	/**
	 * `ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")`: `yyyy` é o ano da era (4 dígitos com zeros, `+` acima de 9999); `XXX` é
	 * `+HH:MM`, sem os segundos do offset, e `Z` quando hora e minuto do offset são zero.
	 */
	private fun formatOffsetDateTime(dateTime: LocalDateTime, offsetSeconds: Int): String = buildString {
		val yearOfEra = if (dateTime.year >= 1) dateTime.year.toLong() else 1L - dateTime.year
		if (yearOfEra >= 10_000) append('+')
		append(yearOfEra.toString().padStart(4, '0'))
		append('-').append(twoDigits(dateTime.monthNumber))
		append('-').append(twoDigits(dateTime.dayOfMonth))
		append('T').append(twoDigits(dateTime.hour))
		append(':').append(twoDigits(dateTime.minute))
		append(':').append(twoDigits(dateTime.second))
		val absHours = abs((offsetSeconds / 3600) % 100)
		val absMinutes = abs((offsetSeconds / 60) % 60)
		if (absHours == 0 && absMinutes == 0) {
			append('Z')
		} else {
			append(if (offsetSeconds < 0) '-' else '+')
			append(twoDigits(absHours)).append(':').append(twoDigits(absMinutes))
		}
	}

	private fun twoDigits(value: Int): String = value.toString().padStart(2, '0')

	// endregion
}
