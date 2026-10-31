package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.crp.CrpGatt
import br.com.grupotechnos.watch.protocol.profile.GattCandidate
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.transport.DiscoveredCharacteristic
import br.com.grupotechnos.watch.protocol.transport.DiscoveredService
import br.com.grupotechnos.watch.protocol.transport.GattStatus
import br.com.grupotechnos.watch.protocol.transport.Transport
import br.com.grupotechnos.watch.protocol.transport.TransportEvents
import br.com.grupotechnos.watch.protocol.transport.WriteType

/**
 * Resposta de uma leitura GATT do [FakeTransport] ([FakeTransport.onRead]).
 *
 * @property value bytes lidos (`null` numa falha).
 * @property status status GATT entregue em `onReadCompleted`.
 */
internal class ReadReply(val value: ByteArray?, val status: Int) {
	companion object {
		/** Leitura bem-sucedida de [value]. */
		fun ok(value: ByteArray): ReadReply = ReadReply(value.copyOf(), GattStatus.SUCCESS)

		/** Characteristic inexistente (o padrão de um transporte sem [FakeTransport.onRead]). */
		val NOT_FOUND: ReadReply = ReadReply(null, GattStatus.NOT_FOUND)
	}
}

/**
 * [Transport] de teste que responde como um host bem comportado, de forma síncrona: cada chamada já enfileira o
 * evento de conclusão em [events] (a sessão só processa depois, na thread dela, como num host real).
 *
 * Dois modos de relógio, escolhidos por quem monta o teste:
 * - **Roteiro**: [onWrite] chama um [SmaWatchSimulator] (ver [scripted]), um [IdoWatchSimulator] (ver [scriptedIdo]) ou
 *   um [CrpWatchSimulator] (ver [scriptedCrp], que também liga [onRead]), que responde por `cmd/key` ou `(cmd, sub)`.
 * - **Replay**: [onWrite], [onRead] e [onNotificationsEnabled] são ligados a um [ReplayDriver], que confere cada TX e cada
 *   leitura contra uma gravação e emite os RX gravados, cada um na characteristic gravada.
 *
 * Controle do link pelo teste: [connectBehavior] decide se `connect` abre o link na hora; [completeConnect],
 * [dropLink] e [setBluetooth] simulam eventos do lado do host. É o contrato que a sessão assume
 * (`docs/arquitetura.md`, seção 5):
 * - `disconnect` responde um `onDisconnected` só quando havia link aberto ou conexão pendente (ADR 12);
 * - Bluetooth desligado segue o contrato B (ADR 18): só `onBluetoothStateChanged(false)`, nenhum `onDisconnected`
 *   pelo que caiu, e `disconnect` fica mudo até o próximo `connect`. O host do iOS (`CoreBluetooth`) se comporta assim.
 */
