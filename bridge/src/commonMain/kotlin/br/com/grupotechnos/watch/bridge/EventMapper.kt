package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.domain.Battery
import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.DeviceInfo
import br.com.grupotechnos.watch.protocol.domain.SleepSession
import br.com.grupotechnos.watch.protocol.domain.StepsDay
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.profile.DeviceProfile
import kotlinx.datetime.TimeZone

/**
 * Tradução dos tipos de domínio para os payloads dos eventos JS, nos formatos de `src/types/events.ts` do
 * `react-native-technos-core-next`.
 *
 * Kotlin puro (só `Map`/`List`/primitivos), para ser testado sem React Native; a conversão para o que a ponte do RN
 * aceita fica em cada host: `WritableConverter` no Android (Repo 2) e `FoundationConverter` (pacote `ios` deste
 * módulo, só no iOS).
 *
 * Datas: usa as strings ISO que o domínio já entrega (`StepsDay.startOfDayIso`, `SleepSession.startIso`...).
 * Só o `date` do sono é calculado aqui, a partir do `dayKey`, com o mesmo padrão `yyyy-MM-dd'T'HH:mm:ssXXX`
 * de `SMAHelper.getDateString` do Core atual (`SMAHelper.java:36-39`). Com offset zero (telefone em UTC, Lisboa no
 * inverno) esse padrão dá `Z`, como o `ZZZZZ` do `SimpleDateFormat` (ICU) do Core; as strings que vêm do :protocol usam
 * sempre `+00:00`. Os dois formatos são ISO-8601 válidos e o `moment()` do app lê os dois; a diferença é de paridade
 * com o Core, não de leitura (`docs/api-js.md` do Repo 2, Datas). O fuso é o do telefone na hora da chamada
 * (`TimeZone.currentSystemDefault()`, o `ZoneId.systemDefault()` do host Android antigo).
 *
 * Tipos dos valores, os mesmos do host Android antigo (o `WritableConverter` não mudou): inteiros vão como [Int],
 * decimais e epoch millis como [Double], texto como [String], `null`, e mapas e listas aninhados. O `WritableMap` só tem
 * `putInt`/`putDouble` e o JS recebe `number` nos dois casos.
 */
public object EventMapper {
	/** `onScannedDevice` (`ScannedDeviceEvent`). `name` é o `appName`, que o app filtra em `ALLOWED_DEVICE_NAMES`. */
	public fun scannedDevice(profile: DeviceProfile, address: String, rssi: Int, bleName: String): Map<String, Any?> = mapOf(
		"name" to profile.appName,
		"deviceId" to address,
		"rssi" to rssi,
		"bleName" to bleName,
		"profileId" to profile.id,
	)

	/** `onConnectStateChanged` (`ConnectStateChangedEvent`). */
	public fun connectStateChanged(state: ConnectionState, phase: String?): Map<String, Any?> = mapOf(
		"state" to StateMapper.toDeviceConnectState(state),
		"sessionState" to state.name,
		"phase" to phase,
	)

	/** `onDeviceInfo` (`DeviceInfoEvent`). */
	public fun deviceInfo(info: DeviceInfo): Map<String, Any?> = mapOf(
		"deviceId" to info.address,
		"profileId" to info.profileId,
		"bleName" to info.bleName,
		"firmware" to info.firmware,
		"bleVersion" to info.bleVersion,
	)

	/**
	 * `onFirmwareInfo` (`FirmwareInfoEvent`). `braceletType` 0 (`SDKUtils.FITGPS`) e `platformCode` 0 são
	 * os valores que o Core atual emite para SMA (`SMAService.java:241-244`, `EventHandler.kt:274-287`).
	 * `deviceId` é novo: o Core atual não o preenche para SMA.
	 */
	public fun firmwareInfo(info: DeviceInfo): Map<String, Any?> = mapOf(
		"version" to info.firmware,
		"braceletType" to 0,
		"platformCode" to 0,
		"deviceId" to info.address,
	)

	/** `onBatteryStatus` (`BatteryStatusEvent`). O SMA não informa carregamento; `battery` é o campo legado `"N%"`. */
	public fun battery(battery: Battery): Map<String, Any?> = mapOf(
		"percentage" to battery.percent,
		"charging" to false,
		"battery" to "${battery.percent}%",
	)

	/** `onPedometer` (`PedometerEvent`): total de hoje. */
	public fun pedometer(today: StepsDay): Map<String, Any?> = mapOf(
		"pedometer" to today.steps,
		"calorie" to today.calories,
		"distance" to today.distanceMeters,
		"date" to today.startOfDayIso,
	)

	/** Um dia de `onHistoryPedometer` (`PedometerHistoryDay`). */
	public fun pedometerDay(day: StepsDay): Map<String, Any?> = mapOf(
		"date" to day.startOfDayIso,
		"distance" to day.distanceMeters,
		"calorie" to day.calories,
		"pedometer" to day.steps,
	)

	/** `onHistoryPedometer` (`HistoryPedometerEvent`). Lista vazia também é emitida, como no Core iOS atual. */
	public fun historyPedometer(days: List<StepsDay>): Map<String, Any?> = mapOf(
		"historypedometer" to days.map(::pedometerDay),
	)

