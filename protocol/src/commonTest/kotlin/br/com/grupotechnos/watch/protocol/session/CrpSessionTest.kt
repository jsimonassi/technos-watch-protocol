package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.crp.CrpCommand
import br.com.grupotechnos.watch.protocol.crp.CrpGatt
import br.com.grupotechnos.watch.protocol.crp.CrpStepsTotals
import br.com.grupotechnos.watch.protocol.domain.Battery
import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.DeviceInfo
import br.com.grupotechnos.watch.protocol.domain.WatchErrorCodes
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.testing.CrpWatchSimulator
import br.com.grupotechnos.watch.protocol.testing.FakeTransport
import br.com.grupotechnos.watch.protocol.testing.RecordingListener
import br.com.grupotechnos.watch.protocol.testing.SessionEvent
import br.com.grupotechnos.watch.protocol.testing.SessionTestLogger
import br.com.grupotechnos.watch.protocol.testing.TestClock
import br.com.grupotechnos.watch.protocol.transport.GattStatus
import br.com.grupotechnos.watch.protocol.transport.WriteType
import br.com.grupotechnos.watch.protocol.util.Hex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CONDOR_REEBOK = "condorreebok"

/** "Agora" dos testes CRP: o instante do acerto de hora da captura (`condor-app-pair-sync.jsonl` atMs 6821). */
private const val CRP_NOW_ISO = "2026-10-31T15:43:19-03:00"

/** Uma `WatchSession` real sobre o [FakeTransport] CRP e o [CrpWatchSimulator], no tempo virtual do teste. */
@OptIn(ExperimentalCoroutinesApi::class)
private class CrpHarness(val scope: TestScope, val clock: TestClock = TestClock.at(CRP_NOW_ISO)) {
	val store = InMemorySampleStore()
	val transport: FakeTransport
	val watch: CrpWatchSimulator
	val listener = RecordingListener()
	val logger = SessionTestLogger()
	val session: WatchSession

	/** Instante virtual de cada escrita, na ordem de [FakeTransport.writes]. */
	val writeTimes = mutableListOf<Long>()

	init {
		val (fake, simulator) = FakeTransport.scriptedCrp()
		transport = fake
		watch = simulator
		transport.mtuReply = 517
		val deliver = transport.onWrite!!
		transport.onWrite = { packet ->
			writeTimes += scope.testScheduler.currentTime
			deliver(packet)
		}
		session = WatchSession(transport, logger, store, clock, StandardTestDispatcher(scope.testScheduler))
		transport.events = session.events
		session.setListener(listener)
	}

	fun run() = scope.runCurrent()

	fun advance(ms: Long) {
		scope.advanceTimeBy(ms)
		scope.runCurrent()
	}

	/** `pair` até READY e o acerto de hora automático. */
	fun pairReady(requestId: Int = 1) {
		session.pair(requestId, ADDRESS, CONDOR_REEBOK)
		run()
		advance(HANDSHAKE_MS)
		assertEquals(ConnectionState.READY, session.state, "states: ${listener.stateTrail()}")
		advance(POST_LOGIN_MS)
	}

	/** `syncHistory` com tempo para os timeouts dos pedidos sem resposta. */
	fun sync(requestId: Int, vararg kinds: DataKind) {
		session.syncHistory(requestId, kinds.toList())
		advance(SYNC_MS)
	}

	fun labels(): List<String> = watch.labels()

	fun assertNoUnexpectedErrors() {
		assertTrue(logger.unexpected().isEmpty(), "unexpected errors: ${logger.unexpected()}")
	}

