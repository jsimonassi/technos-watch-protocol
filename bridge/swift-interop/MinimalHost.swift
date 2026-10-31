// Host iOS mínimo, só para o compilador Swift conferir a API pública como o Swift a enxerga.
//
// Não roda e não fala com Bluetooth: a tarefa `./gradlew :bridge:checkSwiftInterop` faz `swiftc -typecheck` deste
// arquivo contra o framework guarda-chuva de debug do simulador (o :protocol exportado mais o :bridge). Só é conferido o
// que este arquivo usa: se um desses nomes, rótulos ou tipos mudar no header gerado (`TechnosWatchProtocol.h`), ele
// deixa de compilar e a tarefa falha. Um símbolo público que não aparece aqui pode mudar sem a tarefa notar. O que ele
// cobre está no README.md ao lado.

import Foundation
import TechnosWatchProtocol

/// `Transport` como o Swift o vê: um `CentralTransport` de verdade faria isto sobre `CBCentralManager`/`CBPeripheral`.
final class MinimalTransport: NSObject, Transport {
	/// Entrada de eventos da sessão; o host real a recebe logo depois de criar a `WatchSession`.
	var events: TransportEvents?

	/// O que a sessão mandou escrever, já como `Data` (o que iria para `CBPeripheral.writeValue`).
	private(set) var written: [Data] = []

	func connect(address: String, autoReconnect: Bool) {
		events?.onConnected()
	}

	func disconnect() {
		events?.onDisconnected(status: 0, message: "requested")
	}

	func discoverServices() {
		let nus = DiscoveredService(
			uuid: GattUuid.shared.normalize(uuid: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
			characteristics: [
				DiscoveredCharacteristic(uuid: "6e400002-b5a3-f393-e0a9-e50e24dcca9e", canRead: false, canWrite: true, canWriteWithoutResponse: true, canNotify: false),
				DiscoveredCharacteristic(uuid: "6e400003-b5a3-f393-e0a9-e50e24dcca9e", canRead: false, canWrite: false, canWriteWithoutResponse: false, canNotify: true),
			]
		)
		events?.onServicesDiscovered(services: [nus], status: GattStatus.shared.SUCCESS)
	}

	func enableNotifications(service: String, characteristic: String, requestId: Int32) {
		events?.onNotificationsEnabled(requestId: requestId, status: GattStatus.shared.SUCCESS)
	}

	func requestMtu(mtu: Int32, requestId: Int32) {
		// Regra do host iOS (docs/decisoes.md, ADR 5): responder com maximumWriteValueLength(for: .withoutResponse) + 3,
		// nunca com o valor de .withResponse (512, que viraria uma escrita longa). Aqui, o valor de um link de MTU 23.
		let maximumWriteWithoutResponse = 20
		events?.onMtuChanged(requestId: requestId, mtu: Int32(maximumWriteWithoutResponse + 3), status: GattStatus.shared.SUCCESS)
	}

	func write(service: String, characteristic: String, value: KotlinByteArray, type: WriteType, requestId: Int32) {
		// A ponte de bytes do iosMain: cópia para Data, sem percorrer o KotlinByteArray byte a byte.
		let data: Data = value.toData()
		written.append(data)
		// .withResponse termina em didWriteValueFor; .withoutResponse não tem esse callback no iOS, e o host completa
		// quando o CoreBluetooth aceita a escrita. A sessão SMA só usa .withResponse.
		let withResponse: Bool = type == WriteType.withResponse
		precondition(withResponse || type == WriteType.withoutResponse)
		events?.onWriteCompleted(requestId: requestId, status: GattStatus.shared.SUCCESS)
	}

	func read(service: String, characteristic: String, requestId: Int32) {
		let value: Data? = nil
		events?.onReadCompleted(requestId: requestId, data: value, status: GattStatus.shared.NOT_FOUND)
	}
}

/// `WatchLogger`: o pacote cru chega como `KotlinByteArray`; vira `Data` para log e vai direto para o `FrameJsonl`.
final class MinimalLogger: NSObject, WatchLogger {
	private(set) var lines: [String] = []