internal class FakeTransport(
	/** Serviços devolvidos na descoberta; o padrão é só o NUS, como o fitGPS no iOS (`SMAService.m:1056-1061`). */
	var services: List<DiscoveredService> = listOf(serviceOf(Profiles.SMA_NUS)),
) : Transport {
	lateinit var events: TransportEvents

	/** Registro de todas as chamadas, na ordem (ex.: `"connect(AA,false)"`, `"write"`). */
	val calls = mutableListOf<String>()

	/** Pacotes escritos na characteristic de escrita, na ordem. */
	val writes = mutableListOf<ByteArray>()

	/** Tipo de cada escrita de [writes], na mesma ordem. */
	val writeTypes = mutableListOf<WriteType>()

	/** Chamadas de `connect` (endereço, autoReconnect). */
	val connects = mutableListOf<Pair<String, Boolean>>()

	/** `true` = abre o link na hora; `false` = deixa a conexão pendente até [completeConnect]. */
	var connectBehavior: (address: String, autoReconnect: Boolean) -> Boolean = { _, _ -> true }

	var discoveryStatus: Int = GattStatus.SUCCESS

	/** Responde à descoberta? `false` simula um host que nunca responde. */
	var answerDiscovery: Boolean = true

	var notificationsStatus: Int = GattStatus.SUCCESS

	/**
	 * Status da CCCD de cada characteristic; `null` simula um host que não responde. O padrão devolve
	 * [notificationsStatus] para todas.
	 */
	var notificationsStatusOf: (characteristic: String) -> Int? = { notificationsStatus }

	/** Leituras GATT: `null` (o padrão) responde `NOT_FOUND` a todas, como um relógio sem a characteristic. */
	var onRead: ((service: String, characteristic: String) -> ReadReply)? = null

	/** Chamado depois de entregar cada `onReadCompleted` (o replay emite aí os RX gravados depois da leitura). */
	var afterRead: (() -> Unit)? = null

	/** MTU devolvido; `null` simula um host que não responde ao pedido. */
	var mtuReply: Int? = 247

	/** Status de cada escrita. */
	var writeStatus: (ByteArray) -> Int = { GattStatus.SUCCESS }

	/** O "relógio": recebe cada pacote escrito com sucesso, depois do `onWriteCompleted` (ver [replyBeforeWriteCompleted]). */
	var onWrite: ((ByteArray) -> Unit)? = null

	/**
	 * `true` entrega o pacote a [onWrite] antes do `onWriteCompleted`, então a resposta do relógio chega à sessão antes da
	 * confirmação da escrita. Acontece no Android com escrita sem resposta: no log da SDK iDO, o segundo `02 01` saiu às
	 * 22:08:22.013 (`20261029.log` linha 96), a resposta chegou às .068 (linha 120) e o callback da escrita, às .069
	 * (linha 121).
	 */
	var replyBeforeWriteCompleted: Boolean = false

	/** Chamado depois de confirmar as notificações (o replay usa para emitir RX que vêm antes do primeiro TX). */
	var onNotificationsEnabled: (() -> Unit)? = null

	/** Characteristic usada em [notify]. */
	var notifyCharacteristic: String = Profiles.SMA_NUS.notify

	/**
	 * Como [notify] entrega a notificação à sessão; `null` chama `events.onNotification(characteristic, value)`. O teste
	 * do iOS troca pela sobrecarga com `NSData`, para o mesmo replay passar pela ponte de bytes.
	 */
	var deliverNotification: ((characteristic: String, value: ByteArray) -> Unit)? = null

	var linkOpen: Boolean = false
		private set

	var pending: Boolean = false
		private set

	override fun connect(address: String, autoReconnect: Boolean) {
		calls += "connect($address,$autoReconnect)"
		connects += address to autoReconnect
		linkOpen = false
		pending = true
		if (connectBehavior(address, autoReconnect)) completeConnect()
	}

	override fun disconnect() {
		calls += "disconnect"
		if (linkOpen || pending) {
			linkOpen = false
			pending = false
			events.onDisconnected(0, "requested")
		}
	}

	override fun discoverServices() {
		calls += "discoverServices"
		if (answerDiscovery) events.onServicesDiscovered(if (discoveryStatus == GattStatus.SUCCESS) services else emptyList(), discoveryStatus)
	}

	override fun enableNotifications(service: String, characteristic: String, requestId: Int) {
		calls += "enableNotifications($characteristic)"
		val status = notificationsStatusOf(characteristic) ?: return
		events.onNotificationsEnabled(requestId, status)
		if (status == GattStatus.SUCCESS) onNotificationsEnabled?.invoke()
	}

	override fun requestMtu(mtu: Int, requestId: Int) {
		calls += "requestMtu($mtu)"
		mtuReply?.let { events.onMtuChanged(requestId, it, GattStatus.SUCCESS) }
	}

	override fun write(service: String, characteristic: String, value: ByteArray, type: WriteType, requestId: Int) {
		calls += "write"
		val copy = value.copyOf()
		writes += copy
		writeTypes += type
		val status = writeStatus(copy)
		if (replyBeforeWriteCompleted && status == GattStatus.SUCCESS) {
			onWrite?.invoke(copy)
			events.onWriteCompleted(requestId, status)
			return
		}
		events.onWriteCompleted(requestId, status)
		if (status == GattStatus.SUCCESS) onWrite?.invoke(copy)
	}

	override fun read(service: String, characteristic: String, requestId: Int) {
		calls += "read($characteristic)"
		val reply = onRead?.invoke(service, characteristic) ?: ReadReply.NOT_FOUND
		events.onReadCompleted(requestId, reply.value?.copyOf(), reply.status)
		afterRead?.invoke()
	}

	/** Abre uma conexão que estava pendente. */
	fun completeConnect() {
		pending = false
		linkOpen = true
		events.onConnected()
	}

	/** Uma notificação do relógio em [notifyCharacteristic]. */
	fun notify(value: ByteArray) {
		notifyOn(notifyCharacteristic, value)
	}

	/** Uma notificação do relógio em [characteristic] (no CRP, `fee1`, `2a19` ou `2a37` além do `fee3`). */
	fun notifyOn(characteristic: String, value: ByteArray) {
		deliverNotification?.invoke(characteristic, value) ?: events.onNotification(characteristic, value)
	}

	/** O link cai sozinho (relógio longe, desligado). */
	fun dropLink(status: Int = 8, message: String = "link lost") {
		if (!linkOpen && !pending) return
		linkOpen = false
		pending = false
		events.onDisconnected(status, message)
	}

	/**
	 * O Bluetooth do telefone foi ligado ou desligado. Desligar derruba o link e a conexão pendente sem nenhum
	 * `onDisconnected`, nem antes nem depois (contrato B, `docs/decisoes.md`, ADR 18); como nada fica aberto, um
	 * `disconnect` seguinte também não responde, até o próximo `connect`. Para simular o stack que reporta a queda antes
	 * do aviso de Bluetooth desligado, o teste chama [dropLink] antes deste método.
	 */
	fun setBluetooth(enabled: Boolean) {
		if (!enabled) {
			linkOpen = false
			pending = false
		}
		events.onBluetoothStateChanged(enabled)
	}

	/** Quantas vezes `disconnect` foi chamado. */
	fun disconnectCount(): Int = calls.count { it == "disconnect" }

	companion object {
		/**
		 * Serviços descobertos de um candidato: o principal ([serviceOf]) e os das `sideNotify`, cada characteristic lateral
		 * no seu serviço; uma lateral do mesmo serviço do candidato entra nele.
		 */
		fun servicesOf(candidate: GattCandidate): List<DiscoveredService> {
			val main = serviceOf(candidate)
			val byService = LinkedHashMap<String, MutableList<DiscoveredCharacteristic>>()
			byService[main.uuid] = main.characteristics.toMutableList()
			for (side in candidate.sideNotify) {
				val list = byService.getOrPut(side.service) { mutableListOf() }
				if (list.none { it.uuid == side.characteristic }) {
					list += DiscoveredCharacteristic(side.characteristic, canRead = true, canWrite = false, canWriteWithoutResponse = false, canNotify = true)
				}
			}
			return byService.map { (uuid, characteristics) -> DiscoveredService(uuid, characteristics) }
		}

		/** Serviço descoberto com as characteristics de um candidato (escrita, notificação e as `extraNotify`). */
		fun serviceOf(candidate: GattCandidate): DiscoveredService =
			DiscoveredService(
				uuid = candidate.service,
				characteristics = listOf(
					DiscoveredCharacteristic(candidate.write, canRead = false, canWrite = true, canWriteWithoutResponse = true, canNotify = false),
					DiscoveredCharacteristic(candidate.notify, canRead = false, canWrite = false, canWriteWithoutResponse = false, canNotify = true),
				) + candidate.extraNotify.map {
					DiscoveredCharacteristic(it, canRead = false, canWrite = false, canWriteWithoutResponse = false, canNotify = true)
				},
			)

		/**
		 * Transporte de um relógio iDO: descoberta com o serviço `0AF0` ([Profiles.IDO_MAIN]) e notificações em `0AF7`.
		 * Sem [IdoWatchSimulator]: quem monta o teste liga [onWrite] (ver [scriptedIdo]) ou um replay.
		 */
		fun ido(): FakeTransport =
			FakeTransport(services = listOf(serviceOf(Profiles.IDO_MAIN))).apply { notifyCharacteristic = Profiles.IDO_MAIN.notify }

		/** Transporte iDO com um [IdoWatchSimulator] que responde como o Life Ultra GPS do log de produção. */
		fun scriptedIdo(
			schedule: (delayMs: Long, action: () -> Unit) -> Unit = { _, action -> action() },
		): Pair<FakeTransport, IdoWatchSimulator> {
			val transport = ido()
			val simulator = IdoWatchSimulator(schedule = schedule) { characteristic, value -> transport.events.onNotification(characteristic, value) }
			transport.onWrite = { simulator.onPhoneWrite(it) }
			return transport to simulator
		}

		/**
		 * Transporte de um relógio CRP como o P85 da captura: `FEEA` (`fee1` leitura e notificação, `fee2` escrita sem
		 * resposta, `fee3` notificação), `180D`/`2a37`, `180F`/`2a19` e `180A` com `2a28` e `2a29`; notificações em `fee3`.
		 * Sem [CrpWatchSimulator]: quem monta o teste liga [onWrite] e [onRead] (ver [scriptedCrp]) ou um replay.
		 */
		fun crp(): FakeTransport {
			val feea = Profiles.CRP_FEEA
			fun characteristic(uuid: String, read: Boolean = false, writeNoResponse: Boolean = false, notify: Boolean = false) =
				DiscoveredCharacteristic(uuid, canRead = read, canWrite = false, canWriteWithoutResponse = writeNoResponse, canNotify = notify)
			val services = listOf(
				DiscoveredService(
					CrpGatt.HEART_RATE_SERVICE,
					listOf(characteristic(CrpGatt.HEART_RATE, notify = true)),
				),
				DiscoveredService(CrpGatt.BATTERY_SERVICE, listOf(characteristic(CrpGatt.BATTERY_LEVEL, read = true, notify = true))),
				DiscoveredService(
					CrpGatt.DEVICE_INFO_SERVICE,
					listOf(characteristic(CrpGatt.FIRMWARE_REVISION, read = true), characteristic(CrpGatt.PROTOCOL_VERSION, read = true)),
				),
				DiscoveredService(
					feea.service,
					listOf(
						characteristic(CrpGatt.STEPS_TODAY, read = true, notify = true),
						characteristic(feea.write, writeNoResponse = true),
						characteristic(feea.notify, notify = true),
					),
				),
			)
			return FakeTransport(services = services).apply { notifyCharacteristic = feea.notify }
		}

		/** Transporte CRP com um [CrpWatchSimulator] que responde como o P85 da captura do app de produção. */
		fun scriptedCrp(
			schedule: (delayMs: Long, action: () -> Unit) -> Unit = { _, action -> action() },
		): Pair<FakeTransport, CrpWatchSimulator> {
			val transport = crp()
			val simulator = CrpWatchSimulator(schedule = schedule) { characteristic, value -> transport.notifyOn(characteristic, value) }
			transport.onWrite = { simulator.onPhoneWrite(it) }
			transport.onRead = { _, characteristic -> simulator.onPhoneRead(characteristic) }
			return transport to simulator
		}

		/** Transporte com um [SmaWatchSimulator] que responde por roteiro. */
		fun scripted(watch: (FakeTransport) -> SmaWatchSimulator = { transport -> SmaWatchSimulator { transport.notify(it) } }): Pair<FakeTransport, SmaWatchSimulator> {
			val transport = FakeTransport()
			val simulator = watch(transport)
			transport.onWrite = { simulator.onPhoneWrite(it) }
			return transport to simulator
		}
	}
}
