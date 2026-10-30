package br.com.grupotechnos.watch.protocol.profile

/**
 * Identificação de modelos a partir do que o scan enxerga.
 */
public object DeviceRegistry {
	/**
	 * Retorna o perfil do relógio anunciado, ou `null` se não for um modelo suportado.
	 *
	 * A comparação é pelo nome de advertising, exata, como no Core atual. Os serviços anunciados não servem para
	 * decidir: o fitGPS (firmware 0.2.1) anuncia `180f`, `180a` e `00000001-0000-1000-8000-00805f9b34fb`, mas não o
	 * NUS que ele usa (log do app de exemplo de 2026-10-29, scan em ts 1793272444786; a gravação correspondente é
	 * `fixtures/real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl`). Um filtro de scan por serviço NUS não o
	 * encontraria; só o filtro pelo nome encontra. O Life Ultra GPS (iDO) também é identificado pelo nome
	 * (`Life Ultra GPS`, ou `Smart Watch 6010` do catálogo antigo); o host ainda pode filtrar o scan pelo serviço `0AF0`.
	 *
	 * @param bleName nome do advertising (pode ser `null` em alguns resultados de scan).
	 * @param advertisedServices UUIDs anunciados, só para log.
	 */
	public fun identify(bleName: String?, advertisedServices: List<String>): DeviceProfile? {
		if (bleName == null) return null
		return Profiles.all.firstOrNull { profile -> bleName in profile.bleNames }
	}

	/** Retorna o perfil pelo [DeviceProfile.id], ou `null`. */
	public fun byId(id: String): DeviceProfile? = Profiles.all.firstOrNull { it.id == id }
}
