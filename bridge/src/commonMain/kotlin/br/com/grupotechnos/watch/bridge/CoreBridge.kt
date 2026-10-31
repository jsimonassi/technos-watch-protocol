package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.domain.Battery
import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.DeviceInfo
import br.com.grupotechnos.watch.protocol.domain.StepsDay
import br.com.grupotechnos.watch.protocol.domain.WatchErrorCodes
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.log.WatchLogger
import br.com.grupotechnos.watch.protocol.profile.DeviceProfile
import br.com.grupotechnos.watch.protocol.profile.DeviceRegistry
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.session.WatchSession
import br.com.grupotechnos.watch.protocol.session.WatchSessionListener
import kotlin.concurrent.Volatile
import kotlinx.datetime.TimeZone

/**
 * O controlador do Core React Native em código comum: implementa cada método JS de `src/specs/NativeCoreNext.ts`
 * (Repo 2) e o lado [WatchSessionListener] da sessão, com a plataforma atrás de [CoreHost], o módulo JS atrás de
 * [CoreEventSink] e a Promise atrás de [CoreCompletion]. O TurboModule de cada plataforma vira uma casca: cada método
 * chama o método de mesmo nome daqui.
 *
 * Reproduz o host Android antigo (`NativeCoreNextModule`, `CoreSessionListener` e `CoreGraph` do Repo 2) sem mudar nada
 * que o JavaScript observa: os mesmos nomes de evento, os mesmos payloads ([EventMapper]), a mesma ordem (o evento sai
 * antes do `resolve`), as mesmas rejeições (código e mensagem), o mesmo `NO_DATA`, as mesmas regras do pareamento salvo
 * e da reconexão automática, o mesmo ciclo de vida. O inventário método a método está em `docs/arquitetura.md`,
 * seção 13; o README do pacote tem as invariantes.
 *
 * **Montagem (um por processo).** O host cria a sessão, liga o transporte e entrega os três ao construtor:
 * ```
 * val session = WatchSession(transport, logger, store)
 * transport.attach(session.events)       // Android: AndroidGattTransport; iOS: transport.events = session.events
 * val bridge = CoreBridge(session, logger, host)
 * logger.onEntry = { bridge.onLogEntry(it) }
 * ```
 * O construtor chama `session.setListener`: o host não deve trocar o listener depois. O [CoreBridge] não fecha a sessão
 * (ela vive o processo inteiro, como o grafo do host).
 *
 * **Por que a sessão vem pronta, e não é montada aqui.** O host já precisa dela para ligar o transporte aos
 * `TransportEvents`, e o construtor da `WatchSession` é o contrato do :protocol que o Swift já usa; montá-la aqui
 * obrigaria a reexpor `session.events` e as variantes de relógio. E os testes do controlador rodam sobre uma
 * `WatchSession` de verdade em tempo virtual (`StandardTestDispatcher`), que só um teste consegue montar.
 *
 * **Threads.** Os métodos JS vêm da thread dos módulos nativos do React Native; os callbacks da sessão, da thread
 * (lógica) da sessão; [onScannedDevice], [onScanFailed] e os ganchos de Bluetooth, da principal ou da do Bluetooth;
 * [onLogEntry], de qualquer uma. O estado compartilhado é pequeno: a fila de eventos e os pedidos em andamento são
 * travados ([PendingEvents], [RequestRegistry]), os dois sinalizadores (o "usuário desconectou" e o sync em
 * andamento) são `@Volatile`, como no host antigo, e o estado do vínculo Bluetooth clássico (pedido em andamento,
 * endereços recusados, pedidos abandonados por um `forgetDevice`, alvos do `forgetDevice`) fica sob uma trava própria. Nenhum método bloqueia esperando o relógio.
 *
 * **Vínculo Bluetooth clássico.** Um perfil com `Quirks.classicBond` (o Condor Reebok, que usa HFP e A2DP) precisa do
 * vínculo BR/EDR do sistema, que o BLE não cria. No `READY` que fecha um handshake (não no do fim de um sync), depois de
 * gravar o pareamento, o controlador pergunta ao host se o vínculo existe ([CoreHost.hasClassicBond]) e, se não, pede
 * ([CoreHost.requestClassicBond]); o resultado volta por [onClassicBondResult]. Uma falha vira `onError`
 * `CLASSIC_BOND_FAILED` (`requestId` `-1`, `method` `"classicBond"`); falha e "não suportado" (iOS) não são pedidos de
 * novo até o próximo [pairDevice]. O [forgetDevice] desses perfis manda o relógio desvincular, depois o host remove o
 * vínculo ([CoreHost.removeClassicBond]) e só então o pareamento salvo é apagado de novo; um pedido de vínculo ainda em
 * andamento é abandonado nessa hora, e o resultado dele só vai para o log. Para os outros perfis nenhum desses métodos
 * do host é chamado.
 */
