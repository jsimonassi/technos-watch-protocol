package br.com.grupotechnos.watch.protocol.ido

/**
 * Catálogo dos comandos legados iDO que o driver usa (`docs/protocolo-ido.md`, seção 3). A resposta de cada um repete o
 * mesmo `cmd/key`. Fonte de cada par: o log da SDK iDO 2.69.38 com o Life Ultra GPS (`20261029.log`, linhas citadas).
 *
 * @property cmd primeiro byte do frame.
 * @property key segundo byte do frame.
 * @property retryable `true` quando reenviar depois de um timeout não muda nada no relógio (leituras e acerto de hora);
 * a SDK reenvia os comandos da fila até 3 vezes. O bind e a autenticação nunca são reenviados: repetir o `04/01`
 * recomeçaria o vínculo, e o `04/05` só responde depois do toque na tela.
 */
internal enum class IdoCommand(val cmd: Int, val key: Int, val retryable: Boolean) {
	/** `02/01`: informação básica (id, firmware, bateria, vínculo, plataforma). Linhas 88-95. */
	GET_BASIC_INFO(0x02, 0x01, retryable = true),

	/** `02/EB`: versão do firmware (três números) e da pilha BT3. Linhas 5774-5776. */
	GET_FIRMWARE(0x02, 0xEB, retryable = true),

	/** `02/A0`: dados ao vivo, com os totais do dia (passos, calorias totais, distância). Linhas 5833-5835. */
	GET_LIVE_DATA(0x02, 0xA0, retryable = true),

	/** `03/01`: acerta data, hora, dia da semana e fuso. Linhas 5360-5362. */
	SET_TIME(0x03, 0x01, retryable = true),

	/** `04/01`: pedido de vínculo; a resposta traz o desafio da autenticação. Linhas 144-146. */
	BIND(0x04, 0x01, retryable = false),

	/** `04/05`: resposta ao desafio; o relógio responde depois que a pessoa confirma na tela. Linhas 154-158. */
	BIND_AUTH(0x04, 0x05, retryable = false),
	;

	companion object {
		/** Comando do catálogo com este `cmd/key`, ou `null`. */
		fun byCmdKey(cmd: Int, key: Int): IdoCommand? = entries.firstOrNull { it.cmd == cmd && it.key == key }

		/** `"02/01 GET_BASIC_INFO"`, ou `"07/01 UNKNOWN"` fora do catálogo. */
		fun label(cmd: Int, key: Int): String = IdoFrameCodec.cmdKey(cmd, key) + " " + (byCmdKey(cmd, key)?.name ?: "UNKNOWN")
	}
}
