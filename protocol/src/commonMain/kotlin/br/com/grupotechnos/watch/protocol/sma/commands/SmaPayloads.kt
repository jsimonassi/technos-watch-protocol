package br.com.grupotechnos.watch.protocol.sma.commands

import br.com.grupotechnos.watch.protocol.profile.BindPayloadKind
import br.com.grupotechnos.watch.protocol.util.ByteReader
import br.com.grupotechnos.watch.protocol.util.ByteWriter
import kotlinx.datetime.LocalDateTime

/**
 * Payload de 32 bytes do BIND (`03/01`) e do LOGIN (`03/03`), `docs/protocolo-sma.md`, seção 5.
 *
 * - [BindPayloadKind.ANDROID_FF]: `FF` + 31 zeros (`SmaManager.sendBindCmd/sendLoginCmd`, `SmaManager.java:436-449`).
 * - [BindPayloadKind.USER_ID]: userId uint32 big-endian + 28 zeros (`SmaBLE bindBloacUserID`, `SmaBLE.m:559-570`;
 *   união `username_union_t` em `SmaBLE.m:2142-2153`). O Core iOS usa userId 1 (`SMAService.m:1093`).
 */
internal object SmaBindPayload {
	/** Tamanho fixo do payload nas duas SDKs. */
	const val SIZE: Int = 32

	/** Bytes do payload. [userId] só é usado com [BindPayloadKind.USER_ID] e é gravado como uint32. */
	fun encode(kind: BindPayloadKind, userId: Int): ByteArray = when (kind) {
		BindPayloadKind.ANDROID_FF -> ByteWriter(SIZE).u8(0xFF).zeros(SIZE - 1).toByteArray()
		BindPayloadKind.USER_ID -> ByteWriter(SIZE).u32(userId.toLong() and 0xFFFFFFFFL).zeros(SIZE - 4).toByteArray()
	}

	/**
	 * Resposta `03/02` aceita: `value[0] == 0` (`SmaManager.java:726-729`; `SmaBLE.m:288-292`).
	 * Value vazio é recusa: as duas SDKs leriam o byte 13 de um frame que não o tem.
	 */
	fun isBindAccepted(value: ByteArray): Boolean = value.isNotEmpty() && value[0].toInt() == 0

	/**
	 * Resposta `03/04` aceita: value vazio ou `value[0] == 0`. A SDK Android exige `value[0] == 0`
	 * (`SmaManager.java:726, 736-739`); a iOS aceita qualquer resposta com CRC certo (`SmaBLE.m:294-301`).
	 * O meio-termo aceita o caso iOS de resposta sem value sem aceitar uma recusa explícita.
	 */
	fun isLoginAccepted(value: ByteArray): Boolean = value.isEmpty() || value[0].toInt() == 0
}

/**
 * Hora do relógio (`02/01` SET_TIME e resposta `02/07` do GET_TIME), `docs/protocolo-sma.md`, seção 7.
 *
 * Um uint32 big-endian com o horário local de parede, sem fuso nem horário de verão:
 * ```
 * bits 31-26 ano - 2000 (0..63) | 25-22 mês (1..12) | 21-17 dia | 16-12 hora | 11-6 minuto | 5-0 segundo
 * ```
 * Fontes: `SmaTime.toByteArray` (`SmaTime.java:26-33`) e a união `usercdata_union_t` (`SmaBLE.m:2234-2255`),
 * preenchida em `setSystemTime` (`SmaBLE.m:524-556`). Exemplo: 2026-10-28 14:30:00 → `0x6AB8E780`.
 */
internal object SmaTimeCodec {
	/** Menor ano representável (campo de 6 bits a partir de 2000). */
	const val MIN_YEAR: Int = 2000

	/** Maior ano representável. */
	const val MAX_YEAR: Int = 2063

	/**
	 * Empacota o horário.
	 *
	 * @throws IllegalArgumentException se algum campo estiver fora da faixa do layout.
	 */
	fun pack(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
		require(year in MIN_YEAR..MAX_YEAR) { "year outside $MIN_YEAR..$MAX_YEAR: $year" }
		require(month in 1..12) { "invalid month: $month" }
		require(day in 1..31) { "invalid day: $day" }
		require(hour in 0..23) { "invalid hour: $hour" }
		require(minute in 0..59) { "invalid minute: $minute" }
		require(second in 0..59) { "invalid second: $second" }
		return ((year - MIN_YEAR).toLong() shl 26) or (month.toLong() shl 22) or (day.toLong() shl 17) or
			(hour.toLong() shl 12) or (minute.toLong() shl 6) or second.toLong()
	}

	/** Value de 4 bytes do SET_TIME para o horário local [local]. */
	fun encode(local: LocalDateTime): ByteArray =
		ByteWriter(4)
			.u32(pack(local.year, local.monthNumber, local.dayOfMonth, local.hour, local.minute, local.second))
			.toByteArray()

	/**
	 * Lê o horário de 4 bytes.
	 *
	 * @throws IllegalArgumentException se [value] não tiver 4 bytes ou não for uma data válida.
	 */
	fun decode(value: ByteArray): LocalDateTime {
		require(value.size == 4) { "SET_TIME value has 4 bytes, got ${value.size}" }
		val packed = ByteReader.u32At(value, 0)
		return LocalDateTime(
			year = MIN_YEAR + ((packed ushr 26) and 0x3F).toInt(),
			monthNumber = ((packed ushr 22) and 0x0F).toInt(),
			dayOfMonth = ((packed ushr 17) and 0x1F).toInt(),
			hour = ((packed ushr 12) and 0x1F).toInt(),
			minute = ((packed ushr 6) and 0x3F).toInt(),
			second = (packed and 0x3F).toInt(),
		)
	}
}

