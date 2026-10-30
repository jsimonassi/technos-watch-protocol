package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.session.InMemorySampleStore
import br.com.grupotechnos.watch.protocol.session.WatchSession
import br.com.grupotechnos.watch.protocol.testing.FakeTransport
import br.com.grupotechnos.watch.protocol.testing.IdoWatchSimulator
import br.com.grupotechnos.watch.protocol.testing.SessionTestLogger
import br.com.grupotechnos.watch.protocol.testing.TestClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O caminho do JavaScript com a segunda família: o Life Ultra GPS (iDO) pelo [CoreBridge], sobre uma `WatchSession` de
 * verdade com o `IdoWatchSimulator` do kit de teste. Nada no controlador é específico de família; o teste confere que o
 * nome do app resolve o perfil, que o pareamento salvo leva o `profileId` novo, que a reconexão ao religar o Bluetooth
 * não refaz o vínculo e que bateria, firmware e passos chegam aos eventos com os valores do relógio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreBridgeIdoTest {
	private class Rig(scope: TestScope) {
		val clock = TestClock.at("2026-10-29T22:08:49-03:00")
		val transport: FakeTransport
		val watch: IdoWatchSimulator
		val session: WatchSession
		val journal = Journal()
		val host = FakeCoreHost(journal)
		val sink = RecordingSink(journal)
		val bridge: CoreBridge

		init {
			val (fake, simulator) = FakeTransport.scriptedIdo { delayMs, action ->
				scope.backgroundScope.launch {
					delay(delayMs)
					action()
				}
			}
			transport = fake
			watch = simulator
			session = WatchSession(transport, SessionTestLogger(), InMemorySampleStore(), clock, StandardTestDispatcher(scope.testScheduler))
			transport.events = session.events
			bridge = CoreBridge(session, BridgeTestLogger(), host) { TimeZone.of(clock.zone) }
			bridge.attach(sink)
		}
	}

	@Test
	fun lifeUltraGpsWorksThroughTheBridge() = runTest {
		val rig = Rig(this)
		try {
			rig.bridge.onScannedDevice(Profiles.MORMAII_LIFE_ULTRA_GPS, ADDRESS, -58, "Life Ultra GPS")
			assertEquals(
				mapOf("name" to "Life Ultra GPS", "deviceId" to ADDRESS, "rssi" to -58, "bleName" to "Life Ultra GPS", "profileId" to "lifeultragps"),
				rig.sink.payloadsOf("onScannedDevice").single(),
			)
			for (name in listOf("Life Ultra GPS", "Smart Watch 6010", "lifeultragps")) {
				assertEquals("lifeultragps", rig.bridge.resolveProfile(name)?.id, name)
			}

			val pair = RecordingCompletion("pairDevice", rig.journal)
			rig.bridge.pairDevice(ADDRESS, "Life Ultra GPS", pair)
			runCurrent()
			assertEquals(0, pair.settled, "waits for the tap on the watch")
			advanceTimeBy(rig.watch.confirmDelayMs + 1)
			runCurrent()
			assertEquals(1, pair.settled)
			assertNull(pair.code)
			assertEquals(CorePairing(ADDRESS, "lifeultragps", "Life Ultra GPS"), rig.host.pairing)
			assertTrue("host setRecordingDeviceName Life Ultra GPS" in rig.journal.lines, rig.journal.lines.joinToString("\n"))
			advanceTimeBy(1_001)
			runCurrent()

			rig.bridge.getBatteryStatus(RecordingCompletion("getBatteryStatus", rig.journal))
			rig.bridge.getFirmwareInfo(RecordingCompletion("getFirmwareInfo", rig.journal))
			rig.bridge.refreshSteps(RecordingCompletion("refreshSteps", rig.journal))
			runCurrent()
			assertEquals(mapOf("percentage" to 14, "charging" to false, "battery" to "14%"), rig.sink.payloadsOf("onBatteryStatus").single())
			assertEquals("1.1.1", rig.sink.payloadsOf("onFirmwareInfo").single()["version"])
			assertEquals(26, rig.sink.payloadsOf("onPedometer").last()["pedometer"])

			rig.transport.setBluetooth(false)
			runCurrent()
			rig.transport.setBluetooth(true)
			rig.bridge.onBluetoothTurnedOn()
			runCurrent()
			assertEquals(ConnectionState.READY, rig.session.state)
			assertEquals("LOGIN", rig.sink.payloadsOf("onConnectStateChanged").last { it["phase"] != null }["phase"])
			assertEquals(1, rig.watch.receivedOf("04/01").size, "a reconnect never binds again")
		} finally {
			rig.session.close()
			runCurrent()
		}
	}
}
