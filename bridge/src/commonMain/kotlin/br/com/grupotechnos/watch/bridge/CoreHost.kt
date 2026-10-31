package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.log.LogLevel

/**
 * O que o [CoreBridge] pede à plataforma: tudo o que depende do sistema operacional e não é a sessão com o relógio.
 *
 * Um por processo, como o [CoreBridge]. No Android é o `AndroidCoreHost` do `react-native-technos-core-next` (montado
 * pelo `CoreGraph`), sobre `BlePermissions`, `BleScanner`, `SyncForegroundService`, `PairingPrefs`, `FrameRecorder` e
 * `RingLogger`; no iOS será o `CoreGraph.swift` (tabela por plataforma em `docs/arquitetura.md`, seção 13).
 *
 * **Threads.** As consultas (permissões, Bluetooth, endereço, pareamento salvo, entradas do log) são chamadas na thread
 * de quem chamou o [CoreBridge]: a dos módulos nativos do React Native nos métodos JS, a da sessão nos callbacks do
 * relógio, a principal ou a do Bluetooth nos ganchos do processo. Os efeitos ([savePairing], [clearPairing],
 * [startSyncWork], [stopSyncWork], gravação, [requestClassicBond], [removeClassicBond]) vêm na maior parte da thread da
 * sessão. Tudo pode coincidir: a
 * implementação tem de ser thread-safe e não deve bloquear por muito tempo. Nenhum método deve lançar, salvo
 * [exportLogs] (e uma falha de leitura do pareamento salvo durante o `getHistorySleep`, que vira `IO_ERROR`).
 */
public interface CoreHost {
	/**
	 * Nome da permissão de scan que falta, para a mensagem `"Missing <nome>"` do `onError`, ou `null` se ela foi
	 * concedida. Android: `android.permission.BLUETOOTH_SCAN` (API 31+) ou `android.permission.ACCESS_FINE_LOCATION`
	 * (API 30-). O Core não pede permissão: o JS pede, com a UI do app; o nativo só confere.
	 */
	public fun missingScanPermission(): String?

	/**
	 * Nome da permissão de conexão que falta, para a mensagem `"Missing <nome>"`, ou `null` se ela foi concedida (ou não
	 * existe na plataforma). Android: `"BLUETOOTH_CONNECT"` na API 31+, sempre `null` na API 30-. Também decide se a
	 * reconexão automática pode rodar.
	 */
	public fun missingConnectPermission(): String?

	/** `true` se o Bluetooth do telefone está ligado (e existe). */
	public fun isBluetoothEnabled(): Boolean

	/**
	 * `true` se [address] é um endereço que o transporte aceita: um MAC (`BluetoothAdapter.checkBluetoothAddress`, letras
	 * maiúsculas) no Android, o UUID de um `CBPeripheral` no iOS. Um endereço recusado vira `INVALID_ARGUMENT`.
	 */
	public fun isValidAddress(address: String): Boolean

	/**
	 * Começa (ou recomeça) um scan dos relógios suportados. Cada relógio achado volta por [CoreBridge.onScannedDevice];
	 * uma falha, por [CoreBridge.onScanFailed]. O [CoreBridge] já conferiu permissão e Bluetooth.
	 */
	public fun startScan()

	/** Para o scan, se houver um. Pode ser chamado sem scan em andamento. */
	public fun stopScan()

	/**
	 * Uma sincronização de histórico começou: o host mantém o processo vivo até [stopSyncWork] (no Android, o
	 * `SyncForegroundService`; no iOS, uma tarefa de segundo plano). Não deve lançar: uma recusa do sistema só vai para o
	 * log, e o sync continua.
	 */
	public fun startSyncWork()

	/**
	 * A sincronização acabou (sucesso, erro ou queda do link). Chamado uma vez por [startSyncWork], na thread da sessão.
	 */
	public fun stopSyncWork()

	/** O relógio salvo, ou `null` se não há (ou se o salvo está incompleto). */
	public fun loadPairing(): CorePairing?

	/** Grava [pairing] como o último relógio pareado, substituindo o anterior. */
	public fun savePairing(pairing: CorePairing)

	/** Apaga o relógio salvo. */
	public fun clearPairing()

