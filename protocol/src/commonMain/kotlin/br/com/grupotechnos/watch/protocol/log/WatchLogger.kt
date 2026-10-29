package br.com.grupotechnos.watch.protocol.log

/** Nível de log. */
public enum class LogLevel {
	/** Detalhe para investigar um problema (ex.: evento ignorado, ACK com seq diferente). */
	DEBUG,

	/** Marco normal (ex.: transição de estado, comando concluído). */
	INFO,

	/** Algo inesperado que a sessão contornou (ex.: MTU sem resposta, frame corrompido, comando interno que falhou). */
	WARN,

	/** Falha que não deveria acontecer (ex.: exceção do host ou do listener, exceção não tratada na sessão). */
	ERROR,
}

/** Sentido de um pacote no link: do telefone para o relógio ([TX]) ou do relógio para o telefone ([RX]). */
public enum class FrameDirection {
	/** Escrita do telefone na characteristic de escrita. */
	TX,

	/** Notificação do relógio na characteristic de notificação. */
	RX,
}

/**
 * Saída de log implementada pelo host (Logcat, `os_log`, tela de log do app de exemplo).
 *
 * É chamada na thread da sessão; o host não deve bloquear aqui.
 */
public interface WatchLogger {
	/**
	 * Mensagem de diagnóstico.
	 *
	 * @param tag origem no formato `Classe/metodo` (mesma convenção do `LogHelpers` do Core atual).
	 */
	public fun log(level: LogLevel, tag: String, message: String)

	/**
	 * Um pacote GATT cru, exatamente como foi escrito ou notificado (um fragmento, não um frame remontado).
	 *
	 * É a fonte para gravar fixtures: o host pode repassar estes pacotes para [FrameJsonl.line]. Chega uma vez por
	 * pacote de uma conexão da sessão; uma notificação que a sessão descarta por não ter conexão para recebê-la (depois
	 * do fim da conexão ou em outra characteristic) vai só para [log], em DEBUG.
	 *
	 * @param characteristic UUID normalizado da characteristic.
	 * @param summary descrição legível dada pelo driver (ex.: `"seq=3 02/01 SET_TIME len=4 crc=ok"`),
	 * ou vazia quando o pacote é continuação de um frame.
	 */
	public fun frame(direction: FrameDirection, characteristic: String, value: ByteArray, summary: String)
}

/** Logger que descarta tudo. */
public object NoopWatchLogger : WatchLogger {
	override fun log(level: LogLevel, tag: String, message: String) {}

	override fun frame(direction: FrameDirection, characteristic: String, value: ByteArray, summary: String) {}
}
