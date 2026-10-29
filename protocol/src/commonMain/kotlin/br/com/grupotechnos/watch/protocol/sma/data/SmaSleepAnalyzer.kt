package br.com.grupotechnos.watch.protocol.sma.data

import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.SleepStage
import br.com.grupotechnos.watch.protocol.domain.SleepStageType
import br.com.grupotechnos.watch.protocol.util.TimeFormat

/**
 * Transforma registros `05/46` em [SleepSession]. **Exploratório**: o fitGPS nunca teve sono consumido em produção
 * (os dois Cores deixam `getHistorySleep` vazio, `SMAService.java:1423`, `SMAService.m:167`), então as regras abaixo
 * reproduzem a SDK, não um comportamento já observado no relógio (`docs/sma-dados.md`, seção 5).
 *
 * Etapas:
 * 1. **Agrupamento por noite** (decisão desta biblioteca; a SDK recebe a lista já recortada pelo app): em ordem de
 *    tempo, uma noite termina no registro END (34) e o registro seguinte começa outra; uma lacuna de mais de
 *    [NIGHT_GAP_MS] entre dois registros também separa noites (relógio que não gravou o END). Não há corte por
 *    horário: com o corte fixo de 10:00 da primeira versão, quem acordava depois das 10:00 tinha o END jogado na
 *    noite seguinte e a noite certa terminava antes da hora. Sem agrupar, um lote com várias noites perderia todas
 *    menos a última, porque [analyseSleeps] recomeça a cada START.
 * 2. **[analyseSleeps]**, port 1:1 de `SmaSleep.analyseSleeps` (`SmaSleep.java:17-70`), em cada grupo.
 * 3. **[sleepStatus]**, port 1:1 de `SmaSleep.getSleepStatus` (`SmaSleep.java:72-115`), para os totais. Quando o
 *    corte de 10:00 da fórmula dá duração negativa (noite que começa antes e termina depois das 10:00, ou que termina
 *    depois das 10:00 tendo começado na véspera), a duração passa a ser o intervalo real entre o primeiro e o último
 *    ponto e o acordado é recalculado com ela; nos outros casos a fórmula da SDK vale igual.
 * 4. **Estágios**: cada par consecutivo `(p[i], p[i+1])` vira um estágio do tipo de `p[i].mode`: 1 → DEEP,
 *    2 → LIGHT, qualquer outro (3, END 34 no meio, desconhecido) → AWAKE, que é como [sleepStatus] conta
 *    ("acordado = duração − profundo − leve"). Estágios de duração zero são descartados e vizinhos do mesmo tipo
 *    são unidos, para não haver sobreposição. REM nunca é produzido.
 *
 * Os pontos são processados na escala de milissegundos "local como UTC" da SDK ([SmaLocalTime.toLocalMillis]), para
 * a aritmética de minuto do dia ficar igual; só no fim viram instantes reais com o fuso do telefone.
 */
internal object SmaSleepAnalyzer {
	/** 15 minutos em milissegundos: o deslocamento que a SDK aplica (`SmaSleep.java:37, 42, 45, 61`). */
	const val SHIFT_MS: Long = 900_000L

	/** Minuto do dia (10:00) até o qual a fórmula de duração da SDK soma um dia (`SmaSleep.java:100-106`). */
	const val DAY_TURN_MINUTE: Int = 600

	/** Lacuna entre dois registros acima da qual eles pertencem a noites diferentes (12 h): maior que uma noite inteira sem registro no meio (START 23:00, END 10:30), menor que o intervalo entre acordar e dormir de novo. */
	const val NIGHT_GAP_MS: Long = 12 * 3_600_000L

	/**
	 * Um ponto da linha do tempo de sono, mutável como o `SmaSleep` da SDK, porque o algoritmo original altera
	 * `mode` e `time` dos próprios registros.
	 *
	 * @property time milissegundos na escala "local como UTC" da SDK.
	 */
	class Point(var time: Long, var mode: Int, val soft: Int = 0, val strong: Int = 0) {
		override fun toString(): String = "Point(time=$time, mode=$mode, soft=$soft, strong=$strong)"
	}

