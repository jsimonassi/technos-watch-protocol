package br.com.grupotechnos.watch.protocol.driver

import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.StepsSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Costura entre a sessão genérica e o protocolo de uma família.
 *
 * A sessão não sabe o que é um BIND nem um CRC: só chama estes métodos. Hoje há uma implementação,
 * o `SmaDriver`, que fala o protocolo com codec próprio sobre o [DriverLink]. No futuro, um driver
 * adaptador pode falar com a SDK de um fabricante e ignorar o link (ver [br.com.grupotechnos.watch.protocol.driver.HostDriver]).
 *
 * Contrato:
 * - Um driver vive o tempo de uma conexão. A sessão chama [start] uma vez, logo depois de criá-lo, e [close] quando o link cai.
 * - A sessão chama os métodos `suspend` um de cada vez, nunca em paralelo.
 * - Erros saem como `WatchException` com um código de `WatchErrorCodes`; qualquer outra exceção é tratada como `PROTOCOL`.
 * - Datas em epoch millis reais. Horários locais do relógio são convertidos com `link.clock.timeZoneId()`.
 */
internal interface DeviceDriver {
	/** Avisos que o relógio manda sem ter sido perguntado. Coletado pela sessão enquanto a conexão existir. */
	val notices: Flow<DriverNotice>

	/** Começa a consumir `link.notifications` dentro de [scope] (que a sessão cancela quando o link cai). */
	fun start(scope: CoroutineScope)

	/**
	 * Resumo legível de um pacote cru, para log (ex.: `"seq=3 02/01 SET_TIME len=4"`).
	 * Retorna vazio para pacotes que são continuação de um frame.
	 */
	fun describe(value: ByteArray): String

	/**
	 * Handshake do protocolo. Suspende até o relógio aceitar ou lança `WatchException(HANDSHAKE_FAILED | TIMEOUT)`.
	 *
	 * @param onPhase chamado a cada subetapa (no SMA: `"BIND"`, `"LOGIN"`), só para log e UI.
	 */
	suspend fun handshake(mode: HandshakeMode, onPhase: (String) -> Unit)

	/** Versão do firmware e da pilha BLE (no SMA: `02/0A` → `02/0B`). */
	suspend fun readDeviceInfo(): DriverDeviceInfo

	/** Percentual de bateria, de 0 a 100. */
	suspend fun readBattery(): Int

	/** Acerta a hora do relógio. [nowEpochMillis] e [timeZoneId] vêm do relógio do telefone. */
	suspend fun setTime(nowEpochMillis: Long, timeZoneId: String)

	/** Pede ao relógio que avise quando os dados mudarem. Sem efeito se a família não tiver o recurso. */
	suspend fun enableDataChangeNotices()

	/**
	 * Lê todo o histórico de passos disponível, paginando até o fim.
	 * Retorna amostras já agrupadas como a família define (no SMA: uma por hora, último registro da hora prevalece).
	 */
	suspend fun readSteps(): List<StepsSample>

	/** Lê o histórico de sono e entrega as sessões já analisadas. Lista vazia quando não há registros. */
	suspend fun readSleep(): List<SleepSession>

	/** Desfaz o vínculo no relógio (no SMA: UNBIND). */
	suspend fun unbind()

	/** Libera recursos e falha qualquer espera pendente. Idempotente. */
	fun close()
}

/** Tipo de handshake. */
internal enum class HandshakeMode {
	/** Primeiro pareamento: no SMA, BIND seguido de LOGIN. */
	FIRST_PAIR,

	/** Relógio já vinculado: no SMA, só LOGIN. */
	RECONNECT,
}

/** Identificação lida do relógio. */
internal data class DriverDeviceInfo(
	val firmware: String,
	val bleVersion: String?,
)

/** Avisos espontâneos do relógio. */
internal sealed interface DriverNotice {
	/** Os dados de atividade mudaram; vale ler os passos de novo (no SMA: `02/70`). */
	data object DataChanged : DriverNotice

	/** O relógio informou o estado do link (no SMA: `04/62`). */
	data class LinkReported(val connected: Boolean) : DriverNotice
}
