package br.com.grupotechnos.watch.bridge

/**
 * Destino dos eventos JS: a instância atual do TurboModule.
 *
 * É separado do [CoreHost] porque as vidas são diferentes: o [CoreBridge] e o [CoreHost] vivem o processo inteiro (o
 * link BLE e um sync não caem num reload do JS), e o módulo é recriado a cada reload. O módulo se liga com
 * [CoreBridge.attach] na primeira chamada de um método JS e se desliga em [CoreBridge.invalidate]; enquanto não há
 * módulo ligado, ou enquanto [canEmit] é `false`, o [CoreBridge] guarda os eventos na fila dele ([PendingEvents]) e os
 * entrega em ordem quando pode.
 *
 * Nomes de evento possíveis em [emit] (os `EventEmitter` de `src/specs/NativeCoreNext.ts` do Repo 2): `onScannedDevice`,
 * `onConnectStateChanged`, `onDeviceInfo`, `onFirmwareInfo`, `onBatteryStatus`, `onPedometer`, `onHistoryPedometer`,
 * `onHistorySleep`, `onSyncStarted`, `onSyncProgress`, `onSyncFinished`, `onError` e `onLog`.
 *
 * Chamado de qualquer thread (a da sessão, a dos módulos, a principal, a do Bluetooth), sempre dentro da trava
 * reentrante da fila: a implementação não deve bloquear (no Android, o `emitOnX` gerado só agenda a chamada na thread
 * JS) e pode voltar a chamar o [CoreBridge] na mesma thread (um log emitido de dentro de [emit] não trava).
 */
public interface CoreEventSink {
	/**
	 * `true` quando o módulo pode emitir: no Android, instância React ativa e callback de eventos instalado; no iOS, o
	 * `_eventEmitterCallback` do `NativeCoreNextSpecBase` já existe.
	 */
	public fun canEmit(): Boolean

	/**
	 * Emite o evento [name] com [payload] (formato de [EventMapper]). No Android: `WritableConverter.toMap(payload)` e o
	 * `emitOnX` do nome; no iOS: `FoundationConverter.shared.toDictionary(payload:)` e o `emitOnX:`.
	 *
	 * Pode lançar uma `RuntimeException` (no Android, um nome desconhecido ou um módulo que morreu no meio): o evento
	 * volta para a fila. O Swift não lança exceções Kotlin; ali, um evento que não puder sair é perdido.
	 */
	public fun emit(name: String, payload: Map<String, Any?>)
}
