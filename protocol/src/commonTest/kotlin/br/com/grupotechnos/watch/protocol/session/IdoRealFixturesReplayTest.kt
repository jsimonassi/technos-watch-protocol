package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.Battery
import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.DeviceInfo
import br.com.grupotechnos.watch.protocol.profile.DeviceRegistry
import br.com.grupotechnos.watch.protocol.profile.Family
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.testing.FakeTransport
import br.com.grupotechnos.watch.protocol.testing.Fixtures
import br.com.grupotechnos.watch.protocol.testing.IdoReplayScenario
import br.com.grupotechnos.watch.protocol.testing.IdoTxMasker
import br.com.grupotechnos.watch.protocol.testing.Recording
import br.com.grupotechnos.watch.protocol.testing.RecordingLoader
import br.com.grupotechnos.watch.protocol.testing.ReplayAction
import br.com.grupotechnos.watch.protocol.testing.REPLAY_ADDRESS
import br.com.grupotechnos.watch.protocol.testing.ReplayOutcome
import br.com.grupotechnos.watch.protocol.testing.SessionEvent
import br.com.grupotechnos.watch.protocol.testing.runReplay
import br.com.grupotechnos.watch.protocol.transport.WriteType
import br.com.grupotechnos.watch.protocol.util.Hex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Toda gravação de `fixtures/real` cujo `device` é de um perfil iDO vira um replay estrito, com as ações deduzidas dos
 * TX ([IdoReplayScenario]) e a máscara iDO ([IdoTxMasker]), sobre uma `WatchSession` de verdade. O
 * `RealFixturesReplayTest` pula essas gravações (a dedução dele é do SMA).
 *
 * A gravação versionada [COMMITTED_IDO_RECORDING] foi reconstruída do log de bytes da SDK iDO de produção, e não do
 * rádio (`docs/fixtures.md`, seção "Life Ultra GPS"). Mesmo assim ela prova o caminho inteiro com os bytes reais: o
 * pareamento manda a resposta ao desafio gravado byte a byte, o acerto de hora tem o fuso gravado (`DC 05`), e os
 * valores saem iguais aos da tela do relógio (bateria 14 %, firmware 1.1.1, 26 passos).
 *
 * A gravação do iPhone ([IOS_IDO_RECORDING], 2026-10-30) é a primeira do Life Ultra GPS por `CoreBluetooth`: a mesma
 * conversa do Android, com escrita sem resposta em `0af6` e um desafio de bind novo. O cabeçalho traz o MTU de 515 que o
 * iOS ofereceu, que a sessão não usa (o perfil não pede MTU e os frames continuam de até 20 bytes).
 *
 * Roda nos quatro targets, como o replay do SMA; cada gravação imprime uma linha `REPLAY` no `system-out` do relatório.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdoRealFixturesReplayTest {
	@Test
	fun everyIdoRealRecordingReplays() = runTest {
		val files = Fixtures.jsonlUnder("real").filter { path ->
			DeviceRegistry.identify(RecordingLoader.load(path).header.device, emptyList())?.family == Family.IDO
		}
		val listed = files.map { Fixtures.relative(it) }
		assertTrue(COMMITTED_IDO_RECORDING in listed, "iDO recordings under ${Fixtures.path("real")}: $listed")
		val failures = files.mapNotNull { path ->
			val recording = RecordingLoader.load(path)
			val outcome = replay(recording)
			try {
				problems(recording, outcome)
			} finally {
				outcome.session.close()
				runCurrent()
			}
		}
		if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
	}

	@Test
	fun committedRecordingGivesWhatTheWatchShowed() = runTest {
		val recording = RecordingLoader.loadFixture(COMMITTED_IDO_RECORDING)
		val outcome = replay(recording)
		try {
			assertEquals(
				listOf(ReplayAction.Pair, ReplayAction.ReadDeviceInfo, ReplayAction.Sync(listOf(DataKind.STEPS)), ReplayAction.ReadBattery),
				outcome.requestIds.map { it.first },
			)
			assertEquals(emptyList(), outcome.replay.problems())
			assertEquals(8, outcome.replay.matchedTx)
			val ids = outcome.requestIds.map { it.second }
			assertTrue("HANDSHAKING[BIND]" in outcome.listener.stateTrail())
			assertEquals(SessionEvent.Completed(ids[0]), outcome.listener.terminal(ids[0]))
			assertEquals(
				SessionEvent.Info(ids[1], DeviceInfo("lifeultragps", REPLAY_ADDRESS, "Life Ultra GPS", "1.1.1", null)),
				outcome.listener.terminal(ids[1]),
			)
			assertEquals(SessionEvent.BatteryLevel(ids[3], Battery(14)), outcome.listener.terminal(ids[3]))
			val day = outcome.session.getStepsDays(3).single()
			// 13 kcal são as totais do 02/A0; a tela mostrava 0, as de atividade (IdoLiveData).
			assertEquals(Triple(26, 13, 25.0), Triple(day.steps, day.calories, day.distanceMeters))
			// O acerto de hora automático mandou o fuso de São Paulo como a SDK (1500 = DC 05, linha 5360).
			val setTime = outcome.transport.writes.single { it[0].toInt() == 0x03 && it[1].toInt() == 0x01 }
			assertEquals("DC05", Hex.encode(setTime.copyOfRange(14, 16)))
		} finally {
			outcome.session.close()
			runCurrent()
		}
	}

	@Test
	fun iosRecordingGivesTheValuesTheWatchSent() = runTest {
		val recording = RecordingLoader.loadFixture(IOS_IDO_RECORDING)
		assertEquals(515, recording.header.mtu)
		val outcome = replay(recording)
		try {
			assertEquals(
				listOf(
					ReplayAction.Pair,
					ReplayAction.ReadDeviceInfo,
					ReplayAction.ReadDeviceInfo,
					ReplayAction.ReadBattery,
					ReplayAction.SetTime,
					ReplayAction.Sync(listOf(DataKind.STEPS)),
					ReplayAction.Sync(listOf(DataKind.STEPS)),
				),
				outcome.requestIds.map { it.first },
			)
			assertEquals(emptyList(), outcome.replay.problems())
			assertEquals(12, outcome.replay.recordedTx)
			assertEquals(12, outcome.replay.matchedTx)
			assertEquals(ConnectionState.READY, outcome.session.state)
			val ids = outcome.requestIds.map { it.second }
			assertTrue("HANDSHAKING[BIND]" in outcome.listener.stateTrail())
			assertEquals(SessionEvent.Completed(ids[0]), outcome.listener.terminal(ids[0]))
			assertEquals(
				SessionEvent.Info(ids[1], DeviceInfo("lifeultragps", REPLAY_ADDRESS, "Life Ultra GPS", "1.1.1", null)),
				outcome.listener.terminal(ids[1]),
			)
			assertEquals(SessionEvent.BatteryLevel(ids[3], Battery(12)), outcome.listener.terminal(ids[3]))
			// O MTU do cabeçalho não chega à sessão: o perfil pede 23, e nenhum frame passa de 20 bytes.
			assertTrue(outcome.transport.writes.all { it.size <= 20 })
			// Sem resposta, que a 0af6 declarou no iPhone ([read|write|writeWithoutResponse]).
			assertEquals(setOf(WriteType.WITHOUT_RESPONSE), outcome.transport.writeTypes.toSet())
			val day = outcome.session.getStepsDays(3).single()
			assertEquals("2026-10-30", day.dayKey)
			// O que o 02/A0 trouxe; as 7 kcal são as totais, com a de repouso (protocolo-ido.md, seção 3.3).
			assertEquals(Triple(57, 7, 59.0), Triple(day.steps, day.calories, day.distanceMeters))
			val setTimes = outcome.transport.writes.filter { it[0].toInt() == 0x03 && it[1].toInt() == 0x01 }
			assertEquals(listOf("DC05", "DC05"), setTimes.map { Hex.encode(it.copyOfRange(14, 16)) })
		} finally {
			outcome.session.close()
			runCurrent()
		}
	}

	private fun TestScope.replay(recording: Recording): ReplayOutcome =
		runReplay(
			recording,
			actions = IdoReplayScenario.deduce(recording),
			configure = { transport ->
				transport.services = listOf(FakeTransport.serviceOf(Profiles.IDO_MAIN))
				transport.notifyCharacteristic = Profiles.IDO_MAIN.notify
			},
			profileId = Profiles.MORMAII_LIFE_ULTRA_GPS.id,
			newMasker = { IdoTxMasker() },
		)

	private fun problems(recording: Recording, outcome: ReplayOutcome): String? {
		val replay = outcome.replay
		println(
			"REPLAY ${Fixtures.relative(recording.source)}: ${recording.packets.size} packets, " +
				"TX matched ${replay.matchedTx}/${replay.recordedTx}, final state ${outcome.session.state}",
		)
		val problems = replay.problems().toMutableList()
		val actions = outcome.requestIds.map { it.first }
		val expected = expectedFinalState(actions)
		if (outcome.session.state != expected) problems += "final state ${outcome.session.state}, expected $expected"
		for ((action, id) in outcome.requestIds) {
			val terminals = outcome.listener.terminals(id)
			if (terminals.size != 1 || terminals.single() is SessionEvent.Error) problems += "$action #$id ended with $terminals"
		}
		return if (problems.isEmpty()) null else "${recording.source} (actions $actions):\n" + problems.joinToString("\n")
	}
}

/** A gravação iDO versionada (Life Ultra GPS, firmware 1.1.1), caminho relativo a `fixtures/`. */
internal const val COMMITTED_IDO_RECORDING: String = "real/lifeultragps/1.1.1/pair-commands.jsonl"

/** A gravação do Life Ultra GPS (firmware 1.1.1) feita no iPhone (2026-10-30), caminho relativo a `fixtures/`. */
internal const val IOS_IDO_RECORDING: String = "real/lifeultragps/1.1.1/ios-pair-info-time-steps-sync.jsonl"
