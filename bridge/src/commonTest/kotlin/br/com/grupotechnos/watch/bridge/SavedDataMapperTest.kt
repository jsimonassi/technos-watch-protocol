package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.domain.DataKind
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `deletedSavedData`: categoria → `DataKind` e `registered_at` → dia local. Portado do host Android; quatro targets. */
class SavedDataMapperTest {
	private val saoPaulo = TimeZone.of("America/Sao_Paulo")

	@Test
	fun categories() {
		assertEquals(DataKind.STEPS, SavedDataMapper.kindForCategory(5))
		assertEquals(DataKind.SLEEP, SavedDataMapper.kindForCategory(0))
		assertNull(SavedDataMapper.kindForCategory(4))
	}

	@Test
	fun offsetDatesUseTheLocalDay() {
		assertEquals("2026-10-28", SavedDataMapper.dayKey("2026-10-28T00:00:00-03:00", saoPaulo))
		assertEquals("2026-10-28", SavedDataMapper.dayKey("2026-10-28T22:00:00-03:00", saoPaulo))
	}

	@Test
	fun utcDatesAreConvertedBackToTheLocalDay() {
		// Início do dia local que passou por moment().toISOString().
		assertEquals("2026-10-28", SavedDataMapper.dayKey("2026-10-28T03:00:00.000Z", saoPaulo))
		// 22h locais viram o dia seguinte em UTC; cortar a string daria 2026-10-29.
		assertEquals("2026-10-28", SavedDataMapper.dayKey("2026-10-29T01:00:00Z", saoPaulo))
	}

	@Test
	fun legacyAndPartialFormats() {
		assertEquals("2026-10-28", SavedDataMapper.dayKey("2026-10-28T07:00:00-0300", saoPaulo))
		assertEquals("2026-10-28", SavedDataMapper.dayKey("2026-10-28T07:00:00", saoPaulo))
		assertEquals("2026-10-28", SavedDataMapper.dayKey("2026-10-28", saoPaulo))
		assertNull(SavedDataMapper.dayKey("28/10/2026", saoPaulo))
		assertNull(SavedDataMapper.dayKey("", saoPaulo))
	}

	@Test
	fun dayKeysAreDistinctAndKeepOrder() {
		val result = SavedDataMapper.dayKeys(
			listOf(
				"2026-10-27T00:00:00-03:00",
				"2026-10-28T00:00:00-03:00",
				"2026-10-28T03:00:00.000Z",
				"garbage",
			),
			saoPaulo,
		)
		assertEquals(listOf("2026-10-27", "2026-10-28"), result.dayKeys)
		assertEquals(listOf("garbage"), result.rejected)
	}

	@Test
	fun acceptsAndRejectsExactlyWhatJavaTimeDid() {
		// Valores do java.time do host Android antigo (as quatro leituras de SavedDataMapper.dayKey), em São Paulo. O
		// IsoDatesDifferentialTest (jvmTest) compara o resto; estes rodam também no simulador iOS.
		val expected = listOf(
			// ISO_OFFSET_DATE_TIME: sem caixa, segundos opcionais, offset só com a hora, ponto sem dígitos, 9 dígitos.
			"2026-10-28t07:00:00z" to "2026-10-28",
			"2026-10-28T07:00-03:00" to "2026-10-28",
			"2026-10-28T07:00:00-03" to "2026-10-28",
			"2026-10-28T07:00:00.-03:00" to "2026-10-28",
			"2026-10-28T07:00:00.123456789Z" to "2026-10-28",
			"2026-10-28T07:00:00.1234567891Z" to null,
			"2026-10-28T07:00:00+03:00:30" to "2026-10-28",
			"2026-10-29T02:00:00+18:00" to "2026-10-28",
			"2026-10-28T07:00:00+18:01" to null,
			"2026-10-28T07:00:00+24:00" to null,
			"2026-10-28T07:00:00 -03:00" to null,
			"2026-10-28T07:00:00−03:00" to null,
			// Offset legado (ofPattern, SMART): fração só de 3 dígitos, dia além do fim do mês e 24:00 ajustados.
			"2026-10-28T07:00:00.123-0300" to "2026-10-28",
			"2026-10-28T07:00:00.12-0300" to null,
			"2026-10-28T07:00:00.1234-0300" to null,
			"2026-02-30T07:00:00Z" to "2026-02-28",
			"2026-02-30T07:00:00-0300" to "2026-02-28",
			"2026-02-30T07:00:00-03:00" to null,
			"2024-02-30T12:00:00-0300" to "2024-02-29",
			"2026-04-31T12:00:00-0300" to "2026-04-30",
			"2026-10-28T24:00:00-0300" to "2026-10-29",
			"2026-10-28T24:00:00Z" to "2026-10-28",
			"2026-10-28T24:00:00.000-0300" to "2026-10-29",
			"2026-10-28T24:00:00.001-0300" to null,
			"2026-10-28T24:00:00-03:00" to null,
			"2026-10-28T07:00:00+0360" to null,
			// Anos: sinal só acima de 4 dígitos, ano 0 só no ISO (no ofPattern é ano da era).
			"+12026-10-28T07:00:00Z" to "+12026-10-28",
			"+2026-10-28T07:00:00Z" to null,
			"-2026-10-28T07:00:00Z" to "-2026-10-28",
			"0000-10-28T07:00:00-0300" to null,
			"0000-10-28T07:00:00-03:00" to "0000-10-28",
			// Sem offset e só a data.
			"2026-10-28t07:00" to "2026-10-28",
			"2026-10-28T07:00:00." to "2026-10-28",
			"2018-11-04T00:30:00" to "2018-11-04",
			"2026-10-28T07:00:" to null,
			"2026-10-28T07" to null,
			"2026-1-28" to null,
			"20261028" to null,
			" \t2026-10-28\n" to "2026-10-28",
			// Instantes perto da meia-noite local, inclusive no dia em que ela não existiu.
			"2018-11-04T02:30:00Z" to "2018-11-03",
			"2026-10-29T02:59:59Z" to "2026-10-28",
			"2026-10-29T03:00:00Z" to "2026-10-29",
		)
		for ((input, day) in expected) {
			assertEquals(day, SavedDataMapper.dayKey(input, saoPaulo), "dayKey(\"$input\")")
		}
	}
}