	/**
	 * Nome do relógio no cabeçalho da gravação de frames (`FrameJsonl.header`, campo `device`). O [CoreBridge] chama
	 * quando a tentativa de um relógio diferente do anterior chega ao handshake (sessão em `HANDSHAKING`, a primeira
	 * etapa que grava pacote do relógio) e no `onDeviceInfo`, nunca no pedido de conexão.
	 */
	public fun setRecordingDeviceName(name: String)

	/**
	 * Firmware do relógio no cabeçalho da gravação de frames (`FrameJsonl.header`, campo `firmware`). `null` quando o
	 * relógio do cabeçalho mudou e o firmware do novo ainda não foi lido: o do anterior não pode ficar no cabeçalho.
	 */
	public fun setRecordingFirmware(firmware: String?)

	/**
	 * As últimas [limit] entradas do log em memória, **da mais nova para a mais antiga**; [limit] fora de
	 * `0..tamanho` é cortado para esse intervalo (o JS pode mandar qualquer número).
	 */
	public fun logEntries(limit: Int): List<CoreLogEntry>

	/**
	 * Grava o log e a gravação de frames em arquivo e devolve o caminho absoluto do `.jsonl` de frames (formato de
	 * fixture do :protocol). Uma falha vira `IO_ERROR` no `exportLogs` do JS, com a mensagem da exceção. No Swift o método
	 * é `throws`; o erro chega ao Kotlin como exceção, com a descrição do `NSError` como mensagem.
	 */
	@Throws(Exception::class)
	public fun exportLogs(): String

	/** Esvazia o log em memória e começa uma gravação de frames nova. */
	public fun clearLogs()

	/** A partir de agora, entradas abaixo de [level] são descartadas pelo log do host (não vão nem para o `onLog`). */
	public fun setLogLevel(level: LogLevel)

	/**
	 * `true` se o sistema já tem o vínculo Bluetooth clássico (BR/EDR) com o relógio de [address]. O [CoreBridge]
	 * pergunta no `READY` de um perfil com `Quirks.classicBond`, na thread da sessão, antes de pedir o vínculo. Android:
	 * `BluetoothDevice.getBondState() == BOND_BONDED` (sem permissão, `false`). iOS: sempre `false` (o app não enxerga
	 * os vínculos do sistema). Não deve lançar.
	 */
	public fun hasClassicBond(address: String): Boolean

	/**
	 * Pede ao sistema o vínculo Bluetooth clássico com o relógio de [address]. Assíncrono: volta na hora, e o resultado
	 * chega depois por [CoreBridge.onClassicBondResult], uma vez por pedido, de qualquer thread (pode ser de dentro desta
	 * chamada, quando o vínculo já existe). Chamado na thread da sessão, no `READY` de um perfil com `Quirks.classicBond`,
	 * quando [hasClassicBond] respondeu `false` e não há outro pedido em andamento.
	 *
	 * Android: `createBond(TRANSPORT_BREDR)` por reflexão, como o Core de produção (`startBtBonding` em
	 * `core@v4.3.57:android/src/main/java/com/crp/bluetooth/connectionmanager/CRPBleConnectHelper.java`), com uma nova
	 * tentativa automática; o diálogo de consentimento é do sistema. O host não abre HFP nem A2DP: o Android abre os
	 * perfis sozinho logo depois do vínculo (HCI de 2026-10-31, 15:43:18). iOS: devolve [ClassicBondResult.UNSUPPORTED]
	 * com a dica de parear em Ajustes > Bluetooth. Não deve lançar.
	 */
	public fun requestClassicBond(address: String)

	/**
	 * Remove, no melhor esforço, o vínculo Bluetooth clássico com o relógio de [address]. Chamado na thread da sessão no
	 * fim do `forgetDevice` de um perfil com `Quirks.classicBond`: depois de a sessão mandar ao relógio o pedido de
	 * desvincular e antes de o pareamento salvo ser apagado de novo. Android: `removeBond()` por reflexão, com o
	 * resultado só no log. iOS: só um log com a dica de esquecer o relógio em Ajustes > Bluetooth. Não deve lançar.
	 */
	public fun removeClassicBond(address: String)
}
