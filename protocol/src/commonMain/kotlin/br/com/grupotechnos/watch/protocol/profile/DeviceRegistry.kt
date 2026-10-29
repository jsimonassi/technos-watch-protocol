package br.com.grupotechnos.watch.protocol.profile

/**
 * Identificação de modelos a partir do que o scan enxerga.
 */
public object DeviceRegistry {
	/**
	 * Retorna o perfil do relógio anunciado, ou `null` se não for um modelo suportado.
	 *
	 * A comparação é pelo nome de advertising, exata, como no Core atual. Os serviços anunciados
	 * não são usados para decidir: não se sabe ainda se o fitGPS lista o NUS no advertising.
	 *
	 * @param bleName nome do advertising (pode ser `null` em alguns resultados de scan).
	 * @param advertisedServices UUIDs anunciados, para log e uso futuro.
	 */
	public fun identify(bleName: String?, advertisedServices: List<String>): DeviceProfile? {
		if (bleName == null) return null
		return Profiles.all.firstOrNull { profile -> bleName in profile.bleNames }
	}

	/** Retorna o perfil pelo [DeviceProfile.id], ou `null`. */
	public fun byId(id: String): DeviceProfile? = Profiles.all.firstOrNull { it.id == id }
}
