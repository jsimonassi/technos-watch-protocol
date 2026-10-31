package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.crp.CrpStepsTotals
import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.profile.DeviceRegistry
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.session.InMemorySampleStore
import br.com.grupotechnos.watch.protocol.session.WatchSession
import br.com.grupotechnos.watch.protocol.testing.CrpWatchSimulator
import br.com.grupotechnos.watch.protocol.testing.FakeTransport
import br.com.grupotechnos.watch.protocol.testing.SessionTestLogger
import br.com.grupotechnos.watch.protocol.testing.TestClock
import br.com.grupotechnos.watch.protocol.util.Hex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Agora" dos testes: o instante do acerto de hora da captura (`condor-app-pair-sync.jsonl` atMs 6821). */
private const val CRP_NOW_ISO = "2026-10-31T15:43:19-03:00"

/**
 * Um [CoreBridge] sobre uma `WatchSession` de verdade com o Condor Reebok simulado em nível de byte
 * ([CrpWatchSimulator], respostas da captura do app de produção), no tempo virtual do teste.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class CrpRig(private val scope: TestScope) {
	val clock = TestClock.at(CRP_NOW_ISO)
	val transport: FakeTransport
	val watch: CrpWatchSimulator
	val session: WatchSession
	val journal = Journal()
	val host = FakeCoreHost(journal)
	val logger = BridgeTestLogger()
	val sink = RecordingSink(journal)
	val bridge: CoreBridge

	init {
		val (fake, simulator) = FakeTransport.scriptedCrp()
		transport = fake
		watch = simulator
		transport.mtuReply = 517
		session = WatchSession(transport, SessionTestLogger(), InMemorySampleStore(), clock, StandardTestDispatcher(scope.testScheduler))
		transport.events = session.events
		bridge = CoreBridge(session, logger, host) { TimeZone.of(clock.zone) }
		bridge.attach(sink)
	}

	fun completion(method: String): RecordingCompletion = RecordingCompletion(method, journal)

	fun run() = scope.runCurrent()

	fun advance(ms: Long) {
		scope.advanceTimeBy(ms)
		scope.runCurrent()
	}

	/** `pairDevice` até READY mais o acerto de hora automático; não esvazia o diário. */
	fun pairReady(name: String = "Condor Reebok"): RecordingCompletion {
		val completion = completion("pairDevice")
		bridge.pairDevice(ADDRESS, name, completion)
		run()
		advance(HANDSHAKE_MS)
		assertEquals(1, completion.settled, journal.lines.joinToString("\n"))
		advance(POST_LOGIN_MS)
		return completion
	}

	/** `syncDataHistory` com folga para o timeout da sonda `33 01` (que o P85 da captura não responde). */
	fun sync(): RecordingCompletion {
		val completion = completion("syncDataHistory")
		bridge.syncDataHistory(completion)
		advance(SYNC_MS)
		assertEquals(1, completion.settled, journal.lines.joinToString("\n"))
		return completion
	}

	/** Bluetooth desligado e religado pelos ganchos do host; a reconexão automática chega a READY de novo. */
	fun bluetoothCycle() {
		transport.setBluetooth(false)
		run()
		transport.setBluetooth(true)
		bridge.onBluetoothTurnedOn()
		run()
		advance(HANDSHAKE_MS + POST_LOGIN_MS)
		assertEquals(ConnectionState.READY, session.state)
	}

	/** A partir de agora, cada pacote escrito no relógio entra no diário como `watch write <hex>`. */
	fun journalWrites() {
		val deliver = transport.onWrite!!
		transport.onWrite = { packet ->
			journal.add("watch write ${Hex.encode(packet)}")
			deliver(packet)
		}
	}

	fun classicLines(): List<String> = journal.lines.filter { "ClassicBond" in it }

	companion object {
		/** Folga para o handshake de versão: 300 ms + leitura de 2A29 + `5A 00`, `B7 0E` e `BB 16 00` (200 ms cada). */
		const val HANDSHAKE_MS: Long = 2_000

		/** `postLoginDelayMs` do Condor Reebok + o `31` e o `BB 07 00`. */
		const val POST_LOGIN_MS: Long = 1_000

		/** Um sync de passos e sono com o timeout de 4 s do `33 01`. */
		const val SYNC_MS: Long = 15_000
	}
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun runCrp(body: suspend TestScope.(CrpRig) -> Unit): TestResult = runTest {
	val rig = CrpRig(this)
	try {
		body(rig)
	} finally {
		rig.session.close()
		runCurrent()
	}
}

