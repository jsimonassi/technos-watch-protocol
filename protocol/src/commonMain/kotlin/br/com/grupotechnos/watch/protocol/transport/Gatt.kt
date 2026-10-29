package br.com.grupotechnos.watch.protocol.transport

/**
 * Tipo de escrita GATT.
 */
public enum class WriteType {
	/** ATT Write Request: o relógio confirma cada escrita. É o padrão do protocolo SMA. */
	WITH_RESPONSE,

	/** ATT Write Command: sem confirmação na camada ATT. */
	WITHOUT_RESPONSE,
}

/**
 * Códigos de status que o host usa ao chamar [TransportEvents].
 *
 * Qualquer valor diferente de [SUCCESS] é tratado como falha. O host pode repassar o status nativo
 * (status GATT no Android, `NSError.code` no iOS); ele só aparece em log e na mensagem de erro.
 */
public object GattStatus {
	/** A operação terminou com sucesso (igual a `BluetoothGatt.GATT_SUCCESS` no Android). */
	public const val SUCCESS: Int = 0

	/** A operação não terminou dentro do tempo do host. */
	public const val TIMEOUT: Int = -1

	/** O link não estava aberto quando a operação foi pedida. */
	public const val NOT_CONNECTED: Int = -2

	/** O serviço ou a characteristic pedidos não existem no relógio. */
	public const val NOT_FOUND: Int = -3
}

/**
 * Uma characteristic encontrada na descoberta de serviços.
 *
 * As propriedades vêm do byte de propriedades da declaração da characteristic; nesta versão a sessão só usa [uuid]
 * para escolher o candidato GATT, e o resto fica disponível para log e para decisões futuras (ex.: tipo de escrita).
 *
 * @property uuid UUID de 128 bits em minúsculas (ver [GattUuid.normalize]).
 * @property canRead aceita leitura (`PROPERTY_READ`).
 * @property canWrite aceita ATT Write Request, com confirmação (`PROPERTY_WRITE`).
 * @property canWriteWithoutResponse aceita ATT Write Command, sem confirmação (`PROPERTY_WRITE_NO_RESPONSE`).
 * @property canNotify aceita notificação (`PROPERTY_NOTIFY`).
 */
public data class DiscoveredCharacteristic(
	val uuid: String,
	val canRead: Boolean,
	val canWrite: Boolean,
	val canWriteWithoutResponse: Boolean,
	val canNotify: Boolean,
)

/**
 * Um serviço encontrado na descoberta, com suas characteristics.
 *
 * @property uuid UUID de 128 bits em minúsculas (ver [GattUuid.normalize]).
 * @property characteristics todas as characteristics do serviço, na ordem em que o host as encontrou.
 */
public data class DiscoveredService(
	val uuid: String,
	val characteristics: List<DiscoveredCharacteristic>,
)

/**
 * Normalização de UUIDs, para que host e sessão comparem sempre o mesmo texto.
 */
public object GattUuid {
	private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

	/**
	 * Converte para UUID de 128 bits em minúsculas.
	 *
	 * Aceita a forma completa (`"6E400001-B5A3-F393-E0A9-E50E24DCCA9E"`) e as formas curtas de 16 e 32 bits
	 * da base Bluetooth (`"FF60"`, `"0000FF60"`), que viram `"0000ff60-0000-1000-8000-00805f9b34fb"`.
	 */
	public fun normalize(uuid: String): String {
		val clean = uuid.trim().lowercase()
		return when (clean.length) {
			4 -> "0000$clean$BASE_SUFFIX"
			8 -> "$clean$BASE_SUFFIX"
			else -> clean
		}
	}
}
