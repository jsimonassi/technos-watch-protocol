package br.com.grupotechnos.watch.protocol.log

import br.com.grupotechnos.watch.protocol.util.Hex

/**
 * Formato das gravações de tráfego (`.jsonl`) usadas como fixtures nos testes de replay.
 *
 * Uma gravação é um arquivo de texto com um objeto JSON por linha: a primeira linha é o cabeçalho
 * e cada linha seguinte é um pacote GATT. O formato completo está em `docs/fixtures.md`.
 *
 * ```
 * {"type":"header","device":"Mormaii_fitGPS","firmware":"1.2.3","platform":"android","mtu":247,"recordedAt":"2026-10-28T14:30:00-03:00","timeZone":"America/Sao_Paulo"}
 * {"type":"packet","dir":"TX","atMs":120,"characteristic":"6e400002-b5a3-f393-e0a9-e50e24dcca9e","hex":"AB0000251..."}
 * ```
 *
 * Só formata. Gravar no disco é responsabilidade do host.
 */
public object FrameJsonl {
	/**
	 * Linha de cabeçalho.
	 *
	 * @param recordedAtIso instante do início da gravação em ISO-8601 com offset, com ou sem milissegundos
	 * (`2026-10-29T08:14:10.933-03:00`); o replay usa como relógio.
	 * @param timeZoneId fuso do telefone durante a gravação (ex.: `"America/Sao_Paulo"`).
	 */
	public fun header(
		device: String,
		firmware: String?,
		platform: String,
		mtu: Int,
		recordedAtIso: String,
		timeZoneId: String,
	): String {
		val firmwareJson = firmware?.let { "\"${escape(it)}\"" } ?: "null"
		return "{\"type\":\"header\",\"device\":\"${escape(device)}\",\"firmware\":$firmwareJson," +
			"\"platform\":\"${escape(platform)}\",\"mtu\":$mtu,\"recordedAt\":\"${escape(recordedAtIso)}\"," +
			"\"timeZone\":\"${escape(timeZoneId)}\"}"
	}

	/**
	 * Linha de pacote.
	 *
	 * @param atMs milissegundos desde o início da gravação.
	 * @param value bytes do pacote, gravados em hexadecimal maiúsculo sem separadores.
	 */
	public fun line(direction: FrameDirection, atMs: Long, characteristic: String, value: ByteArray): String =
		"{\"type\":\"packet\",\"dir\":\"${direction.name}\",\"atMs\":$atMs," +
			"\"characteristic\":\"${escape(characteristic)}\",\"hex\":\"${Hex.encode(value)}\"}"

	private fun escape(text: String): String = buildString {
		for (char in text) {
			when (char) {
				'"' -> append("\\\"")
				'\\' -> append("\\\\")
				'\n' -> append("\\n")
				else -> append(char)
			}
		}
	}
}
