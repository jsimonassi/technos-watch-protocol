package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.ido.IdoFrameCodec
import br.com.grupotechnos.watch.protocol.log.FrameDirection

/**
 * Deduz, dos frames TX de uma gravação iDO, as chamadas que o host fez (par do [ReplayScenario], que é só do SMA).
 *
 * No iDO cada escrita é um frame inteiro (`[cmd][key]...`, sem fragmento nem ACK), então os TX já são a sequência de
 * pedidos. Regras, na ordem:
 * - `02/01` seguido de `04/01` no começo → [ReplayAction.Pair] (consome `02/01`, `04/01` e o `04/05`, se houver);
 *   `02/01` sozinho no começo → [ReplayAction.Reconnect].
 * - O primeiro `03/01` depois do handshake é o acerto de hora automático da sessão e não vira ação.
 * - `02/01` seguido de `02/EB` → ReadDeviceInfo; `02/01` sozinho → ReadBattery; `03/01` → SetTime; `02/A0` →
 *   `Sync([STEPS])` (o `refreshSteps` e o `syncHistory` geram os mesmos bytes; o sono não escreve nada).
 * - `02/01` seguido de `04/01` no meio → [ReplayAction.Pair] de novo.
 * - O resto falha com mensagem. Uma reconexão no meio da gravação (`02/01` e o acerto de hora automático) não é
 *   deduzida: os bytes são iguais aos de um ReadBattery seguido de um SetTime. O Forget não escreve nada no iDO e
 *   também não aparece.
 */
internal object IdoReplayScenario {
	/** `cmd/key` de cada frame TX, em ordem (ex.: `"02/01"`). */
	fun txFrames(recording: Recording): List<String> =
		recording.packets.filter { it.direction == FrameDirection.TX }.map { packet ->
			require(packet.value.size >= 2) { "TX packet without cmd/key: $packet in ${recording.source}" }
			IdoFrameCodec.cmdKey(packet.value[0].toInt() and 0xFF, packet.value[1].toInt() and 0xFF)
		}

	fun deduce(recording: Recording): List<ReplayAction> {
		val frames = txFrames(recording)
		require(frames.isNotEmpty()) { "no TX frames in ${recording.source}" }
		require(frames[0] == BASIC_INFO) { "iDO recording must start with $BASIC_INFO, got ${frames[0]} in ${recording.source}" }
		val actions = mutableListOf<ReplayAction>()
		var i = 0

		fun next(offset: Int = 0): String? = frames.getOrNull(i + offset)

		fun pairHandshake() {
			i++
			if (next() == BIND_AUTH) i++
			if (next() == SET_TIME) i++
		}

		if (next(1) == BIND) {
			actions += ReplayAction.Pair
			i++
			pairHandshake()
		} else {
			actions += ReplayAction.Reconnect
			i++
			if (next() == SET_TIME) i++
		}
		while (i < frames.size) {
			when (next()) {
				BASIC_INFO -> if (next(1) == FIRMWARE) {
					actions += ReplayAction.ReadDeviceInfo
					i += 2
				} else if (next(1) == BIND) {
					actions += ReplayAction.Pair
					i++
					pairHandshake()
				} else {
					actions += ReplayAction.ReadBattery
					i++
				}
				SET_TIME -> {
					actions += ReplayAction.SetTime
					i++
				}
				LIVE_DATA -> {
					actions += ReplayAction.Sync(listOf(DataKind.STEPS))
					i++
				}
				else -> throw IllegalArgumentException("unsupported iDO TX ${next()} at frame $i in ${recording.source}")
			}
		}
		return actions
	}

	private const val BASIC_INFO = "02/01"
	private const val FIRMWARE = "02/EB"
	private const val LIVE_DATA = "02/A0"
	private const val SET_TIME = "03/01"
	private const val BIND = "04/01"
	private const val BIND_AUTH = "04/05"
}

/**
 * Máscara iDO para o replay estrito: no `03/01` (acerto de hora) zera data, hora e dia da semana (bytes 2 a 9), que
 * dependem do relógio do replay (parado no `recordedAt`) e não do instante gravado. Os bytes do fuso (14 e 15) ficam:
 * eles não mudam e conferem a fórmula do fuso contra a gravação. Os outros frames são comparados inteiros (no `04/05`,
 * a resposta ao desafio gravado).
 */
internal class IdoTxMasker : PacketMasker {
	override fun mask(packet: ByteArray): ByteArray {
		val out = packet.copyOf()
		val isSetTime = out.size >= 10 && out[0].toInt() == 0x03 && out[1].toInt() == 0x01
		if (isSetTime) for (i in 2..9) out[i] = 0
		return out
	}
}
