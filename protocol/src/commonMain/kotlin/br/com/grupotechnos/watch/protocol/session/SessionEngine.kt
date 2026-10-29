package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.Battery
import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.DeviceInfo
import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.StepsDay
import br.com.grupotechnos.watch.protocol.domain.WatchErrorCodes
import br.com.grupotechnos.watch.protocol.domain.WatchException
import br.com.grupotechnos.watch.protocol.driver.DeviceDriver
import br.com.grupotechnos.watch.protocol.driver.DriverNotice
import br.com.grupotechnos.watch.protocol.driver.Drivers
import br.com.grupotechnos.watch.protocol.driver.HandshakeMode
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.log.WatchLogger
import br.com.grupotechnos.watch.protocol.profile.DeviceProfile
import br.com.grupotechnos.watch.protocol.profile.DeviceRegistry
import br.com.grupotechnos.watch.protocol.profile.GattCandidate
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.profile.StepsAggregation
import br.com.grupotechnos.watch.protocol.transport.DiscoveredService
import br.com.grupotechnos.watch.protocol.transport.GattStatus
import br.com.grupotechnos.watch.protocol.transport.GattUuid
import br.com.grupotechnos.watch.protocol.transport.Transport
import br.com.grupotechnos.watch.protocol.transport.TransportEvent
import br.com.grupotechnos.watch.protocol.transport.TransportEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

/**
 * Máquina de estados da [WatchSession]: conexão, configuração GATT, handshake, fila de comandos, sincronização e
 * reconexão. O desenho completo, com o diagrama de estados, está em `docs/arquitetura.md`.
 *
 * **Threading.** Tudo roda em `context`, que precisa ter paralelismo 1 (`Dispatchers.Default.limitedParallelism(1)`
 * em produção, `StandardTestDispatcher` nos testes). As chamadas públicas e os eventos do host só enfileiram uma
 * entrada em [inbox]; uma única corrotina consome a caixa e trata cada entrada sem suspender. O que demora
 * (configuração, handshake, comandos, sync) roda em corrotinas no escopo da tentativa de conexão ([Attempt.scope]),
 * cancelado quando o link cai. Assim, o estado mutável não precisa de trava: só a thread da sessão o toca. As
 * exceções são [state], [currentAddress] e [currentProfileId] (`@Volatile`, lidos de qualquer thread) e o
 * [HistoryStore], que responde às consultas síncronas por instantâneos imutáveis.
 *
 * **Roteamento dos eventos do host.** Conclusões GATT (write, read, notificação, MTU) vão para [GattOperations] pelo
 * `requestId` interno; a descoberta de serviços, que não tem id, para [pendingDiscovery]; notificações, para o link
 * da tentativa atual; eventos de ciclo de vida (conectou, caiu, Bluetooth), para os tratadores de estado.
 *
 * **Desconexões esperadas.** Quando a sessão chama `Transport.disconnect` com um link aberto ou pendente, o host
 * responde com um `onDisconnected` que chega depois, talvez quando outra tentativa já começou. [staleDisconnects]
 * conta essas respostas esperadas para não confundi-las com a queda do link novo; a contagem zera em `onConnected`,
 * porque o host é serial e entrega o `onDisconnected` antigo antes do `onConnected` novo.
 *
 * **Regra do callback terminal.** Cada `requestId` recebe exatamente um terminal: pedidos de ciclo de vida
 * ([LifecycleRequest]) saem de [lifecycle] ao terminar; comandos usam [SessionCommand.markFinished]; recusas
 * imediatas (`NOT_READY`, `UNSUPPORTED_DEVICE`) emitem e retornam.
 */
