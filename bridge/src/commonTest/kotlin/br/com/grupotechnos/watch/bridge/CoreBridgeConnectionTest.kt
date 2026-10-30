package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.session.SessionHarness
import br.com.grupotechnos.watch.protocol.transport.GattStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scan e conexão do [CoreBridge], contra o host Android antigo (`NativeCoreNextModule.scan`, `pairDevice`,
 * `startPairingAttempt`, `checkConnectTarget`, `disconnectCurrentDevice`, `forgetDevice`, `isConnected`,
 * `getLastPairedDevice`, e o `savePairing`/`clearForgottenPairing` do `CoreSessionListener`).
 */
class CoreBridgeConnectionTest {
	private val pairingTrail = listOf(
		"""event onConnectStateChanged {"state":1,"sessionState":"CONNECTING","phase":null}""",
		"""event onConnectStateChanged {"state":1,"sessionState":"CONFIGURING","phase":null}""",
		"""event onConnectStateChanged {"state":1,"sessionState":"HANDSHAKING","phase":null}""",
		"""event onConnectStateChanged {"state":1,"sessionState":"HANDSHAKING","phase":"BIND"}""",
		"""event onConnectStateChanged {"state":1,"sessionState":"HANDSHAKING","phase":"LOGIN"}""",
	)
	private val savedFitGps = "host savePairing CorePairing(address=$ADDRESS, profileId=fitgps, name=fitGPS)"
	private val ready = """event onConnectStateChanged {"state":2,"sessionState":"READY","phase":null}"""

	@Test
	fun scanChecksPermissionThenBluetoothThenStarts() = runBridge { b ->
		b.host.missingScan = "android.permission.BLUETOOTH_SCAN"
		b.host.bluetoothEnabled = false
		b.bridge.scan()
		b.host.missingScan = null
		b.bridge.scan()
		b.host.bluetoothEnabled = true
		b.bridge.scan()
		b.bridge.stopScan()
		assertEquals(
			listOf(
				"""event onError {"requestId":-1,"code":"PERMISSION_DENIED","message":"Missing android.permission.BLUETOOTH_SCAN","method":"scan"}""",
				"""event onError {"requestId":-1,"code":"BLUETOOTH_OFF","message":"Bluetooth is off","method":"scan"}""",
				"host startScan",
				"host stopScan",
			),
			b.journal.lines,
		)
	}

	@Test
	fun pairDeviceStopsTheScanSavesThePairingAtReadyAndResolvesAfterTheEvent() = runBridge { b ->
		val completion = b.completion("pairDevice")
		b.bridge.pairDevice(ADDRESS, "fitGPS", completion)
		assertEquals(listOf("host stopScan"), b.journal.lines, "the recording is named only when the handshake starts")
		b.run()
		assertEquals(
			listOf("host stopScan") + pairingTrail.take(2) + "host setRecordingDeviceName Mormaii_fitGPS" + pairingTrail.drop(2) +
				listOf(savedFitGps, ready, "resolve pairDevice null"),
			b.journal.lines,
		)
		assertEquals(listOf(ADDRESS to false), b.h.transport.connects)
		assertEquals(0, b.bridge.pendingRequests)
	}

	@Test
	fun pairDeviceAcceptsTheAppNameTheBleNameAndTheProfileId() = runBridge { b ->
		for (name in listOf("fitGPS", "Mormaii_fitGPS", "fitgps")) {
			assertEquals("fitgps", b.bridge.resolveProfile(name)?.id, name)
		}
		assertNull(b.bridge.resolveProfile("FITGPS"), "exact match only")
		assertNull(b.bridge.resolveProfile("Mormaii_fitGPS2"))
	}

