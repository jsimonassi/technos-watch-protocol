package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.WatchErrorCodes
import br.com.grupotechnos.watch.protocol.domain.WatchException
import br.com.grupotechnos.watch.protocol.driver.DeviceDriver
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.log.WatchLogger
import kotlinx.coroutines.CancellationException

/**
 * Leitura de histórico do relógio para o [HistoryStore]. Não conhece estado de conexão nem listener: a
 * `SessionEngine` cuida disso e chama estas funções de dentro da fila de comandos.
 *
 * Regras (`docs/arquitetura.md`, fluxo "relógio → app"):
 * - **Passos** são obrigatórios: erro na leitura encerra o sync com o código do erro (no SMA, `TIMEOUT` de GET não
 *   tem retentativa, `docs/decisoes.md`).
 * - **Sono** é exploratório no fitGPS (hipótese H4): erro ou lista vazia vão para o log e o sync continua com sucesso.
 *   A exceção é `DISCONNECTED`, que é propagado para a queda do link ser tratada como queda.
 * - Progresso: 0 no início e `100 * (i + 1) / n` ao fim de cada tipo pedido, então `[STEPS, SLEEP]` dá 0, 50, 100.
 * - No fim, a poda por `Quirks.historyRetentionDays`.
 */
internal class SyncEngine(
	private val history: HistoryStore,
	private val logger: WatchLogger,
) {
	/** Lê os passos e mescla no histórico. */
	suspend fun refreshSteps(driver: DeviceDriver) {
		val samples = driver.readSteps()
		val changed = history.mergeSteps(samples)
		logger.log(LogLevel.INFO, "Session/refreshSteps", "read ${samples.size} step samples, $changed new or changed")
	}

	/**
	 * Sincroniza os tipos pedidos, nesta ordem: passos, depois sono. Lista vazia significa todos.
	 *
	 * @param onProgress chamado com 0 e depois ao fim de cada tipo.
	 */
	suspend fun sync(driver: DeviceDriver, kinds: List<DataKind>, retentionDays: Int, onProgress: (Int) -> Unit) {
		val ordered = DataKind.entries.filter { kinds.isEmpty() || it in kinds }
		onProgress(0)
		ordered.forEachIndexed { index, kind ->
			when (kind) {
				DataKind.STEPS -> refreshSteps(driver)
				DataKind.SLEEP -> readSleep(driver)
			}
			onProgress(100 * (index + 1) / ordered.size)
		}
		history.prune(retentionDays)
	}

	private suspend fun readSleep(driver: DeviceDriver) {
		try {
			val sessions = driver.readSleep()
			if (sessions.isEmpty()) {
				logger.log(LogLevel.INFO, "Session/syncHistory", "watch returned no sleep sessions (exploratory, not an error)")
				return
			}
			val changed = history.mergeSleep(sessions)
			logger.log(LogLevel.INFO, "Session/syncHistory", "read ${sessions.size} sleep sessions, $changed new or changed")
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			if (error is WatchException && error.code == WatchErrorCodes.DISCONNECTED) throw error
			val code = (error as? WatchException)?.code ?: WatchErrorCodes.PROTOCOL
			logger.log(LogLevel.WARN, "Session/syncHistory", "sleep read failed ($code: ${error.message}); sync continues")
		}
	}
}
