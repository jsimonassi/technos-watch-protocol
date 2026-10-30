package br.com.grupotechnos.watch.bridge

import kotlinx.datetime.TimeZone
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Teste diferencial: as datas do código comum ([IsoDates], via [EventMapper] e [SavedDataMapper]) contra o `java.time`
 * do host Android antigo, copiado sem mudança em [Oracle]. Só na JVM, porque o oráculo é o `java.time`.
 *
 * - `startOfDayIso`: todo dia de 1900 a 2100 em nove fusos com histórico de horário de verão e offsets fora da hora
 *   cheia (inclusive `America/Sao_Paulo` em 2018 e 2019, quando a meia-noite não existiu), mais anos extremos.
 * - `dayKey`: um corpus gerado de `registered_at` (datas válidas e inválidas, anos com e sem sinal, separadores, horas,
 *   frações de 0 a 10 dígitos, offsets com e sem dois-pontos, `Z`, lixo, espaços) em cinco fusos, e um corpus de
 *   mutações aleatórias (semente fixa) de textos válidos.
 *
 * Cada caso imprime uma linha `DIFFERENTIAL` no `system-out` do XML com quantas comparações rodaram e quantas
 * divergiram (sempre 0). A única diferença aceita é deliberada (KDoc de [IsoDates]): onde o oráculo lança
 * `DateTimeException` fora do parse (instante fora do intervalo de datas), o código novo devolve `null`; essas entradas
 * são contadas à parte.
 */
class IsoDatesDifferentialTest {
	/** O código do host Android antigo (`EventMapper.startOfDayIso` e `SavedDataMapper.dayKey`), sem mudança. */
	private object Oracle {
		val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

		private val LEGACY_OFFSET: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]XX")

		fun startOfDayIso(dayKey: String, zone: ZoneId): String =
			LocalDate.parse(dayKey).atStartOfDay(zone).format(ISO_FORMATTER)

		fun dayKey(registeredAt: String, zone: ZoneId): String? {
			val text = registeredAt.trim()
			if (text.isEmpty()) return null
			parseOrNull { OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDate() }?.let { return it.toString() }
			parseOrNull { OffsetDateTime.parse(text, LEGACY_OFFSET).atZoneSameInstant(zone).toLocalDate() }?.let { return it.toString() }
			parseOrNull { LocalDateTime.parse(text).toLocalDate() }?.let { return it.toString() }
			parseOrNull { LocalDate.parse(text) }?.let { return it.toString() }
			return null
		}

