package br.com.grupotechnos.watch.protocol.driver

import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.StepsSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Costura entre a sessão genérica e o protocolo de uma família.
 *
 * A sessão não sabe o que é um BIND nem um CRC: só chama estes métodos. Hoje há três implementações com codec próprio
 * sobre o [DriverLink]: o `SmaDriver` (fitGPS), o `IdoDriver` (Life Ultra GPS) e o `CrpDriver` (Condor Reebok). No futuro, um driver
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

	/**
	 * Começa a consumir `link.notifications` (e, no CRP, `link.sideNotifications`) dentro de [scope] (que a sessão
	 * cancela quando o link cai).
	 */
	fun start(scope: CoroutineScope)

	/**
	 * Resumo legível de um pacote cru, para log (ex.: `"seq=3 02/01 SET_TIME len=4"`).
	 * Retorna vazio para pacotes que são continuação de um frame.
	 */
	fun describe(value: ByteArray): String

	/**
	 * Resumo de um pacote de [characteristic] (TX, RX ou READ). O padrão ignora a characteristic e chama [describe]; o CRP
	 * descreve as leituras e as notificações laterais (`fee1`, `2a19`, `2a37`) pelo conteúdo de cada uma.
	 */
	fun describe(characteristic: String, value: ByteArray): String = describe(value)

	/**
	 * Handshake do protocolo. Suspende até o relógio aceitar ou lança `WatchException(HANDSHAKE_FAILED | TIMEOUT)`.
	 *
	 * @param onPhase chamado a cada subetapa (no SMA: `"BIND"`, `"LOGIN"`; no iDO: `"BIND"` no primeiro pareamento e
	 * `"LOGIN"` na reconexão; no CRP: `"VERSION"` nos dois modos), só para log e UI.
	 */
	suspend fun handshake(mode: HandshakeMode, onPhase: (String) -> Unit)

	/** Versão do firmware e da pilha BLE (no SMA: `02/0A` → `02/0B`). */
	suspend fun readDeviceInfo(): DriverDeviceInfo

	/** Percentual de bateria, de 0 a 100. */
	suspend fun readBattery(): Int

	/**
	 * Bateria com o estado de carga. O padrão chama [readBattery] e devolve `charging = false` (SMA e iDO); o CRP aplica a
	 * regra do Core para leituras de 2A19 acima de 100.
	 */
	suspend fun readBatteryStatus(): DriverBattery = DriverBattery(readBattery(), charging = false)

	/** Acerta a hora do relógio. [nowEpochMillis] e [timeZoneId] vêm do relógio do telefone. */
	suspend fun setTime(nowEpochMillis: Long, timeZoneId: String)

	/** Pede ao relógio que avise quando os dados mudarem. Sem efeito se a família não tiver o recurso. */
	suspend fun enableDataChangeNotices()

	/**
	 * Lê todo o histórico de passos disponível, paginando até o fim.
	 * Retorna amostras já agrupadas como a família define (no SMA: uma por hora, último registro da hora prevalece; no
	 * fitGPS 0.2.1, com o acumulado do dia).
	 */
	suspend fun readSteps(): List<StepsSample>

	/** Lê o histórico de sono e entrega as sessões já analisadas. Lista vazia quando não há registros. */
	suspend fun readSleep(): List<SleepSession>

	/** Desfaz o vínculo no relógio (no SMA: UNBIND; no iDO os bytes não são conhecidos e o driver falha sem escrever). */
	suspend fun unbind()

	/** Libera recursos e falha qualquer espera pendente. Idempotente. */
	fun close()
}

/** Tipo de handshake. */
internal enum class HandshakeMode {
	/** Primeiro pareamento: no SMA, BIND seguido de LOGIN; no iDO, informação básica, bind e autenticação. */
	FIRST_PAIR,

	/** Relógio já vinculado: no SMA, só LOGIN; no iDO, só a informação básica (o relógio tem de se dizer vinculado). */
	RECONNECT,
}

/** Identificação lida do relógio. */
internal data class DriverDeviceInfo(
	val firmware: String,
	val bleVersion: String?,
)

/**
 * Bateria lida ou avisada pelo relógio.
 *
 * @property percent percentual de 0 a 100.
 * @property charging `true` quando o relógio informa carga (no CRP, leitura de 2A19 acima de 100).
 */
internal data class DriverBattery(val percent: Int, val charging: Boolean)

/** Avisos espontâneos do relógio. */
internal sealed interface DriverNotice {
	/**
	 * Os dados de atividade mudaram; vale ler os passos de novo (no SMA: `02/70`; no CRP, uma notificação de FEE1 sem o
	 * estado das meias horas desta conexão ou com o dia mudado).
	 */
	data object DataChanged : DriverNotice

	/** O relógio informou o estado do link (no SMA: `04/62`). */
	data class LinkReported(val connected: Boolean) : DriverNotice

	/** Bateria que o relógio mandou sozinho (CRP: notificação de 2A19, uma por minuto no P85). */
	data class BatteryReported(val battery: DriverBattery) : DriverNotice

	/**
	 * Amostras novas montadas pelo driver (CRP: notificação de FEE1, a meia hora atual e a amostra de totais do dia); a
	 * sessão mescla no histórico e emite `onLiveSteps`, no máximo uma vez a cada `SessionEngine.LIVE_STEPS_THROTTLE_MS`.
	 */
	data class StepsUpdated(val samples: List<StepsSample>) : DriverNotice
}
