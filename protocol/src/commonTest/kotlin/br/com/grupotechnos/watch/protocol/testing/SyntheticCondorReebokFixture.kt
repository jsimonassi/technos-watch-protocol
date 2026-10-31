package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.crp.CrpFrameCodec
import br.com.grupotechnos.watch.protocol.crp.CrpGatt
import br.com.grupotechnos.watch.protocol.crp.CrpProtocolVersion
import br.com.grupotechnos.watch.protocol.crp.CrpRequest
import br.com.grupotechnos.watch.protocol.crp.CrpTimeZoneCodec
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.util.Hex

/**
 * Gerador da gravação sintética `fixtures/synthetic/condorreebok-pair-sync.jsonl` (procedência em `docs/fixtures.md`).
 *
 * Monta a conversa que se espera entre esta biblioteca e o Condor Reebok num pareamento seguido de bateria, firmware e
 * sync de passos e sono: os TX são os que o **nosso** driver manda (montados com o [CrpFrameCodec], bloco do MTU 517) e
 * os RX e READ são os bytes **reais** da captura do app de produção, copiados de
 * `fixtures/reference/condorreebok/MOY-VS02-2.0.0/condor-app-pair-sync.jsonl` (o `atMs` de lá está em cada constante;
 * o `CrpSyntheticReplayTest` confere que cada um existe na referência). Diferenças em relação ao app de produção, todas
 * do nosso driver: um `5A 01` sem a leitura de 2A28 (vale a resposta), o `33 01` sondado (sem resposta, como o P85 não
 * respondeu a nenhum pedido que o app não mandava) e nada de `5D`, `5C`, `B2`, `B6`, `35` e `B9`.
 *
 * Conversa (12 TX e 3 READ):
 * 1. READ 2a29 → TX `5A 00` → RX `5A 00` → TX `B7 0E` → TX `BB 16 00` → RX `F9 01 01 00`;
 * 2. TX `31` (hora automática) e TX `BB 07 00 D0 D5 FF FF`;
 * 3. READ 2a19 = `32` (bateria);
 * 4. TX `5A 01` → RX `5A 01` (firmware);
 * 5. READ fee1 (zeros) → `59 00..03` com os RX reais → TX `33 01` (sem RX) → TX `32` → RX `32` vazio → RX 2a19 `32`
 *    (o aviso espontâneo de bateria, uma vez por minuto no P85).
 */
internal object SyntheticCondorReebokFixture {
	const val RELATIVE_PATH: String = "synthetic/condorreebok-pair-sync.jsonl"

	/** A referência de onde vêm os RX e READ. */
	const val REFERENCE_PATH: String = "reference/condorreebok/MOY-VS02-2.0.0/condor-app-pair-sync.jsonl"

	val header = RecordingHeader(
		device = "P85",
		firmware = "MOY-VS02-2.0.0",
		platform = "synthetic",
		mtu = 517,
		recordedAt = "2026-10-31T15:43:12.209-03:00",
		timeZone = "America/Sao_Paulo",
	)

	/** Leitura de 2A29 (atMs 1535): `MOYOUNG-V2`. */
	const val READ_PROTOCOL: String = "4D4F594F554E472D5632"

	/** RX `5A 00` (atMs 1596). */
	const val RX_PROTOCOL: String = "FEEA20105A004D4F594F554E472D5632"

	/** RX `F9 01 01 00` (atMs 3172): clássico ainda desconectado. */
	const val RX_CLASSIC_DOWN: String = "FEEA2008F9010100"

	/** Leitura de 2A19 (atMs 105394): 50 %. */
	const val READ_BATTERY: String = "32"

	/** RX `5A 01` (atMs 98835): `MOY-VS02-2.0.0`. */
	const val RX_FIRMWARE: String = "FEEA20145A014D4F592D565330322D322E302E30"

	/** Leitura de FEE1 (atMs 56158): relógio sem uso. */
	const val READ_STEPS: String = "000000000000000000"

	/** RX `59 pp` com 24 meias horas zeradas (atMs 56234, 60960, 59313 e 61707). */
	fun rxStepsPage(page: Int): String = "FEEA2036" + "59" + Hex.encode(byteArrayOf(page.toByte())) + "00".repeat(48)

	/** RX `32` vazio (atMs 57733): sem sono. */
	const val RX_SLEEP_EMPTY: String = "FEEA200532"

	/** Notificação de 2A19 (atMs 48921): 50 %. */
	const val RX_BATTERY_NOTICE: String = "32"

	fun build(): Recording {
		val c = Conversation()
		val tz = header.timeZone
		val now = header.recordedAtEpochMillis
		c.read(CrpGatt.PROTOCOL_VERSION, READ_PROTOCOL, at = 1_535)
		c.tx(CrpRequest.protocolVersion(), at = 1_542)
		c.rx(Profiles.CRP_FEEA.notify, RX_PROTOCOL, at = 1_596)
		c.tx(CrpRequest.sppQuery(), at = 1_773)
		c.tx(CrpRequest.emojiCount(), at = 2_025)
		c.rx(Profiles.CRP_FEEA.notify, RX_CLASSIC_DOWN, at = 3_172)
		// Hora automática: o replay a mascara (o relógio do replay fica parado no recordedAt).
		c.tx(CrpRequest.setTime(now, tz), at = 3_400)
		c.tx(CrpRequest.timeZone(CrpTimeZoneCodec.offsetSeconds(now, tz)), at = 3_626)
		c.read(CrpGatt.BATTERY_LEVEL, READ_BATTERY, at = 10_000)
		c.tx(CrpRequest.firmware(), at = 12_000)
		c.rx(Profiles.CRP_FEEA.notify, RX_FIRMWARE, at = 12_074)
		c.read(CrpGatt.STEPS_TODAY, READ_STEPS, at = 14_000)
		for ((index, page) in listOf(0x00, 0x01, 0x02, 0x03).withIndex()) {
			c.tx(CrpRequest.stepsPage(page), at = 14_010L + index * 300)
			c.rx(Profiles.CRP_FEEA.notify, rxStepsPage(page), at = 14_080L + index * 300)
		}
		c.tx(CrpRequest.legacyHistory(0x01), at = 15_300)
		c.tx(CrpRequest.sleepToday(), at = 20_500)
		c.rx(Profiles.CRP_FEEA.notify, RX_SLEEP_EMPTY, at = 20_920)
		c.rx(CrpGatt.BATTERY_LEVEL, RX_BATTERY_NOTICE, at = 48_921)
		return Recording(header, c.packets, "generated")
	}

	/** Pacotes com tempos fixos (cosméticos: o replay não usa `atMs`). */
	private class Conversation {
		val packets = mutableListOf<RecordedPacket>()

		fun tx(request: CrpRequest, at: Long) {
			val frame = CrpFrameCodec.encode(request.cmd, request.payload, CrpFrameCodec.blockSize(517, CrpProtocolVersion.V2))
			packets += RecordedPacket(FrameDirection.TX, at, Profiles.CRP_FEEA.write, frame)
		}

		fun rx(characteristic: String, hex: String, at: Long) {
			packets += RecordedPacket(FrameDirection.RX, at, characteristic, Hex.decode(hex))
		}

		fun read(characteristic: String, hex: String, at: Long) {
			packets += RecordedPacket(FrameDirection.READ, at, characteristic, Hex.decode(hex))
		}
	}
}
