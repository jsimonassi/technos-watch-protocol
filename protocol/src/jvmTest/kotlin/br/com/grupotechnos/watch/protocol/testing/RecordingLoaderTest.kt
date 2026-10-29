package br.com.grupotechnos.watch.protocol.testing

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

/** Leitura do cabeçalho das gravações (`docs/fixtures.md`, seção 1). */
class RecordingLoaderTest {
	private fun header(recordedAt: String): String =
		"""{"type":"header","device":"Mormaii_fitGPS","firmware":"0.2.1","platform":"android","mtu":23,""" +
			""""recordedAt":"$recordedAt","timeZone":"America/Sao_Paulo"}"""

	/** Instante esperado calculado pelo `java.time`, independente do `kotlinx-datetime` que o leitor usa. */
	private fun epochMillis(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

	@Test
	fun recordedAtWithMillisecondsParses() {
		// O FrameRecorder do react-native-technos-core-next passa a escrever yyyy-MM-dd'T'HH:mm:ss.SSSxxx: antes ele
		// truncava no segundo, e o recordedAt da gravação de 2026-10-29 ficou 933 ms antes do atMs 0.
		val withMillis = "2026-10-29T08:14:10.933-03:00"
		assertEquals(epochMillis(withMillis), RecordingLoader.parse(header(withMillis), "inline").header.recordedAtEpochMillis)
		// "xxx" escreve o offset zero como +00:00, nunca Z.
		val utc = "2026-10-29T11:14:10.933+00:00"
		assertEquals(epochMillis(utc), RecordingLoader.parse(header(utc), "inline").header.recordedAtEpochMillis)
		assertEquals(epochMillis(withMillis), epochMillis(utc))
		// O formato antigo, sem milissegundos, continua valendo (é o da fixture real de 2026-10-29).
		val seconds = "2026-10-29T08:14:10-03:00"
		assertEquals(epochMillis(seconds), RecordingLoader.parse(header(seconds), "inline").header.recordedAtEpochMillis)
	}

	@Test
	fun recordedAtWrittenWithTheHostPatternRoundTrips() {
		val pattern = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
		for (zone in listOf("America/Sao_Paulo", "UTC", "Asia/Kolkata")) {
			val moment = ZonedDateTime.of(2026, 10, 29, 8, 14, 10, 7_000_000, ZoneId.of(zone))
			val text = moment.format(pattern)
			assertEquals(moment.toInstant().toEpochMilli(), RecordingLoader.parse(header(text), zone).header.recordedAtEpochMillis, text)
		}
	}
}
