package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.profile.DeviceRegistry
import br.com.grupotechnos.watch.protocol.profile.Family
import br.com.grupotechnos.watch.protocol.testing.Fixtures
import br.com.grupotechnos.watch.protocol.testing.Recording
import br.com.grupotechnos.watch.protocol.testing.RecordingLoader
import br.com.grupotechnos.watch.protocol.testing.ReplayAction
import br.com.grupotechnos.watch.protocol.testing.ReplayOutcome
import br.com.grupotechnos.watch.protocol.testing.ReplayScenario
import br.com.grupotechnos.watch.protocol.testing.SessionEvent
import br.com.grupotechnos.watch.protocol.testing.SyntheticFitGpsFixture
import br.com.grupotechnos.watch.protocol.testing.runReplay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Toda gravação `.jsonl` em qualquer subpasta de `fixtures/real` vira um replay estrito, com as ações deduzidas dos
 * próprios frames ([ReplayScenario]). Basta copiar o arquivo exportado pelo app de exemplo para a pasta
 * (`docs/fixtures.md`); a pasta `fixtures/` é input de todas as tarefas de teste do Gradle, então a gravação nova roda
 * na próxima execução.
 *
 * Está no `commonTest`: roda em `jvmTest`, `testDebugUnitTest`, `testReleaseUnitTest` e `iosSimulatorArm64Test`. Cada
 * arquivo reproduzido imprime uma linha `REPLAY <caminho>: <n> packets, TX matched <a>/<b>, final state <estado>` no
 * stdout do teste, que fica no `system-out` do relatório XML de cada tarefa (`protocol/build/test-results/<tarefa>/`).
 * É a prova de que a gravação rodou de verdade, e não foi pulada, naquele target. O mesmo verificador roda na gravação
 * sintética, para garantir que ele próprio funciona.
 *
 * A listagem de `fixtures/real` tem código próprio em cada target (`java.io.File` na JVM, `NSFileManager` no iOS). Para
 * uma listagem quebrada não pular todas as gravações em silêncio, a gravação versionada [COMMITTED_REAL_RECORDING] tem de
 * aparecer nela, senão o teste falha.
 *
 * A dedução e a máscara daqui são do SMA. Uma gravação cujo `device` do cabeçalho o `DeviceRegistry` identifica como de
 * outra família é pulada com uma linha `REPLAY <caminho>: skipped (family <família>, replayed by its family test)`; as do
 * iDO rodam no `IdoRealFixturesReplayTest` e as do CRP no `CrpRealFixturesReplayTest`. Um `device` desconhecido continua
 * sendo reproduzido como SMA.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealFixturesReplayTest {
	@Test
	fun everyRealRecordingReplays() = runTest {
		val files = Fixtures.jsonlUnder("real")
		val listed = files.map { Fixtures.relative(it) }
		assertTrue(
			COMMITTED_REAL_RECORDING in listed,
			"listing of ${Fixtures.path("real")} misses $COMMITTED_REAL_RECORDING; found $listed",
		)
		val failures = files.mapNotNull { path ->
			val recording = RecordingLoader.load(path)
			val family = DeviceRegistry.identify(recording.header.device, emptyList())?.family
			if (family != null && family != Family.SMA) {
				println("REPLAY ${Fixtures.relative(path)}: skipped (family $family, replayed by its family test)")
				return@mapNotNull null
			}
			check(recording)
		}
		if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
	}

	@Test
	fun verifierAcceptsTheSyntheticRecording() = runTest {
		val recording = RecordingLoader.loadFixture(SyntheticFitGpsFixture.RELATIVE_PATH)
		val failure = check(recording)
		assertTrue(failure == null, failure)
	}

	/** `null` quando a gravação reproduz sem divergência; senão, a descrição do problema. */
	private fun TestScope.check(recording: Recording): String? {
		val actions = try {
			ReplayScenario.deduce(recording)
		} catch (error: IllegalArgumentException) {
			println("REPLAY ${Fixtures.relative(recording.source)}: not replayed (${error.message})")
			return "${recording.source}: ${error.message}"
		}
		val outcome = runReplay(recording, actions)
		try {
			val replay = outcome.replay
			println(
				"REPLAY ${Fixtures.relative(recording.source)}: ${recording.packets.size} packets, " +
					"TX matched ${replay.matchedTx}/${replay.recordedTx}, final state ${outcome.session.state}",
			)
			val problems = replay.problems().toMutableList()
			val expected = expectedFinalState(actions)
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
				terminals.single() is SessionEvent.Error -> "$action #$id failed: ${terminals.single()}"
				else -> null
			}
		}
}

/**
 * A gravação real versionada (fitGPS 0.2.1), caminho relativo a `fixtures/`. A listagem de `fixtures/real` tem de
 * encontrá-la em todo target, e o `RealtimeReplayTest` a reproduz no dispatcher de produção.
 */
internal const val COMMITTED_REAL_RECORDING: String = "real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl"

/** Estado em que uma gravação reproduzida termina: `DISCONNECTED` se a última ação é um Forget, senão `READY`. */
internal fun expectedFinalState(actions: List<ReplayAction>): ConnectionState =
	if (actions.last() == ReplayAction.Forget) ConnectionState.DISCONNECTED else ConnectionState.READY
