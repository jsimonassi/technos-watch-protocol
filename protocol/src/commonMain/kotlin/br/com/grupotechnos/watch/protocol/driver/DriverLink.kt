package br.com.grupotechnos.watch.protocol.driver

import br.com.grupotechnos.watch.protocol.log.WatchLogger
import br.com.grupotechnos.watch.protocol.profile.DeviceProfile
import br.com.grupotechnos.watch.protocol.profile.GattCandidate
import br.com.grupotechnos.watch.protocol.session.WatchClock
import br.com.grupotechnos.watch.protocol.transport.WriteType
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Uma notificação de uma characteristic de `GattCandidate.sideNotify`, com a origem (no CRP: `fee1`, `2a19` ou `2a37`).
 *
 * @property characteristic UUID normalizado (128 bits, minúsculas) da characteristic que notificou.
 * @property value bytes da notificação, como chegaram.
 */
internal class CharacteristicNotification(val characteristic: String, val value: ByteArray)

/**
 * O que a sessão entrega a um driver com codec próprio: um link GATT já configurado.
 *
 * A sessão cria um [DriverLink] por conexão, depois de descobrir os serviços, escolher o [GattCandidate],
 * ligar as notificações e negociar o MTU. Quando o link cai, a sessão cancela o escopo do driver e o
 * [notifications] é fechado.
 *
 * Garantias da implementação da sessão:
 * - [write] e [read] são serializados: duas chamadas concorrentes nunca viram duas operações GATT simultâneas.
 * - Todo pacote escrito, notificado e lido é registrado em `WatchLogger.frame` (TX, RX e READ), com
 *   `DeviceDriver.describe(characteristic, value)` como resumo.
 * - Falha de transporte vira `WatchException(TRANSPORT)`; queda do link vira `WatchException(DISCONNECTED)`
 *   e cancela a corrotina que estava suspensa.
 */
internal interface DriverLink {
	/** Perfil do relógio desta conexão; o driver lê os `Quirks` daqui. */
	val profile: DeviceProfile

	/** Candidato GATT escolhido para esta conexão. */
	val gatt: GattCandidate

	/** MTU ATT efetivo (23 quando não foi negociado). O payload útil de uma escrita é `mtu - 3`. */
	val mtu: Int

	/** Relógio e fuso do telefone, para acertar a hora e converter os horários locais que o relógio grava. */
	val clock: WatchClock

	/** Logger do host; o driver registra com tags `SmaDriver/metodo` ou `IdoDriver/metodo`. */
	val logger: WatchLogger

	/**
	 * Notificações de `gatt.notify` e de `gatt.extraNotify` (no iDO, `0AF7` e `0AF2`, como a SDK, que entrega as duas
	 * ao mesmo decodificador), uma por pacote, na ordem de chegada. Um único consumidor.
	 */
	val notifications: ReceiveChannel<ByteArray>

	/**
	 * Notificações das characteristics de `gatt.sideNotify` (no CRP: `fee1`, `2a19` e `2a37`), com a origem, na ordem de
	 * chegada. Um único consumidor. Vazio (e fechado com o link) nas famílias sem `sideNotify`.
	 */
	val sideNotifications: ReceiveChannel<CharacteristicNotification>

	/** Escreve um pacote na characteristic `gatt.write` e suspende até o host confirmar a escrita. */
	suspend fun write(value: ByteArray, type: WriteType = WriteType.WITH_RESPONSE)

	/** Lê uma characteristic qualquer (ex.: Device Information Service). */
	suspend fun read(service: String, characteristic: String): ByteArray
}
