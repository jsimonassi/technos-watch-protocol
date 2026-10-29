package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.testing.Recording
import br.com.grupotechnos.watch.protocol.testing.RecordingLoader
import br.com.grupotechnos.watch.protocol.testing.ReplayAction
import br.com.grupotechnos.watch.protocol.testing.ReplayOutcome
import br.com.grupotechnos.watch.protocol.testing.ReplayScenario
import br.com.grupotechnos.watch.protocol.testing.SyntheticFitGpsFixture
import br.com.grupotechnos.watch.protocol.testing.runReplay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Toda gravação `.jsonl` em qualquer subpasta de `fixtures/real` vira um replay estrito, com as ações deduzidas dos próprios frames
 * ([ReplayScenario]). Basta copiar o arquivo exportado pelo app de exemplo para a pasta (`docs/fixtures.md`).
 *
 * Enquanto não houver gravação real, o teste passa sem fazer nada; o mesmo verificador roda na gravação sintética
 * para garantir que ele próprio funciona.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealFixturesReplayTest {
	@Test
	fun everyRealRecordingReplays() = runTest {
		val root = File(RecordingLoader.fixturesDir(), "real")
		val files = root.walkTopDown().filter { it.isFile && it.extension == "jsonl" }.sortedBy { it.path }.toList()
		if (files.isEmpty()) println("RealFixturesReplayTest: no recordings under $root yet")
		val failures = files.mapNotNull { file -> check(RecordingLoader.load(file)) }
		if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
	}

	@Test
	fun verifierAcceptsTheSyntheticRecording() = runTest {
		val recording = RecordingLoader.load(File(RecordingLoader.fixturesDir(), SyntheticFitGpsFixture.RELATIVE_PATH))
		val failure = check(recording)
		assertTrue(failure == null, failure)
	}

	/** `null` quando a gravação reproduz sem divergência; senão, a descrição do problema. */
	private fun TestScope.check(recording: Recording): String? {
		val actions = try {
			ReplayScenario.deduce(recording)
		} catch (error: IllegalArgumentException) {
			return "${recording.source}: ${error.message}"
		}
		val outcome = runReplay(recording, actions)
		try {
			val problems = outcome.replay.problems().toMutableList()
			val expected = if (actions.last() == ReplayAction.Forget) ConnectionState.DISCONNECTED else ConnectionState.READY
			if (outcome.session.state != expected) problems += "final state ${outcome.session.state}, expected $expected"
			problems += unfinished(outcome)
			return if (problems.isEmpty()) null else "${recording.source} (actions $actions):\n" + problems.joinToString("\n")
		} finally {
			outcome.session.close()
			runCurrent()
		}
	}

	private fun unfinished(outcome: ReplayOutcome): List<String> =
		outcome.requestIds.mapNotNull { (action, id) ->
			val terminals = outcome.listener.terminals(id)
			when {
				terminals.size != 1 -> "$action #$id ended with $terminals"
				terminals.single() is br.com.grupotechnos.watch.protocol.testing.SessionEvent.Error -> "$action #$id failed: ${terminals.single()}"
				else -> null
			}
		}
}