/**
 * O Condor Reebok (família CRP) pelo [CoreBridge]: o nome resolve o perfil (inclusive pelo prefixo `P85`), o `READY`
 * pede ao host o vínculo Bluetooth clássico uma vez (e não pede quando o sistema já o tem, quando falhou ou quando a
 * plataforma não suporta, até o próximo `pairDevice`), o `forgetDevice` manda o `F8`, remove o vínculo do sistema e só
 * então apaga o pareamento e resolve, e os avisos espontâneos do relógio (bateria com carregamento, passos de FEE1)
 * viram `onBatteryStatus` e `onPedometer`. O SMA e o iDO nunca chamam os métodos de vínculo do host.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreBridgeCrpTest {
	private val requestBond = "host requestClassicBond $ADDRESS"
	private val ready = """event onConnectStateChanged {"state":2,"sessionState":"READY","phase":null}"""

	@Test
	fun pairDeviceWithCondorReebokResolvesTheCrpProfile() = runCrp { rig ->
		for (name in listOf("Condor Reebok", "P85", "condorreebok", "P85-1234", " P85 ")) {
			assertEquals("condorreebok", rig.bridge.resolveProfile(name)?.id, name)
		}
		assertNull(rig.bridge.resolveProfile("P8"))
		assertEquals("fitgps", rig.bridge.resolveProfile("fitGPS")?.id)

		val pair = rig.pairReady()
		assertNull(pair.code)
		assertEquals(ConnectionState.READY, rig.session.state)
		assertEquals(CorePairing(ADDRESS, "condorreebok", "Condor Reebok"), rig.host.pairing)
		assertTrue("host setRecordingDeviceName P85" in rig.journal.lines, rig.journal.lines.joinToString("\n"))
		val phases = rig.sink.payloadsOf("onConnectStateChanged").mapNotNull { it["phase"] }
		assertEquals(listOf("VERSION"), phases)
		assertEquals(listOf("5A/00", "B7/0E", "BB/16"), rig.watch.labels().take(3))
	}

	@Test
	fun readyAsksTheHostForTheClassicBondOnce() = runCrp { rig ->
		rig.pairReady()
		val lines = rig.journal.lines
		val saved = lines.indexOfFirst { it.startsWith("host savePairing") }
		assertTrue(saved >= 0, lines.joinToString("\n"))
		// Efeitos do host antes do evento: grava o pareamento, pede o vínculo e só então emite o READY.
		assertEquals(listOf(requestBond, ready), lines.subList(saved + 1, saved + 3))
		assertEquals(listOf("hasClassicBond($ADDRESS)"), rig.host.queries.filter { it.startsWith("hasClassicBond") })
		assertTrue(rig.logger.withTag("CoreBridge/classicBond").contains("Requesting classic bond with $ADDRESS"))

		// SYNCING -> READY não é um handshake: nem pergunta ao host de novo.
		rig.sync()
		assertEquals(1, rig.journal.starting("host requestClassicBond").size)
		assertEquals(1, rig.host.queries.count { it.startsWith("hasClassicBond") })

		// Depois do vínculo, os syncs seguintes não consultam o sistema nem logam nada do vínculo.
		rig.bridge.onClassicBondResult(ADDRESS, ClassicBondResult.BONDED, "bonded")
		rig.host.classicBonded = true
		rig.sync()
		rig.sync()
		assertEquals(1, rig.host.queries.count { it.startsWith("hasClassicBond") })
		assertTrue(rig.logger.withTag("CoreBridge/classicBond").none { "already exists" in it })

		// O handshake seguinte (Bluetooth desligado e religado) só confere que o vínculo continua no sistema.
		rig.bluetoothCycle()
		assertEquals(1, rig.journal.starting("host requestClassicBond").size)
		assertEquals(2, rig.host.queries.count { it.startsWith("hasClassicBond") })
		assertTrue(rig.journal.starting("event onError").isEmpty(), rig.journal.lines.joinToString("\n"))
	}

	@Test
	fun existingClassicBondIsNotRequested() = runCrp { rig ->
		rig.host.classicBonded = true
		rig.pairReady()
		assertTrue(rig.classicLines().isEmpty(), rig.journal.lines.joinToString("\n"))
		assertEquals(listOf("hasClassicBond($ADDRESS)"), rig.host.queries.filter { it.startsWith("hasClassicBond") })
		assertEquals(
			listOf(LogLevel.INFO to "Classic bond with $ADDRESS already exists"),
			rig.logger.messages.filter { it.second == "CoreBridge/classicBond" }.map { it.first to it.third },
		)
	}

	@Test
	fun aResultFromInsideTheRequestClearsThePendingRequest() = runCrp { rig ->
		// O host Android responde BONDED de dentro do pedido quando o vínculo apareceu entre a consulta e o pedido.
		rig.host.onRequestClassicBond = { address -> rig.bridge.onClassicBondResult(address, ClassicBondResult.BONDED, "already bonded") }
		rig.pairReady()
		assertEquals(1, rig.journal.starting("host requestClassicBond").size)
		rig.host.onRequestClassicBond = null
		rig.bluetoothCycle()
		assertEquals(2, rig.journal.starting("host requestClassicBond").size, "nothing left in flight")
	}

	@Test
	fun classicBondFailureEmitsOnErrorWithoutRequestId() = runCrp { rig ->
		rig.pairReady()
		rig.journal.clear()
		rig.bridge.onClassicBondResult(ADDRESS, ClassicBondResult.FAILED, "Classic bond failed after 2 attempts")
		assertEquals(
			listOf(
				"""event onError {"requestId":-1,"code":"CLASSIC_BOND_FAILED","message":"Classic bond failed after 2 attempts","method":"classicBond"}""",
			),
			rig.journal.lines,
		)
		assertEquals(
			"method=classicBond requestId=-1 code=CLASSIC_BOND_FAILED message=Classic bond failed after 2 attempts",
			rig.logger.withTag("CoreBridge/emitError").single(),
		)
		assertEquals(ConnectionState.READY, rig.session.state, "the BLE session stays ready")
		assertEquals(0, rig.bridge.pendingRequests)
	}

	@Test
	fun declinedBondIsNotRequestedAgainUntilPairDevice() = runCrp { rig ->
		rig.pairReady()
		rig.bridge.onClassicBondResult(ADDRESS, ClassicBondResult.FAILED, "declined")
		rig.journal.clear()
		rig.host.queries.clear()

		rig.bluetoothCycle()
		rig.sync()
		assertTrue(rig.classicLines().isEmpty(), rig.journal.lines.joinToString("\n"))
		assertTrue(rig.host.queries.none { it.startsWith("hasClassicBond") }, rig.host.queries.toString())

		rig.bridge.disconnectCurrentDevice(rig.completion("disconnectCurrentDevice"))
		rig.advance(1_000)
		rig.journal.clear()
		rig.pairReady()
		assertEquals(listOf(requestBond), rig.classicLines())
	}

	@Test
	fun unsupportedClassicBondOnlyLogs() = runCrp { rig ->
		val hint = "iOS apps cannot create a classic Bluetooth bond; pair calls in Settings > Bluetooth"
		rig.pairReady()
		rig.journal.clear()
		rig.bridge.onClassicBondResult(ADDRESS, ClassicBondResult.UNSUPPORTED, hint)
		assertTrue(rig.journal.lines.isEmpty(), rig.journal.lines.joinToString("\n"))
		assertTrue(LogLevel.WARN to hint in rig.logger.messages.filter { it.second == "CoreBridge/classicBond" }.map { it.first to it.third })
		rig.sync()
		assertTrue(rig.classicLines().isEmpty(), rig.journal.lines.joinToString("\n"))
	}

	@Test
	fun forgetDeviceWritesF8ThenRemovesTheSystemBondThenResolves() = runCrp { rig ->
		rig.pairReady()
		rig.bridge.onClassicBondResult(ADDRESS, ClassicBondResult.BONDED, "bonded")
		rig.journal.clear()
		rig.journalWrites()
		val forget = rig.completion("forgetDevice")
		rig.bridge.forgetDevice(forget)
		rig.run()
		rig.advance(1_000)
		assertEquals(
			listOf(
				"host clearPairing",
				"watch write FEEA2005F8",
				"""event onConnectStateChanged {"state":0,"sessionState":"DISCONNECTED","phase":null}""",
				"host removeClassicBond $ADDRESS",
				"host clearPairing",
				"resolve forgetDevice null",
			),
			rig.journal.lines,
		)
		assertEquals("F8", rig.watch.labels().last())
		assertNull(rig.host.pairing)
		assertEquals(1, forget.settled)
	}

	@Test
	fun forgetWithABondInFlightOnlyLogsTheCancelledResult() = runCrp { rig ->
		// O diálogo do sistema ainda está aberto (pedido sem resposta) quando o usuário esquece o relógio.
		rig.pairReady()
		assertEquals(1, rig.journal.starting("host requestClassicBond").size)
		rig.journal.clear()
		rig.bridge.forgetDevice(rig.completion("forgetDevice"))
		rig.run()
		rig.advance(1_000)
		assertEquals("host removeClassicBond $ADDRESS", rig.journal.lines[rig.journal.lines.size - 3], rig.journal.lines.joinToString("\n"))
		assertEquals("resolve forgetDevice null", rig.journal.lines.last())
		assertTrue(rig.logger.withTag("CoreBridge/classicBond").contains("Classic bond request for $ADDRESS abandoned: the watch was forgotten"))

		// O host Android encerra o pedido cancelado com FAILED: só log, sem onError e sem recusa.
		rig.journal.clear()
		rig.bridge.onClassicBondResult(ADDRESS, ClassicBondResult.FAILED, "Classic bond request for $ADDRESS cancelled: the watch is being forgotten")
		assertTrue(rig.journal.lines.isEmpty(), rig.journal.lines.joinToString("\n"))
		assertTrue(rig.logger.withTag("CoreBridge/classicBond").any { it.startsWith("Classic bond result for forgotten $ADDRESS ignored: FAILED") })
		assertEquals(0, rig.logger.withTag("CoreBridge/emitError").size)

		// Sem recusa nem pedido preso: a reconexão seguinte (sem pairDevice) pede o vínculo de novo.
		rig.bridge.startPairingAttempt(ADDRESS, "Condor Reebok", rig.completion("startPairingAttempt"))
		rig.run()
		rig.advance(CrpRig.HANDSHAKE_MS + CrpRig.POST_LOGIN_MS)
		assertEquals(ConnectionState.READY, rig.session.state)
		assertEquals(listOf(requestBond), rig.classicLines())
	}

	@Test
	fun forgetWithoutASessionRemovesTheBondOfTheSavedWatch() = runCrp { rig ->
		// Processo novo: o relógio salvo nunca conectou nesta execução, e a sessão não tem endereço.
		rig.host.pairing = CorePairing(ADDRESS, "condorreebok", "Condor Reebok")
		rig.bridge.forgetDevice(rig.completion("forgetDevice"))
		rig.run()
		rig.advance(1_000)
		assertEquals(
			listOf("host clearPairing", "host removeClassicBond $ADDRESS", "host clearPairing", "resolve forgetDevice null"),
			rig.journal.lines,
		)
		assertTrue(rig.transport.writes.isEmpty())
	}

	@Test
	fun disconnectCurrentDeviceKeepsTheClassicBond() = runCrp { rig ->
		rig.pairReady()
		rig.bridge.onClassicBondResult(ADDRESS, ClassicBondResult.BONDED, "bonded")
		rig.journal.clear()
		rig.journalWrites()
		rig.bridge.disconnectCurrentDevice(rig.completion("disconnectCurrentDevice"))
		rig.run()
		rig.advance(1_000)
		assertEquals(
			listOf(
				"""event onConnectStateChanged {"state":0,"sessionState":"DISCONNECTED","phase":null}""",
				"resolve disconnectCurrentDevice null",
			),
			rig.journal.lines,
		)
		assertFalse("F8" in rig.watch.labels())
		assertEquals(CorePairing(ADDRESS, "condorreebok", "Condor Reebok"), rig.host.pairing)
	}

	@Test
	fun smaAndIdoNeverTouchTheClassicBond() = runTest {
		// SMA: pareia, sincroniza, esquece.
		val sma = BridgeHarness(this)
		try {
			sma.pairReady()
			sma.bridge.syncDataHistory(sma.completion("syncDataHistory"))
			sma.run()
			sma.bridge.forgetDevice(sma.completion("forgetDevice"))
			sma.run()
			sma.advance(4_001)
			assertTrue(sma.journal.lines.none { "ClassicBond" in it }, sma.journal.lines.joinToString("\n"))
			assertTrue(sma.host.queries.none { "ClassicBond" in it }, sma.host.queries.toString())
		} finally {
			sma.h.session.close()
			runCurrent()
		}

		// iDO: pareia (com o toque no relógio), esquece.
		val clock = TestClock.at("2026-10-29T22:08:49-03:00")
		val (transport, watch) = FakeTransport.scriptedIdo { delayMs, action ->
			backgroundScope.launch {
				delay(delayMs)
				action()
			}
		}
		val session = WatchSession(transport, SessionTestLogger(), InMemorySampleStore(), clock, StandardTestDispatcher(testScheduler))
		transport.events = session.events
		val journal = Journal()
		val host = FakeCoreHost(journal)
		val bridge = CoreBridge(session, BridgeTestLogger(), host) { TimeZone.of(clock.zone) }
		bridge.attach(RecordingSink(journal))
		try {
			bridge.pairDevice(ADDRESS, "Life Ultra GPS", RecordingCompletion("pairDevice", journal))
			runCurrent()
			advanceTimeBy(watch.confirmDelayMs + 1_001)
			runCurrent()
			assertEquals(ConnectionState.READY, session.state)
			bridge.forgetDevice(RecordingCompletion("forgetDevice", journal))
			runCurrent()
			advanceTimeBy(10_000)
			runCurrent()
			assertTrue(journal.lines.none { "ClassicBond" in it }, journal.lines.joinToString("\n"))
			assertTrue(host.queries.none { "ClassicBond" in it }, host.queries.toString())
		} finally {
			session.close()
			runCurrent()
		}
	}

	@Test
	fun scannedP85IsReportedAsCondorReebok() = runCrp { rig ->
		for (bleName in listOf("P85", "P85-1234", "Condor Reebok")) {
			assertEquals(Profiles.CONDOR_REEBOK, DeviceRegistry.identify(bleName, emptyList()), bleName)
		}
		rig.bridge.onScannedDevice(Profiles.CONDOR_REEBOK, ADDRESS, -60, "P85")
		assertEquals(
			mapOf("name" to "Condor Reebok", "deviceId" to ADDRESS, "rssi" to -60, "bleName" to "P85", "profileId" to "condorreebok"),
			rig.sink.payloadsOf("onScannedDevice").single(),
		)
	}

	@Test
	fun spontaneousBatteryBecomesOnBatteryStatusWithCharging() = runCrp { rig ->
		rig.pairReady()
		rig.journal.clear()
		rig.watch.pushBattery(0x9B)
		rig.run()
		rig.watch.pushBattery(0x31)
		rig.run()
		assertEquals(
			listOf(
				"""event onBatteryStatus {"percentage":55,"charging":true,"battery":"55%"}""",
				"""event onBatteryStatus {"percentage":49,"charging":false,"battery":"49%"}""",
			),
			rig.journal.lines,
		)
		assertEquals(true, rig.sink.payloadsOf("onBatteryStatus").first()["charging"])

		// A leitura pedida pelo JS aplica a mesma regra e resolve depois do evento.
		rig.journal.clear()
		rig.watch.battery = 0x9B
		rig.bridge.getBatteryStatus(rig.completion("getBatteryStatus"))
		rig.advance(1_000)
		assertEquals(
			listOf(
				"""event onBatteryStatus {"percentage":55,"charging":true,"battery":"55%"}""",
				"resolve getBatteryStatus null",
			),
			rig.journal.lines,
		)
	}

	@Test
	fun liveStepsBecomeOnPedometer() = runCrp { rig ->
		rig.pairReady()
		rig.sync()
		rig.journal.clear()
		rig.watch.pushSteps(CrpStepsTotals(16, 13, 1, null))
		rig.advance(1_000)
		rig.watch.pushSteps(CrpStepsTotals(77, 63, 4, null))
		rig.advance(3_000)
		val pedometer = rig.journal.starting("event onPedometer")
		assertTrue(pedometer.isNotEmpty(), rig.journal.lines.joinToString("\n"))
		assertEquals(
			"""event onPedometer {"pedometer":77,"calorie":4,"distance":63.0,"date":"2026-10-31T00:00:00-03:00"}""",
			pedometer.last(),
		)
		assertTrue(rig.journal.starting("resolve").isEmpty(), "no Promise for spontaneous steps")
	}
}
