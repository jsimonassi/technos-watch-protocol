package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.session.SessionHarness.Companion.oct
import br.com.grupotechnos.watch.protocol.session.SessionHarness.Companion.sportPage
import br.com.grupotechnos.watch.protocol.testing.WatchReply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comandos ao relógio pelo [CoreBridge], contra o host Android antigo (`NativeCoreNextModule.command` e os callbacks
 * terminais do `CoreSessionListener`): o evento sai antes do `resolve`, toda falha sai como `onError` com o método JS
 * e rejeita com o código da sessão, e o trabalho em segundo plano acompanha o sync.
 */
class CoreBridgeCommandsTest {
	private val deviceInfo =
		"""event onDeviceInfo {"deviceId":"$ADDRESS","profileId":"fitgps","bleName":"Mormaii_fitGPS","firmware":"1.2.3","bleVersion":"4.5.6"}"""
	private val firmwareInfo = """event onFirmwareInfo {"version":"1.2.3","braceletType":0,"platformCode":0,"deviceId":"$ADDRESS"}"""
	private val recording = listOf("host setRecordingFirmware 1.2.3", "host setRecordingDeviceName Mormaii_fitGPS")

	@Test
	fun deviceInfoEmitsOnlyItsOwnEventAndThenResolves() = runBridge { b ->
		b.pairReady()
		b.bridge.getDeviceInfo(b.completion("getDeviceInfo"))
		b.run()
		b.bridge.getFirmwareInfo(b.completion("getFirmwareInfo"))
		b.run()
		assertEquals(
			recording + listOf(deviceInfo, "resolve getDeviceInfo null") +
				recording + listOf(firmwareInfo, "resolve getFirmwareInfo null"),
			b.journal.lines,
		)
	}

	@Test
	fun deviceInfoWithoutARegisteredRequestEmitsBothEvents() = runBridge { b ->
		b.pairReady()
		val orphan = b.completion("getDeviceInfo")
		b.bridge.getDeviceInfo(orphan)
		// O módulo morreu com a leitura em andamento: o terminal chega sem entrada no registro, como um aviso espontâneo.
		b.bridge.invalidate(b.sink)
		val next = RecordingSink(b.journal)
		b.bridge.attach(next)
		b.journal.clear()
		b.run()
		assertEquals(recording + listOf(deviceInfo, firmwareInfo), b.journal.lines)
		assertEquals(1, orphan.settled, "only the CANCELLED from invalidate")
	}

	@Test
	fun batteryIsEmittedBeforeTheResolve() = runBridge { b ->
		b.pairReady()
		b.bridge.getBatteryStatus(b.completion("getBatteryStatus"))
		b.run()
		assertEquals(
			listOf(
				"""event onBatteryStatus {"percentage":85,"charging":false,"battery":"85%"}""",
				"resolve getBatteryStatus null",
			),
			b.journal.lines,
		)
	}

