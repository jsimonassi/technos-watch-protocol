package br.com.grupotechnos.watch.protocol.profile

/**
 * Família de protocolo. Cada família tem um driver interno próprio.
 */
public enum class Family {
	/** Relógios com firmware SMA (bestmafen). Na Mormaii: fitGPS. */
	SMA,

	/**
	 * Relógios iDO (VeryFit). Na Mormaii: Life Ultra GPS (plataforma SiFli). Protocolo legado de frames de até 20 bytes
	 * sem CRC (`docs/protocolo-ido.md`).
	 */
	IDO,
}

/**
 * Um conjunto serviço + characteristics que o driver sabe usar.
 *
 * Um perfil lista candidatos em ordem de prioridade; a sessão usa o primeiro cujo serviço
 * e characteristics aparecem na descoberta. Todos os UUIDs em 128 bits e minúsculas.
 *
 * @property service serviço GATT.
 * @property write characteristic onde a sessão escreve os frames.
 * @property notify characteristic de onde vêm as notificações do relógio.
 * @property extraNotify outras characteristics de notificação do mesmo serviço, ligadas em ordem depois de [notify] e
 * antes do handshake; as notificações delas também chegam ao driver. Vazio no SMA. No iDO é a de saúde (`0af2`), que
 * a SDK liga logo depois da normal (`docs/protocolo-ido.md`, seção 1). Uma que não apareça na descoberta é pulada,
 * com aviso no log.
 */
public data class GattCandidate(
	val service: String,
	val write: String,
	val notify: String,
	val extraNotify: List<String> = emptyList(),
)

/**
 * Formato do payload de 32 bytes do BIND/LOGIN do SMA.
 */
public enum class BindPayloadKind {
	/** `FF` seguido de 31 zeros, como a SDK Android (`SmaManager.sendBindCmd`). */
	ANDROID_FF,

	/** userId uint32 big-endian seguido de 28 zeros, como a SDK iOS (`SmaBLE bindUserWithUserID:`). */
	USER_ID,
}

/**
 * Como combinar as amostras de passos de um dia num total.
 */
public enum class StepsAggregation {
	/**
	 * Cada amostra é o total de uma hora; o total do dia é a soma das horas, das calorias e da distância.
	 * É o que os dois Cores atuais fazem em produção com o fitGPS (`SMAService.getHistoryPedometer`), o que conta a
	 * mais quando o relógio entrega o acumulado do dia (`docs/decisoes.md`, ADR 1). Fica para um relógio cujo contador
	 * zere a cada hora.
	 */
	HOURLY_SUM,

	/**
	 * Cada amostra é o acumulado do dia; o total do dia é a amostra de maior número de passos, com as calorias e a
	 * distância dela. É o do fitGPS com firmware 0.2.1 (`docs/decisoes.md`, ADR 1).
	 */
	DAY_CUMULATIVE,
}

/**
 * Ajustes de comportamento por modelo. São dados, não código: o mesmo driver serve modelos com quirks diferentes.
 *
 * [fragmentGapTimeoutMs], [bindPayload] e [bindUserId] são só do SMA; o driver iDO os ignora.
 *
 * @property requestMtu MTU ATT pedido depois de habilitar notificações. Até 23 (o MTU padrão), a sessão não pede MTU e
 * usa 23: é o caso do iDO, cuja SDK nunca pede (`docs/protocolo-ido.md`, seção 1).
 * @property maxFragment tamanho máximo de cada escrita; `null` usa `MTU - 3`. Nunca abaixo de 20. O iDO não fragmenta:
 * todo frame legado cabe em 20 bytes.
 * @property connectTimeoutMs tempo máximo para abrir o link no primeiro pareamento (a reconexão não tem timeout).
 * @property commandTimeoutMs tempo máximo de espera por ACK e por resposta de cada comando. A sessão usa o mesmo
 * valor para esperar a habilitação das notificações e o UNBIND do `disconnect(forget = true)`.
 * @property fragmentGapTimeoutMs tempo máximo entre duas notificações do mesmo frame.
 * @property bindTimeoutMs tempo máximo de espera pela resposta do BIND (pode exigir confirmação na tela do relógio). No
 * iDO vale para a resposta do bind (`04/01`) e para a da autenticação (`04/05`), que só chega depois do toque na tela.
 * @property postLoginDelayMs espera depois do LOGIN antes dos comandos automáticos (acerto de hora).
 * @property bindPayload formato do payload do BIND/LOGIN.
 * @property bindUserId userId usado quando [bindPayload] é [BindPayloadKind.USER_ID].
 * @property stepsAggregation como combinar as amostras de passos de um dia num total.
 * @property historyRetentionDays quantos dias de amostras a sessão mantém no `SampleStore`. A poda depois de cada
 * sync usa esta janela, e a mescla de passos já descarta amostras mais velhas que ela ou do futuro (dia depois de
 * hoje e mais de 3 h à frente de agora).
 */
public data class Quirks(
	val requestMtu: Int,
	val maxFragment: Int?,
	val connectTimeoutMs: Long,
	val commandTimeoutMs: Long,
	val fragmentGapTimeoutMs: Long,
	val bindTimeoutMs: Long,
	val postLoginDelayMs: Long,
	val bindPayload: BindPayloadKind,
	val bindUserId: Int,
	val stepsAggregation: StepsAggregation,
	val historyRetentionDays: Int,
)

/**
 * Tudo o que o sistema sabe sobre um modelo de relógio.
 *
 * @property id identificador estável usado pelo host e pelo JavaScript (ex.: `"fitgps"`, `"lifeultragps"`).
 * @property bleNames nomes de advertising que identificam o modelo, comparados de forma exata.
 * @property appName nome que o app Mormaii espera em `onScannedDevice` (ex.: `"fitGPS"`).
 * @property slug identificador do modelo no backend (`SMART_WATCH_SLUG` no app).
 * @property family família de protocolo; decide qual driver interno a sessão usa.
 * @property gattCandidates serviços que o driver sabe usar, em ordem de prioridade.
 * @property quirks ajustes de comportamento do modelo (timeouts, MTU, payload do BIND, agregação, retenção).
 */
public data class DeviceProfile(
	val id: String,
	val bleNames: List<String>,
	val appName: String,
	val slug: String,
	val family: Family,
	val gattCandidates: List<GattCandidate>,
	val quirks: Quirks,
)
