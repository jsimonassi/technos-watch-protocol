package br.com.grupotechnos.watch.protocol.sma.codec

/**
 * Contador de sequence id dos frames que o telefone envia (`docs/protocolo-sma.md`, seção 4).
 *
 * Começa em 1 e incrementa a cada frame de dados, como o `mSerialNum` da SDK Android
 * (`SmaBleHelper.java:5, 18-19, 27`). Só os 16 bits baixos vão no frame, então depois de `0xFFFF` vem `0x0000`,
 * igual ao que a SDK Android produz quando o `int` passa de 65535. A SDK iOS começa em 0
 * (`SmaBusinessTool.m:12`); o relógio aceita os dois, porque nenhuma das SDKs nem o firmware (até onde se
 * observa) valida o seq: ele só é copiado no ACK.
 *
 * As retentativas do `SmaDriver` pegam um seq novo daqui. É escolha desta biblioteca, não paridade: a fila iOS
 * reenvia os mesmos bytes, com o mesmo seq (`SmaBusinessTool.m:118-126` grava o seq ao montar o comando;
 * `SmaBLE.m:3302-3337` reenvia o `NSData` guardado), e a Android não reenvia (`SmaMessenger.java:36-96`). Não se sabe
 * se o firmware descarta repetições pelo seq (hipótese H9 de `docs/protocolo-sma.md`, seção 8).
 *
 * @param first valor do primeiro [next]; só os testes mudam.
 */
internal class SmaSequence(first: Int = 1) {
	private var nextValue = first and 0xFFFF

	/** Próximo sequence id (0..0xFFFF). */
	fun next(): Int {
		val value = nextValue
		nextValue = (nextValue + 1) and 0xFFFF
		return value
	}
}
