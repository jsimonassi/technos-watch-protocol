package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.sma.data.SmaLocalTime
import br.com.grupotechnos.watch.protocol.testing.FakeTransport
import br.com.grupotechnos.watch.protocol.testing.RecordingListener
import br.com.grupotechnos.watch.protocol.testing.SessionTestLogger
import br.com.grupotechnos.watch.protocol.testing.SmaWatchSimulator
import br.com.grupotechnos.watch.protocol.testing.TestClock
import br.com.grupotechnos.watch.protocol.testing.WatchReply
import br.com.grupotechnos.watch.protocol.util.ByteWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal const val ADDRESS: String = "AA:BB:CC:DD:EE:FF"
internal const val FIT_GPS: String = "fitgps"

/** "Agora" dos testes de sessão: 2026-10-28 14:30 em São Paulo (UTC-3, sem horário de verão desde 2019). */
internal const val NOW_ISO: String = "2026-10-28T14:30:00-03:00"

/**
 * `runTest` com uma [SessionHarness] que é fechada no fim, mesmo quando uma asserção falha. Sem isso, uma sessão
 * em ciclo de reconexão continuaria agendando tentativas no agendador virtual depois da falha e o teste não terminaria.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun runSession(
	store: SampleStore = InMemorySampleStore(),
	clock: TestClock = TestClock.at(NOW_ISO),
	body: suspend TestScope.(SessionHarness) -> Unit,
): TestResult = runTest {
	val harness = SessionHarness(this, store, clock)
	try {
		body(harness)
	} finally {
		harness.session.close()
		runCurrent()
	}
}

/**
 * Uma `WatchSession` real sobre [FakeTransport] + [SmaWatchSimulator], no tempo virtual do teste.
 *
 * O relógio simulado começa "feliz" (BIND/LOGIN aceitos, bateria 85, versão 1.2.3/4.5.6) e responde SPORT e SLEEP
 * com [sportPages]/[sleepPages] (página vazia quando a lista acaba).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SessionHarness(
	val scope: TestScope,
	val store: SampleStore = InMemorySampleStore(),
	val clock: TestClock = TestClock.at(NOW_ISO),
) {
	val transport = FakeTransport()
	val watch = SmaWatchSimulator { transport.notify(it) }
	val listener = RecordingListener()
	val logger = SessionTestLogger()
	val session = WatchSession(transport, logger, store, clock, StandardTestDispatcher(scope.testScheduler))

	/** Páginas de `05/42` a devolver, em ordem. */
	val sportPages = ArrayDeque<ByteArray>()

	/** Páginas de `05/46` a devolver, em ordem. */
	val sleepPages = ArrayDeque<ByteArray>()

	init {
		transport.events = session.events
		transport.onWrite = { watch.onPhoneWrite(it) }
		session.setListener(listener)
		watch.installHappyPath()
		watch.on(0x05, 0x41) { WatchReply(responses = listOf(watch.frameOf(0x05, 0x42, sportPages.removeFirstOrNull() ?: ByteArray(0)))) }
		watch.on(0x05, 0x45) { WatchReply(responses = listOf(watch.frameOf(0x05, 0x46, sleepPages.removeFirstOrNull() ?: ByteArray(0)))) }
	}

	/** Executa o que já pode executar no instante atual. */
	fun run() = scope.runCurrent()

	/** Avança o tempo virtual e executa o que vencer. */
	fun advance(ms: Long) {
		scope.advanceTimeBy(ms)
		scope.runCurrent()
	}

	/** `pair` até READY, mais o acerto de hora automático (passa o `postLoginDelayMs`). */
	fun pairReady(requestId: Int = 1) {
		session.pair(requestId, ADDRESS, FIT_GPS)
		run()
		assertEquals(ConnectionState.READY, session.state, "states: ${listener.stateTrail()}")
		advance(POST_LOGIN_MS)
	}

	/** Nenhuma exceção escapou para o tratador global da sessão. */
	fun assertNoUnexpectedErrors() {
		assertTrue(logger.unexpected().isEmpty(), "unexpected errors: ${logger.unexpected()}")
	}

	companion object {
		/** `postLoginDelayMs` do fitGPS + 1 ms. */
		const val POST_LOGIN_MS: Long = 3_001

		/** Página de `05/42`: registros `(horário local, mode, passos)`. */
		fun sportPage(vararg records: Triple<LocalDateTime, Int, Int>): ByteArray {
			val writer = ByteWriter()
			for ((local, mode, steps) in records) writer.u32(SmaLocalTime.toSecondsSince2000(local)).u8(mode).u24(steps)
			return writer.toByteArray()
		}

		/** Página de `05/46`: registros `(horário local, mode)` com soft/strong zerados. */
		fun sleepPage(vararg records: Pair<LocalDateTime, Int>): ByteArray {
			val writer = ByteWriter()
			for ((local, mode) in records) writer.u32(SmaLocalTime.toSecondsSince2000(local)).u8(mode).u8(0).u8(0)
			return writer.toByteArray()
		}

		/** Horário local de 2026-10-[day] [hour]:[minute]. */
		fun oct(day: Int, hour: Int, minute: Int = 0): LocalDateTime = LocalDateTime(2026, 10, day, hour, minute, 0)
	}
}
