package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.profile.GattCandidate
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.transport.DiscoveredCharacteristic
import br.com.grupotechnos.watch.protocol.transport.DiscoveredService
import br.com.grupotechnos.watch.protocol.transport.GattStatus
import br.com.grupotechnos.watch.protocol.transport.Transport
import br.com.grupotechnos.watch.protocol.transport.TransportEvents
import br.com.grupotechnos.watch.protocol.transport.WriteType

/**
 * [Transport] de teste que responde como um host bem comportado, de forma síncrona: cada chamada já enfileira o
 * evento de conclusão em [events] (a sessão só processa depois, na thread dela, como num host real).
 *
 * Dois modos de relógio, escolhidos por quem monta o teste:
 * - **Roteiro**: [onWrite] chama um [SmaWatchSimulator] (ver [scripted]), que responde por `cmd/key`.
 * - **Replay**: [onWrite] e [onNotificationsEnabled] são ligados a um [ReplayDriver], que confere cada TX contra uma
 *   gravação e emite os RX gravados.
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

	/** Chamadas de `connect` (endereço, autoReconnect). */
	val connects = mutableListOf<Pair<String, Boolean>>()

	/** `true` = abre o link na hora; `false` = deixa a conexão pendente até [completeConnect]. */
	var connectBehavior: (address: String, autoReconnect: Boolean) -> Boolean = { _, _ -> true }

	var discoveryStatus: Int = GattStatus.SUCCESS

	/** Responde à descoberta? `false` simula um host que nunca responde. */
	var answerDiscovery: Boolean = true

	var notificationsStatus: Int = GattStatus.SUCCESS

	/** MTU devolvido; `null` simula um host que não responde ao pedido. */
	var mtuReply: Int? = 247

	/** Status de cada escrita. */
	var writeStatus: (ByteArray) -> Int = { GattStatus.SUCCESS }

	/** O "relógio": recebe cada pacote escrito com sucesso, depois do `onWriteCompleted`. */
	var onWrite: ((ByteArray) -> Unit)? = null

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
		events.onNotificationsEnabled(requestId, notificationsStatus)
		if (notificationsStatus == GattStatus.SUCCESS) onNotificationsEnabled?.invoke()
	}

	override fun requestMtu(mtu: Int, requestId: Int) {
		calls += "requestMtu($mtu)"
		mtuReply?.let { events.onMtuChanged(requestId, it, GattStatus.SUCCESS) }
	}

	override fun write(service: String, characteristic: String, value: ByteArray, type: WriteType, requestId: Int) {
		calls += "write"
		val copy = value.copyOf()
		writes += copy
		val status = writeStatus(copy)
		events.onWriteCompleted(requestId, status)
		if (status == GattStatus.SUCCESS) onWrite?.invoke(copy)
	}

	override fun read(service: String, characteristic: String, requestId: Int) {
		calls += "read($characteristic)"
		events.onReadCompleted(requestId, null, GattStatus.NOT_FOUND)
	}

	/** Abre uma conexão que estava pendente. */
	fun completeConnect() {
		pending = false
		linkOpen = true
		events.onConnected()
	}

	/** Uma notificação do relógio. */
	fun notify(value: ByteArray) {
		deliverNotification?.invoke(notifyCharacteristic, value) ?: events.onNotification(notifyCharacteristic, value)
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
		/** Serviço descoberto com as characteristics de um candidato. */
		fun serviceOf(candidate: GattCandidate): DiscoveredService =
			DiscoveredService(
				uuid = candidate.service,
				characteristics = listOf(
					DiscoveredCharacteristic(candidate.write, canRead = false, canWrite = true, canWriteWithoutResponse = true, canNotify = false),
					DiscoveredCharacteristic(candidate.notify, canRead = false, canWrite = false, canWriteWithoutResponse = false, canNotify = true),
				),
			)

		/** Transporte com um [SmaWatchSimulator] que responde por roteiro. */
		fun scripted(watch: (FakeTransport) -> SmaWatchSimulator = { transport -> SmaWatchSimulator { transport.notify(it) } }): Pair<FakeTransport, SmaWatchSimulator> {
			val transport = FakeTransport()
			val simulator = watch(transport)
			transport.onWrite = { simulator.onPhoneWrite(it) }
			return transport to simulator
		}
	}
}
