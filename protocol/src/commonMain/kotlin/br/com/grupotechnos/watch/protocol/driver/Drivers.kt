package br.com.grupotechnos.watch.protocol.driver

import br.com.grupotechnos.watch.protocol.ido.IdoDriver
import br.com.grupotechnos.watch.protocol.profile.Family
import br.com.grupotechnos.watch.protocol.sma.SmaDriver

/**
 * Escolhe o driver da família do perfil. É o único ponto da sessão que conhece as famílias.
 */
internal object Drivers {
	/** Cria o driver de uma conexão; a sessão chama uma vez por link, depois de configurá-lo. */
	fun create(link: DriverLink): DeviceDriver = when (link.profile.family) {
		Family.SMA -> SmaDriver(link)
		Family.IDO -> IdoDriver(link)
	}
}
