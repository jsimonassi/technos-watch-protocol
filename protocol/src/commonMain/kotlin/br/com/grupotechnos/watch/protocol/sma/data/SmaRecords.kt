package br.com.grupotechnos.watch.protocol.sma.data

import br.com.grupotechnos.watch.protocol.util.ByteReader
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Época dos registros de histórico SMA: segundos desde 2000-01-01 00:00 no **horário local de parede** do relógio
 * (`docs/sma-dados.md`, seção 1).
 *
 * A SDK Android soma `MS = 946684800000` (2000-01-01T00:00Z) e formata em GMT (`SmaManager.java:87, 791-795`;
 * `SmaBleUtils.getDefaultTimeZone`, `SmaBleUtils.java:16-18`); a iOS faz o mesmo com um formatter em GMT
 * (`SmaBLE.m:2677-2702`). Ou seja, as duas tratam o número como um relógio de parede sem fuso. O Core Android
 * ainda subtrai `getRawOffset()` (`SMAService.java:420`), o que ignora o horário de verão; aqui o horário de parede
 * é convertido para instante com o fuso do telefone (`TimeFormat.fromLocalWallClock`), que leva o horário de verão
 * em conta.
 */
internal object SmaLocalTime {
	/** 2000-01-01T00:00Z em segundos Unix (`SmaManager.java:87`, em milissegundos). */
	const val EPOCH_2000_UNIX_SECONDS: Long = 946_684_800L

	/** Horário de parede local de um timestamp SMA. */
	fun toLocalWallClock(secondsSince2000: Long): LocalDateTime =
		Instant.fromEpochSeconds(EPOCH_2000_UNIX_SECONDS + secondsSince2000).toLocalDateTime(TimeZone.UTC)

	/** Timestamp SMA de um horário de parede local (inverso de [toLocalWallClock]). */
	fun toSecondsSince2000(local: LocalDateTime): Long =
		local.toInstant(TimeZone.UTC).epochSeconds - EPOCH_2000_UNIX_SECONDS

	/**
	 * O mesmo número na escala que a SDK usa internamente (`SmaSleep.time`, `SmaSport.time`): milissegundos
	 * "como se fosse UTC". O analisador de sono trabalha nessa escala para reproduzir a aritmética da SDK.
	 */
	fun toLocalMillis(secondsSince2000: Long): Long = (EPOCH_2000_UNIX_SECONDS + secondsSince2000) * 1000L

	/** Horário de parede de um valor em [toLocalMillis]. */
	fun localMillisToWallClock(localMillis: Long): LocalDateTime =
		Instant.fromEpochMilliseconds(localMillis).toLocalDateTime(TimeZone.UTC)
}

/**
 * Um registro do histórico de atividade `05/42` (`docs/sma-dados.md`, seção 1).
 *
 * Layout de 8 bytes (`SmaManager.java:783-797`; `SmaBLE.m:2698-2701`):
 * `[0..3]` timestamp uint32 BE ([SmaLocalTime]) · `[4]` mode · `[5..7]` passos uint24 BE.
 *
 * @property secondsSince2000 timestamp cru: segundos desde 2000-01-01 00:00 no horário local de parede ([SmaLocalTime]).
 * @property mode [SmaSportMode]; 0 é a amostra normal de passos.
 * @property steps passos do registro (uint24).
 */
internal data class SmaSportRecord(
	val secondsSince2000: Long,
	val mode: Int,
	val steps: Int,
) {
	companion object {
		/** Tamanho de cada registro. */
		const val SIZE: Int = 8
	}
}

/**
 * Um registro do histórico de sono `05/46` (`docs/sma-dados.md`, seção 3).
 *
 * Layout de 7 bytes (`SmaManager.java:815-831`; `SmaBLE.m:2926-2931`):
 * `[0..3]` timestamp uint32 BE ([SmaLocalTime]) · `[4]` mode · `[5]` soft · `[6]` strong.
 *
 * @property secondsSince2000 timestamp cru: segundos desde 2000-01-01 00:00 no horário local de parede ([SmaLocalTime]).
 * @property mode [SmaSleepMode].
 * @property soft contagem de movimentos leves na janela (a SDK iOS chama de `SOFTLY`).
 * @property strong contagem de movimentos fortes na janela.
 */