	/** Sessões analisadas, em ordem cronológica. Lista vazia quando não há registros suficientes. */
	fun analyze(records: List<SmaSleepRecord>, timeZoneId: String): List<SleepSession> {
		return nights(records)
			.mapNotNull { night -> buildSession(analyseSleeps(night), timeZoneId) }
			.sortedBy { it.startEpochMillis }
	}

	/** Etapa 1: separa os registros em noites (fecha no END e em lacunas maiores que [NIGHT_GAP_MS]). */
	fun nights(records: List<SmaSleepRecord>): List<List<Point>> {
		val groups = ArrayList<MutableList<Point>>()
		var current = ArrayList<Point>()
		for (record in records.sortedBy { it.secondsSince2000 }) {
			val point = Point(SmaLocalTime.toLocalMillis(record.secondsSince2000), record.mode, record.soft, record.strong)
			val last = current.lastOrNull()
			if (last != null && (last.mode == SmaSleepMode.END || point.time - last.time > NIGHT_GAP_MS)) {
				groups += current
				current = ArrayList()
			}
			current += point
		}
		if (current.isNotEmpty()) groups += current
		return groups
	}

	/**
	 * Port de `SmaSleep.analyseSleeps` (`SmaSleep.java:17-70`). Trabalha sobre cópias: a entrada não é alterada.
	 *
	 * Regras (com a linha de origem):
	 * - menos de 2 registros → lista vazia (`:18, 67-68`);
	 * - ordena por tempo, de forma estável (`:19`, `Collections.sort` com `compareTo` por `time`, `:117-123`);
	 * - START (17): descarta tudo o que veio antes, insere LIGHT em `t` e o próprio registro vira DEEP em
	 *   `t + 15 min` (`:25-37`);
	 * - END (34): só marca que houve despertar (`:38-39`);
	 * - senão, `strong > 2` → AWAKE em `t − 15 min` (`:40-42`); senão, `soft > 2` → LIGHT em `t − 15 min` (`:43-45`);
	 *   os demais mantêm `mode` e `time`;
	 * - o ponto só entra se o `mode` for diferente do último já incluído (`:48-50`);
	 * - sem END e com mais de um ponto, o último vira END (`:53-55`);
	 * - sem START e com mais de um ponto, o primeiro vira LIGHT e um DEEP é inserido 15 min depois dele (`:57-63`);
	 * - ordena de novo por tempo (`:65`).
	 */
	fun analyseSleeps(origin: List<Point>): List<Point> {
		if (origin.size < 2) return emptyList()
		val sorted = origin.map { Point(it.time, it.mode, it.soft, it.strong) }.sortedBy { it.time }
		val result = ArrayList<Point>()
		var hasFallAsleep = false
		var hasWakeUp = false
		for (sleep in sorted) {
			if (sleep.mode == SmaSleepMode.START) {
				hasFallAsleep = true
				result.clear()
				result += Point(sleep.time, SmaSleepMode.LIGHT)
				sleep.mode = SmaSleepMode.DEEP
				sleep.time += SHIFT_MS
			} else if (sleep.mode == SmaSleepMode.END) {
				hasWakeUp = true
			} else if (sleep.strong > 2) {
				sleep.mode = SmaSleepMode.AWAKE
				sleep.time -= SHIFT_MS
			} else if (sleep.soft > 2) {
				sleep.mode = SmaSleepMode.LIGHT
				sleep.time -= SHIFT_MS
			}
			if (result.isEmpty() || result.last().mode != sleep.mode) {
				result += sleep
			}
		}
		if (!hasWakeUp && result.size > 1) {
			result.last().mode = SmaSleepMode.END
		}
		if (!hasFallAsleep && result.size > 1) {
			result[0].mode = SmaSleepMode.LIGHT
			result.add(1, Point(result[0].time + SHIFT_MS, SmaSleepMode.DEEP))
		}
		return result.sortedBy { it.time }
	}

