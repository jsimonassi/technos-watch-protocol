package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.WatchErrorCodes
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.testing.WatchReply
import br.com.grupotechnos.watch.protocol.transport.GattStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ciclo de vida e ganchos do processo do [CoreBridge], contra o host Android antigo (`NativeCoreNextModule.invalidate`,
 * `onHostResume`, `graph()`, `CoreGraph.reconnectIfPaired`, `emitCurrentState`, `onBluetoothStateChanged`, o
 * `onDevice`/`onFailure` do scanner e o `onEntry` do `RingLogger`).
 */
class CoreBridgeLifecycleTest {
	private val saved = CorePairing(ADDRESS, "fitgps", "fitGPS")

	@Test
	fun initializeEmitsTheCurrentStateAndThenResolves() = runBridge { b ->
		b.bridge.initialize(b.completion("initialize"))
		b.bridge.initSMASDK(b.completion("initSMASDK"))
		assertEquals(
			listOf(
				"""event onConnectStateChanged {"state":0,"sessionState":"DISCONNECTED","phase":null}""",
				"resolve initialize null",
				"""event onConnectStateChanged {"state":0,"sessionState":"DISCONNECTED","phase":null}""",
				"resolve initSMASDK null",
			),
			b.journal.lines,
		)
		assertTrue(b.h.transport.connects.isEmpty(), "no saved pairing, no reconnect")
	}

	@Test
	fun initializeReconnectsToTheSavedWatchBeforeResolving() = runBridge { b ->
		b.host.pairing = saved
		b.bridge.initialize(b.completion("initialize"))
		assertEquals(
			listOf(
				"""event onConnectStateChanged {"state":0,"sessionState":"DISCONNECTED","phase":null}""",
				"resolve initialize null",
			),
			b.journal.lines,
		)
		assertEquals(listOf("Reconnecting to $ADDRESS (initialize)"), b.logger.withTag("CoreBridge/reconnectIfPaired"))
		b.run()
		assertEquals(listOf(ADDRESS to true), b.h.transport.connects, "reconnect is a pending connect")
		assertEquals(ConnectionState.READY, b.h.session.state)
		assertEquals(
			listOf(
				"""event onConnectStateChanged {"state":0,"sessionState":"RECONNECTING","phase":null}""",
				"""event onConnectStateChanged {"state":1,"sessionState":"CONFIGURING","phase":null}""",
				"host setRecordingDeviceName Mormaii_fitGPS",
				"""event onConnectStateChanged {"state":1,"sessionState":"HANDSHAKING","phase":null}""",
			),
			b.journal.lines.subList(2, 6),
			"the recording is named when the handshake starts",
		)
	}

	@Test
	fun currentStateIsReemittedAfterAJsReload() = runBridge { b ->
		b.pairReady()
		val reloaded = RecordingSink(b.journal)
		b.bridge.invalidate(b.sink)
		b.bridge.attach(reloaded)
		b.bridge.initialize(b.completion("initialize"))
		assertEquals(
			listOf(
				"""event onConnectStateChanged {"state":2,"sessionState":"READY","phase":null}""",
				"resolve initialize null",
			),
			b.journal.lines,
		)
	}

	@Test
	fun currentStateIsReemittedWhenItChangesWhileBeingEmitted() = runBridge { b ->
		b.bridge.pairDevice(ADDRESS, "fitGPS", b.completion("pairDevice"))
		b.journal.clear()
		// A sessão anda (na thread dela) enquanto o estado lido ainda está saindo: o último evento tem de ser o atual.
		b.sink.onEmit = { name ->
			if (name == "onConnectStateChanged") {
				b.sink.onEmit = null
				b.run()
			}
		}
		b.bridge.initialize(b.completion("initialize"))
		val states = b.journal.starting("event onConnectStateChanged")
		assertEquals("""event onConnectStateChanged {"state":0,"sessionState":"DISCONNECTED","phase":null}""", states.first())
		val ready = """event onConnectStateChanged {"state":2,"sessionState":"READY","phase":null}"""
		// O READY da própria sessão e, depois dele, o reenvio do emitCurrentState, que releu o estado depois de emitir.
		assertEquals(listOf(ready, "resolve pairDevice null", ready, "resolve initialize null"), b.journal.lines.takeLast(4))
		assertEquals(ready, states.last())
		assertEquals(ConnectionState.READY, b.h.session.state)
	}

