package br.com.grupotechnos.watch.protocol.domain

/**
 * Estado da conexão com o relógio, do ponto de vista da [br.com.grupotechnos.watch.protocol.session.WatchSession].
 *
 * O host traduz esses estados para o que a camada de cima espera. No Core React Native, por exemplo,
 * `CONNECTING`, `CONFIGURING` e `HANDSHAKING` viram o código 1 de `deviceConnectStates`, e `READY` e `SYNCING` viram 2.
 * As transições estão no diagrama de `docs/arquitetura.md`, seção 5.
 */
public enum class ConnectionState {
	/** Sem conexão e sem tentativa em andamento. */
	DISCONNECTED,

	/**
	 * Primeiro pareamento: `Transport.connect(autoReconnect = false)` foi chamado e a sessão aguarda `onConnected`
	 * por até `Quirks.connectTimeoutMs`. Uma reconexão espera o link em [RECONNECTING], não aqui.
	 */
	CONNECTING,

	/** Link GATT aberto: descoberta de serviços, habilitação de notificações e negociação de MTU. */
	CONFIGURING,

	/** Handshake do protocolo da família (no SMA: BIND e LOGIN). */
	HANDSHAKING,

	/** Handshake concluído; comandos são aceitos. */
	READY,

	/** Sincronização de histórico em andamento; outros comandos ficam na fila. */
	SYNCING,

	/**
	 * Esperando o link de uma reconexão: depois de `WatchSession.reconnect`, de uma queda com o relógio já pareado,
	 * de uma tentativa de reconexão que falhou (com espera crescente entre as tentativas) ou do Bluetooth religado.
	 * O host mantém uma conexão pendente, sem timeout, até o relógio voltar (`connect(autoReconnect = true)`).
	 * Quando o link abre, a sessão passa por [CONFIGURING] e [HANDSHAKING] como no primeiro pareamento.
	 */
	RECONNECTING,

	/** O Bluetooth do telefone está desligado. */
	BLUETOOTH_OFF,
}
