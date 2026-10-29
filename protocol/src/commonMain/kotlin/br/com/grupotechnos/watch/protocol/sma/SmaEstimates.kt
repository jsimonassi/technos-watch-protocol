package br.com.grupotechnos.watch.protocol.sma

import kotlin.math.floor

/**
 * Calorias e distância estimadas a partir dos passos, com as fórmulas da SDK SMA e o usuário fixo do Core atual
 * (`docs/sma-dados.md`, seção 4).
 *
 * O relógio só informa passos. O Core atual calcula o resto com `SmaBleUtils.getCalorie/getDistance`
 * (`SmaBleUtils.java:89-95`) e um perfil fixo: 60 kg, 173,5 cm, gênero 1 (`SMAService.java:112-114`). As contas são
 * feitas em `Float`, na mesma ordem das expressões Java, para que os números saiam idênticos aos do Core
 * (os testes comparam com valores calculados rodando o próprio código Java da SDK).
 */
internal object SmaEstimates {
	/** Peso do usuário fixo do Core (`SMAService.java:112`). */
	const val USER_WEIGHT_KG: Float = 60.0f

	/** Altura do usuário fixo do Core (`SMAService.java:113`). */
	const val USER_HEIGHT_CM: Float = 173.5f

	/** Gênero do usuário fixo do Core: 1 = masculino (`SMAService.java:114`). */
	const val USER_GENDER: Int = 1

	/**
	 * `SmaBleUtils.getCalorie` (`SmaBleUtils.java:93-95`): `floor(k * peso * passos / 1000)` com
	 * `k = 0.46` para gênero 0 e `0.55` para os demais, em `Float`.
	 */
	fun calories(steps: Int, weightKg: Float = USER_WEIGHT_KG, gender: Int = USER_GENDER): Int {
		val factor = if (gender == 0) 0.46f else 0.55f
		return floor((factor * weightKg * steps.toFloat() / 1000.0f).toDouble()).toInt()
	}

	/**
	 * `SmaBleUtils.getDistance` (`SmaBleUtils.java:89-91`): `altura * passos * 45 / 10000 / 1000`, em `Float`.
	 * O resultado é em **quilômetros** (passada de 45% da altura em cm, dividida por 100 000); o Core trata assim
	 * ao multiplicar por 1000 para metros (`SMAService.java:864`).
	 */
	fun distanceKm(steps: Int, heightCm: Float = USER_HEIGHT_CM): Float =
		heightCm * steps.toFloat() * 45.0f / 10000.0f / 1000.0f

	/**
	 * [distanceKm] em metros, com a multiplicação por 1000 feita em `Float` como no Core
	 * (`SMADataPedometer.distance` é `float` e `SMAService.java:864` faz `distance * 1000`).
	 */
	fun distanceMeters(steps: Int, heightCm: Float = USER_HEIGHT_CM): Double =
		(distanceKm(steps, heightCm) * 1000.0f).toDouble()
}