	@Test
	fun connectTargetIsCheckedInOrderAndRejectsAfterTheErrorEvent() = runBridge { b ->
		for (method in listOf("pairDevice", "startPairingAttempt")) {
			fun call(address: String, name: String) {
				val completion = b.completion(method)
				if (method == "pairDevice") b.bridge.pairDevice(address, name, completion) else b.bridge.startPairingAttempt(address, name, completion)
				assertEquals(1, completion.settled)
			}
			b.journal.clear()
			b.host.missingConnect = "BLUETOOTH_CONNECT"
			b.host.bluetoothEnabled = false
			call("bad", "Foo")
			b.host.missingConnect = null
			call("bad", "Foo")
			b.host.bluetoothEnabled = true
			call("aa:bb:cc:dd:ee:ff", "Foo")
			call(ADDRESS, "Foo")
			assertEquals(
				listOf(
					"""event onError {"requestId":-1,"code":"PERMISSION_DENIED","message":"Missing BLUETOOTH_CONNECT","method":"$method"}""",
					"reject $method PERMISSION_DENIED Missing BLUETOOTH_CONNECT",
					"""event onError {"requestId":-1,"code":"BLUETOOTH_OFF","message":"Bluetooth is off","method":"$method"}""",
					"reject $method BLUETOOTH_OFF Bluetooth is off",
					"""event onError {"requestId":-1,"code":"INVALID_ARGUMENT","message":"Invalid Bluetooth address aa:bb:cc:dd:ee:ff","method":"$method"}""",
					"reject $method INVALID_ARGUMENT Invalid Bluetooth address aa:bb:cc:dd:ee:ff",
					"""event onError {"requestId":-1,"code":"UNSUPPORTED_DEVICE","message":"Unsupported device name Foo","method":"$method"}""",
					"reject $method UNSUPPORTED_DEVICE Unsupported device name Foo",
				),
				b.journal.lines,
				"no scan stop, no recording name, no session call before every check passes",
			)
		}
		b.bridge.userDisconnected = true
		b.bridge.pairDevice(ADDRESS, "Foo", b.completion("pairDevice"))
		assertTrue(b.bridge.userDisconnected, "a rejected pair keeps the user's disconnect")
		b.run()
		assertTrue(b.h.transport.connects.isEmpty())
		assertEquals(0, b.bridge.pendingRequests)
	}

	@Test
	fun pairDeviceFailureIsAnErrorEventWithTheMethodAndThenARejection() = runBridge { b ->
		b.h.transport.connectBehavior = { _, _ -> false }
		b.bridge.pairDevice(ADDRESS, "fitGPS", b.completion("pairDevice"))
		b.run()
		b.journal.clear()
		b.advance(30_001)
		assertEquals(
			listOf(
				"""event onError {"requestId":1,"code":"CONNECT_TIMEOUT","message":"no connection after 30000 ms","method":"pairDevice"}""",
				"reject pairDevice CONNECT_TIMEOUT no connection after 30000 ms",
			),
			b.journal.lines.filter { !it.startsWith("event onConnectStateChanged") },
		)
		val stateBeforeError = b.journal.lines.indexOfFirst { it.startsWith("event onConnectStateChanged") }
		assertTrue(stateBeforeError < b.journal.lines.indexOfFirst { it.startsWith("event onError") }, b.journal.lines.joinToString("\n"))
	}

	@Test
	fun startPairingAttemptResolvesAtOnceWithoutStoppingTheScan() = runBridge { b ->
		b.bridge.userDisconnected = true
		b.bridge.startPairingAttempt(ADDRESS, "Mormaii_fitGPS", b.completion("startPairingAttempt"))
		assertEquals(listOf("resolve startPairingAttempt null"), b.journal.lines)
		assertFalse(b.bridge.userDisconnected)
		assertEquals(1, b.bridge.pendingRequests, "registered without a promise, for the error's method name")
		b.run()
		val named = b.journal.lines.indexOf("host setRecordingDeviceName Mormaii_fitGPS")
		assertEquals(b.journal.lines.indexOf("""event onConnectStateChanged {"state":1,"sessionState":"HANDSHAKING","phase":null}""") - 1, named)
		assertEquals(listOf(ADDRESS to true), b.h.transport.connects)
		assertEquals(ConnectionState.READY, b.h.session.state)
		assertTrue(b.journal.lines.contains(savedFitGps))
		assertEquals(0, b.bridge.pendingRequests)
		assertEquals(1, b.journal.starting("resolve").size, "the terminal settles nothing")
	}

