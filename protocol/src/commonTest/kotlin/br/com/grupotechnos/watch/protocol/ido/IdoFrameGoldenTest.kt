package br.com.grupotechnos.watch.protocol.ido

import br.com.grupotechnos.watch.protocol.util.Hex
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vetores dourados do protocolo iDO, copiados do log da SDK iDO 2.69.38 de produção com o Life Ultra GPS
 * (`20261029.log`, 2026-10-29; linhas no nome de cada vetor). Os TX são comparados byte a byte com o que a SDK mandou; os
 * RX são os bytes que o relógio respondeu, e os valores esperados são os que a SDK imprimiu em JSON para os mesmos
 * frames e os que a tela do relógio mostrava (`docs/protocolo-ido.md`, seção 7).
 */
class IdoFrameGoldenTest {
	private fun tx(command: IdoCommand, payload: ByteArray = ByteArray(0)): String =
		Hex.encode(IdoFrameCodec.encode(command.cmd, command.key, payload))

	private fun rx(hex: String): IdoFrame = assertIs<IdoDecoded.Frame>(IdoFrameCodec.decode(Hex.decode(hex))).frame

	@Test
	fun getBasicInfoMatchesLine88() {
		assertEquals("0201", tx(IdoCommand.GET_BASIC_INFO))
	}

	@Test
	fun bindMatchesLine144() {
		assertEquals("0401F1010102020100", tx(IdoCommand.BIND, IdoBindPayload.REQUEST))
	}

	@Test
	fun authReplyToTheRealChallengeMatchesLine154() {
		val reply = IdoBindReply.decode(rx("04 01 00 0c 00 31 bc c3 54 3f 1c e0 3e 1c 85 03 7c"))
		val challenge = reply.challenge ?: error("challenge missing")
		assertEquals("04050C00E03E1C85037CD182DFD13C60", tx(IdoCommand.BIND_AUTH, IdoAuth.reply(reply.encryptedVersion, challenge)))
	}

	@Test
	fun setTimeForThursdayEveningInSaoPauloMatchesLine5360() {
		val payload = IdoTimeCodec.encode(LocalDateTime(2026, 10, 29, 22, 8, 49), offsetMillis = -3 * 3_600_000L, fractionalZone = true)
		assertEquals("0301EA070A1D1608310300000000DC05", tx(IdoCommand.SET_TIME, payload))
	}

	@Test
	fun setTimeWithWholeHourZoneMatchesLine182() {
		// Antes de ler a tabela de funções, a SDK mandou o fuso em horas inteiras (-3 → 15). Mesmo dia, 22:08:24. A linha 186
		// repete o frame com semana 04 (o setTime do Core de produção, domingo = 0); o relógio aceitou os dois.
		val payload = IdoTimeCodec.encode(LocalDateTime(2026, 10, 29, 22, 8, 24), offsetMillis = -3 * 3_600_000L, fractionalZone = false)
		assertEquals("0301EA070A1D16081803000000000F00", tx(IdoCommand.SET_TIME, payload))
	}

	@Test
	fun firmwareAndLiveDataRequestsMatchLines5774And5833() {
		assertEquals("02EB", tx(IdoCommand.GET_FIRMWARE))
		assertEquals("02A0", tx(IdoCommand.GET_LIVE_DATA))
	}

	@Test
	fun basicInfoFromLine95() {
		val info = IdoBasicInfo.decode(rx("02 01 66 20 01 00 00 0e 01 00 01 f3 62 02 02 01 00 01 01 03"))
		assertEquals(
			IdoBasicInfo(
				deviceId = 8294,
				firmwareVersion = 1,
				mode = 0,
				batteryStatus = 0,
				batteryPercent = 14,
				pairFlag = 1,
				reboot = 0,
				platform = 98,
				shape = 2,
				deviceType = 2,
				gpsPlatform = 3,
			),
			info,
		)
		assertTrue(info.bound)
	}

	@Test
	fun bindReplyFromLine146() {
		val reply = IdoBindReply.decode(rx("04 01 00 0c 00 31 bc c3 54 3f 1c e0 3e 1c 85 03 7c"))
		assertEquals(IdoBindPayload.CODE_SUCCESS, reply.code)
		assertEquals(12, reply.authLength)
		assertEquals(0, reply.encryptedVersion)
		assertContentEquals(Hex.decode("31bcc3543f1ce03e1c85037c"), reply.challenge)
	}

	@Test
	fun bindReplyWithoutChallenge() {
		val reply = IdoBindReply.decode(rx("04 01 02"))
		assertEquals(IdoBindPayload.CODE_ALREADY_BOUND, reply.code)
		assertNull(reply.challenge)
	}

	@Test
	fun authResultFromLine158() {
		assertEquals(0, IdoAuth.authCode(rx("04 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")))
		assertEquals(1, IdoAuth.authCode(rx("04 05 01")))
	}

	@Test
	fun setTimeResultFromLine5362() {
		assertTrue(IdoTimeCodec.accepted(rx("03 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")))
		assertTrue(!IdoTimeCodec.accepted(rx("03 01 01")))
	}

	@Test
	fun firmwareFromLine5776() {
		assertEquals("1.1.1", IdoFirmwarePayload.decode(rx("02 eb 01 01 01 00 00 00 00 00 00 02")))
	}

	@Test
	fun liveDataFromLine5835MatchesTheWatchScreen() {
		val live = IdoLiveData.decode(rx("02 a0 1a 00 00 00 0d 00 00 00 19 00 00 00 00 00 00 00 8e"))
		assertEquals(IdoLiveData(steps = 26, calories = 13, distanceMeters = 25, activeTime = 0), live)
		// A tela mostrava 0,02 milha: 25 m são 0,016 milha.
		assertEquals(2.0, kotlin.math.round(live.distanceMeters / 1609.344 * 100))
	}
}
