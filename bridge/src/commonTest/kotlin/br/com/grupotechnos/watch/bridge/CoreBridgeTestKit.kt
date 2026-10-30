package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.log.WatchLogger
import br.com.grupotechnos.watch.protocol.session.InMemorySampleStore
import br.com.grupotechnos.watch.protocol.session.NOW_ISO
import br.com.grupotechnos.watch.protocol.session.SampleStore
import br.com.grupotechnos.watch.protocol.session.SessionHarness
import br.com.grupotechnos.watch.protocol.testing.TestClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Diário do que o JavaScript e a plataforma veem, numa ordem só: eventos (`event <nome> <payload>`), liquidações
 * (`resolve <método> <valor>`, `reject <método> <código> <mensagem>`) e os efeitos pedidos ao host (`host <método>
 * <argumento>`). As consultas do host (permissões, Bluetooth, pareamento salvo) não entram: ficam em
 * [FakeCoreHost.queries]. É o que permite conferir a ordem (evento antes do `resolve`, FGS antes do `onSyncStarted`).
 */
internal class Journal {
	val lines = mutableListOf<String>()

	fun add(line: String) {
		lines += line
	}

	/** As linhas que começam com [prefix] (ex.: `"event onError"`). */
	fun starting(prefix: String): List<String> = lines.filter { it.startsWith(prefix) }

	fun clear() {
		lines.clear()
	}
}

/**
 * [CoreHost] de teste: responde às consultas com o que o teste configurar e grava cada efeito no [Journal]. Por padrão:
 * permissões concedidas, Bluetooth ligado, endereço válido se for um MAC em maiúsculas (a regra do
 * `BluetoothAdapter.checkBluetoothAddress`), nenhum relógio salvo.
 */
internal class FakeCoreHost(private val journal: Journal) : CoreHost {
	var missingScan: String? = null
	var missingConnect: String? = null
	var bluetoothEnabled: Boolean = true
	var pairing: CorePairing? = null

	/** Exceção lançada por [loadPairing] (para o `IO_ERROR` do `getHistorySleep`). */
	var loadPairingError: RuntimeException? = null
	var logs: List<CoreLogEntry> = emptyList()
	var export: () -> String = { "/data/corenext/frames-20261028-143000.jsonl" }

	/** Consultas, na ordem (`"isBluetoothEnabled"`, `"isValidAddress(AA:…)"`, `"logEntries(3)"`). */
	val queries = mutableListOf<String>()

	override fun missingScanPermission(): String? = missingScan.also { queries += "missingScanPermission" }

	override fun missingConnectPermission(): String? = missingConnect.also { queries += "missingConnectPermission" }

	override fun isBluetoothEnabled(): Boolean = bluetoothEnabled.also { queries += "isBluetoothEnabled" }

	override fun isValidAddress(address: String): Boolean {
		queries += "isValidAddress($address)"
		return MAC.matches(address)
	}

	override fun startScan() = journal.add("host startScan")

	override fun stopScan() = journal.add("host stopScan")

	override fun startSyncWork() = journal.add("host startSyncWork")

	override fun stopSyncWork() = journal.add("host stopSyncWork")

	override fun loadPairing(): CorePairing? {
		queries += "loadPairing"
		loadPairingError?.let { throw it }
		return pairing
	}

	override fun savePairing(pairing: CorePairing) {
		this.pairing = pairing
		journal.add("host savePairing $pairing")
	}

	override fun clearPairing() {
		pairing = null
		journal.add("host clearPairing")
	}

	override fun setRecordingDeviceName(name: String) = journal.add("host setRecordingDeviceName $name")

	override fun setRecordingFirmware(firmware: String) = journal.add("host setRecordingFirmware $firmware")

	override fun logEntries(limit: Int): List<CoreLogEntry> {
		queries += "logEntries($limit)"
		return logs.take(limit.coerceIn(0, logs.size))
	}

	override fun exportLogs(): String = export()

	override fun clearLogs() = journal.add("host clearLogs")

	override fun setLogLevel(level: LogLevel) = journal.add("host setLogLevel $level")

	private companion object {
		val MAC = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
	}
}

/** [CoreEventSink] de teste: grava cada evento no [Journal]; [ready] é o `canEmit`. O `onLog` só entra com [recordLogs]. */
internal class RecordingSink(private val journal: Journal, var recordLogs: Boolean = false) : CoreEventSink {
	var ready: Boolean = true

	/** Eventos recebidos, com o payload original (para conferir tipos). */
	val events = mutableListOf<Pair<String, Map<String, Any?>>>()

	override fun canEmit(): Boolean = ready

	/** Gancho chamado depois de gravar cada evento, na thread de quem emitiu (dentro da trava da fila). */
	var onEmit: ((String) -> Unit)? = null

	override fun emit(name: String, payload: Map<String, Any?>) {
		events += name to payload
		if (name != "onLog" || recordLogs) journal.add("event $name ${render(payload)}")
		onEmit?.invoke(name)
	}

	fun payloadsOf(name: String): List<Map<String, Any?>> = events.filter { it.first == name }.map { it.second }
}

/** [CoreCompletion] de teste: grava a liquidação no [Journal] e falha se for liquidada duas vezes. */
internal class RecordingCompletion(private val method: String, private val journal: Journal) : CoreCompletion {
	var settled: Int = 0
		private set
	var value: Any? = null
		private set
	var code: String? = null
		private set

	override fun resolve(value: Any?) {
		settled++
		this.value = value
		journal.add("resolve $method ${render(value)}")
		if (settled > 1) fail("$method settled $settled times")
	}