	companion object {
		/** Folga para o handshake: 300 ms + leitura + `5A 00`, `B7 0E` e `BB 16 00` (200 ms cada). */
		const val HANDSHAKE_MS: Long = 2_000

		/** `postLoginDelayMs` do Condor Reebok + o `31` e o `BB 07 00`. */
		const val POST_LOGIN_MS: Long = 1_000

		/** Folga para um sync com dois timeouts de 4 s (o `33` e um pedido silenciado). */
		const val SYNC_MS: Long = 15_000
	}
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun runCrp(configure: CrpHarness.() -> Unit = {}, body: suspend TestScope.(CrpHarness) -> Unit): TestResult = runTest {
	val harness = CrpHarness(this)
	harness.configure()
	try {
		body(harness)
	} finally {
		harness.session.close()
		runCurrent()
	}
}

/**
 * O Condor Reebok (CRP) pela `WatchSession`, com o relógio simulado em nível de byte ([CrpWatchSimulator], respostas da
 * captura do app de produção): handshake de versão sem vínculo, hora, bateria (com o aviso espontâneo), firmware, passos
 * por meia hora com os totais do dia, passos ao vivo, sono, esquecer e as notificações laterais.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrpSessionTest {
	private val fee3 = Profiles.CRP_FEEA.notify

	@Test
	fun pairReachesReadyWithVersionHandshakeAndNoBind() = runCrp { h ->
		h.pairReady()
		assertEquals(SessionEvent.Completed(1), h.listener.terminal(1))
		assertEquals(
			listOf("CONNECTING", "CONFIGURING", "HANDSHAKING", "HANDSHAKING[VERSION]", "READY"),
			h.listener.stateTrail(),
		)
		val gatt = h.transport.calls.filter { it.startsWith("enableNotifications") || it.startsWith("requestMtu") }
		assertEquals(
			listOf(
				"enableNotifications($fee3)",
				"enableNotifications(${CrpGatt.HEART_RATE})",
				"enableNotifications(${CrpGatt.STEPS_TODAY})",
				"enableNotifications(${CrpGatt.BATTERY_LEVEL})",
				"requestMtu(512)",
			),
			gatt,
		)
		assertEquals(listOf(CrpGatt.PROTOCOL_VERSION), h.watch.reads)
		assertEquals(listOf("5A/00", "B7/0E", "BB/16", "31", "BB/07"), h.labels())
		assertEquals("FEEA20065A00", Hex.encode(h.transport.writes.first()), "block of MTU 517: byte2 0x20")
		assertTrue(h.transport.writeTypes.all { it == WriteType.WITHOUT_RESPONSE })
		// A leitura de 2A29 vai para a gravação como READ.
		val read = h.logger.frames.single { it.direction == FrameDirection.READ }
		assertEquals(CrpGatt.PROTOCOL_VERSION, read.characteristic)
		assertEquals("PROTOCOL_VERSION_TEXT MOYOUNG-V2", read.summary)
		h.assertNoUnexpectedErrors()
	}

	@Test
	fun reconnectUsesTheSameHandshake() = runCrp { h ->
		h.session.reconnect(1, ADDRESS, CONDOR_REEBOK)
		h.run()
		h.advance(CrpHarness.HANDSHAKE_MS + CrpHarness.POST_LOGIN_MS)
		assertEquals(SessionEvent.Completed(1), h.listener.terminal(1))
		assertEquals(
			listOf("RECONNECTING", "CONFIGURING", "HANDSHAKING", "HANDSHAKING[VERSION]", "READY"),
			h.listener.stateTrail(),
		)
		assertEquals(listOf("5A/00", "B7/0E", "BB/16", "31", "BB/07"), h.labels())
		assertEquals(listOf(CrpGatt.PROTOCOL_VERSION), h.watch.reads)
	}

	@Test
	fun versionComesFromTheNotificationWhen2A29IsMissing() = runCrp({ watch.protocolText = null }) { h ->
		h.pairReady()
		assertEquals(SessionEvent.Completed(1), h.listener.terminal(1))
		assertEquals(listOf("5A/00", "B7/0E", "BB/16", "31", "BB/07"), h.labels())
		h.session.readDeviceInfo(2)
		h.advance(1_000)
		assertEquals(
			SessionEvent.Info(2, DeviceInfo(CONDOR_REEBOK, ADDRESS, "P85", "MOY-VS02-2.0.0", "MOYOUNG-V2")),
			h.listener.terminal(2),
		)
	}

	@Test
	fun v1WatchUsesBlock20AndSkipsTheExtraQueries() = runCrp({
		watch.protocolText = "MOYOUNG"
		watch.protocolReplyText = "MOYOUNG"
	}) { h ->
		h.pairReady()
		assertEquals(listOf("5A/00", "31", "BB/07"), h.labels())
		// O 5A 00 sai antes de a versão ser conhecida (bloco do MTU); depois do V1, bloco 20 e byte2 0x10.
		assertEquals(0x20, h.transport.writes[0][2].toInt())
		assertTrue(h.transport.writes.drop(1).all { it[2].toInt() == 0x10 }, h.transport.writes.map { Hex.encode(it) }.toString())
	}

	@Test
	fun pairFailsWithHandshakeFailedWithoutAnyVersion() = runCrp({
		watch.protocolText = null
		watch.silent += CrpCommand.PROTOCOL_INFO to CrpCommand.SUB_PROTOCOL_VERSION
	}) { h ->
		h.session.pair(1, ADDRESS, CONDOR_REEBOK)
		h.run()
		h.advance(6_000)
		assertEquals(WatchErrorCodes.HANDSHAKE_FAILED, h.listener.errorCode(1))
		assertEquals(ConnectionState.DISCONNECTED, h.session.state)
		assertTrue(h.logger.contains("no protocol version (2A29 and 5A 00)"))
	}

	@Test
	fun autoSetTimeWritesTimeThenZoneWithTheGap() = runCrp { h ->
		h.pairReady()
		val time = h.transport.writes.indexOfFirst { it[4].toInt() == CrpCommand.SET_TIME }
		assertEquals("FEEA200A316AE59C1708", Hex.encode(h.transport.writes[time]))
		assertEquals("FEEA200BBB0700D0D5FFFF", Hex.encode(h.transport.writes[time + 1]))
		assertTrue(h.writeTimes[time + 1] - h.writeTimes[time] >= 200, "gap ${h.writeTimes[time + 1] - h.writeTimes[time]} ms")
		// O handshake também respeita os 200 ms entre frames.
		assertTrue(h.writeTimes.zipWithNext().all { (a, b) -> b - a >= 200 }, h.writeTimes.toString())
	}

	@Test
	fun batteryReadAppliesTheChargingRule() = runCrp { h ->
		h.pairReady()
		h.session.readBattery(2)
		h.advance(100)
		assertEquals(SessionEvent.BatteryLevel(2, Battery(50, charging = false)), h.listener.terminal(2))
		h.watch.battery = 0x9B
		h.session.readBattery(3)
		h.advance(100)
		assertEquals(SessionEvent.BatteryLevel(3, Battery(55, charging = true)), h.listener.terminal(3))
		assertEquals(listOf(CrpGatt.BATTERY_LEVEL, CrpGatt.BATTERY_LEVEL), h.watch.reads.drop(1))
	}

	@Test
	fun spontaneousBatteryNotificationEmitsOnBatteryWithoutRequest() = runCrp { h ->
		h.pairReady()
		h.listener.clear()
		h.watch.pushBattery(0x31)
		h.run()
		assertEquals(listOf<SessionEvent>(SessionEvent.BatteryLevel(WatchSession.NO_REQUEST_ID, Battery(49, false))), h.listener.events)
		h.watch.pushBattery(0xC8)
		h.run()
		assertEquals(SessionEvent.BatteryLevel(WatchSession.NO_REQUEST_ID, Battery(100, true)), h.listener.events.last())
	}

	@Test
	fun firmwareFallsBackTo2A28() = runCrp({ watch.silent += CrpCommand.PROTOCOL_INFO to CrpCommand.SUB_FIRMWARE }) { h ->
		h.pairReady()
		h.session.readDeviceInfo(2)
		h.advance(6_000)
		assertEquals(
			SessionEvent.Info(2, DeviceInfo(CONDOR_REEBOK, ADDRESS, "P85", "MOY-VS02-2.0.0", "MOYOUNG-V2")),
			h.listener.terminal(2),
		)
		assertEquals(CrpGatt.FIRMWARE_REVISION, h.watch.reads.last())
		assertTrue(h.logger.contains("5A 01 not answered"))
	}

	@Test
	fun stepsSyncBuildsHalfHoursAndDayTotals() = runCrp({
		watch.stepsPages[0x00] = IntArray(24).also { it[16] = 120; it[17] = 350 }
		watch.stepsPages[0x02] = IntArray(24).also { it[20] = 1_000 }
		watch.stepsPages[0x03] = IntArray(24).also { it[3] = 234 }
		watch.fee1 = CrpStepsTotals(600, 450, 21, null)
	}) { h ->
		h.pairReady()
		h.sync(2, DataKind.STEPS)
		assertEquals(SessionEvent.SyncFinished(2), h.listener.terminal(2))
		assertEquals(listOf("59/00", "59/01", "59/02", "59/03", "33/01"), h.labels().drop(5))
		assertEquals(CrpGatt.STEPS_TODAY, h.watch.reads[1])
		val days = h.session.getStepsDays(3)
		assertEquals(listOf("2026-10-30", "2026-10-31"), days.map { it.dayKey })
		assertEquals(Triple(1_234, 0, 0.0), Triple(days[0].steps, days[0].calories, days[0].distanceMeters))
		assertEquals(Triple(600, 21, 450.0), Triple(days[1].steps, days[1].calories, days[1].distanceMeters))
		val samples = h.store.loadSteps()
		// Ontem: 48 meias horas, sem amostra de totais (sem 33 01). Hoje: 31 fechadas, a atual e os totais.
		assertEquals(48 + 31 + 2, samples.size)
		assertEquals(1, samples.count { it.mode == 2 })
		h.assertNoUnexpectedErrors()
	}

	@Test
	fun distanceAndCaloriesCategoriesAreNeverRequested() = runCrp { h ->
		h.pairReady()
		h.sync(2, DataKind.STEPS, DataKind.SLEEP)
		h.sync(3, DataKind.STEPS)
		assertEquals(emptyList(), h.watch.framesOf(CrpCommand.DISTANCE_HALF_HOURS))
		assertEquals(emptyList(), h.watch.framesOf(CrpCommand.CALORIES_HALF_HOURS))
		assertEquals(emptyList(), h.watch.framesOf(CrpCommand.HISTORY))
	}

	@Test
	fun liveFee1UpdatesTodayAtMostEveryTwoSeconds() = runCrp { h ->
		h.pairReady()
		h.sync(2, DataKind.STEPS)
		h.listener.clear()
		for (i in 0 until 10) {
			h.watch.pushSteps(CrpStepsTotals(16 + i, 13 + i, 1, null))
			h.advance(500)
		}
		h.advance(2_000)
		val live = h.listener.events.filterIsInstance<SessionEvent.LiveSteps>()
		assertTrue(live.all { it.requestId == WatchSession.NO_REQUEST_ID })
		assertTrue(live.size in 2..3, "live updates: $live")
		assertEquals(Triple(25, 1, 22.0), live.last().today.let { Triple(it.steps, it.calories, it.distanceMeters) })
		assertEquals(25, h.session.getStepsDays(3).last().steps)
		// Nenhum frame novo: os passos ao vivo não releem o histórico.
		assertEquals(listOf("59/00", "59/01", "59/02", "59/03", "33/01"), h.labels().drop(5))
	}

	@Test
	fun firstLiveNotificationWithoutStateTriggersARefresh() = runCrp({ watch.fee1 = CrpStepsTotals(50, 40, 2, null) }) { h ->
		h.pairReady()
		h.listener.clear()
		h.watch.pushSteps(CrpStepsTotals(50, 40, 2, null))
		h.run()
		assertTrue(h.listener.events.isEmpty())
		h.advance(SessionEngine.DATA_CHANGED_DEBOUNCE_MS + 10_000)
		assertEquals(listOf("59/00", "59/01", "59/02", "59/03", "33/01"), h.labels().drop(5))
		val live = h.listener.events.filterIsInstance<SessionEvent.LiveSteps>().single()
		assertEquals(WatchSession.NO_REQUEST_ID, live.requestId)
		assertEquals(50, live.today.steps)
	}

	@Test
	fun walkWithoutStateRefreshesOnceAndThenStreams() = runCrp({ watch.fee1 = CrpStepsTotals(16, 13, 1, null) }) { h ->
		h.pairReady()
		h.listener.clear()
		// FEE1 a cada segundo, como na caminhada da captura (walk@114170 a @124147), sem nenhum readSteps antes: o debounce
		// da sessão reinicia a cada DataChanged, então o driver pede a releitura uma vez só.
		fun step(i: Int) {
			h.watch.fee1 = CrpStepsTotals(16 + i, 13 + i, 1, null)
			h.watch.pushSteps(h.watch.fee1)
			h.advance(1_000)
		}
		for (i in 0 until 3) step(i)
		assertEquals(listOf("59/00"), h.labels().drop(5).take(1), "the refresh starts about 2 s after the first FEE1")
		for (i in 3 until 15) step(i)
		assertEquals(listOf("59/00", "59/01", "59/02", "59/03", "33/01"), h.labels().drop(5))
		assertTrue(h.logger.contains("refresh already requested"))
		// Depois da releitura, FEE1 vira passos ao vivo (no máximo um a cada 2 s), até o último valor.
		h.advance(SessionEngine.LIVE_STEPS_THROTTLE_MS)
		val live = h.listener.events.filterIsInstance<SessionEvent.LiveSteps>()
		assertTrue(live.size >= 3, "live updates: $live")
		assertTrue(live.all { it.requestId == WatchSession.NO_REQUEST_ID })
		assertEquals(30, live.last().today.steps)
		h.assertNoUnexpectedErrors()
	}

	@Test
	fun failedRefreshIsRetriedOnlyAfterTheBackoff() = runCrp({
		watch.silent += CrpCommand.STEPS_HALF_HOURS to 0x00
	}) { h ->
		h.pairReady()
		fun walk(seconds: Int, from: Int) {
			for (i in from until from + seconds) {
				h.watch.pushSteps(CrpStepsTotals(16 + i, 13 + i, 1, null))
				h.advance(1_000)
			}
		}
		// A releitura sai ~2 s depois do primeiro FEE1 e vence no 59 00 (~6,2 s); a trava segura os pedidos por 30 s.
		walk(30, from = 0)
		assertEquals(1, h.watch.framesOf(CrpCommand.STEPS_HALF_HOURS).size, h.labels().toString())
		assertTrue(h.logger.contains("internal command failed: TIMEOUT"))
		walk(10, from = 30)
		assertEquals(2, h.watch.framesOf(CrpCommand.STEPS_HALF_HOURS).size, h.labels().toString())
		assertEquals(ConnectionState.READY, h.session.state)
	}

	@Test
	fun fragmentedResponsesWithByte2Of0x10AreReassembled() = runCrp({
		watch.splitResponsesAt = 20
		watch.responseByte2 = 0x10
		watch.firmwareReplyText = "MOY-VS02-2.0.0-SPLIT-TEST"
		watch.stepsPages[0x00] = IntArray(24).also { it[16] = 120; it[17] = 350 }
		watch.stepsPages[0x01] = IntArray(24).also { it[6] = 0x1234 }
		watch.fee1 = CrpStepsTotals(120 + 350 + 0x1234 + 7, 450, 21, null)
	}) { h ->
		h.pairReady()
		h.session.readDeviceInfo(2)
		h.advance(1_000)
		assertEquals(
			SessionEvent.Info(2, DeviceInfo(CONDOR_REEBOK, ADDRESS, "P85", "MOY-VS02-2.0.0-SPLIT-TEST", "MOYOUNG-V2")),
			h.listener.terminal(2),
		)
		h.sync(3, DataKind.STEPS)
		assertEquals(SessionEvent.SyncFinished(3), h.listener.terminal(3))
		// Hoje: 08:00 = 120, 08:30 = 350, 15:00 = 0x1234 (na segunda página), e a meia hora atual fecha com o FEE1.
		val today = h.store.loadSteps().filter { it.mode == 0 && it.steps > 0 }.sortedBy { it.atEpochMillis }.map { it.steps }
		assertEquals(listOf(120, 350, 0x1234), today)
		assertEquals(120 + 350 + 0x1234 + 7, h.session.getStepsDays(3).last().steps)
		// As respostas chegaram em pedaços de 20 bytes, com byte2 0x10, e nenhum pedaço foi descartado.
		val rx = h.logger.frames.filter { it.direction == FrameDirection.RX && it.characteristic == fee3 }
		assertTrue(rx.count { it.value.size == 20 } >= 5, rx.toString())
		assertTrue(rx.filter { it.value.size >= 3 && it.value[0] == 0xFE.toByte() }.all { it.value[2].toInt() == 0x10 }, rx.toString())
		assertTrue(h.logger.messages.none { it.first == LogLevel.WARN && it.second == "CrpDriver/receive" }, h.logger.messages.toString())
		h.assertNoUnexpectedErrors()
	}

	@Test
	fun partialFrameIsDiscardedAfterTheGapAndTheRequestTimesOut() = runCrp({
		watch.silent += CrpCommand.PROTOCOL_INFO to CrpCommand.SUB_FIRMWARE
	}) { h ->
		h.pairReady()
		// O relógio manda só o primeiro pedaço da resposta ao 5A 01: o cabeçalho anuncia 20 bytes e chegam 16.
		val deliver = h.transport.onWrite!!
		h.transport.onWrite = { packet ->
			deliver(packet)
			if (Hex.encode(packet) == "FEEA20065A01") h.transport.notifyOn(fee3, Hex.decode("FEEA20145A01") + "MOY-VS02-2".encodeToByteArray())
		}
		h.session.readDeviceInfo(2)
		h.advance(Profiles.CONDOR_REEBOK.quirks.fragmentGapTimeoutMs + 100)
		assertTrue(h.logger.contains("partial frame discarded after 3000 ms without continuation"))
		assertTrue(h.listener.terminals(2).isEmpty(), "still waiting for the reply")
		h.advance(2_000)
		// A espera vence (200 ms + 4 s) e a leitura cai no 2A28.
		assertEquals(
			SessionEvent.Info(2, DeviceInfo(CONDOR_REEBOK, ADDRESS, "P85", "MOY-VS02-2.0.0", "MOYOUNG-V2")),
			h.listener.terminal(2),
		)
		val messages = h.logger.messages.map { it.third }
		assertTrue(
			messages.indexOfFirst { it.startsWith("partial frame discarded") } < messages.indexOfFirst { it.startsWith("5A 01 not answered") },
			messages.toString(),
		)
		// Depois do descarte, um frame novo é aceito: o 5A 01 seguinte responde sem o 2A28.
		h.transport.onWrite = deliver
		h.watch.silent.clear()
		h.watch.reads.clear()
		h.session.readDeviceInfo(3)
		h.advance(1_000)
		assertEquals(
			SessionEvent.Info(3, DeviceInfo(CONDOR_REEBOK, ADDRESS, "P85", "MOY-VS02-2.0.0", "MOYOUNG-V2")),
			h.listener.terminal(3),
		)
		assertEquals(emptyList(), h.watch.reads)
	}

	@Test
	fun sleepTodayIsAssembledLikeTheSdk() = runCrp({
		watch.sleepToday = CrpWatchSimulator.legacySleep(Triple(1, 23, 5), Triple(2, 23, 50), Triple(1, 1, 10), Triple(0, 6, 30))
	}) { h ->
		h.pairReady()
		h.sync(2, DataKind.SLEEP)
		assertEquals(SessionEvent.SyncFinished(2), h.listener.terminal(2))
		val session = h.session.getSleepSessions(3).single()
		assertEquals("2026-10-31", session.dayKey)
		assertEquals(listOf(445, 365, 80, 0), listOf(session.totalMinutes, session.lightMinutes, session.deepMinutes, session.awakeMinutes))
		assertEquals("2026-10-30T23:05:00-03:00", session.startIso)
		assertEquals(listOf("32", "33/03"), h.labels().drop(5))
	}

	@Test
	fun b6ReplyIsAcceptedInsteadOf32() = runCrp({
		watch.replyB6Instead = true
		watch.sleepToday = CrpWatchSimulator.newSleep(Triple(1, 23, 5), Triple(2, 23, 50), Triple(1, 1, 10), Triple(0, 6, 30))
	}) { h ->
		h.pairReady()
		h.sync(2, DataKind.SLEEP)
		val session = h.session.getSleepSessions(3).single()
		assertEquals(listOf(445, 365, 80), listOf(session.totalMinutes, session.lightMinutes, session.deepMinutes))
	}

	@Test
	fun silent32TimesOutWithoutBlockingTheQueue() = runCrp({ watch.silent += CrpCommand.SLEEP_TODAY to null }) { h ->
		h.pairReady()
		h.session.syncHistory(2, listOf(DataKind.STEPS, DataKind.SLEEP))
		h.session.readBattery(3)
		h.advance(CrpHarness.SYNC_MS)
		assertEquals(SessionEvent.SyncFinished(2), h.listener.terminal(2))
		assertEquals(SessionEvent.BatteryLevel(3, Battery(50)), h.listener.terminal(3))
		assertTrue(h.session.getSleepSessions(3).isEmpty())
		assertTrue(h.logger.contains("no answer to 32"))
	}

	@Test
	fun legacy33IsProbedOncePerConnection() = runCrp { h ->
		h.pairReady()
		h.sync(2, DataKind.STEPS, DataKind.SLEEP)
		h.sync(3, DataKind.STEPS, DataKind.SLEEP)
		assertEquals(1, h.watch.framesOf(CrpCommand.LEGACY_HISTORY).size)
		assertTrue(h.logger.contains("0x33 history disabled for this connection"))
		// Com o legado respondendo, o 33 01 sai a cada leitura e o sono pede ontem e anteontem.
		h.watch.sleepLegacy = mapOf(0x03 to CrpWatchSimulator.legacySleep(Triple(1, 22, 0), Triple(0, 23, 0)))
		h.watch.yesterdayTotals = CrpStepsTotals(10, 8, 3, null)
		h.session.disconnect(4, forget = false)
		h.advance(100)
		h.watch.frames.clear()
		h.session.reconnect(5, ADDRESS, CONDOR_REEBOK)
		h.advance(CrpHarness.HANDSHAKE_MS + CrpHarness.POST_LOGIN_MS)
		h.sync(6, DataKind.STEPS, DataKind.SLEEP)
		h.sync(7, DataKind.STEPS)
		assertEquals(listOf("33/01", "33/03", "33/04", "33/01"), h.watch.labels().filter { it.startsWith("33") })
		val yesterday = h.session.getStepsDays(3).first { it.dayKey == "2026-10-30" }
		assertEquals(Pair(3, 8.0), Pair(yesterday.calories, yesterday.distanceMeters))
		assertEquals(listOf("2026-10-30"), h.session.getSleepSessions(3).map { it.dayKey })
	}

	@Test
	fun forgetWritesF8ThenDisconnects() = runCrp { h ->
		h.pairReady()
		h.session.disconnect(9, forget = true)
		h.advance(1_000)
		assertEquals("F8", h.labels().last())
		assertEquals("FEEA2005F8", Hex.encode(h.transport.writes.last()))
		assertEquals(SessionEvent.Completed(9), h.listener.terminal(9))
		assertEquals(ConnectionState.DISCONNECTED, h.session.state)
		assertTrue(h.transport.calls.last() == "disconnect")
	}

	@Test
	fun f9AndBondNoticesAreOnlyLogged() = runCrp { h ->
		h.pairReady()
		h.listener.clear()
		h.watch.pushF9(byteArrayOf(0x01, 0x01, 0x01))
		h.watch.pushF9(byteArrayOf(0x02, 0x01))
		h.watch.emitFrame(CrpCommand.BIND_NOTICE, byteArrayOf(0x04))
		h.watch.pushHeartRate(65)
		h.run()
		assertTrue(h.listener.events.isEmpty(), h.listener.events.toString())
		val classic = h.logger.messages.filter { it.second == "CrpDriver/classicState" }
		assertEquals(listOf(LogLevel.INFO, LogLevel.INFO), classic.map { it.first })
		assertEquals(listOf("watch reports classic=01", "watch reports sub=02 value=01"), classic.map { it.third })
		assertTrue(h.logger.withTag("CrpDriver/heartRate").contains("hr=65"))
		h.assertNoUnexpectedErrors()
	}

	@Test
	fun failedSideCccdIsNotFatal() = runCrp({
		transport.notificationsStatusOf = { characteristic ->
			when (characteristic) {
				CrpGatt.BATTERY_LEVEL -> 133
				CrpGatt.HEART_RATE -> null
				else -> GattStatus.SUCCESS
			}
		}
	}) { h ->
		h.session.pair(1, ADDRESS, CONDOR_REEBOK)
		h.run()
		h.advance(6_000 + CrpHarness.HANDSHAKE_MS)
		assertEquals(SessionEvent.Completed(1), h.listener.terminal(1))
		assertEquals(ConnectionState.READY, h.session.state)
		val warnings = h.logger.messages.filter { it.first == LogLevel.WARN && it.second == "Session/configure" }.map { it.third }
		assertTrue(warnings.any { it.contains("${CrpGatt.HEART_RATE} not answered") }, warnings.toString())
		assertTrue(warnings.any { it.contains("${CrpGatt.BATTERY_LEVEL} failed, status=133") }, warnings.toString())
	}

	@Test
	fun missingSideCharacteristicIsSkipped() = runCrp({
		transport.services = transport.services.filterNot { it.uuid == CrpGatt.BATTERY_SERVICE }
	}) { h ->
		h.pairReady()
		assertEquals(SessionEvent.Completed(1), h.listener.terminal(1))
		assertTrue(h.transport.calls.none { it == "enableNotifications(${CrpGatt.BATTERY_LEVEL})" })
		assertTrue(h.logger.contains("side notify [${CrpGatt.BATTERY_LEVEL}] not discovered"))
	}
}
