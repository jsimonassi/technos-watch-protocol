package br.com.grupotechnos.watch.bridge

/**
 * O último relógio pareado, como o host o guarda ([CoreHost.loadPairing], [CoreHost.savePairing]).
 *
 * O [CoreBridge] grava quando a sessão chega em `READY` (salvo depois de um disconnect/forget do usuário), apaga no
 * `forgetDevice` e lê no `getLastPairedDevice`, na reconexão automática e para saber a retenção do `getHistorySleep`.
 *
 * @property address endereço do relógio (MAC no Android, UUID do periférico no iOS).
 * @property profileId id do `DeviceProfile` (ex.: `"fitgps"`).
 * @property name `appName` do perfil (ex.: `"fitGPS"`), ou o [profileId] se o perfil não existir mais; o host Android
 * devolve `""` quando a chave não foi gravada.
 */
public data class CorePairing(
	val address: String,
	val profileId: String,
	val name: String,
)
