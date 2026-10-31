package br.com.grupotechnos.watch.protocol.crp

import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.SleepStage
import br.com.grupotechnos.watch.protocol.domain.SleepStageType
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Monta uma [SleepSession] a partir dos registros de sono do CRP, igual à SDK (`sdk:j/o0.java:24-133`;
 * `docs/protocolo-crp.md`, seção 9; ADR 29).
 *
 * **Data.** A data de sono é a da SDK: hoje − d (d = 0 no `32`, 1 no `33 03`, 2 no `33 04`), mais 1 dia se agora for
 * 20h ou mais (`sdk:m/a.java:3051-3058, 3118-3128`). Ela vira o `dayKey` da sessão ([sleepDate]).
 *
 * **Montagem.** Cada registro `[estado, hora, minuto]` abre um estágio; o seguinte o fecha:
 * - referência inicial `(0, 0)`; no formato legado a hora vem `% 24`, no novo (`B6`) não;
 * - duração = `(h' − hAnterior) * 60 + m − mAnterior`, com `h' = h + 24` se `hAnterior > h`; um registro com duração
 *   negativa é ignorado e não move a referência (o primeiro é sempre aceito, porque é medido desde 00:00);
 * - o primeiro registro aceito fixa o início: `hora:minuto` de `wakeDay − 1` se a hora for 20 ou mais, senão de
 *   `wakeDay`; cada estágio seguinte começa onde o anterior terminou;
 * - o último registro só fecha o anterior (o estado dele não conta).
 *
 * Estados: 0 acordado, 1 leve, 2 profundo, 3 REM; outro vira acordado, com aviso. Estágios de 0 min saem. Totais:
 * `totalMinutes = leve + profundo + REM` (sem o acordado), como a SDK (`sdk:j/o0.java:52`) e o Core
 * (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/CRPService.java:2018-2041`). O REM que a SDK fabrica quando o
 * relógio não manda REM (`sdk:j/k0.java`) não é reproduzido.
 *
 * Vetor da fase 0: `01 17 05 02 17 32 01 01 0A 00 06 1E` dá leve 365, profundo 80, total 445, das 23:05 de
 * `wakeDay − 1` às 06:30 de `wakeDay`.
 */
internal object CrpSleepAssembler {
	/** Hora local a partir da qual a data de sono passa para o dia seguinte (`sdk:j/o0.java:169-173`). */
	const val DAY_ROLLOVER_HOUR: Int = 20

	/** Data de sono de uma resposta pedida [daysAgo] dias atrás, lida em [nowEpochMillis] no fuso [tz]. */
	fun sleepDate(nowEpochMillis: Long, tz: String, daysAgo: Int): LocalDate {
		val local = TimeFormat.toLocalWallClock(nowEpochMillis, tz)
		val shift = if (local.hour >= DAY_ROLLOVER_HOUR) 1 - daysAgo else -daysAgo
		return local.date.plus(shift, DateTimeUnit.DAY)
	}

	/**
	 * Sessão dos [records] com data de sono [wakeDay] no fuso [tz], ou `null` quando não sobra nenhum estágio (lista vazia,
	 * um registro só, todos de 0 min).
	 *
	 * @param legacyHourWrap `true` nos formatos `32`/`33` (hora `% 24`); `false` no `B6`.
	 * @param onWarning recebe um aviso para o log (estado desconhecido, registro com hora ou minuto inválido).
	 */
	fun assemble(
		records: List<CrpSleepRecord>,
		wakeDay: LocalDate,
		tz: String,
		legacyHourWrap: Boolean,
		onWarning: (String) -> Unit = {},
	): SleepSession? {
		var prevHour = 0
		var prevMinute = 0
		var prevState = -1
		var cursor = 0L
		val stages = mutableListOf<SleepStage>()
		for (record in records) {
			val hour = if (legacyHourWrap) record.hour % 24 else record.hour
			val adjusted = if (prevHour > hour) hour + 24 else hour
			val minutes = (adjusted - prevHour) * 60 + record.minute - prevMinute
			if (minutes < 0) continue
			if (prevState < 0) {
				if (hour > 23 || record.minute > 59) {
					onWarning("first sleep record ${record.hour}:${record.minute} is not a valid time; skipped")
					continue
				}
				val day = if (hour >= DAY_ROLLOVER_HOUR) wakeDay.minus(1, DateTimeUnit.DAY) else wakeDay
				cursor = TimeFormat.fromLocalWallClock(LocalDateTime(day.year, day.monthNumber, day.dayOfMonth, hour, record.minute, 0), tz)
			} else {
				val end = cursor + minutes * 60_000L
				if (minutes > 0) stages += stage(typeOf(prevState, onWarning), cursor, end, tz)
				cursor = end
			}
			prevState = record.state
			prevHour = hour
			prevMinute = record.minute
		}
		if (stages.isEmpty()) return null
		fun minutesOf(type: SleepStageType): Int = stages.filter { it.type == type }.sumOf { ((it.endEpochMillis - it.startEpochMillis) / 60_000L).toInt() }
		val light = minutesOf(SleepStageType.LIGHT)
		val deep = minutesOf(SleepStageType.DEEP)
		val rem = minutesOf(SleepStageType.REM)
		val start = stages.first().startEpochMillis
		val end = stages.last().endEpochMillis
		return SleepSession(
			dayKey = TimeFormat.formatDate(wakeDay),
			startEpochMillis = start,
			endEpochMillis = end,
			startIso = TimeFormat.isoWithOffset(start, tz),
			endIso = TimeFormat.isoWithOffset(end, tz),
			stages = stages,
			totalMinutes = light + deep + rem,
			lightMinutes = light,
			deepMinutes = deep,
			remMinutes = rem,
			awakeMinutes = minutesOf(SleepStageType.AWAKE),
		)
	}

	private fun typeOf(state: Int, onWarning: (String) -> Unit): SleepStageType = when (state) {
		0 -> SleepStageType.AWAKE
		1 -> SleepStageType.LIGHT
		2 -> SleepStageType.DEEP
		3 -> SleepStageType.REM
		else -> {
			onWarning("unknown sleep state $state counted as awake")
			SleepStageType.AWAKE
		}
	}

	private fun stage(type: SleepStageType, start: Long, end: Long, tz: String): SleepStage =
		SleepStage(type, start, end, TimeFormat.isoWithOffset(start, tz), TimeFormat.isoWithOffset(end, tz))
}
