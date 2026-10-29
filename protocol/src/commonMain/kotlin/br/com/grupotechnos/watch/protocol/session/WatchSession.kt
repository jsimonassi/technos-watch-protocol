package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.StepsDay
import br.com.grupotechnos.watch.protocol.log.WatchLogger
import br.com.grupotechnos.watch.protocol.transport.Transport
import br.com.grupotechnos.watch.protocol.transport.TransportEvents
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Fachada única que o host usa para falar com um relógio.
 *
 * Uma sessão cuida de um relógio por vez: conexão, handshake, fila de comandos, sincronização e reconexão.
 * Não conhece a plataforma (o host implementa [Transport]) nem o protocolo da família (o driver interno cuida disso).
 *
 * Uso típico no host:
 * ```
 * val transport = AndroidGattTransport(context)   // implementação do host
 * val session = WatchSession(transport, logger, store)
 * transport.attach(session.events)                // o host passa a chamar session.events.onX(...) nos callbacks BLE
 * session.setListener(listener)
 * session.pair(requestId = 1, address = "AA:BB:CC:DD:EE:FF", profileId = "fitgps")
 * ```
 *
 * Todos os métodos retornam imediatamente e podem ser chamados de qualquer thread. O resultado chega pelo
 * [WatchSessionListener], na thread da sessão. Comandos que exigem conexão (`readX`, `setTime`, `refreshSteps`,
 * `syncHistory`) são aceitos em [ConnectionState.READY] e [ConnectionState.SYNCING] e executados um de cada vez,
 * em ordem; em outros estados terminam com `onError(NOT_READY)`.
 */
