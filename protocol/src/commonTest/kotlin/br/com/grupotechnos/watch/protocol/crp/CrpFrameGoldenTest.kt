package br.com.grupotechnos.watch.protocol.crp

import br.com.grupotechnos.watch.protocol.util.Hex
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Vetores dourados do CRP (`docs/protocolo-crp.md`, seção 12). Cada TX foi conferido de forma independente contra a
 * captura do app Condor de produção (`fixtures/reference/condorreebok/MOY-VS02-2.0.0/`, `atMs` no comentário) ou contra
 * os vetores da fase 0 (calculados com a SDK, `sdk:f/b2.java:9-37`), e cada RX é o hex real da captura.
 */
class CrpFrameGoldenTest {
	private fun tx(request: CrpRequest, block: Int = 244): String = Hex.encode(CrpFrameCodec.encode(request.cmd, request.payload, block))

	@Test
	fun handshakeFramesMatchTheCapture() {
		// condor-app-pair-sync.jsonl atMs 1542 (bloco do MTU 517) e condor-app-reconnect-sync-walk.jsonl atMs 1196 (bloco 20).
		assertEquals("FEEA20065A00", tx(CrpRequest.protocolVersion()))
		assertEquals("FEEA10065A00", tx(CrpRequest.protocolVersion(), block = 20))
		// condor-app-pair-sync.jsonl atMs 1773 e 2025.
		assertEquals("FEEA2006B70E", tx(CrpRequest.sppQuery()))
		assertEquals("FEEA2007BB1600", tx(CrpRequest.emojiCount()))
	}

	@Test
	fun timeAndZoneMatchTheCapture() {
		// condor-app-pair-sync.jsonl atMs 6821: 15:43:19 local relido como GMT+8 = 0x6AE59C17, mais 08.
		val now = Instant.parse("2026-10-31T15:43:19-03:00").toEpochMilliseconds()
		assertEquals("FEEA200A316AE59C1708", tx(CrpRequest.setTime(now, "America/Sao_Paulo")))
		// Os milissegundos são truncados.
		assertEquals("FEEA200A316AE59C1708", tx(CrpRequest.setTime(now + 999, "America/Sao_Paulo")))
		// Vetor da fase 0: 2026-10-31 12:00:00 local → 6A E5 67 C0.
		val noon = Instant.parse("2026-10-31T12:00:00-03:00").toEpochMilliseconds()
		assertEquals("FEEA200A316AE567C008", tx(CrpRequest.setTime(noon, "America/Sao_Paulo")))
		// condor-app-pair-sync.jsonl atMs 7047: −10800 s em int32 LE.
		assertEquals("FEEA200BBB0700D0D5FFFF", tx(CrpRequest.timeZone(-10_800)))
		assertEquals(-10_800, CrpTimeZoneCodec.offsetSeconds(now, "America/Sao_Paulo"))
	}

	@Test
	fun dataRequestsMatchTheCaptureAndTheSdk() {
		// condor-app-pair-sync.jsonl atMs 98761 (5A 01) e 56165, 60613, 58729, 61368 (59 00..03).
		assertEquals("FEEA20065A01", tx(CrpRequest.firmware()))
		assertEquals(
			listOf("FEEA20065900", "FEEA20065901", "FEEA20065902", "FEEA20065903"),
			(0..3).map { tx(CrpRequest.stepsPage(it)) },
		)
		// condor-app-pair-sync.jsonl atMs 56403 e 56631 (só catálogo: o P85 não responde).
		assertEquals("FEEA20065D00", tx(CrpRequest.distancePage(0)))
		assertEquals("FEEA20065C00", tx(CrpRequest.caloriesPage(0)))
		// condor-app-pair-sync.jsonl atMs 57313.
		assertEquals("FEEA200532", tx(CrpRequest.sleepToday()))
		// Fase 0 (sdk:f/p1.java:10-18; sdk:f/m1.java:20-35): o app de produção nunca mandou 0x33.
		assertEquals("FEEA20063301", tx(CrpRequest.legacyHistory(0x01)))
		assertEquals("FEEA20063303", tx(CrpRequest.legacyHistory(0x03)))
		assertEquals("FEEA20063304", tx(CrpRequest.legacyHistory(0x04)))
		// Fase 0 (sdk:f/r.java:7-9; sdk:f/x.java:21-25).
		assertEquals("FEEA2005F8", tx(CrpRequest.removeBond()))
		assertEquals("FEEA20065102", tx(CrpRequest.factoryReset()))
		// condor-app-pair-sync.jsonl atMs 144792 (24 h) e 162416 (12 h).
		assertEquals("FEEA20061701", tx(CrpRequest.timeFormat(twentyFourHours = true)))
		assertEquals("FEEA20061700", tx(CrpRequest.timeFormat(twentyFourHours = false)))
	}

