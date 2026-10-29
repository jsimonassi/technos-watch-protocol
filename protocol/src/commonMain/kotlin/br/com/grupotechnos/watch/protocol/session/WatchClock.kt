package br.com.grupotechnos.watch.protocol.session

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * Relógio de parede e fuso do telefone.
 *
 * A sessão usa para acertar a hora do relógio e para converter os horários locais que o relógio grava
 * em instantes reais. Os testes injetam um relógio fixo para que os resultados não dependam da máquina.
 */
public interface WatchClock {
	/** Agora, em milissegundos desde 1970-01-01T00:00Z. */
	public fun nowEpochMillis(): Long

	/** Fuso do telefone no formato IANA (ex.: `"America/Sao_Paulo"`). */
	public fun timeZoneId(): String
}

/** Relógio e fuso reais do sistema. */
public object SystemWatchClock : WatchClock {
	override fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

	override fun timeZoneId(): String = TimeZone.currentSystemDefault().id
}
