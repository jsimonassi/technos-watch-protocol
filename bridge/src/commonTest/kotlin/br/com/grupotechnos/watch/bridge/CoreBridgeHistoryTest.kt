package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.SleepStage
import br.com.grupotechnos.watch.protocol.domain.SleepStageType
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.session.InMemorySampleStore
import br.com.grupotechnos.watch.protocol.session.SessionHarness.Companion.oct
import br.com.grupotechnos.watch.protocol.session.SessionHarness.Companion.sportPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Histórico local pelo [CoreBridge], contra o host Android antigo (`getHistoryPedometer`, `getHistorySleep` com a regra
 * do `NO_DATA`, `sleepHistoryDays`, `deletedSavedData` com `readSavedItem`/`readSavedMap`).
 */
class CoreBridgeHistoryTest {
	private fun stored(dayKey: String): SleepSession = SleepSession(
		dayKey = dayKey,
		startEpochMillis = 1_793_143_800_000,
		endEpochMillis = 1_793_170_800_000,
		startIso = "2026-10-27T23:30:00-03:00",
		endIso = "2026-10-28T07:00:00-03:00",
		stages = listOf(
			SleepStage(SleepStageType.LIGHT, 1_793_143_800_000, 1_793_151_000_000, "2026-10-27T23:30:00-03:00", "2026-10-28T01:30:00-03:00"),
			SleepStage(SleepStageType.DEEP, 1_793_151_000_000, 1_793_170_800_000, "2026-10-28T01:30:00-03:00", "2026-10-28T07:00:00-03:00"),
		),
		totalMinutes = 450,
		lightMinutes = 120,
		deepMinutes = 330,
		remMinutes = 0,
		awakeMinutes = 0,
	)

