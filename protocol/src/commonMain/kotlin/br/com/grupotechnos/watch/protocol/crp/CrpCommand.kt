package br.com.grupotechnos.watch.protocol.crp

/**
 * Catálogo dos comandos CRP da fase 1 (`docs/protocolo-crp.md`, seção 6), com os rótulos do log e o atraso da fila.
 *
 * O frame não tem id de pedido: a resposta repete o `cmd` e, nos comandos multiplexados, o primeiro byte do payload (o
 * "sub"; nos `B6`, os dois primeiros). As fontes de cada `cmd` estão no KDoc das constantes (`sdk:` é
 * `com/crrepa/...` da SDK Android descompilada, crpblelib 2.0.3; as referências `atMs` são da fixture
 * `fixtures/reference/condorreebok/MOY-VS02-2.0.0/condor-app-pair-sync.jsonl`).
 */
internal object CrpCommand {
	/** `5A`: informação do protocolo; sub `00` versão (`sdk:f/a0.java:21-23`), `01` firmware (`sdk:f/a0.java:17-19`). */
	const val PROTOCOL_INFO: Int = 0x5A

	/** Sub do `5A` que pede a versão do protocolo (`MOYOUNG-V2`). */
	const val SUB_PROTOCOL_VERSION: Int = 0x00

	/** Sub do `5A` que pede a versão do firmware (`MOY-VS02-2.0.0`). */
	const val SUB_FIRMWARE: Int = 0x01

	/** `B7 0E`: consulta de SPP que a SDK manda sozinha nos relógios V2/V3; o P85 não responde (`sdk:f/a0.java:64-68`). */
	const val SPP_QUERY: Int = 0xB7

	/** `BB`: ajustes; sub `16 00` contagem de emojis (`sdk:f/d2.java:11-17`), `07 00` fuso (`sdk:f/v1.java:74-85`). */
	const val SETTINGS: Int = 0xBB

	/** Sub do `BB` da contagem de emojis (pedido `BB 16 00`). */
	const val SUB_EMOJI_COUNT: Int = 0x16

	/** Sub do `BB` do fuso (pedido `BB 07 00` + int32 LE). */
	const val SUB_TIME_ZONE: Int = 0x07

	/** `31`: acerto de hora, sem resposta (`sdk:f/v1.java:18-45`). */
	const val SET_TIME: Int = 0x31

	/** `59`: passos por meia hora, uma página de 24 meias horas por pedido (`sdk:f/u.java:19-21,51-53`). */
	const val STEPS_HALF_HOURS: Int = 0x59

	/** `5D`: distância por meia hora; o P85 não responde, só codec (`sdk:f/u.java:33-49`). */
	const val DISTANCE_HALF_HOURS: Int = 0x5D

	/** `5C`: calorias por meia hora; o P85 não responde, só codec (`sdk:f/u.java:33-49`). */
	const val CALORIES_HALF_HOURS: Int = 0x5C

	/** `32`: sono de hoje no formato legado (`sdk:f/m1.java:16-18`). */
	const val SLEEP_TODAY: Int = 0x32

	/** `33`: histórico legado; sub `01`/`02` passos de 1 e 2 dias atrás, `03`/`04` sono (`sdk:f/p1.java:10-18`; `sdk:f/m1.java:20-35`). */
	const val LEGACY_HISTORY: Int = 0x33

	/** Sub do `33` dos passos de ontem. */
	const val SUB_STEPS_YESTERDAY: Int = 0x01

	/** Sub do `33` do sono de ontem. */
	const val SUB_SLEEP_YESTERDAY: Int = 0x03

	/** Sub do `33` do sono de anteontem. */
	const val SUB_SLEEP_TWO_DAYS_AGO: Int = 0x04

	/** `B6`: histórico novo `[tipo][dia]`; tipo `00` passos, `01` sono, `02` FC (`sdk:m/a.java:3064-3091`). O P85 não responde. */
	const val HISTORY: Int = 0xB6

	/** Tipo `B6` dos passos do dia. */
	const val HISTORY_STEPS: Int = 0x00

	/** Tipo `B6` do sono do dia. */
	const val HISTORY_SLEEP: Int = 0x01

	/** `F8`: remove o vínculo no relógio; sucesso = fim da escrita (`sdk:f/r.java:7-9`; `sdk:m/f.java:213-220`). */
	const val REMOVE_BOND: Int = 0xF8

	/** `51`: controle do aparelho; `51 02` é o reset de fábrica, não enviado na fase 1 (`sdk:f/x.java:21-25`). */
	const val DEVICE_CONTROL: Int = 0x51

	/** `17`: formato de hora, `01` = 24 h e `00` = 12 h; não enviado na fase 1 (`sdk:f/v1.java:60-64`). */
	const val TIME_FORMAT: Int = 0x17

	/**
	 * `F9` (só RX): estado do Bluetooth clássico. A SDK 2.0.3 não trata (não há `case -7` em `sdk:m/a.java:455-745`); o
	 * significado vem da captura: `F9 01 01 xx` acompanha o vínculo BR/EDR (00 → 01 quando ele fecha) e `F9 02 01`/`02 00`
	 * chegam periodicamente.
	 */
	const val CLASSIC_STATE: Int = 0xF9

	/** `77` (RX `77 04`): confirmação de vínculo tolerada (`sdk:m/a.java:632-633`). */
	const val BIND_NOTICE: Int = 0x77

	/** `87` (RX `87 04`): confirmação de vínculo tolerada (`sdk:m/a.java:881-889`). */
	const val BIND_NOTICE_ALT: Int = 0x87

