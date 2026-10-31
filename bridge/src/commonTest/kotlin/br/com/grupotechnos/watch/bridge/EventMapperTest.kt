package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.domain.Battery
import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.DeviceInfo
import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.SleepStage
import br.com.grupotechnos.watch.protocol.domain.SleepStageType
import br.com.grupotechnos.watch.protocol.domain.StepsDay
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.profile.Profiles
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Formatos de `src/types/events.ts` do Repo 2: nomes de campo, tipos e datas. Portado do `EventMapperTest` do host
 * Android, com as mesmas asserções; roda nos quatro targets.
 */
class EventMapperTest {
	private val saoPaulo = TimeZone.of("America/Sao_Paulo")

	private val info = DeviceInfo(
		profileId = "fitgps",
		address = "AA:BB:CC:DD:EE:FF",
		bleName = "Mormaii_fitGPS",
		firmware = "1.2.3",
		bleVersion = null,
	)

	@Test
	fun scannedDeviceUsesAppNameAsName() {
		val event = EventMapper.scannedDevice(Profiles.MORMAII_FIT_GPS, "AA:BB:CC:DD:EE:FF", -61, "Mormaii_fitGPS")
		assertEquals(
			mapOf(
				"name" to "fitGPS",
				"deviceId" to "AA:BB:CC:DD:EE:FF",
				"rssi" to -61,
				"bleName" to "Mormaii_fitGPS",
				"profileId" to "fitgps",
			),
			event,
		)
	}

	@Test
	fun connectStateChangedCarriesCodeSessionStateAndPhase() {
		assertEquals(
			mapOf("state" to 1, "sessionState" to "HANDSHAKING", "phase" to "BIND"),
			EventMapper.connectStateChanged(ConnectionState.HANDSHAKING, "BIND"),
		)
		assertEquals(
			mapOf("state" to 2, "sessionState" to "READY", "phase" to null),
			EventMapper.connectStateChanged(ConnectionState.READY, null),
		)
	}

	@Test
	fun deviceInfoAndFirmwareInfo() {
		assertEquals(
			mapOf(
				"deviceId" to "AA:BB:CC:DD:EE:FF",
				"profileId" to "fitgps",
				"bleName" to "Mormaii_fitGPS",
				"firmware" to "1.2.3",
				"bleVersion" to null,
			),
			EventMapper.deviceInfo(info),
		)
		assertEquals(
			mapOf("version" to "1.2.3", "braceletType" to 0, "platformCode" to 0, "deviceId" to "AA:BB:CC:DD:EE:FF"),
			EventMapper.firmwareInfo(info),
		)
	}

	@Test
	fun batteryKeepsLegacyStringField() {
		assertEquals(
			mapOf("percentage" to 85, "charging" to false, "battery" to "85%"),
			EventMapper.battery(Battery(85)),
		)
	}

	@Test
	fun batteryCarriesTheChargingFlag() {
		// CRP: 2A19 = 0x9B (155) é carregando com 55 % (regra do Core, CRPService.java:1814-1822; o driver já converte).
		assertEquals(
			mapOf("percentage" to 55, "charging" to true, "battery" to "55%"),
			EventMapper.battery(Battery(55, charging = true)),
		)
		assertEquals(false, EventMapper.battery(Battery(49, charging = false))["charging"])
	}

	@Test
	fun pedometerAndHistoryPedometer() {
		val day = StepsDay("2026-10-28", "2026-10-28T00:00:00-03:00", steps = 4321, calories = 150, distanceMeters = 3012.5)
		assertEquals(
			mapOf("pedometer" to 4321, "calorie" to 150, "distance" to 3012.5, "date" to "2026-10-28T00:00:00-03:00"),
			EventMapper.pedometer(day),
		)
		assertEquals(
			mapOf(
				"historypedometer" to listOf(
					mapOf("date" to "2026-10-28T00:00:00-03:00", "distance" to 3012.5, "calorie" to 150, "pedometer" to 4321),
				),
			),
			EventMapper.historyPedometer(listOf(day)),
		)
		assertEquals(mapOf("historypedometer" to emptyList<Any?>()), EventMapper.historyPedometer(emptyList()))
	}

	@Test
	fun historySleepUsesStartOfWakeUpDayAndEmptyRespiratoryRate() {
		val stage = SleepStage(
			type = SleepStageType.DEEP,
			startEpochMillis = 0,
			endEpochMillis = 0,
			startIso = "2026-10-28T01:00:00-03:00",
			endIso = "2026-10-28T02:30:00-03:00",
		)
		val session = SleepSession(
			dayKey = "2026-10-28",
			startEpochMillis = 0,
			endEpochMillis = 0,
			startIso = "2026-10-27T23:10:00-03:00",
			endIso = "2026-10-28T06:40:00-03:00",
			stages = listOf(stage),
			totalMinutes = 450,
			lightMinutes = 300,
			deepMinutes = 120,
			remMinutes = 0,
			awakeMinutes = 30,
		)
		val event = EventMapper.historySleep(listOf(session), saoPaulo)
		assertEquals(
			mapOf(
				"historysleep" to listOf(
					mapOf(
						"date" to "2026-10-28T00:00:00-03:00",
						"sleep" to mapOf(
							"start" to "2026-10-27T23:10:00-03:00",
							"end" to "2026-10-28T06:40:00-03:00",
							"items" to listOf(
								mapOf("typeItem" to 3, "startItem" to "2026-10-28T01:00:00-03:00", "endItem" to "2026-10-28T02:30:00-03:00"),
							),
							"respiratoryRateItems" to emptyList<Any?>(),
							"totalSleepMinutes" to 450,
							"lightSleepMinutes" to 300,
							"deepSleepMinutes" to 120,
							"remSleepMinutes" to 0,
							"wakeUpMinutes" to 30,
						),
					),
				),
			),
			event,
		)
		// A sobrecarga pública usa o fuso do sistema, como o ZoneId.systemDefault() do host antigo.
		assertEquals(
			EventMapper.historySleep(listOf(session), TimeZone.currentSystemDefault()),
			EventMapper.historySleep(listOf(session)),
		)
	}