	func log(level: LogLevel, tag: String, message: String) {
		lines.append("\(level.name) \(tag) \(message)")
	}

	func frame(direction: FrameDirection, characteristic: String, value: KotlinByteArray, summary: String) {
		let data: Data = value.toData()
		let hex = data.map { String(format: "%02X", $0) }.joined()
		lines.append("\(direction.name) \(hex) \(summary)")
		lines.append(FrameJsonl.shared.line(direction: direction, atMs: 0, characteristic: characteristic, value: value))
	}
}

/// `SampleStore` em memória; o host real grava JSON em Application Support.
final class MinimalStore: NSObject, SampleStore {
	private var steps: [StepsSample] = []
	private var sleep: [SleepSession] = []
	private var confirmed: [String: [String]] = [:]

	func loadSteps() -> [StepsSample] { steps }

	func saveSteps(samples: [StepsSample]) { steps = samples }

	func loadSleep() -> [SleepSession] { sleep }

	func saveSleep(sessions: [SleepSession]) { sleep = sessions }

	func loadConfirmedDays(kind: DataKind) -> [String] { confirmed[kind.name] ?? [] }

	func saveConfirmedDays(kind: DataKind, dayKeys: [String]) { confirmed[kind.name] = dayKeys }

	func clear() {
		steps = []
		sleep = []
		confirmed = [:]
	}
}

/// `WatchSessionListener`: chamado na thread da sessão; o host real despacha para onde precisar.
final class MinimalListener: NSObject, WatchSessionListener {
	private(set) var received: [String] = []

	func onStateChanged(state: ConnectionState, phase: String?) { received.append("state \(state.name) \(phase ?? "-")") }

	func onDeviceInfo(requestId: Int32, info: DeviceInfo) { received.append("info \(requestId) \(info.firmware)") }

	func onBattery(requestId: Int32, battery: Battery) { received.append("battery \(requestId) \(battery.percent) \(battery.charging)") }

	func onLiveSteps(requestId: Int32, today: StepsDay) { received.append("steps \(requestId) \(today.steps)") }

	func onSyncStarted(requestId: Int32) { received.append("sync started \(requestId)") }

	func onSyncProgress(requestId: Int32, percent: Int32) { received.append("sync \(requestId) \(percent)%") }

	func onSyncFinished(requestId: Int32) { received.append("sync finished \(requestId)") }

	func onCompleted(requestId: Int32) { received.append("completed \(requestId)") }