	/** Comandos cujo primeiro byte do payload entra no rótulo e no casamento da resposta. */
	private val MULTIPLEXED: Set<Int> = setOf(
		PROTOCOL_INFO, STEPS_HALF_HOURS, DISTANCE_HALF_HOURS, CALORIES_HALF_HOURS, LEGACY_HISTORY, SPP_QUERY, SETTINGS,
		DEVICE_CONTROL, TIME_FORMAT, CLASSIC_STATE, 0xB9, 0xB2, 0x35, 0xBA,
	)

	private val NAMES: Map<Int, String> = mapOf(
		SET_TIME to "SET_TIME",
		STEPS_HALF_HOURS to "STEPS_HALF_HOURS",
		DISTANCE_HALF_HOURS to "DISTANCE_HALF_HOURS",
		CALORIES_HALF_HOURS to "CALORIES_HALF_HOURS",
		SLEEP_TODAY to "SLEEP_TODAY",
		LEGACY_HISTORY to "LEGACY_HISTORY",
		HISTORY to "HISTORY",
		REMOVE_BOND to "REMOVE_BOND",
		TIME_FORMAT to "TIME_FORMAT",
		CLASSIC_STATE to "CLASSIC_STATE",
		BIND_NOTICE to "BIND_NOTICE",
		BIND_NOTICE_ALT to "BIND_NOTICE",
		SPP_QUERY to "SPP_QUERY",
	)

	private val SUB_NAMES: Map<Pair<Int, Int>, String> = mapOf(
		(PROTOCOL_INFO to SUB_PROTOCOL_VERSION) to "PROTOCOL_VERSION",
		(PROTOCOL_INFO to SUB_FIRMWARE) to "FIRMWARE_VERSION",
		(SETTINGS to SUB_EMOJI_COUNT) to "EMOJI_COUNT",
		(SETTINGS to SUB_TIME_ZONE) to "TIME_ZONE",
		(DEVICE_CONTROL to 0x02) to "FACTORY_RESET",
	)

	/**
	 * Rótulo de log de um frame: `"5A/00 PROTOCOL_VERSION"`, `"59/02 STEPS_HALF_HOURS"`, `"B6/01/00 HISTORY"`,
	 * `"32 SLEEP_TODAY"` ou `"41 UNKNOWN"`. Usa só o `cmd` e os bytes de sub, nunca o resto do payload.
	 */
	fun label(cmd: Int, payload: ByteArray): String {
		val sub = payload.getOrNull(0)?.let { it.toInt() and 0xFF }
		val key = when {
			cmd == HISTORY && payload.size >= 2 -> hex2(cmd) + "/" + hex2(sub!!) + "/" + hex2(payload[1].toInt() and 0xFF)
			cmd in MULTIPLEXED && sub != null -> hex2(cmd) + "/" + hex2(sub)
			else -> hex2(cmd)
		}
		val name = (if (sub != null) SUB_NAMES[cmd to sub] else null) ?: NAMES[cmd] ?: "UNKNOWN"
		return "$key $name"
	}

	/**
	 * Espera depois de escrever o frame de [cmd], copiada da fila da SDK (`sdk:m/f.java:94-130`): `33` → 1000 ms;
	 * `11`, `12`, `34` a `37`, `3B` e `3C` → 500 ms; `67` → 50 ms; os demais → [gapMs] (o `Quirks.commandGapMs`, 200 ms
	 * no Condor Reebok). A SDK não espera depois do `32` e trava a fila até a resposta; aqui o `32` usa [gapMs] e a
	 * resposta é esperada com timeout, como em todo pedido.
	 */
	fun postDelayMs(cmd: Int, gapMs: Long): Long = when (cmd) {
		LEGACY_HISTORY -> 1_000
		0x11, 0x12, 0x34, 0x35, 0x36, 0x37, 0x3B, 0x3C -> 500
		0x67 -> 50
		else -> gapMs
	}

	private fun hex2(value: Int): String = value.toString(16).uppercase().padStart(2, '0')
}

/**
 * UUIDs que o driver CRP lê ou recebe fora do canal de frames (`docs/protocolo-crp.md`, seção 2). O mapa vem da SDK
 * (`sdk:m/e.java:72-103`: 16 = FEE1, 17 = 2A28, 18 = 2A19, 20 = 2A29) e foi conferido na descoberta da captura
 * (cabeçalho `gatt` da fixture de referência).
 */
internal object CrpGatt {
	/** Device Information Service. */
	const val DEVICE_INFO_SERVICE: String = "0000180a-0000-1000-8000-00805f9b34fb"

	/** Manufacturer Name String: a versão do protocolo (`MOYOUNG-V2`; `sdk:m/e.java:121-127`). */
	const val PROTOCOL_VERSION: String = "00002a29-0000-1000-8000-00805f9b34fb"

	/** Software Revision String: a versão do firmware (`MOY-VS02-2.0.0`; `sdk:m/e.java:105-107`). */
	const val FIRMWARE_REVISION: String = "00002a28-0000-1000-8000-00805f9b34fb"

	/** Battery Service. */
	const val BATTERY_SERVICE: String = "0000180f-0000-1000-8000-00805f9b34fb"

	/** Battery Level: 1 byte, leitura e notificação (`sdk:m/e.java:85-87`). */
	const val BATTERY_LEVEL: String = "00002a19-0000-1000-8000-00805f9b34fb"

	/** Passos do dia, no serviço `FEEA`: leitura e notificação (`sdk:m/e.java:113-115`). */
	const val STEPS_TODAY: String = "0000fee1-0000-1000-8000-00805f9b34fb"

	/** Heart Rate Service. */
	const val HEART_RATE_SERVICE: String = "0000180d-0000-1000-8000-00805f9b34fb"

	/** Heart Rate Measurement (serviço `180D`): só log na fase 1. */
	const val HEART_RATE: String = "00002a37-0000-1000-8000-00805f9b34fb"
}
