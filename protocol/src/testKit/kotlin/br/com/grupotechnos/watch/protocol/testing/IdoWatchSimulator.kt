package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.ido.IdoAuth
import br.com.grupotechnos.watch.protocol.ido.IdoFrameCodec
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.util.Hex

/**
 * Relógio iDO (Life Ultra GPS) simulado em nível de byte: recebe cada frame que o telefone escreve em [onPhoneWrite] e
 * entrega as respostas em [sink], com a characteristic de origem.
 *
 * As respostas partem dos bytes reais do log da SDK de produção (`20261029.log`, `docs/protocolo-ido.md`) e têm o mesmo
 * tamanho que o relógio mandou: `02/01`, `03/01` e `04/05` com 20 bytes; `04/01` com 17; `02/A0` com 19; `02/EB` com 12.
 * Por isso o caminho de "completar com zeros" do codec já roda no caso normal; [trimReplies] corta também os zeros do
 * fim de toda resposta. Comandos fora do catálogo ficam sem resposta, como os da lista [silent].
 *
 * **Vínculo.** O `04/01` responde conforme [bindBehavior]; com desafio, o `04/05` só é respondido depois de
 * [confirmDelayMs] (o toque na tela), agendado por [schedule]. Os testes de sessão passam um `schedule` com `delay` no
 * tempo virtual; o padrão responde na hora. A resposta do telefone ao desafio é conferida contra [IdoAuth] e guardada
 * em [authReplies]; uma resposta errada é recusada com `auth_code` 1.
 *
 * @param schedule agenda uma ação depois de um tempo (a confirmação na tela).
 * @param sink entrega uma notificação do relógio (characteristic, bytes).
 */