	func onError(requestId: Int32, code: String, message: String) { received.append("error \(requestId) \(code) \(message)") }
}

/// Monta a sessão e entrega bytes nos dois sentidos, como o delegate do `CBPeripheral` faria.
func exerciseMinimalHost() -> [String] {
	let transport = MinimalTransport()
	let logger = MinimalLogger()
	let session = WatchSession(transport: transport, logger: logger, store: MinimalStore())
	transport.events = session.events
	let listener = MinimalListener()
	session.setListener(listener: listener)
	session.pair(requestId: 1, address: "00000000-0000-0000-0000-000000000000", profileId: Profiles.shared.MORMAII_FIT_GPS.id)

	// didUpdateValueFor: o valor da characteristic já é Data.
	let notification = Data([0xAB, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01])
	session.events.onNotification(characteristic: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E", data: notification)
	// didUpdateValueFor de uma leitura: valor opcional.
	session.events.onReadCompleted(requestId: 7, data: Data([0x55]), status: GattStatus.shared.SUCCESS)
	session.events.onReadCompleted(requestId: 8, data: nil, status: GattStatus.shared.NOT_FOUND)

	// Data -> KotlinByteArray, para o resto da API que recebe bytes (ex.: FrameJsonl.line).
	let bytes: KotlinByteArray = ByteBridgeKt.toByteArray(notification)
	let line: String = FrameJsonl.shared.line(direction: FrameDirection.rx, atMs: 0, characteristic: "6e400003-b5a3-f393-e0a9-e50e24dcca9e", value: bytes)
	let roundTrip: Data = bytes.toData()
	precondition(roundTrip == notification)

	session.close()
	return logger.lines + listener.received + [line] + exerciseWholeSession() + describeProfiles() + describeConstants() +
		exerciseBridge() + exerciseCoreBridge()
}

/// `WatchClock` do host: o real usa `Date()` e `TimeZone.current`, como o `SystemWatchClock`.
final class MinimalClock: NSObject, WatchClock {
	func nowEpochMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

	func timeZoneId() -> String { TimeZone.current.identifier }
}

/// Todo método e propriedade pública da `WatchSession`, os dois construtores e os tipos de domínio que o host mapeia
/// para os eventos e retornos do JavaScript.
func exerciseWholeSession() -> [String] {
	var out: [String] = []
	let transport = MinimalTransport()
	let session = WatchSession(transport: transport, logger: NoopWatchLogger.shared, store: InMemorySampleStore(), clock: MinimalClock())
	transport.events = session.events
	session.setListener(listener: nil)
	let noRequest: Int32 = WatchSession.companion.NO_REQUEST_ID
	out.append("noRequestId \(noRequest)")
	out.append("state \(session.state.name) \(session.currentAddress ?? "-") \(session.currentProfileId ?? "-")")
	let profileId: String = Profiles.shared.MORMAII_FIT_GPS.id
	session.reconnect(requestId: 2, address: "00000000-0000-0000-0000-000000000000", profileId: profileId)
	session.readDeviceInfo(requestId: 3)
	session.readBattery(requestId: 4)
	session.setTime(requestId: 5)
	session.refreshSteps(requestId: 6)
	session.syncHistory(requestId: 7, kinds: [DataKind.steps, DataKind.sleep])
	session.syncHistory(requestId: 8, kinds: [])
	for day in session.getStepsDays(daysBefore: 3) {
		out.append(describe(day: day))
	}
	for sleep in session.getSleepSessions(daysBefore: 1) {
		out.append(describe(sleep: sleep))
	}
	session.confirmUploaded(kind: DataKind.steps, dayKeys: ["2026-10-28"])
	session.confirmUploaded(kind: DataKind.sleep, dayKeys: [])
	session.disconnect(requestId: 9, forget: false)
	session.disconnect(requestId: 10, forget: true)
	session.close()

	// O construtor com o relógio do sistema e os tipos que vêm prontos na biblioteca.
	let systemSession = WatchSession(transport: MinimalTransport(), logger: MinimalLogger(), store: MinimalStore(), clock: SystemWatchClock.shared)
	out.append("system clock \(SystemWatchClock.shared.nowEpochMillis()) \(SystemWatchClock.shared.timeZoneId())")
	systemSession.close()

	// Os eventos com `KotlinByteArray` continuam disponíveis ao lado das sobrecargas com `Data`.
	let events: TransportEvents = session.events
	let ack = KotlinByteArray(size: 2)
	ack.set(index: 0, value: Int8(bitPattern: 0xAB))
	ack.set(index: 1, value: 0x10)
	events.onBluetoothStateChanged(enabled: true)
	events.onConnected()
	events.onMtuChanged(requestId: 1, mtu: 23, status: GattStatus.shared.SUCCESS)
	events.onNotificationsEnabled(requestId: 2, status: GattStatus.shared.SUCCESS)
	events.onWriteCompleted(requestId: 3, status: GattStatus.shared.TIMEOUT)
	events.onNotification(characteristic: "6e400003-b5a3-f393-e0a9-e50e24dcca9e", value: ack)
	events.onReadCompleted(requestId: 4, value: ack, status: GattStatus.shared.SUCCESS)
	events.onReadCompleted(requestId: 5, value: nil, status: GattStatus.shared.NOT_CONNECTED)
	events.onServicesDiscovered(services: [], status: GattStatus.shared.NOT_FOUND)
	events.onDisconnected(status: 8, message: "link lost")
	out.append("ack size \(ack.size) first \(ack.get(index: 0))")

	// Domínio: o que o listener entrega e o que o `SampleStore` do host grava e relê.
	let info = DeviceInfo(profileId: profileId, address: "addr", bleName: "fitGPS", firmware: "0.2.1", bleVersion: nil)
	out.append("info \(info.profileId) \(info.address) \(info.bleName) \(info.firmware) \(info.bleVersion ?? "-")")
	// O Kotlin/Native não exporta valor padrão: o Swift passa `charging` sempre (no CRP, 2A19 acima de 100).
	let battery = Battery(percent: 85, charging: true)
	out.append("battery \(battery.percent) \(battery.charging)")
	let sample = StepsSample(atEpochMillis: 0, steps: 10, mode: 0, calories: 1, distanceMeters: 5.0)
	out.append("sample \(sample.atEpochMillis) \(sample.steps) \(sample.mode) \(sample.calories) \(sample.distanceMeters)")
	out.append(describe(day: StepsDay(dayKey: "2026-10-28", startOfDayIso: "2026-10-28T00:00:00-03:00", steps: 10, calories: 1, distanceMeters: 5.0)))
	let stage = SleepStage(type: SleepStageType.deep, startEpochMillis: 0, endEpochMillis: 60_000, startIso: "", endIso: "")
	out.append(describe(sleep: SleepSession(dayKey: "2026-10-28", startEpochMillis: 0, endEpochMillis: 60_000, startIso: "", endIso: "", stages: [stage], totalMinutes: 1, lightMinutes: 0, deepMinutes: 1, remMinutes: 0, awakeMinutes: 0)))
	let stageTypes: [SleepStageType] = [SleepStageType.awake, SleepStageType.light, SleepStageType.deep, SleepStageType.rem]
	out.append("stage types \(stageTypes.map { "\($0.name)=\($0.code)" }) of \(SleepStageType.entries.count)")
	let kinds: [DataKind] = DataKind.entries
	out.append("kinds \(kinds.map { $0.name })")
	let states: [ConnectionState] = [ConnectionState.disconnected, ConnectionState.connecting, ConnectionState.configuring, ConnectionState.handshaking, ConnectionState.ready, ConnectionState.syncing, ConnectionState.reconnecting, ConnectionState.bluetoothOff]
	out.append("states \(states.map { "\($0.name)/\($0.ordinal)" }) of \(ConnectionState.entries.count)")
	return out
}

func describe(day: StepsDay) -> String {
	"day \(day.dayKey) \(day.startOfDayIso) \(day.steps) \(day.calories) \(day.distanceMeters)"
}

func describe(sleep: SleepSession) -> String {
	let stages = sleep.stages.map { "\($0.type.name) \($0.startEpochMillis)-\($0.endEpochMillis) \($0.startIso)-\($0.endIso)" }
	return "sleep \(sleep.dayKey) \(sleep.startEpochMillis)-\(sleep.endEpochMillis) \(sleep.startIso)-\(sleep.endIso) " +
		"\(sleep.totalMinutes) \(sleep.lightMinutes) \(sleep.deepMinutes) \(sleep.remMinutes) \(sleep.awakeMinutes) \(stages)"
}

/// Perfis e identificação: o host escolhe o perfil pelo nome anunciado e lê os UUIDs e os quirks que tocam o BLE.
func describeProfiles() -> [String] {
	var out: [String] = []
	let identified: DeviceProfile? = DeviceRegistry.shared.identify(bleName: "fitGPS", advertisedServices: [])
	let byId: DeviceProfile? = DeviceRegistry.shared.byId(id: Profiles.shared.MORMAII_FIT_GPS.id)
	out.append("identified \(identified?.id ?? "-") \(byId?.slug ?? "-")")
	// Nome de scan com sufixo: o Condor Reebok é reconhecido pelo prefixo "P85" (o host mostra o appName no scan).
	let condor: DeviceProfile = Profiles.shared.CONDOR_REEBOK
	let byPrefix: DeviceProfile? = DeviceRegistry.shared.identify(bleName: "P85-1234", advertisedServices: [])
	let matches: Bool = DeviceRegistry.shared.matchesName(profile: condor, name: " P85 ")
	out.append("prefix \(byPrefix?.id ?? "-") \(matches)")
	for profile in Profiles.shared.all {
		let quirks: Quirks = profile.quirks
		let maxFragment: Int32? = quirks.maxFragment?.int32Value
		let prefixes: [String] = profile.bleNamePrefixes
		out.append("profile \(profile.id) \(profile.appName) \(profile.slug) \(profile.bleNames) \(prefixes) \(profile.family.name) " +
			"mtu \(quirks.requestMtu) fragment \(maxFragment.map { String($0) } ?? "-") connect \(quirks.connectTimeoutMs) " +
			"command \(quirks.commandTimeoutMs) retention \(quirks.historyRetentionDays) steps \(quirks.stepsAggregation.name)")
		// O que o host iOS lê para o BLE: a espera entre frames é do driver, o bridging muda as opções do connect.
		let gap: Int64 = quirks.commandGapMs
		let classicBond: Bool = quirks.classicBond
		let bridging: Bool = quirks.iosTransportBridging
		out.append("quirks \(profile.id) gap \(gap) classic \(classicBond) bridging \(bridging)")
		for candidate in profile.gattCandidates {
			let side: [NotifyChannel] = candidate.sideNotify
			out.append("gatt \(candidate.service) \(candidate.write) \(candidate.notify) side \(side.map { "\($0.service)/\($0.characteristic)" })")
		}
	}
	let candidates: [GattCandidate] = [Profiles.shared.SMA_NUS, Profiles.shared.SMA_ROUND, Profiles.shared.CRP_FEEA]
	let families: [Family] = [Family.sma, Family.ido, Family.crp]
	out.append("candidates \(candidates.map { $0.service }) families \(families.map { $0.name })")
	return out
}

/// Constantes que o host repassa ao JavaScript (códigos de erro) ou compara (status GATT, write type, nível de log).
func describeConstants() -> [String] {
	let codes = WatchErrorCodes.shared
	let errorCodes: [String] = [
		codes.BUSY, codes.CANCELLED, codes.CONNECT_TIMEOUT, codes.DISCONNECTED, codes.GATT_NOT_SUPPORTED, codes.HANDSHAKE_FAILED,
		codes.NOT_READY, codes.NO_DATA, codes.PAIRING_REMOVED, codes.PROTOCOL, codes.TIMEOUT, codes.TRANSPORT, codes.UNSUPPORTED_DEVICE,
	]
	let statuses: [Int32] = [
		GattStatus.shared.SUCCESS, GattStatus.shared.NOT_CONNECTED, GattStatus.shared.NOT_FOUND, GattStatus.shared.TIMEOUT,
		GattStatus.shared.PAIRING_REMOVED,
	]
	let levels: [LogLevel] = [LogLevel.debug, LogLevel.info, LogLevel.warn, LogLevel.error]
	let directions: [FrameDirection] = [FrameDirection.tx, FrameDirection.rx, FrameDirection.read]
	let writeTypes: [WriteType] = [WriteType.withResponse, WriteType.withoutResponse]
	let header: String = FrameJsonl.shared.header(device: "fitgps", firmware: nil, platform: "ios", mtu: 23, recordedAtIso: "2026-10-28T14:30:00-03:00", timeZoneId: "America/Sao_Paulo")
	NoopWatchLogger.shared.log(level: LogLevel.info, tag: "Host/check", message: "ok")
	let classicBondFailed: String = HostErrorCodes.shared.CLASSIC_BOND_FAILED
	return [
		"codes \(errorCodes) host \(classicBondFailed)",
		"statuses \(statuses)",
		"levels \(levels.map { $0.name }) directions \(directions.map { $0.name }) writes \(writeTypes.map { $0.name })",
		header,
	]
}

/// Contrato JS do Core (:bridge), como o host iOS vai usá-lo: o payload do evento vira `NSDictionary` com
/// `FoundationConverter` antes de ir para o `emitOnX:` do TurboModule, e um valor solto (o `isConnected`) sai de uma
/// lista de um elemento, lida aqui no Swift, para continuar `CFBoolean`.
func exerciseBridge() -> [String] {
	let converter = FoundationConverter.shared
	let mapper = EventMapper.shared
	let battery: [String: Any] = mapper.battery(battery: Battery(percent: 85, charging: false))
	let payload: [AnyHashable: Any] = converter.toDictionary(payload: battery)
	let state: [String: Any] = mapper.connectStateChanged(state: ConnectionState.ready, phase: nil)
	let error: [String: Any] = mapper.error(requestId: WatchSession.companion.NO_REQUEST_ID, code: HostErrorCodes.shared.BLUETOOTH_OFF, message: "Bluetooth is off", method: "scan")
	let logs: [Any] = converter.toArray(values: [mapper.log(ts: 0, level: LogLevel.info, tag: "Host/check", message: "ok", dir: nil, hex: nil)])
	let connected: Any = converter.toArray(values: [StateMapper.shared.isConnected(state: ConnectionState.syncing)])[0]
	precondition(connected as? Bool == true)
	let codes: [String] = [
		HostErrorCodes.shared.PERMISSION_DENIED, HostErrorCodes.shared.BLUETOOTH_OFF, HostErrorCodes.shared.INVALID_ARGUMENT,
		HostErrorCodes.shared.IO_ERROR, HostErrorCodes.shared.CLASSIC_BOND_FAILED,
	]
	let states: [Int32] = [
		StateMapper.shared.DISCONNECTED, StateMapper.shared.CONNECTING, StateMapper.shared.CONNECTED, StateMapper.shared.BLUETOOTH_OFF,
		StateMapper.shared.toDeviceConnectState(state: ConnectionState.reconnecting),
	]
	let saved: SavedDataMapper.Result = SavedDataMapper.shared.dayKeys(registeredAts: ["2026-10-28T03:00:00.000Z", "garbage"])
	let kind: DataKind? = SavedDataMapper.shared.kindForCategory(category: SavedDataMapper.shared.CATEGORY_PEDOMETER)
	let day: String? = SavedDataMapper.shared.dayKey(registeredAt: "2026-10-28")
	let sleep = SleepSession(dayKey: "2026-10-28", startEpochMillis: 0, endEpochMillis: 60_000, startIso: "", endIso: "", stages: [], totalMinutes: 1, lightMinutes: 0, deepMinutes: 1, remMinutes: 0, awakeMinutes: 0)
	return [
		"bridge \(payload.count) \(state.count) \(error.count) \(logs.count) \(connected)",
		"codes \(codes) states \(states)",
		"saved \(saved.dayKeys) \(saved.rejected) \(kind?.name ?? "-") \(day ?? "-")",
		describeSleepDates(mapper: mapper, session: sleep),
	]
}

/// `startOfDayIso`, `sleepDay` e `historySleep` têm `@Throws`: um `dayKey` inválido chega ao Swift como `Error` (o
/// `NSError` do Kotlin/Native) e não encerra o processo. Cada `try` confere um dos três: sem o `@Throws`, o `swiftc`
/// avisa que não há chamada que lança, e o `-warnings-as-errors` derruba o portão.
func describeSleepDates(mapper: EventMapper, session: SleepSession) -> String {
	do {
		let start: String = try mapper.startOfDayIso(dayKey: session.dayKey)
		let day: [String: Any] = try mapper.sleepDay(session: session)
		let history: [String: Any] = try mapper.historySleep(sessions: [session])
		return "sleep \(start) \(day.count) \(history.count)"
	} catch {
		return "invalid dayKey: \(error)"
	}
}

/// `CoreHost` como o `CoreGraph.swift` do host iOS vai implementá-lo: permissões e estado do `CBCentralManager`,
/// scan, tarefa de segundo plano no sync, pareamento salvo em `UserDefaults`, cabeçalho da gravação, o log em memória e
/// o vínculo Bluetooth clássico, que o iOS não deixa o app criar.
final class MinimalCoreHost: NSObject, CoreHost {
	private(set) var effects: [String] = []

	/// O `CoreGraph.swift` liga isto ao `CoreBridge.onClassicBondResult(address:result:message:)` depois de criar o bridge.
	var onClassicBondResult: ((String, ClassicBondResult, String) -> Void)?
	private var pairing: CorePairing?
	private var entries: [CoreLogEntry] = []

	func missingScanPermission() -> String? { nil }

	func missingConnectPermission() -> String? { nil }

	func isBluetoothEnabled() -> Bool { true }

	/// No iOS o endereço é o UUID do `CBPeripheral`, não um MAC.
	func isValidAddress(address: String) -> Bool { UUID(uuidString: address) != nil }

	func startScan() { effects.append("startScan") }

	func stopScan() { effects.append("stopScan") }

	func startSyncWork() { effects.append("startSyncWork") }

	func stopSyncWork() { effects.append("stopSyncWork") }

	func loadPairing() -> CorePairing? { pairing }

	func savePairing(pairing: CorePairing) {
		self.pairing = pairing
		effects.append("savePairing \(pairing.address) \(pairing.profileId) \(pairing.name)")
	}

	func clearPairing() { pairing = nil }

	func setRecordingDeviceName(name: String) { effects.append("device \(name)") }

	func setRecordingFirmware(firmware: String?) { effects.append("firmware \(firmware ?? "nil")") }

	func logEntries(limit: Int32) -> [CoreLogEntry] { Array(entries.prefix(Int(max(0, limit)))) }

	/// `throws` do lado Swift: o erro chega ao Kotlin como exceção e vira `IO_ERROR` no `exportLogs` do JS.
	func exportLogs() throws -> String {
		guard let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
			throw NSError(domain: "CoreNext", code: 1, userInfo: [NSLocalizedDescriptionKey: "No documents directory"])
		}
		return directory.appendingPathComponent("frames.jsonl").path
	}

	func clearLogs() { entries = [] }

	func setLogLevel(level: LogLevel) { effects.append("level \(level.name)") }

	/// O app iOS não enxerga os vínculos do sistema.
	func hasClassicBond(address: String) -> Bool { false }

	/// O iOS não deixa o app criar o vínculo BR/EDR: devolve `.unsupported`, de forma assíncrona, com a dica de Ajustes.
	func requestClassicBond(address: String) {
		effects.append("requestClassicBond \(address)")
		let hint = "iOS apps cannot create a classic Bluetooth bond; pair calls in Settings > Bluetooth"
		DispatchQueue.main.async { [weak self] in
			self?.onClassicBondResult?(address, ClassicBondResult.unsupported, hint)
		}
	}

	/// Só log: quem esquece o relógio nos Ajustes é o usuário.
	func removeClassicBond(address: String) { effects.append("removeClassicBond \(address)") }
}

/// `CoreCompletion` sobre o par `resolve`/`reject` do React Native. O valor passa inteiro pelo `FoundationConverter`
/// numa lista de um elemento, para o `Bool` do `isConnected` chegar ao JS como `true`/`false` (`CFBoolean`) e os mapas e
/// listas como `NSDictionary`/`NSArray`.
final class MinimalCompletion: NSObject, CoreCompletion {
	private let resolveBlock: (Any?) -> Void
	private let rejectBlock: (String, String, Error?) -> Void

