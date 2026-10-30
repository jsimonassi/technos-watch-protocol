package br.com.grupotechnos.watch.bridge

/**
 * Códigos de erro que nascem no host, antes de a sessão ser chamada.
 *
 * Completam os de `WatchErrorCodes` (:protocol), que a sessão produz. Todos aparecem no `reject(code, message)` da
 * Promise e no evento `onError`; no JavaScript, em `errorCodes` (`src/constants.ts` do `react-native-technos-core-next`)
 * e na tabela de erros do `docs/api-js.md` de lá. Mudou a lista, mude as três.
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
}
