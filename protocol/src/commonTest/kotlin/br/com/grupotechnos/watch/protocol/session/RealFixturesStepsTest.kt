package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.StepsDay
import br.com.grupotechnos.watch.protocol.profile.StepsAggregation
import br.com.grupotechnos.watch.protocol.sma.SmaEstimates
import br.com.grupotechnos.watch.protocol.testing.RecordingLoader
import br.com.grupotechnos.watch.protocol.testing.ReplayOutcome
import br.com.grupotechnos.watch.protocol.testing.ReplayScenario
import br.com.grupotechnos.watch.protocol.testing.SessionTestLogger
import br.com.grupotechnos.watch.protocol.testing.TestClock
import br.com.grupotechnos.watch.protocol.testing.runReplay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Totais de passos que as duas gravações reais do fitGPS 0.2.1 de 2026-10-29 produzem, contra a tela do relógio.
 *
 * É a evidência do H3 (`docs/protocolo-sma.md`, seção 8; `docs/decisoes.md`, ADR 1). A gravação da manhã termina com
 * `mode 0` = 56 às 08:31:22 (`atMs` 1032178) e a da noite traz o registro ao vivo `mode 0` = 56 às 21:37:37 e 21:37:47
 * (`atMs` 29133 e 38440). O relógio ficou parado o dia todo depois da manhã, e a tela dele mostrava 56 passos no dia
 * (confirmado pelo usuário no aparelho, 2026-10-29). Um contador por hora leria 0 às 21:37: o `mode 0` é o acumulado do
 * dia, e o total certo do dia é 56.
 *
 * Cada gravação sozinha tem passos numa hora só (08:00 e 21:00), e aí as duas regras dão 56. A diferença aparece com as
 * duas no mesmo histórico, como no telefone de quem sincroniza de manhã e à noite: `DAY_CUMULATIVE` dá 56 e
 * `HOURLY_SUM`, a regra do Core de produção, dá 112.
 *
 * A gravação do iPhone de 2026-10-30 ([IOS_FIT_GPS_RECORDING]) traz o que veio depois da meia-noite: o `05/42` devolveu
 * `mode 16` com 56 passos às 23:59:00 de 29/10, o total da tela de ontem, `mode 16` com 0 às 00:00:00 e o registro ao
 * vivo `mode 0` com 0 às 09:30:07 (o relógio ficou parado a noite toda). Só `mode 0` vira amostra (`docs/sma-dados.md`,
 * seção 1), então o 56 não volta a contar e hoje tem 0.
 *
 * Está no `commonTest`: roda nos quatro targets, e cada total conferido imprime uma linha `STEPS-DAY` no `system-out` do
 * relatório XML, ao lado das linhas `REPLAY` (`docs/fixtures.md`, seção 4.1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealFixturesStepsTest {
	private val zone = "America/Sao_Paulo"

	@Test
	fun morningRecordingGivesTheDayTotalOnTheWatchScreen() = runTest {
		val morning = replay(MORNING)
		try {
			assertEquals(listOf(hour("08") to 56), samples(morning))
			assertWatchScreenTotal(MORNING, morning.session.getStepsDays(3))
		} finally {
			close(morning)
		}
	}

	@Test
	fun eveningRecordingGivesTheDayTotalOnTheWatchScreen() = runTest {
		val evening = replay(EVENING)
		try {
			assertEquals(listOf(hour("21") to 56), samples(evening))
			assertWatchScreenTotal(EVENING, evening.session.getStepsDays(3))
		} finally {
			close(evening)
		}
	}

	@Test
	fun bothRecordingsInOneHistoryCountTheDayAccumulatorOnce() = runTest {
		// A sessão da noite começa com o histórico da manhã, como no telefone que fez as duas gravações.
		val morning = replay(MORNING)
		close(morning)
		val evening = replay(EVENING, morning.store)
		try {
			assertEquals(listOf(hour("08") to 56, hour("21") to 56), samples(evening))
			// Perfil do fitGPS (DAY_CUMULATIVE): 56, a tela do relógio. Com HOURLY_SUM seriam 112.
			assertWatchScreenTotal("$MORNING + $EVENING", evening.session.getStepsDays(3))
		} finally {
			close(evening)
		}
	}

	@Test
	fun hourlySumOfTheSameSamplesDoublesTheDay() = runTest {
		// Independente do perfil: as amostras das duas gravações, as duas regras, o mesmo histórico.
		val morning = replay(MORNING)
		close(morning)
		val evening = replay(EVENING)
		close(evening)
		val history = HistoryStore(InMemorySampleStore(), TestClock(evening.clock.nowEpochMillis, zone), SessionTestLogger())
		assertEquals(2, history.mergeSteps(morning.store.loadSteps() + evening.store.loadSteps(), RETENTION_DAYS))

		val cumulative = history.stepsDays(3, StepsAggregation.DAY_CUMULATIVE).single()
		assertEquals(StepsDay(DAY, "2026-10-29T00:00:00-03:00", 56, SmaEstimates.calories(56), SmaEstimates.distanceMeters(56)), cumulative)
		assertEquals(cumulative, history.today(StepsAggregation.DAY_CUMULATIVE))

		// A soma das horas conta o acumulado duas vezes: é o que o Core de produção manda ao backend (ADR 1).
		val hourly = history.stepsDays(3, StepsAggregation.HOURLY_SUM).single()
		assertEquals(
			StepsDay(DAY, "2026-10-29T00:00:00-03:00", 112, 2 * SmaEstimates.calories(56), 2 * SmaEstimates.distanceMeters(56)),
			hourly,
		)
		assertEquals(hourly, history.today(StepsAggregation.HOURLY_SUM))
		println("STEPS-DAY $MORNING + $EVENING: $DAY DAY_CUMULATIVE ${cumulative.steps}, HOURLY_SUM ${hourly.steps}")
	}

	@Test
	fun iosRecordingAfterMidnightIgnoresYesterdaysTotalOnTheSitRecord() = runTest {
		val night = replay(IOS_FIT_GPS_RECORDING)
		try {
			// Os mode 16 (56 às 23:59:00 de 29/10, 0 às 00:00:00) ficam de fora; o ao vivo de 09:30:07 dá 0 às 09:00.
			assertEquals(listOf(Instant.parse("2026-10-30T09:00:00-03:00").toEpochMilliseconds() to 0), samples(night))
			val days = night.session.getStepsDays(3)
			assertEquals(listOf(StepsDay("2026-10-30", "2026-10-30T00:00:00-03:00", 0, 0, 0.0)), days)
			println("STEPS-DAY $IOS_FIT_GPS_RECORDING: ${days.single().dayKey} = ${days.single().steps} steps (fitGPS profile)")
		} finally {
			close(night)
		}
	}

	/** Reproduz [relative] (ações deduzidas, TX estrito) e confere que ela rodou inteira. */
	private fun TestScope.replay(relative: String, store: InMemorySampleStore = InMemorySampleStore()): ReplayOutcome {
		val recording = RecordingLoader.loadFixture(relative)
		val actions = ReplayScenario.deduce(recording)
		val outcome = runReplay(recording, actions, store = store)
		assertTrue(outcome.replay.problems().isEmpty(), "$relative: ${outcome.replay.problems()}")
		assertEquals(expectedFinalState(actions), outcome.session.state, relative)
		return outcome
	}

	private fun TestScope.close(outcome: ReplayOutcome) {
		outcome.session.close()
		runCurrent()
	}

	/** O dia 2026-10-29 com os 56 passos da tela do relógio e as estimativas desses 56. */
	private fun assertWatchScreenTotal(source: String, days: List<StepsDay>) {
		assertEquals(
			listOf(StepsDay(DAY, "2026-10-29T00:00:00-03:00", 56, SmaEstimates.calories(56), SmaEstimates.distanceMeters(56))),
			days,
			source,
		)
		println("STEPS-DAY $source: ${days.single().dayKey} = ${days.single().steps} steps (fitGPS profile)")
	}

	/** Amostras do store como `(instante, passos)`. */
	private fun samples(outcome: ReplayOutcome): List<Pair<Long, Int>> = outcome.store.loadSteps().map { it.atEpochMillis to it.steps }

	private fun hour(hh: String): Long = Instant.parse("${DAY}T$hh:00:00-03:00").toEpochMilliseconds()

	private companion object {
		const val MORNING = "real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl"
		const val EVENING = "real/fitgps/0.2.1/pair-commands-sync-bt-reconnect-disconnect-reconnect.jsonl"
		const val DAY = "2026-10-29"

		/** `historyRetentionDays` do fitGPS. */
		const val RETENTION_DAYS = 30
	}
}