public class WatchSession internal constructor(
	transport: Transport,
	logger: WatchLogger,
	store: SampleStore,
	clock: WatchClock,
	context: CoroutineContext,
) {
	/** Sessão com relógio e fuso injetados (útil para testes do host). */
	public constructor(transport: Transport, logger: WatchLogger, store: SampleStore, clock: WatchClock) :
		this(transport, logger, store, clock, Dispatchers.Default.limitedParallelism(1))

	/** Sessão com o relógio e o fuso do sistema. */
	public constructor(transport: Transport, logger: WatchLogger, store: SampleStore) :
		this(transport, logger, store, SystemWatchClock)

	private val engine = SessionEngine(transport, logger, store, clock, context)

	/** Entrada dos eventos BLE do host. */
	public val events: TransportEvents
		get() = engine.events

	/** Estado atual. Pode ser lido de qualquer thread. */
	public val state: ConnectionState
		get() = engine.state

	/** Endereço do relógio da sessão atual, ou `null` se nunca pareou nesta sessão. */
	public val currentAddress: String?
		get() = engine.currentAddress

	/** Perfil do relógio da sessão atual, ou `null`. */
	public val currentProfileId: String?
		get() = engine.currentProfileId

	/** Define (ou remove, com `null`) o listener. Só há um listener por sessão. */
	public fun setListener(listener: WatchSessionListener?) {
		engine.setListener(listener)
	}

	/**
	 * Primeiro pareamento: conexão direta, handshake completo (no SMA: BIND e LOGIN).
	 *
	 * Termina com `onCompleted(requestId)` quando chega em [ConnectionState.READY], ou com `onError`
	 * (`UNSUPPORTED_DEVICE`, `CONNECT_TIMEOUT`, `GATT_NOT_SUPPORTED`, `HANDSHAKE_FAILED`, `DISCONNECTED`, `TRANSPORT`
	 * quando o Bluetooth está desligado ou uma operação GATT da configuração falha, `CANCELLED` quando um `pair`,
	 * `reconnect`, `disconnect` ou `close` posterior o substitui). Uma conexão ou um pareamento em andamento é
	 * derrubado antes (não há `BUSY`). O BIND pode esperar confirmação na tela do relógio até `Quirks.bindTimeoutMs`;
	 * durante o handshake a sessão não reconecta.
	 */
	public fun pair(requestId: Int, address: String, profileId: String) {
		engine.pair(requestId, address, profileId)
	}

	/**
	 * Reconexão a um relógio já pareado: conexão pendente sem timeout e handshake curto (no SMA: só LOGIN).
	 *
	 * Termina com `onCompleted(requestId)` quando chega em [ConnectionState.READY]; pode demorar indefinidamente
	 * se o relógio estiver longe. O host não deve prender a UI esperando por isso. Se a sessão já estiver pronta com o
	 * mesmo relógio, termina na hora; se já estiver reconectando a ele, o pedido novo substitui o anterior (que recebe
	 * `CANCELLED`). Com o Bluetooth desligado termina com `onError(TRANSPORT)`, mas a sessão guarda o alvo e reconecta
	 * sozinha quando o Bluetooth voltar.
	 *
	 * Se o relógio **recusar** o LOGIN (no SMA, com os dois formatos de payload), a sessão desiste: vai para
	 * [ConnectionState.DISCONNECTED], termina com `onError(HANDSHAKE_FAILED)` e não refaz o BIND sozinha, porque um
	 * BIND em segundo plano pode pedir confirmação na tela do relógio. Só um `pair` refaz o vínculo
	 * (`docs/decisoes.md`, ADR 11). Falta de resposta e queda continuam tentando, com espera crescente.
	 */
	public fun reconnect(requestId: Int, address: String, profileId: String) {
		engine.reconnect(requestId, address, profileId)
	}

	/**
	 * Desconecta e para de reconectar.
	 *
	 * Comandos em andamento ou na fila terminam com `onError(CANCELLED)`. Termina com `onCompleted(requestId)`.
	 *
	 * @param forget `true` desfaz o vínculo no relógio (no SMA: UNBIND, se conectado, esperando até
	 * `Quirks.commandTimeoutMs`; falha do UNBIND não impede o resto) e apaga o `SampleStore`.
	 */
	public fun disconnect(requestId: Int, forget: Boolean) {
		engine.disconnect(requestId, forget)
	}

	/** Lê firmware e identificação. Termina com `onDeviceInfo` ou `onError`. */
	public fun readDeviceInfo(requestId: Int) {
		engine.readDeviceInfo(requestId)
	}

	/** Lê a bateria. Termina com `onBattery` ou `onError`. */
	public fun readBattery(requestId: Int) {
		engine.readBattery(requestId)
	}

	/** Acerta o relógio com a hora e o fuso do telefone. Termina com `onCompleted` ou `onError`. */
	public fun setTime(requestId: Int) {
		engine.setTime(requestId)
	}

	/** Lê os passos recentes, grava no store e entrega o total de hoje. Termina com `onLiveSteps` ou `onError`. */
	public fun refreshSteps(requestId: Int) {
		engine.refreshSteps(requestId)
	}

	/**
	 * Lê o histórico do relógio e grava no [SampleStore].
	 *
	 * Emite `onSyncStarted`, `onSyncProgress` e termina com `onSyncFinished` ou `onError`.
	 * A falta de registros de um tipo (ex.: sono) não é erro: só fica registrada no log. Erro na leitura de sono
	 * também só vai para o log (sono é exploratório); erro na leitura de passos encerra com `onError`.
	 *
	 * @param kinds tipos a ler, sempre na ordem passos → sono; lista vazia lê todos.
	 */
	public fun syncHistory(requestId: Int, kinds: List<DataKind>) {
		engine.syncHistory(requestId, kinds)
	}

	/**
	 * Totais por dia a partir do [SampleStore], do mais antigo para o mais novo, até hoje, sem os dias já confirmados.
	 * Só aparecem dias com amostras (como no Core atual, não há dia zerado). Não fala com o relógio. Chamada síncrona
	 * e segura em qualquer thread: lê um instantâneo imutável que a sessão troca a cada gravação.
	 *
	 * @param daysBefore janela que o host pediu. Como nos dois Cores atuais, **não** corta os dias mais velhos: todo
	 * dia não confirmado que ainda está na retenção volta, para nenhum dia ficar sem envio (`docs/decisoes.md`,
	 * ADR 15). "Hoje" é o dia de `WatchClock.nowEpochMillis` no fuso `WatchClock.timeZoneId`.
	 */
	public fun getStepsDays(daysBefore: Int): List<StepsDay> = engine.getStepsDays(daysBefore)

	/**
	 * Sessões de sono do [SampleStore] cujo dia (`SleepSession.dayKey`) vai até hoje, sem os dias já confirmados, em
	 * ordem de início. Não fala com o relógio; síncrona e segura em qualquer thread.
	 *
	 * @param daysBefore como em [getStepsDays]: não corta os dias mais velhos que ainda não foram confirmados.
	 */
	public fun getSleepSessions(daysBefore: Int): List<SleepSession> = engine.getSleepSessions(daysBefore)

	/**
	 * Marca dias como já enviados ao backend; eles deixam de aparecer em [getStepsDays] e [getSleepSessions].
	 * Vale na hora, para qualquer thread (a gravação no [SampleStore] acontece depois, na thread da sessão).
	 *
	 * @param dayKeys dias no formato `yyyy-MM-dd`; outros formatos são ignorados com aviso no log.
	 */
	public fun confirmUploaded(kind: DataKind, dayKeys: List<String>) {
		engine.confirmUploaded(kind, dayKeys)
	}

	/**
	 * Encerra a sessão: desconecta, cancela tudo e libera a thread. A instância não pode ser reutilizada.
	 * Pedidos pendentes terminam com `onError(CANCELLED)`; pedidos feitos depois também, na thread de quem chamou.
	 */
	public fun close() {
		engine.close()
	}

	/** Constantes da sessão. */
	public companion object {
		/** `requestId` dos eventos que o relógio provoca sem um comando do host. */
		public const val NO_REQUEST_ID: Int = -1
	}
}
