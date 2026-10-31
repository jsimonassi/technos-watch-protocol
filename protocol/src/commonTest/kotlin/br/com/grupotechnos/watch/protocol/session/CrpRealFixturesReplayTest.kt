package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.profile.DeviceRegistry
import br.com.grupotechnos.watch.protocol.profile.Family
import br.com.grupotechnos.watch.protocol.testing.CrpReplayScenario
import br.com.grupotechnos.watch.protocol.testing.Fixtures
import br.com.grupotechnos.watch.protocol.testing.RecordingLoader
import br.com.grupotechnos.watch.protocol.testing.SessionEvent
import br.com.grupotechnos.watch.protocol.testing.runCrpReplay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.fail

/**
 * Toda gravação de `fixtures/real` cujo `device` é de um perfil CRP vira um replay estrito, com as ações deduzidas dos
 * pedidos ([CrpReplayScenario]) e a máscara CRP, sobre uma `WatchSession` de verdade. O `RealFixturesReplayTest` pula
 * essas gravações. As fixtures de referência (`fixtures/reference/`), gravadas de outro app, não entram aqui.
 *
 * Ainda não há gravação real do Condor Reebok feita com o app de exemplo: o teste imprime
 * `REPLAY crp: no recordings yet` e passa. Quando o export (`frames-*.jsonl`) for copiado para
 * `fixtures/real/condorreebok/<firmware>/`, ele roda sem mudança aqui, nos quatro targets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrpRealFixturesReplayTest {
	@Test
	fun everyCrpRealRecordingReplays() = runTest {
		val files = Fixtures.jsonlUnder("real").filter { path ->
			DeviceRegistry.identify(RecordingLoader.load(path).header.device, emptyList())?.family == Family.CRP
		}
		if (files.isEmpty()) {
			println("REPLAY crp: no recordings yet")
			return@runTest
		}
		val failures = files.mapNotNull { path ->
			val recording = RecordingLoader.load(path)
			val actions = try {
				CrpReplayScenario.deduce(recording)
			} catch (error: IllegalArgumentException) {
				println("REPLAY ${Fixtures.relative(path)}: not replayed (${error.message})")
				return@mapNotNull "${recording.source}: ${error.message}"
			}
			val outcome = runCrpReplay(recording, actions)
			try {
				val replay = outcome.replay
				println(
					"REPLAY ${Fixtures.relative(path)}: ${recording.packets.size} packets, TX matched ${replay.matchedTx}/${replay.recordedTx}, " +
						"READ matched ${replay.matchedReads}/${replay.recordedReads}, final state ${outcome.session.state}",
				)
				val problems = replay.problems().toMutableList()
				val expected = expectedFinalState(actions)
				if (outcome.session.state != expected) problems += "final state ${outcome.session.state}, expected $expected"
				for ((action, id) in outcome.requestIds) {
					val terminals = outcome.listener.terminals(id)
					if (terminals.size != 1 || terminals.single() is SessionEvent.Error) problems += "$action #$id ended with $terminals"
				}
				if (problems.isEmpty()) null else "${recording.source} (actions $actions):\n" + problems.joinToString("\n")
			} finally {
				outcome.session.close()
				runCurrent()
			}
		}
		if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
	}
}