/**
 * Fuso (`02/68` TIMEZONE), só codec nesta PoC (`docs/protocolo-sma.md`, seção 7).
 *
 * Um byte com sinal: offset em unidades de 15 minutos, truncado em direção a zero (divisão inteira de Java e
 * de Objective-C). A SDK Android usa `TimeZone.getRawOffset()`, sem horário de verão, e zera offsets acima de
 * um dia (`SmaTimezone.java:6-21`); a iOS usa `secondsFromGMT`, com horário de verão (`SmaBLE.m:3601-3620`).
 * Qual offset passar é decisão de quem chama; nenhum dos Cores atuais envia esse comando.
 */
internal object SmaTimezoneCodec {
	private const val MAX_OFFSET_SECONDS = 24 * 60 * 60

	/** Value de 1 byte para o offset em segundos. Offsets acima de 24 h viram 0, como na SDK Android. */
	fun encode(offsetSeconds: Int): ByteArray {
		val safe = if (offsetSeconds < -MAX_OFFSET_SECONDS || offsetSeconds > MAX_OFFSET_SECONDS) 0 else offsetSeconds
		val quarters = safe / 60 / 15
		return byteArrayOf(quarters.toByte())
	}

	/** Offset em segundos lido do byte com sinal. */
	fun decode(value: ByteArray): Int {
		require(value.size == 1) { "TIMEZONE value has 1 byte, got ${value.size}" }
		return value[0].toInt() * 15 * 60
	}
}

/**
 * Perfil do usuário (`02/10` USER_INFO), só codec nesta PoC (`docs/protocolo-sma.md`, seção 7).
 *
 * Um uint32 big-endian:
 * ```
 * bit 31 gênero (0 feminino, 1 masculino) | 30-24 idade | 23-15 altura em meio cm | 14-5 peso em meio kg | 4-0 reservado
 * ```
 * Fontes: união `userprofile_union_t` (`SmaBLE.m:2209-2229`) preenchida em `SmaBLE.m:495-521`, e
 * `SmaUserInfo.toByteArray` (`SmaUserInfo.java:9-16`). As duas SDKs geram o mesmo layout, mas a Android
 * trunca a altura para centímetros inteiros antes de dobrar (`(int)this.height * 2`), perdendo o meio
 * centímetro; aqui seguimos o iOS (`he*2`), que preserva 173,5 cm como 347.
 */
internal object SmaUserInfoCodec {
	/**
	 * @param male `true` = 1 (masculino), como `SMAService.java:114` usa para o perfil fixo.
	 * @throws IllegalArgumentException se algum campo não couber no seu número de bits.
	 */
	fun encode(male: Boolean, age: Int, heightCm: Double, weightKg: Double): ByteArray {
		val heightHalves = (heightCm * 2).toInt()
		val weightHalves = (weightKg * 2).toInt()
		require(age in 0..127) { "age out of 7 bits: $age" }
		require(heightHalves in 0..511) { "height out of 9 bits (half cm): $heightCm" }
		require(weightHalves in 0..1023) { "weight out of 10 bits (half kg): $weightKg" }
		val gender = if (male) 1L else 0L
		val packed = (gender shl 31) or (age.toLong() shl 24) or (heightHalves.toLong() shl 15) or (weightHalves.toLong() shl 5)
		return ByteWriter(4).u32(packed).toByteArray()
	}
}

/**
 * Resposta `02/0B` do VERSION (`docs/protocolo-sma.md`, seção 7).
 *
 * Firmware em `value[0..2]` como `"a.b.c"` (`SmaManager.java:641-651`). A SDK iOS também lê a versão da pilha
 * BLE em `value[3..5]` (`SmaBLE.m:325-330`), sem conferir o tamanho; aqui ela só é lida quando os 6 bytes existem.
 */
internal object SmaVersionPayload {
	/** Firmware e versão BLE (ou `null`). */
	data class Version(val firmware: String, val bleVersion: String?)

	/**
	 * @throws IllegalArgumentException se o value tiver menos de 3 bytes.
	 */
	fun decode(value: ByteArray): Version {
		require(value.size >= 3) { "VERSION_BACK needs 3 bytes, got ${value.size}" }
		val firmware = triple(value, 0)
		val ble = if (value.size >= 6) triple(value, 3) else null
		return Version(firmware, ble)
	}

	private fun triple(value: ByteArray, offset: Int): String =
		"${ByteReader.u8At(value, offset)}.${ByteReader.u8At(value, offset + 1)}.${ByteReader.u8At(value, offset + 2)}"
}

/**
 * Resposta `02/09` do BATTERY: `value[0]` sem sinal é o percentual (`SmaManager.java:652-658`; `SmaBLE.m:331-336`).
 */
internal object SmaBatteryPayload {
	/**
	 * @throws IllegalArgumentException se o value estiver vazio.
	 */
	fun decode(value: ByteArray): Int {
		require(value.isNotEmpty()) { "BATTERY_BACK without value" }
		return ByteReader.u8At(value, 0)
	}
}

/** Value do UNBIND (`03/05`): `[00]`, como `SmaManager.unbind` (`SmaManager.java:416`). */
internal object SmaUnbindPayload {
	/** Uma cópia nova a cada chamada. */
	fun encode(): ByteArray = byteArrayOf(0x00)
}
