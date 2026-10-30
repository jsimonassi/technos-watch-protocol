package br.com.grupotechnos.watch.bridge

/**
 * O que liquida uma Promise do JavaScript, sem depender do React Native: o host embrulha a `Promise` do Android
 * (`com.facebook.react.bridge.Promise`) ou o par `resolve`/`reject` do iOS (`RCTPromiseResolveBlock`,
 * `RCTPromiseRejectBlock`) numa implementação desta interface e a passa ao [CoreBridge].
 *
 * O [CoreBridge] chama exatamente um dos dois métodos, uma vez, para cada completion que recebe; quando há evento, ele
 * já saiu antes da liquidação (quem espera a Promise encontra o dado emitido). Pode ser chamada de qualquer thread: a
 * dos módulos nativos (respostas imediatas e rejeições antecipadas) ou a da sessão (fim de um comando ao relógio).
 *
 * Valores de [resolve], os mesmos que o host Android antigo entregava ao `Promise.resolve`:
 * - `null`: comandos sem valor (a maioria);
 * - [Boolean]: `isConnected`;
 * - [String]: o caminho do `exportLogs`;
 * - `Map<String, Any?>`: o `getLastPairedDevice`, no formato de [EventMapper.lastPairedDevice];
 * - `List<Map<String, Any?>>`: o `getLogs`, cada item no formato de [EventMapper.log].
 *
 * Os mapas e listas têm os tipos de valor de sempre (`Int`, `Double`, `String`, `Boolean`, `null`, aninhados): no
 * Android, o `PromiseCompletion` do Repo 2 os passa pelo `WritableConverter`; no iOS, o valor inteiro passa por
 * `FoundationConverter.shared.toArray(values: [value])[0]`, que também transforma o `Boolean` (que chega ao Swift como
 * `KotlinBoolean`) num `CFBoolean` de verdade (`ios/README.md` deste módulo).
 */
public interface CoreCompletion {
	/** Resolve a Promise com [value] (tipos acima). */
	public fun resolve(value: Any?)

	/**
	 * Rejeita a Promise.
	 *
	 * @param code um código de `WatchErrorCodes` (:protocol) ou de [HostErrorCodes].
	 * @param message descrição em inglês, a mesma que vai no `onError`.
	 */
	public fun reject(code: String, message: String)
}
