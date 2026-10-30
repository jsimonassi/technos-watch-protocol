package br.com.grupotechnos.watch.protocol.profile

/**
 * Perfis conhecidos.
 */
public object Profiles {
	/**
	 * Serviço SMA "ROUND" (`FF60`). A SDK Android prefere este quando existe (`SmaManager.selectUUID`). O fitGPS com
	 * firmware 0.2.1 não o expõe (hipótese H6 refutada para esse firmware; `docs/decisoes.md`, ADR 6); continua primeiro
	 * na lista porque a ordem não custa nada a um relógio que não o tem.
	 */
	public val SMA_ROUND: GattCandidate = GattCandidate(
		service = "0000ff60-0000-1000-8000-00805f9b34fb",
		write = "0000ff61-0000-1000-8000-00805f9b34fb",
		notify = "0000ff62-0000-1000-8000-00805f9b34fb",
	)

	/**
	 * Nordic UART Service. É o único que o Core iOS atual usa com o fitGPS (`SMAService.m:1056-1061`) e o único serviço
	 * de dados que o fitGPS 0.2.1 expõe (`fixtures/real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl`; a lista
	 * de serviços está em `docs/protocolo-sma.md`, seção 1). O relógio não o anuncia no advertising.
	 */
	public val SMA_NUS: GattCandidate = GattCandidate(
		service = "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
		write = "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
		notify = "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
	)

	/**
	 * Mormaii fitGPS. Nome BLE e appName iguais aos do Core atual (`AvailableDevices.java:21`);
	 * slug igual a `SMART_WATCH_SLUG.fitGPS` do app.
	 *
	 * Passos em [StepsAggregation.DAY_CUMULATIVE]: no firmware 0.2.1 o registro `mode 0` é o acumulado do dia, não o
	 * total da hora (confirmado no relógio em 2026-10-29; `docs/decisoes.md`, ADR 1). O Core de produção soma as horas
	 * e conta a mais.
	 */
	public val MORMAII_FIT_GPS: DeviceProfile = DeviceProfile(
		id = "fitgps",
		bleNames = listOf("Mormaii_fitGPS"),
		appName = "fitGPS",
		slug = "b3",
		family = Family.SMA,
		gattCandidates = listOf(SMA_ROUND, SMA_NUS),
		quirks = Quirks(
			requestMtu = 247,
			maxFragment = null,
			connectTimeoutMs = 30_000,
			commandTimeoutMs = 4_000,
			fragmentGapTimeoutMs = 8_000,
			bindTimeoutMs = 60_000,
			postLoginDelayMs = 3_000,
			bindPayload = BindPayloadKind.ANDROID_FF,
			bindUserId = 1,
			stepsAggregation = StepsAggregation.DAY_CUMULATIVE,
			historyRetentionDays = 30,
		),
	)

	/** Todos os perfis registrados. */
	public val all: List<DeviceProfile> = listOf(MORMAII_FIT_GPS)
}
