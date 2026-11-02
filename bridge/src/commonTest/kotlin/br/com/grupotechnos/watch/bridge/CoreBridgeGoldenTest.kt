package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.session.InMemorySampleStore
import br.com.grupotechnos.watch.protocol.session.WatchSession
import br.com.grupotechnos.watch.protocol.testing.FakeTransport
import br.com.grupotechnos.watch.protocol.testing.Fixtures
import br.com.grupotechnos.watch.protocol.testing.REPLAY_ADDRESS
import br.com.grupotechnos.watch.protocol.testing.Recording
import br.com.grupotechnos.watch.protocol.testing.RecordingLoader
import br.com.grupotechnos.watch.protocol.testing.ReplayAction
import br.com.grupotechnos.watch.protocol.testing.ReplayDriver
import br.com.grupotechnos.watch.protocol.testing.ReplayScenario
import br.com.grupotechnos.watch.protocol.testing.SessionTestLogger
import br.com.grupotechnos.watch.protocol.testing.TestClock
import br.com.grupotechnos.watch.protocol.testing.readFixtureText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Golden do fluxo de eventos JS: a gravação real versionada do fitGPS (parear, comandos, Bluetooth desligado e
 * religado, sync) passa pelo [CoreBridge] como o app a usaria, método JS por método JS, numa `WatchSession` de verdade
 * em tempo virtual, com o relógio respondendo pela gravação ([ReplayDriver] estrito: cada TX tem de bater). Tudo o que o
 * JavaScript e a plataforma veem (eventos com payload, liquidações e efeitos no host, em ordem; sem o `onLog`) é
 * comparado com `bridge/golden/` (o README de lá explica como ler e regenerar).
 *
 * Roda nos quatro targets; cada um imprime uma linha `GOLDEN <arquivo>: <n> lines, TX matched <a>/<b>` no `system-out`
 * do XML, a prova de que o replay rodou ali.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreBridgeGoldenTest {
	@Test
	fun realRecordingThroughTheBridgeMatchesTheGolden() = runTest {
		val recording = RecordingLoader.loadFixture(RECORDING)
		val (lines, replay) = bridgeJournal(recording)
		assertTrue(replay.problems().isEmpty(), replay.problems().joinToString("\n"))
		val text = lines.joinToString("\n", postfix = "\n")
		val path = configuredGoldenDir().trimEnd('/') + "/" + GOLDEN_FILE
		if (regenerateGoldenIfRequested(path, text)) println("GOLDEN $GOLDEN_FILE regenerated")
		println("GOLDEN $GOLDEN_FILE: ${lines.size} lines, TX matched ${replay.matchedTx}/${replay.recordedTx}")
		val golden = readFixtureText(path)
		if (golden != text) {
			val expected = golden.lines()
			val actual = text.lines()
			val first = expected.indices.firstOrNull { it >= actual.size || expected[it] != actual[it] } ?: expected.size
			println("GOLDEN actual journal:\n$text")
			fail(
				"bridge journal differs from bridge/golden/$GOLDEN_FILE at line ${first + 1}\n" +
					"  golden: ${expected.getOrNull(first)}\n  actual: ${actual.getOrNull(first)}\n" +
					"regenerate with BRIDGE_GOLDEN_REGENERATE=1 ./gradlew :bridge:jvmTest --tests '*CoreBridgeGoldenTest*' " +
					"and review the diff against the old mapping rules (golden/README.md)",
			)
		}
	}

	@Test
	fun theRecordingStillDeducesTheScriptTheGoldenWasWrittenFor() = runTest {
		val recording = RecordingLoader.loadFixture(RECORDING)
		val actions = ReplayScenario.deduce(recording)
		assertTrue(actions.all { it in SCRIPTED }, "the golden maps only $SCRIPTED to JS methods, got $actions")
		assertTrue(Fixtures.relative(recording.source) == RECORDING)
	}

	private companion object {
		const val RECORDING = "real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl"
		const val GOLDEN_FILE = "fitgps-0.2.1-pair-commands-bt-reconnect-sync.txt"

		/** Tempo virtual de cada ação, o mesmo do `runReplay` do :protocol (cobre o `bindTimeoutMs` de 60 s com folga). */
		const val ACTION_BUDGET_MS: Long = 120_000

		val SCRIPTED: Set<ReplayAction> = setOf(
			ReplayAction.Pair,
			ReplayAction.Reconnect,
			ReplayAction.SetTime,
			ReplayAction.ReadBattery,
			ReplayAction.ReadDeviceInfo,
			ReplayAction.Sync(listOf(DataKind.STEPS, DataKind.SLEEP)),
			ReplayAction.Sync(listOf(DataKind.STEPS)),
			ReplayAction.Forget,
			ReplayAction.BluetoothCycle,
		)
	}

	/**
	 * Reproduz [recording] pelo [CoreBridge]. Cada ação deduzida da gravação vira o método JS que a produz (o
	 * `ReadDeviceInfo` como `getDeviceInfo`, o `Sync([STEPS])` como `refreshSteps`, o ciclo de Bluetooth pelos ganchos
	 * do host); depois vêm as consultas locais, que não falam com o relógio (histórico, `deletedSavedData`, estado,
	 * relógio salvo) e um `disconnectCurrentDevice`, que não escreve nada no relógio.
	 */
	private fun TestScope.bridgeJournal(recording: Recording): Pair<List<String>, ReplayDriver> {
		val transport = FakeTransport()
		transport.mtuReply = recording.header.mtu
		val replay = ReplayDriver(recording, transport, strict = true)
		val clock = TestClock(recording.header.recordedAtEpochMillis, recording.header.timeZone)
		val session = WatchSession(transport, SessionTestLogger(), InMemorySampleStore(), clock, StandardTestDispatcher(testScheduler))
		transport.events = session.events
		val journal = Journal()
		val host = FakeCoreHost(journal)
		val sink = RecordingSink(journal)
		val bridge = CoreBridge(session, BridgeTestLogger(), host) { TimeZone.of(recording.header.timeZone) }
		journal.add("# CoreBridge journal of fixtures/$RECORDING (${recording.header.device} ${recording.header.firmware}, ${recording.header.timeZone})")
		journal.add("# generated by CoreBridgeGoldenTest; regenerate: BRIDGE_GOLDEN_REGENERATE=1 ./gradlew :bridge:jvmTest --tests '*CoreBridgeGoldenTest*'")

		fun step(call: String, block: () -> Unit) {
			journal.add("call $call")
			block()
			advanceTimeBy(ACTION_BUDGET_MS)
			runCurrent()
		}

		fun c(method: String) = RecordingCompletion(method, journal)
		try {
			bridge.attach(sink)
			step("initialize()") { bridge.initialize(c("initialize")) }
			for (action in ReplayScenario.deduce(recording)) {
				when (action) {
					ReplayAction.Pair -> step("pairDevice(\"$REPLAY_ADDRESS\", \"fitGPS\")") { bridge.pairDevice(REPLAY_ADDRESS, "fitGPS", c("pairDevice")) }
					ReplayAction.Reconnect -> step("startPairingAttempt(\"$REPLAY_ADDRESS\", \"fitGPS\")") {
						bridge.startPairingAttempt(REPLAY_ADDRESS, "fitGPS", c("startPairingAttempt"))
					}
					ReplayAction.SetTime -> step("setTime()") { bridge.setTime(c("setTime")) }
					ReplayAction.ReadBattery -> step("getBatteryStatus()") { bridge.getBatteryStatus(c("getBatteryStatus")) }
					ReplayAction.ReadDeviceInfo -> step("getDeviceInfo()") { bridge.getDeviceInfo(c("getDeviceInfo")) }
					ReplayAction.Forget -> step("forgetDevice()") { bridge.forgetDevice(c("forgetDevice")) }
					is ReplayAction.Sync -> when (action.kinds) {
						listOf(DataKind.STEPS, DataKind.SLEEP) -> step("syncDataHistory()") { bridge.syncDataHistory(c("syncDataHistory")) }
						listOf(DataKind.STEPS) -> step("refreshSteps()") { bridge.refreshSteps(c("refreshSteps")) }
						else -> fail("no JS method reads only ${action.kinds}")
					}
					ReplayAction.RefreshSteps -> step("refreshSteps()") { bridge.refreshSteps(c("refreshSteps")) }
					ReplayAction.BluetoothCycle -> step("[host] Bluetooth off, then on") {
						// O que o CoreGraph do Android faz no BluetoothStateReceiver: para o scan, o transporte entrega o
						// desligado; ao religar, entrega o ligado e só depois pede a reconexão (a sessão ainda não processou).
						host.bluetoothEnabled = false
						bridge.onBluetoothTurnedOff()
						transport.setBluetooth(false)
						runCurrent()
						host.bluetoothEnabled = true
						transport.setBluetooth(true)
						bridge.onBluetoothTurnedOn()
					}
				}
			}
			step("isConnected()") { bridge.isConnected(c("isConnected")) }
			step("getLastPairedDevice()") { bridge.getLastPairedDevice(c("getLastPairedDevice")) }
			step("getHistoryPedometer(3)") { bridge.getHistoryPedometer(3.0, c("getHistoryPedometer")) }
			step("getHistorySleep()") { bridge.getHistorySleep(c("getHistorySleep")) }
			val firstDay = session.getStepsDays(3).firstOrNull()?.startOfDayIso
			if (firstDay != null) {
				step("deletedSavedData([\"$firstDay\"], 5)") { bridge.deletedSavedData(listOf(firstDay), 5.0) }
				step("getHistoryPedometer(3)") { bridge.getHistoryPedometer(3.0, c("getHistoryPedometer")) }
			}
			step("disconnectCurrentDevice()") { bridge.disconnectCurrentDevice(c("disconnectCurrentDevice")) }
			step("isConnected()") { bridge.isConnected(c("isConnected")) }
		} finally {
			session.close()
			runCurrent()
		}
		return journal.lines.toList() to replay
	}
}