	init(resolve: @escaping (Any?) -> Void, reject: @escaping (String, String, Error?) -> Void) {
		resolveBlock = resolve
		rejectBlock = reject
	}

	func resolve(value: Any?) {
		guard let value else {
			resolveBlock(nil)
			return
		}
		resolveBlock(FoundationConverter.shared.toArray(values: [value])[0])
	}

	func reject(code: String, message: String) {
		rejectBlock(code, message, nil)
	}
}

/// `CoreEventSink`: o TurboModule converte o payload e chama o `emitOnX:` do nome.
final class MinimalEventSink: NSObject, CoreEventSink {
	private(set) var emitted: [String] = []
	var ready = true

	func canEmit() -> Bool { ready }

	func emit(name: String, payload: [String: Any]) {
		let dictionary: [AnyHashable: Any] = FoundationConverter.shared.toDictionary(payload: payload)
		emitted.append("\(name) \(dictionary.count)")
	}
}

/// Todo método público do `CoreBridge`, como o host iOS vai chamá-los: os métodos JS a partir do TurboModule, os
/// ganchos do processo a partir do `CoreGraph.swift` (scan, Bluetooth, log, primeiro plano).
func exerciseCoreBridge() -> [String] {
	let transport = MinimalTransport()
	let logger = MinimalLogger()
	let session = WatchSession(transport: transport, logger: logger, store: MinimalStore())
	transport.events = session.events
	let host = MinimalCoreHost()
	let bridge = CoreBridge(session: session, logger: logger, host: host)
	host.onClassicBondResult = { [weak bridge] address, result, message in
		bridge?.onClassicBondResult(address: address, result: result, message: message)
	}
	let sink = MinimalEventSink()
	var settled: [String] = []
	func completion(_ method: String) -> CoreCompletion {
		MinimalCompletion(
			resolve: { value in settled.append("\(method) resolved \(String(describing: value))") },
			reject: { code, message, _ in settled.append("\(method) rejected \(code) \(message)") }
		)
	}
	let deviceId = "00000000-0000-0000-0000-000000000000"
	let profile: DeviceProfile = Profiles.shared.MORMAII_FIT_GPS

	bridge.attach(sink: sink)
	bridge.initialize(completion: completion("initialize"))
	bridge.doInitSMASDK(completion: completion("initSMASDK"))
	bridge.scan()
	bridge.stopScan()
	bridge.pairDevice(deviceId: deviceId, name: profile.appName, completion: completion("pairDevice"))
	bridge.startPairingAttempt(deviceId: deviceId, name: profile.appName, completion: completion("startPairingAttempt"))
	bridge.disconnectCurrentDevice(completion: completion("disconnectCurrentDevice"))
	bridge.forgetDevice(completion: completion("forgetDevice"))
	bridge.isConnected(completion: completion("isConnected"))
	bridge.getLastPairedDevice(completion: completion("getLastPairedDevice"))
	bridge.getDeviceInfo(completion: completion("getDeviceInfo"))
	bridge.getFirmwareInfo(completion: completion("getFirmwareInfo"))
	bridge.getBatteryStatus(completion: completion("getBatteryStatus"))
	bridge.setTime(completion: completion("setTime"))
	bridge.refreshSteps(completion: completion("refreshSteps"))
	bridge.syncDataHistory(completion: completion("syncDataHistory"))
	bridge.getHistoryPedometer(daysBefore: 3, completion: completion("getHistoryPedometer"))
	bridge.getHistorySleep(completion: completion("getHistorySleep"))
	// O NSArray do TurboModule: strings puras (wrapper do Core atual) ou mapas do spec.
	let saved: [Any] = ["2026-10-28T03:00:00.000Z", ["registered_at": "2026-10-27", "category": 0] as [String: Any]]
	bridge.deletedSavedData(filteredArrayData: saved, category: Double(SavedDataMapper.shared.CATEGORY_PEDOMETER))
	bridge.getLogs(limit: 50, completion: completion("getLogs"))
	bridge.exportLogs(completion: completion("exportLogs"))
	bridge.clearLogs()
	bridge.setLogLevel(level: 1)

	// Ganchos do processo.
	bridge.onScannedDevice(profile: profile, address: deviceId, rssi: -61, bleName: profile.bleNames[0])
	bridge.onScanFailed(errorCode: -1, message: "Bluetooth LE scanner unavailable")
	bridge.onLogEntry(entry: CoreLogEntry(ts: 0, level: LogLevel.info, tag: "Host/check", message: "ok", dir: FrameDirection.tx, hex: "AB"))
	bridge.onLogEntry(entry: CoreLogEntry(ts: 0, level: LogLevel.debug, tag: "Host/check", message: "ok", dir: nil, hex: nil))
	bridge.onBluetoothTurnedOff()
	bridge.onBluetoothTurnedOn()
	bridge.onHostResume()
	// Resultado do vínculo clássico, de qualquer thread: os três casos que o host Android e o iOS devolvem.
	let bondResults: [ClassicBondResult] = [ClassicBondResult.bonded, ClassicBondResult.failed, ClassicBondResult.unsupported]
	for result in bondResults {
		bridge.onClassicBondResult(address: deviceId, result: result, message: "check \(result.name)")
	}
	bridge.invalidate(sink: sink)
	session.close()
	return settled + sink.emitted + host.effects
}
