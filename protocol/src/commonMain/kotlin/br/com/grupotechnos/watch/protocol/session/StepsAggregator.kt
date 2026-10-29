package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.StepsDay
import br.com.grupotechnos.watch.protocol.domain.StepsSample
import br.com.grupotechnos.watch.protocol.profile.StepsAggregation
import br.com.grupotechnos.watch.protocol.util.TimeFormat

/**
 * Soma as amostras de passos por dia local, segundo a [StepsAggregation] do perfil.
 *
 * - [StepsAggregation.HOURLY_SUM]: cada amostra é o total de uma hora; o dia é a soma de passos, calorias e
 *   distância. É o que o Core Android faz em produção (`SMAService.java:859-866`) e o padrão do fitGPS
 *   (`docs/decisoes.md`, ADR 1).
 * - [StepsAggregation.DAY_CUMULATIVE]: cada amostra é o acumulado do dia; o dia é a amostra de **maior** número de
 *   passos, com as calorias e a distância dela. Não se usa a última amostra porque um relógio cumulativo que zera à
 *   meia-noite no fuso dele, e não no do telefone, deixaria a última hora do dia com um valor pequeno.
 *
 * O dia de cada amostra é `TimeFormat.dayKey(atEpochMillis, timeZoneId)`: o fuso do telefone **na hora da consulta**.
 * Se o telefone mudar de fuso, os dias são recalculados na próxima consulta; o `SampleStore` guarda só instantes.
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
