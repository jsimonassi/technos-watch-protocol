package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.Battery
import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.DeviceInfo
import br.com.grupotechnos.watch.protocol.domain.StepsDay
import br.com.grupotechnos.watch.protocol.domain.WatchErrorCodes
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.testing.FakeTransport
import br.com.grupotechnos.watch.protocol.testing.IdoWatchSimulator
import br.com.grupotechnos.watch.protocol.testing.IdoWatchSimulator.BindBehavior
import br.com.grupotechnos.watch.protocol.testing.RecordingListener
import br.com.grupotechnos.watch.protocol.testing.SessionEvent
import br.com.grupotechnos.watch.protocol.testing.SessionTestLogger
import br.com.grupotechnos.watch.protocol.testing.TestClock
import br.com.grupotechnos.watch.protocol.transport.WriteType
import br.com.grupotechnos.watch.protocol.util.Hex
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LIFE_ULTRA_GPS = "lifeultragps"

/** "Agora" dos testes iDO: o instante do acerto de hora do log de produção (`20261029.log` linha 5360). */
private const val IDO_NOW_ISO = "2026-10-29T22:08:49-03:00"

/**
 * Uma `WatchSession` real sobre o [FakeTransport] iDO e o [IdoWatchSimulator], no tempo virtual do teste. A confirmação
 * do vínculo na tela do relógio é agendada no `backgroundScope` do teste, então avança com o tempo virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class IdoHarness(val scope: TestScope, val clock: TestClock = TestClock.at(IDO_NOW_ISO)) {
	val store = InMemorySampleStore()
	val transport: FakeTransport
	val watch: IdoWatchSimulator
	val listener = RecordingListener()
	val logger = SessionTestLogger()
	val session: WatchSession

	init {
		val (fake, simulator) = FakeTransport.scriptedIdo { delayMs, action ->
			scope.backgroundScope.launch {
				delay(delayMs)
				action()
			}
		}
		transport = fake
		watch = simulator
		session = WatchSession(transport, logger, store, clock, StandardTestDispatcher(scope.testScheduler))
		transport.events = session.events
		session.setListener(listener)
	}

	fun run() = scope.runCurrent()

	fun advance(ms: Long) {
		scope.advanceTimeBy(ms)
		scope.runCurrent()
	}

	/** `pair` com a confirmação na tela, até READY, mais o acerto de hora automático. */
	fun pairReady(requestId: Int = 1) {
		session.pair(requestId, ADDRESS, LIFE_ULTRA_GPS)
		run()
		advance(watch.confirmDelayMs + 1)
		assertEquals(ConnectionState.READY, session.state, "states: ${listener.stateTrail()}")
		advance(POST_LOGIN_MS)
	}

	fun at(local: LocalDateTime): Long = TimeFormat.fromLocalWallClock(local, clock.zone)

	fun assertNoUnexpectedErrors() {
		assertTrue(logger.unexpected().isEmpty(), "unexpected errors: ${logger.unexpected()}")
	}

	companion object {
		/** `postLoginDelayMs` do Life Ultra GPS + 1 ms. */
		const val POST_LOGIN_MS: Long = 1_001
	}
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun runIdo(body: suspend TestScope.(IdoHarness) -> Unit): TestResult = runTest {
	val harness = IdoHarness(this)
	try {
		body(harness)
	} finally {
		harness.session.close()
		runCurrent()
	}
}

