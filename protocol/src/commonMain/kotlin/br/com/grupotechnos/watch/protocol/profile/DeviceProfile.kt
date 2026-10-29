package br.com.grupotechnos.watch.protocol.profile

/**
 * Família de protocolo. Cada família tem um driver interno próprio.
 */
public enum class Family {
	/** Relógios com firmware SMA (bestmafen). Na Mormaii: fitGPS. */
	SMA,
}

/**
 * Um conjunto serviço + characteristics que o driver sabe usar.
 *
 * Um perfil lista candidatos em ordem de prioridade; a sessão usa o primeiro cujo serviço
 * e characteristics aparecem na descoberta. Todos os UUIDs em 128 bits e minúsculas.
 *
 * @property service serviço GATT.
 * @property write characteristic onde a sessão escreve os frames.
 * @property notify characteristic de onde vêm as notificações do relógio.
 */
public data class GattCandidate(
	val service: String,
	val write: String,
	val notify: String,
)

/**
 * Formato do payload de 32 bytes do BIND/LOGIN do SMA.
 */
public enum class BindPayloadKind {
	/** `FF` seguido de 31 zeros, como a SDK Android (`SmaManager.sendBindCmd`). */
	ANDROID_FF,

	/** userId uint32 big-endian seguido de 28 zeros, como a SDK iOS (`SmaBLE bindUserWithUserID:`). */
	USER_ID,
}

/**
 * Como somar as amostras de passos de um dia.
 */
public enum class StepsAggregation {
	/**
	 * Cada amostra é o total de uma hora; o total do dia é a soma das horas.
	 * É o que o Core atual faz em produção (`SMAService.getHistoryPedometer`).
	 */
	HOURLY_SUM,

	/** Cada amostra é o acumulado do dia; o total do dia é o maior valor do dia. */
	DAY_CUMULATIVE,
}

/**
 * Ajustes de comportamento por modelo. São dados, não código: o mesmo driver serve modelos com quirks diferentes.
 *
 * @property requestMtu MTU ATT pedido depois de habilitar notificações.
 * @property maxFragment tamanho máximo de cada escrita; `null` usa `MTU - 3`. Nunca abaixo de 20.
 * @property connectTimeoutMs tempo máximo para abrir o link no primeiro pareamento (a reconexão não tem timeout).
 * @property commandTimeoutMs tempo máximo de espera por ACK e por resposta de cada comando. A sessão usa o mesmo
 * valor para esperar a habilitação das notificações e o UNBIND do `disconnect(forget = true)`.
 * @property fragmentGapTimeoutMs tempo máximo entre duas notificações do mesmo frame.
 * @property bindTimeoutMs tempo máximo de espera pela resposta do BIND (pode exigir confirmação na tela do relógio).
 * @property postLoginDelayMs espera depois do LOGIN antes dos comandos automáticos (acerto de hora).
 * @property bindPayload formato do payload do BIND/LOGIN.
 * @property bindUserId userId usado quando [bindPayload] é [BindPayloadKind.USER_ID].
 * @property stepsAggregation como somar as amostras de passos de um dia.
 * @property historyRetentionDays quantos dias de amostras a sessão mantém no `SampleStore`.
 */
public data class Quirks(
	val requestMtu: Int,
	val maxFragment: Int?,
	val connectTimeoutMs: Long,
	val commandTimeoutMs: Long,
	val fragmentGapTimeoutMs: Long,
	val bindTimeoutMs: Long,
	val postLoginDelayMs: Long,
	val bindPayload: BindPayloadKind,
	val bindUserId: Int,
	val stepsAggregation: StepsAggregation,
	val historyRetentionDays: Int,
)

/**
 * Tudo o que o sistema sabe sobre um modelo de relógio.
 *
 * @property id identificador estável usado pelo host e pelo JavaScript (ex.: `"fitgps"`).
 * @property bleNames nomes de advertising que identificam o modelo, comparados de forma exata.
 * @property appName nome que o app Mormaii espera em `onScannedDevice` (ex.: `"fitGPS"`).
 * @property slug identificador do modelo no backend (`SMART_WATCH_SLUG` no app).
 * @property family família de protocolo; decide qual driver interno a sessão usa.
 * @property gattCandidates serviços que o driver sabe usar, em ordem de prioridade.
 * @property quirks ajustes de comportamento do modelo (timeouts, MTU, payload do BIND, agregação, retenção).
 */
public data class DeviceProfile(
	val id: String,
	val bleNames: List<String>,
	val appName: String,
	val slug: String,
	val family: Family,
	val gattCandidates: List<GattCandidate>,
	val quirks: Quirks,
)