	override fun reject(code: String, message: String) {
		settled++
		this.code = code
		journal.add("reject $method $code $message")
		if (settled > 1) fail("$method settled $settled times")
	}
}

/**
 * Logger do controlador nos testes: guarda cada linha e, com [forward], repassa ao [CoreBridge.onLogEntry], como o
 * `RingLogger.onEntry` do host Android faz (para conferir que o WARN do `emitError` sai antes do `onError`).
 */
internal class BridgeTestLogger : WatchLogger {
	val messages = mutableListOf<Triple<LogLevel, String, String>>()
	var forward: CoreBridge? = null
	var now: Long = 1_793_272_200_000

	/** Gancho chamado em cada linha, na thread de quem logou (para encaixar uma chamada "do JS" num ponto exato). */
	var onLog: ((LogLevel, String, String) -> Unit)? = null

	override fun log(level: LogLevel, tag: String, message: String) {
		messages += Triple(level, tag, message)
		forward?.onLogEntry(CoreLogEntry(now, level, tag, message, null, null))
		onLog?.invoke(level, tag, message)
	}

	override fun frame(direction: FrameDirection, characteristic: String, value: ByteArray, summary: String) {}

	fun withTag(tag: String): List<String> = messages.filter { it.second == tag }.map { it.third }
}

/**
 * Um [CoreBridge] sobre uma `WatchSession` de verdade ([SessionHarness]: transporte falso, relógio SMA simulado, tempo
 * virtual), com [FakeCoreHost], [RecordingSink] ligado e o fuso fixo em `America/Sao_Paulo` (o do [TestClock]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class BridgeHarness(
	scope: TestScope,
	store: SampleStore = InMemorySampleStore(),
	clock: TestClock = TestClock.at(NOW_ISO),
	attachSink: Boolean = true,
) {
	val h = SessionHarness(scope, store, clock)
	val journal = Journal()
	val host = FakeCoreHost(journal)
	val logger = BridgeTestLogger()
	val sink = RecordingSink(journal)
	val zone: TimeZone = TimeZone.of(clock.zone)
	val bridge = CoreBridge(h.session, logger, host) { zone }

	init {
		if (attachSink) bridge.attach(sink)
	}

	fun completion(method: String): RecordingCompletion = RecordingCompletion(method, journal)

	fun run() = h.run()

	fun advance(ms: Long) = h.advance(ms)

	/** `pairDevice` até READY mais o acerto de hora automático, e esvazia o diário. */
	fun pairReady(): RecordingCompletion {
		val completion = completion("pairDevice")
		bridge.pairDevice(ADDRESS, "fitGPS", completion)
		run()
		advance(SessionHarness.POST_LOGIN_MS)
		assertEquals(1, completion.settled, journal.lines.joinToString("\n"))
		journal.clear()
		return completion
	}
}

/** `runTest` com um [BridgeHarness] cuja sessão é fechada no fim, mesmo quando uma asserção falha. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun runBridge(
	store: SampleStore = InMemorySampleStore(),
	clock: TestClock = TestClock.at(NOW_ISO),
	attachSink: Boolean = true,
	body: suspend TestScope.(BridgeHarness) -> Unit,
): TestResult = runTest {
	val harness = BridgeHarness(this, store, clock, attachSink)
	try {
		body(harness)
	} finally {
		harness.h.session.close()
		runCurrent()
	}
}

/** Endereço dos testes (o mesmo da `SessionHarness`). */
internal const val ADDRESS: String = "AA:BB:CC:DD:EE:FF"

/**
 * Valor de payload em texto, com o tipo visível: `Int` sai como `5`, `Double` sempre com ponto (`5.0`, `863.8`, com até
 * seis casas, arredondado, para o texto ser o mesmo na JVM e no Kotlin/Native), `Long` com sufixo `L` e `Float` com `f`
 * (os dois nunca deveriam aparecer num payload), texto entre aspas, mapas na ordem de inserção.
 */
internal fun render(value: Any?): String = when (value) {
	null -> "null"
	is String -> quote(value)
	is Boolean -> value.toString()
	is Int -> value.toString()
	is Long -> "${value}L"
	is Float -> "${value}f"
	is Double -> renderDouble(value)
	is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> quote(k.toString()) + ":" + render(v) }
	is List<*> -> value.joinToString(",", "[", "]") { render(it) }
	else -> "<${value::class.simpleName}:$value>"
}

// Parte inteira e fração separadas: um epoch millis (1,79e12) multiplicado por 10^6 passaria de 2^53 e perderia dígitos.
private fun renderDouble(value: Double): String {
	if (value.isNaN() || value.isInfinite()) return value.toString()
	val magnitude = abs(value)
	var whole = floor(magnitude).toLong()
	var micros = ((magnitude - floor(magnitude)) * 1_000_000).roundToLong()
	if (micros == 1_000_000L) {
		whole += 1
		micros = 0
	}
	val sign = if (value < 0 && (whole != 0L || micros != 0L)) "-" else ""
	val fraction = micros.toString().padStart(6, '0').trimEnd('0')
	return "$sign$whole.${fraction.ifEmpty { "0" }}"
}

private fun quote(text: String): String = buildString {
	append('"')
	for (char in text) {
		when (char) {
			'"' -> append("\\\"")
			'\\' -> append("\\\\")
			'\n' -> append("\\n")
			'\r' -> append("\\r")
			'\t' -> append("\\t")
			else -> if (char < ' ') append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
		}
	}
	append('"')
}