/**
 * O Life Ultra GPS pela `WatchSession`, com o relógio simulado em nível de byte (`docs/protocolo-ido.md`): pareamento
 * com confirmação na tela, reconexão sem autenticação, comandos, passos do dia e esquecer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdoSessionTest {
	@Test
	fun pairEnablesBothNotificationsThenBindsWithConfirmationOnTheWatch() = runIdo { h ->
		h.session.pair(1, ADDRESS, LIFE_ULTRA_GPS)
		h.run()
		assertEquals(ConnectionState.HANDSHAKING, h.session.state)
		assertEquals("HANDSHAKING[BIND]", h.listener.stateTrail().last())
		assertTrue(h.listener.terminals(1).isEmpty(), "waits for the tap on the watch")
		assertEquals(listOf("02/01", "04/01", "04/05"), h.watch.receivedLabels())
		assertEquals("0C00E03E1C85037CD182DFD13C60", Hex.encode(h.watch.authReplies.single()))

		h.advance(h.watch.confirmDelayMs + 1)
		assertEquals(SessionEvent.Completed(1), h.listener.terminal(1))
		assertEquals(
			listOf("CONNECTING", "CONFIGURING", "HANDSHAKING", "HANDSHAKING[BIND]", "READY"),
			h.listener.stateTrail(),
		)
		val gattCalls = h.transport.calls.filter { it.startsWith("enableNotifications") || it.startsWith("requestMtu") }
		assertEquals(
			listOf(
				"enableNotifications(${Profiles.IDO_MAIN.notify})",
				"enableNotifications(${Profiles.IDO_MAIN.extraNotify.single()})",
			),
			gattCalls,
			"CCCD 0AF7 then 0AF2, no MTU request",
		)

		h.advance(IdoHarness.POST_LOGIN_MS)
		assertEquals("0301EA070A1D1608310300000000DC05", Hex.encode(h.watch.receivedOf("03/01").single()), "automatic set time")
		assertTrue(h.transport.writeTypes.all { it == WriteType.WITHOUT_RESPONSE }, "writes: ${h.transport.writeTypes}")
		assertTrue(h.transport.writes.all { it.size <= 20 })
		h.assertNoUnexpectedErrors()
	}

	@Test
	fun rejectionOnTheWatchFailsThePairing() = runIdo { h ->
		h.watch.bindBehavior = BindBehavior.REJECT
		h.session.pair(1, ADDRESS, LIFE_ULTRA_GPS)
		h.run()
		h.advance(h.watch.confirmDelayMs + 1)
		assertEquals(WatchErrorCodes.HANDSHAKE_FAILED, h.listener.errorCode(1))
		assertEquals(ConnectionState.DISCONNECTED, h.session.state)
		assertEquals("disconnect", h.transport.calls.last(), "the link is closed: the watch takes no second bind on it")
	}

	@Test
	fun noTapOnTheWatchTimesOutAfterTheBindTimeout() = runIdo { h ->
		h.watch.bindBehavior = BindBehavior.NO_ANSWER
		h.session.pair(1, ADDRESS, LIFE_ULTRA_GPS)
		h.run()
		h.advance(Profiles.MORMAII_LIFE_ULTRA_GPS.quirks.bindTimeoutMs - 1_000)
		assertTrue(h.listener.terminals(1).isEmpty(), "still waiting for the confirmation")
		h.advance(1_001)
		assertEquals(WatchErrorCodes.HANDSHAKE_FAILED, h.listener.errorCode(1))
		assertEquals(1, h.watch.receivedOf("04/05").size, "the auth reply is never resent")
	}

	@Test
	fun alreadyBoundWatchPairsWithoutAuth() = runIdo { h ->
		h.watch.bindBehavior = BindBehavior.ALREADY_BOUND
		h.session.pair(1, ADDRESS, LIFE_ULTRA_GPS)
		h.run()
		assertEquals(SessionEvent.Completed(1), h.listener.terminal(1))
		assertEquals(listOf("02/01", "04/01"), h.watch.receivedLabels())
	}

	@Test
	fun unansweredBindIsNeverResent() = runIdo { h ->
		h.watch.silent += "04/01"
		h.session.pair(1, ADDRESS, LIFE_ULTRA_GPS)
		h.run()
		h.advance(Profiles.MORMAII_LIFE_ULTRA_GPS.quirks.bindTimeoutMs - 1_000)
		assertTrue(h.listener.terminals(1).isEmpty(), "still waiting for the bind reply")
		h.advance(1_001)
		assertEquals(WatchErrorCodes.HANDSHAKE_FAILED, h.listener.errorCode(1))
		assertEquals(1, h.watch.receivedOf("04/01").size, "resending 04/01 would restart the bind")
		assertTrue(h.watch.receivedOf("04/05").isEmpty())
	}

	@Test
	fun refusedBindFailsWithoutAuth() = runIdo { h ->
		h.watch.bindBehavior = BindBehavior.REFUSE
		h.session.pair(1, ADDRESS, LIFE_ULTRA_GPS)
		h.run()
		assertEquals(WatchErrorCodes.HANDSHAKE_FAILED, h.listener.errorCode(1))
		assertTrue(h.watch.receivedOf("04/05").isEmpty())
	}

	@Test
	fun bindWithoutChallengeIsDone() = runIdo { h ->
		h.watch.bindBehavior = BindBehavior.NO_AUTH
		h.session.pair(1, ADDRESS, LIFE_ULTRA_GPS)
		h.run()
		assertEquals(SessionEvent.Completed(1), h.listener.terminal(1))
		assertTrue(h.watch.receivedOf("04/05").isEmpty())
	}

	@Test
	fun reconnectSendsNoAuth() = runIdo { h ->
		h.session.reconnect(1, ADDRESS, LIFE_ULTRA_GPS)
		h.run()
		assertEquals(SessionEvent.Completed(1), h.listener.terminal(1))
		assertEquals(listOf("RECONNECTING", "CONFIGURING", "HANDSHAKING", "HANDSHAKING[LOGIN]", "READY"), h.listener.stateTrail())
		h.advance(IdoHarness.POST_LOGIN_MS)
		assertEquals(listOf("02/01", "03/01"), h.watch.receivedLabels())
		h.assertNoUnexpectedErrors()
	}

	@Test
	fun reconnectToAWatchThatDroppedTheBondStops() = runIdo { h ->
		h.watch.pairFlag = 0
		h.session.reconnect(1, ADDRESS, LIFE_ULTRA_GPS)
		h.run()
		assertEquals(WatchErrorCodes.HANDSHAKE_FAILED, h.listener.errorCode(1))
		assertEquals(ConnectionState.DISCONNECTED, h.session.state)
		val connects = h.transport.connects.size
		h.advance(120_000)
		assertEquals(connects, h.transport.connects.size, "no automatic retry after the watch dropped the bond")
		assertTrue(h.watch.receivedOf("04/01").isEmpty(), "a reconnect never binds")
	}

	@Test
	fun commandsReturnTheWatchValuesWithTotalCalories() = runIdo { h ->
		h.pairReady()
		h.session.readBattery(2)
		h.session.readDeviceInfo(3)
		h.session.setTime(4)
		h.session.refreshSteps(5)
		h.run()
		assertEquals(SessionEvent.BatteryLevel(2, Battery(14)), h.listener.terminal(2))
		assertEquals(SessionEvent.Info(3, DeviceInfo(LIFE_ULTRA_GPS, ADDRESS, "Life Ultra GPS", "1.1.1", null)), h.listener.terminal(3))
		assertEquals(SessionEvent.Completed(4), h.listener.terminal(4))
		// Passos e distância são os da tela (26 e 0,02 milha); as 13 kcal são as totais do 02/A0 (repouso + atividade),
		// e a tela mostrava 0, a de atividade (linhas 3891 e 3893 do log; IdoLiveData).
		val today = StepsDay("2026-10-29", "2026-10-29T00:00:00-03:00", steps = 26, calories = 13, distanceMeters = 25.0)
		assertEquals(SessionEvent.LiveSteps(5, today), h.listener.terminal(5))

		h.session.syncHistory(6, emptyList())
		h.run()
		val sync = h.listener.events.filter {
			(it is SessionEvent.SyncStarted && it.requestId == 6) || (it is SessionEvent.SyncProgress && it.requestId == 6) ||
				(it is SessionEvent.SyncFinished && it.requestId == 6)
		}
		assertEquals(
			listOf(SessionEvent.SyncStarted(6), SessionEvent.SyncProgress(6, 0), SessionEvent.SyncProgress(6, 50), SessionEvent.SyncProgress(6, 100), SessionEvent.SyncFinished(6)),
			sync,
		)
		assertEquals(listOf(today), h.session.getStepsDays(3))
		assertEquals(emptyList(), h.session.getSleepSessions(3))
		assertEquals(
			listOf("02/01", "04/01", "04/05", "03/01", "02/01", "02/01", "02/EB", "03/01", "02/A0", "02/A0"),
			h.watch.receivedLabels(),
		)
		h.assertNoUnexpectedErrors()
	}

	@Test
	fun repliesArrivingBeforeTheWriteConfirmationAreKept() = runIdo { h ->
		h.transport.replyBeforeWriteCompleted = true
		h.pairReady()
		h.session.readBattery(2)
		h.session.readDeviceInfo(3)
		h.session.refreshSteps(4)
		h.run()
		assertEquals(SessionEvent.BatteryLevel(2, Battery(14)), h.listener.terminal(2))
		assertEquals("1.1.1", (h.listener.terminal(3) as SessionEvent.Info).info.firmware)
		assertEquals(26, (h.listener.terminal(4) as SessionEvent.LiveSteps).today.steps)
		assertEquals(
			listOf("02/01", "04/01", "04/05", "03/01", "02/01", "02/01", "02/EB", "02/A0"),
			h.watch.receivedLabels(),
			"every command answered on the first try",
		)
		assertTrue(h.logger.withTag("IdoDriver/request").isEmpty(), "no retry: ${h.logger.withTag("IdoDriver/request")}")
		h.assertNoUnexpectedErrors()
	}

	@Test
	fun refusedSetTimeIsAProtocolError() = runIdo { h ->
		h.pairReady()
		h.watch.setTimeStatus = 1
		h.session.setTime(2)
		h.run()
		assertEquals(WatchErrorCodes.PROTOCOL, h.listener.errorCode(2))
		assertEquals(ConnectionState.READY, h.session.state)
		assertEquals(2, h.watch.receivedOf("03/01").size, "a refusal is an answer: no retry")
	}

	@Test
	fun stepsAreTheDayTotalOfTheLatestRead() = runIdo { h ->
		h.pairReady()
		h.session.refreshSteps(2)
		h.run()
		h.watch.steps = 40
		h.clock.nowEpochMillis = h.at(LocalDateTime(2026, 10, 29, 23, 10, 0))
		h.session.refreshSteps(3)
		h.run()
		h.watch.steps = 45
		h.clock.nowEpochMillis = h.at(LocalDateTime(2026, 10, 29, 23, 40, 0))
		h.session.syncHistory(4, listOf(DataKind.STEPS))
		h.run()
		assertEquals(45, h.session.getStepsDays(3).single().steps)
		assertEquals(
			listOf(h.at(LocalDateTime(2026, 10, 29, 22, 0, 0)), h.at(LocalDateTime(2026, 10, 29, 23, 0, 0))),
			h.store.loadSteps().map { it.atEpochMillis },
			"one sample per hour; a later read in the same hour replaces it",
		)
		h.watch.steps = 3
		h.clock.nowEpochMillis = h.at(LocalDateTime(2026, 10, 30, 0, 5, 0))
		h.session.refreshSteps(5)
		h.run()
		assertEquals(listOf("2026-10-29" to 45, "2026-10-30" to 3), h.session.getStepsDays(3).map { it.dayKey to it.steps })
	}

	@Test
	fun paddedRepliesAndInterleavedNoiseAreHandled() = runIdo { h ->
		h.watch.trimReplies = true
		h.watch.noise = true
		h.pairReady()
		h.session.readBattery(2)
		h.session.readDeviceInfo(3)
		h.session.refreshSteps(4)
		h.run()
		assertEquals(SessionEvent.BatteryLevel(2, Battery(14)), h.listener.terminal(2))
		assertEquals("1.1.1", (h.listener.terminal(3) as SessionEvent.Info).info.firmware)
		assertEquals(26, (h.listener.terminal(4) as SessionEvent.LiveSteps).today.steps)
		assertTrue(h.logger.withTag("IdoDriver/receive").any { it.startsWith("V3 packet ignored") })
		assertTrue(h.logger.withTag("IdoDriver/receive").any { it.startsWith("unsolicited 07/40") })
		assertTrue(h.logger.withTag("IdoDriver/receive").any { it.startsWith("unsolicited 02/5F") }, "same cmd, other key")
		h.assertNoUnexpectedErrors()
	}

	@Test
	fun repliesOnTheHealthCharacteristicReachTheDriver() = runIdo { h ->
		h.watch.replyCharacteristic = Profiles.IDO_MAIN.extraNotify.single()
		h.pairReady()
		h.session.readBattery(2)
		h.run()
		assertEquals(SessionEvent.BatteryLevel(2, Battery(14)), h.listener.terminal(2))
	}

	@Test
	fun missingHealthCharacteristicIsSkipped() = runIdo { h ->
		h.transport.services = listOf(FakeTransport.serviceOf(Profiles.IDO_MAIN.copy(extraNotify = emptyList())))
		h.pairReady()
		assertEquals(1, h.transport.calls.count { it.startsWith("enableNotifications") })
		assertTrue(h.logger.messages.any { it.first == LogLevel.WARN && it.third.contains("lacks extra notify") })
	}

	@Test
	fun unansweredReadIsRetriedOnceThenTimesOut() = runIdo { h ->
		h.pairReady()
		h.watch.silent += "02/A0"
		h.session.refreshSteps(2)
		h.run()
		h.advance(4_001)
		assertEquals(2, h.watch.receivedOf("02/A0").size)
		assertTrue(h.listener.terminals(2).isEmpty())
		h.advance(4_001)
		assertEquals(WatchErrorCodes.TIMEOUT, h.listener.errorCode(2))
		assertEquals(ConnectionState.READY, h.session.state)
	}

	@Test
	fun forgetClearsLocalStateWithoutWritingToTheWatch() = runIdo { h ->
		h.pairReady()
		h.session.refreshSteps(2)
		h.run()
		val writes = h.transport.writes.size
		h.session.disconnect(3, forget = true)
		h.run()
		assertEquals(SessionEvent.Completed(3), h.listener.terminal(3))
		assertEquals(ConnectionState.DISCONNECTED, h.session.state)
		assertEquals(writes, h.transport.writes.size, "unbind bytes are unknown: nothing is written")
		assertEquals(emptyList(), h.session.getStepsDays(3))
		assertEquals(null, h.session.currentProfileId)
		assertTrue(h.logger.withTag("Session/disconnect").any { it.startsWith("UNBIND failed") })
	}
}
