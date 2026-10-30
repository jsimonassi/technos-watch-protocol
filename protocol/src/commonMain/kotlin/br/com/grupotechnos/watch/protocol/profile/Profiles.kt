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

	/**
	 * Serviço iDO `0AF0`: escrita dos comandos normais em `0AF6` e notificações em `0AF7` (normal) e `0AF2` (saúde). A
	 * SDK iDO liga o CCCD de `0AF7` e depois o de `0AF2` antes de mandar qualquer comando (log da SDK de produção de
	 * 2026-10-29, `20261029.log` linhas 63-70) e entrega as notificações dos dois ao mesmo decodificador. A escrita de
	 * saúde (`0AF1`, comandos `0x08`/`0x09`) não é usada (`docs/protocolo-ido.md`, seção 1).
	 */
	public val IDO_MAIN: GattCandidate = GattCandidate(
		service = "00000af0-0000-1000-8000-00805f9b34fb",
		write = "00000af6-0000-1000-8000-00805f9b34fb",
		notify = "00000af7-0000-1000-8000-00805f9b34fb",
		extraNotify = listOf("00000af2-0000-1000-8000-00805f9b34fb"),
	)

	/**
	 * Mormaii Life Ultra GPS (família iDO, plataforma SiFli 98, `gps_platform` 3). O scan de produção viu o nome
	 * `Life Ultra GPS` (`20261029.log` linha 74); o catálogo do Core atual lista `Smart Watch 6010` como nome BLE e
	 * `Life Ultra GPS` como appName (`AvailableDevices.java:39`). O slug é o `SMART_WATCH_SLUG` do app (`lifeultragps`).
	 *
	 * - Passos em [StepsAggregation.DAY_CUMULATIVE]: o driver lê o total do dia ao vivo (`02/A0`), que é o acumulador do
	 *   dia (26 passos no log e na tela do relógio, 2026-10-29).
	 * - `bindTimeoutMs = 90000`: a resposta da autenticação (`04/05`) só chega depois do toque na tela; o relógio espera
	 *   até 240 s (`bind_confirm_timeout`, `20261029.log` linha 101).
	 * - `requestMtu = 23`: a SDK não pede MTU, então a sessão também não pede.
	 * - `maxFragment = 20`: todo frame legado tem no máximo 20 bytes.
	 * - `postLoginDelayMs = 1000`: a SDK acerta a hora logo depois do vínculo (`20261029.log` linhas 165-182).
	 * - `fragmentGapTimeoutMs`, `bindPayload` e `bindUserId` são do SMA e ficam em valores neutros.
	 */
	public val MORMAII_LIFE_ULTRA_GPS: DeviceProfile = DeviceProfile(
		id = "lifeultragps",
		bleNames = listOf("Life Ultra GPS", "Smart Watch 6010"),
		appName = "Life Ultra GPS",
		slug = "lifeultragps",
		family = Family.IDO,
		gattCandidates = listOf(IDO_MAIN),
		quirks = Quirks(
			requestMtu = 23,
			maxFragment = 20,
			connectTimeoutMs = 30_000,
			commandTimeoutMs = 4_000,
			fragmentGapTimeoutMs = 8_000,
			bindTimeoutMs = 90_000,
			postLoginDelayMs = 1_000,
			bindPayload = BindPayloadKind.ANDROID_FF,
			bindUserId = 1,
			stepsAggregation = StepsAggregation.DAY_CUMULATIVE,
			historyRetentionDays = 30,
		),
	)

	/** Todos os perfis registrados. */
	public val all: List<DeviceProfile> = listOf(MORMAII_FIT_GPS, MORMAII_LIFE_ULTRA_GPS)
}
