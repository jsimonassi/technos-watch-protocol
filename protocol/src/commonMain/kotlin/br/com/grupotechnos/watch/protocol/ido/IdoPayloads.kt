package br.com.grupotechnos.watch.protocol.ido

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.isoDayNumber

/**
 * Resposta do `02/01` (informação básica), `docs/protocolo-ido.md`, seção 3.1. Offsets contados do `cmd`, conferidos
 * contra o JSON que a SDK imprimiu para o mesmo frame (`20261029.log` linha 95 → linhas 98-116):
 * `02 01 66 20 01 00 00 0e 01 00 01 f3 62 02 02 01 00 01 01 03` dá `deivceId` 8294, `firmwareVersion` 1, `mode` 0,
 * `battStatus` 0, `energe` 14, `pairFlag` 1, `reboot` 0, `platform` 98, `shape` 2, `dev_type` 2 e `gps_platform` 3. Os
 * bytes 10-11 levam `cloud_clock_dial_version`, `bind_confirm_method` e `bind_confirm_timeout` empacotados (não lidos).
 *
 * @property deviceId id do modelo na iDO (`u16` LE nos bytes 2-3).
 * @property firmwareVersion versão curta do firmware (byte 4); a versão completa vem do `02/EB`.
 * @property mode modo do relógio (byte 5).
 * @property batteryStatus 0 normal, 1 carregando, 2 cheia, 3 baixa (byte 6).
 * @property batteryPercent bateria em % (`energe`, byte 7).
 * @property pairFlag 1 quando o relógio se diz vinculado (byte 8).
 * @property reboot byte 9.
 * @property platform plataforma do chip (byte 12; 98 = SiFli).
 * @property shape formato da tela (byte 13).
 * @property deviceType tipo do aparelho (byte 14).
 * @property gpsPlatform plataforma do GPS (byte 19; 3 no Life Ultra GPS).
 */
internal data class IdoBasicInfo(
	val deviceId: Int,
	val firmwareVersion: Int,
	val mode: Int,
	val batteryStatus: Int,
	val batteryPercent: Int,
	val pairFlag: Int,
	val reboot: Int,
	val platform: Int,
	val shape: Int,
	val deviceType: Int,
	val gpsPlatform: Int,
) {
	/** `true` quando o relógio se diz vinculado (`pairFlag == 1`). */
	val bound: Boolean
		get() = pairFlag == 1

	/** `true` quando o relógio informa carregamento (`battStatus == 1`). */
	val charging: Boolean
		get() = batteryStatus == 1

	companion object {
		/** Lê a resposta do `02/01`. */
		fun decode(frame: IdoFrame): IdoBasicInfo = IdoBasicInfo(
			deviceId = frame.u16le(2),
			firmwareVersion = frame.u8(4),
			mode = frame.u8(5),
			batteryStatus = frame.u8(6),
			batteryPercent = frame.u8(7),
			pairFlag = frame.u8(8),
			reboot = frame.u8(9),
			platform = frame.u8(12),
			shape = frame.u8(13),
			deviceType = frame.u8(14),
			gpsPlatform = frame.u8(19),
		)
	}
}

/**
 * Pedido e resposta do vínculo `04/01` (`docs/protocolo-ido.md`, seção 4).
 *
 * O pedido é fixo: `04 01 f1 01 01 02 02 01 00` (`20261029.log` linha 144). A biblioteca nativa monta
 * `04 01 F1 01 <bind_version> <os_type> <os_version> <is_clean_data> 00` com o `BindPara` que o Core manda
 * (`bind_version` 1, `os_type` 2 = Android, `os_version` 2, `is_clean_data` 1). Os mesmos bytes servem para o iOS na PoC.
 */
internal object IdoBindPayload {
	/** Payload do `04/01` depois de `cmd/key`, igual ao do log. */
	val REQUEST: ByteArray = byteArrayOf(0xF1.toByte(), 0x01, 0x01, 0x02, 0x02, 0x01, 0x00)

	/** `bind_ret_code` de sucesso. */
	const val CODE_SUCCESS: Int = 0

	/** `bind_ret_code` de relógio já vinculado; a SDK trata como vínculo feito. */
	const val CODE_ALREADY_BOUND: Int = 2

	/** Tamanho do desafio que a autenticação embaralha. */
	const val CHALLENGE_SIZE: Int = 12
}

/**
 * Resposta do `04/01`: `04 01 <bind_ret_code> <auth_length> <encrypted_version> <encrypted_data...>`. No log,
 * `04 01 00 0c 00 31 bc c3 54 3f 1c e0 3e 1c 85 03 7c` (`20261029.log` linha 146; a SDK guardou o mesmo desafio,
 * linha 150).
 *
 * @property code `bind_ret_code` (byte 2): 0 sucesso, 2 já vinculado; outro valor é recusa.
 * @property authLength `auth_length` (byte 3): 12 quando o relógio pede a autenticação.
 * @property encryptedVersion `encrypted_version` (byte 4), repetido na resposta.
 * @property challenge os 12 bytes de `encrypted_data` (bytes 5-16), ou `null` quando [authLength] é menor que 12.
 */