	@Test
	fun commandFailureIsAnErrorEventWithTheMethodBeforeTheRejection() = runBridge { b ->
		b.pairReady()
		b.h.watch.on(0x02, 0x08) { WatchReply() }
		b.bridge.getBatteryStatus(b.completion("getBatteryStatus"))
		b.run()
		b.advance(4_001)
		val error = b.journal.lines.single { it.startsWith("event onError") }
		assertTrue(error.startsWith("""event onError {"requestId":2,"code":"TIMEOUT","message":"""), error)
		assertTrue(error.endsWith(""","method":"getBatteryStatus"}"""), error)
		val message = error.substringAfter(""""message":"""").substringBefore("""","method"""")
		assertEquals(listOf(error, "reject getBatteryStatus TIMEOUT $message"), b.journal.lines)
	}

	@Test
	fun commandsOutsideReadyFailWithNotReadyFromTheSession() = runBridge { b ->
		val methods = listOf("getDeviceInfo", "getFirmwareInfo", "getBatteryStatus", "setTime", "refreshSteps", "syncDataHistory")
		b.bridge.getDeviceInfo(b.completion(methods[0]))
		b.bridge.getFirmwareInfo(b.completion(methods[1]))
		b.bridge.getBatteryStatus(b.completion(methods[2]))
		b.bridge.setTime(b.completion(methods[3]))
		b.bridge.refreshSteps(b.completion(methods[4]))
		b.bridge.syncDataHistory(b.completion(methods[5]))
		b.run()
		val errors = b.journal.starting("event onError")
		assertEquals(methods.map { """"method":"$it"}""" }, errors.map { it.substringAfterLast(",") })
		assertEquals(methods.indices.map { """"requestId":${it + 1}""" }, errors.map { it.substringAfter("{").substringBefore(",") })
		assertEquals(methods.map { "reject $it NOT_READY" }, b.journal.starting("reject").map { it.split(" ").take(3).joinToString(" ") })
		assertTrue(b.journal.starting("host").isEmpty(), "a failed sync never started the background work")
	}

	@Test
	fun setTimeResolvesWithoutAnEvent() = runBridge { b ->
		b.pairReady()
		b.bridge.setTime(b.completion("setTime"))
		b.run()
		assertEquals(listOf("resolve setTime null"), b.journal.lines)
	}

	@Test
	fun refreshStepsEmitsTodayBeforeTheResolve() = runBridge { b ->
		b.pairReady()
		// Acumulado do dia, como o fitGPS 0.2.1: o total de hoje é a leitura das 10h (ADR 1 do :protocol).
		b.h.sportPages += sportPage(Triple(oct(28, 9), 0, 1_000), Triple(oct(28, 10), 0, 1_234))
		b.bridge.refreshSteps(b.completion("refreshSteps"))
		b.run()
		assertEquals(2, b.journal.lines.size, b.journal.lines.joinToString("\n"))
		val pedometer = b.journal.lines[0]
		assertTrue(pedometer.startsWith("""event onPedometer {"pedometer":1234,"calorie":"""), pedometer)
		assertTrue(pedometer.endsWith(""","date":"2026-10-28T00:00:00-03:00"}"""), pedometer)
		assertEquals("resolve refreshSteps null", b.journal.lines[1])
		val payload = b.sink.payloadsOf("onPedometer").single()
		assertTrue(payload["pedometer"] is Int && payload["calorie"] is Int && payload["distance"] is Double, "$payload")
	}

	@Test
	fun dataChangedNoticeEmitsTheLiveStepsWithoutAPromise() = runBridge { b ->
		b.pairReady()
		b.h.sportPages += sportPage(Triple(oct(28, 14), 0, 123))
		b.h.watch.send(b.h.watch.frame(0x02, 0x70, 0x01))
		b.advance(2_001)
		assertEquals(1, b.journal.lines.size, b.journal.lines.joinToString("\n"))
		assertTrue(b.journal.lines.single().startsWith("""event onPedometer {"pedometer":123,"""))
	}

	@Test
	fun syncStartsTheBackgroundWorkBeforeTheEventAndStopsItBeforeTheLastEvent() = runBridge { b ->
		b.pairReady()
		b.bridge.syncDataHistory(b.completion("syncDataHistory"))
		b.run()
		assertEquals(
			listOf(
				"""event onConnectStateChanged {"state":2,"sessionState":"SYNCING","phase":null}""",
				"host startSyncWork",
				"event onSyncStarted {}",
				"""event onSyncProgress {"progress":0}""",
				"""event onSyncProgress {"progress":50}""",
				"""event onSyncProgress {"progress":100}""",
				"host savePairing CorePairing(address=$ADDRESS, profileId=fitgps, name=fitGPS)",
				"""event onConnectStateChanged {"state":2,"sessionState":"READY","phase":null}""",
				"host stopSyncWork",
				"event onSyncFinished {}",
				"resolve syncDataHistory null",
			),
			b.journal.lines,
		)
		assertEquals(listOf("sync finished"), b.logger.withTag("CoreBridge/stopSyncWork"))
	}

	@Test
	fun syncErrorStopsTheBackgroundWorkBeforeTheErrorEvent() = runBridge { b ->
		b.pairReady()
		b.h.watch.on(0x05, 0x41) { WatchReply() }
		b.bridge.syncDataHistory(b.completion("syncDataHistory"))
		b.run()
		b.advance(4_001)
		val lines = b.journal.lines
		val stop = lines.indexOf("host stopSyncWork")
		assertTrue(stop > lines.indexOf("host startSyncWork"), lines.joinToString("\n"))
		assertTrue(lines[stop + 1].startsWith("""event onError {"requestId":2,"code":"TIMEOUT""""), lines.joinToString("\n"))
		assertTrue(lines[stop + 2].startsWith("reject syncDataHistory TIMEOUT"), lines.joinToString("\n"))
		assertEquals(1, b.journal.starting("host stopSyncWork").size)
		assertEquals(listOf("sync error TIMEOUT"), b.logger.withTag("CoreBridge/stopSyncWork"))
	}

	@Test
	fun linkLossDuringSyncStopsTheBackgroundWorkOnceBeforeTheError() = runBridge { b ->
		b.pairReady()
		b.h.watch.on(0x05, 0x41) { WatchReply() }
		b.bridge.syncDataHistory(b.completion("syncDataHistory"))
		b.run()
		b.h.transport.dropLink()
		b.run()
		val lines = b.journal.lines
		val stop = lines.indexOf("host stopSyncWork")
		assertTrue(lines[stop + 1].startsWith("""event onError {"requestId":2,"code":"DISCONNECTED""""), lines.joinToString("\n"))
		assertTrue(lines[stop + 2].startsWith("reject syncDataHistory DISCONNECTED"), lines.joinToString("\n"))
		assertTrue(lines.indexOf("""event onConnectStateChanged {"state":0,"sessionState":"RECONNECTING","phase":null}""") > stop)
		assertEquals(1, b.journal.starting("host stopSyncWork").size, "the RECONNECTING that follows finds nothing to stop")
		assertEquals(listOf("sync error DISCONNECTED"), b.logger.withTag("CoreBridge/stopSyncWork"))
	}

	@Test
	fun bluetoothOffDuringSyncStopsTheBackgroundWorkOnce() = runBridge { b ->
		b.pairReady()
		b.h.watch.on(0x05, 0x41) { WatchReply() }
		b.bridge.syncDataHistory(b.completion("syncDataHistory"))
		b.run()
		b.bridge.onBluetoothTurnedOff()
		b.h.transport.setBluetooth(false)
		b.run()
		assertEquals(listOf("sync error DISCONNECTED"), b.logger.withTag("CoreBridge/stopSyncWork"))
		assertEquals(1, b.journal.starting("host stopSyncWork").size)
		assertTrue(b.journal.lines.contains("""event onConnectStateChanged {"state":4,"sessionState":"BLUETOOTH_OFF","phase":null}"""))
		assertEquals("reject syncDataHistory DISCONNECTED", b.journal.starting("reject").single().split(" ").take(3).joinToString(" "))
	}

	@Test
	fun forgetDuringSyncNeitherSavesTheReadyInBetweenNorLeavesTheBackgroundWorkOn() = runBridge { b ->
		b.pairReady()
		b.h.watch.on(0x05, 0x41) { WatchReply() }
		b.bridge.syncDataHistory(b.completion("syncDataHistory"))
		b.run()
		b.bridge.forgetDevice(b.completion("forgetDevice"))
		b.run()
		b.advance(4_001)
		val lines = b.journal.lines
		// A sessão cancela o sync, volta a READY e só então processa o disconnect: é o READY "no meio" do forget.
		assertTrue(lines.contains("""event onConnectStateChanged {"state":2,"sessionState":"READY","phase":null}"""), lines.joinToString("\n"))
		assertTrue(b.journal.starting("host savePairing").isEmpty(), lines.joinToString("\n"))
		assertEquals(listOf("host startSyncWork", "host clearPairing", "host stopSyncWork"), lines.filter { it.startsWith("host") }.take(3))
		assertEquals(2, b.journal.starting("host clearPairing").size)
		assertEquals("resolve forgetDevice null", lines.last())
	}

	@Test
	fun aStateOutsideReadyAndSyncingStopsTheBackgroundWorkEvenBeforeTheSyncFails() = runBridge { b ->
		// Com a sessão de hoje o sync sempre falha antes da troca de estado; a regra do host antigo cobre a outra ordem.
		val listener = b.bridge.sessionListener
		listener.onSyncStarted(5)
		listener.onStateChanged(ConnectionState.RECONNECTING, null)
		listener.onError(5, "DISCONNECTED", "link lost")
		assertEquals(listOf("state=RECONNECTING"), b.logger.withTag("CoreBridge/stopSyncWork"))
		assertEquals(listOf("host startSyncWork", "host stopSyncWork"), b.journal.starting("host"))
	}

	@Test
	fun anotherRequestFailingDuringTheSyncKeepsTheBackgroundWork() = runBridge { b ->
		val listener = b.bridge.sessionListener
		listener.onSyncStarted(5)
		listener.onError(6, "TIMEOUT", "battery")
		listener.onStateChanged(ConnectionState.SYNCING, null)
		listener.onStateChanged(ConnectionState.READY, null)
		assertEquals(listOf("host startSyncWork"), b.journal.starting("host"))
		listener.onSyncFinished(5)
		assertEquals(listOf("host startSyncWork", "host stopSyncWork"), b.journal.starting("host"))
		listener.onSyncFinished(5)
		assertEquals(1, b.journal.starting("host stopSyncWork").size)
	}

	@Test
	fun spontaneousDeviceInfoEmitsBothEventsInOrder() = runBridge { b ->
		val listener = b.bridge.sessionListener
		listener.onDeviceInfo(-1, br.com.grupotechnos.watch.protocol.domain.DeviceInfo("fitgps", ADDRESS, "Mormaii_fitGPS", "1.2.3", "4.5.6"))
		assertEquals(recording + listOf(deviceInfo, firmwareInfo), b.journal.lines)
	}
}
