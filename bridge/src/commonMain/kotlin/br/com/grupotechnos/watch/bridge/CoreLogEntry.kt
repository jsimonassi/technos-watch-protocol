package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.log.LogLevel

/**
 * Uma entrada do log do host, na forma neutra que o [CoreBridge] transforma em `onLog` ([CoreBridge.onLogEntry]) e nos
 * itens do `getLogs` ([CoreHost.logEntries]), os dois pelo [EventMapper.log].
 *
 * @property ts instante em milissegundos desde 1970-01-01T00:00Z.
 * @property level nível da entrada.
 * @property tag origem no formato `Classe/metodo`.
 * @property message texto (para um pacote GATT, o resumo do driver e o tamanho).
 * @property dir sentido do pacote, só nas entradas de pacote GATT (`WatchLogger.frame`); `null` nas outras.
 * @property hex bytes do pacote em hexadecimal maiúsculo sem separadores, só nas entradas de pacote; `null` nas outras.
 */
public data class CoreLogEntry(
	val ts: Long,
	val level: LogLevel,
	val tag: String,
	val message: String,
	val dir: FrameDirection?,
	val hex: String?,
)
