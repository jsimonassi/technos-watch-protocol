package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.StepsDay
import br.com.grupotechnos.watch.protocol.domain.StepsSample
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.log.WatchLogger
import br.com.grupotechnos.watch.protocol.profile.StepsAggregation
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Histórico local da sessão: a cópia em memória do [SampleStore] que responde `getStepsDays`/`getSleepSessions`.
 *
 * **Por que existe.** O relógio pode não devolver o passado de novo (hipóteses H1/H2 de `docs/protocolo-sma.md`,
 * seção 8: o cursor pode avançar com o nosso ACK e os registros lidos podem ser apagados). Então tudo o que a sessão
 * lê é mesclado aqui e persistido no store, e as consultas do host nunca falam com o relógio (`docs/decisoes.md`,
 * ADR "Histórico guardado no store").
 *
 * **Concorrência.** As consultas são síncronas e podem vir de qualquer thread (a thread JS do React Native, por
 * exemplo). Por isso o estado é feito de **instantâneos imutáveis**: listas e conjuntos que nunca são alterados, só
 * substituídos por inteiro.
 * - Passos e sono: `@Volatile`, substituídos só na thread da sessão ([mergeSteps], [mergeSleep], [prune], [clear]).
 * - Dias confirmados: [AtomicReference] com compare-and-set, porque [confirm] roda na thread de quem chama
 *   (`confirmUploaded` precisa valer na hora, antes de o app pedir o histórico de novo) e pode concorrer com a poda.
 * Um leitor vê sempre um instantâneo inteiro, nunca uma lista pela metade.
 *
 * **Persistência.** Cada mudança grava no [SampleStore] na thread da sessão. Falha do store é registrada no log e
 * não interrompe a sincronização: o instantâneo em memória continua valendo até o processo morrer.
 *
 * Os instantâneos iniciais são carregados do store no construtor, na thread que cria a `WatchSession`.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class HistoryStore(
	private val store: SampleStore,
	private val clock: WatchClock,
	private val logger: WatchLogger,
) {
	@Volatile
	private var steps: List<StepsSample> = emptyList()

	@Volatile
	private var sleep: List<SleepSession> = emptyList()

	private val confirmed: Map<DataKind, AtomicReference<Set<String>>> =
		DataKind.entries.associateWith { AtomicReference(emptySet()) }

	init {
		steps = loadOrEmpty("loadSteps") { store.loadSteps() }
			.associateBy { it.atEpochMillis }.values.sortedBy { it.atEpochMillis }
		sleep = loadOrEmpty("loadSleep") { store.loadSleep() }
			.associateBy { it.startEpochMillis }.values.sortedBy { it.startEpochMillis }
		for (kind in DataKind.entries) {
			val days = loadOrEmpty("loadConfirmedDays") { store.loadConfirmedDays(kind) }
			confirmed.getValue(kind).store(days.toSet())
		}
	}

	/** Instantâneo atual das amostras de passos, em ordem de instante. */
	fun stepsSnapshot(): List<StepsSample> = steps

	/** Instantâneo atual das sessões de sono, em ordem de início. */
	fun sleepSnapshot(): List<SleepSession> = sleep

	/** Dias confirmados de [kind]. */
	fun confirmedDays(kind: DataKind): Set<String> = confirmed.getValue(kind).load()

	/**
	 * Mescla amostras novas: a chave é `atEpochMillis` e a leitura mais nova substitui a antiga (no SMA, a mesma hora
	 * relida depois tem o valor atualizado da hora). Thread da sessão.
	 *
	 * @return quantas amostras eram novas ou mudaram.
	 */
	fun mergeSteps(samples: List<StepsSample>): Int {
		if (samples.isEmpty()) return 0
		val byInstant = LinkedHashMap<Long, StepsSample>()
		steps.forEach { byInstant[it.atEpochMillis] = it }
		var changed = 0
		for (sample in samples) {
			if (byInstant.put(sample.atEpochMillis, sample) != sample) changed++
		}
		steps = byInstant.values.sortedBy { it.atEpochMillis }
		persist("saveSteps") { store.saveSteps(steps) }
		return changed
	}

	/** Mescla sessões de sono pela chave `startEpochMillis`; a mais nova substitui. Thread da sessão. */
	fun mergeSleep(sessions: List<SleepSession>): Int {
		if (sessions.isEmpty()) return 0
		val byStart = LinkedHashMap<Long, SleepSession>()
		sleep.forEach { byStart[it.startEpochMillis] = it }
		var changed = 0
		for (session in sessions) {
			if (byStart.put(session.startEpochMillis, session) != session) changed++
		}
		sleep = byStart.values.sortedBy { it.startEpochMillis }
		persist("saveSleep") { store.saveSleep(sleep) }
		return changed
	}

	/**
	 * Marca dias como enviados ao backend. Atualiza o instantâneo na hora (qualquer thread); a gravação no store é
	 * feita depois por [persistConfirmed], na thread da sessão. Dias fora do formato `yyyy-MM-dd` são ignorados.
	 */
	fun confirm(kind: DataKind, dayKeys: List<String>) {
		val valid = dayKeys.filter { isDayKey(it) }
		if (valid.size != dayKeys.size) {
			logger.log(LogLevel.WARN, "Session/confirmUploaded", "ignored invalid day keys: ${dayKeys - valid.toSet()}")
		}
		if (valid.isEmpty()) return
		update(kind) { it + valid }
	}

	/** Grava no store o instantâneo atual dos dias confirmados de [kind]. Thread da sessão. */
	fun persistConfirmed(kind: DataKind) {
		val days = confirmedDays(kind).sorted()
		persist("saveConfirmedDays") { store.saveConfirmedDays(kind, days) }
	}

	/**
	 * Descarta amostras, sessões e dias confirmados anteriores a `hoje - retentionDays` (no fuso atual do telefone).
	 * Thread da sessão.
	 */
	fun prune(retentionDays: Int) {
		val oldestKept = dayKeyBefore(retentionDays.coerceAtLeast(0))
		val tz = clock.timeZoneId()
		val keptSteps = steps.filter { TimeFormat.dayKey(it.atEpochMillis, tz) >= oldestKept }
		if (keptSteps.size != steps.size) {
			logger.log(LogLevel.INFO, "Session/prune", "dropped ${steps.size - keptSteps.size} step samples before $oldestKept")
			steps = keptSteps
			persist("saveSteps") { store.saveSteps(steps) }
		}
		val keptSleep = sleep.filter { it.dayKey >= oldestKept }
		if (keptSleep.size != sleep.size) {
			logger.log(LogLevel.INFO, "Session/prune", "dropped ${sleep.size - keptSleep.size} sleep sessions before $oldestKept")
			sleep = keptSleep
			persist("saveSleep") { store.saveSleep(sleep) }
		}
		for (kind in DataKind.entries) {
			val before = confirmedDays(kind)
			val after = update(kind) { days -> days.filterTo(HashSet()) { it >= oldestKept } }
			if (after.size != before.size) persistConfirmed(kind)
		}
	}

	/** Apaga tudo, no store e em memória. Thread da sessão. */
	fun clear() {
		steps = emptyList()
		sleep = emptyList()
		DataKind.entries.forEach { kind -> update(kind) { emptySet() } }
		persist("clear") { store.clear() }
	}

	/**
	 * Totais por dia de todos os dias com amostra, até hoje, que ainda não foram confirmados, em ordem crescente.
	 * Como o Core atual, dias sem amostra não aparecem (não há dia zerado inventado).
	 *
	 * [daysBefore] **não** corta os dias mais velhos: os dois Cores ignoram o parâmetro e devolvem tudo o que não foi
	 * enviado (`SMAService.java:829-850`, `SMAService.m:301-312`), e o app Mormaii sempre pede 3 dias
	 * (`src/services/Smartwatch.ts:177-178` do app). Cortar perderia em silêncio um dia não enviado de quem passou mais de 3 dias
	 * sem abrir o app: ele ficaria no store até a poda (`docs/decisoes.md`, ADR 15). O valor só vai para o log.
	 */
	fun stepsDays(daysBefore: Int, aggregation: StepsAggregation): List<StepsDay> {
		val today = todayKey()
		val skip = confirmedDays(DataKind.STEPS)
		val days = StepsAggregator.byDay(steps, aggregation, clock.timeZoneId())
			.filter { it.dayKey <= today && it.dayKey !in skip }
		logOlderThanWindow("getStepsDays", daysBefore, days.map { it.dayKey })
		return days
	}

	/** Total de hoje, mesmo sem amostras (dia zerado) e mesmo se hoje já foi confirmado: é o valor "ao vivo". */
	fun today(aggregation: StepsAggregation): StepsDay {
		val tz = clock.timeZoneId()
		val today = todayKey()
		return StepsAggregator.day(today, steps.filter { TimeFormat.dayKey(it.atEpochMillis, tz) == today }, aggregation, tz)
	}

	/**
	 * Sessões de sono com `dayKey` até hoje, sem os dias confirmados, por início. Mesma regra de [stepsDays]:
	 * [daysBefore] não corta os dias mais velhos (ADR 15).
	 */
	fun sleepSessions(daysBefore: Int): List<SleepSession> {
		val today = todayKey()
		val skip = confirmedDays(DataKind.SLEEP)
		val sessions = sleep.filter { it.dayKey <= today && it.dayKey !in skip }
		logOlderThanWindow("getSleepSessions", daysBefore, sessions.map { it.dayKey })
		return sessions
	}

	/** Registra quando a resposta traz dias anteriores à janela pedida, para o log explicar a diferença. */
	private fun logOlderThanWindow(method: String, daysBefore: Int, dayKeys: List<String>) {
		val oldest = dayKeyBefore(daysBefore.coerceAtLeast(0))
		val older = dayKeys.filter { it < oldest }.distinct()
		if (older.isNotEmpty()) {
			logger.log(LogLevel.INFO, "Session/$method", "daysBefore=$daysBefore, also returning unconfirmed days $older")
		}
	}

	private fun todayKey(): String = TimeFormat.dayKey(clock.nowEpochMillis(), clock.timeZoneId())

	private fun dayKeyBefore(days: Int): String {
		val today = TimeFormat.parseDayKey(todayKey())
		return TimeFormat.formatDate(today.minus(days, DateTimeUnit.DAY))
	}

	private fun update(kind: DataKind, transform: (Set<String>) -> Set<String>): Set<String> {
		val reference = confirmed.getValue(kind)
		while (true) {
			val current = reference.load()
			val next = transform(current)
			if (reference.compareAndSet(current, next)) return next
		}
	}

	private fun isDayKey(text: String): Boolean =
		try {
			TimeFormat.parseDayKey(text)
			true
		} catch (_: IllegalArgumentException) {
			false
		}

	private inline fun <T> loadOrEmpty(method: String, load: () -> List<T>): List<T> =
		try {
			load()
		} catch (error: Exception) {
			logger.log(LogLevel.ERROR, "Session/$method", "store failed, starting empty: ${error.message}")
			emptyList()
		}

	private inline fun persist(method: String, save: () -> Unit) {
		try {
			save()
		} catch (error: Exception) {
			logger.log(LogLevel.ERROR, "Session/$method", "store failed, keeping the in-memory copy: ${error.message}")
		}
	}
}