	/**
	 * Port de `SmaSleep.getSleepStatus` (`SmaSleep.java:72-115`): `[duração, profundo, leve, acordado]` em minutos.
	 *
	 * - Profundo e leve somam os intervalos `p[i+1] − p[i]` com `p[i].mode` 1 e 2 (`:80-94`), truncados para minutos
	 *   no fim (`:109-110`).
	 * - A duração usa o minuto do dia do primeiro e do último ponto, somando 1440 a quem estiver até 10:00
	 *   (`:96-108`); acordado é `duração − profundo − leve` (`:111`), não a soma dos intervalos `mode == 3`.
	 * - Com menos de 2 pontos, tudo zero (`:77, 114`).
	 */
	fun sleepStatus(points: List<Point>): IntArray {
		var duration = 0L
		var deep = 0L
		var light = 0L
		var awake = 0L
		if (points.size > 1) {
			for (index in 0 until points.size - 1) {
				val first = points[index]
				val time = points[index + 1].time - first.time
				when (first.mode) {
					SmaSleepMode.DEEP -> deep += time
					SmaSleepMode.LIGHT -> light += time
					SmaSleepMode.AWAKE -> awake += time
				}
			}
			var start = points.first().time / 1000L / 60L % 1440L
			var end = points.last().time / 1000L / 60L % 1440L
			if (start <= DAY_TURN_MINUTE) start += 1440L
			if (end <= DAY_TURN_MINUTE) end += 1440L
			duration = end - start
			deep = deep / 1000L / 60L
			light = light / 1000L / 60L
			awake = duration - deep - light
		}
		return intArrayOf(duration.toInt(), deep.toInt(), light.toInt(), awake.toInt())
	}

	private fun buildSession(points: List<Point>, timeZoneId: String): SleepSession? {
		if (points.size < 2) return null
		val epochs = points.map { TimeFormat.fromLocalWallClock(SmaLocalTime.localMillisToWallClock(it.time), timeZoneId) }
		val stages = ArrayList<SleepStage>()
		for (index in 0 until points.size - 1) {
			val start = epochs[index]
			val end = epochs[index + 1]
			if (end <= start) continue
			val type = when (points[index].mode) {
				SmaSleepMode.DEEP -> SleepStageType.DEEP
				SmaSleepMode.LIGHT -> SleepStageType.LIGHT
				else -> SleepStageType.AWAKE
			}
			val previous = stages.lastOrNull()
			if (previous != null && previous.type == type && previous.endEpochMillis == start) {
				stages[stages.size - 1] = stage(type, previous.startEpochMillis, end, timeZoneId)
			} else {
				stages += stage(type, start, end, timeZoneId)
			}
		}
		if (stages.isEmpty()) return null
		val (sdkDuration, deep, light, sdkAwake) = sleepStatus(points)
		val duration = if (sdkDuration >= 0) sdkDuration else ((points.last().time - points.first().time) / 60_000L).toInt()
		val awake = if (sdkDuration >= 0) sdkAwake else duration - deep - light
		val startEpoch = epochs.first()
		val endEpoch = epochs.last()
		return SleepSession(
			dayKey = TimeFormat.dayKey(endEpoch, timeZoneId),
			startEpochMillis = startEpoch,
			endEpochMillis = endEpoch,
			startIso = TimeFormat.isoWithOffset(startEpoch, timeZoneId),
			endIso = TimeFormat.isoWithOffset(endEpoch, timeZoneId),
			stages = stages,
			// Depois da troca acima a duração não fica negativa; o coerce só protege de pontos fora de ordem.
			totalMinutes = duration.coerceAtLeast(0),
			lightMinutes = light,
			deepMinutes = deep,
			remMinutes = 0,
			awakeMinutes = awake.coerceAtLeast(0),
		)
	}

	private fun stage(type: SleepStageType, start: Long, end: Long, timeZoneId: String): SleepStage =
		SleepStage(
			type = type,
			startEpochMillis = start,
			endEpochMillis = end,
			startIso = TimeFormat.isoWithOffset(start, timeZoneId),
			endIso = TimeFormat.isoWithOffset(end, timeZoneId),
		)
}