	@Test
	fun historyPedometerEmitsTheUnconfirmedDaysBeforeTheResolve() = runBridge { b ->
		b.pairReady()
		b.h.sportPages += sportPage(Triple(oct(27, 9), 0, 500), Triple(oct(28, 10), 0, 1_000))
		b.bridge.refreshSteps(b.completion("refreshSteps"))
		b.run()
		b.journal.clear()
		b.bridge.getHistoryPedometer(7.9, b.completion("getHistoryPedometer"))
		assertEquals(2, b.journal.lines.size)
		val event = b.journal.lines[0]
		assertTrue(event.startsWith("""event onHistoryPedometer {"historypedometer":[{"date":"2026-10-27T00:00:00-03:00","distance":"""), event)
		assertTrue(event.contains(""""pedometer":500},{"date":"2026-10-28T00:00:00-03:00","distance":"""), event)
		assertTrue(event.endsWith(""""pedometer":1000}]}"""), event)
		assertEquals("resolve getHistoryPedometer null", b.journal.lines[1])
		b.journal.clear()
		b.bridge.deletedSavedData(listOf("2026-10-27T09:00:00-03:00"), 5.0)
		b.bridge.getHistoryPedometer(-3.5, b.completion("getHistoryPedometer"))
		assertTrue(b.journal.lines[0].startsWith("""event onHistoryPedometer {"historypedometer":[{"date":"2026-10-28T00:00:00-03:00""""), b.journal.lines[0])
	}

	@Test
	fun emptyPedometerHistoryIsStillEmitted() = runBridge { b ->
		b.bridge.getHistoryPedometer(3.0, b.completion("getHistoryPedometer"))
		assertEquals(listOf("""event onHistoryPedometer {"historypedometer":[]}""", "resolve getHistoryPedometer null"), b.journal.lines)
	}

	@Test
	fun unreadableHistoryIsAnIoErrorWithTheExceptionMessage() = runBridge { b ->
		b.h.clock.zone = "Not/AZone"
		val expected = try {
			b.h.session.getStepsDays(3)
			fail("the session should throw with an invalid zone")
		} catch (error: RuntimeException) {
			error.message ?: error::class.simpleName
		}
		b.bridge.getHistoryPedometer(3.0, b.completion("getHistoryPedometer"))
		b.bridge.getHistorySleep(b.completion("getHistorySleep"))
		assertEquals(
			listOf(
				"""event onError {"requestId":-1,"code":"IO_ERROR","message":"$expected","method":"getHistoryPedometer"}""",
				"reject getHistoryPedometer IO_ERROR $expected",
				"""event onError {"requestId":-1,"code":"IO_ERROR","message":"$expected","method":"getHistorySleep"}""",
				"reject getHistorySleep IO_ERROR $expected",
			),
			b.journal.lines,
		)
	}

	@Test
	fun sleepWithoutAnyNightIsNoDataEventAndRejection() = runBridge { b ->
		val completion = b.completion("getHistorySleep")
		b.bridge.getHistorySleep(completion)
		assertEquals(
			listOf(
				"""event onError {"requestId":-1,"code":"NO_DATA","message":"No sleep sessions stored","method":"getHistorySleep"}""",
				"reject getHistorySleep NO_DATA No sleep sessions stored",
			),
			b.journal.lines,
		)
		assertEquals("NO_DATA", completion.code)
	}

	@Test
	fun sleepNightsAreEmittedWithTheStartOfTheWakeUpDayBeforeTheResolve() = runBridge(InMemorySampleStore().apply { saveSleep(listOf(stored("2026-10-28"))) }) { b ->
		b.bridge.getHistorySleep(b.completion("getHistorySleep"))
		assertEquals(
			listOf(
				"""event onHistorySleep {"historysleep":[{"date":"2026-10-28T00:00:00-03:00","sleep":{"start":"2026-10-27T23:30:00-03:00","end":"2026-10-28T07:00:00-03:00","items":[{"typeItem":2,"startItem":"2026-10-27T23:30:00-03:00","endItem":"2026-10-28T01:30:00-03:00"},{"typeItem":3,"startItem":"2026-10-28T01:30:00-03:00","endItem":"2026-10-28T07:00:00-03:00"}],"respiratoryRateItems":[],"totalSleepMinutes":450,"lightSleepMinutes":120,"deepSleepMinutes":330,"remSleepMinutes":0,"wakeUpMinutes":0}}]}""",
				"resolve getHistorySleep null",
			),
			b.journal.lines,
		)
	}

	@Test
	fun confirmedNightsTurnIntoNoData() = runBridge(InMemorySampleStore().apply { saveSleep(listOf(stored("2026-10-28"))) }) { b ->
		b.bridge.deletedSavedData(listOf(mapOf("registered_at" to "2026-10-28T07:00:00-03:00", "category" to 0.0)), 5.0)
		b.bridge.getHistorySleep(b.completion("getHistorySleep"))
		assertEquals("reject getHistorySleep NO_DATA No sleep sessions stored", b.journal.lines.last())
	}

	@Test
	fun sleepWindowIsTheRetentionOfTheSessionOrSavedProfile() = runBridge { b ->
		assertEquals(30, b.bridge.sleepHistoryDays(), "no profile at all")
		b.host.pairing = CorePairing(ADDRESS, "unknown", "")
		assertEquals(30, b.bridge.sleepHistoryDays(), "unknown profile")
		b.host.pairing = CorePairing(ADDRESS, "fitgps", "fitGPS")
		assertEquals(30, b.bridge.sleepHistoryDays())
		b.host.pairing = null
		b.pairReady()
		b.host.queries.clear()
		assertEquals(30, b.bridge.sleepHistoryDays())
		assertTrue("loadPairing" !in b.host.queries, "the session profile comes first")
	}

	@Test
	fun failingToReadTheSavedPairingInTheSleepWindowIsAnIoError() = runBridge { b ->
		b.host.loadPairingError = IllegalStateException("prefs unavailable")
		b.bridge.getHistorySleep(b.completion("getHistorySleep"))
		assertEquals(
			listOf(
				"""event onError {"requestId":-1,"code":"IO_ERROR","message":"prefs unavailable","method":"getHistorySleep"}""",
				"reject getHistorySleep IO_ERROR prefs unavailable",
			),
			b.journal.lines,
		)
	}

	@Test
	fun deletedSavedDataReadsStringsAndMapsAndSkipsWhatItCannotRead() = runBridge { b ->
		val items = listOf(
			mapOf("registered_at" to "2026-10-26T12:00:00-03:00", "category" to 0.0),
			mapOf("registered_at" to "2026-10-25"),
			mapOf("category" to 5.0),
			mapOf("registered_at" to 123.0, "category" to 5.0),
			null,
			42.0,
			true,
			listOf("2026-10-24"),
			mapOf("registered_at" to "2026-10-24T08:00:00-0300", "category" to true),
			mapOf("registered_at" to "garbage", "category" to 5.0),
			mapOf("registered_at" to "2026-10-23", "category" to 7.0),
			mapOf("registered_at" to "2026-10-28T02:30:00.000Z", "category" to null),
			"2026-10-22",
		)
		b.bridge.deletedSavedData(items, 5.9)
		b.run()
		assertEquals(
			listOf(
				Triple(LogLevel.WARN, "CoreBridge/deletedSavedData", "Ignoring category 7"),
				Triple(LogLevel.INFO, "CoreBridge/deletedSavedData", "kind=SLEEP dayKeys=[2026-10-26]"),
				Triple(LogLevel.WARN, "CoreBridge/deletedSavedData", "Unreadable dates: [garbage]"),
				Triple(LogLevel.INFO, "CoreBridge/deletedSavedData", "kind=STEPS dayKeys=[2026-10-25, 2026-10-24, 2026-10-27, 2026-10-22]"),
			),
			b.logger.messages.filter { it.second == "CoreBridge/deletedSavedData" },
		)
		assertEquals(listOf("2026-10-26"), b.h.store.loadConfirmedDays(DataKind.SLEEP))
		assertEquals(listOf("2026-10-22", "2026-10-24", "2026-10-25", "2026-10-27"), b.h.store.loadConfirmedDays(DataKind.STEPS).sorted())
		assertTrue(b.journal.lines.isEmpty(), "no event and no promise")
	}

	@Test
	fun deletedSavedDataWithNothingReadableConfirmsNothing() = runBridge { b ->
		b.bridge.deletedSavedData(listOf("garbage", "2026-13-01"), 5.0)
		b.bridge.deletedSavedData(listOf("2026-10-27"), 3.0)
		b.bridge.deletedSavedData(emptyList(), 5.0)
		b.run()
		assertEquals(
			listOf(
				"Unreadable dates: [garbage, 2026-13-01]",
				"kind=STEPS dayKeys=[]",
				"Ignoring category 3",
			),
			b.logger.withTag("CoreBridge/deletedSavedData"),
		)
		assertTrue(b.h.store.loadConfirmedDays(DataKind.STEPS).isEmpty())
	}
}