		private inline fun <T> parseOrNull(block: () -> T): T? = try {
			block()
		} catch (_: DateTimeParseException) {
			null
		}
	}

	private val startOfDayZones = listOf(
		"America/Sao_Paulo", "America/Manaus", "America/Noronha", "America/Campo_Grande", "Europe/Lisbon", "UTC",
		"Asia/Kolkata", "Pacific/Chatham", "America/St_Johns",
	)

	@Test
	fun startOfDayIsoMatchesJavaTimeOnEveryDayFrom1900To2100() {
		var compared = 0
		val mismatches = mutableListOf<String>()
		var gapDays = 0
		for (zoneId in startOfDayZones) {
			val javaZone = ZoneId.of(zoneId)
			val zone = TimeZone.of(zoneId)
			var day = LocalDate.of(1900, 1, 1)
			val last = LocalDate.of(2100, 12, 31)
			while (!day.isAfter(last)) {
				val key = day.toString()
				val expected = Oracle.startOfDayIso(key, javaZone)
				val actual = EventMapper.startOfDayIso(key, zone)
				compared++
				if (expected != actual) mismatches += "$key $zoneId: java=$expected common=$actual"
				if (!expected.startsWith("${key}T00:00:00")) gapDays++
				day = day.plusDays(1)
			}
			for (key in listOf("0001-01-01", "0000-06-15", "-0005-06-15", "1850-01-01", "9999-12-31", "+12345-01-01")) {
				compared++
				val expected = Oracle.startOfDayIso(key, javaZone)
				val actual = EventMapper.startOfDayIso(key, zone)
				if (expected != actual) mismatches += "$key $zoneId: java=$expected common=$actual"
			}
		}
		println("DIFFERENTIAL startOfDayIso compared=$compared mismatches=${mismatches.size} daysNotStartingAtMidnight=$gapDays zones=${startOfDayZones.size}")
		assertEquals(emptyList(), mismatches.take(20), "${mismatches.size} mismatches")
		// O corpus tem de ter dias cuja meia-noite não existiu, senão o caso do buraco não foi exercitado.
		assertTrue(gapDays > 0, "no day without a midnight in the corpus")
		assertEquals("2018-11-04T01:00:00-02:00", EventMapper.startOfDayIso("2018-11-04", TimeZone.of("America/Sao_Paulo")))
	}

	@Test
	fun startOfDayIsoRejectsWhatLocalDateParseRejects() {
		val zone = ZoneId.of("America/Sao_Paulo")
		var compared = 0
		for (bad in listOf("", "2026-02-30", "2026-13-01", "2026-1-28", "20261028", "+2026-10-28", "-0000-01-01", "2026-10-28T00:00", " 2026-10-28")) {
			assertFailsWith<DateTimeParseException>(bad) { Oracle.startOfDayIso(bad, zone) }
			assertFailsWith<IllegalArgumentException>(bad) { EventMapper.startOfDayIso(bad, TimeZone.of(zone.id)) }
			compared++
		}
		println("DIFFERENTIAL startOfDayIso-invalid compared=$compared mismatches=0")
	}

	@Test
	fun dayKeyMatchesJavaTimeOnAGeneratedCorpus() {
		val zones = listOf("America/Sao_Paulo", "UTC", "Asia/Kolkata", "Pacific/Chatham", "America/St_Johns")
		val corpus = generatedCorpus()
		val stats = compare(corpus, zones)
		println(
			"DIFFERENTIAL dayKey-generated inputs=${corpus.size} zones=${zones.size} compared=${stats.compared} " +
				"mismatches=${stats.mismatches.size} accepted=${stats.accepted} rejected=${stats.rejected} " +
				"javaThrewOutsideParse=${stats.javaThrew}",
		)
		assertEquals(emptyList(), stats.mismatches.take(20), "${stats.mismatches.size} mismatches")
		// O corpus tem de cobrir as quatro leituras e as recusas.
		assertTrue(stats.accepted > 10_000 && stats.rejected > 10_000, "corpus too one-sided: $stats")
	}

	@Test
	fun dayKeyMatchesJavaTimeOnRandomMutations() {
		val random = Random(20261029)
		val templates = listOf(
			"2026-10-28T07:00:00-03:00", "2026-10-28T03:00:00.000Z", "2026-10-28T07:00:00-0300", "2026-10-28T07:00:00.123-0300",
			"2026-10-28T07:00", "2026-10-28", "2018-11-04T00:30:00", "2026-02-28T24:00:00Z", "+12026-10-28T07:00:00Z",
			"2026-10-28T07:00:00.123456789+05:30", "2026-10-28t07:00:00z", "2026-10-29T01:00:00Z",
		)
		val alphabet = "0123456789-+:.TtZz W/−"
		val corpus = LinkedHashSet<String>()
		while (corpus.size < 100_000) {
			val chars = StringBuilder(templates[random.nextInt(templates.size)])
			repeat(1 + random.nextInt(3)) {
				val at = random.nextInt(chars.length + 1)
				when (random.nextInt(3)) {
					0 -> if (chars.isNotEmpty() && at < chars.length) chars.deleteCharAt(at)
					1 -> chars.insert(at, alphabet[random.nextInt(alphabet.length)])
					else -> if (at < chars.length) chars.setCharAt(at, alphabet[random.nextInt(alphabet.length)])
				}
			}
			corpus += chars.toString()
		}
		val zones = listOf("America/Sao_Paulo", "Pacific/Chatham")
		val stats = compare(corpus.toList(), zones)
		println(
			"DIFFERENTIAL dayKey-mutations inputs=${corpus.size} zones=${zones.size} compared=${stats.compared} " +
				"mismatches=${stats.mismatches.size} accepted=${stats.accepted} rejected=${stats.rejected} " +
				"javaThrewOutsideParse=${stats.javaThrew}",
		)
		assertEquals(emptyList(), stats.mismatches.take(20), "${stats.mismatches.size} mismatches")
		assertTrue(stats.accepted > 1_000, "mutations never produced a valid date: $stats")
	}

	@Test
	fun publicOverloadsUseTheSystemTimeZone() {
		// A sobrecarga sem fuso lê TimeZone.currentSystemDefault(), o ZoneId.systemDefault() do host antigo.
		val system = ZoneId.systemDefault()
		assertEquals(Oracle.startOfDayIso("2026-10-28", system), EventMapper.startOfDayIso("2026-10-28"))
		for (input in listOf("2026-10-28T22:00:00-03:00", "2026-10-29T01:00:00Z", "2026-10-28T07:00:00-0300", "2026-10-28")) {
			assertEquals(Oracle.dayKey(input, system), SavedDataMapper.dayKey(input), input)
		}
		println("DIFFERENTIAL system-zone zone=${system.id} compared=5 mismatches=0")
	}

	private data class Stats(
		val compared: Int,
		val accepted: Int,
		val rejected: Int,
		val javaThrew: Int,
		val mismatches: List<String>,
	)

	private fun compare(corpus: List<String>, zones: List<String>): Stats {
		var compared = 0
		var accepted = 0
		var rejected = 0
		var javaThrew = 0
		val mismatches = mutableListOf<String>()
		for (zoneId in zones) {
			val javaZone = ZoneId.of(zoneId)
			val zone = TimeZone.of(zoneId)
			for (input in corpus) {
				compared++
				val actual = SavedDataMapper.dayKey(input, zone)
				val expected = try {
					Oracle.dayKey(input, javaZone)
				} catch (_: DateTimeException) {
					// Instante fora do intervalo de datas: o host antigo deixava a exceção subir; o novo devolve null.
					javaThrew++
					if (actual != null) mismatches += "${show(input)} $zoneId: java threw, common=$actual"
					continue
				}
				if (expected == null) rejected++ else accepted++
				if (expected != actual) mismatches += "${show(input)} $zoneId: java=$expected common=$actual"
			}
		}
		return Stats(compared, accepted, rejected, javaThrew, mismatches)
	}

	private fun show(input: String): String =
		"\"" + input.map { if (it.code in 0x20..0x7e) it.toString() else "\\u%04X".format(it.code) }.joinToString("") + "\""

	/** Todas as combinações de data × separador × hora × offset, mais datas sozinhas, lixo e espaços. */
	private fun generatedCorpus(): List<String> {
		val dates = listOf(
			"2026-10-28", "2026-10-29", "2026-12-31", "2027-01-01", "2024-02-29", "2026-02-28", "2026-02-29", "2026-02-30",
			"2026-02-31", "2026-04-30", "2026-04-31", "2026-06-31", "2026-13-01", "2026-00-10", "2026-10-00", "2026-10-32",
			"2018-11-04", "2018-11-03", "2019-02-16", "2019-02-17", "0000-01-01", "0001-01-01", "9999-12-31", "+10000-01-01",
			"+2026-10-28", "-2026-10-28", "-0000-10-28", "-0001-12-31", "+999999999-12-31", "-999999999-01-01",
			"+1000000000-01-01", "02026-10-28", "226-10-28", "2026-1-28", "2026-10-1", "2026/10/28", "+0012345-01-01",
			"1900-02-29", "2000-02-29", "2100-02-29", "+00000002026-10-28",
		)
		val separators = listOf("T", "t", " ")
		val times = listOf(
			"", "00:00", "00:00:00", "07:00", "07:00:00", "23:59:59", "24:00", "24:00:00", "24:00:00.000", "24:00:00.001",
			"24:00:01", "23:60:00", "23:59:60", "25:00:00", "7:00", "07:0", "07:00:", "07:00:00.", "07:00:00.1",
			"07:00:00.12", "07:00:00.123", "07:00:00.1234", "07:00:00.123456789", "07:00:00.1234567890", "21:30:00",
			"22:00:00", "01:00:00", "02:59:59", "07:00:00,123", "07", "07:00:00:00",
		)
		val offsets = listOf(
			"", "Z", "z", "+00:00", "-00:00", "+0000", "-0000", "-0300", "-03:00", "-03", "+03", "+3", "-03:0", "-03:00:00",
			"-03:00:30", "-030030", "+05:30", "+0530", "+05:30:00", "+18:00", "+1800", "-18:00", "-1800", "+18:01", "+1801",
			"+19:00", "+24:00", "+2400", "+0360", "+03:60", "+14:00", "-0330", "-03:30", "−03:00", "UTC", "+03:00Z",
			" Z", "Z ", "+13:45", "-12:00", "+12:00",
		)
		val corpus = LinkedHashSet<String>()
		for (date in dates) {
			corpus += date
			for (offset in offsets) corpus += date + offset
			for (separator in separators) {
				for (time in times) {
					for (offset in offsets) corpus += date + separator + time + offset
				}
			}
		}
		val wrappers = listOf(" ", "\t", "\n", "\r\n", " ", " ", "​", "  ")
		for (core in listOf("2026-10-28T07:00:00-03:00", "2026-10-29T01:00:00Z", "2026-10-28T07:00:00-0300", "2026-10-28")) {
			for (wrapper in wrappers) {
				corpus += wrapper + core
				corpus += core + wrapper
				corpus += wrapper + core + wrapper
			}
		}
		corpus += listOf("", " ", "\t\n", "garbage", "28/10/2026", "2026/10/28", "T07:00", "2026-10-28T", "null", "undefined", "NaN")
		return corpus.toList()
	}
}
