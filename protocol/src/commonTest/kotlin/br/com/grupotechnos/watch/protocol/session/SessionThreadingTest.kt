package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.StepsSample
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.log.NoopWatchLogger
import br.com.grupotechnos.watch.protocol.testing.FakeTransport
import br.com.grupotechnos.watch.protocol.testing.SessionTestLogger
import br.com.grupotechnos.watch.protocol.testing.TestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Regras de threading da sessão que nenhum replay alcança (`docs/arquitetura.md`, seção 4). Roda nos quatro targets.
 *
 * **Dispatcher serial.** O `RealtimeReplayTest` não distingue um dispatcher serial de um paralelo: o replay é pergunta e
 * resposta e uma corrotina só consome a caixa, então os callbacks saem em série de qualquer jeito. Aqui, blocos lançados
 * no próprio contexto de uma sessão criada pelo construtor público ([WatchSession.sessionContext]) esperam um parceiro,
 * sem suspender, por até [PARTNER_WAIT]. Num dispatcher paralelo dois blocos se encontram; num serial, nunca. O
 * controle positivo (`Dispatchers.Default`, sem limite) prova que a sonda enxerga paralelismo naquele target; sem ele,
 * um "no máximo 1" não diria nada.
 *
 * **Logger na thread de quem chama.** `getStepsDays`, `getSleepSessions`, `confirmUploaded` e o construtor chamam o
 * `WatchLogger` antes de retornar, na thread de quem os chamou, e não na da sessão (contrato no KDoc de `WatchLogger`).
 * Com o `StandardTestDispatcher`, nada da sessão roda antes do `runCurrent`, então um log que já está lá veio de quem
 * chamou.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)
class SessionThreadingTest {
	@Test
	fun publicConstructorsRunTheSessionOnASerialDispatcher() {
		val control = maxOverlap(Dispatchers.Default)
		assertTrue(control >= 2, "the probe saw no parallelism on Dispatchers.Default (max $control at a time); it proves nothing here")
		val sessions = listOf<Pair<String, (FakeTransport) -> WatchSession>>(
			"WatchSession(transport, logger, store, clock)" to { WatchSession(it, NoopWatchLogger, InMemorySampleStore(), TestClock.at(NOW_ISO)) },
			"WatchSession(transport, logger, store)" to { WatchSession(it, NoopWatchLogger, InMemorySampleStore()) },
		)
		for ((name, create) in sessions) {
			val transport = FakeTransport()
			val session = create(transport)
			transport.events = session.events
			try {
				assertEquals(1, maxOverlap(session.sessionContext), "$name: blocks on the session dispatcher ran at the same time")
			} finally {
				session.close()
			}
		}
	}

	@Test
	fun synchronousCallsLogOnTheCallersThread() = runTest {
		val clock = TestClock.at(NOW_ISO)
		val oldDayMs = clock.nowEpochMillis - 8 * DAY_MS
		val store = InMemorySampleStore().apply {
			saveSteps(listOf(StepsSample(oldDayMs, 100, 0, 10, 50.0)))
			saveSleep(listOf(SleepSession("2026-10-20", oldDayMs, oldDayMs + 60_000, "", "", emptyList(), 1, 0, 0, 0, 1)))
		}
		val failing = object : SampleStore by store {
			override fun loadConfirmedDays(kind: DataKind): List<String> = throw IllegalStateException("disk gone")
		}
		val logger = SessionTestLogger()
		val transport = FakeTransport()
		val session = WatchSession(transport, logger, failing, clock, StandardTestDispatcher(testScheduler))
		transport.events = session.events
		try {
			assertTrue(logger.withTag("Session/loadConfirmedDays").any { "disk gone" in it }, "constructor: ${logger.messages}")
			// Dias não confirmados mais velhos que a janela pedida (0 = só hoje): as duas consultas registram isso em INFO.
			assertEquals(listOf("2026-10-20"), session.getStepsDays(0).map { it.dayKey })
			assertTrue(logger.withTag("Session/getStepsDays").isNotEmpty(), "getStepsDays: ${logger.messages}")
			assertEquals(listOf("2026-10-20"), session.getSleepSessions(0).map { it.dayKey })
			assertTrue(logger.withTag("Session/getSleepSessions").isNotEmpty(), "getSleepSessions: ${logger.messages}")
			session.confirmUploaded(DataKind.STEPS, listOf("not-a-day"))
			val warnings = logger.messages.filter { it.first == LogLevel.WARN && it.second == "Session/confirmUploaded" }
			assertTrue(warnings.isNotEmpty(), "confirmUploaded: ${logger.messages}")
		} finally {
			session.close()
			runCurrent()
		}
	}

	/**
	 * Quantos blocos lançados em [context] rodaram ao mesmo tempo, no máximo. Cada bloco espera, sem suspender, até outro
	 * entrar ou [PARTNER_WAIT] passar: suspendendo, um dispatcher serial rodaria outro bloco no meio e a conta passaria de 1.
	 */
	private fun maxOverlap(context: CoroutineContext): Int {
		val inFlight = AtomicInt(0)
		val max = AtomicInt(0)
		fun record(running: Int) {
			while (true) {
				val seen = max.load()
				if (running <= seen || max.compareAndSet(seen, running)) return
			}
		}
		runBlocking {
			List(BLOCKS) {
				launch(context) {
					record(inFlight.incrementAndFetch())
					val start = TimeSource.Monotonic.markNow()
					while (inFlight.load() < 2 && start.elapsedNow() < PARTNER_WAIT) {
						// Espera ocupada de propósito (ver o KDoc).
					}
					record(inFlight.load())
					inFlight.decrementAndFetch()
				}
			}.joinAll()
		}
		return max.load()
	}

	private companion object {
		/** Blocos por sonda. No dispatcher serial, a sonda leva `BLOCKS * PARTNER_WAIT` (400 ms). */
		const val BLOCKS = 8

		/** Quanto cada bloco espera um parceiro. */
		val PARTNER_WAIT: Duration = 50.milliseconds

		const val DAY_MS: Long = 24 * 60 * 60 * 1000L
	}
}
