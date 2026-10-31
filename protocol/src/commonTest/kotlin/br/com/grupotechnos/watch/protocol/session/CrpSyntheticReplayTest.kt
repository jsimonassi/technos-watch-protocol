package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.Battery
import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.DeviceInfo
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.testing.CrpReplayScenario
import br.com.grupotechnos.watch.protocol.testing.FakeTransport
import br.com.grupotechnos.watch.protocol.testing.Fixtures
import br.com.grupotechnos.watch.protocol.testing.PacketMasker
import br.com.grupotechnos.watch.protocol.testing.RecordedPacket
import br.com.grupotechnos.watch.protocol.testing.Recording
import br.com.grupotechnos.watch.protocol.testing.RecordingLoader
import br.com.grupotechnos.watch.protocol.testing.REPLAY_ADDRESS
import br.com.grupotechnos.watch.protocol.testing.ReplayAction
import br.com.grupotechnos.watch.protocol.testing.SessionEvent
import br.com.grupotechnos.watch.protocol.testing.SyntheticCondorReebokFixture
import br.com.grupotechnos.watch.protocol.testing.runCrpReplay
import br.com.grupotechnos.watch.protocol.testing.runReplay
import br.com.grupotechnos.watch.protocol.util.Hex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replay estrito da gravação sintética do Condor Reebok (`fixtures/synthetic/condorreebok-pair-sync.jsonl`): a sessão
 * inteira (FakeTransport → SessionEngine → CrpDriver → codec) reproduz os 12 TX e as 3 leituras, com os RX e READ
 * reais da captura do app de produção. Roda nos quatro targets e imprime uma linha `REPLAY`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrpSyntheticReplayTest {
	private val actions = listOf(
		ReplayAction.Pair,
		ReplayAction.ReadBattery,
		ReplayAction.ReadDeviceInfo,
		ReplayAction.Sync(listOf(DataKind.STEPS, DataKind.SLEEP)),
	)

	@Test
	fun scenarioIsDeducedFromTheRequests() {
		val recording = RecordingLoader.loadFixture(SyntheticCondorReebokFixture.RELATIVE_PATH)
		assertEquals(
			listOf(
				"R:2a29", "5A/00", "B7/0E", "BB/16", "31", "BB/07", "R:2a19", "5A/01", "R:fee1", "59/00", "59/01", "59/02",
				"59/03", "33/01", "32",
			),
			CrpReplayScenario.requests(recording),
		)
		assertEquals(actions, CrpReplayScenario.deduce(recording))
	}

	@Test
	fun pairBatteryFirmwareSyncReplaysStrictly() = runTest {
		val recording = RecordingLoader.loadFixture(SyntheticCondorReebokFixture.RELATIVE_PATH)
		val outcome = runCrpReplay(recording, actions)
		try {
			val replay = outcome.replay
			println(
				"REPLAY ${Fixtures.relative(recording.source)}: ${recording.packets.size} packets, " +
					"TX matched ${replay.matchedTx}/${replay.recordedTx}, READ matched ${replay.matchedReads}/${replay.recordedReads}, " +
					"final state ${outcome.session.state}",
			)
			assertEquals(emptyList(), replay.problems())
			assertEquals(12, replay.recordedTx)
			assertEquals(12, replay.matchedTx)
			assertEquals(3, replay.recordedReads)
			assertEquals(3, replay.matchedReads)
			assertTrue(replay.remaining.isEmpty(), "unconsumed: ${replay.remaining}")
			assertEquals(ConnectionState.READY, outcome.session.state)
			assertEquals(
				listOf("CONNECTING", "CONFIGURING", "HANDSHAKING", "HANDSHAKING[VERSION]", "READY", "SYNCING", "READY"),
				outcome.listener.stateTrail(),
			)
			val ids = outcome.requestIds.map { it.second }
			assertEquals(SessionEvent.Completed(ids[0]), outcome.listener.terminal(ids[0]))
			assertEquals(SessionEvent.BatteryLevel(ids[1], Battery(50)), outcome.listener.terminal(ids[1]))
			assertEquals(
				SessionEvent.Info(ids[2], DeviceInfo("condorreebok", REPLAY_ADDRESS, "P85", "MOY-VS02-2.0.0", "MOYOUNG-V2")),
				outcome.listener.terminal(ids[2]),
			)
			assertEquals(SessionEvent.SyncFinished(ids[3]), outcome.listener.terminal(ids[3]))
			// O relógio da captura estava sem uso: nenhum dia de passos, nenhuma noite.
			assertEquals(emptyList(), outcome.session.getStepsDays(3))
			assertEquals(emptyList(), outcome.session.getSleepSessions(3))
			// O aviso de bateria que o relógio mandou sozinho no fim vira onBattery sem pedido.
			assertEquals(
				SessionEvent.BatteryLevel(-1, Battery(50)),
				outcome.listener.events.filterIsInstance<SessionEvent.BatteryLevel>().last(),
			)
			// As três leituras vão para a gravação como READ.
			assertEquals(3, outcome.logger.frames.count { it.direction == FrameDirection.READ })
			outcome.listener.assertOneTerminalEach(ids)
			assertTrue(outcome.logger.unexpected().isEmpty(), outcome.logger.unexpected().toString())
		} finally {
			outcome.session.close()
			runCurrent()
		}
	}

	@Test
	fun everyRxAndReadComesFromTheReference() {
		val synthetic = RecordingLoader.loadFixture(SyntheticCondorReebokFixture.RELATIVE_PATH)
		val reference = RecordingLoader.loadFixture(SyntheticCondorReebokFixture.REFERENCE_PATH)
		val real = reference.packets.map { Triple(it.direction, it.characteristic, Hex.encode(it.value)) }.toSet()
		val copied = synthetic.packets.filter { it.direction != FrameDirection.TX }
		assertEquals(12, copied.size)
		for (packet in copied) {
			assertTrue(Triple(packet.direction, packet.characteristic, Hex.encode(packet.value)) in real, "not in the reference: $packet")
		}
	}

	@Test
	fun shiftedReplayClockIsMaskedOnlyInTheTimeFrame() = runTest {
		val recording = RecordingLoader.loadFixture(SyntheticCondorReebokFixture.RELATIVE_PATH)
		// O relógio do replay 7 s depois do recordedAt: o 31 sai com outros segundos e só a máscara o deixa casar.
		val shifted = Recording(recording.header.copy(recordedAt = "2026-10-31T15:43:19.209-03:00"), recording.packets, "shifted")
		val recordedTime = recording.packets.single { it.direction == FrameDirection.TX && Hex.encode(it.value).startsWith("FEEA200A31") }
		val masked = runCrpReplay(shifted, actions)
		try {
			assertEquals(emptyList(), masked.replay.problems())
			assertEquals(12, masked.replay.matchedTx)
			assertEquals(3, masked.replay.matchedReads)
			val sentTime = masked.logger.frames.single { it.direction == FrameDirection.TX && Hex.encode(it.value).startsWith("FEEA200A31") }
			assertEquals("FEEA200A316AE59C1008", Hex.encode(recordedTime.value))
			assertEquals("FEEA200A316AE59C1708", Hex.encode(sentTime.value))
		} finally {
			masked.session.close()
			runCurrent()
		}
		// Sem a máscara, o mesmo replay acusa o 31 (e só ele).
		val unmasked = runReplay(
			shifted,
			actions = actions,
			profileId = Profiles.CONDOR_REEBOK.id,
			newMasker = { PacketMasker { it } },
			configure = { transport ->
				transport.services = FakeTransport.crp().services
				transport.notifyCharacteristic = Profiles.CRP_FEEA.notify
			},
		)
		try {
			val problems = unmasked.replay.problems()
			assertEquals(1, problems.count { it.contains("differs") }, problems.toString())
			assertTrue(problems.single { it.contains("differs") }.contains("FEEA200A316AE59C1708"), problems.toString())
		} finally {
			unmasked.session.close()
			runCurrent()
		}
	}

	@Test
	fun strictReplayCatchesAChangedTx() = runTest {
		val recording = RecordingLoader.loadFixture(SyntheticCondorReebokFixture.RELATIVE_PATH)
		// Uma gravação em que o relógio esperava o 5A 00 com byte2 0x10: a sessão manda 0x20 (MTU 517).
		val changed = recording.packets.map { packet ->
			if (packet.direction == FrameDirection.TX && Hex.encode(packet.value) == "FEEA20065A00") {
				RecordedPacket(packet.direction, packet.atMs, packet.characteristic, Hex.decode("FEEA10065A00"))
			} else {
				packet
			}
		}
		val outcome = runCrpReplay(Recording(recording.header, changed, "changed"), actions)
		try {
			assertTrue(outcome.replay.problems().any { it.contains("differs") }, outcome.replay.problems().toString())
		} finally {
			outcome.session.close()
			runCurrent()
		}
	}
}
