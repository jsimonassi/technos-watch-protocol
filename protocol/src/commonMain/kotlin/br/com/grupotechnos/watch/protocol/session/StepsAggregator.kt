package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.StepsDay
import br.com.grupotechnos.watch.protocol.domain.StepsSample
import br.com.grupotechnos.watch.protocol.profile.StepsAggregation
import br.com.grupotechnos.watch.protocol.util.TimeFormat

/**
 * Combina as amostras de passos por dia local, segundo a [StepsAggregation] do perfil.
 *
 * - [StepsAggregation.HOURLY_SUM]: cada amostra é o total de uma hora; o dia é a soma de passos, calorias e
 *   distância. É o que os dois Cores fazem em produção (`SMAService.java:859-866`; `SMAService.m:334-342`).
 * - [StepsAggregation.DAY_CUMULATIVE]: cada amostra é o acumulado do dia; o dia é a amostra de **maior** número de
 *   passos, com as calorias e a distância dela. É o padrão do fitGPS (firmware 0.2.1; `docs/decisoes.md`, ADR 1). Com
 *   um contador que só cresce durante o dia, o maior valor é a leitura mais nova do dia. Não se usa a última amostra
 *   porque um relógio cumulativo que zera à meia-noite no fuso dele, e não no do telefone, deixaria a última hora do
 *   dia com um valor pequeno.
 *
 * O dia de cada amostra é `TimeFormat.dayKey(atEpochMillis, timeZoneId)`: o fuso do telefone **na hora da consulta**.
 * Se o telefone mudar de fuso, os dias são recalculados na próxima consulta; o `SampleStore` guarda só instantes. Com
 * [StepsAggregation.DAY_CUMULATIVE] isso pode levar a última hora de um dia ainda não confirmado, com o acumulado do dia
 * inteiro, para o dia seguinte (telefone que foi para leste); é um limite aberto da ADR 1.
 */
internal object StepsAggregator {
	/** Um [StepsDay] por dia com amostra, em ordem crescente de dia. */
	fun byDay(samples: List<StepsSample>, aggregation: StepsAggregation, timeZoneId: String): List<StepsDay> =
		samples
			.groupBy { TimeFormat.dayKey(it.atEpochMillis, timeZoneId) }
			.toSortedMap()
			.map { (dayKey, daySamples) -> day(dayKey, daySamples, aggregation, timeZoneId) }

	/** Total de um dia; [samples] vazio dá um dia zerado. */
	fun day(dayKey: String, samples: List<StepsSample>, aggregation: StepsAggregation, timeZoneId: String): StepsDay {
		val startIso = TimeFormat.startOfDayIso(dayKey, timeZoneId)
		if (samples.isEmpty()) return StepsDay(dayKey, startIso, steps = 0, calories = 0, distanceMeters = 0.0)
		return when (aggregation) {
			StepsAggregation.HOURLY_SUM -> StepsDay(
				dayKey = dayKey,
				startOfDayIso = startIso,
				steps = samples.sumOf { it.steps },
				calories = samples.sumOf { it.calories },
				distanceMeters = samples.sumOf { it.distanceMeters },
			)
			StepsAggregation.DAY_CUMULATIVE -> {
				val top = samples.maxBy { it.steps }
				StepsDay(dayKey, startIso, top.steps, top.calories, top.distanceMeters)
			}
		}
	}

	private fun <V> Map<String, V>.toSortedMap(): Map<String, V> = entries.sortedBy { it.key }.associate { it.key to it.value }
}
