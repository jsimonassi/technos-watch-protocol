package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.domain.Battery
import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.DeviceInfo
import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.StepsDay
import br.com.grupotechnos.watch.protocol.domain.StepsSample
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.log.WatchLogger
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.session.InMemorySampleStore
import br.com.grupotechnos.watch.protocol.session.WatchSession
import br.com.grupotechnos.watch.protocol.session.WatchSessionListener
import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrameCodec
import br.com.grupotechnos.watch.protocol.transport.Transport
import br.com.grupotechnos.watch.protocol.transport.TransportEvent
import br.com.grupotechnos.watch.protocol.transport.TransportEvents
import br.com.grupotechnos.watch.protocol.transport.WriteType
import br.com.grupotechnos.watch.protocol.util.ByteReader
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Um callback do listener, com a thread em que rodou. */
internal class ObservedCallback(val event: SessionEvent, val threadId: Long, val onHostQueue: Boolean)

/** Uma chamada a `TransportEvents` feita pelo host falso: onde rodou e quanto tempo levou para retornar. */
internal class ObservedEvent(val name: String, val threadId: Long, val onHostQueue: Boolean, val took: Duration)

/** Uma chamada ao [WatchLogger.log], com a thread. */
internal class ObservedLog(val level: LogLevel, val tag: String, val message: String, val threadId: Long, val onHostQueue: Boolean)

/** O que o histórico guardou, para comparar o tempo real com o replay em tempo virtual. */
internal data class StoreSnapshot(
	val stepsDays: List<StepsDay>,
	val sleepSessions: List<SleepSession>,
	val storedSteps: List<StepsSample>,
	val storedSleep: List<SleepSession>,
)

/** Resultado de [RealtimeReplay.run]. */
internal class RealtimeOutcome(
	val matchedTx: Int,
	val recordedTx: Int,
	val problems: List<String>,
	val finalState: ConnectionState,
	/** Do primeiro pedido até o terminal da última ação (sem o fechamento). */
	val duration: Duration,
	val requestIds: List<Pair<ReplayAction, Int>>,
	/** Callbacks até o fim do fechamento (o `DISCONNECTED` do `close`). */
	val callbacks: List<ObservedCallback>,
	/** Callbacks que chegaram depois do fim do fechamento, dentro da carência. Tem de ficar vazio. */
	val callbacksAfterClose: List<ObservedCallback>,
	val events: List<ObservedEvent>,
	val logs: List<ObservedLog>,
	/** Maior número de callbacks do listener rodando ao mesmo tempo (1 = serial). */
	val maxConcurrentCallbacks: Int,
	val hostFailures: List<String>,
	val snapshot: StoreSnapshot,
)

