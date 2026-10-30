package br.com.grupotechnos.watch.protocol.sma.data

import br.com.grupotechnos.watch.protocol.domain.StepsSample
import br.com.grupotechnos.watch.protocol.sma.SmaEstimates
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.datetime.LocalDateTime

/**
 * Converte registros `05/42` em [StepsSample] horários (`docs/sma-dados.md`, seção 2).
 *
 * Regras, iguais às do Core Android em produção (`SMAService.java:418-449`):
 * - Só registros com `mode == 0` ([SmaSportMode.NORMAL]) entram; os de exercício (32/47 etc.) ficam de fora.
 * - O horário é arredondado para baixo até a hora cheia local (minuto e segundo zerados, `SMAService.java:439-443`).
 * - Vários registros na mesma hora: vale o **último na ordem de chegada** (o `HashMap.put` de
 *   `SMAService.java:448` sobrescreve). A ordem de chegada é a ordem das páginas e, dentro da página, a do value.
 * - A hora local vira instante com o fuso do telefone, levando horário de verão em conta (o Core subtraía
 *   `getRawOffset()`, `SMAService.java:420`, errando uma hora durante o horário de verão).
 * - Calorias e distância por amostra com [SmaEstimates] (perfil fixo do Core).
 *
 * O resultado sai ordenado por instante. Como combinar as horas de um dia é decisão da sessão
 * (`Quirks.stepsAggregation`). No fitGPS 0.2.1 o `mode 0` é o acumulado do dia e o perfil usa `DAY_CUMULATIVE`
 * (`docs/decisoes.md`, ADR 1): o relógio entrega os registros do mais antigo para o mais novo e o contador só cresce
 * durante o dia, então o último da hora é também o maior dela, e o maior das horas é o total do dia.
 */
internal object SmaStepsMapper {
	/**
	 * @param records registros na ordem em que chegaram.
	 * @param timeZoneId fuso do telefone (`DriverLink.clock.timeZoneId()`).
	 */
	fun map(records: List<SmaSportRecord>, timeZoneId: String): List<StepsSample> {
		val byHour = LinkedHashMap<LocalDateTime, SmaSportRecord>()
		for (record in records) {
			if (record.mode != SmaSportMode.NORMAL) continue
			val local = SmaLocalTime.toLocalWallClock(record.secondsSince2000)
			val hour = LocalDateTime(local.year, local.monthNumber, local.dayOfMonth, local.hour, 0, 0)
			byHour.remove(hour)
			byHour[hour] = record
		}
		return byHour.entries
			.map { (hour, record) ->
				StepsSample(
					atEpochMillis = TimeFormat.fromLocalWallClock(hour, timeZoneId),
					steps = record.steps,
					mode = record.mode,
					calories = SmaEstimates.calories(record.steps),
					distanceMeters = SmaEstimates.distanceMeters(record.steps),
				)
			}
			.sortedBy { it.atEpochMillis }
	}
}
