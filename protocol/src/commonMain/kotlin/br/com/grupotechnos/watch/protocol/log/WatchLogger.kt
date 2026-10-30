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
 * **Threads.** A implementação tem de aceitar chamadas de qualquer thread, inclusive ao mesmo tempo, e não deve
 * bloquear nem lançar. Um logger com estado (anel em memória, arquivo) precisa de trava ou de fila própria.
 * - [frame] e quase todo [log] rodam na thread da sessão (a mesma dos callbacks do `WatchSessionListener`).
 * - `WatchSession.getStepsDays`, `getSleepSessions` e `confirmUploaded` chamam [log] na thread de quem os chamou, antes
 *   de retornar (dias anteriores à janela pedida, dia em formato inválido); o construtor da `WatchSession` também,
 *   quando o `SampleStore` falha ao carregar. Essas chamadas podem coincidir com as da thread da sessão.
 * - Depois de `WatchSession.close`, um evento que o host ainda entrega (a resposta ao `disconnect` do próprio `close`,
 *   uma notificação atrasada) é descartado com um [log] em DEBUG (`Session/post`) na thread de quem o entregou, porque
 *   a thread da sessão já não existe.
 *
 * **Exceções.** O logger não deve lançar. Se lançar, a sessão engole a exceção nas linhas do próprio log da sessão e
 * do histórico local: ela nunca escapa de `getStepsDays`, `getSleepSessions`, `confirmUploaded` nem do construtor da
 * `WatchSession`, e não interrompe mescla, poda nem gravação no `SampleStore`. No link, no driver e no sync, que chamam
 * o logger direto na thread da sessão, ela faz a operação em andamento falhar: um [frame] que lança perde a notificação
 * recebida (RX) ou falha o comando (TX). Ver `docs/arquitetura.md`, seção 4.
 *
 * No Swift, o `value` de [frame] chega como `KotlinByteArray`; `value.toData()` (pacote
 * `br.com.grupotechnos.watch.protocol.ios`) o copia para `Data`.
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
