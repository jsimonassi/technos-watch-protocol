package br.com.grupotechnos.watch.protocol.driver

import br.com.grupotechnos.watch.protocol.log.WatchLogger
import br.com.grupotechnos.watch.protocol.profile.DeviceProfile
import br.com.grupotechnos.watch.protocol.profile.GattCandidate
import br.com.grupotechnos.watch.protocol.session.WatchClock
import br.com.grupotechnos.watch.protocol.transport.WriteType
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * O que a sessão entrega a um driver com codec próprio: um link GATT já configurado.
 *
 * A sessão cria um [DriverLink] por conexão, depois de descobrir os serviços, escolher o [GattCandidate],
 * ligar as notificações e negociar o MTU. Quando o link cai, a sessão cancela o escopo do driver e o
 * [notifications] é fechado.
 *
 * Garantias da implementação da sessão:
 * - [write] e [read] são serializados: duas chamadas concorrentes nunca viram duas operações GATT simultâneas.
 * - Todo pacote escrito e notificado é registrado em `WatchLogger.frame`, com `DeviceDriver.describe` como resumo.
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

	/** Logger do host; o driver registra com tags `SmaDriver/metodo`. */
	val logger: WatchLogger

	/** Notificações da characteristic `gatt.notify`, uma por pacote, na ordem de chegada. Um único consumidor. */
	val notifications: ReceiveChannel<ByteArray>

	/** Escreve um pacote na characteristic `gatt.write` e suspende até o host confirmar a escrita. */
	suspend fun write(value: ByteArray, type: WriteType = WriteType.WITH_RESPONSE)

	/** Lê uma characteristic qualquer (ex.: Device Information Service). */
	suspend fun read(service: String, characteristic: String): ByteArray
}