internal class IdoBindReply(
	val code: Int,
	val authLength: Int,
	val encryptedVersion: Int,
	val challenge: ByteArray?,
) {
	companion object {
		/** Lê a resposta do `04/01`. */
		fun decode(frame: IdoFrame): IdoBindReply {
			val authLength = frame.u8(3)
			val challenge = if (authLength >= IdoBindPayload.CHALLENGE_SIZE) {
				frame.bytes.copyOfRange(5, 5 + IdoBindPayload.CHALLENGE_SIZE)
			} else {
				null
			}
			return IdoBindReply(code = frame.u8(2), authLength = authLength, encryptedVersion = frame.u8(4), challenge = challenge)
		}
	}
}

/**
 * Autenticação do vínculo `04/05` (`docs/protocolo-ido.md`, seção 4.2). Não há chave: é um embaralhamento do desafio
 * `E` de 12 bytes que o relógio mandou no `04/01` (`protocol_data_encrypted_auth_gt01` na biblioteca nativa da SDK):
 *
 * `04 05 <0x0c> <encrypted_version> E[6..11] (E[0..5] XOR E[6..11])`
 *
 * Conferido byte a byte contra o log: desafio `31 bc c3 54 3f 1c e0 3e 1c 85 03 7c` (linha 146) e envio
 * `04 05 0c 00 e0 3e 1c 85 03 7c d1 82 df d1 3c 60` (linha 154): `31^e0=d1`, `bc^3e=82`, `c3^1c=df`, `54^85=d1`,
 * `3f^03=3c`, `1c^7c=60`. A resposta `04 05 <auth_code>` com `auth_code` 0 chegou 1,9 s depois, quando a pessoa tocou na
 * tela (linhas 158-163).
 */
internal object IdoAuth {
	/**
	 * Payload do `04/05` (depois de `cmd/key`) para o desafio [challenge].
	 *
	 * @throws IllegalArgumentException se [challenge] não tiver 12 bytes.
	 */
	fun reply(encryptedVersion: Int, challenge: ByteArray): ByteArray {
		val size = IdoBindPayload.CHALLENGE_SIZE
		require(challenge.size == size) { "iDO auth challenge must have $size bytes, got ${challenge.size}" }
		require(encryptedVersion in 0..0xFF) { "encrypted_version out of range: $encryptedVersion" }
		val half = size / 2
		val out = ByteArray(2 + size)
		out[0] = size.toByte()
		out[1] = encryptedVersion.toByte()
		for (i in 0 until half) {
			out[2 + i] = challenge[half + i]
			out[2 + half + i] = (challenge[i].toInt() xor challenge[half + i].toInt()).toByte()
		}
		return out
	}

	/** `auth_code` da resposta `04/05` (byte 2): 0 é sucesso. */
	fun authCode(frame: IdoFrame): Int = frame.u8(2)
}

/**
 * Acerto de hora `03/01` (`docs/protocolo-ido.md`, seção 3.4):
 *
 * `03 01 <ano u16 LE> <mês> <dia> <hora> <minuto> <segundo> <semana> 00 00 00 00 <fuso u16 LE>`
 *
 * A semana conta de segunda (0) a domingo (6), como o `SystemTime` que a própria SDK monta
 * (`com.ido.ble.common.ember.acorn(boolean)`, decompilado: domingo vira 6 e os outros dias `DAY_OF_WEEK - 2`). O fuso
 * segue a fórmula do Core com a SDK (`v2_support_set_time_zone_float`, verdadeiro neste relógio, `20261029.log` linha
 * 931): centésimos de hora (`offsetMillis / 36000`), e um valor negativo vira `|valor - 1200|`. No log, 2026-10-29
 * 22:08:49 de uma quinta em São Paulo (-3 h) virou `03 01 ea 07 0a 1d 16 08 31 03 00 00 00 00 dc 05` (linhas 5355-5360;
 * -300 → 1500 = `0x05dc`). Sem o fuso fracionário a SDK manda horas inteiras, negativo como `|horas - 12|` (15 = `0x0f`,
 * linha 182, antes de a SDK ler a tabela de funções).
 *
 * A semana não está provada pelo relógio: a linha 186 manda a mesma quinta com semana `04` (o `setTime` do Core de
 * produção, linha 172, que conta de domingo = 0, `SDKCallbacks.java:596`), e o relógio respondeu `03 01 00` aos dois.
 * Quem decide é o dia da semana na tela depois do acerto (`docs/pendencias.md`, caixa **Hora** do Life Ultra GPS).
 */