internal data class SmaSleepRecord(
	val secondsSince2000: Long,
	val mode: Int,
	val soft: Int,
	val strong: Int,
) {
	companion object {
		/** Tamanho de cada registro. */
		const val SIZE: Int = 7
	}
}

/**
 * Valores de `mode` dos registros de atividade (`SmaSport.Mode`, `SmaSport.java:75-121`).
 *
 * Só [NORMAL] vira amostra de passos; os outros são ignorados, como no Core, que só olha 0, [START] e [END]
 * (`SMAService.java:421-449`).
 */
internal object SmaSportMode {
	/** Amostra normal do pedômetro; é a única que vira [br.com.grupotechnos.watch.protocol.domain.StepsSample]. */
	const val NORMAL: Int = 0

	/** Parado. */
	const val SIT: Int = 16

	/** Caminhando. */
	const val WALK: Int = 17

	/** Correndo. */
	const val RUN: Int = 18

	/** Início de exercício (o Core usa para calcular a atividade por diferença de passos, `SMAService.java:422-436`). */
	const val START: Int = 32

	/** Exercício em andamento. */
	const val GOING: Int = 34

	/** Fim de exercício. */
	const val END: Int = 47
}

/** Valores de `mode` dos registros de sono (`SmaSleep.Mode`, `SmaSleep.java:129-135`). */
internal object SmaSleepMode {
	/** Sono profundo; vira [br.com.grupotechnos.watch.protocol.domain.SleepStageType.DEEP]. */
	const val DEEP: Int = 1

	/** Sono leve; vira [br.com.grupotechnos.watch.protocol.domain.SleepStageType.LIGHT]. */
	const val LIGHT: Int = 2

	/** Acordado; vira [br.com.grupotechnos.watch.protocol.domain.SleepStageType.AWAKE]. */
	const val AWAKE: Int = 3

	/** Adormeceu. */
	const val START: Int = 17

	/** Acordou. */
	const val END: Int = 34
}

/**
 * Decodificação das respostas de histórico.
 *
 * O número de registros é `len(value) / tamanho`, com divisão inteira, como nas duas SDKs
 * (`SmaManager.java:784, 816`; `SmaBLE.m:2698, 2926`). Bytes que sobram (value que não é múltiplo do tamanho)
 * são ignorados e contados em [Decoded.trailingBytes] para o driver registrar no log.
 */
internal object SmaRecordCodec {
	/** Máximo de registros por resposta de `05/42` e `05/46` (`SmaManager.getMaxItem`, `SmaManager.java:1444-1450`). */
	const val MAX_RECORDS_PER_PAGE: Int = 20

	/** Registros decodificados de um value. */
	data class Decoded<T>(val records: List<T>, val trailingBytes: Int)

	/** Registros de `05/42`. */
	fun decodeSport(value: ByteArray): Decoded<SmaSportRecord> {
		val count = value.size / SmaSportRecord.SIZE
		val reader = ByteReader(value)
		val records = List(count) {
			val seconds = reader.u32()
			val mode = reader.u8()
			val steps = reader.u24()
			SmaSportRecord(seconds, mode, steps)
		}
		return Decoded(records, value.size - count * SmaSportRecord.SIZE)
	}

	/** Registros de `05/46`. */
	fun decodeSleep(value: ByteArray): Decoded<SmaSleepRecord> {
		val count = value.size / SmaSleepRecord.SIZE
		val reader = ByteReader(value)
		val records = List(count) {
			val seconds = reader.u32()
			val mode = reader.u8()
			val soft = reader.u8()
			val strong = reader.u8()
			SmaSleepRecord(seconds, mode, soft, strong)
		}
		return Decoded(records, value.size - count * SmaSleepRecord.SIZE)
	}
}
