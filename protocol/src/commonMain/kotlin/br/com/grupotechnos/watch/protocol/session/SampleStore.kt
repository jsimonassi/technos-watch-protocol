package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.StepsSample

/**
 * Persistência local do histórico lido do relógio, implementada pelo host.
 *
 * Existe porque o relógio pode não devolver o histórico inteiro a cada leitura (o cursor pode avançar
 * depois do nosso ACK). A sessão grava aqui tudo o que lê e responde `getStepsDays`/`getSleepSessions`
 * a partir daqui, nunca direto do relógio.
 *
 * Os `load` são chamados uma vez, no construtor da `WatchSession` (na thread de quem a cria); a partir daí a sessão
 * trabalha sobre uma cópia em memória e só chama `save` e `clear`, sempre na thread da sessão, que não é a thread
 * principal: o host pode fazer IO síncrono. Cada `save` substitui o conteúdo anterior por completo. Uma exceção
 * lançada aqui é registrada no log e não interrompe a sessão (um `load` que falha começa vazio).
 */
public interface SampleStore {
	/** Amostras de passos gravadas, em qualquer ordem; a sessão ordena e remove duplicatas por `atEpochMillis`. */
	public fun loadSteps(): List<StepsSample>

	/** Substitui todas as amostras de passos gravadas por [samples] (a lista completa, já mesclada e ordenada pela sessão). */
	public fun saveSteps(samples: List<StepsSample>)

	/** Sessões de sono gravadas, em qualquer ordem; a sessão ordena e remove duplicatas por `startEpochMillis`. */
	public fun loadSleep(): List<SleepSession>

	/** Substitui todas as sessões de sono gravadas por [sessions]. */
	public fun saveSleep(sessions: List<SleepSession>)

	/** Dias (`yyyy-MM-dd`) já confirmados pelo app como enviados ao backend. */
	public fun loadConfirmedDays(kind: DataKind): List<String>

	/** Substitui os dias confirmados de [kind] por [dayKeys] (o conjunto inteiro, não só os novos). */
	public fun saveConfirmedDays(kind: DataKind, dayKeys: List<String>)

	/** Apaga tudo. Chamado ao esquecer o relógio (`disconnect(forget = true)`). */
	public fun clear()
}

/** Implementação em memória, para testes e para hosts que não precisam sobreviver a reinício do processo. */
public class InMemorySampleStore : SampleStore {
	private var steps: List<StepsSample> = emptyList()
	private var sleep: List<SleepSession> = emptyList()
	private val confirmed = mutableMapOf<DataKind, List<String>>()

	override fun loadSteps(): List<StepsSample> = steps

	override fun saveSteps(samples: List<StepsSample>) {
		steps = samples.toList()
	}

	override fun loadSleep(): List<SleepSession> = sleep

	override fun saveSleep(sessions: List<SleepSession>) {
		sleep = sessions.toList()
	}

	override fun loadConfirmedDays(kind: DataKind): List<String> = confirmed[kind].orEmpty()

	override fun saveConfirmedDays(kind: DataKind, dayKeys: List<String>) {
		confirmed[kind] = dayKeys.toList()
	}

	override fun clear() {
		steps = emptyList()
		sleep = emptyList()
		confirmed.clear()
	}
}
