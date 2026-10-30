package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.domain.DataKind
import kotlinx.datetime.TimeZone

/**
 * Tradução do `deletedSavedData(items, category)` do JS para `WatchSession.confirmUploaded(kind, dayKeys)`.
 *
 * O app manda, depois de enviar ao backend, a lista de `registered_at` confirmados e a categoria
 * (`TechnosCore.deletedCategory`: `PEDOMETER = 5`, `SLEEP = 0`, `TechnosWareCore/src/NativeModules/index.ts:33-43`).
 * A sessão trabalha com dias locais (`yyyy-MM-dd`), então cada data vira o dia local no fuso do telefone.
 *
 * Por que converter para o fuso do telefone e não cortar os 10 primeiros caracteres: hoje o app manda
 * `moment(data.id).format()`, com o offset do telefone (`GTE-Mormaii/src/mappers/entities/general.ts:7-13`),
 * mas a data já passou por `moment(date).toISOString()` (UTC) no caminho (`pedometer.ts:40-48`) e o backend
 * pode devolvê-la em UTC. `2026-10-28T22:00:00-03:00` em UTC é `2026-10-29T01:00:00Z`: cortar daria o dia
 * errado; converter o instante para o fuso local devolve o dia da amostra nos dois formatos.
 */
public object SavedDataMapper {
	/** `deletedCategory.PEDOMETER` do Core atual. */
	public const val CATEGORY_PEDOMETER: Int = 5

	/** `deletedCategory.SLEEP` do Core atual. */
	public const val CATEGORY_SLEEP: Int = 0

	/** Categoria do Core atual para [DataKind], ou `null` para categorias que este Core não guarda. */
	public fun kindForCategory(category: Int): DataKind? = when (category) {
		CATEGORY_PEDOMETER -> DataKind.STEPS
		CATEGORY_SLEEP -> DataKind.SLEEP
		else -> null
	}

	/**
	 * Dia local (`yyyy-MM-dd`) de uma data ISO-8601 no fuso atual do telefone, ou `null` se não for possível ler.
	 *
	 * Aceita, em ordem: com offset ou `Z` (`2026-10-28T00:00:00-03:00`, `...000Z`), offset legado (`-0300`),
	 * data-hora sem offset (tratada como hora local) e só a data (`2026-10-28`). Aceita e recusa exatamente o que o
	 * `java.time` do host Android antigo aceitava e recusava (ver o KDoc de `IsoDates.dayKey`).
	 */
	public fun dayKey(registeredAt: String): String? = dayKey(registeredAt, TimeZone.currentSystemDefault())

	/** [dayKey] no fuso [zone]. */
	internal fun dayKey(registeredAt: String, zone: TimeZone): String? = IsoDates.dayKey(registeredAt, zone)

	/**
	 * Dias distintos, em ordem, das datas que puderam ser lidas; as inválidas vão em [Result.rejected] para log. O fuso
	 * do telefone é lido uma vez para a lista inteira.
	 */
	public fun dayKeys(registeredAts: List<String>): Result = dayKeys(registeredAts, TimeZone.currentSystemDefault())

	/** [dayKeys] no fuso [zone]. */
	internal fun dayKeys(registeredAts: List<String>, zone: TimeZone): Result {
		val days = LinkedHashSet<String>()
		val rejected = mutableListOf<String>()
		for (value in registeredAts) {
			val day = dayKey(value, zone)
			if (day == null) rejected += value else days += day
		}
		return Result(days.toList(), rejected)
	}

	/**
	 * Resultado de [dayKeys].
	 *
	 * @property dayKeys dias distintos, na ordem da primeira data de cada um.
	 * @property rejected as datas que não puderam ser lidas, na ordem de entrada.
	 */
	public data class Result(val dayKeys: List<String>, val rejected: List<String>)
}
