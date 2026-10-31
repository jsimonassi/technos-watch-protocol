package br.com.grupotechnos.watch.protocol.crp

import br.com.grupotechnos.watch.protocol.domain.StepsSample
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus

/**
 * Estado das meias horas de hoje que o driver guarda depois de um `readSteps`, para montar os passos ao vivo a partir
 * das notificações de FEE1 sem reler o histórico.
 *
 * @property dayKey dia local (`yyyy-MM-dd`) das meias horas.
 * @property completed passos de cada meia hora já fechada (48 posições; de [index] em diante, zero).
 * @property index meia hora atual (`(hora * 60 + minuto) / 30`).
 * @property lastRemainder passos da meia hora atual na última amostra montada (FEE1 menos as meias horas fechadas).
 */
internal class LiveStepsState(val dayKey: String, val completed: IntArray, val index: Int, val lastRemainder: Int)

/** Resultado de [CrpStepsMapper.liveUpdate]. */
internal sealed interface LiveUpdate {
	/** O dia mudou desde o último `readSteps`: o driver avisa `DriverNotice.DataChanged` e a sessão relê o histórico. */
	data object DataChanged : LiveUpdate

	/** Notificação de totais zerados: nada a fazer. */
	data object Ignore : LiveUpdate

	/** Amostras novas (meia hora atual e totais do dia) e o estado que passa a valer. */
	class Samples(val samples: List<StepsSample>, val state: LiveStepsState) : LiveUpdate
}

/**
 * Converte as meias horas do `0x59` e os totais do dia em [StepsSample] (`docs/protocolo-crp.md`, seção 8; ADR 28).
 *
 * O relógio dá os passos de cada meia hora (24 por página, 2 páginas por dia, hoje e ontem) e os totais do dia (FEE1 para
 * hoje; `33 01` para ontem, quando o firmware responde). O P85 não responde às categorias de distância (`5D`) nem de
 * calorias (`5C`), então não existem distância nem calorias por meia hora: só os totais do dia.
 *
 * Amostras de um dia `D` (o `mode` de cada uma está no KDoc de [StepsSample]):
 * - hoje, se o dia "tem passos" (soma das meias horas > 0 ou algum total > 0): uma amostra [MODE_BUCKET] por meia hora
 *   fechada `i < idx` (zeros inclusive, para sobrescrever valores velhos do store), a meia hora atual `idx` com o resto
 *   `max(0, FEE1 − Σ fechadas)` ([MODE_LIVE_REMAINDER]) e a amostra de totais ([MODE_DAY_TOTALS]) em
 *   `início do dia + 1 s`, com `steps = 0` e as calorias e a distância de FEE1. As duas últimas só existem com FEE1. As
 *   meias horas `>= idx` do histórico são ignoradas, como a SDK (`sdk:j/q0.java:31-49`).
 * - ontem, se tem passos: as 48 meias horas e, só com o `33 01`, a amostra de totais de ontem. Sem `33 01`, a amostra de
 *   totais que já está no store (lida ontem) continua valendo, porque a sessão mescla pelo instante.
 *
 * Instante da meia hora `i` do dia `D`: `TimeFormat.fromLocalWallClock(LocalDateTime(D, i / 2, (i % 2) * 30), tz)`.
 * Com `HOURLY_SUM`, o dia soma exatamente os passos de FEE1 e as calorias e a distância dele.
 */
internal object CrpStepsMapper {
	/** Meia hora do histórico `0x59`. */
	const val MODE_BUCKET: Int = 0

	/** Meia hora atual completada com FEE1. */
	const val MODE_LIVE_REMAINDER: Int = 1

	/** Amostra de totais do dia (calorias e distância do relógio, `steps = 0`). */
	const val MODE_DAY_TOTALS: Int = 2

	/** Meias horas por dia. */
	const val BUCKETS_PER_DAY: Int = 48

	/** Deslocamento da amostra de totais a partir do início do dia: não colide com a meia hora 0 (00:00). */
	const val DAY_TOTALS_OFFSET_MS: Long = 1_000L

	/** Meia hora local de [nowEpochMillis] em [tz]: `(hora * 60 + minuto) / 30`, de 0 a 47. */
	fun currentIndex(nowEpochMillis: Long, tz: String): Int {
		val local = TimeFormat.toLocalWallClock(nowEpochMillis, tz)
		return (local.hour * 60 + local.minute) / 30
	}

