package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.log.FrameDirection
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Leitura do cabeçalho das gravações (`docs/fixtures.md`, seção 1).
 *
 * O [RecordingLoader] roda nos quatro targets, mas este teste fica na JVM: o instante esperado vem do `java.time`, um
 * oráculo independente do `kotlinx-datetime` que o leitor usa, e o padrão é o mesmo `DateTimeFormatter` do host Android.
 */
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

	@Test
	fun readPacketsAndExtraHeaderKeys() {
		// As fixtures de referência (scripts/btsnoop2jsonl.py) trazem chaves extras no cabeçalho e pacotes READ.
		val header = header("2026-10-31T15:43:12.209-03:00").removeSuffix("}") +
			",\"source\":\"hci\",\"gatt\":[{\"service\":\"180a\",\"characteristics\":[]}],\"redactions\":\"texto\"}"
		val read = "{\"type\":\"packet\",\"dir\":\"READ\",\"atMs\":1535,\"characteristic\":\"00002a29-0000-1000-8000-00805f9b34fb\",\"hex\":\"4D4F594F554E472D5632\"}"
		val recording = RecordingLoader.parse(header + "\n" + read + "\n", "inline")
		assertEquals("2026-10-31T15:43:12.209-03:00", recording.header.recordedAt)
		val packet = recording.packets.single()
		assertEquals(FrameDirection.READ, packet.direction)
		assertEquals("00002a29-0000-1000-8000-00805f9b34fb", packet.characteristic)
		// O Recording.toJsonl (o FrameJsonl do host) escreve "dir":"READ" de volta.
		assertEquals(read, recording.toJsonl().lines()[1])
	}
}