public class CoreBridge internal constructor(
	private val session: WatchSession,
	private val logger: WatchLogger,
	private val host: CoreHost,
	/** Fuso do telefone, lido na hora de cada chamada que depende dele; os testes fixam um. */
	private val systemZone: () -> TimeZone,
) {
	/**
	 * Controlador sobre [session], que o host já montou e ligou ao transporte.
	 *
	 * @param logger o mesmo `WatchLogger` da sessão: as linhas do controlador (`CoreBridge/...`) vão para ele.
	 * @param host a plataforma (permissões, scan, sync em segundo plano, pareamento salvo, gravação e log).
	 */
	public constructor(session: WatchSession, logger: WatchLogger, host: CoreHost) :
		this(session, logger, host, { TimeZone.currentSystemDefault() })

	private val events = PendingEvents<Map<String, Any?>>()
	private val requests = RequestRegistry<CoreCompletion>()
	private val sinkLock: BridgeLock = newReentrantLock()
	private var attached: SinkAdapter? = null

	/**
	 * `true` depois de `disconnectCurrentDevice`/`forgetDevice` e até o próximo `pairDevice`/`startPairingAttempt`.
	 * Impede a reconexão automática (volta ao app, Bluetooth religado, `initialize`) de desfazer um "desconectar"
	 * explícito, e o `READY` que chegue no meio de regravar o relógio esquecido. Só em memória: num processo novo o
	 * relógio pareado volta a ser reconectado, como o Core atual faz no boot.
	 */
	@Volatile
	internal var userDisconnected: Boolean = false

	/** `requestId` do sync em andamento enquanto o trabalho em segundo plano ([CoreHost.startSyncWork]) está ligado. */
	@Volatile
	private var activeSyncRequestId: Int? = null

	/**
	 * Trava do estado do vínculo clássico: [classicBondInFlight], [classicBondDeclined], [classicBondAbandoned] e
	 * [forgetTargets].
	 */
	private val classicBondLock: BridgeLock = newReentrantLock()

	/** Endereço com pedido de vínculo clássico em andamento no host ([CoreHost.requestClassicBond]); `null` sem pedido. */
	private var classicBondInFlight: String? = null

	/**
	 * Endereços cujo vínculo clássico falhou ou não é suportado nesta execução: não são pedidos de novo até o próximo
	 * [pairDevice] do mesmo endereço. Evita o laço de diálogos do Core de produção, que pedia de novo a cada recusa.
	 */
	private val classicBondDeclined: MutableSet<String> = mutableSetOf()

	/** `requestId` de um [forgetDevice] de perfil com `Quirks.classicBond` → endereço cujo vínculo o host remove no fim. */
	private val forgetTargets: MutableMap<Int, String> = mutableMapOf()

	/**
	 * Endereços cujo pedido de vínculo ainda estava em andamento quando o [forgetDevice] terminou: o pedido deixa de
	 * contar como em andamento ali, e o resultado que o host ainda manda (no Android, a falha do pedido cancelado pela
	 * remoção) só vai para o log, sem `onError` nem recusa, porque o relógio já foi esquecido.
	 */
	private val classicBondAbandoned: MutableSet<String> = mutableSetOf()

	/**
	 * O listener ligado à sessão. `internal` só para os testes chamarem um callback direto: com a sessão de hoje, alguns
	 * caminhos (a queda que muda o estado antes de o sync falhar, o erro de outro pedido durante o sync) nunca chegam
	 * nessa ordem, mas a regra do host antigo continua aqui.
	 */
	internal val sessionListener: WatchSessionListener = SessionListener()

	init {
		session.setListener(sessionListener)
	}

	// region Ciclo de vida

	/**
	 * Liga o módulo JS [sink] como destino dos eventos e entrega a fila, em ordem. O host chama na primeira chamada de um
	 * método JS de cada instância do módulo (como o `graph()` do host Android antigo); chamar de novo com o mesmo [sink]
	 * não faz nada (não entrega a fila de novo). Um [sink] diferente substitui o anterior.
	 *
	 * A troca e a entrega da fila acontecem sob a mesma trava: com dois `attach` ao mesmo tempo (duas instâncias do
	 * módulo no primeiro método JS, como no primeiro teste no iPhone, 2026-10-30), o sink da fila de eventos é sempre o
	 * último registrado aqui. Segurar a trava ao emitir não trava nada, porque o sink não chama o controlador de volta.
	 */
	public fun attach(sink: CoreEventSink) {
		sinkLock.withLock {
			if (attached?.sink === sink) return
			val adapter = SinkAdapter(sink)
			attached = adapter
			events.attach(adapter)
		}
	}

	/**
	 * O módulo [sink] foi destruído (reload ou fim do JS): desliga-o, se ainda for o atual, e rejeita com
	 * `CANCELLED` (`"Module invalidated"`) toda Promise ainda pendente, porque o JS que a esperava não existe mais. Os
	 * comandos continuam na sessão; o fim deles só não tem mais quem avisar (os eventos voltam a ficar na fila).
	 *
	 * O host só chama para um módulo que se ligou com [attach] (o host antigo não fazia nada num módulo que nunca foi
	 * usado).
	 */
	public fun invalidate(sink: CoreEventSink) {
		sinkLock.withLock {
			attached?.takeIf { it.sink === sink }?.let { adapter ->
				attached = null
				events.detach(adapter)
			}
		}
		requests.drain().forEach { it.token?.reject(WatchErrorCodes.CANCELLED, "Module invalidated") }
	}

	/**
	 * O app voltou ao primeiro plano: entrega a fila e reconecta ao relógio salvo, se fizer sentido
	 * ([reconnectIfPaired], motivo `"host resume"`). O host só chama para um módulo que se ligou com [attach].
	 */
	public fun onHostResume() {
		events.flush()
		reconnectIfPaired("host resume")
	}

	/**
	 * O Bluetooth do telefone desligou: para o scan. O transporte cuida do resto (entrega
	 * `onBluetoothStateChanged(false)` à sessão, contrato B do :protocol).
	 */
	public fun onBluetoothTurnedOff() {
		host.stopScan()
	}

	/**
	 * O Bluetooth do telefone ligou: reconecta ao relógio salvo, se fizer sentido ([reconnectIfPaired], motivo
	 * `"Bluetooth turned on"`). O host chama **depois** de entregar `onBluetoothStateChanged(true)` à sessão (no Android,
	 * no bloco que o `AndroidGattTransport.onBluetoothStateChanged` roda depois da entrega).
	 */
	public fun onBluetoothTurnedOn() {
		reconnectIfPaired("Bluetooth turned on")
	}

	/** Um relógio suportado apareceu no scan: emite `onScannedDevice` ([EventMapper.scannedDevice]). */
	public fun onScannedDevice(profile: DeviceProfile, address: String, rssi: Int, bleName: String) {
		emit("onScannedDevice", EventMapper.scannedDevice(profile, address, rssi, bleName))
	}

	/**
	 * O scan falhou ([errorCode] do sistema, ou `-1` quando nem começou): emite `onError` com `TRANSPORT`,
	 * `"$message (errorCode=$errorCode)"` e `method` `"scan"`.
	 */
	public fun onScanFailed(errorCode: Int, message: String) {
		emitError(WatchSession.NO_REQUEST_ID, WatchErrorCodes.TRANSPORT, "$message (errorCode=$errorCode)", METHOD_SCAN)
	}

	/**
	 * Uma entrada nova do log do host: emite `onLog` ([EventMapper.log]) se houver módulo pronto. **Não** entra na fila:
	 * é volumoso e já fica no log em memória do host (`getLogs`). Pode ser chamado de qualquer thread, inclusive de
	 * dentro de um `WatchLogger.log` do próprio controlador.
	 */
	public fun onLogEntry(entry: CoreLogEntry) {
		events.emit(
			"onLog",
			EventMapper.log(entry.ts, entry.level, entry.tag, entry.message, entry.dir?.name, entry.hex),
			queueIfNotReady = false,
		)
	}

	/**
	 * Resultado de um [CoreHost.requestClassicBond]: o pedido deixa de estar em andamento e, conforme [result],
	 * - [ClassicBondResult.BONDED]: só um INFO;
	 * - [ClassicBondResult.FAILED]: [address] não é pedido de novo até o próximo [pairDevice], e sai `onError` com
	 *   `CLASSIC_BOND_FAILED`, [message], `requestId` `-1` e `method` `"classicBond"` (não há Promise: o pedido nasce no
	 *   `READY`);
	 * - [ClassicBondResult.UNSUPPORTED]: [address] também não é pedido de novo até o próximo [pairDevice], e só sai um
	 *   WARN com [message] (no iOS, a dica de parear em Ajustes), sem `onError`.
	 *
	 * O resultado de um pedido que um [forgetDevice] abandonou (o relógio foi esquecido com o pedido em andamento) só vai
	 * para o log: nem `onError`, nem recusa, nem mexe no pedido em andamento seguinte.
	 *
	 * Pode ser chamado de qualquer thread, inclusive de dentro do [CoreHost.requestClassicBond].
	 */
	public fun onClassicBondResult(address: String, result: ClassicBondResult, message: String) {
		val abandoned = classicBondLock.withLock {
			if (classicBondAbandoned.remove(address)) {
				true
			} else {
				classicBondInFlight = null
				if (result != ClassicBondResult.BONDED) classicBondDeclined += address
				false
			}
		}
		if (abandoned) {
			log(LogLevel.INFO, METHOD_CLASSIC_BOND, "Classic bond result for forgotten $address ignored: $result ($message)")
			return
		}
		when (result) {
			ClassicBondResult.BONDED -> log(LogLevel.INFO, METHOD_CLASSIC_BOND, "Classic bond with $address ready: $message")
			ClassicBondResult.FAILED ->
				emitError(WatchSession.NO_REQUEST_ID, HostErrorCodes.CLASSIC_BOND_FAILED, message, METHOD_CLASSIC_BOND)
			ClassicBondResult.UNSUPPORTED -> log(LogLevel.WARN, METHOD_CLASSIC_BOND, message)
		}
	}

	// endregion

	// region Inicialização

	/**
	 * `initialize()`: reenvia o estado atual da sessão como `onConnectStateChanged` (fase `null`), tenta reconectar ao
	 * relógio salvo (motivo `"initialize"`) e resolve com `null`. Idempotente.
	 */
	public fun initialize(completion: CoreCompletion) {
		emitCurrentState()
		reconnectIfPaired("initialize")
		completion.resolve(null)
	}

	/** `initSMASDK()`: o mesmo que [initialize] (nome do Core atual). */
	public fun initSMASDK(completion: CoreCompletion) {
		initialize(completion)
	}

	// endregion

	// region Scan e conexão

	/**
	 * `scan()`: sem a permissão de scan emite `onError` `PERMISSION_DENIED` (`"Missing <permissão>"`); com o Bluetooth
	 * desligado, `onError` `BLUETOOTH_OFF` (`"Bluetooth is off"`); senão pede o scan ao host. Os dois erros têm
	 * `requestId` `-1` e `method` `"scan"`; não há Promise.
	 */
	public fun scan() {
		host.missingScanPermission()?.let { permission ->
			emitError(WatchSession.NO_REQUEST_ID, HostErrorCodes.PERMISSION_DENIED, "Missing $permission", METHOD_SCAN)
			return
		}
		if (!host.isBluetoothEnabled()) {
			emitError(WatchSession.NO_REQUEST_ID, HostErrorCodes.BLUETOOTH_OFF, "Bluetooth is off", METHOD_SCAN)
			return
		}
		host.startScan()
	}

	/** `stopScan()`: para o scan, se houver. */
	public fun stopScan() {
		host.stopScan()
	}

	/**
	 * `pairDevice(deviceId, name)`: primeiro pareamento. Confere permissão, Bluetooth, endereço e nome (rejeição
	 * antecipada, com `onError` antes); para o scan, zera o "usuário desconectou" e pede `WatchSession.pair`. Resolve
	 * com `null` quando a sessão chega em `READY`; uma falha sai como `onError` (`method` `"pairDevice"`) e rejeita com o
	 * código da sessão (inclusive `PAIRING_REMOVED`, que o JS mostra como "esqueça o relógio nos ajustes de Bluetooth").
	 * O nome do relógio só vai para o cabeçalho da gravação quando a tentativa chega ao handshake (o listener, em
	 * `HANDSHAKING`), e não aqui: uma tentativa que falha antes não gravou pacote e não pode renomear a gravação de outro
	 * relógio. Um vínculo clássico que falhou ou não é suportado para [deviceId] volta a ser pedido no próximo `READY`
	 * ([onClassicBondResult]).
	 *
	 * @param name `appName` (`"fitGPS"`, `"Life Ultra GPS"`, `"Condor Reebok"`: o que o app manda), nome BLE
	 * (`"Mormaii_fitGPS"`, `"Smart Watch 6010"`, `"P85"`, inclusive com sufixo, como `"P85-1234"`) ou `id` do perfil.
	 */
	public fun pairDevice(deviceId: String, name: String, completion: CoreCompletion) {
		val profile = checkConnectTarget(METHOD_PAIR, deviceId, name, completion) ?: return
		host.stopScan()
		userDisconnected = false
		classicBondLock.withLock {
			classicBondDeclined.remove(deviceId)
			classicBondAbandoned.remove(deviceId)
		}
		val requestId = requests.register(METHOD_PAIR, completion)
		session.pair(requestId, deviceId, profile.id)
	}

	/**
	 * `startPairingAttempt(deviceId, name)`: reconexão a um relógio já pareado. Mesmas checagens de [pairDevice], sem
	 * parar o scan; pede `WatchSession.reconnect` e **resolve na hora** com `null` (a reconexão pode levar horas, e o JS
	 * acompanha pelo `onConnectStateChanged`). Uma falha posterior sai só como `onError` (`method`
	 * `"startPairingAttempt"`). Como no [pairDevice], o cabeçalho da gravação só muda quando o handshake começa.
	 */
	public fun startPairingAttempt(deviceId: String, name: String, completion: CoreCompletion) {
		val profile = checkConnectTarget(METHOD_START_PAIRING_ATTEMPT, deviceId, name, completion) ?: return
		userDisconnected = false
		// Sem completion no registro: o terminal só emite o onError, com o nome do método.
		val requestId = requests.register(METHOD_START_PAIRING_ATTEMPT, null)
		session.reconnect(requestId, deviceId, profile.id)
		completion.resolve(null)
	}

	/**
	 * `disconnectCurrentDevice()`: marca "usuário desconectou" (a reconexão automática para) e pede
	 * `WatchSession.disconnect(forget = false)`. Resolve com `null` quando a sessão conclui. Não mexe no vínculo
	 * Bluetooth clássico: só o [forgetDevice] o remove.
	 */
	public fun disconnectCurrentDevice(completion: CoreCompletion) {
		userDisconnected = true
		session.disconnect(requests.register(METHOD_DISCONNECT, completion), false)
	}

	/**
	 * `forgetDevice()`: marca "usuário desconectou", apaga o relógio salvo e pede `WatchSession.disconnect(forget =
	 * true)` (UNBIND e store apagado). Quando a sessão conclui, apaga o salvo **de novo**, se o usuário não pediu outro
	 * pareamento no meio (cobre um `READY` que tenha gravado entre o pedido e o disconnect), e resolve com `null`.
	 *
	 * Num perfil com `Quirks.classicBond`, o endereço (o da sessão ou, sem ele, o do pareamento salvo, lido antes de ser
	 * apagado) é guardado com o pedido. No fim da sessão (`onCompleted` ou `onError`), a ordem é: o relógio já recebeu o
	 * pedido de desvincular (o `F8` do CRP), o host remove o vínculo clássico ([CoreHost.removeClassicBond]), o salvo é
	 * apagado de novo e a Promise é liquidada.
	 */
	public fun forgetDevice(completion: CoreCompletion) {
		userDisconnected = true
		val classicTarget = classicBondTarget()
		host.clearPairing()
		val requestId = requests.register(METHOD_FORGET, completion)
		if (classicTarget != null) classicBondLock.withLock { forgetTargets[requestId] = classicTarget }
		session.disconnect(requestId, true)
	}

	/** `isConnected()`: resolve com `true` em `READY` ou `SYNCING` ([StateMapper.isConnected]). Não fala com o relógio. */
	public fun isConnected(completion: CoreCompletion) {
		completion.resolve(StateMapper.isConnected(session.state))
	}

	/** `getLastPairedDevice()`: resolve com o relógio salvo ([EventMapper.lastPairedDevice]) ou `null`. */
	public fun getLastPairedDevice(completion: CoreCompletion) {
		val pairing = host.loadPairing()
		if (pairing == null) {
			completion.resolve(null)
			return
		}
		completion.resolve(EventMapper.lastPairedDevice(pairing.address, pairing.name, pairing.profileId))
	}

	// endregion

	// region Comandos ao relógio

	/** `getDeviceInfo()`: lê a identificação do relógio; emite só `onDeviceInfo` e resolve com `null`. */
	public fun getDeviceInfo(completion: CoreCompletion) {
		session.readDeviceInfo(requests.register(METHOD_DEVICE_INFO, completion))
	}

	/** `getFirmwareInfo()`: a mesma leitura de [getDeviceInfo]; emite só `onFirmwareInfo` e resolve com `null`. */
	public fun getFirmwareInfo(completion: CoreCompletion) {
		session.readDeviceInfo(requests.register(METHOD_FIRMWARE_INFO, completion))
	}

	/** `getBatteryStatus()`: emite `onBatteryStatus` e resolve com `null`. */
	public fun getBatteryStatus(completion: CoreCompletion) {
		session.readBattery(requests.register("getBatteryStatus", completion))
	}

	/** `setTime()`: acerta o relógio; sem evento, resolve com `null`. */
	public fun setTime(completion: CoreCompletion) {
		session.setTime(requests.register("setTime", completion))
	}

	/** `refreshSteps()`: lê os passos recentes; emite `onPedometer` com o total de hoje e resolve com `null`. */
	public fun refreshSteps(completion: CoreCompletion) {
		session.refreshSteps(requests.register("refreshSteps", completion))
	}

	/**
	 * `syncDataHistory()`: lê passos e sono do relógio. Emite `onSyncStarted`, `onSyncProgress` e `onSyncFinished`, e
	 * resolve com `null` depois do último; liga o trabalho em segundo plano do host durante o sync.
	 */
	public fun syncDataHistory(completion: CoreCompletion) {
		session.syncHistory(requests.register("syncDataHistory", completion), listOf(DataKind.STEPS, DataKind.SLEEP))
	}

	// endregion

	// region Histórico local

	/**
	 * `getHistoryPedometer(daysBefore)`: emite `onHistoryPedometer` com os dias não confirmados (lista vazia também) e
	 * resolve com `null`. Uma leitura que falha vira `IO_ERROR` (com `onError` antes). Não fala com o relógio.
	 *
	 * @param daysBefore o número do JS; truncado para inteiro, negativo vira 0 (a sessão não corta dias mais velhos que
	 * ainda não foram confirmados).
	 */
	public fun getHistoryPedometer(daysBefore: Double, completion: CoreCompletion) {
		try {
			val days = session.getStepsDays(daysBefore.toInt().coerceAtLeast(0))
			emit("onHistoryPedometer", EventMapper.historyPedometer(days))
			completion.resolve(null)
		} catch (error: RuntimeException) {
			rejectEarly(completion, METHOD_HISTORY_PEDOMETER, HostErrorCodes.IO_ERROR, describe(error))
		}
	}

	/**
	 * `getHistorySleep()`: emite `onHistorySleep` com as noites não confirmadas (fuso atual do telefone) e resolve com
	 * `null`. Sem nenhuma noite gravada, **nunca fica mudo**: emite `onError` `NO_DATA` (`"No sleep sessions stored"`,
	 * `requestId` `-1`, `method` `"getHistorySleep"`) e rejeita com o mesmo código e mensagem. Uma leitura que falha vira
	 * `IO_ERROR`. A janela é a retenção do perfil da sessão (ou do relógio salvo), 30 dias se não houver perfil.
	 */
	public fun getHistorySleep(completion: CoreCompletion) {
		val sessions = try {
			session.getSleepSessions(sleepHistoryDays())
		} catch (error: RuntimeException) {
			rejectEarly(completion, METHOD_HISTORY_SLEEP, HostErrorCodes.IO_ERROR, describe(error))
			return
		}
		if (sessions.isEmpty()) {
			rejectEarly(completion, METHOD_HISTORY_SLEEP, WatchErrorCodes.NO_DATA, "No sleep sessions stored")
			return
		}
		emit("onHistorySleep", EventMapper.historySleep(sessions, systemZone()))
		completion.resolve(null)
	}

	/**
	 * `deletedSavedData(filteredArrayData, category)`: marca dias como enviados ao backend
	 * (`WatchSession.confirmUploaded`). Sem Promise e sem evento; o que não deu para ler vai para o log.
	 *
	 * Cada item é uma [String] (o `registered_at` puro, que o wrapper do Core atual manda) ou um mapa com
	 * `registered_at` [String] e, opcional, `category` numérica (o formato do spec); qualquer outra coisa é ignorada sem
	 * log. A categoria do item vale mais que [category]. Categoria desconhecida: WARN `"Ignoring category N"`. Cada
	 * `registered_at` vira o dia local no fuso do telefone, lido uma vez para a chamada inteira ([SavedDataMapper]); as
	 * datas ilegíveis saem num WARN `"Unreadable dates: [...]"`, e cada tipo, num INFO `"kind=STEPS dayKeys=[...]"`.
	 *
	 * @param filteredArrayData a lista do JS já convertida pelo host: no Android, `ReadableConverter.toList` (números
	 * como `Double`, mapas como `Map`, pelo `getType` de cada item); no iOS, o `NSArray` do TurboModule.
	 * @param category `TechnosCore.deletedCategory` (5 passos, 0 sono), truncado para inteiro.
	 */
	public fun deletedSavedData(filteredArrayData: List<Any?>, category: Double) {
		val zone = systemZone()
		val fallbackCategory = category.toInt()
		val byKind = LinkedHashMap<DataKind, MutableList<String>>()
		for (item in filteredArrayData) {
			val (itemCategory, registeredAt) = readSavedItem(item, fallbackCategory) ?: continue
			val kind = SavedDataMapper.kindForCategory(itemCategory)
			if (kind == null) {
				log(LogLevel.WARN, METHOD_DELETED_SAVED_DATA, "Ignoring category $itemCategory")
				continue
			}
			byKind.getOrPut(kind) { mutableListOf() } += registeredAt
		}
		for ((kind, dates) in byKind) {
			val result = SavedDataMapper.dayKeys(dates, zone)
			if (result.rejected.isNotEmpty()) {
				log(LogLevel.WARN, METHOD_DELETED_SAVED_DATA, "Unreadable dates: ${result.rejected}")
			}
			log(LogLevel.INFO, METHOD_DELETED_SAVED_DATA, "kind=$kind dayKeys=${result.dayKeys}")
			if (result.dayKeys.isNotEmpty()) session.confirmUploaded(kind, result.dayKeys)
		}
	}

	// endregion

	// region Logs

	/**
	 * `getLogs(limit)`: resolve com as últimas entradas do log do host, da mais nova para a mais antiga, cada uma no
	 * formato de [EventMapper.log].
	 *
	 * @param limit o número do JS, truncado para inteiro; o host corta para `0..tamanho`.
	 */
	public fun getLogs(limit: Double, completion: CoreCompletion) {
		val entries = host.logEntries(limit.toInt())
		completion.resolve(entries.map { EventMapper.log(it.ts, it.level, it.tag, it.message, it.dir?.name, it.hex) })
	}

	/**
	 * `exportLogs()`: resolve com o caminho que [CoreHost.exportLogs] devolve; uma falha vira `IO_ERROR` com a mensagem
	 * da exceção (com `onError` antes).
	 */
	public fun exportLogs(completion: CoreCompletion) {
		try {
			completion.resolve(host.exportLogs())
		} catch (error: Exception) {
			rejectEarly(completion, METHOD_EXPORT_LOGS, HostErrorCodes.IO_ERROR, describe(error))
		}
	}

	/** `clearLogs()`: esvazia o log em memória do host e começa uma gravação de frames nova. */
	public fun clearLogs() {
		host.clearLogs()
	}

	/**
	 * `setLogLevel(level)`: 0 = DEBUG, 1 = INFO, 2 = WARN, 3 = ERROR (o número do JS truncado para inteiro). Outro valor
	 * só deixa um WARN `"Invalid level <número>"` e não muda nada.
	 */
	public fun setLogLevel(level: Double) {
		val newLevel = LogLevel.entries.getOrNull(level.toInt())
		if (newLevel == null) {
			log(LogLevel.WARN, "setLogLevel", "Invalid level $level")
			return
		}
		host.setLogLevel(newLevel)
	}

	// endregion

	// region Regras do processo

	/**
	 * Reenvia o estado atual como `onConnectStateChanged` (fase `null`, que a sessão não expõe). A sessão sobrevive ao
	 * reload do JS, mas o JS novo só soube das transições que caíram no intervalo: com o relógio já `READY`, ficaria sem
	 * estado até a próxima. Uma transição da thread da sessão pode sair entre a leitura e a emissão; por isso relê
	 * depois de emitir e reenvia se mudou, para o último evento ser sempre o estado atual.
	 */
	internal fun emitCurrentState() {
		var emitted: ConnectionState
		do {
			emitted = session.state
			emit("onConnectStateChanged", EventMapper.connectStateChanged(emitted, null))
		} while (session.state != emitted)
	}

	/** Emite `onError` ([EventMapper.error]), depois de um WARN no log com os mesmos campos. */
	internal fun emitError(requestId: Int, code: String, message: String, method: String?) {
		log(LogLevel.WARN, "emitError", "method=$method requestId=$requestId code=$code message=$message")
		emit("onError", EventMapper.error(requestId, code, message, method))
	}

	/**
	 * Perfil a partir do nome que o JS passa em `pairDevice(deviceId, name)`: aceita o `appName` (`"fitGPS"`, o que o
	 * app Mormaii manda hoje, `AvailableDevices.java:21-23` do Core atual; `"Life Ultra GPS"` no iDO; `"Condor Reebok"`
	 * no CRP), o nome BLE (`"Mormaii_fitGPS"`, `"Life Ultra GPS"`, `"Smart Watch 6010"`, `"P85"`) e o `id`. Por último,
	 * o nome de scan como o `DeviceRegistry.identify` o lê (com `trim` e os prefixos de `DeviceProfile.bleNamePrefixes`):
	 * `"P85-1234"` é o Condor Reebok, como no `startsWith` do Core atual (`AvailableDevices.java:55-57`).
	 */
	internal fun resolveProfile(name: String): DeviceProfile? =
		Profiles.all.firstOrNull { profile -> name == profile.appName || name == profile.id || name in profile.bleNames }
			?: DeviceRegistry.identify(name, emptyList())

	/** Dias de sono pedidos à sessão em `getHistorySleep` (o spec não tem parâmetro): a retenção do perfil. */
	internal fun sleepHistoryDays(): Int {
		val profileId = session.currentProfileId ?: host.loadPairing()?.profileId
		return profileId?.let(DeviceRegistry::byId)?.quirks?.historyRetentionDays ?: DEFAULT_HISTORY_DAYS
	}

	/**
	 * No `READY` que fecha um handshake (thread da sessão, depois de gravar o pareamento; o `READY` do fim de um sync não
	 * chama: o vínculo não muda no meio da conexão, e cada sync faria uma consulta ao sistema e um INFO): pede ao host o
	 * vínculo Bluetooth clássico do relógio da sessão, se o perfil tem `Quirks.classicBond` e o sistema ainda não o tem.
	 * Sai sem chamar o host quando o perfil
	 * não tem o quirk (SMA e iDO: nenhum método de vínculo do host é chamado), o usuário desconectou, a sessão não tem
	 * endereço, já há um pedido em andamento ou o endereço falhou ou não é suportado nesta execução (até o próximo
	 * [pairDevice]). Com o vínculo já no sistema ([CoreHost.hasClassicBond]), só um INFO. Senão marca o pedido em
	 * andamento **antes** de chamar o host, porque o resultado pode voltar de dentro da própria chamada.
	 *
	 * @return `true` se pediu o vínculo ao host.
	 */
	internal fun requestClassicBondIfNeeded(): Boolean {
		val profile = session.currentProfileId?.let(DeviceRegistry::byId) ?: return false
		if (!profile.quirks.classicBond || userDisconnected) return false
		val address = session.currentAddress ?: return false
		if (!canRequestClassicBond(address)) return false
		if (host.hasClassicBond(address)) {
			log(LogLevel.INFO, METHOD_CLASSIC_BOND, "Classic bond with $address already exists")
			return false
		}
		val claimed = classicBondLock.withLock {
			canRequestClassicBond(address).also { allowed -> if (allowed) classicBondInFlight = address }
		}
		if (!claimed) return false
		log(LogLevel.INFO, METHOD_CLASSIC_BOND, "Requesting classic bond with $address")
		host.requestClassicBond(address)
		return true
	}

	/**
	 * Reconecta ao relógio salvo se fizer sentido: há pareamento salvo, ninguém pediu para desconectar, a sessão está
	 * parada (`DISCONNECTED` ou `BLUETOOTH_OFF`), o Bluetooth está ligado e há permissão de conexão. É o equivalente
	 * nativo do `useSdkInit` do app Mormaii, que chama `pairDevice(lastDevice)` a cada volta ao primeiro plano
	 * (`useSdkInit.ts:15-32`). O `requestId` é interno, sem completion: uma falha sai como `onError` com `method` `null`.
	 *
	 * Uma tentativa por gatilho (`initialize`, volta ao primeiro plano, Bluetooth religado): quando a sessão desiste
	 * (`HANDSHAKE_FAILED` de um LOGIN recusado, `PAIRING_REMOVED` de um vínculo apagado no relógio) ela fica em
	 * `DISCONNECTED`, e só o próximo gatilho tenta de novo; nada aqui reage ao `DISCONNECTED`, então não há laço. Com
	 * `PAIRING_REMOVED`, a volta ao primeiro plano depois de o usuário esquecer o relógio nos Ajustes é justamente o
	 * gatilho que reconecta.
	 *
	 * @return `true` se pediu a reconexão.
	 */
	internal fun reconnectIfPaired(reason: String): Boolean {
		if (userDisconnected) return false
		val pairing = host.loadPairing() ?: return false
		val state = session.state
		if (state != ConnectionState.DISCONNECTED && state != ConnectionState.BLUETOOTH_OFF) return false
		if (!host.isBluetoothEnabled() || host.missingConnectPermission() != null) return false
		log(LogLevel.INFO, "reconnectIfPaired", "Reconnecting to ${pairing.address} ($reason)")
		session.reconnect(requests.nextId(), pairing.address, pairing.profileId)
		return true
	}

	/** Pedidos com completion ainda pendente (só para os testes). */
	internal val pendingRequests: Int
		get() = requests.size

	/** Eventos guardados na fila (só para os testes). */
	internal val queuedEvents: Int
		get() = events.size

	// endregion

	// region Auxiliares

	private fun emit(name: String, payload: Map<String, Any?>) {
		events.emit(name, payload)
	}

	private fun log(level: LogLevel, method: String, message: String) {
		logger.log(level, "$TAG/$method", message)
	}

	/** Valida permissão, Bluetooth, endereço e nome antes de conectar. Devolve o perfil, ou `null` depois de rejeitar. */
	private fun checkConnectTarget(method: String, deviceId: String, name: String, completion: CoreCompletion): DeviceProfile? {
		host.missingConnectPermission()?.let { permission ->
			rejectEarly(completion, method, HostErrorCodes.PERMISSION_DENIED, "Missing $permission")
			return null
		}
		if (!host.isBluetoothEnabled()) {
			rejectEarly(completion, method, HostErrorCodes.BLUETOOTH_OFF, "Bluetooth is off")
			return null
		}
		if (!host.isValidAddress(deviceId)) {
			rejectEarly(completion, method, HostErrorCodes.INVALID_ARGUMENT, "Invalid Bluetooth address $deviceId")
			return null
		}
		val profile = resolveProfile(name)
		if (profile == null) {
			rejectEarly(completion, method, WatchErrorCodes.UNSUPPORTED_DEVICE, "Unsupported device name $name")
			return null
		}
		return profile
	}

	/** Erro detectado antes de falar com a sessão: `onError` com `requestId` `-1` e o [method], depois o `reject`. */
	private fun rejectEarly(completion: CoreCompletion, method: String, code: String, message: String) {
		emitError(WatchSession.NO_REQUEST_ID, code, message, method)
		completion.reject(code, message)
	}

	/** O que o host antigo punha na rejeição: a mensagem da exceção ou, sem mensagem, o nome simples da classe. */
	private fun describe(error: Throwable): String = error.message ?: error::class.simpleName ?: ""

	/** `true` se não há pedido de vínculo clássico em andamento e [address] não foi recusado nesta execução. */
	private fun canRequestClassicBond(address: String): Boolean = classicBondLock.withLock {
		classicBondInFlight == null && address !in classicBondDeclined
	}

	/**
	 * Endereço cujo vínculo clássico o [forgetDevice] remove no fim: o da sessão (com o perfil da sessão) ou, sem ele, o
	 * do pareamento salvo (com o perfil salvo), se o perfil tem `Quirks.classicBond`; senão `null`.
	 */
	private fun classicBondTarget(): String? {
		val sessionAddress = session.currentAddress
		val (address, profileId) = if (sessionAddress != null) {
			sessionAddress to session.currentProfileId
		} else {
			val saved = host.loadPairing() ?: return null
			saved.address to saved.profileId
		}
		val profile = profileId?.let(DeviceRegistry::byId) ?: return null
		return address.takeIf { profile.quirks.classicBond }
	}

	/**
	 * Um item do `deletedSavedData`: a string pura (`TechnosWareCore/src/NativeModules/index.ts:763-768`) ou o mapa do
	 * spec. Devolve `(categoria, registered_at)`, ou `null` para um item que não dá para ler.
	 */
	private fun readSavedItem(item: Any?, fallbackCategory: Int): Pair<Int, String>? = when (item) {
		is String -> fallbackCategory to item
		is Map<*, *> -> readSavedMap(item, fallbackCategory)
		else -> null
	}

	private fun readSavedMap(item: Map<*, *>, fallbackCategory: Int): Pair<Int, String>? {
		val registeredAt = item["registered_at"] as? String ?: return null
		val itemCategory = (item["category"] as? Number)?.toDouble()?.toInt() ?: fallbackCategory
		return itemCategory to registeredAt
	}

	/** Nome do relógio no cabeçalho da gravação quando o handshake começa, antes de a sessão ler a identificação. */
	private fun recordingName(profile: DeviceProfile): String = profile.bleNames.firstOrNull() ?: profile.appName

	/** O [CoreEventSink] do módulo como destino da [PendingEvents]. */
	private class SinkAdapter(val sink: CoreEventSink) : PendingEvents.Sink<Map<String, Any?>> {
		override fun canEmit(): Boolean = sink.canEmit()

		override fun emit(name: String, payload: Map<String, Any?>) {
			sink.emit(name, payload)
		}
	}

	// endregion

	/**
	 * Saída da sessão (thread da sessão). Em cada callback, nesta ordem: efeitos do processo (pareamento salvo, trabalho
	 * em segundo plano, cabeçalho da gravação), o evento JS e, se o callback é terminal, a liquidação da completion
	 * (regra do `requestId` do [WatchSessionListener]). O evento sai antes do `resolve` para quem espera a Promise já
	 * encontrar o dado emitido. Toda falha de comando também sai como `onError`, com o nome do método JS que a originou.
	 */
	private inner class SessionListener : WatchSessionListener {
		/** `perfil/endereço` do relógio que o cabeçalho da gravação nomeia; `null` até o primeiro handshake. Só a thread da sessão usa. */
		private var recordedWatch: String? = null

		/** Estado anterior ao callback atual (o `READY` de um handshake vem de `HANDSHAKING`). Só a thread da sessão usa. */
		private var previousState: ConnectionState? = null

		override fun onStateChanged(state: ConnectionState, phase: String?) {
			log(LogLevel.INFO, "onStateChanged", "state=$state phase=$phase")
			val previous = previousState
			previousState = state
			// Depois de um disconnect/forget do usuário não grava: um handshake que chega a READY entre o forgetDevice e a
			// sessão processar o disconnect regravaria o relógio esquecido, e o próximo processo reconectaria a ele.
			if (state == ConnectionState.READY) {
				if (!userDisconnected) savePairing()
				if (previous == ConnectionState.HANDSHAKING) requestClassicBondIfNeeded()
			}
			if (state != ConnectionState.READY && state != ConnectionState.SYNCING) stopSyncWork("state=$state")
			if (state == ConnectionState.HANDSHAKING) recordLinkedWatch()
			emit("onConnectStateChanged", EventMapper.connectStateChanged(state, phase))
		}

		override fun onDeviceInfo(requestId: Int, info: DeviceInfo) {
			host.setRecordingFirmware(info.firmware)
			host.setRecordingDeviceName(info.bleName)
			val entry = requests.take(requestId)
			// getDeviceInfo e getFirmwareInfo usam a mesma leitura; cada um emite o seu evento. Espontâneo emite os dois.
			when (entry?.method) {
				METHOD_FIRMWARE_INFO -> emit("onFirmwareInfo", EventMapper.firmwareInfo(info))
				METHOD_DEVICE_INFO -> emit("onDeviceInfo", EventMapper.deviceInfo(info))
				else -> {
					emit("onDeviceInfo", EventMapper.deviceInfo(info))
					emit("onFirmwareInfo", EventMapper.firmwareInfo(info))
				}
			}
			entry?.token?.resolve(null)
		}

		override fun onBattery(requestId: Int, battery: Battery) {
			emit("onBatteryStatus", EventMapper.battery(battery))
			requests.take(requestId)?.token?.resolve(null)
		}

		override fun onLiveSteps(requestId: Int, today: StepsDay) {
			emit("onPedometer", EventMapper.pedometer(today))
			requests.take(requestId)?.token?.resolve(null)
		}

		override fun onSyncStarted(requestId: Int) {
			activeSyncRequestId = requestId
			host.startSyncWork()
			emit("onSyncStarted", EventMapper.empty())
		}

		override fun onSyncProgress(requestId: Int, percent: Int) {
			emit("onSyncProgress", EventMapper.syncProgress(percent))
		}

		override fun onSyncFinished(requestId: Int) {
			if (activeSyncRequestId == requestId) stopSyncWork("sync finished")
			emit("onSyncFinished", EventMapper.empty())
			requests.take(requestId)?.token?.resolve(null)
		}

		override fun onCompleted(requestId: Int) {
			val entry = requests.take(requestId)
			removeForgottenClassicBond(requestId)
			if (entry?.method == METHOD_FORGET) clearForgottenPairing()
			entry?.token?.resolve(null)
		}

		override fun onError(requestId: Int, code: String, message: String) {
			if (activeSyncRequestId == requestId) stopSyncWork("sync error $code")
			val entry = requests.take(requestId)
			removeForgottenClassicBond(requestId)
			if (entry?.method == METHOD_FORGET) clearForgottenPairing()
			emitError(requestId, code, message, entry?.method)
			entry?.token?.reject(code, message)
		}

		/**
		 * A tentativa chegou ao handshake (`HANDSHAKING`; as fases repetem o estado e caem no "mesmo relógio"): se o
		 * relógio (perfil e endereço) não é o que o cabeçalho já nomeia, o nome passa a ser o dele e o firmware do anterior
		 * sai (`null`) até o `onDeviceInfo` do novo. É a primeira etapa que com certeza grava um pacote do relógio novo: o
		 * handshake dos dois drivers começa com um pedido, e o link registra o TX antes de mandá-lo. Antes dela a tentativa
		 * pode falhar sem gravar nada (pedido que nunca conecta, descoberta com erro, `GATT_NOT_SUPPORTED`, link que cai
		 * antes dos CCCD, `PAIRING_REMOVED`), e renomear ali repetiria o erro do primeiro teste no iPhone (2026-10-30): os
		 * pedidos que falharam com o Life Ultra GPS renomearam a gravação do fitGPS, que saiu com `device`
		 * `"Life Ultra GPS"` e `firmware` `"0.2.1"` (o do fitGPS). Sobra um caso: uma notificação que chegue entre os CCCD
		 * de uma tentativa que falha antes do handshake fica sob o cabeçalho anterior. Os pacotes de antes continuam na
		 * gravação (o host só começa outra no `clearLogs`): uma exportação tem um cabeçalho só (`docs/fixtures.md`,
		 * seção 3.1).
		 */
		private fun recordLinkedWatch() {
			val profileId = session.currentProfileId ?: return
			val address = session.currentAddress ?: return
			val profile = DeviceRegistry.byId(profileId) ?: return
			val watch = "$profileId/$address"
			if (watch == recordedWatch) return
			val previous = recordedWatch
			recordedWatch = watch
			val name = recordingName(profile)
			if (previous != null) {
				host.setRecordingFirmware(null)
				log(LogLevel.INFO, "recordLinkedWatch", "Recording header now names $name ($profileId); packets recorded before belong to the previous watch")
			}
			host.setRecordingDeviceName(name)
		}

		private fun savePairing() {
			val address = session.currentAddress ?: return
			val profileId = session.currentProfileId ?: return
			val name = DeviceRegistry.byId(profileId)?.appName ?: profileId
			host.savePairing(CorePairing(address, profileId, name))
		}

		/**
		 * Fim do `forgetDevice` de um perfil com `Quirks.classicBond` (sucesso ou erro): o host remove o vínculo clássico
		 * guardado com o pedido. A sessão já mandou o pedido de desvincular ao relógio (o `F8` do CRP), e o pareamento
		 * ainda vai ser apagado de novo ([clearForgottenPairing]). Não depende da completion: um `invalidate` no meio
		 * (reload do JS) não impede a remoção.
		 */
		private fun removeForgottenClassicBond(requestId: Int) {
			val (address, abandoned) = classicBondLock.withLock {
				forgetTargets.remove(requestId)?.let { target ->
					// Um pedido de vínculo em andamento para o relógio esquecido é abandonado aqui, antes da remoção: o host
					// o encerra (Android) e o resultado que vier depois só vai para o log (onClassicBondResult).
					val inFlight = classicBondInFlight == target
					if (inFlight) {
						classicBondInFlight = null
						classicBondAbandoned += target
					}
					target to inFlight
				}
			} ?: return
			if (abandoned) log(LogLevel.INFO, METHOD_CLASSIC_BOND, "Classic bond request for $address abandoned: the watch was forgotten")
			log(LogLevel.INFO, METHOD_CLASSIC_BOND, "Removing classic bond with $address")
			host.removeClassicBond(address)
		}

		/**
		 * Fim do `forgetDevice`: apaga o pareamento de novo, agora que a sessão já processou o disconnect. O
		 * [forgetDevice] já apagou ao receber o pedido; esta segunda vez cobre um `READY` que tenha gravado no meio. Se o
		 * usuário já pediu outro pareamento ([userDisconnected] voltou a `false`), o que está salvo é o novo e fica.
		 */
		private fun clearForgottenPairing() {
			if (userDisconnected) host.clearPairing()
		}

		private fun stopSyncWork(reason: String) {
			if (activeSyncRequestId == null) return
			activeSyncRequestId = null
			log(LogLevel.DEBUG, "stopSyncWork", reason)
			host.stopSyncWork()
		}
	}

	private companion object {
		const val TAG = "CoreBridge"

		/** Retenção usada no `getHistorySleep` quando não há perfil (nem na sessão, nem salvo). */
		const val DEFAULT_HISTORY_DAYS = 30

		const val METHOD_SCAN = "scan"
		const val METHOD_PAIR = "pairDevice"
		const val METHOD_START_PAIRING_ATTEMPT = "startPairingAttempt"
		const val METHOD_DISCONNECT = "disconnectCurrentDevice"
		const val METHOD_FORGET = "forgetDevice"
		const val METHOD_DEVICE_INFO = "getDeviceInfo"
		const val METHOD_FIRMWARE_INFO = "getFirmwareInfo"
		const val METHOD_HISTORY_PEDOMETER = "getHistoryPedometer"
		const val METHOD_HISTORY_SLEEP = "getHistorySleep"
		const val METHOD_DELETED_SAVED_DATA = "deletedSavedData"
		const val METHOD_EXPORT_LOGS = "exportLogs"

		/** `method` do `onError` `CLASSIC_BOND_FAILED` e tag do log do vínculo clássico (não é método JS). */
		const val METHOD_CLASSIC_BOND = "classicBond"
	}
}
