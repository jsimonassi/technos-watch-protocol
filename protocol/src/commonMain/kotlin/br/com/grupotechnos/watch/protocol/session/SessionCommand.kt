package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.StepsSample

/**
 * Um comando na fila da conexão. A fila executa um de cada vez, em ordem de chegada, porque o `DeviceDriver` não
 * aceita chamadas em paralelo (e o relógio SMA também não: as SDKs mantêm uma fila serial, `SmaMessenger`).
 *
 * **Regra do callback terminal.** Todo comando termina com exatamente um callback terminal (`docs/arquitetura.md`,
 * seção "Erros"). O guarda é [markFinished]: quem for emitir um terminal (sucesso, erro, cancelamento por queda do
 * link ou `disconnect`) chama antes e só emite se receber `true`. Assim, uma corrotina cancelada que ainda passaria
 * por um `catch` não duplica o erro que a derrubada do link já entregou.
 *
 * @property requestId id do host, ou [WatchSession.NO_REQUEST_ID] nos comandos internos.
 * @property internal comando que a própria sessão cria (acerto de hora pós-login, releitura depois de `02/70`):
 * erros só vão para o log, nunca para `onError`.
 */
internal sealed class SessionCommand(val requestId: Int, val internal: Boolean) {
	private var finished = false

	/** Nome curto para log (`"readBattery"`). */
	abstract val name: String

	/** Retorna `true` uma única vez: na primeira chamada. */
	fun markFinished(): Boolean {
		if (finished) return false
		finished = true
		return true
	}

	/** `true` depois de [markFinished]. */
	val isFinished: Boolean
		get() = finished

	/** `readDeviceInfo` → `onDeviceInfo`. */
	class ReadDeviceInfo(requestId: Int) : SessionCommand(requestId, internal = false) {
		override val name: String = "readDeviceInfo"
	}

	/** `readBattery` → `onBattery`. */
	class ReadBattery(requestId: Int) : SessionCommand(requestId, internal = false) {
		override val name: String = "readBattery"
	}

	/** `setTime` → `onCompleted`; interno logo depois do login. */
	class SetTime(requestId: Int, internal: Boolean) : SessionCommand(requestId, internal) {
		override val name: String = "setTime"
	}

	/** Pede ao relógio os avisos de dados novos (interno, pós-login). */
	class EnableNotices : SessionCommand(WatchSession.NO_REQUEST_ID, internal = true) {
		override val name: String = "enableDataChangeNotices"
	}

	/** `refreshSteps` → `onLiveSteps`; interno depois de `DriverNotice.DataChanged`, com [WatchSession.NO_REQUEST_ID]. */
	class RefreshSteps(requestId: Int, internal: Boolean) : SessionCommand(requestId, internal) {
		override val name: String = "refreshSteps"
	}

	/**
	 * Mescla no histórico as amostras que o driver montou com os passos ao vivo (`DriverNotice.StepsUpdated`; no CRP, a
	 * notificação de FEE1) e emite `onLiveSteps` com [WatchSession.NO_REQUEST_ID]. Interno: passa pela fila para manter a
	 * ordem com um sync em andamento.
	 */
	class ApplySteps(val samples: List<StepsSample>) : SessionCommand(WatchSession.NO_REQUEST_ID, internal = true) {
		override val name: String = "applyLiveSteps"
	}

	/** `syncHistory` → `onSyncStarted`, `onSyncProgress`, `onSyncFinished`. */
	class SyncHistory(requestId: Int, val kinds: List<DataKind>) : SessionCommand(requestId, internal = false) {
		override val name: String = "syncHistory"
	}
}
