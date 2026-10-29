package br.com.grupotechnos.watch.protocol.sma

import br.com.grupotechnos.watch.protocol.domain.WatchErrorCodes
import br.com.grupotechnos.watch.protocol.domain.WatchException
import br.com.grupotechnos.watch.protocol.driver.DriverLink
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.log.LogLevel
import br.com.grupotechnos.watch.protocol.log.WatchLogger
import br.com.grupotechnos.watch.protocol.profile.DeviceProfile
import br.com.grupotechnos.watch.protocol.profile.GattCandidate
import br.com.grupotechnos.watch.protocol.profile.Profiles
import br.com.grupotechnos.watch.protocol.profile.Quirks
import br.com.grupotechnos.watch.protocol.session.WatchClock
import br.com.grupotechnos.watch.protocol.testing.SmaWatchSimulator
import br.com.grupotechnos.watch.protocol.transport.WriteType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay

/** Relógio fixo para testes. */
internal class FixedWatchClock(
	private val now: Long,
	private val zone: String,
) : WatchClock {
	override fun nowEpochMillis(): Long = now

	override fun timeZoneId(): String = zone
}

/** Logger que guarda tudo em memória. */
internal class RecordingLogger : WatchLogger {
	val messages = mutableListOf<Triple<LogLevel, String, String>>()

	/** Chamadas de [frame]. O [FakeDriverLink] não registra pacotes, então qualquer chamada veio do driver. */
	var frameCalls: Int = 0
		private set

	override fun log(level: LogLevel, tag: String, message: String) {
		messages += Triple(level, tag, message)
	}

	override fun frame(direction: FrameDirection, characteristic: String, value: ByteArray, summary: String) {
		frameCalls++
	}

	fun contains(text: String): Boolean = messages.any { it.third.contains(text) }
}

/** Perfil fitGPS com quirks ajustáveis. */
internal fun testProfile(transform: (Quirks) -> Quirks = { it }): DeviceProfile =
	Profiles.MORMAII_FIT_GPS.copy(quirks = transform(Profiles.MORMAII_FIT_GPS.quirks))

/**
 * [DriverLink] de teste com um relógio SMA simulado em nível de byte ([SmaWatchSimulator]).
 *
 * Cada [write] espera [writeLatencyMs] de tempo virtual (como a confirmação GATT), só então registra o pacote em
 * [writes] e entrega ao relógio. As respostas do relógio entram em [notifications] fragmentadas como o firmware faz
 * (`SmaManager.java:506-528`): o L1 de 8 bytes numa notificação e o resto em pedaços de 20.
 */
internal class FakeDriverLink(
	override val profile: DeviceProfile = testProfile(),
	override val mtu: Int = 23,
	override val clock: WatchClock = FixedWatchClock(0, "America/Sao_Paulo"),
	private val writeLatencyMs: Long = 1,
) : DriverLink {
	override val gatt: GattCandidate = Profiles.SMA_NUS

	override val logger: RecordingLogger = RecordingLogger()

	private val channel = Channel<ByteArray>(Channel.UNLIMITED)

	override val notifications: ReceiveChannel<ByteArray> = channel

	/** Todos os pacotes escritos pelo driver, na ordem em que a escrita terminou. */
	val writes = mutableListOf<ByteArray>()

	val watch = SmaWatchSimulator { packet -> notify(packet) }

	override suspend fun write(value: ByteArray, type: WriteType) {
		check(type == WriteType.WITH_RESPONSE) { "SMA frames must be written with response" }
		if (writeLatencyMs > 0) delay(writeLatencyMs)
		writes += value.copyOf()
		watch.onPhoneWrite(value.copyOf())
	}

	override suspend fun read(service: String, characteristic: String): ByteArray =
		throw WatchException(WatchErrorCodes.TRANSPORT, "not supported in tests")

	/** Entrega uma notificação crua ao driver. */
	fun notify(packet: ByteArray) {
		channel.trySend(packet)
	}

	/** Simula a queda do link. */
	fun drop() {
		channel.close()
	}
}

/** Nome antigo do relógio simulado; a implementação foi para `testing/SmaWatchSimulator`, compartilhada com a sessão. */
internal typealias FakeSmaWatch = SmaWatchSimulator

/** Ver [br.com.grupotechnos.watch.protocol.testing.WatchReply]. */
internal typealias WatchReply = br.com.grupotechnos.watch.protocol.testing.WatchReply