	@Test
	fun reconnectIfPairedNeedsEveryCondition() = runBridge { b ->
		assertFalse(b.bridge.reconnectIfPaired("no pairing"))
		b.host.pairing = saved
		b.bridge.userDisconnected = true
		assertFalse(b.bridge.reconnectIfPaired("user disconnected"))
		b.bridge.userDisconnected = false
		b.host.bluetoothEnabled = false
		assertFalse(b.bridge.reconnectIfPaired("bluetooth off"))
		b.host.bluetoothEnabled = true
		b.host.missingConnect = "BLUETOOTH_CONNECT"
		assertFalse(b.bridge.reconnectIfPaired("no permission"))
		b.host.missingConnect = null
		assertTrue(b.h.transport.connects.isEmpty())
		assertTrue(b.bridge.reconnectIfPaired("all set"))
		b.run()
		assertEquals(ConnectionState.READY, b.h.session.state)
		assertFalse(b.bridge.reconnectIfPaired("already ready"), "only from DISCONNECTED or BLUETOOTH_OFF")
		assertEquals(listOf(ADDRESS to true), b.h.transport.connects)
		assertEquals(listOf("Reconnecting to $ADDRESS (all set)"), b.logger.withTag("CoreBridge/reconnectIfPaired"))
	}

	@Test
	fun reconnectStartsFromBluetoothOffToo() = runBridge { b ->
		b.host.pairing = saved
		b.h.transport.setBluetooth(false)
		b.run()
		assertEquals(ConnectionState.BLUETOOTH_OFF, b.h.session.state)
		assertTrue(b.bridge.reconnectIfPaired("from bluetooth off"))
	}