/**
 * Replay de uma gravação com a sessão **real**: o construtor público de `WatchSession` (o dispatcher de produção,
 * `Dispatchers.Default.limitedParallelism(1)`, sem dispatcher de teste nem tempo virtual) e um host que entrega todo
 * evento do `TransportEvents` a partir de uma fila serial própria ([HostQueue]), como o `CoreBluetooth` faz na fila de
 * delegate. É o teste de fumaça do threading em cada target (`docs/arquitetura.md`, seção 4).
 *
 * O relógio gravado é o mesmo do replay virtual: [FakeTransport] + [ReplayDriver] (TX estrito, MTU do cabeçalho), só
 * que rodando dentro da fila do host. As chamadas que a sessão faz no `Transport` (na thread dela) viram blocos na fila;
 * dali o [FakeTransport] responde, o [ReplayDriver] confere o TX e emite os RX gravados.
 *
 * As esperas são de verdade: cada ação espera o callback terminal com prazo próprio ([STEP_TIMEOUT]) e o conjunto tem
 * um prazo global ([GLOBAL_TIMEOUT]). Depois de cada handshake a sessão espera `postLoginDelayMs` (3 s) e manda o acerto
 * de hora automático; o replay espera esse `02/01` sair antes da ação seguinte, senão ela entraria na fila antes dele e
 * o TX sairia fora da ordem gravada. Uma divergência de TX, um terminal de erro ou uma falha na fila do host encerram na
 * hora, com mensagem, sem esperar o prazo global.
 *
 * @param deliverOnHostQueue `false` só para a checagem de mutação: as chamadas da sessão rodam direto na thread dela, e
 * a asserção de que todo evento veio da fila do host tem de falhar.
 * @param dropRx índice (a partir de 0, na ordem da gravação) de um RX que o host "perde"; só para a checagem de mutação.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class RealtimeReplay(
	private val recording: Recording,
	private val deliverOnHostQueue: Boolean = true,
	private val dropRx: Int? = null,
) {
	private val queue = createHostQueue("watch-host-queue")
	private val inner = FakeTransport().apply { mtuReply = recording.header.mtu }
	private val replay = ReplayDriver(recording, inner, strict = true)
	private val callbackChannel = Channel<ObservedCallback>(Channel.UNLIMITED)
	private val eventChannel = Channel<ObservedEvent>(Channel.UNLIMITED)
	private val logChannel = Channel<ObservedLog>(Channel.UNLIMITED)
	private val txFrameChannel = Channel<String>(Channel.UNLIMITED)
	private val inFlight = AtomicInt(0)
	private val maxInFlight = AtomicInt(0)
	private val store = InMemorySampleStore()
	private val clock = TestClock(recording.header.recordedAtEpochMillis, recording.header.timeZone)
	private val session = WatchSession(HostQueueTransport(), ChannelLogger(), store, clock)

	private val callbacks = mutableListOf<ObservedCallback>()
	private val events = mutableListOf<ObservedEvent>()
	private val logs = mutableListOf<ObservedLog>()
	private val txFrames = mutableListOf<String>()
	private val ids = mutableListOf<Pair<ReplayAction, Int>>()
	private var rxSeen = 0
	private var currentStep = "start"
	private lateinit var globalStart: TimeMark

	init {
		inner.events = probe(session.events)
		session.setListener(ChannelListener())
	}

	/** Roda [actions] em tempo real e fecha a sessão. Lança [AssertionError] com o motivo na primeira falha. */
	suspend fun run(actions: List<ReplayAction> = ReplayScenario.deduce(recording)): RealtimeOutcome {
		globalStart = TimeSource.Monotonic.markNow()
		val profile = Profiles.MORMAII_FIT_GPS.id
		try {
			for ((index, action) in actions.withIndex()) {
				val id = index + 1
				ids += action to id
				currentStep = "$action #$id"
				val setTimesBefore = txFrames.count { it == SET_TIME }
				when (action) {
					ReplayAction.Pair -> session.pair(id, REPLAY_ADDRESS, profile)
					ReplayAction.Reconnect -> session.reconnect(id, REPLAY_ADDRESS, profile)
					ReplayAction.SetTime -> session.setTime(id)
					ReplayAction.ReadBattery -> session.readBattery(id)
					ReplayAction.ReadDeviceInfo -> session.readDeviceInfo(id)
					is ReplayAction.Sync -> session.syncHistory(id, action.kinds)
					ReplayAction.RefreshSteps -> session.refreshSteps(id)
					ReplayAction.Forget -> session.disconnect(id, forget = true)
					ReplayAction.BluetoothCycle -> {
						val beforeOff = callbacks.size
						queue.post { inner.setBluetooth(false) }
						awaitUntil("BLUETOOTH_OFF", STEP_TIMEOUT) { statesSince(beforeOff).contains(ConnectionState.BLUETOOTH_OFF) }
						val beforeOn = callbacks.size
						queue.post { inner.setBluetooth(true) }
						// O reconnect do host só pode vir depois que a sessão saiu de BLUETOOTH_OFF; antes, ele termina em TRANSPORT.
						awaitUntil("leave BLUETOOTH_OFF", STEP_TIMEOUT) { statesSince(beforeOn).isNotEmpty() }
						session.reconnect(id, REPLAY_ADDRESS, profile)
					}
				}
				awaitTerminal(action, id)
				val handshake = action == ReplayAction.Pair || action == ReplayAction.Reconnect || action == ReplayAction.BluetoothCycle
				if (handshake) {
					awaitUntil("automatic SET_TIME after the handshake", AUTO_SET_TIME_TIMEOUT) {
						txFrames.count { it == SET_TIME } > setTimesBefore
					}
				}
			}
			val duration = globalStart.elapsedNow()
			// Espera o host terminar o que já está na fila (o último ACK do telefone) antes de conferir a gravação.
			queue.call { }
			val (matched, problems) = queue.call { replay.matchedTx to replay.problems() }
			val finalState = session.state
			val snapshot = StoreSnapshot(session.getStepsDays(3), session.getSleepSessions(1), store.loadSteps(), store.loadSleep())

			currentStep = "close"
			val beforeClose = callbacks.size
			session.close()
			if (finalState != ConnectionState.DISCONNECTED) {
				awaitUntil("DISCONNECTED after close", STEP_TIMEOUT) { statesSince(beforeClose).contains(ConnectionState.DISCONNECTED) }
			}
			val closedAt = callbacks.size
			// Um evento atrasado do host depois do close: tem de ser descartado sem callback.
			queue.post { session.events.onNotification(Profiles.SMA_NUS.notify, SmaFrameCodec.ack(1)) }
			delay(CLOSE_GRACE)
			queue.call { }
			drain()
			return RealtimeOutcome(
				matchedTx = matched,
				recordedTx = replay.recordedTx,
				problems = problems,
				finalState = finalState,
				duration = duration,
				requestIds = ids.toList(),
				callbacks = callbacks.take(closedAt),
				callbacksAfterClose = callbacks.drop(closedAt),
				events = events.toList(),
				logs = logs.toList(),
				maxConcurrentCallbacks = maxInFlight.load(),
				hostFailures = queue.failures(),
				snapshot = snapshot,
			)
		} finally {
			session.close()
			queue.close()
		}
	}

	private suspend fun awaitTerminal(action: ReplayAction, id: Int) {
		awaitUntil("terminal callback", STEP_TIMEOUT) {
			val terminal = callbacks.firstOrNull { it.event.terminalId == id }?.event
			if (terminal is SessionEvent.Error) fail("$action #$id failed: $terminal (${progress()})")
			terminal != null
		}
	}

	/**
	 * Espera [condition] com esperas reais curtas. Falha na hora com uma divergência de TX, uma exceção na fila do host ou
	 * um erro inesperado da sessão; e com mensagem quando estoura [timeout] ou o prazo global.
	 */
	private suspend fun awaitUntil(what: String, timeout: Duration, condition: () -> Boolean) {
		val start = TimeSource.Monotonic.markNow()
		var polls = 0
		while (true) {
			drain()
			if (polls++ % 10 == 0) failFast()
			if (condition()) return
			if (start.elapsedNow() > timeout) fail("$currentStep: $what not reached after $timeout (${progress()})")
			if (globalStart.elapsedNow() > GLOBAL_TIMEOUT) fail("$currentStep: global timeout $GLOBAL_TIMEOUT while waiting for $what (${progress()})")
			delay(POLL)
		}
	}

	private fun failFast() {
		val mismatches = queue.call { replay.mismatches.toList() }
		if (mismatches.isNotEmpty()) fail("$currentStep: TX mismatch\n" + mismatches.joinToString("\n"))
		val hostFailures = queue.failures()
		if (hostFailures.isNotEmpty()) fail("$currentStep: host queue failures $hostFailures")
		val unexpected = logs.filter { it.tag == "Session/uncaught" || it.tag == "Session/dispatch" }
		if (unexpected.isNotEmpty()) fail("$currentStep: unexpected session errors ${unexpected.map { it.message }}")
	}

	private fun progress(): String {
		val (matched, next) = queue.call { replay.matchedTx to replay.remaining.firstOrNull() }
		val last = callbacks.takeLast(3).map { it.event }
		return "TX matched $matched/${replay.recordedTx}, next recorded packet $next, state ${session.state}, last callbacks $last"
	}

	private fun statesSince(index: Int): List<ConnectionState> =
		callbacks.drop(index).mapNotNull { (it.event as? SessionEvent.State)?.state }

	private fun drain() {
		while (true) callbacks += callbackChannel.tryReceive().getOrNull() ?: break
		while (true) events += eventChannel.tryReceive().getOrNull() ?: break
		while (true) logs += logChannel.tryReceive().getOrNull() ?: break
		while (true) txFrames += txFrameChannel.tryReceive().getOrNull() ?: break
	}

	/**
	 * O `TransportEvents` que o [FakeTransport] chama: mede cada chamada à entrada real da sessão, registra se veio da fila
	 * do host e descarta o RX [dropRx].
	 */
	private fun probe(target: TransportEvents): TransportEvents = TransportEvents { event ->
		val onQueue = queue.isCurrent()
		val thread = currentThreadId()
		if (event is TransportEvent.Notification && rxSeen++ == dropRx) return@TransportEvents
		val start = TimeSource.Monotonic.markNow()
		forward(target, event)
		eventChannel.trySend(ObservedEvent(event::class.simpleName ?: "?", thread, onQueue, start.elapsedNow()))
	}

	private fun forward(target: TransportEvents, event: TransportEvent) {
		when (event) {
			TransportEvent.Connected -> target.onConnected()
			is TransportEvent.Disconnected -> target.onDisconnected(event.status, event.message)
			is TransportEvent.ServicesDiscovered -> target.onServicesDiscovered(event.services, event.status)
			is TransportEvent.NotificationsEnabled -> target.onNotificationsEnabled(event.requestId, event.status)
			is TransportEvent.MtuChanged -> target.onMtuChanged(event.requestId, event.mtu, event.status)
			is TransportEvent.WriteCompleted -> target.onWriteCompleted(event.requestId, event.status)
			is TransportEvent.ReadCompleted -> target.onReadCompleted(event.requestId, event.value, event.status)
			is TransportEvent.Notification -> target.onNotification(event.characteristic, event.value)
			is TransportEvent.BluetoothStateChanged -> target.onBluetoothStateChanged(event.enabled)
		}
	}

	/** O `Transport` que a sessão vê: cada chamada vira um bloco na fila do host, que roda o [FakeTransport]. */
	private inner class HostQueueTransport : Transport {
		/** Bytes que faltam do frame TX atual (um frame pode ocupar várias escritas). */
		private var txRemaining = 0

		private fun host(block: () -> Unit) {
			if (deliverOnHostQueue) queue.post(block) else block()
		}

		override fun connect(address: String, autoReconnect: Boolean) = host { inner.connect(address, autoReconnect) }

		override fun disconnect() = host { inner.disconnect() }

		override fun discoverServices() = host { inner.discoverServices() }

		override fun enableNotifications(service: String, characteristic: String, requestId: Int) =
			host { inner.enableNotifications(service, characteristic, requestId) }

		override fun requestMtu(mtu: Int, requestId: Int) = host { inner.requestMtu(mtu, requestId) }

		override fun write(service: String, characteristic: String, value: ByteArray, type: WriteType, requestId: Int) {
			// O host real copia antes de despachar: o array é da sessão.
			val copy = value.copyOf()
			trackTxFrame(copy)
			host { inner.write(service, characteristic, copy, type, requestId) }
		}

		override fun read(service: String, characteristic: String, requestId: Int) = host { inner.read(service, characteristic, requestId) }

		/** Publica o `cmd/key` de cada frame de dados TX que começa (chamado na thread da sessão, em ordem). */
		private fun trackTxFrame(packet: ByteArray) {
			if (txRemaining > 0) {
				txRemaining -= packet.size
				return
			}
			if (SmaFrameCodec.isControl(packet)) return
			val total = SmaFrameCodec.frameLength(packet) ?: return
			txRemaining = total - packet.size
			if (packet.size > 10) {
				txFrameChannel.trySend(SmaFrameCodec.cmdKey(ByteReader.u8At(packet, 8), ByteReader.u8At(packet, 10)))
			}
		}
	}

	/** Listener que publica cada callback com a thread e conta quantos rodam ao mesmo tempo. */
	private inner class ChannelListener : WatchSessionListener {
		private fun observe(event: SessionEvent) {
			val running = inFlight.incrementAndFetch()
			while (true) {
				val max = maxInFlight.load()
				if (running <= max || maxInFlight.compareAndSet(max, running)) break
			}
			callbackChannel.trySend(ObservedCallback(event, currentThreadId(), queue.isCurrent()))
			inFlight.decrementAndFetch()
		}

		override fun onStateChanged(state: ConnectionState, phase: String?) = observe(SessionEvent.State(state, phase))

		override fun onDeviceInfo(requestId: Int, info: DeviceInfo) = observe(SessionEvent.Info(requestId, info))

		override fun onBattery(requestId: Int, battery: Battery) = observe(SessionEvent.BatteryLevel(requestId, battery))

		override fun onLiveSteps(requestId: Int, today: StepsDay) = observe(SessionEvent.LiveSteps(requestId, today))

		override fun onSyncStarted(requestId: Int) = observe(SessionEvent.SyncStarted(requestId))

		override fun onSyncProgress(requestId: Int, percent: Int) = observe(SessionEvent.SyncProgress(requestId, percent))

		override fun onSyncFinished(requestId: Int) = observe(SessionEvent.SyncFinished(requestId))

		override fun onCompleted(requestId: Int) = observe(SessionEvent.Completed(requestId))

		override fun onError(requestId: Int, code: String, message: String) = observe(SessionEvent.Error(requestId, code, message))
	}

	/** Logger que publica cada mensagem com a thread; os pacotes não interessam aqui (o [ReplayDriver] confere o TX). */
	private inner class ChannelLogger : WatchLogger {
		override fun log(level: LogLevel, tag: String, message: String) {
			logChannel.trySend(ObservedLog(level, tag, message, currentThreadId(), queue.isCurrent()))
		}

		override fun frame(direction: FrameDirection, characteristic: String, value: ByteArray, summary: String) {}
	}

	companion object {
		/** Prazo de cada ação até o callback terminal. */
		val STEP_TIMEOUT: Duration = 20.seconds

		/** Prazo do acerto de hora automático depois do handshake: `postLoginDelayMs` com folga. */
		val AUTO_SET_TIME_TIMEOUT: Duration = Profiles.MORMAII_FIT_GPS.quirks.postLoginDelayMs.milliseconds + 10.seconds

		/** Prazo do replay inteiro. */
		val GLOBAL_TIMEOUT: Duration = 90.seconds

		/** Quanto tempo o teste espera, depois do fechamento, por um callback que não deveria vir. */
		val CLOSE_GRACE: Duration = 1500.milliseconds

		private val POLL: Duration = 5.milliseconds
		private const val SET_TIME = "02/01"
	}
}

/** O replay em tempo virtual ([runReplay]) reduzido ao que o [RealtimeReplay] compara. */
internal fun storeSnapshotOf(outcome: ReplayOutcome): StoreSnapshot =
	StoreSnapshot(
		outcome.session.getStepsDays(3),
		outcome.session.getSleepSessions(1),
		outcome.store.loadSteps(),
		outcome.store.loadSleep(),
	)