	@Test
	fun startPairingAttemptFailureIsOnlyAnErrorEventWithTheMethod() = runBridge { b ->
		b.h.watch.on(0x03, 0x03) {
			br.com.grupotechnos.watch.protocol.testing.WatchReply(responses = listOf(b.h.watch.frame(0x03, 0x04, 0x01)))
		}
		b.bridge.startPairingAttempt(ADDRESS, "fitGPS", b.completion("startPairingAttempt"))
		b.run()
		val error = b.journal.starting("event onError").single()
		assertTrue(error.startsWith("""event onError {"requestId":1,"code":"HANDSHAKE_FAILED""""), error)
		assertTrue(error.endsWith(""""method":"startPairingAttempt"}"""), error)
		assertTrue(b.journal.starting("reject").isEmpty())
	}

	@Test
	fun disconnectStopsAutomaticReconnectionUntilTheNextPair() = runBridge { b ->
		b.pairReady()
		b.bridge.disconnectCurrentDevice(b.completion("disconnectCurrentDevice"))
		assertTrue(b.bridge.userDisconnected)
		b.run()
		assertEquals(
			listOf(
				"""event onConnectStateChanged {"state":0,"sessionState":"DISCONNECTED","phase":null}""",
				"resolve disconnectCurrentDevice null",
			),
			b.journal.lines,
		)
		assertEquals(CorePairing(ADDRESS, "fitgps", "fitGPS"), b.host.pairing, "disconnect keeps the saved watch")
		b.bridge.onHostResume()
		b.bridge.onBluetoothTurnedOn()
		b.bridge.initialize(b.completion("initialize"))
		b.run()
		assertEquals(ConnectionState.DISCONNECTED, b.h.session.state)
		assertTrue(b.logger.withTag("CoreBridge/reconnectIfPaired").isEmpty())
		b.bridge.pairDevice(ADDRESS, "fitGPS", b.completion("pairDevice"))
		assertFalse(b.bridge.userDisconnected)
	}

	@Test
	fun forgetClearsThePairingWhenAskedAndAgainWhenTheSessionConcludes() = runBridge { b ->
		b.pairReady()
		b.bridge.forgetDevice(b.completion("forgetDevice"))
		assertEquals(listOf("host clearPairing"), b.journal.lines)
		b.run()
		b.advance(4_001)
		assertEquals(
			listOf(
				"host clearPairing",
				"""event onConnectStateChanged {"state":0,"sessionState":"DISCONNECTED","phase":null}""",
				"host clearPairing",
				"resolve forgetDevice null",
			),
			b.journal.lines,
		)
		assertTrue(b.h.watch.receivedOf(0x03, 0x05).isNotEmpty(), "UNBIND")
		assertTrue(b.h.store.loadSteps().isEmpty())
		assertTrue(b.bridge.userDisconnected)
	}

	@Test
	fun aReadyBetweenForgetAndTheDisconnectIsNotSaved() = runBridge { b ->
		val forget = b.completion("forgetDevice")
		val pair = b.completion("pairDevice")
		// A corrida que o host antigo comenta em CoreSessionListener.onStateChanged: o forgetDevice (thread dos módulos)
		// roda depois de a sessão chegar a READY e antes de o listener decidir gravar. O log do onStateChanged sai antes
		// dessa decisão, na thread da sessão; o gancho encaixa o forget exatamente ali.
		b.logger.onLog = { _, tag, message ->
			if (tag == "CoreBridge/onStateChanged" && message.startsWith("state=READY")) {
				b.logger.onLog = null
				b.bridge.forgetDevice(forget)
			}
		}
		b.bridge.pairDevice(ADDRESS, "fitGPS", pair)
		b.run()
		b.advance(4_001)
		val lines = b.journal.lines
		assertTrue(lines.contains(ready), lines.joinToString("\n"))
		assertTrue(b.journal.starting("host savePairing").isEmpty(), lines.joinToString("\n"))
		assertEquals(listOf("host clearPairing", ready, "resolve pairDevice null"), lines.subList(lines.indexOf("host clearPairing"), lines.indexOf("resolve pairDevice null") + 1))
		assertEquals(2, b.journal.starting("host clearPairing").size)
		assertEquals("resolve forgetDevice null", lines.last())
		assertNull(b.host.pairing)
		assertEquals(1, pair.settled)
		assertEquals(1, forget.settled)
	}

	@Test
	fun aPairRequestedBeforeTheForgetConcludesKeepsTheNewPairing() = runBridge { b ->
		b.pairReady()
		b.bridge.forgetDevice(b.completion("forgetDevice"))
		b.bridge.pairDevice(ADDRESS, "fitGPS", b.completion("pairDevice"))
		b.run()
		b.advance(SessionHarness.POST_LOGIN_MS)
		assertEquals(1, b.journal.starting("host clearPairing").size, b.journal.lines.joinToString("\n"))
		assertEquals(CorePairing(ADDRESS, "fitgps", "fitGPS"), b.host.pairing)
	}

	@Test
	fun aForgetThatEndsInErrorStillClearsThePairingAgain() = runBridge { b ->
		b.pairReady()
		// UNBIND sem ACK deixa o forget esperando o relógio; o close da sessão o termina com erro, não com onCompleted.
		b.h.watch.on(0x03, 0x05) { br.com.grupotechnos.watch.protocol.testing.WatchReply(ack = false) }
		b.bridge.forgetDevice(b.completion("forgetDevice"))
		b.run()
		assertEquals(listOf("host clearPairing"), b.journal.lines)
		b.h.session.close()
		b.run()
		assertEquals(
			listOf(
				"host clearPairing",
				"host clearPairing",
				"""event onError {"requestId":2,"code":"CANCELLED","message":"session closed during disconnect","method":"forgetDevice"}""",
				"reject forgetDevice CANCELLED session closed during disconnect",
				"""event onConnectStateChanged {"state":0,"sessionState":"DISCONNECTED","phase":null}""",
			),
			b.journal.lines,
		)
	}

	@Test
	fun aForgetEndingInErrorAfterANewPairKeepsTheNewPairing() = runBridge { b ->
		b.pairReady()
		b.h.watch.on(0x03, 0x05) { br.com.grupotechnos.watch.protocol.testing.WatchReply(ack = false) }
		b.bridge.forgetDevice(b.completion("forgetDevice"))
		b.run()
		// O pairDevice volta userDisconnected a false antes do erro do forget: o que for salvo agora é o pareamento novo.
		b.bridge.pairDevice(ADDRESS, "fitGPS", b.completion("pairDevice"))
		b.h.session.close()
		b.run()
		assertTrue(b.journal.lines.contains("reject forgetDevice CANCELLED session closed during disconnect"), b.journal.lines.joinToString("\n"))
		assertEquals(1, b.journal.starting("host clearPairing").size, b.journal.lines.joinToString("\n"))
	}

	@Test
	fun everyReadySavesThePairingUnlessTheUserDisconnected() = runBridge { b ->
		b.pairReady()
		b.bridge.syncDataHistory(b.completion("syncDataHistory"))
		b.run()
		assertEquals(listOf(savedFitGps), b.journal.starting("host savePairing"), "SYNCING -> READY saves again")
		b.journal.clear()
		b.bridge.userDisconnected = true
		b.bridge.syncDataHistory(b.completion("syncDataHistory"))
		b.run()
		assertTrue(b.journal.lines.contains(ready))
		assertTrue(b.journal.starting("host savePairing").isEmpty())
	}

	@Test
	fun isConnectedIsTrueOnlyInReadyAndSyncing() = runBridge { b ->
		val before = b.completion("isConnected")
		b.bridge.isConnected(before)
		assertEquals(false, before.value)
		b.pairReady()
		val ready = b.completion("isConnected")
		b.bridge.isConnected(ready)
		assertEquals(true, ready.value)
		b.h.watch.on(0x05, 0x41) { br.com.grupotechnos.watch.protocol.testing.WatchReply() }
		b.bridge.syncDataHistory(b.completion("syncDataHistory"))
		b.run()
		assertEquals(ConnectionState.SYNCING, b.h.session.state)
		val syncing = b.completion("isConnected")
		b.bridge.isConnected(syncing)
		assertEquals(true, syncing.value)
		assertTrue(syncing.value is Boolean)
	}

	@Test
	fun lastPairedDeviceIsNullOrTheSavedWatch() = runBridge { b ->
		val none = b.completion("getLastPairedDevice")
		b.bridge.getLastPairedDevice(none)
		b.pairReady()
		val saved = b.completion("getLastPairedDevice")
		b.bridge.getLastPairedDevice(saved)
		assertEquals(
			listOf(
				"resolve getLastPairedDevice null",
				"""resolve getLastPairedDevice {"deviceId":"$ADDRESS","name":"fitGPS","profileId":"fitgps"}""",
			),
			listOf(render(none.value).let { "resolve getLastPairedDevice $it" }) + b.journal.lines,
		)
		b.host.pairing = CorePairing("11:22:33:44:55:66", "gone", "")
		val legacy = b.completion("getLastPairedDevice")
		b.bridge.getLastPairedDevice(legacy)
		assertEquals(mapOf("deviceId" to "11:22:33:44:55:66", "name" to "", "profileId" to "gone"), legacy.value)
	}

	@Test
	fun syncAfterForgetStartsFromAnEmptyStore() = runBridge { b ->
		b.pairReady()
		b.h.store.saveConfirmedDays(DataKind.STEPS, listOf("2026-10-27"))
		b.bridge.forgetDevice(b.completion("forgetDevice"))
		b.run()
		b.advance(4_001)
		assertTrue(b.h.store.loadConfirmedDays(DataKind.STEPS).isEmpty())
	}

	@Test
	fun pairDeviceRefusedBecauseTheWatchRemovedThePairingRejectsWithPairingRemoved() = runBridge { b ->
		b.host.pairing = CorePairing(ADDRESS, "fitgps", "fitGPS")
		b.h.transport.connectBehavior = { _, _ -> false }
		b.bridge.pairDevice(ADDRESS, "fitGPS", b.completion("pairDevice"))
		b.run()
		b.journal.clear()
		b.h.transport.dropLink(GattStatus.PAIRING_REMOVED, "Connect failed (status=-4)")
		b.run()
		assertEquals(3, b.journal.lines.size, b.journal.lines.joinToString("\n"))
		assertEquals("""event onConnectStateChanged {"state":0,"sessionState":"DISCONNECTED","phase":null}""", b.journal.lines[0])
		val error = b.journal.lines[1]
		assertTrue(error.startsWith("""event onError {"requestId":1,"code":"PAIRING_REMOVED","message":"the watch erased the Bluetooth pairing"""), error)
		assertTrue(error.endsWith(""""method":"pairDevice"}"""), error)
		assertTrue(b.journal.lines[2].startsWith("reject pairDevice PAIRING_REMOVED the watch erased the Bluetooth pairing"), b.journal.lines[2])
		assertEquals(CorePairing(ADDRESS, "fitgps", "fitGPS"), b.host.pairing, "kept for the reconnect after the user forgets the watch in Settings")
		b.advance(120_000)
		assertEquals(1, b.h.transport.connects.size)
	}

	@Test
	fun pairingRemovedAfterADropIsAnErrorEventWithoutMethodAndTheSessionStops() = runBridge { b ->
		b.pairReady()
		b.h.transport.dropLink(GattStatus.PAIRING_REMOVED, "Link lost (status=-4)")
		b.run()
		assertEquals(
			listOf("""event onConnectStateChanged {"state":0,"sessionState":"DISCONNECTED","phase":null}"""),
			b.journal.starting("event onConnectStateChanged"),
			"no RECONNECTING",
		)
		val error = b.journal.starting("event onError").single()
		assertTrue(error.startsWith("""event onError {"requestId":-1,"code":"PAIRING_REMOVED""""), error)
		assertTrue(error.endsWith(""""method":null}"""), error)
		assertTrue(b.journal.starting("reject").isEmpty())
		assertEquals(CorePairing(ADDRESS, "fitgps", "fitGPS"), b.host.pairing)
		b.advance(120_000)
		assertEquals(1, b.h.transport.connects.size)
	}

	@Test
	fun startPairingAttemptRefusedBecauseTheWatchRemovedThePairingIsOnlyAnErrorEventWithTheMethod() = runBridge { b ->
		b.host.pairing = CorePairing(ADDRESS, "fitgps", "fitGPS")
		b.h.transport.connectBehavior = { _, _ -> false }
		b.bridge.startPairingAttempt(ADDRESS, "fitGPS", b.completion("startPairingAttempt"))
		b.run()
		assertEquals(listOf("resolve startPairingAttempt null"), b.journal.starting("resolve"))
		b.journal.clear()
		// A conexão pendente que o iOS encerra com o vínculo apagado (LinkLifecycle.linkDown no Repo 2).
		b.h.transport.dropLink(GattStatus.PAIRING_REMOVED, "Connect failed (status=-4)")
		b.run()
		assertEquals(2, b.journal.lines.size, b.journal.lines.joinToString("\n"))
		assertEquals("""event onConnectStateChanged {"state":0,"sessionState":"DISCONNECTED","phase":null}""", b.journal.lines[0])
		val error = b.journal.lines[1]
		assertTrue(error.startsWith("""event onError {"requestId":1,"code":"PAIRING_REMOVED","message":"the watch erased the Bluetooth pairing"""), error)
		assertTrue(error.endsWith(""""method":"startPairingAttempt"}"""), error)
		assertEquals(ConnectionState.DISCONNECTED, b.h.session.state)
		assertEquals(0, b.bridge.pendingRequests)
		assertEquals(CorePairing(ADDRESS, "fitgps", "fitGPS"), b.host.pairing)
		b.advance(120_000)
		assertEquals(listOf(ADDRESS to true), b.h.transport.connects, "the pending connect is not tried again")
	}

	@Test
	fun recordingIsNamedOnlyWhenAHandshakeStartsAndDropsThePreviousWatchFirmware() = runBridge { b ->
		b.pairReady()
		b.bridge.getDeviceInfo(b.completion("getDeviceInfo"))
		b.run()
		assertEquals(listOf("host setRecordingFirmware 1.2.3", "host setRecordingDeviceName Mormaii_fitGPS"), b.journal.starting("host setRecording"))
		b.journal.clear()
		// O primeiro teste no iPhone: pedidos ao Life Ultra GPS que nunca conectam não podem renomear a gravação do fitGPS.
		b.h.transport.connectBehavior = { _, _ -> false }
		b.bridge.pairDevice(ADDRESS, "Life Ultra GPS", b.completion("pairDevice"))
		b.run()
		b.h.transport.dropLink(GattStatus.PAIRING_REMOVED, "Connect failed (status=-4)")
		b.run()
		b.bridge.startPairingAttempt(ADDRESS, "Life Ultra GPS", b.completion("startPairingAttempt"))
		b.run()
		b.h.transport.dropLink(GattStatus.PAIRING_REMOVED, "Connect failed (status=-4)")
		b.run()
		// Links do Life Ultra GPS que abrem e falham na configuração, antes de qualquer pacote: a descoberta com o vínculo
		// apagado e o GATT_NOT_SUPPORTED (o transporte de teste só tem o serviço do fitGPS).
		b.h.transport.connectBehavior = { _, _ -> true }
		b.h.transport.discoveryStatus = GattStatus.PAIRING_REMOVED
		b.bridge.pairDevice(ADDRESS, "Life Ultra GPS", b.completion("pairDevice"))
		b.run()
		b.h.transport.discoveryStatus = GattStatus.SUCCESS
		b.bridge.pairDevice(ADDRESS, "Life Ultra GPS", b.completion("pairDevice"))
		b.run()
		assertEquals(2, b.journal.starting("""event onConnectStateChanged {"state":1,"sessionState":"CONFIGURING"""").size)
		assertEquals(
			listOf("PAIRING_REMOVED", "PAIRING_REMOVED", "GATT_NOT_SUPPORTED"),
			b.journal.starting("reject pairDevice").map { it.split(" ")[2] },
		)
		assertTrue(b.journal.starting("host setRecording").isEmpty(), b.journal.lines.joinToString("\n"))
		// Outro fitGPS chega ao handshake: o cabeçalho passa a ser dele, e o firmware do anterior sai.
		b.bridge.pairDevice(OTHER_ADDRESS, "fitGPS", b.completion("pairDevice"))
		b.run()
		val handshaking = b.journal.lines.indexOf("""event onConnectStateChanged {"state":1,"sessionState":"HANDSHAKING","phase":null}""")
		assertEquals(
			listOf("host setRecordingFirmware null", "host setRecordingDeviceName Mormaii_fitGPS"),
			b.journal.lines.subList(handshaking - 2, handshaking),
			"effects before the event; the previous watch's firmware leaves the header",
		)
		assertEquals(2, b.journal.starting("host setRecording").size)
		assertTrue(b.logger.withTag("CoreBridge/recordLinkedWatch").single().startsWith("Recording header now names Mormaii_fitGPS (fitgps)"))
		// Outro link do mesmo relógio (a reconexão da sessão depois de uma queda) não mexe no cabeçalho.
		b.journal.clear()
		b.h.transport.dropLink()
		b.run()
		assertTrue(
			b.journal.lines.contains("""event onConnectStateChanged {"state":1,"sessionState":"HANDSHAKING","phase":null}"""),
			b.journal.lines.joinToString("\n"),
		)
		assertTrue(b.journal.starting("host setRecording").isEmpty(), b.journal.lines.joinToString("\n"))
	}

	private companion object {
		/** Um segundo relógio do mesmo modelo, para trocar o relógio do cabeçalho. */
		const val OTHER_ADDRESS = "11:22:33:44:55:66"
	}
}
