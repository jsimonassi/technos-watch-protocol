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

	/**
	 * Serviço `FEEA` da CRP (Moyoung): escrita dos frames em `FEE2` (só write-without-response no P85) e respostas em
	 * `FEE3`. As notificações laterais são `2A37` (frequência cardíaca, `180D`), `FEE1` (passos do dia, `FEEA`) e `2A19`
	 * (bateria, `180F`), ligadas depois de `FEE3`, nessa ordem.
	 *
	 * - Canal de comandos: `sdk:q/b.java:36-61`, `sdk:m/f.java:132-140`.
	 * - Ordem das CCCDs: a captura do app de produção liga `FEE3`, `2A37` e `FEE1`
	 *   (`fixtures/reference/condorreebok/MOY-VS02-2.0.0/condor-app-pair-sync.jsonl`, cabeçalho `gatt`); a SDK tentaria
	 *   também `FEE7`, `FEE8`, `4A02` e `FEE9`, que o P85 não tem no `FEEA` (`sdk:q/b.java:162-174`).
	 * - `2A19` tem CCCD no P85 (propriedades `0x12`) e entra por último, como o `subscribeDeviceBattery` da SDK
	 *   (`sdk:h/a.java:423-429`; `sdk:m/d.java:14-43`): sem a CCCD, nem o Android nem o iOS entregam ao app o aviso de
	 *   bateria que o relógio manda uma vez por minuto.
	 */
	public val CRP_FEEA: GattCandidate = GattCandidate(
		service = "0000feea-0000-1000-8000-00805f9b34fb",
		write = "0000fee2-0000-1000-8000-00805f9b34fb",
		notify = "0000fee3-0000-1000-8000-00805f9b34fb",
		sideNotify = listOf(
			NotifyChannel("0000180d-0000-1000-8000-00805f9b34fb", "00002a37-0000-1000-8000-00805f9b34fb"),
			NotifyChannel("0000feea-0000-1000-8000-00805f9b34fb", "0000fee1-0000-1000-8000-00805f9b34fb"),
			NotifyChannel("0000180f-0000-1000-8000-00805f9b34fb", "00002a19-0000-1000-8000-00805f9b34fb"),
		),
	)

	/**
	 * Condor Reebok (família CRP, firmware `MOY-VS02-2.0.0`).
	 *
	 * - Nomes: o Core aceita `Condor Reebok` ou qualquer nome que comece com `P85`
	 *   (`core@v4.3.57:android/src/main/java/com/reactlibrary/devices/AvailableDevices.java:55-57`); o nome GAP (2A00) do
	 *   relógio da captura é exatamente `P85` (o `device` do cabeçalho de
	 *   `fixtures/reference/condorreebok/MOY-VS02-2.0.0/condor-app-pair-sync.jsonl`, lido de 2A00 em HCI 15:43:12.351), por
	 *   isso ele vem primeiro em [DeviceProfile.bleNames] e
	 *   sai no `DeviceInfo.bleName` e no cabeçalho das gravações. O slug é o do app Condor
	 *   (`GTE-CondorSmart/src/constants/index.ts:116`).
	 * - `requestMtu = 512`: o pedido da SDK nos relógios V2/V3 (`sdk:e/f.java:16-52`). Na captura quem abre a troca é o
	 *   relógio, com 517 (cabeçalho `mtu` e `mtuInitiator` da fixture de referência), e o pedido do app não gera tráfego.
	 * - `commandGapMs = 200`: o atraso padrão da fila da SDK depois de cada frame (`sdk:m/f.java:94-130`); o driver usa a
	 *   tabela por comando a partir deste valor.
	 * - `commandTimeoutMs = 4000`: espera por resposta de cada pedido; a SDK só verifica o seu de 5 s quando outro comando
	 *   entra na fila (`sdk:p/c.java:33-43`).
	 * - `postLoginDelayMs = 250`: na reconexão da captura (conexão 16) o acerto de hora sai cerca de 250 ms depois do
	 *   `B7 0E` (`fixtures/reference/condorreebok/MOY-VS02-2.0.0/condor-app-bt-reconnect.jsonl`, `atMs` 2046 e 2295).
	 * - `bindPayload`, `bindUserId` e `bindTimeoutMs` não se aplicam (o CRP não tem vínculo de aplicação) e ficam em
	 *   valores neutros.
	 * - `HOURLY_SUM`: cada amostra é uma meia hora do histórico `0x59`, mais a amostra de totais do dia.
	 * - `classicBond`: o relógio tem HFP/A2DP no mesmo endereço, e o Core de produção cria o vínculo BR/EDR
	 *   (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/connectionmanager/CRPBleConnectHelper.java:664-668`).
	 * - `iosTransportBridging`: a SDK iOS conecta com `CBConnectPeripheralOptionEnableTransportBridgingKey`
	 *   (`iOS-bin 0xb2e60`; fase 0, CON-17).
	 */
	public val CONDOR_REEBOK: DeviceProfile = DeviceProfile(
		id = "condorreebok",
		bleNames = listOf("P85", "Condor Reebok"),
		appName = "Condor Reebok",
		slug = "reebok",
		family = Family.CRP,
		gattCandidates = listOf(CRP_FEEA),
		quirks = Quirks(
			requestMtu = 512,
			maxFragment = null,
			connectTimeoutMs = 30_000,
			commandTimeoutMs = 4_000,
			fragmentGapTimeoutMs = 3_000,
			bindTimeoutMs = 4_000,
			postLoginDelayMs = 250,
			bindPayload = BindPayloadKind.ANDROID_FF,
			bindUserId = 1,
			stepsAggregation = StepsAggregation.HOURLY_SUM,
			historyRetentionDays = 30,
			commandGapMs = 200,
			classicBond = true,
			iosTransportBridging = true,
		),
		bleNamePrefixes = listOf("P85"),
	)

	/** Todos os perfis registrados. */
	public val all: List<DeviceProfile> = listOf(MORMAII_FIT_GPS, MORMAII_LIFE_ULTRA_GPS, CONDOR_REEBOK)
}
