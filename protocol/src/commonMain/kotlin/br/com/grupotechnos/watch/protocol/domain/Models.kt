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
 * o `pair` recebe só o endereço. Nos perfis sem prefixo é o mesmo nome que o scan viu; num perfil com
 * `DeviceProfile.bleNamePrefixes` (Condor Reebok) é o nome real do modelo (`"P85"`, a characteristic 2A00 do relógio),
 * mesmo que o scan tenha visto uma variação com sufixo.
 * @property firmware versão do firmware: `"x.y.z"` no SMA (resposta `02/0B`) e no iDO; no CRP, o texto do relógio
 * (ex.: `"MOY-VS02-2.0.0"`, resposta `5A 01` ou leitura de 2A28).
 * @property bleVersion versão da pilha BLE do relógio no formato `"x.y.z"`, ou `null` quando o protocolo não informa
 * (no SMA, quando a resposta `02/0B` tem menos de 6 bytes). No CRP, a versão do protocolo lida em 2A29 (ex.:
 * `MOYOUNG-V2`).
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
 * @property charging `true` quando o relógio está carregando. No CRP, uma leitura de 2A19 acima de 100 quer dizer
 * carregando, com nível = valor − 100. A regra é do Core de produção
 * (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/CRPService.java:1814-1822`), não da SDK, que repassa o byte
 * como veio. No SMA e no iDO é sempre `false` (nenhum dos dois informa carga na PoC).
 */
public data class Battery(
	val percent: Int,
	val charging: Boolean = false,
)

/**
 * Uma amostra de passos como o relógio entrega, já normalizada para um instante real.
 *
 * No SMA cada amostra representa uma hora cheia (o último registro da hora prevalece). No fitGPS com firmware 0.2.1 o
 * valor é o acumulado do dia até esse registro, não o total da hora (`docs/decisoes.md`, ADR 1).
 * Como combinar as amostras de um dia é definido por [br.com.grupotechnos.watch.protocol.profile.StepsAggregation].
 *
 * No CRP (Condor Reebok) há três tipos de amostra, distinguidos por [mode]:
 * - `0`: meia hora do histórico `0x59` (passos do intervalo; `calories` e `distanceMeters` zerados, porque o P85 não
 *   responde às categorias de distância `0x5D` e calorias `0x5C`);
 * - `1`: a meia hora atual, completada com o total de FEE1 menos as meias horas anteriores;
 * - `2`: **amostra de totais do dia**, no instante `início do dia + 1 s`, com `steps = 0` e as calorias e a distância
 *   do dia que o relógio informa (FEE1 hoje; `33 01` ontem, quando o firmware responde).
 * Com `HOURLY_SUM`, o total do dia soma os passos das meias horas mais as calorias e a distância da amostra de totais.
 *
 * @property atEpochMillis início do intervalo, em milissegundos desde 1970-01-01T00:00Z.
 * @property steps passos: o total do intervalo ou, num relógio que acumula por dia (fitGPS 0.2.1), o acumulado do dia
 * na última leitura do intervalo.
 * @property mode código de modo do registro no protocolo da família (no SMA, 0 = registro normal; no CRP, ver acima).
 * @property calories calorias estimadas para [steps] (kcal); no CRP, as do relógio, só na amostra de totais.
 * @property distanceMeters distância estimada para [steps] (metros); no CRP, a do relógio, só na amostra de totais.
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
 * @property dayKey dia local do fim da sessão (`yyyy-MM-dd`), ou seja, o dia em que a pessoa acordou. No CRP, a data de
 * sono da SDK: o dia em que se acorda, com a virada às 20h (uma leitura feita às 20h ou depois vale para o dia
 * seguinte; `sdk:m/a.java:3051-3058, 3118-3128`).
 * @property startEpochMillis início da sessão (primeiro ponto), em milissegundos desde 1970-01-01T00:00Z.
 * @property endEpochMillis fim da sessão (último ponto, o despertar), em milissegundos desde 1970-01-01T00:00Z.
 * @property startIso [startEpochMillis] em ISO-8601 com o offset do telefone.
 * @property endIso [endEpochMillis] em ISO-8601 com o offset do telefone.
 * @property stages estágios em ordem cronológica, sem sobreposição.
 * @property totalMinutes total de minutos de sono. Segue a regra da família: no SMA inclui o acordado (é a duração da
 * sessão); no CRP é leve + profundo + REM, como a SDK (`sdk:j/o0.java:52`) e o Core
 * (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/CRPService.java:2018-2041`).
 * @property lightMinutes minutos de sono leve.
 * @property deepMinutes minutos de sono profundo.
 * @property remMinutes minutos de sono REM (sempre 0 no SMA; no CRP, só o estado 3 que o relógio manda).
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
