package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.Battery
import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.DeviceInfo
import br.com.grupotechnos.watch.protocol.domain.StepsDay

/**
 * Saída da sessão para o host.
 *
 * Todos os métodos são chamados na thread da sessão (nunca na thread principal nem na do Bluetooth);
 * o host faz o despacho para onde precisar (no Core React Native, a emissão de eventos para o JavaScript).
 *
 * Regra do `requestId`: cada comando enviado com um `requestId` termina com exatamente um callback terminal
 * com o mesmo id. Terminais: [onDeviceInfo], [onBattery], [onLiveSteps], [onSyncFinished], [onCompleted]
 * e [onError]. [onSyncStarted] e [onSyncProgress] não são terminais. Eventos que o relógio
 * provoca sozinho chegam com [WatchSession.NO_REQUEST_ID]; por isso o host não deve usar esse valor como id.
 * A única exceção à thread da sessão: depois de `WatchSession.close`, um pedido novo termina com `onError(CANCELLED)`
 * na thread de quem chamou. Uma exceção lançada pelo listener é registrada no log e não afeta a sessão.
 */
public interface WatchSessionListener {
	/**
	 * O estado da conexão mudou.
	 *
	 * @param phase subetapa do handshake para log e UI (no SMA: `"BIND"` ou `"LOGIN"`); `null` fora do handshake.
	 */
	public fun onStateChanged(state: ConnectionState, phase: String?)

	/** Resposta de [WatchSession.readDeviceInfo]. Terminal. */
	public fun onDeviceInfo(requestId: Int, info: DeviceInfo)

	/** Resposta de [WatchSession.readBattery]. Terminal. */
	public fun onBattery(requestId: Int, battery: Battery)

	/**
	 * Total de passos de hoje, depois de [WatchSession.refreshSteps] (terminal) ou quando o relógio
	 * avisa que os dados mudaram ([WatchSession.NO_REQUEST_ID]).
	 */
	public fun onLiveSteps(requestId: Int, today: StepsDay)

	/** Início de [WatchSession.syncHistory]. */
	public fun onSyncStarted(requestId: Int)

	/** Progresso de [WatchSession.syncHistory], de 0 a 100. */
	public fun onSyncProgress(requestId: Int, percent: Int)

	/** Fim com sucesso de [WatchSession.syncHistory]. Terminal. */
	public fun onSyncFinished(requestId: Int)

	/** Fim com sucesso de comandos sem resultado próprio: `pair`, `reconnect`, `disconnect`, `setTime`. Terminal. */
	public fun onCompleted(requestId: Int)

	/**
	 * Falha. Terminal quando [requestId] é de um comando.
	 *
	 * @param code um dos valores de [br.com.grupotechnos.watch.protocol.domain.WatchErrorCodes].
	 */
	public fun onError(requestId: Int, code: String, message: String)
}