	@Test
	fun syncErrorAndLogShapes() {
		assertEquals(emptyMap(), EventMapper.empty())
		assertEquals(mapOf("progress" to 50), EventMapper.syncProgress(50))
		assertEquals(mapOf("progress" to 100), EventMapper.syncProgress(140))
		assertEquals(mapOf("progress" to 0), EventMapper.syncProgress(-3))
		assertEquals(
			mapOf("requestId" to 7, "code" to "TIMEOUT", "message" to "no ack", "method" to "getBatteryStatus"),
			EventMapper.error(7, "TIMEOUT", "no ack", "getBatteryStatus"),
		)
		assertEquals(
			mapOf(
				"ts" to 1_793_208_600_000.0,
				"level" to "DEBUG",
				"tag" to "Frame/6e400002",
				"message" to "seq=1 03/01 BIND len=45",
				"dir" to "TX",
				"hex" to "AB00",
			),
			EventMapper.log(1_793_208_600_000L, LogLevel.DEBUG, "Frame/6e400002", "seq=1 03/01 BIND len=45", "TX", "AB00"),
		)
		assertEquals(
			mapOf("deviceId" to "AA:BB:CC:DD:EE:FF", "name" to "fitGPS", "profileId" to "fitgps"),
			EventMapper.lastPairedDevice("AA:BB:CC:DD:EE:FF", "fitGPS", "fitgps"),
		)
	}

	@Test
	fun valueTypesAreTheOnesTheWritableConverterExpects() {
		// Int para inteiros, Double para decimais e epoch millis, Boolean, String e null: o WritableConverter não mudou.
		val battery = EventMapper.battery(Battery(85))
		assertTrue(battery["percentage"] is Int)
		assertTrue(battery["charging"] is Boolean)
		val log = EventMapper.log(1L, LogLevel.INFO, "t", "m", null, null)
		assertTrue(log["ts"] is Double)
		val day = EventMapper.pedometerDay(StepsDay("2026-10-28", "2026-10-28T00:00:00-03:00", 1, 2, 3.0))
		assertTrue(day["distance"] is Double)
		assertTrue(day["pedometer"] is Int)
	}

	@Test
	fun startOfDayIsoFollowsTheZoneOffset() {
		assertEquals("2026-10-28T00:00:00-03:00", EventMapper.startOfDayIso("2026-10-28", saoPaulo))
		assertEquals("2026-10-28T00:00:00Z", EventMapper.startOfDayIso("2026-10-28", TimeZone.of("UTC")))
		assertEquals("2026-07-01T00:00:00+02:00", EventMapper.startOfDayIso("2026-07-01", TimeZone.of("Europe/Berlin")))
	}

	@Test
	fun startOfDayIsoMatchesJavaTimeOnDstAndOddOffsets() {
		// Valores do java.time (LocalDate.atStartOfDay(zone).format("yyyy-MM-dd'T'HH:mm:ssXXX")), o host Android antigo.
		val expected = listOf(
			// Meia-noite que não existiu: o início é o fim do buraco, com o offset de depois.
			Triple("2018-11-04", "America/Sao_Paulo", "2018-11-04T01:00:00-02:00"),
			// Fim do horário de verão: a volta foi das 00:00 do dia 17 para as 23:00 do dia 16; a meia-noite do 17 já é -03:00.
			Triple("2019-02-17", "America/Sao_Paulo", "2019-02-17T00:00:00-03:00"),
			Triple("2026-10-28", "Asia/Kolkata", "2026-10-28T00:00:00+05:30"),
			Triple("2026-10-28", "Pacific/Chatham", "2026-10-28T00:00:00+13:45"),
			Triple("2026-07-01", "Pacific/Chatham", "2026-07-01T00:00:00+12:45"),
			Triple("2026-10-28", "America/St_Johns", "2026-10-28T00:00:00-02:30"),
			Triple("2026-03-08", "America/St_Johns", "2026-03-08T00:00:00-03:30"),
			Triple("2026-01-15", "Europe/Lisbon", "2026-01-15T00:00:00Z"),
			Triple("2026-07-15", "Europe/Lisbon", "2026-07-15T00:00:00+01:00"),
			Triple("2026-10-28", "America/Noronha", "2026-10-28T00:00:00-02:00"),
			Triple("2026-10-28", "America/Manaus", "2026-10-28T00:00:00-04:00"),
		)
		for ((day, zone, iso) in expected) {
			assertEquals(iso, EventMapper.startOfDayIso(day, TimeZone.of(zone)), "$day in $zone")
		}
	}

	@Test
	fun startOfDayIsoRejectsWhatLocalDateParseRejects() {
		for (bad in listOf("2026-02-30", "2026-1-28", "20261028", "2026-10-28T00:00", " 2026-10-28", "")) {
			assertFailsWith<IllegalArgumentException>(bad) { EventMapper.startOfDayIso(bad, saoPaulo) }
		}
	}
}
