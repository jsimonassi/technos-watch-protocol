package br.com.grupotechnos.watch.protocol.domain

/**
 * Tipos de histórico que a sessão sabe sincronizar.
 */
public enum class DataKind {
	/** Passos, com calorias e distância estimadas. */
	STEPS,

	/** Sono (exploratório no SMA: o relógio fitGPS nunca teve sono validado em produção). */
	SLEEP,
}

/**
 * Identificação do relógio conectado.
 *
 * @property profileId id do [br.com.grupotechnos.watch.protocol.profile.DeviceProfile] (ex.: `"fitgps"`).
 * @property address endereço usado no `Transport.connect` (MAC no Android, UUID do periférico no iOS).
 * @property bleName nome de advertising do modelo (ex.: `"Mormaii_fitGPS"`). É o primeiro de `DeviceProfile.bleNames`:
 * o `pair` recebe só o endereço, e o perfil só reconhece nomes exatos, então é o mesmo nome que o scan viu.
 * @property firmware versão do firmware no formato `"x.y.z"` (no SMA, a resposta `02/0B`).
 * @property bleVersion versão da pilha BLE do relógio no formato `"x.y.z"`, ou `null` quando o protocolo não informa
 * (no SMA, quando a resposta `02/0B` tem menos de 6 bytes).
 */
public data class DeviceInfo(
	val profileId: String,
	val address: String,
	val bleName: String,
	val firmware: String,
	val bleVersion: String?,
)

/**
 * Nível de bateria do relógio.
 *
 * @property percent percentual de 0 a 100.
 */
public data class Battery(
	val percent: Int,
)

/**
 * Uma amostra de passos como o relógio entrega, já normalizada para um instante real.
 *
 * No SMA cada amostra representa uma hora cheia (o último registro da hora prevalece).
 * Como somar ou combinar as amostras de um dia é definido por
 * [br.com.grupotechnos.watch.protocol.profile.StepsAggregation].
 *
 * @property atEpochMillis início do intervalo, em milissegundos desde 1970-01-01T00:00Z.
 * @property steps passos do intervalo.
 * @property mode código de modo do registro no protocolo da família (no SMA, 0 = registro normal).
 * @property calories calorias estimadas para [steps] (kcal).
 * @property distanceMeters distância estimada para [steps] (metros).
 */
public data class StepsSample(
	val atEpochMillis: Long,
	val steps: Int,
	val mode: Int,
	val calories: Int,
	val distanceMeters: Double,
)

/**
 * Total de passos de um dia no fuso do telefone.
 *
 * @property dayKey dia local no formato `yyyy-MM-dd`; é a chave usada para confirmar envio ao backend.
 * @property startOfDayIso início do dia local em ISO-8601 com offset (ex.: `"2026-10-28T00:00:00-03:00"`).
 * @property steps total de passos do dia.
 * @property calories calorias estimadas do dia (kcal).
 * @property distanceMeters distância estimada do dia (metros).
 */
public data class StepsDay(
	val dayKey: String,
	val startOfDayIso: String,
	val steps: Int,
	val calories: Int,
	val distanceMeters: Double,
)

/**
 * Estágio de sono. Os códigos são os mesmos de `TechnosSleepType` do Core atual,
 * para o mapeamento para o JavaScript ser direto.
 *
 * @property code código numérico usado no evento `onHistorySleep` (`typeItem`).
 */
public enum class SleepStageType(public val code: Int) {
	/** Acordado (código 1). */
	AWAKE(1),

	/** Sono leve (código 2). */
	LIGHT(2),

	/** Sono profundo (código 3). */
	DEEP(3),

	/** Sono REM (código 4). O SMA não tem esse estágio, então o driver SMA nunca o produz. */
	REM(4),
}

/**
 * Um trecho contínuo de um único estágio de sono.
 *
 * @property type estágio do trecho.
 * @property startEpochMillis início, em milissegundos desde 1970-01-01T00:00Z.
 * @property endEpochMillis fim (exclusivo), em milissegundos desde 1970-01-01T00:00Z; sempre maior que [startEpochMillis].
 * @property startIso [startEpochMillis] em ISO-8601 com o offset do telefone.
 * @property endIso [endEpochMillis] em ISO-8601 com o offset do telefone.
 */
public data class SleepStage(
	val type: SleepStageType,
	val startEpochMillis: Long,
	val endEpochMillis: Long,
	val startIso: String,
	val endIso: String,
)

/**
 * Uma noite (ou cochilo) de sono já analisada.
 *
 * Os totais em minutos seguem a regra da família, que não é necessariamente a soma dos [stages]: no SMA,
 * [awakeMinutes] é `totalMinutes - deepMinutes - lightMinutes` (`docs/sma-dados.md`, seção 5).
 *
 * @property dayKey dia local do fim da sessão (`yyyy-MM-dd`), ou seja, o dia em que a pessoa acordou.
 * @property startEpochMillis início da sessão (primeiro ponto), em milissegundos desde 1970-01-01T00:00Z.
 * @property endEpochMillis fim da sessão (último ponto, o despertar), em milissegundos desde 1970-01-01T00:00Z.
 * @property startIso [startEpochMillis] em ISO-8601 com o offset do telefone.
 * @property endIso [endEpochMillis] em ISO-8601 com o offset do telefone.
 * @property stages estágios em ordem cronológica, sem sobreposição.
 * @property totalMinutes duração da sessão em minutos, incluindo o tempo acordado.
 * @property lightMinutes minutos de sono leve.
 * @property deepMinutes minutos de sono profundo.
 * @property remMinutes minutos de sono REM (sempre 0 no SMA).
 * @property awakeMinutes minutos acordado dentro da sessão.
 */
public data class SleepSession(
	val dayKey: String,
	val startEpochMillis: Long,
	val endEpochMillis: Long,
	val startIso: String,
	val endIso: String,
	val stages: List<SleepStage>,
	val totalMinutes: Int,
	val lightMinutes: Int,
	val deepMinutes: Int,
	val remMinutes: Int,
	val awakeMinutes: Int,
)