	@Test
	fun internalReconnectFailureIsAnErrorWithoutMethod() = runBridge { b ->
		b.host.pairing = saved
		b.h.watch.on(0x03, 0x03) { WatchReply(responses = listOf(b.h.watch.frame(0x03, 0x04, 0x01))) }
		assertTrue(b.bridge.reconnectIfPaired("test"))
		b.run()
		val error = b.journal.starting("event onError").single()
		assertTrue(error.contains(""""code":"HANDSHAKE_FAILED""""), error)
		assertTrue(error.endsWith(""""method":null}"""), error)
		assertEquals(0, b.bridge.pendingRequests)
	}

	@Test
	fun eventsWaitForTheFirstAttachAndArriveInOrder() = runBridge(attachSink = false) { b ->
		b.host.missingScan = "android.permission.BLUETOOTH_SCAN"
		b.bridge.scan()
		b.bridge.onScanFailed(-1, "Bluetooth LE scanner unavailable (Bluetooth off?)")
		assertTrue(b.journal.lines.isEmpty())
		assertEquals(2, b.bridge.queuedEvents)
		b.bridge.attach(b.sink)
		assertEquals(0, b.bridge.queuedEvents)
		assertEquals(
			listOf(
				"""event onError {"requestId":-1,"code":"PERMISSION_DENIED","message":"Missing android.permission.BLUETOOTH_SCAN","method":"scan"}""",
				"""event onError {"requestId":-1,"code":"TRANSPORT","message":"Bluetooth LE scanner unavailable (Bluetooth off?) (errorCode=-1)","method":"scan"}""",
			),
			b.journal.lines,
		)
	}

	@Test
	fun attachingTheSameSinkAgainDoesNotFlush() = runBridge { b ->
		b.sink.ready = false
		b.bridge.onScanFailed(2, "Scan failed (errorCode=2)")
		assertEquals(1, b.bridge.queuedEvents)
		b.sink.ready = true
		b.bridge.attach(b.sink)
		assertEquals(1, b.bridge.queuedEvents, "attach of the current sink is a no-op, like graph() after the first call")
		b.bridge.onHostResume()
		assertEquals(0, b.bridge.queuedEvents)
		assertEquals(1, b.journal.starting("event onError").size)
	}

	@Test
	fun eventsQueueWhileTheSinkCannotEmitAndFlushOnHostResume() = runBridge { b ->
		b.sink.ready = false
		b.bridge.getBatteryStatus(b.completion("getBatteryStatus"))
		b.run()
		assertEquals(listOf("reject getBatteryStatus NOT_READY readBattery needs READY or SYNCING (state=DISCONNECTED)"), b.journal.starting("reject"))
		assertTrue(b.journal.starting("event").isEmpty())
		b.sink.ready = true
		b.bridge.onHostResume()
		assertEquals(1, b.journal.starting("event onError").size)
	}

	@Test
	fun hostResumeFlushesBeforeReconnecting() = runBridge { b ->
		b.host.pairing = saved
		b.sink.ready = false
		b.bridge.onScanFailed(1, "Scan failed (errorCode=1)")
		b.sink.ready = true
		b.bridge.onHostResume()
		assertEquals(listOf("Reconnecting to $ADDRESS (host resume)"), b.logger.withTag("CoreBridge/reconnectIfPaired"))
		b.run()
		assertEquals(
			listOf(
				"""event onError {"requestId":-1,"code":"TRANSPORT","message":"Scan failed (errorCode=1) (errorCode=1)","method":"scan"}""",
				"""event onConnectStateChanged {"state":0,"sessionState":"RECONNECTING","phase":null}""",
			),
			b.journal.lines.take(2),
		)
	}

	@Test
	fun automaticReconnectTriesOncePerTriggerWhenTheWatchRemovedThePairing() = runBridge { b ->
		b.host.pairing = saved
		b.h.transport.connectBehavior = { _, auto -> !auto }
		b.bridge.initialize(b.completion("initialize"))
		b.run()
		b.h.transport.dropLink(GattStatus.PAIRING_REMOVED, "Connect failed (status=-4)")
		b.run()
		val error = b.journal.starting("event onError").single()
		assertTrue(error.startsWith("""event onError {"requestId":1,"code":"PAIRING_REMOVED""""), error)
		assertTrue(error.endsWith(""""method":null}"""), error)
		assertEquals(ConnectionState.DISCONNECTED, b.h.session.state)
		b.advance(120_000)
		assertEquals(1, b.h.transport.connects.size, "nothing reacts to DISCONNECTED: no loop")
		// Outro gatilho com o vínculo ainda apagado: uma tentativa, um erro.
		b.bridge.onBluetoothTurnedOn()
		b.run()
		b.h.transport.dropLink(GattStatus.PAIRING_REMOVED, "Connect failed (status=-4)")
		b.run()
		assertEquals(2, b.h.transport.connects.size)
		assertEquals(2, b.journal.starting("event onError").size)
		// O usuário esqueceu o relógio nos Ajustes e voltou ao app: a volta ao primeiro plano reconecta.
		b.h.transport.connectBehavior = { _, _ -> true }
		b.bridge.onHostResume()
		b.run()
		assertEquals(3, b.h.transport.connects.size)
		assertEquals(ConnectionState.READY, b.h.session.state)
	}

	@Test
	fun invalidateRejectsEveryPendingPromiseWithCancelled() = runBridge { b ->
		b.h.transport.connectBehavior = { _, _ -> false }
		val pair = b.completion("pairDevice")
		val battery = b.completion("getBatteryStatus")
		b.bridge.pairDevice(ADDRESS, "fitGPS", pair)
		b.bridge.startPairingAttempt(ADDRESS, "fitGPS", b.completion("startPairingAttempt"))
		b.bridge.getBatteryStatus(battery)
		assertEquals(3, b.bridge.pendingRequests)
		b.journal.clear()
		b.bridge.invalidate(b.sink)
		assertEquals(
			listOf(
				"reject pairDevice CANCELLED Module invalidated",
				"reject getBatteryStatus CANCELLED Module invalidated",
			),
			b.journal.lines,
			"registration order; the startPairingAttempt entry has no promise and just goes away",
		)
		assertEquals(0, b.bridge.pendingRequests)
		b.run()
		assertEquals(1, pair.settled)
		assertEquals(1, battery.settled)
		assertTrue(b.journal.starting("event").isEmpty(), "the detached module gets nothing")
		assertTrue(b.bridge.queuedEvents > 0, "session events wait for the next module")
	}

	@Test
	fun invalidatingAnOldModuleKeepsTheCurrentOneAttached() = runBridge { b ->
		val current = RecordingSink(b.journal)
		b.bridge.attach(current)
		b.bridge.invalidate(b.sink)
		b.bridge.onScanFailed(3, "x")
		assertEquals(1, current.events.size)
		assertTrue(b.sink.events.isEmpty())
	}

	@Test
	fun bluetoothOffStopsTheScanAndBluetoothOnReconnects() = runBridge { b ->
		b.host.pairing = saved
		b.bridge.onBluetoothTurnedOff()
		assertEquals(listOf("host stopScan"), b.journal.lines)
		b.h.transport.setBluetooth(false)
		b.run()
		b.h.transport.setBluetooth(true)
		b.bridge.onBluetoothTurnedOn()
		assertEquals(listOf("Reconnecting to $ADDRESS (Bluetooth turned on)"), b.logger.withTag("CoreBridge/reconnectIfPaired"))
		b.run()
		assertEquals(ConnectionState.READY, b.h.session.state)
	}

	@Test
	fun scannedDeviceUsesTheProfileAppName() = runBridge { b ->
		b.bridge.onScannedDevice(Profiles.MORMAII_FIT_GPS, ADDRESS, -61, "Mormaii_fitGPS")
		assertEquals(
			listOf("""event onScannedDevice {"name":"fitGPS","deviceId":"$ADDRESS","rssi":-61,"bleName":"Mormaii_fitGPS","profileId":"fitgps"}"""),
			b.journal.lines,
		)
	}

	@Test
	fun logEntriesGoOutOnlyWhileTheModuleCanEmit() = runBridge { b ->
		b.sink.recordLogs = true
		b.bridge.onLogEntry(CoreLogEntry(1_793_272_444_786, LogLevel.DEBUG, "Frame/6e400002", "seq=1 03/01 BIND len=40", FrameDirection.TX, "AB00"))
		b.sink.ready = false
		b.bridge.onLogEntry(CoreLogEntry(1, LogLevel.INFO, "T/t", "dropped", null, null))
		assertEquals(0, b.bridge.queuedEvents, "onLog is never queued")
		b.sink.ready = true
		b.bridge.onHostResume()
		assertEquals(
			listOf("""event onLog {"ts":1793272444786.0,"level":"DEBUG","tag":"Frame/6e400002","message":"seq=1 03/01 BIND len=40","dir":"TX","hex":"AB00"}"""),
			b.journal.lines,
		)
		assertEquals(1.793272444786E12, b.sink.payloadsOf("onLog").single()["ts"])
	}

	@Test
	fun emitErrorLogsTheWarningBeforeTheEvent() = runBridge { b ->
		b.sink.recordLogs = true
		b.logger.forward = b.bridge
		b.bridge.emitError(7, WatchErrorCodes.TIMEOUT, "no ACK", "getBatteryStatus")
		assertEquals(
			listOf(
				"""event onLog {"ts":1793272200000.0,"level":"WARN","tag":"CoreBridge/emitError","message":"method=getBatteryStatus requestId=7 code=TIMEOUT message=no ACK","dir":null,"hex":null}""",
				"""event onError {"requestId":7,"code":"TIMEOUT","message":"no ACK","method":"getBatteryStatus"}""",
			),
			b.journal.lines,
		)
	}
}