internal class SessionEngine(
	private val transport: Transport,
	private val logger: WatchLogger,
	store: SampleStore,
	private val clock: WatchClock,
	context: CoroutineContext,
) {
	private val scope: CoroutineScope = CoroutineScope(
		SupervisorJob() + context + CoroutineExceptionHandler { _, error ->
			log(LogLevel.ERROR, "uncaught", "unexpected ${error::class.simpleName}: ${error.message}")
		},
	)
	private val inbox = Channel<SessionInput>(Channel.UNLIMITED)
	private val history = HistoryStore(store, clock, logger)
	private val syncEngine = SyncEngine(history, logger)
	private val operations = GattOperations()

	/** Entrada dos eventos BLE do host; cada chamada só enfileira. */
	val events: TransportEvents = TransportEvents { event -> post(SessionInput.Event(event)) }

	@Volatile
	var state: ConnectionState = ConnectionState.DISCONNECTED
		private set

	@Volatile
	var currentAddress: String? = null
		private set

	@Volatile
	var currentProfileId: String? = null
		private set

	@Volatile
	private var listener: WatchSessionListener? = null

	@Volatile
	private var closed = false

	// A partir daqui, estado confinado à thread da sessão.
	private var phase: String? = null
	private var target: Target? = null
	private var attempt: Attempt? = null
	private var lifecycle: LifecycleRequest? = null
	private var bonded = false
	private var userDisconnected = false
	private var transportActive = false
	private var staleDisconnects = 0
	private var reconnectJob: Job? = null
	private var reconnectFailures = 0
	private var pendingDiscovery: CompletableDeferred<TransportEvent.ServicesDiscovered>? = null
	private val forgetRequests = mutableListOf<Int>()
	private var shutDown = false

	init {
		scope.launch {
			for (input in inbox) dispatch(input)
		}
	}

	fun setListener(listener: WatchSessionListener?) {
		this.listener = listener
	}

	fun pair(requestId: Int, address: String, profileId: String) =
		call(requestId, "pair") { onPair(requestId, address, profileId) }

	fun reconnect(requestId: Int, address: String, profileId: String) =
		call(requestId, "reconnect") { onReconnect(requestId, address, profileId) }

	fun disconnect(requestId: Int, forget: Boolean) =
		call(requestId, "disconnect") { onDisconnectRequest(requestId, forget) }

	fun readDeviceInfo(requestId: Int) =
		call(requestId, "readDeviceInfo") { enqueue(SessionCommand.ReadDeviceInfo(requestId)) }

	fun readBattery(requestId: Int) =
		call(requestId, "readBattery") { enqueue(SessionCommand.ReadBattery(requestId)) }

	fun setTime(requestId: Int) =
		call(requestId, "setTime") { enqueue(SessionCommand.SetTime(requestId, internal = false)) }

	fun refreshSteps(requestId: Int) =
		call(requestId, "refreshSteps") { enqueue(SessionCommand.RefreshSteps(requestId, internal = false)) }

	fun syncHistory(requestId: Int, kinds: List<DataKind>) {
		val copy = kinds.toList()
		call(requestId, "syncHistory") { enqueue(SessionCommand.SyncHistory(requestId, copy)) }
	}

	fun getStepsDays(daysBefore: Int): List<StepsDay> = history.stepsDays(daysBefore, aggregation())

	fun getSleepSessions(daysBefore: Int): List<SleepSession> = history.sleepSessions(daysBefore)

	fun confirmUploaded(kind: DataKind, dayKeys: List<String>) {
		history.confirm(kind, dayKeys.toList())
		call(null, "confirmUploaded") { history.persistConfirmed(kind) }
	}

	fun close() {
		if (closed) return
		closed = true
		inbox.trySend(SessionInput.Call(null, "close") { onClose() })
	}

	// region Caixa de entrada

	private fun post(input: SessionInput) {
		if (inbox.trySend(input).isFailure) log(LogLevel.DEBUG, "post", "session closed; dropped $input")
	}

	/**
	 * Enfileira uma chamada do host. Depois de [close], o pedido termina na hora com `onError(CANCELLED)`, na thread
	 * de quem chamou (a da sessão já não existe).
	 */
	private fun call(requestId: Int?, name: String, action: () -> Unit) {
		val accepted = !closed && inbox.trySend(SessionInput.Call(requestId, name, action)).isSuccess
		if (!accepted && requestId != null) {
			notifyListener(name) { it.onError(requestId, WatchErrorCodes.CANCELLED, "session closed") }
		}
	}

	private fun dispatch(input: SessionInput) {
		try {
			when (input) {
				is SessionInput.Call -> input.action()
				is SessionInput.Event -> onTransportEvent(input.event)
			}
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			log(LogLevel.ERROR, "dispatch", "failed to handle $input: ${error::class.simpleName} ${error.message}")
		}
	}

	private fun onTransportEvent(event: TransportEvent) {
		when (event) {
			TransportEvent.Connected -> onLinkUp()
			is TransportEvent.Disconnected -> onLinkDown(event)
			is TransportEvent.ServicesDiscovered -> {
				val waiting = pendingDiscovery
				if (waiting == null) {
					log(LogLevel.DEBUG, "onServicesDiscovered", "no discovery pending; ignored")
				} else {
					pendingDiscovery = null
					waiting.complete(event)
				}
			}
			is TransportEvent.NotificationsEnabled -> completeOperation(event.requestId, event, "onNotificationsEnabled")
			is TransportEvent.MtuChanged -> completeOperation(event.requestId, event, "onMtuChanged")
			is TransportEvent.WriteCompleted -> completeOperation(event.requestId, event, "onWriteCompleted")
			is TransportEvent.ReadCompleted -> completeOperation(event.requestId, event, "onReadCompleted")
			is TransportEvent.Notification -> onNotification(event)
			is TransportEvent.BluetoothStateChanged -> onBluetoothState(event.enabled)
		}
	}

	private fun completeOperation(requestId: Int, event: TransportEvent, method: String) {
		if (!operations.complete(requestId, event)) {
			log(LogLevel.DEBUG, method, "gatt request $requestId is not pending (expired or from an old link); ignored")
		}
	}

	private fun onNotification(event: TransportEvent.Notification) {
		val link = attempt?.link
		if (link == null || link.isClosed || event.characteristic != link.gatt.notify) {
			logger.frame(FrameDirection.RX, event.characteristic, event.value, "")
			log(LogLevel.DEBUG, "onNotification", "notification on ${event.characteristic} without a matching link; dropped")
			return
		}
		link.deliver(event.value)
	}

	// endregion

	// region Ciclo de vida

	private fun onPair(requestId: Int, address: String, profileId: String) {
		val profile = DeviceRegistry.byId(profileId)
		if (profile == null) {
			error(requestId, WatchErrorCodes.UNSUPPORTED_DEVICE, "unknown profile $profileId")
			return
		}
		if (state == ConnectionState.BLUETOOTH_OFF) {
			error(requestId, WatchErrorCodes.TRANSPORT, "bluetooth off")
			return
		}
		abandonCurrent("superseded by pair #$requestId")
		setTarget(address, profile)
		bonded = false
		userDisconnected = false
		reconnectFailures = 0
		lifecycle = LifecycleRequest(requestId, "pair")
		startAttempt(HandshakeMode.FIRST_PAIR, "pair #$requestId")
	}

	private fun onReconnect(requestId: Int, address: String, profileId: String) {
		val profile = DeviceRegistry.byId(profileId)
		if (profile == null) {
			error(requestId, WatchErrorCodes.UNSUPPORTED_DEVICE, "unknown profile $profileId")
			return
		}
		val current = target
		val sameTarget = current != null && current.address == address && current.profile.id == profile.id
		if (state == ConnectionState.BLUETOOTH_OFF) {
			// Lembra o alvo para religar sozinho quando o Bluetooth voltar (onBluetoothState).
			setTarget(address, profile)
			bonded = true
			userDisconnected = false
			error(requestId, WatchErrorCodes.TRANSPORT, "bluetooth off; the session reconnects when it is turned on")
			return
		}
		if (sameTarget && !userDisconnected) {
			if (state == ConnectionState.READY || state == ConnectionState.SYNCING) {
				completed(requestId)
				return
			}
			val reconnecting = attempt?.mode == HandshakeMode.RECONNECT ||
				(attempt == null && state == ConnectionState.RECONNECTING)
			if (reconnecting) {
				failLifecycle(WatchErrorCodes.CANCELLED, "superseded by reconnect #$requestId")
				lifecycle = LifecycleRequest(requestId, "reconnect")
				if (attempt == null) {
					reconnectJob?.cancel()
					reconnectJob = null
					startAttempt(HandshakeMode.RECONNECT, "reconnect #$requestId")
				}
				return
			}
		}
		abandonCurrent("superseded by reconnect #$requestId")
		setTarget(address, profile)
		bonded = true
		userDisconnected = false
		reconnectFailures = 0
		lifecycle = LifecycleRequest(requestId, "reconnect")
		startAttempt(HandshakeMode.RECONNECT, "reconnect #$requestId")
	}

	private fun onDisconnectRequest(requestId: Int, forget: Boolean) {
		userDisconnected = true
		reconnectJob?.cancel()
		reconnectJob = null
		failLifecycle(WatchErrorCodes.CANCELLED, "disconnect #$requestId")
		val current = attempt
		if (forget && current != null && current.ready && !current.closing) {
			// UNBIND precisa do link vivo e da fila parada: o driver não aceita duas chamadas ao mesmo tempo.
			current.closing = true
			failCommands(current, WatchErrorCodes.CANCELLED, "disconnect #$requestId")
			forgetRequests += requestId
			val driver = current.driver
			val timeoutMs = current.profile.quirks.commandTimeoutMs
			scope.launch {
				current.commandJob?.cancelAndJoin()
				try {
					val done = driver != null && withTimeoutOrNull(timeoutMs) { driver.unbind() } != null
					if (done) {
						log(LogLevel.INFO, "disconnect", "UNBIND acknowledged")
					} else {
						log(LogLevel.WARN, "disconnect", "UNBIND not acknowledged after $timeoutMs ms; forgetting anyway")
					}
				} catch (error: CancellationException) {
					throw error
				} catch (error: Exception) {
					log(LogLevel.WARN, "disconnect", "UNBIND failed (${error.message}); forgetting anyway")
				}
				if (forgetRequests.remove(requestId)) finishDisconnect(requestId, forget = true, owner = current)
			}
			return
		}
		finishDisconnect(requestId, forget, owner = current)
	}

	/**
	 * Fecha a conexão e conclui o `disconnect`. Se outra tentativa começou enquanto o UNBIND rodava (um `pair` novo),
	 * ela não é derrubada: só o histórico é apagado, se pedido.
	 */
	private fun finishDisconnect(requestId: Int, forget: Boolean, owner: Attempt?) {
		val superseded = attempt != null && attempt !== owner
		if (!superseded) {
			attempt?.let { teardown(it, WatchErrorCodes.CANCELLED, "disconnect #$requestId") }
			disconnectTransport("disconnect #$requestId")
			if (state != ConnectionState.BLUETOOTH_OFF) {
				setState(ConnectionState.DISCONNECTED, "disconnect #$requestId" + if (forget) " (forget)" else "")
			}
			if (forget) {
				target = null
				currentAddress = null
				currentProfileId = null
				bonded = false
			}
		}
		if (forget) history.clear()
		completed(requestId, "disconnect")
	}

	private fun onClose() {
		if (shutDown) return
		shutDown = true
		reconnectJob?.cancel()
		failLifecycle(WatchErrorCodes.CANCELLED, "session closed")
		attempt?.let { teardown(it, WatchErrorCodes.CANCELLED, "session closed") }
		disconnectTransport("session closed")
		forgetRequests.toList().forEach { error(it, WatchErrorCodes.CANCELLED, "session closed during disconnect") }
		forgetRequests.clear()
		if (state != ConnectionState.DISCONNECTED) setState(ConnectionState.DISCONNECTED, "session closed")
		inbox.close()
		while (true) {
			val pending = inbox.tryReceive().getOrNull() ?: break
			val requestId = (pending as? SessionInput.Call)?.requestId ?: continue
			error(requestId, WatchErrorCodes.CANCELLED, "session closed")
		}
		scope.cancel()
	}

	private fun onBluetoothState(enabled: Boolean) {
		if (!enabled) {
			if (state == ConnectionState.BLUETOOTH_OFF) return
			reconnectJob?.cancel()
			reconnectJob = null
			attempt?.let { current ->
				val pairing = current.mode == HandshakeMode.FIRST_PAIR && !current.ready
				teardown(current, WatchErrorCodes.DISCONNECTED, "bluetooth off")
				if (pairing) failLifecycle(WatchErrorCodes.DISCONNECTED, "bluetooth turned off")
			}
			disconnectTransport("bluetooth off")
			setState(ConnectionState.BLUETOOTH_OFF, "bluetooth turned off")
			return
		}
		if (state != ConnectionState.BLUETOOTH_OFF) {
			log(LogLevel.DEBUG, "onBluetoothStateChanged", "bluetooth on while $state; nothing to do")
			return
		}
		if (target != null && bonded && !userDisconnected) {
			reconnectFailures = 0
			startAttempt(HandshakeMode.RECONNECT, "bluetooth turned on")
		} else {
			setState(ConnectionState.DISCONNECTED, "bluetooth turned on")
		}
	}

	/** Cancela o pedido de ciclo de vida e a tentativa em andamento, e fecha o link, antes de um `pair`/`reconnect` novo. */
	private fun abandonCurrent(reason: String) {
		reconnectJob?.cancel()
		reconnectJob = null
		failLifecycle(WatchErrorCodes.CANCELLED, reason)
		attempt?.let { teardown(it, WatchErrorCodes.CANCELLED, reason) }
		disconnectTransport(reason)
	}

	private fun startAttempt(mode: HandshakeMode, reason: String) {
		val target = target ?: return
		val job = SupervisorJob(scope.coroutineContext[Job])
		val attempt = Attempt(mode, target, CoroutineScope(scope.coroutineContext + job))
		this.attempt = attempt
		val autoReconnect = mode == HandshakeMode.RECONNECT
		setState(if (autoReconnect) ConnectionState.RECONNECTING else ConnectionState.CONNECTING, reason)
		transportActive = true
		if (!hostCall("connect") { transport.connect(target.address, autoReconnect) }) {
			onAttemptFailed(attempt, WatchException(WatchErrorCodes.TRANSPORT, "Transport.connect threw"))
			return
		}
		if (autoReconnect) return
		val timeoutMs = target.profile.quirks.connectTimeoutMs
		attempt.scope.launch {
			delay(timeoutMs)
			if (isCurrent(attempt) && !attempt.connected) {
				onAttemptFailed(attempt, WatchException(WatchErrorCodes.CONNECT_TIMEOUT, "no connection after $timeoutMs ms"))
			}
		}
	}

	private fun onLinkUp() {
		val current = attempt
		if (current == null) {
			log(LogLevel.WARN, "onConnected", "link up with no connection attempt (state=$state); closing it")
			transportActive = true
			disconnectTransport("unexpected link")
			return
		}
		if (current.connected) {
			log(LogLevel.WARN, "onConnected", "duplicate onConnected ignored")
			return
		}
		staleDisconnects = 0
		transportActive = true
		current.connected = true
		current.stage = Stage.CONFIGURING
		setState(ConnectionState.CONFIGURING, "link up (${current.mode})")
		current.scope.launch { establish(current) }
	}

	private fun onLinkDown(event: TransportEvent.Disconnected) {
		if (staleDisconnects > 0) {
			staleDisconnects--
			log(LogLevel.DEBUG, "onDisconnected", "expected disconnect (status=${event.status}); ignored")
			return
		}
		transportActive = false
		val reason = "link lost (status=${event.status} ${event.message})"
		val current = attempt
		if (current == null) {
			log(LogLevel.INFO, "onDisconnected", "$reason with no connection attempt (state=$state)")
			return
		}
		when {
			// UNBIND em andamento: a conclusão do disconnect faz o resto.
			current.closing -> teardown(current, WatchErrorCodes.CANCELLED, reason)
			current.mode == HandshakeMode.FIRST_PAIR && !current.ready -> {
				teardown(current, WatchErrorCodes.DISCONNECTED, reason)
				setState(ConnectionState.DISCONNECTED, reason)
				failLifecycle(WatchErrorCodes.DISCONNECTED, reason)
			}
			// Queda de um link que estava pronto: reconecta na hora (o relógio pode só ter se afastado um instante).
			current.ready -> {
				teardown(current, WatchErrorCodes.DISCONNECTED, reason)
				startAttempt(HandshakeMode.RECONNECT, reason)
			}
			// Conexão pendente que falhou no host, ou link que caiu antes do READY (GATT 133/8 logo depois de abrir, um
			// relógio que derruba o link no LOGIN): espera crescente, senão o ciclo connect/discover/CCCD/LOGIN gira
			// sem parar (ADR 11).
			else -> {
				teardown(current, WatchErrorCodes.DISCONNECTED, reason)
				scheduleReconnect(reason)
			}
		}
	}

	private suspend fun establish(attempt: Attempt) {
		try {
			val link = configure(attempt)
			val driver = Drivers.create(link)
			attempt.driver = driver
			link.describer = driver::describe
			driver.start(attempt.scope)
			attempt.scope.launch { driver.notices.collect { notice -> onNotice(attempt, notice) } }
			attempt.stage = Stage.HANDSHAKING
			val reason = "handshake ${attempt.mode}"
			setState(ConnectionState.HANDSHAKING, reason)
			driver.handshake(attempt.mode) { phase ->
				if (isCurrent(attempt)) setState(ConnectionState.HANDSHAKING, reason, phase)
			}
			onReady(attempt)
		} catch (error: CancellationException) {
			throw error
		} catch (error: WatchException) {
			onAttemptFailed(attempt, error)
		} catch (error: Exception) {
			onAttemptFailed(attempt, WatchException(WatchErrorCodes.PROTOCOL, "${error::class.simpleName}: ${error.message}", error))
		}
	}

	/** Descoberta, escolha do candidato GATT, CCCD e MTU (`docs/arquitetura.md`, estado CONFIGURING). */
	private suspend fun configure(attempt: Attempt): SessionDriverLink {
		val profile = attempt.profile
		val quirks = profile.quirks
		val discovery = CompletableDeferred<TransportEvent.ServicesDiscovered>()
		pendingDiscovery = discovery
		hostCallOrThrow("discoverServices") { transport.discoverServices() }
		val discovered = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { discovery.await() }
			?: throw WatchException(WatchErrorCodes.TRANSPORT, "service discovery not answered after $DISCOVERY_TIMEOUT_MS ms")
		if (pendingDiscovery === discovery) pendingDiscovery = null
		if (discovered.status != GattStatus.SUCCESS) {
			throw WatchException(WatchErrorCodes.TRANSPORT, "service discovery failed, status=${discovered.status}")
		}
		val candidate = chooseCandidate(profile, discovered.services)
			?: throw WatchException(
				WatchErrorCodes.GATT_NOT_SUPPORTED,
				"none of ${profile.gattCandidates.map { it.service }} found; discovered ${discovered.services.map { it.uuid }}",
			)
		log(LogLevel.INFO, "configure", "using service ${candidate.service} (write ${candidate.write}, notify ${candidate.notify})")
		val link = SessionDriverLink(profile, candidate, clock, logger, transport, operations, quirks.commandTimeoutMs * 2)
		attempt.link = link
		val cccd = operations.run(quirks.commandTimeoutMs) { id ->
			hostCallOrThrow("enableNotifications") { transport.enableNotifications(candidate.service, candidate.notify, id) }
		} as? TransportEvent.NotificationsEnabled
			?: throw WatchException(WatchErrorCodes.TRANSPORT, "enableNotifications not answered after ${quirks.commandTimeoutMs} ms")
		if (cccd.status != GattStatus.SUCCESS) {
			throw WatchException(WatchErrorCodes.TRANSPORT, "enableNotifications failed, status=${cccd.status}")
		}
		// Um MTU que não foi negociado não impede o protocolo: o SMA funciona com 20 bytes por escrita (o iOS usa
		// 20 por pedaço, SmaBLE.m:663-668). Por isso falha ou silêncio aqui só reduz o fragmento.
		val mtu = operations.run(MTU_TIMEOUT_MS) { id ->
			hostCallOrThrow("requestMtu") { transport.requestMtu(quirks.requestMtu, id) }
		} as? TransportEvent.MtuChanged
		link.mtu = when {
			mtu == null -> {
				log(LogLevel.WARN, "configure", "MTU not answered after $MTU_TIMEOUT_MS ms; using ${SessionDriverLink.DEFAULT_MTU}")
				SessionDriverLink.DEFAULT_MTU
			}
			mtu.status != GattStatus.SUCCESS || mtu.mtu < SessionDriverLink.DEFAULT_MTU -> {
				log(LogLevel.WARN, "configure", "MTU request failed (status=${mtu.status}, mtu=${mtu.mtu}); using ${SessionDriverLink.DEFAULT_MTU}")
				SessionDriverLink.DEFAULT_MTU
			}
			else -> mtu.mtu
		}
		log(LogLevel.INFO, "configure", "MTU ${link.mtu}")
		return link
	}

	/**
	 * O primeiro candidato do perfil cujo serviço e as duas characteristics aparecem na descoberta. A ordem do perfil
	 * é a prioridade (no fitGPS, `FF60` antes do NUS, como `SmaManager.selectUUID`, `SmaManager.java:1577-1591`).
	 */
	private fun chooseCandidate(profile: DeviceProfile, services: List<DiscoveredService>): GattCandidate? {
		val byUuid = services.associateBy { GattUuid.normalize(it.uuid) }
		for (candidate in profile.gattCandidates) {
			val normalized = GattCandidate(
				service = GattUuid.normalize(candidate.service),
				write = GattUuid.normalize(candidate.write),
				notify = GattUuid.normalize(candidate.notify),
			)
			val service = byUuid[normalized.service] ?: continue
			val characteristics = service.characteristics.map { GattUuid.normalize(it.uuid) }.toSet()
			if (normalized.write in characteristics && normalized.notify in characteristics) return normalized
			log(LogLevel.INFO, "configure", "service ${normalized.service} lacks write/notify characteristics; trying the next candidate")
		}
		return null
	}

	private fun onReady(attempt: Attempt) {
		if (!isCurrent(attempt)) return
		attempt.ready = true
		attempt.stage = Stage.READY
		bonded = true
		reconnectFailures = 0
		attempt.commandJob = attempt.scope.launch { runCommands(attempt) }
		setState(ConnectionState.READY, "handshake ${attempt.mode} ok")
		completeLifecycle()
		val delayMs = attempt.profile.quirks.postLoginDelayMs
		attempt.scope.launch {
			delay(delayMs)
			if (!isCurrent(attempt)) return@launch
			enqueue(SessionCommand.SetTime(WatchSession.NO_REQUEST_ID, internal = true))
			enqueue(SessionCommand.EnableNotices())
		}
	}

	private fun onAttemptFailed(attempt: Attempt, error: WatchException) {
		if (!isCurrent(attempt)) return
		if (attempt.mode == HandshakeMode.FIRST_PAIR && !attempt.ready) {
			val code = when {
				error.code == WatchErrorCodes.DISCONNECTED -> WatchErrorCodes.DISCONNECTED
				attempt.stage == Stage.HANDSHAKING -> WatchErrorCodes.HANDSHAKE_FAILED
				else -> error.code
			}
			val message = if (code == error.code) error.message else "${error.code}: ${error.message}"
			teardown(attempt, WatchErrorCodes.DISCONNECTED, message)
			disconnectTransport("pair failed")
			setState(ConnectionState.DISCONNECTED, "pair failed: $code")
			failLifecycle(code, message)
			return
		}
		if (error.code == WatchErrorCodes.HANDSHAKE_FAILED) {
			// O relógio recusou o LOGIN (o driver já tentou os dois formatos, sem BIND). Tentar de novo sozinho não
			// muda a resposta e só refazer o vínculo resolveria, o que exige o usuário (pode pedir confirmação na
			// tela do relógio): a sessão desiste e o host decide (ADR 11).
			teardown(attempt, WatchErrorCodes.DISCONNECTED, error.message)
			disconnectTransport("reconnect refused")
			bonded = false
			setState(ConnectionState.DISCONNECTED, "reconnect refused: ${error.message}")
			failLifecycle(WatchErrorCodes.HANDSHAKE_FAILED, error.message)
			return
		}
		teardown(attempt, WatchErrorCodes.DISCONNECTED, error.message)
		disconnectTransport("reconnect attempt failed")
		scheduleReconnect("reconnect attempt failed: ${error.code} ${error.message}")
	}

	/** Volta a tentar com espera crescente (2 s, 4 s, 8 s… até 60 s); zera quando chega em READY. */
	private fun scheduleReconnect(reason: String) {
		setState(ConnectionState.RECONNECTING, reason)
		reconnectFailures++
		val waitMs = reconnectDelayMs(reconnectFailures)
		log(LogLevel.INFO, "scheduleReconnect", "next attempt in $waitMs ms (failure #$reconnectFailures)")
		reconnectJob?.cancel()
		reconnectJob = scope.launch {
			delay(waitMs)
			reconnectJob = null
			if (state == ConnectionState.RECONNECTING && attempt == null && target != null && !userDisconnected) {
				startAttempt(HandshakeMode.RECONNECT, "retry after failure #$reconnectFailures")
			}
		}
	}

	/**
	 * Derruba a tentativa: falha os comandos, fecha driver e link, acorda as esperas GATT e cancela o escopo.
	 * Idempotente. Não mexe em [state] nem no pedido de ciclo de vida; quem chama decide.
	 */
	private fun teardown(attempt: Attempt, commandCode: String, message: String) {
		if (attempt.tornDown) return
		attempt.tornDown = true
		if (this.attempt === attempt) this.attempt = null
		failCommands(attempt, commandCode, message)
		attempt.driver?.close()
		attempt.link?.close()
		operations.failAll(WatchException(WatchErrorCodes.DISCONNECTED, message))
		pendingDiscovery?.cancel()
		pendingDiscovery = null
		attempt.scope.cancel()
		log(LogLevel.DEBUG, "teardown", "${attempt.mode} attempt closed: $message")
	}

	private fun disconnectTransport(reason: String) {
		if (!transportActive) return
		transportActive = false
		staleDisconnects++
		log(LogLevel.DEBUG, "disconnectTransport", reason)
		hostCall("disconnect") { transport.disconnect() }
	}

	private fun setTarget(address: String, profile: DeviceProfile) {
		target = Target(address, profile)
		currentAddress = address
		currentProfileId = profile.id
	}

	private fun isCurrent(attempt: Attempt): Boolean = this.attempt === attempt && !attempt.tornDown

	// endregion

	// region Comandos

	private fun enqueue(command: SessionCommand) {
		val current = attempt?.takeIf {
			it.ready && !it.closing && (state == ConnectionState.READY || state == ConnectionState.SYNCING)
		}
		if (current == null) {
			failCommand(command, WatchErrorCodes.NOT_READY, "${command.name} needs READY or SYNCING (state=$state)")
			return
		}
		if (current.queue.trySend(command).isFailure) {
			failCommand(command, WatchErrorCodes.NOT_READY, "${command.name}: command queue closed")
			return
		}
		current.accepted.removeAll { it.isFinished }
		current.accepted += command
		log(LogLevel.DEBUG, command.name, "queued #${command.requestId}")
	}

	private suspend fun runCommands(attempt: Attempt) {
		for (command in attempt.queue) {
			if (command.isFinished) continue
			attempt.inFlight = command
			try {
				execute(attempt, command)
			} catch (error: CancellationException) {
				throw error
			} catch (error: WatchException) {
				failCommand(command, error.code, error.message)
			} catch (error: Exception) {
				failCommand(command, WatchErrorCodes.PROTOCOL, "${error::class.simpleName}: ${error.message}")
			} finally {
				if (attempt.inFlight === command) attempt.inFlight = null
			}
		}
	}

	private suspend fun execute(attempt: Attempt, command: SessionCommand) {
		val driver = attempt.driver ?: throw WatchException(WatchErrorCodes.NOT_READY, "no driver")
		when (command) {
			is SessionCommand.ReadDeviceInfo -> {
				val info = driver.readDeviceInfo()
				val device = DeviceInfo(
					profileId = attempt.profile.id,
					address = attempt.target.address,
					// O nome anunciado não chega até a sessão (pair recebe só o endereço); o perfil só casa nomes exatos,
					// então o primeiro nome do perfil é o que o scan viu (DeviceRegistry.identify).
					bleName = attempt.profile.bleNames.firstOrNull() ?: attempt.profile.id,
					firmware = info.firmware,
					bleVersion = info.bleVersion,
				)
				succeed(command) { it.onDeviceInfo(command.requestId, device) }
			}
			is SessionCommand.ReadBattery -> {
				val percent = driver.readBattery()
				succeed(command) { it.onBattery(command.requestId, Battery(percent)) }
			}
			is SessionCommand.SetTime -> {
				driver.setTime(clock.nowEpochMillis(), clock.timeZoneId())
				succeed(command) { if (!command.internal) it.onCompleted(command.requestId) }
			}
			is SessionCommand.EnableNotices -> {
				driver.enableDataChangeNotices()
				succeed(command) {}
			}
			is SessionCommand.RefreshSteps -> {
				syncEngine.refreshSteps(driver)
				val today = history.today(aggregation())
				succeed(command) { it.onLiveSteps(command.requestId, today) }
			}
			is SessionCommand.SyncHistory -> runSync(attempt, driver, command)
		}
	}

	private suspend fun runSync(attempt: Attempt, driver: DeviceDriver, command: SessionCommand.SyncHistory) {
		val id = command.requestId
		setState(ConnectionState.SYNCING, "syncHistory #$id ${command.kinds.ifEmpty { DataKind.entries }}")
		notifyListener("onSyncStarted") { it.onSyncStarted(id) }
		try {
			syncEngine.sync(driver, command.kinds, attempt.profile.quirks.historyRetentionDays) { percent ->
				if (!command.isFinished) notifyListener("onSyncProgress") { it.onSyncProgress(id, percent) }
			}
		} finally {
			if (isCurrent(attempt) && state == ConnectionState.SYNCING) {
				setState(ConnectionState.READY, "syncHistory #$id ended")
			}
		}
		succeed(command) { it.onSyncFinished(id) }
	}

	private fun onNotice(attempt: Attempt, notice: DriverNotice) {
		if (!isCurrent(attempt)) return
		when (notice) {
			DriverNotice.DataChanged -> {
				// O relógio pode mandar vários 02/70 seguidos enquanto a pessoa anda; uma releitura a cada rajada basta.
				attempt.debounce?.cancel()
				attempt.debounce = attempt.scope.launch {
					delay(DATA_CHANGED_DEBOUNCE_MS)
					attempt.debounce = null
					enqueue(SessionCommand.RefreshSteps(WatchSession.NO_REQUEST_ID, internal = true))
				}
			}
			is DriverNotice.LinkReported ->
				log(LogLevel.INFO, "onLinkReported", "watch reports link connected=${notice.connected}")
		}
	}

	/**
	 * Falha o comando em andamento e os da fila, fecha a fila e cancela o consumidor.
	 *
	 * Usa [Attempt.accepted], e não só o que sobrou no canal: um comando que o canal já entregou ao consumidor, mas
	 * que o consumidor ainda não começou a executar (ele só roda na próxima vez que a thread da sessão ficar livre),
	 * não está mais no canal nem em [Attempt.inFlight], e sem essa lista ficaria sem terminal.
	 */
	private fun failCommands(attempt: Attempt, code: String, message: String) {
		attempt.queue.close()
		attempt.accepted.toList().forEach { failCommand(it, code, message) }
		attempt.accepted.clear()
		while (true) {
			val next = attempt.queue.tryReceive().getOrNull() ?: break
			failCommand(next, code, message)
		}
		attempt.commandJob?.cancel()
	}

	private fun succeed(command: SessionCommand, emit: (WatchSessionListener) -> Unit) {
		if (!command.markFinished()) return
		log(LogLevel.INFO, command.name, "#${command.requestId} done" + if (command.internal) " (internal)" else "")
		notifyListener(command.name, emit)
	}

	private fun failCommand(command: SessionCommand, code: String, message: String) {
		if (!command.markFinished()) return
		if (command.internal) {
			log(LogLevel.WARN, command.name, "internal command failed: $code $message")
			return
		}
		error(command.requestId, code, message, command.name)
	}

	// endregion

	// region Saída

	private fun setState(next: ConnectionState, reason: String, phase: String? = null) {
		val previous = state
		if (previous == next && phase == this.phase) return
		state = next
		this.phase = phase
		log(LogLevel.INFO, "transition", "$previous -> $next" + (phase?.let { " [$it]" } ?: "") + ": $reason")
		notifyListener("onStateChanged") { it.onStateChanged(next, phase) }
	}

	private fun completeLifecycle() {
		val request = lifecycle ?: return
		lifecycle = null
		completed(request.requestId, request.kind)
	}

	private fun failLifecycle(code: String, message: String) {
		val request = lifecycle ?: return
		lifecycle = null
		error(request.requestId, code, message, request.kind)
	}

	private fun completed(requestId: Int, method: String = "completed") {
		log(LogLevel.INFO, method, "#$requestId completed")
		notifyListener(method) { it.onCompleted(requestId) }
	}

	private fun error(requestId: Int, code: String, message: String, method: String = "error") {
		log(LogLevel.WARN, method, "#$requestId failed: $code $message")
		notifyListener(method) { it.onError(requestId, code, message) }
	}

	/** Um listener do host que lança não pode derrubar a sessão: a exceção vai para o log. */
	private inline fun notifyListener(method: String, block: (WatchSessionListener) -> Unit) {
		val target = listener ?: return
		try {
			block(target)
		} catch (error: Exception) {
			log(LogLevel.ERROR, method, "listener threw ${error::class.simpleName}: ${error.message}")
		}
	}

	private inline fun hostCall(method: String, block: () -> Unit): Boolean =
		try {
			block()
			true
		} catch (error: Exception) {
			log(LogLevel.ERROR, method, "Transport.$method threw ${error::class.simpleName}: ${error.message}")
			false
		}

	private inline fun hostCallOrThrow(method: String, block: () -> Unit) {
		if (!hostCall(method, block)) throw WatchException(WatchErrorCodes.TRANSPORT, "Transport.$method threw")
	}

	private fun aggregation(): StepsAggregation =
		(currentProfileId?.let { DeviceRegistry.byId(it) } ?: Profiles.MORMAII_FIT_GPS).quirks.stepsAggregation

	private fun log(level: LogLevel, method: String, message: String) {
		try {
			logger.log(level, "Session/$method", message)
		} catch (_: Exception) {
			// O logger é do host; uma falha nele não pode virar falha de protocolo.
		}
	}

	private fun reconnectDelayMs(failures: Int): Long {
		var waitMs = RECONNECT_BASE_DELAY_MS
		repeat((failures - 1).coerceIn(0, 10)) { waitMs *= 2 }
		return waitMs.coerceAtMost(RECONNECT_MAX_DELAY_MS)
	}

	// endregion

	/** Relógio alvo: o último `pair`/`reconnect`. */
	private class Target(val address: String, val profile: DeviceProfile)

	/** Um `pair` ou `reconnect` que termina quando a sessão chega em READY (ou falha). */
	private class LifecycleRequest(val requestId: Int, val kind: String)

	/** Etapa da tentativa, para traduzir o erro do primeiro pareamento (`HANDSHAKE_FAILED` só depois do link pronto). */
	private enum class Stage { CONNECTING, CONFIGURING, HANDSHAKING, READY }

	/**
	 * Uma tentativa de conexão: do `Transport.connect` até a queda do link. Tudo o que depende do link (driver, fila,
	 * esperas) vive em [scope], que [teardown] cancela.
	 */
	private class Attempt(val mode: HandshakeMode, val target: Target, val scope: CoroutineScope) {
		val profile: DeviceProfile
			get() = target.profile
		var stage: Stage = Stage.CONNECTING
		var connected = false
		var ready = false
		var closing = false
		var tornDown = false
		var link: SessionDriverLink? = null
		var driver: DeviceDriver? = null
		val queue = Channel<SessionCommand>(Channel.UNLIMITED)

		/** Comandos aceitos e ainda não terminados (inclui o em andamento); ver [failCommands]. */
		val accepted = mutableListOf<SessionCommand>()
		var inFlight: SessionCommand? = null
		var commandJob: Job? = null
		var debounce: Job? = null
	}

	/** Entradas da caixa da sessão, tratadas em ordem por uma única corrotina. */
	private sealed interface SessionInput {
		class Call(val requestId: Int?, val name: String, val action: () -> Unit) : SessionInput {
			override fun toString(): String = "call $name #${requestId ?: "-"}"
		}

		class Event(val event: TransportEvent) : SessionInput {
			override fun toString(): String = "event ${event::class.simpleName}"
		}
	}

	companion object {
		/** Espera pela descoberta de serviços; no Android ela costuma levar de 1 a 5 s. */
		const val DISCOVERY_TIMEOUT_MS: Long = 15_000

		/** Espera pela resposta do pedido de MTU; sem resposta, segue com 23. */
		const val MTU_TIMEOUT_MS: Long = 2_000

		/** Janela que junta vários avisos `DataChanged` numa releitura só. */
		const val DATA_CHANGED_DEBOUNCE_MS: Long = 2_000

		/** Primeira espera depois de uma tentativa de reconexão que falhou. */
		const val RECONNECT_BASE_DELAY_MS: Long = 2_000

		/** Teto da espera entre tentativas de reconexão. */
		const val RECONNECT_MAX_DELAY_MS: Long = 60_000
	}
}
