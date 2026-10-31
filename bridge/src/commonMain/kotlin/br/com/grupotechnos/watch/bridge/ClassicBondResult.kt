package br.com.grupotechnos.watch.bridge

/**
 * Resultado de um pedido de vínculo Bluetooth clássico (BR/EDR) que o [CoreBridge] fez ao host com
 * [CoreHost.requestClassicBond]; o host o devolve por [CoreBridge.onClassicBondResult].
 *
 * Só os relógios cujo perfil tem `Quirks.classicBond` pedem o vínculo (hoje, o Condor Reebok, que usa HFP e A2DP pelo
 * clássico; HCI de 2026-10-31, 15:43:15-18: SSP NoInputNoOutput com diálogo de consentimento do Android e link key tipo
 * 4). No Swift os casos aparecem como `ClassicBondResult.bonded`, `.failed` e `.unsupported`.
 */
public enum class ClassicBondResult {
	/** O sistema tem o vínculo clássico com o relógio (acabou de ser criado, ou já existia). Só vai para o log. */
	BONDED,

	/**
	 * O vínculo não saiu: o usuário recusou o diálogo do sistema, as tentativas do host acabaram (no Android, duas, com
	 * 45 s cada) ou o sistema recusou o pedido. O [CoreBridge] emite `onError` com `CLASSIC_BOND_FAILED`
	 * ([HostErrorCodes.CLASSIC_BOND_FAILED]) e não pede de novo até o próximo `pairDevice`.
	 */
	FAILED,

	/**
	 * A plataforma não deixa o app criar o vínculo clássico (iOS: o usuário pareia as chamadas em Ajustes > Bluetooth).
	 * O [CoreBridge] só registra um WARN com a mensagem do host, sem `onError`, e não pede de novo até o próximo
	 * `pairDevice`.
	 */
	UNSUPPORTED,
}