	/**
	 * Amostras de hoje e de ontem (ver o KDoc do objeto). [today] e [yesterday] têm [BUCKETS_PER_DAY] posições, ou são
	 * `null` quando não foram lidos.
	 */
	fun map(
		today: IntArray?,
		yesterday: IntArray?,
		todayTotals: CrpStepsTotals?,
		yesterdayTotals: CrpStepsTotals?,
		nowEpochMillis: Long,
		tz: String,
	): List<StepsSample> {
		val todayDate = TimeFormat.toLocalWallClock(nowEpochMillis, tz).date
		val samples = mutableListOf<StepsSample>()
		if (yesterday != null) {
			val date = todayDate.minus(1, DateTimeUnit.DAY)
			val hasSteps = yesterday.sum() > 0 || (yesterdayTotals != null && !yesterdayTotals.isZero)
			if (hasSteps) {
				for (i in 0 until BUCKETS_PER_DAY) samples += bucket(date, i, yesterday.getOrElse(i) { 0 }, MODE_BUCKET, tz)
				if (yesterdayTotals != null) samples += totals(date, yesterdayTotals, tz)
			}
		}
		if (today != null) {
			val idx = currentIndex(nowEpochMillis, tz)
			val completed = (0 until idx).sumOf { today.getOrElse(it) { 0 } }
			val hasSteps = completed > 0 || (todayTotals != null && !todayTotals.isZero)
			if (hasSteps) {
				for (i in 0 until idx) samples += bucket(todayDate, i, today.getOrElse(i) { 0 }, MODE_BUCKET, tz)
				if (todayTotals != null) {
					samples += bucket(todayDate, idx, remainder(todayTotals, completed), MODE_LIVE_REMAINDER, tz)
					samples += totals(todayDate, todayTotals, tz)
				}
			}
		}
		return samples
	}

	/** Estado das meias horas de hoje depois de um `readSteps` (ver [LiveStepsState]). */
	fun liveState(today: IntArray, todayTotals: CrpStepsTotals?, nowEpochMillis: Long, tz: String): LiveStepsState {
		val idx = currentIndex(nowEpochMillis, tz)
		val completed = IntArray(BUCKETS_PER_DAY) { i -> if (i < idx) today.getOrElse(i) { 0 } else 0 }
		val last = todayTotals?.let { remainder(it, completed.sum()) } ?: 0
		return LiveStepsState(TimeFormat.dayKey(nowEpochMillis, tz), completed, idx, last)
	}

	/**
	 * Uma notificação de FEE1 com o estado guardado:
	 * - dia diferente → [LiveUpdate.DataChanged];
	 * - totais zerados → [LiveUpdate.Ignore];
	 * - meia hora nova (`idx > state.index`): a antiga fecha com o último resto (aproximação até a próxima leitura do
	 *   histórico) e as puladas ficam 0;
	 * - resto = `max(0, passos − Σ fechadas)`; saem a meia hora atual ([MODE_LIVE_REMAINDER]) e os totais
	 *   ([MODE_DAY_TOTALS]).
	 */
	fun liveUpdate(state: LiveStepsState, totals: CrpStepsTotals, nowEpochMillis: Long, tz: String): LiveUpdate {
		val dayKey = TimeFormat.dayKey(nowEpochMillis, tz)
		if (dayKey != state.dayKey) return LiveUpdate.DataChanged
		if (totals.isZero) return LiveUpdate.Ignore
		val idx = currentIndex(nowEpochMillis, tz)
		val completed = state.completed.copyOf()
		if (idx > state.index) {
			completed[state.index] = state.lastRemainder
			for (j in state.index + 1 until idx) completed[j] = 0
		}
		val closed = (0 until idx).sumOf { completed[it] }
		val rest = remainder(totals, closed)
		val date = TimeFormat.parseDayKey(dayKey)
		val samples = listOf(bucket(date, idx, rest, MODE_LIVE_REMAINDER, tz), totals(date, totals, tz))
		return LiveUpdate.Samples(samples, LiveStepsState(dayKey, completed, maxOf(idx, 0), rest))
	}

	/** Instante da meia hora [index] do dia [date] no fuso [tz]. */
	fun bucketStart(date: LocalDate, index: Int, tz: String): Long =
		TimeFormat.fromLocalWallClock(LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, index / 2, (index % 2) * 30, 0), tz)

	private fun bucket(date: LocalDate, index: Int, steps: Int, mode: Int, tz: String): StepsSample =
		StepsSample(atEpochMillis = bucketStart(date, index, tz), steps = steps, mode = mode, calories = 0, distanceMeters = 0.0)

	private fun totals(date: LocalDate, totals: CrpStepsTotals, tz: String): StepsSample =
		StepsSample(
			atEpochMillis = TimeFormat.startOfDayEpochMillis(TimeFormat.formatDate(date), tz) + DAY_TOTALS_OFFSET_MS,
			steps = 0,
			mode = MODE_DAY_TOTALS,
			calories = totals.calories,
			distanceMeters = totals.distanceMeters.toDouble(),
		)

	private fun remainder(totals: CrpStepsTotals, completed: Int): Int = maxOf(0, totals.steps - completed)
}
