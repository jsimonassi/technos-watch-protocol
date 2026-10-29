package br.com.grupotechnos.watch.protocol.sma.codec

/**
 * CRC-16/ARC (também chamada CRC-16/IBM refletida): polinômio refletido `0xA001`, valor inicial `0x0000`,
 * entrada e saída refletidas, sem XOR final.
 *
 * É o CRC do frame SMA, calculado sobre o L2 inteiro (do offset 8 até o fim) e gravado em big-endian nos
 * bytes 4 e 5 do L1 (`docs/protocolo-sma.md`, seção 3).
 *
 * Fontes:
 * - Android: tabela `crc16_table` em `SmaBleHelper.java:6` (primeiros valores `0, 49345, 49537, 320` =
 *   `0x0000, 0xC0C1, 0xC181, 0x0140`) e laço `crc = (crc >> 8) ^ T[(crc ^ b) & 0xFF]` em `SmaBleHelper.java:49-58`.
 * - iOS: mesma tabela e mesmo laço em `SmaBusinessTool.m:14-62`.
 *
 * Vetores conferidos em `Crc16ArcTest`: `"123456789"` → `0xBB3D` (vetor padrão do catálogo de CRCs) e
 * `01 00 01 00 00` → `0x006C`, que é o CRC gravado à mão no frame OTA fixo do iOS (`SmaBusinessTool.m:204-222`).
 */
internal object Crc16Arc {
	private const val POLYNOMIAL_REFLECTED = 0xA001

	/** Tabela gerada a partir do polinômio; `Crc16ArcTest` confere contra os valores da SDK. */
	internal val table: IntArray = IntArray(256) { index ->
		var crc = index
		repeat(8) {
			crc = if (crc and 1 != 0) (crc ushr 1) xor POLYNOMIAL_REFLECTED else crc ushr 1
		}
		crc
	}

	/**
	 * CRC de `bytes[offset until offset + length]`.
	 *
	 * @return valor de 16 bits (0..0xFFFF).
	 */
	fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
		require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
			"invalid range: offset=$offset length=$length size=${bytes.size}"
		}
		var crc = 0
		for (index in offset until offset + length) {
			crc = (crc ushr 8) xor table[(crc xor bytes[index].toInt()) and 0xFF]
		}
		return crc and 0xFFFF
	}
}
