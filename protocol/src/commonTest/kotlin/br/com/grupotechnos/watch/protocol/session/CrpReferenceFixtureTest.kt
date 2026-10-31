package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.crp.CrpCategoryPage
import br.com.grupotechnos.watch.protocol.crp.CrpCommand
import br.com.grupotechnos.watch.protocol.crp.CrpFrame
import br.com.grupotechnos.watch.protocol.crp.CrpFrameCodec
import br.com.grupotechnos.watch.protocol.crp.CrpGatt
import br.com.grupotechnos.watch.protocol.crp.CrpBatteryReading
import br.com.grupotechnos.watch.protocol.crp.CrpReassembler
import br.com.grupotechnos.watch.protocol.crp.CrpRequest
import br.com.grupotechnos.watch.protocol.crp.CrpSleepAssembler
import br.com.grupotechnos.watch.protocol.crp.CrpSleepPayload
import br.com.grupotechnos.watch.protocol.crp.CrpStepsTotals
import br.com.grupotechnos.watch.protocol.crp.CrpText
import br.com.grupotechnos.watch.protocol.crp.CrpTimeCodec
import br.com.grupotechnos.watch.protocol.crp.CrpTimeZoneCodec
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.testing.Recording
import br.com.grupotechnos.watch.protocol.testing.RecordingLoader
import br.com.grupotechnos.watch.protocol.util.Hex
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodificação das fixtures de referência do Condor Reebok (`fixtures/reference/condorreebok/MOY-VS02-2.0.0/`),
 * geradas por `scripts/btsnoop2jsonl.py` da captura HCI do app Condor de produção. Elas não reproduzem contra a nossa
 * sessão (a sequência é a do app Condor); este teste confere que os nossos codecs leem os bytes reais e que a redação
 * foi feita.
 */
class CrpReferenceFixtureTest {
	private val pairSync = RecordingLoader.loadFixture("$DIR/condor-app-pair-sync.jsonl")
	private val btReconnect = RecordingLoader.loadFixture("$DIR/condor-app-bt-reconnect.jsonl")
	private val walk = RecordingLoader.loadFixture("$DIR/condor-app-reconnect-sync-walk.jsonl")
	private val all = listOf(pairSync, btReconnect, walk)

	@Test
	fun headersDescribeTheP85Connection() {
		for (recording in all) {
			assertEquals("P85", recording.header.device)
			assertEquals("MOY-VS02-2.0.0", recording.header.firmware)
			assertEquals("android-hci", recording.header.platform)
			assertEquals(517, recording.header.mtu)
			assertEquals("America/Sao_Paulo", recording.header.timeZone)
		}
		assertEquals("2026-10-31T15:43:12.209-03:00", pairSync.header.recordedAt)
	}

	@Test
	fun versionAndFirmwareAgreeBetweenReadsAndReplies() {
		for (recording in all) {
			assertEquals("MOYOUNG-V2", CrpText.decode(reads(recording, CrpGatt.PROTOCOL_VERSION).first()))
			val protocol = rxFrames(recording).first { it.isCmd(CrpCommand.PROTOCOL_INFO, CrpCommand.SUB_PROTOCOL_VERSION) }
			assertEquals("MOYOUNG-V2", CrpText.decode(protocol.payloadFrom(1)))
			assertEquals("MOY-VS02-2.0.0", CrpText.decode(reads(recording, CrpGatt.FIRMWARE_REVISION).first()))
			val firmware = rxFrames(recording).first { it.isCmd(CrpCommand.PROTOCOL_INFO, CrpCommand.SUB_FIRMWARE) }
			assertEquals("MOY-VS02-2.0.0", CrpText.decode(firmware.payloadFrom(1)))
		}
	}

	@Test
	fun stepsPagesAndSleepOfAnUnusedWatch() {
		val pages = rxFrames(pairSync).filter { it.cmd == CrpCommand.STEPS_HALF_HOURS }
		assertEquals(listOf(0, 2, 1, 3), pages.map { it.byteAt(0) })
		for (page in pages) {
			val decoded = assertNotNull(CrpCategoryPage.decodeSteps(page.payload))
			assertEquals(24, decoded.values.size)
			assertEquals(0L, decoded.values.sum())
		}
		// O P85 não respondeu a 5D nem a 5C (pedidos em atMs 56403, 56631, 58957 e 59184) nem a nenhum B6.
		val replied = rxFrames(pairSync).map { it.cmd }.toSet()
		assertTrue(CrpCommand.DISTANCE_HALF_HOURS !in replied && CrpCommand.CALORIES_HALF_HOURS !in replied && CrpCommand.HISTORY !in replied)
		assertTrue(txFrames(pairSync).any { it.cmd == CrpCommand.DISTANCE_HALF_HOURS } && txFrames(pairSync).any { it.cmd == CrpCommand.HISTORY })
		val sleep = rxFrames(pairSync).single { it.cmd == CrpCommand.SLEEP_TODAY }
		val records = assertNotNull(CrpSleepPayload.decodeLegacy(sleep.payload))
		assertNull(CrpSleepAssembler.assemble(records, LocalDate(2026, 10, 31), "America/Sao_Paulo", legacyHourWrap = true))
		assertEquals(CrpStepsTotals(0, 0, 0, null), CrpStepsTotals.decode(reads(pairSync, CrpGatt.STEPS_TODAY).single()))
	}

