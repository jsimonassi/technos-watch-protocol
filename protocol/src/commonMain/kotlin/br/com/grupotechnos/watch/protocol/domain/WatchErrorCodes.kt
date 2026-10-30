package br.com.grupotechnos.watch.protocol.domain

/**
 * Códigos de erro entregues em `WatchSessionListener.onError`.
 *
 * São strings estáveis: o host pode repassá-las ao JavaScript sem tradução. Quem produz cada código está na
 * tabela de `docs/arquitetura.md`, seção 8.
 */
public object WatchErrorCodes {
	/**
	 * O comando exige `READY` ou `SYNCING` e a sessão está em outro estado (ou a conexão está sendo encerrada).
	 * Também é o código do driver para um comando pedido antes do LOGIN.
	 */
	public const val NOT_READY: String = "NOT_READY"

	/** O `profileId` não existe no registro de perfis. */
	public const val UNSUPPORTED_DEVICE: String = "UNSUPPORTED_DEVICE"

	/** O relógio não apareceu dentro do tempo de conexão do primeiro pareamento. */
	public const val CONNECT_TIMEOUT: String = "CONNECT_TIMEOUT"

	/** Nenhum serviço GATT esperado pelo perfil foi encontrado. */
	public const val GATT_NOT_SUPPORTED: String = "GATT_NOT_SUPPORTED"

	/**
	 * O handshake do primeiro pareamento falhou (no SMA: BIND ou LOGIN recusados, sem resposta ou com frame inválido;
	 * o código original vai na mensagem). Numa reconexão, só a recusa do LOGIN chega ao host com este código (a sessão
	 * desiste e não refaz o BIND, ADR 11); falta de resposta numa reconexão não chega: a sessão tenta de novo.
	 */
	public const val HANDSHAKE_FAILED: String = "HANDSHAKE_FAILED"

	/** Um comando ficou sem ACK ou sem resposta dentro do tempo do perfil. */
	public const val TIMEOUT: String = "TIMEOUT"

	/** Frame recebido com CRC ou estrutura inválidos, NACK numa leitura, ou erro inesperado dentro do driver. */
	public const val PROTOCOL: String = "PROTOCOL"

	/**
	 * Reservado para o host: a `WatchSession` não emite este código, porque uma leitura sem registros não é erro
	 * (sono vazio só vai para o log, `docs/decisoes.md`, ADR 9). O Core React Native usa quando não há nada
	 * gravado para responder (ex.: `getHistorySleep` sem nenhuma noite).
	 */
	public const val NO_DATA: String = "NO_DATA"

	/**
	 * A operação GATT falhou no host (status diferente de sucesso) ou não foi confirmada a tempo; também é o código de
	 * `pair`/`reconnect` com o Bluetooth desligado.
	 */
	public const val TRANSPORT: String = "TRANSPORT"

	/** O link caiu com o comando em andamento ou na fila. */
	public const val DISCONNECTED: String = "DISCONNECTED"

	/**
	 * O telefone guarda um vínculo Bluetooth do sistema que o relógio já apagou (o host entregou
	 * `GattStatus.PAIRING_REMOVED`; no iOS, `CBError.peerRemovedPairingInformation`). O sistema recusa a conexão até o
	 * usuário esquecer o relógio nas configurações de Bluetooth do telefone; o app não consegue remover o vínculo. Vale
	 * para `pair` e `reconnect` em qualquer etapa: a sessão vai para `DISCONNECTED` e não tenta de novo sozinha, nem ao
	 * religar o Bluetooth. Sem `pair`/`reconnect` pendente (a reconexão que a própria sessão começou depois de uma
	 * queda), o erro sai com `WatchSession.NO_REQUEST_ID`, para o host saber por que a reconexão parou
	 * (`docs/decisoes.md`, ADR 23).
	 */
	public const val PAIRING_REMOVED: String = "PAIRING_REMOVED"

	/** O pedido foi cancelado por `disconnect`, por `close` ou por um `pair`/`reconnect` posterior que o substituiu. */
	public const val CANCELLED: String = "CANCELLED"

	/**
	 * Reservado: a `WatchSession` não emite este código. Um `pair`/`reconnect` com outra conexão em andamento derruba
	 * a anterior (que termina com [CANCELLED]) em vez de recusar (`docs/decisoes.md`, ADR 13).
	 */
	public const val BUSY: String = "BUSY"
}