internal object IdoTimeCodec {
	/**
	 * Payload do `03/01` (depois de `cmd/key`), 14 bytes.
	 *
	 * @param local data e hora de parede do telefone.
	 * @param offsetMillis offset do fuso do telefone nesse instante.
	 * @param fractionalZone `true` para o fuso em centésimos de hora (o Life Ultra GPS); `false` para horas inteiras.
	 * @throws IllegalArgumentException se o ano não couber em `u16`.
	 */
	fun encode(local: LocalDateTime, offsetMillis: Long, fractionalZone: Boolean): ByteArray {
		require(local.year in 0..0xFFFF) { "year out of range: ${local.year}" }
		val zone = timeZoneValue(offsetMillis, fractionalZone)
		return byteArrayOf(
			(local.year and 0xFF).toByte(),
			(local.year ushr 8).toByte(),
			local.monthNumber.toByte(),
			local.dayOfMonth.toByte(),
			local.hour.toByte(),
			local.minute.toByte(),
			local.second.toByte(),
			weekDay(local).toByte(),
			0,
			0,
			0,
			0,
			(zone and 0xFF).toByte(),
			((zone ushr 8) and 0xFF).toByte(),
		)
	}

	/** Dia da semana do relógio: segunda = 0 … domingo = 6. */
	fun weekDay(local: LocalDateTime): Int = local.dayOfWeek.isoDayNumber - 1

	/**
	 * Valor do campo de fuso: com [fractionalZone], `offsetMillis / 36000` (centésimos de hora); sem ele, horas
	 * inteiras. Nos dois, um valor negativo vira o módulo de `valor - 1200` (ou de `horas - 12`): o relógio lê acima de
	 * 1200 (ou 12) como oeste.
	 */
	fun timeZoneValue(offsetMillis: Long, fractionalZone: Boolean): Int {
		if (fractionalZone) {
			val hundredths = (offsetMillis / 36_000L).toInt()
			return if (hundredths < 0) kotlin.math.abs(hundredths - 1200) else hundredths
		}
		val hours = (offsetMillis / 3_600_000L).toInt()
		return if (hours < 0) kotlin.math.abs(hours - 12) else hours
	}

	/** `true` se a resposta `03/01` tem status 0 (byte 2), como `03 01 00 …` no log (linha 5362). */
	fun accepted(frame: IdoFrame): Boolean = frame.u8(2) == 0
}

/**
 * Resposta do `02/EB` (versão do firmware e da pilha BT3), `docs/protocolo-ido.md`, seção 3.2. No log,
 * `02 eb 01 01 01 00 00 00 00 00 00 02` (`20261029.log` linha 5776), que a SDK leu como `firmware_version1..3` = 1, 1, 1
 * e os campos `BT_*` zerados, menos `BT_match_version3` = 2 (linhas 5777-5788).
 */
internal object IdoFirmwarePayload {
	/** Versão do firmware como `"v1.v2.v3"` (bytes 2, 3 e 4), ex.: `"1.1.1"`. */
	fun decode(frame: IdoFrame): String = "${frame.u8(2)}.${frame.u8(3)}.${frame.u8(4)}"
}

/**
 * Resposta do `02/A0` (dados ao vivo), `docs/protocolo-ido.md`, seção 3.3. Os totais são os acumuladores do dia no
 * relógio. No log, `02 a0 1a 00 00 00 0d 00 00 00 19 00 00 00 00 00 00 00 8e` (`20261029.log` linha 5835): 26 passos,
 * 13 kcal, 25 m e 0 de tempo ativo; a tela mostrava 26 passos, 0,02 milha (25 m = 0,016 milha) e 0 caloria.
 *
 * **Calorias.** O byte 6 é a caloria total do dia, com a de repouso: o sync V3 do mesmo dia deu
 * `total_activity_calories` 0 e `total_rest_activity_calories` 13 (linhas 3891 e 3893). A tela do relógio mostra só a
 * caloria de atividade (0 naquela hora), e o `02/A0` não tem campo para ela (o `LiveData` da SDK só tem `totalStep`,
 * `totalCalories`, `totalDistances`, `totalActiveTime`, `heartRate`, `sbp` e `dbp`). O driver repassa o valor como
 * veio, como o caminho ao vivo do Core de produção (`SDKCallbacks.java:348`, `emitOnCalories(liveData.totalCalories)`);
 * o histórico do Core usa a de atividade (`HelpersFunctions.java:373`), que só vem do V3, fora da PoC.
 *
 * @property steps `totalStep` (`u32` LE no byte 2).
 * @property calories `totalCalories` em kcal (`u32` LE no byte 6): repouso + atividade, não o número da tela.
 * @property distanceMeters `totalDistances` em metros (`u32` LE no byte 10).
 * @property activeTime `totalActiveTime` (`u32` LE no byte 14).
 */
internal data class IdoLiveData(
	val steps: Long,
	val calories: Long,
	val distanceMeters: Long,
	val activeTime: Long,
) {
	companion object {
		/** Lê a resposta do `02/A0`. */
		fun decode(frame: IdoFrame): IdoLiveData = IdoLiveData(
			steps = frame.u32le(2),
			calories = frame.u32le(6),
			distanceMeters = frame.u32le(10),
			activeTime = frame.u32le(14),
		)
	}
}
