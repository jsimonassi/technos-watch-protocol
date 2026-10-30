package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.domain.Battery
import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.DeviceInfo
import br.com.grupotechnos.watch.protocol.domain.StepsDay
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.log.WatchLogger
import br.com.grupotechnos.watch.protocol.session.WatchClock
import br.com.grupotechnos.watch.protocol.session.WatchSessionListener
import br.com.grupotechnos.watch.protocol.util.Hex
import kotlinx.datetime.Instant
import kotlin.test.assertEquals
import kotlin.test.fail

/** Relógio de parede ajustável pelo teste. */
internal class TestClock(
	var nowEpochMillis: Long,
	var zone: String = "America/Sao_Paulo",
) : WatchClock {
	override fun nowEpochMillis(): Long = nowEpochMillis

	override fun timeZoneId(): String = zone

	companion object {
		/** Relógio parado num instante ISO-8601 com offset (ex.: `"2026-10-28T14:30:00-03:00"`). */
		fun at(iso: String, zone: String = "America/Sao_Paulo"): TestClock =
			TestClock(Instant.parse(iso).toEpochMilliseconds(), zone)
	}
}

/** Um pacote registrado pelo logger. */
internal class LoggedFrame(val direction: FrameDirection, val characteristic: String, val value: ByteArray, val summary: String) {
	override fun toString(): String = "$direction ${Hex.encode(value)} $summary"
}

/** Logger que guarda mensagens e pacotes. */
internal class SessionTestLogger : WatchLogger {
	val messages = mutableListOf<Triple<LogLevel, String, String>>()
	val frames = mutableListOf<LoggedFrame>()

	override fun log(level: LogLevel, tag: String, message: String) {
		messages += Triple(level, tag, message)
	}

	override fun frame(direction: FrameDirection, characteristic: String, value: ByteArray, summary: String) {
		frames += LoggedFrame(direction, characteristic, value.copyOf(), summary)
	}

	fun contains(text: String): Boolean = messages.any { it.third.contains(text) }

	fun withTag(tag: String): List<String> = messages.filter { it.second == tag }.map { it.third }

	/** Mensagens de erro inesperado (exceção que escapou de uma corrotina ou do despacho). */
	fun unexpected(): List<String> = messages.filter { it.second == "Session/uncaught" || it.second == "Session/dispatch" }.map { it.third }
}

/** Callbacks do listener como valores, para comparar em ordem. */
internal sealed interface SessionEvent {
	data class State(val state: ConnectionState, val phase: String?) : SessionEvent
	data class Info(val requestId: Int, val info: DeviceInfo) : SessionEvent
	data class BatteryLevel(val requestId: Int, val battery: Battery) : SessionEvent
	data class LiveSteps(val requestId: Int, val today: StepsDay) : SessionEvent
	data class SyncStarted(val requestId: Int) : SessionEvent
	data class SyncProgress(val requestId: Int, val percent: Int) : SessionEvent
	data class SyncFinished(val requestId: Int) : SessionEvent
	data class Completed(val requestId: Int) : SessionEvent
	data class Error(val requestId: Int, val code: String, val message: String) : SessionEvent

	/** `requestId` do callback terminal, ou `null` para os não terminais. */
	val terminalId: Int?
		get() = when (this) {
			is Info -> requestId
			is BatteryLevel -> requestId
			is LiveSteps -> requestId
			is SyncFinished -> requestId
			is Completed -> requestId
			is Error -> requestId
			else -> null
		}
}

/** Listener que grava tudo. */
internal class RecordingListener : WatchSessionListener {
	val events = mutableListOf<SessionEvent>()

	override fun onStateChanged(state: ConnectionState, phase: String?) {
		events += SessionEvent.State(state, phase)
	}

	override fun onDeviceInfo(requestId: Int, info: DeviceInfo) {
		events += SessionEvent.Info(requestId, info)
	}

	override fun onBattery(requestId: Int, battery: Battery) {
		events += SessionEvent.BatteryLevel(requestId, battery)
	}

	override fun onLiveSteps(requestId: Int, today: StepsDay) {
		events += SessionEvent.LiveSteps(requestId, today)
	}

	override fun onSyncStarted(requestId: Int) {
		events += SessionEvent.SyncStarted(requestId)
	}

	override fun onSyncProgress(requestId: Int, percent: Int) {
		events += SessionEvent.SyncProgress(requestId, percent)
	}

	override fun onSyncFinished(requestId: Int) {
		events += SessionEvent.SyncFinished(requestId)
	}

	override fun onCompleted(requestId: Int) {
		events += SessionEvent.Completed(requestId)
	}

	override fun onError(requestId: Int, code: String, message: String) {
		events += SessionEvent.Error(requestId, code, message)
	}

	/** Estados na ordem em que foram anunciados. */
	fun states(): List<ConnectionState> = events.filterIsInstance<SessionEvent.State>().map { it.state }

	/** Estados com fase, como `"HANDSHAKING[BIND]"`. */
	fun stateTrail(): List<String> = events.filterIsInstance<SessionEvent.State>().map { e -> e.state.name + (e.phase?.let { "[$it]" } ?: "") }

	/** Callbacks terminais de [requestId]. */
	fun terminals(requestId: Int): List<SessionEvent> = events.filter { it.terminalId == requestId }

	/** O único terminal de [requestId]; falha se houver zero ou mais de um. */
	fun terminal(requestId: Int): SessionEvent {
		val all = terminals(requestId)
		if (all.size != 1) fail("request #$requestId expected exactly one terminal callback, got $all")
		return all.single()
	}

	/** Código do erro terminal de [requestId]. */
	fun errorCode(requestId: Int): String {
		val event = terminal(requestId)
		return (event as? SessionEvent.Error)?.code ?: fail("request #$requestId ended with $event, not an error")
	}

	/** Confere a regra do terminal para todos os ids. */
	fun assertOneTerminalEach(requestIds: Iterable<Int>) {
		for (id in requestIds) assertEquals(1, terminals(id).size, "terminal callbacks of #$id: ${terminals(id)}")
	}

	fun clear() {
		events.clear()
	}
}