	/**
	 * Um dia de `onHistorySleep` (`SleepHistoryDay`). `date` é o início do dia em que a pessoa acordou
	 * ([SleepSession.dayKey]) no fuso atual do telefone. O SMA não mede respiração nem REM: `respiratoryRateItems` sai
	 * vazio.
	 *
	 * @throws IllegalArgumentException se o `dayKey` da sessão não for uma data ISO (como em [startOfDayIso]; no Swift,
	 * um erro que se pega com `try`).
	 */
	@Throws(IllegalArgumentException::class)
	public fun sleepDay(session: SleepSession): Map<String, Any?> = sleepDay(session, TimeZone.currentSystemDefault())

	/** [sleepDay] no fuso [zone] (testes e quem já leu o fuso uma vez para o evento inteiro). */
	internal fun sleepDay(session: SleepSession, zone: TimeZone): Map<String, Any?> = mapOf(
		"date" to startOfDayIso(session.dayKey, zone),
		"sleep" to mapOf(
			"start" to session.startIso,
			"end" to session.endIso,
			"items" to session.stages.map { stage ->
				mapOf(
					"typeItem" to stage.type.code,
					"startItem" to stage.startIso,
					"endItem" to stage.endIso,
				)
			},
			"respiratoryRateItems" to emptyList<Any?>(),
			"totalSleepMinutes" to session.totalMinutes,
			"lightSleepMinutes" to session.lightMinutes,
			"deepSleepMinutes" to session.deepMinutes,
			"remSleepMinutes" to session.remMinutes,
			"wakeUpMinutes" to session.awakeMinutes,
		),
	)

	/**
	 * `onHistorySleep` (`HistorySleepEvent`), no fuso atual do telefone, lido uma vez para o evento inteiro.
	 *
	 * @throws IllegalArgumentException se o `dayKey` de alguma sessão não for uma data ISO (como em [startOfDayIso]; no
	 * Swift, um erro que se pega com `try`).
	 */
	@Throws(IllegalArgumentException::class)
	public fun historySleep(sessions: List<SleepSession>): Map<String, Any?> =
		historySleep(sessions, TimeZone.currentSystemDefault())

	/** [historySleep] no fuso [zone]. */
	internal fun historySleep(sessions: List<SleepSession>, zone: TimeZone): Map<String, Any?> = mapOf(
		"historysleep" to sessions.map { sleepDay(it, zone) },
	)

	/** `onSyncStarted` e `onSyncFinished`: sem campos. */
	public fun empty(): Map<String, Any?> = emptyMap()

	/** `onSyncProgress` (`SyncProgressEvent`), com [percent] limitado a 0..100. */
	public fun syncProgress(percent: Int): Map<String, Any?> = mapOf("progress" to percent.coerceIn(0, 100))

	/** `onError` (`ErrorEvent`). [requestId] é `-1` quando o erro não veio de um comando. */
	public fun error(requestId: Int, code: String, message: String, method: String?): Map<String, Any?> = mapOf(
		"requestId" to requestId,
		"code" to code,
		"message" to message,
		"method" to method,
	)

	/** `onLog` e itens de `getLogs` (`LogEvent`). [ts] vai como [Double] porque epoch millis não cabe em `Int`. */
	public fun log(ts: Long, level: LogLevel, tag: String, message: String, dir: String?, hex: String?): Map<String, Any?> = mapOf(
		"ts" to ts.toDouble(),
		"level" to level.name,
		"tag" to tag,
		"message" to message,
		"dir" to dir,
		"hex" to hex,
	)

	/** Retorno de `getLastPairedDevice` (`LastPairedDevice`). */
	public fun lastPairedDevice(address: String, name: String, profileId: String): Map<String, Any?> = mapOf(
		"deviceId" to address,
		"name" to name,
		"profileId" to profileId,
	)

	/**
	 * Início do dia local de [dayKey] (`yyyy-MM-dd`) no fuso atual do telefone, em ISO com offset, ex.:
	 * `"2026-10-28T00:00:00-03:00"`.
	 *
	 * O `@Throws` existe por causa do iOS: sem ele, a exceção que sai do Kotlin para o Swift/ObjC encerra o processo. Com
	 * ele, o Swift vê `startOfDayIso(dayKey:) throws -> String` (no ObjC, `startOfDayIsoDayKey:error:`), e um `dayKey`
	 * inválido chega como `NSError`. No Android nada muda. O mesmo vale para [sleepDay] e [historySleep].
	 *
	 * @throws IllegalArgumentException se [dayKey] não for uma data ISO (no iOS, também um ano fora do intervalo do
	 * `kotlinx-datetime`).
	 */
	@Throws(IllegalArgumentException::class)
	public fun startOfDayIso(dayKey: String): String = startOfDayIso(dayKey, TimeZone.currentSystemDefault())

	/**
	 * [startOfDayIso] no fuso [zone]. Como `LocalDate.atStartOfDay(zone)`: num dia cuja meia-noite não existe (horário de
	 * verão), o início é o fim do buraco (`2018-11-04T01:00:00-02:00` em `America/Sao_Paulo`). Offset zero sai `Z`.
	 */
	internal fun startOfDayIso(dayKey: String, zone: TimeZone): String = IsoDates.startOfDayIso(dayKey, zone)
}
