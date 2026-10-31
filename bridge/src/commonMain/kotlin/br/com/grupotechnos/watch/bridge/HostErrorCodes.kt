package br.com.grupotechnos.watch.bridge

/**
 * Códigos de erro que nascem no host: antes de a sessão ser chamada, ou num pedido do [CoreBridge] à plataforma que não
 * é da sessão (o vínculo Bluetooth clássico).
 *
 * Completam os de `WatchErrorCodes` (:protocol), que a sessão produz. Todos aparecem no evento `onError` e, quando
 * nascem num método JS, também no `reject(code, message)` da Promise ([CLASSIC_BOND_FAILED] não tem Promise); no
 * JavaScript, em `errorCodes` (`src/constants.ts` do `react-native-technos-core-next`) e na tabela de erros do
 * `docs/api-js.md` de lá. Mudou a lista, mude as três.
 */
public object HostErrorCodes {
	/** Falta permissão de Bluetooth (no Android, `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` na API 31+ ou localização na 30-). O JS pede; o nativo só confere. */
	public const val PERMISSION_DENIED: String = "PERMISSION_DENIED"

	/** O Bluetooth do telefone está desligado (ou o aparelho não tem BLE). */
	public const val BLUETOOTH_OFF: String = "BLUETOOTH_OFF"

	/** Argumento inválido vindo do JS (ex.: endereço do relógio mal formado). */
	public const val INVALID_ARGUMENT: String = "INVALID_ARGUMENT"

	/** Falha de arquivo (exportar logs, ler o histórico local). */
	public const val IO_ERROR: String = "IO_ERROR"

	/**
	 * O vínculo Bluetooth clássico (BR/EDR) que o relógio usa para chamadas e música não foi criado: o usuário recusou o
	 * diálogo do sistema ou as tentativas do host acabaram ([ClassicBondResult.FAILED]). Sai só como `onError`, com
	 * `requestId` `-1` e `method` `"classicBond"`, porque o pedido nasce no `READY`, e não num método JS; a sessão BLE
	 * continua pronta. O [CoreBridge] não pede de novo até o próximo `pairDevice`.
	 */
	public const val CLASSIC_BOND_FAILED: String = "CLASSIC_BOND_FAILED"
}