internal class IdoWatchSimulator(
	private val schedule: (delayMs: Long, action: () -> Unit) -> Unit = { _, action -> action() },
	private val sink: (characteristic: String, value: ByteArray) -> Unit,
) {
	/** Como o relógio responde ao `04/01` e ao `04/05`. */
	enum class BindBehavior {
		/** Manda o desafio e confirma (`auth_code` 0) depois de [confirmDelayMs]: a pessoa tocou em "sim". */
		CONFIRM,

		/** Manda o desafio e recusa (`auth_code` 1) depois de [confirmDelayMs]: a pessoa tocou em "não". */
		REJECT,

		/** Responde `bind_ret_code` 2: já vinculado. */
		ALREADY_BOUND,

		/** Responde `bind_ret_code` 1: recusado sem desafio. */
		REFUSE,

		/** Aceita o bind sem desafio (`auth_length` 0), como um iDO antigo. */
		NO_AUTH,

		/** Manda o desafio e nunca responde o `04/05`: a pessoa não tocou na tela. */
		NO_ANSWER,
	}

	var bindBehavior: BindBehavior = BindBehavior.CONFIRM

	/** Tempo entre o `04/05` do telefone e a resposta (no log, 1,9 s). */
	var confirmDelayMs: Long = 1_900

	/** `pairFlag` da informação básica; vira 1 quando o vínculo é confirmado. */
	var pairFlag: Int = 1

	var battery: Int = 14
	var batteryStatus: Int = 0
	var steps: Long = 26
	/** Calorias totais do dia no `02/A0` (repouso + atividade). */
	var calories: Long = 13
	var distanceMeters: Long = 25

	/** Desafio do `04/01`; o padrão é o do log (linha 146). */
	var challenge: ByteArray = REAL_CHALLENGE.copyOf()

	/** Corta os zeros do fim de toda resposta (o codec tem de completar de novo). */
	var trimReplies: Boolean = false

	/**
	 * Antes de cada resposta, manda um pacote V3 (`0x33`), um frame legado que ninguém pediu (`07/40`) e um frame com o
	 * mesmo `cmd` e outra `key` (a `key` com os bits invertidos e o resto `FF`), que o driver não pode tomar pela resposta.
	 */
	var noise: Boolean = false

	/** Status da resposta ao `03/01` (byte 2); 0 é aceito, como no log (linha 5362). */
	var setTimeStatus: Int = 0

	/** `cmd/key` (ex.: `"02/A0"`) que o relógio não responde. */
	val silent: MutableSet<String> = mutableSetOf()

	/** Characteristic das respostas; o padrão é a normal (`0AF7`). */
	var replyCharacteristic: String = Profiles.IDO_MAIN.notify

	/** Frames recebidos do telefone, na ordem. */
	val received: MutableList<ByteArray> = mutableListOf()

	/** Payloads do `04/05` recebidos (depois de `cmd/key`). */
	val authReplies: MutableList<ByteArray> = mutableListOf()

	/** `cmd/key` de cada frame recebido (ex.: `"02/01"`). */
	fun receivedLabels(): List<String> = received.map { IdoFrameCodec.cmdKey(it[0].toInt() and 0xFF, it[1].toInt() and 0xFF) }

	/** Frames recebidos com este `cmd/key`. */
	fun receivedOf(cmdKey: String): List<ByteArray> = received.filter { IdoFrameCodec.cmdKey(it[0].toInt() and 0xFF, it[1].toInt() and 0xFF) == cmdKey }

	/** Entrega um frame escrito pelo telefone. */
	fun onPhoneWrite(packet: ByteArray) {
		check(packet.size in 2..IdoFrameCodec.MAX_FRAME) { "phone wrote an invalid iDO frame: ${Hex.encode(packet)}" }
		received += packet.copyOf()
		val label = IdoFrameCodec.cmdKey(packet[0].toInt() and 0xFF, packet[1].toInt() and 0xFF)
		if (label in silent) return
		when (label) {
			"02/01" -> reply(basicInfo())
			"02/EB" -> reply(Hex.decode(REAL_FIRMWARE))
			"02/A0" -> reply(liveData())
			"03/01" -> reply(byteArrayOf(0x03, 0x01, setTimeStatus.toByte()).copyOf(20))
			"04/01" -> reply(bindReply())
			"04/05" -> onAuth(packet.copyOfRange(2, packet.size))
		}
	}

	private fun onAuth(payload: ByteArray) {
		authReplies += payload
		val correct = payload.contentEquals(IdoAuth.reply(0, challenge))
		when (bindBehavior) {
			BindBehavior.NO_ANSWER -> return
			BindBehavior.REJECT -> schedule(confirmDelayMs) { reply(authReply(1)) }
			else -> schedule(confirmDelayMs) {
				if (correct) pairFlag = 1
				reply(authReply(if (correct) 0 else 1))
			}
		}
	}

	private fun bindReply(): ByteArray = when (bindBehavior) {
		BindBehavior.ALREADY_BOUND -> Hex.decode("040102").copyOf(17)
		BindBehavior.REFUSE -> Hex.decode("040101").copyOf(17)
		BindBehavior.NO_AUTH -> {
			pairFlag = 1
			Hex.decode("040100").copyOf(17)
		}
		else -> Hex.decode("0401000c00") + challenge
	}

	private fun authReply(code: Int): ByteArray = byteArrayOf(0x04, 0x05, code.toByte()).copyOf(20)

	private fun basicInfo(): ByteArray {
		val frame = Hex.decode(REAL_BASIC_INFO)
		frame[6] = batteryStatus.toByte()
		frame[7] = battery.toByte()
		frame[8] = pairFlag.toByte()
		return frame
	}

	private fun liveData(): ByteArray {
		val frame = Hex.decode(REAL_LIVE_DATA)
		putU32le(frame, 2, steps)
		putU32le(frame, 6, calories)
		putU32le(frame, 10, distanceMeters)
		return frame
	}

	private fun reply(frame: ByteArray) {
		if (noise) {
			sink(replyCharacteristic, Hex.decode(V3_PACKET))
			sink(replyCharacteristic, Hex.decode("0740010203"))
			sink(replyCharacteristic, ByteArray(IdoFrameCodec.MAX_FRAME) { 0xFF.toByte() }.also {
				it[0] = frame[0]
				it[1] = (frame[1].toInt() xor 0xFF).toByte()
			})
		}
		val out = if (trimReplies) frame.copyOf(maxOf(2, frame.indexOfLast { it.toInt() != 0 } + 1)) else frame
		sink(replyCharacteristic, out)
	}

	private fun putU32le(frame: ByteArray, offset: Int, value: Long) {
		for (i in 0 until 4) frame[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
	}

	companion object {
		/** Desafio do `04/01` no log (linha 146). */
		val REAL_CHALLENGE: ByteArray = Hex.decode("31bcc3543f1ce03e1c85037c")

		/** Resposta do `02/01` no log (linha 95): bateria 14 %, `pairFlag` 1, plataforma 98. */
		const val REAL_BASIC_INFO: String = "02 01 66 20 01 00 00 0e 01 00 01 f3 62 02 02 01 00 01 01 03"

		/** Resposta do `02/A0` no log (linha 5835): 26 passos, 13 kcal totais (repouso + atividade), 25 m. */
		const val REAL_LIVE_DATA: String = "02 a0 1a 00 00 00 0d 00 00 00 19 00 00 00 00 00 00 00 8e"

		/** Resposta do `02/EB` no log (linha 5776): firmware 1.1.1. */
		const val REAL_FIRMWARE: String = "02 eb 01 01 01 00 00 00 00 00 00 02"

		/** Começo de um pacote V3 que o relógio mandou (linha 330, primeiros 20 bytes). */
		const val V3_PACKET: String = "33 da ad da ad 01 42 00 1a 00 02 00 01 35 7d 00 2b 02 cd 00"
	}
}