	@Test
	fun captureRepliesDecode() {
		// condor-app-pair-sync.jsonl atMs 1596: 5A 00 "MOYOUNG-V2"; atMs 1535 é a leitura de 2A29 com o mesmo texto.
		val protocol = single("FEEA20105A004D4F594F554E472D5632")
		assertEquals(CrpCommand.PROTOCOL_INFO, protocol.cmd)
		assertEquals("MOYOUNG-V2", CrpText.decode(protocol.payloadFrom(1)))
		assertEquals(CrpProtocolVersion.V2, CrpProtocolVersion.parse(CrpText.decode(Hex.decode("4D4F594F554E472D5632"))))
		// atMs 98835: 5A 01 "MOY-VS02-2.0.0" (e a leitura de 2A28 em atMs 98758).
		val firmware = single("FEEA20145A014D4F592D565330322D322E302E30")
		assertEquals("MOY-VS02-2.0.0", CrpText.decode(firmware.payloadFrom(1)))
		// atMs 56234: 59 00 com 24 meias horas zeradas.
		val page = single("FEEA2036" + "5900" + "00".repeat(48))
		val decoded = assertNotNull(CrpCategoryPage.decodeSteps(page.payload))
		assertEquals(0, decoded.page)
		assertEquals(24, decoded.values.size)
		assertEquals(0L, decoded.values.sum())
		// atMs 57733: 32 sem payload = sem sono.
		val sleep = single("FEEA200532")
		assertEquals(CrpCommand.SLEEP_TODAY, sleep.cmd)
		assertEquals(emptyList(), CrpSleepPayload.decodeLegacy(sleep.payload))
		assertNull(CrpSleepAssembler.assemble(emptyList(), kotlinx.datetime.LocalDate(2026, 10, 31), "America/Sao_Paulo", true))
		// atMs 3172 e 7070: F9 01 01 00 → 01 quando o vínculo clássico fecha; atMs 7257: F9 02 01.
		assertEquals("classic=00", CrpClassicState.describe(single("FEEA2008F9010100").payload))
		assertEquals("classic=01", CrpClassicState.describe(single("FEEA2008F9010101").payload))
		assertEquals("sub=02 value=01", CrpClassicState.describe(single("FEEA2007F90201").payload))
		// FEE1 da caminhada (condor-app-reconnect-sync-walk.jsonl atMs 106148 e 136148) e 2A19 (atMs 48921, 99511).
		assertEquals(CrpStepsTotals(16, 13, 1, null), CrpStepsTotals.decode(Hex.decode("1000000D0000010000")))
		assertEquals(CrpStepsTotals(77, 63, 4, null), CrpStepsTotals.decode(Hex.decode("4D00003F0000040000")))
		assertEquals(CrpBatteryReading(0x32, 50, false), CrpBatteryReading.decode(Hex.decode("32")))
		assertEquals(CrpBatteryReading(0x31, 49, false), CrpBatteryReading.decode(Hex.decode("31")))
	}

	@Test
	fun phaseZeroSleepVectorsDecodeToTheSameRecords() {
		// Fase 0: resposta 0x32 sintética e a mesma noite no formato B6 01 00 (sdk:j/o0.java:24-133).
		val legacy = single("FEEA201132" + "011705021732" + "01010A00061E")
		val modern = single("FEEA200FB60100" + "7115" + "72C9" + "1128" + "6078")
		val expected = listOf(CrpSleepRecord(1, 23, 5), CrpSleepRecord(2, 23, 50), CrpSleepRecord(1, 1, 10), CrpSleepRecord(0, 6, 30))
		assertEquals(expected, CrpSleepPayload.decodeLegacy(legacy.payload))
		assertEquals(expected, CrpSleepPayload.decodeNew(modern.payloadFrom(2)))
	}

	@Test
	fun setTimePayloadRoundTripsToTheWallClock() {
		assertEquals(LocalDateTime(2026, 10, 31, 15, 43, 19), CrpTimeCodec.decodeWallClock(Hex.decode("6AE59C1708")))
	}

	private fun single(hex: String): CrpFrame {
		val frames = CrpReassembler { error("unexpected drop: $it") }.accept(Hex.decode(hex))
		assertEquals(1, frames.size, hex)
		return frames.single()
	}
}