	@Test
	fun walkNotificationsGrowFrom16To77Steps() {
		val live = walk.packets.filter { it.direction == FrameDirection.RX && it.characteristic == CrpGatt.STEPS_TODAY }
			.mapNotNull { CrpStepsTotals.decode(it.value) }.filterNot { it.isZero }
		assertEquals(CrpStepsTotals(16, 13, 1, null), live.first())
		assertEquals(CrpStepsTotals(77, 63, 4, null), live.last())
		assertTrue(live.zipWithNext().all { (a, b) -> b.steps >= a.steps })
	}

	@Test
	fun batteryNotificationsWithoutCccd() {
		val pairBattery = pairSync.packets.filter { it.direction == FrameDirection.RX && it.characteristic == CrpGatt.BATTERY_LEVEL }
		assertTrue(pairBattery.isNotEmpty())
		assertTrue(pairBattery.all { CrpBatteryReading.decode(it.value) == CrpBatteryReading(0x32, 50, false) })
		val walkBattery = walk.packets.filter { it.direction == FrameDirection.RX && it.characteristic == CrpGatt.BATTERY_LEVEL }
			.mapNotNull { CrpBatteryReading.decode(it.value)?.percent }
		assertTrue(49 in walkBattery && 50 in walkBattery, walkBattery.toString())
		assertEquals(50, CrpBatteryReading.decode(reads(pairSync, CrpGatt.BATTERY_LEVEL).single())?.percent)
	}

	@Test
	fun everySetTimeIsTheWallClockOfItsInstant() {
		for (recording in all) {
			val times = recording.packets.filter { it.direction == FrameDirection.TX && isCmd(it.value, CrpCommand.SET_TIME) }
			assertTrue(times.isNotEmpty())
			for (packet in times) {
				val sentAt = recording.header.recordedAtEpochMillis + packet.atMs
				val local = CrpTimeCodec.decodeWallClock(packet.value.copyOfRange(CrpFrameCodec.HEADER_SIZE, packet.value.size))
				val encoded = TimeFormat.fromLocalWallClock(local, recording.header.timeZone)
				assertTrue(encoded in (sentAt - 2_000)..sentAt, "31 at atMs ${packet.atMs} encodes $local")
				assertEquals(0x08, packet.value.last().toInt())
			}
		}
	}

	@Test
	fun timeZoneAndProtocolQueryMatchOurEncoder() {
		val zone = Hex.encode(CrpFrameCodec.encode(CrpCommand.SETTINGS, CrpTimeZoneCodec.encode(
			CrpTimeZoneCodec.offsetSeconds(pairSync.header.recordedAtEpochMillis, "America/Sao_Paulo"),
		), 244))
		for (recording in all) {
			val sent = recording.packets.filter { it.direction == FrameDirection.TX && Hex.encode(it.value).startsWith("FEEA200BBB07") }
			assertTrue(sent.isNotEmpty())
			assertTrue(sent.all { Hex.encode(it.value) == zone })
		}
		// Conexão 17: o 5A 00 saiu com bloco 20 (byte2 0x10), igual ao nosso encoder com bloco 20.
		val first = walk.packets.first { it.direction == FrameDirection.TX }
		assertEquals(Hex.encode(CrpFrameCodec.encode(CrpRequest.protocolVersion().cmd, CrpRequest.protocolVersion().payload, 20)), Hex.encode(first.value))
		val pairFirst = pairSync.packets.first { it.direction == FrameDirection.TX }
		assertEquals("FEEA20065A00", Hex.encode(pairFirst.value))
	}

	@Test
	fun redactionRemovedTextFramesAndFakedTheProfile() {
		for (recording in all) {
			for (frame in txFrames(recording) + rxFrames(recording)) {
				assertTrue(frame.cmd !in setOf(0x41, 0x42, 0x43, 0xB5), "redacted cmd ${frame.cmd} in ${recording.source}")
				if (frame.cmd == 0x12) assertEquals("B24B1800", Hex.encode(frame.payload))
			}
		}
		assertTrue(txFrames(pairSync).any { it.cmd == 0x12 })
	}

	private fun reads(recording: Recording, characteristic: String): List<ByteArray> =
		recording.packets.filter { it.direction == FrameDirection.READ && it.characteristic == characteristic }.map { it.value }

	private fun txFrames(recording: Recording): List<CrpFrame> = frames(recording, FrameDirection.TX, Profiles.CRP_FEEA.write)

	private fun rxFrames(recording: Recording): List<CrpFrame> = frames(recording, FrameDirection.RX, Profiles.CRP_FEEA.notify)

	private fun frames(recording: Recording, direction: FrameDirection, characteristic: String): List<CrpFrame> {
		val reassembler = CrpReassembler { error("unexpected drop in ${recording.source}: $it") }
		return recording.packets.filter { it.direction == direction && it.characteristic == characteristic }.flatMap { reassembler.accept(it.value) }
	}

	private fun isCmd(packet: ByteArray, cmd: Int): Boolean =
		CrpFrameCodec.totalLength(packet) != null && (packet[4].toInt() and 0xFF) == cmd

	private companion object {
		const val DIR = "reference/condorreebok/MOY-VS02-2.0.0"
	}
}
