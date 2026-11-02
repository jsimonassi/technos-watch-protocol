package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.crp.CrpCategoryPage
import br.com.grupotechnos.watch.protocol.crp.CrpCommand
import br.com.grupotechnos.watch.protocol.crp.CrpReassembler
import br.com.grupotechnos.watch.protocol.crp.CrpStepsMapper
import br.com.grupotechnos.watch.protocol.crp.CrpTimeCodec
import br.com.grupotechnos.watch.protocol.domain.Battery
import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import br.com.grupotechnos.watch.protocol.domain.DataKind
import br.com.grupotechnos.watch.protocol.domain.DeviceInfo
import br.com.grupotechnos.watch.protocol.domain.StepsDay
import br.com.grupotechnos.watch.protocol.domain.StepsSample
import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.profile.DeviceRegistry
import br.com.grupotechnos.watch.protocol.profile.Family
import br.com.grupotechnos.watch.protocol.testing.CrpReplayScenario
import br.com.grupotechnos.watch.protocol.testing.Fixtures
import br.com.grupotechnos.watch.protocol.testing.REPLAY_ADDRESS
import br.com.grupotechnos.watch.protocol.testing.RecordedPacket
import br.com.grupotechnos.watch.protocol.testing.Recording
import br.com.grupotechnos.watch.protocol.testing.RecordingLoader
import br.com.grupotechnos.watch.protocol.testing.ReplayAction
import br.com.grupotechnos.watch.protocol.testing.SessionEvent
import br.com.grupotechnos.watch.protocol.testing.runCrpReplay
import br.com.grupotechnos.watch.protocol.util.Hex
import br.com.grupotechnos.watch.protocol.util.TimeFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Toda gravação de `fixtures/real` cujo `device` é de um perfil CRP vira um replay estrito, com as ações deduzidas dos
 * pedidos ([CrpReplayScenario]) e a máscara CRP, sobre uma `WatchSession` de verdade. O `RealFixturesReplayTest` pula
 * essas gravações. As fixtures de referência (`fixtures/reference/`), gravadas de outro app, não entram aqui.
 *
 * A primeira gravação real ([CRP_ANDROID_RECORDING], 2026-11-01) é do app de exemplo num Android, com o P85 (firmware
 * `MOY-VS02-2.0.0`): pareamento, esquecer, pareamento de novo (com o vínculo clássico recusado no telefone, que fica no
 * host e não aparece nos frames), firmware duas vezes, bateria, acerto de hora, a releitura que a sessão fez sozinha
 * quando FEE1 notificou passos sem as meias horas lidas, Refresh steps, Sync completo e o Bluetooth desligado e religado.
 * A segunda ([CRP_IOS_RECORDING], 2026-11-01) é do mesmo relógio com o app de exemplo num iPhone (MTU 515): pareamento
 * com o vínculo clássico "não suportado" no host (o iOS não cria vínculo BR/EDR; o P85 já estava pareado em Ajustes >
 * Bluetooth), firmware duas vezes, bateria, acerto de hora, Refresh steps, Sync completo e uma reconexão. Para uma listagem
 * quebrada não pular tudo em silêncio, as duas têm de aparecer na listagem de `fixtures/real`.
 *
 * Roda nos quatro targets; cada gravação imprime uma linha `REPLAY` no `system-out` do relatório, e os testes nomeados
 * das duas gravações imprimem também `STEPS-DAY` e `SLEEP`. O relógio do replay fica parado no `recordedAt` do
 * cabeçalho (`TestClock`, tempo virtual) e o fuso é o do cabeçalho: nada depende do relógio nem do fuso da máquina.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrpRealFixturesReplayTest {
	@Test
	fun everyCrpRealRecordingReplays() = runTest {
		val files = Fixtures.jsonlUnder("real").filter { path ->
			DeviceRegistry.identify(RecordingLoader.load(path).header.device, emptyList())?.family == Family.CRP
		}
		val listed = files.map { Fixtures.relative(it) }
		assertTrue(CRP_ANDROID_RECORDING in listed, "CRP recordings under ${Fixtures.path("real")}: $listed")
		assertTrue(CRP_IOS_RECORDING in listed, "CRP recordings under ${Fixtures.path("real")}: $listed")
		val failures = files.mapNotNull { path ->
			val recording = RecordingLoader.load(path)
			val actions = try {
				CrpReplayScenario.deduce(recording)
			} catch (error: IllegalArgumentException) {
				println("REPLAY ${Fixtures.relative(path)}: not replayed (${error.message})")
				return@mapNotNull "${recording.source}: ${error.message}"
			}
			val outcome = runCrpReplay(recording, actions)
			try {
				val replay = outcome.replay
				println(
					"REPLAY ${Fixtures.relative(path)}: ${recording.packets.size} packets, TX matched ${replay.matchedTx}/${replay.recordedTx}, " +
						"READ matched ${replay.matchedReads}/${replay.recordedReads}, final state ${outcome.session.state}",
				)
				val problems = replay.problems().toMutableList()
				val expected = expectedFinalState(actions)
				if (outcome.session.state != expected) problems += "final state ${outcome.session.state}, expected $expected"
				for ((action, id) in outcome.requestIds) {
					val terminals = outcome.listener.terminals(id)
					if (terminals.size != 1 || terminals.single() is SessionEvent.Error) problems += "$action #$id ended with $terminals"
				}
				if (problems.isEmpty()) null else "${recording.source} (actions $actions):\n" + problems.joinToString("\n")
			} finally {
				outcome.session.close()
				runCurrent()
			}
		}
		if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
	}

	@Test
	fun androidRecordingGivesWhatTheWatchAnswered() = runTest {
		val recording = RecordingLoader.loadFixture(CRP_ANDROID_RECORDING)
		assertEquals("P85", recording.header.device)
		assertEquals("MOY-VS02-2.0.0", recording.header.firmware)
		assertEquals(517, recording.header.mtu)
		val zone = recording.header.timeZone
		// A releitura pedida por FEE1 (atMs 222901) não é ação do host: o deduce a pula e a sessão a faz sozinha. O #8 do
		// app foi um Refresh steps (log: `Session/refreshSteps queued #8`, sem SYNCING); o deduce o vê como Sync([STEPS]),
		// porque os bytes são os mesmos, e este roteiro escrito à mão usa o que o log mostra.
		val actions = listOf(
			ReplayAction.Pair,
			ReplayAction.Forget,
			ReplayAction.Pair,
			ReplayAction.ReadDeviceInfo,
			ReplayAction.ReadDeviceInfo,
			ReplayAction.ReadBattery,
			ReplayAction.SetTime,
			ReplayAction.RefreshSteps,
			ReplayAction.Sync(listOf(DataKind.STEPS, DataKind.SLEEP)),
			ReplayAction.BluetoothCycle,
		)
		assertEquals(
			actions.map { if (it == ReplayAction.RefreshSteps) ReplayAction.Sync(listOf(DataKind.STEPS)) else it },
			CrpReplayScenario.deduce(recording),
		)
		val outcome = runCrpReplay(recording, actions)
		try {
			val replay = outcome.replay
			assertEquals(emptyList(), replay.problems())
			assertEquals(38, replay.recordedTx)
			assertEquals(38, replay.matchedTx)
			assertEquals(7, replay.recordedReads)
			assertEquals(7, replay.matchedReads)
			assertTrue(replay.remaining.isEmpty(), "unconsumed: ${replay.remaining}")
			assertEquals(ConnectionState.READY, outcome.session.state)
			val handshake = listOf("CONNECTING", "CONFIGURING", "HANDSHAKING", "HANDSHAKING[VERSION]", "READY")
			// Só o syncHistory (#9 no app) passa por SYNCING, como no log; o refreshSteps e a releitura interna, não.
			assertEquals(
				handshake + "DISCONNECTED" + handshake + listOf("SYNCING", "READY") +
					listOf("BLUETOOTH_OFF", "RECONNECTING", "CONFIGURING", "HANDSHAKING", "HANDSHAKING[VERSION]", "READY"),
				outcome.listener.stateTrail(),
			)
			val ids = outcome.requestIds.map { it.second }
			outcome.listener.assertOneTerminalEach(ids)
			val info = DeviceInfo("condorreebok", REPLAY_ADDRESS, "P85", "MOY-VS02-2.0.0", "MOYOUNG-V2")
			val todaySteps = StepsDay("2026-11-01", "2026-11-01T00:00:00-03:00", 21, 1, 17.0)
			assertEquals(
				listOf(
					SessionEvent.Completed(ids[0]),
					SessionEvent.Completed(ids[1]),
					SessionEvent.Completed(ids[2]),
					SessionEvent.Info(ids[3], info),
					SessionEvent.Info(ids[4], info),
					SessionEvent.BatteryLevel(ids[5], Battery(43, charging = false)),
					SessionEvent.Completed(ids[6]),
					SessionEvent.LiveSteps(ids[7], todaySteps),
					SessionEvent.SyncFinished(ids[8]),
					SessionEvent.Completed(ids[9]),
				),
				ids.map { outcome.listener.terminal(it) },
			)

			// O F8 do Forget saiu uma vez, logo depois do acerto de hora automático da primeira conexão.
			val writes = outcome.transport.writes.map { Hex.encode(it) }
			assertEquals(1, writes.count { it == "FEEA2005F8" })
			assertEquals("FEEA200BBB0700D0D5FFFF", writes[writes.indexOf("FEEA2005F8") - 1])

			// 2A19 notificou 0x2B cinco vezes sem pedido (a cada ~60 s, nas três conexões): cinco onBattery sem requestId.
			assertEquals(
				List(5) { SessionEvent.BatteryLevel(WatchSession.NO_REQUEST_ID, Battery(43, charging = false)) },
				outcome.listener.events.filterIsInstance<SessionEvent.BatteryLevel>().filter { it.requestId == WatchSession.NO_REQUEST_ID },
			)

			// Cada 31 gravado traz o relógio do telefone na hora do envio (recordedAt + atMs, no segundo): é só isso que a
			// máscara zera. O fuso (BB 07 00 D0 D5 FF FF, -10800 s) é comparado inteiro.
			val recordedTimes = recording.packets.filter { it.direction == FrameDirection.TX && Hex.encode(it.value).startsWith("FEEA200A31") }
			assertEquals(4, recordedTimes.size)
			for (packet in recordedTimes) {
				val sent = CrpTimeCodec.decodeWallClock(packet.value.copyOfRange(5, 10)).toInstant(TimeZone.UTC).epochSeconds
				val phone = TimeFormat.toLocalWallClock(recording.header.recordedAtEpochMillis + packet.atMs, zone)
					.toInstant(TimeZone.UTC).epochSeconds
				assertTrue(abs(sent - phone) <= 1, "31 at atMs ${packet.atMs}: watch time ${sent - phone} s away from the phone clock")
			}
			assertEquals(4, writes.count { it == "FEEA200BBB0700D0D5FFFF" })

			// Passos: o 59 03 trouxe 0x4D (77) na meia hora 15:30 de ontem, e o 33 01 respondeu 77 passos, 63 m e 4 kcal.
			val samples = outcome.store.loadSteps()
			val yesterday = samples.filter { TimeFormat.dayKey(it.atEpochMillis, zone) == "2026-10-31" }
			assertEquals(
				listOf(StepsSample(at("2026-10-31T15:30:00-03:00"), 77, CrpStepsMapper.MODE_BUCKET, 0, 0.0)),
				yesterday.filter { it.mode == CrpStepsMapper.MODE_BUCKET && it.steps != 0 },
			)
			assertEquals(
				listOf(StepsSample(at("2026-10-31T00:00:01-03:00"), 0, CrpStepsMapper.MODE_DAY_TOTALS, 4, 63.0)),
				yesterday.filter { it.mode == CrpStepsMapper.MODE_DAY_TOTALS },
			)
			// Hoje as meias horas vieram zeradas (os 21 passos estavam na meia hora aberta) e FEE1 deu 21 passos, 17 m e 1 kcal.
			val today = samples.filter { TimeFormat.dayKey(it.atEpochMillis, zone) == "2026-11-01" }
			assertTrue(today.filter { it.mode == CrpStepsMapper.MODE_BUCKET }.all { it.steps == 0 }, today.toString())
			assertEquals(
				listOf(StepsSample(at("2026-11-01T16:30:00-03:00"), 21, CrpStepsMapper.MODE_LIVE_REMAINDER, 0, 0.0)),
				today.filter { it.mode == CrpStepsMapper.MODE_LIVE_REMAINDER },
			)
			val days = outcome.session.getStepsDays(3).associateBy { it.dayKey }
			assertEquals(setOf("2026-10-31", "2026-11-01"), days.keys)
			assertEquals(Triple(77, 4, 63.0), days.getValue("2026-10-31").let { Triple(it.steps, it.calories, it.distanceMeters) })
			assertEquals(Triple(21, 1, 17.0), days.getValue("2026-11-01").let { Triple(it.steps, it.calories, it.distanceMeters) })
			println(
				"STEPS-DAY $CRP_ANDROID_RECORDING: 2026-10-31 = 77 steps, 63 m, 4 kcal (15:30 = 77, from 59 03 and 33 01); " +
					"2026-11-01 = 21 steps, 17 m, 1 kcal (FEE1; CRP profile)",
			)

			// A releitura pedida por FEE1: um pedido só para a rajada de seis notificações e um onLiveSteps sem requestId
			// (#-1 no log); depois, o onLiveSteps do Refresh steps do app (#8), como no log.
			assertEquals(1, outcome.logger.withTag("CrpDriver/liveSteps").count { it.endsWith("asking for a refresh") })
			assertEquals(5, outcome.logger.withTag("CrpDriver/liveSteps").count { it.endsWith("refresh already requested") })
			assertEquals(
				listOf(SessionEvent.LiveSteps(WatchSession.NO_REQUEST_ID, todaySteps), SessionEvent.LiveSteps(ids[7], todaySteps)),
				outcome.listener.events.filterIsInstance<SessionEvent.LiveSteps>(),
			)

			// Sono: o 32, o 33 03 e o 33 04 responderam com o eco vazio (nenhum timeout), e o 33 01 manteve o legado ligado.
			assertEquals(emptyList(), outcome.session.getSleepSessions(3))
			assertEquals(
				listOf(
					"0 records for 2026-11-01 -> no session",
					"0 records for 2026-10-31 -> no session",
					"0 records for 2026-10-30 -> no session",
					"0 sleep sessions",
				),
				outcome.logger.withTag("CrpDriver/readSleep"),
			)
			val noAnswer = outcome.logger.messages.filter { it.third.contains("no answer") }
			assertTrue(noAnswer.isEmpty(), noAnswer.toString())
			println("SLEEP $CRP_ANDROID_RECORDING: 0 sessions (32, 33 03 and 33 04 answered with empty echoes, no timeout)")

			// F9 (estado do clássico) e 2A37 (frequência cardíaca) só vão para o log.
			assertEquals(
				listOf(
					"classic=00", "classic=01", "sub=02 value=01", "sub=02 value=01", "sub=02 value=01", "sub=02 value=01",
					"classic=00", "classic=00",
				),
				outcome.logger.withTag("CrpDriver/classicState").map { it.removePrefix("watch reports ") },
			)
			assertEquals(10, outcome.logger.withTag("CrpDriver/heartRate").size)
			assertTrue(outcome.logger.unexpected().isEmpty(), outcome.logger.unexpected().toString())
		} finally {
			outcome.session.close()
			runCurrent()
		}
	}

	@Test
	fun iosRecordingGivesWhatTheWatchAnswered() = runTest {
		val recording = RecordingLoader.loadFixture(CRP_IOS_RECORDING)
		assertEquals("P85", recording.header.device)
		assertEquals("MOY-VS02-2.0.0", recording.header.firmware)
		assertEquals("ios", recording.header.platform)
		assertEquals(515, recording.header.mtu)
		val zone = recording.header.timeZone
		// Não há log do iPhone. A primeira leitura de passos (atMs 17216) só pode ter sido o Refresh steps do app: pelo
		// CoreBridge, só o refreshSteps lê passos sem sono (o syncDataHistory pede passos e sono), e a releitura interna
		// precisaria de FEE1 notificado, que a gravação não tem. O deduce a vê como Sync([STEPS]) (mesmos bytes), e este
		// roteiro escrito à mão usa o RefreshSteps. A reconexão do fim (atMs 70767) não diz a causa (Bluetooth desligado,
		// fora de alcance ou Reconnect); o deduce a modela como o Bluetooth desligado e religado.
		val actions = listOf(
			ReplayAction.Pair,
			ReplayAction.ReadDeviceInfo,
			ReplayAction.ReadDeviceInfo,
			ReplayAction.ReadBattery,
			ReplayAction.SetTime,
			ReplayAction.RefreshSteps,
			ReplayAction.Sync(listOf(DataKind.STEPS, DataKind.SLEEP)),
			ReplayAction.BluetoothCycle,
		)
		assertEquals(
			actions.map { if (it == ReplayAction.RefreshSteps) ReplayAction.Sync(listOf(DataKind.STEPS)) else it },
			CrpReplayScenario.deduce(recording),
		)
		val outcome = runCrpReplay(recording, actions)
		try {
			val replay = outcome.replay
			assertEquals(emptyList(), replay.problems())
			assertEquals(27, replay.recordedTx)
			assertEquals(27, replay.matchedTx)
			assertEquals(5, replay.recordedReads)
			assertEquals(5, replay.matchedReads)
			assertTrue(replay.remaining.isEmpty(), "unconsumed: ${replay.remaining}")
			assertEquals(ConnectionState.READY, outcome.session.state)
			val handshake = listOf("CONNECTING", "CONFIGURING", "HANDSHAKING", "HANDSHAKING[VERSION]", "READY")
			// Só o Sync passa por SYNCING; o Refresh steps, não.
			assertEquals(
				handshake + listOf("SYNCING", "READY") +
					listOf("BLUETOOTH_OFF", "RECONNECTING", "CONFIGURING", "HANDSHAKING", "HANDSHAKING[VERSION]", "READY"),
				outcome.listener.stateTrail(),
			)
			val ids = outcome.requestIds.map { it.second }
			outcome.listener.assertOneTerminalEach(ids)
			val info = DeviceInfo("condorreebok", REPLAY_ADDRESS, "P85", "MOY-VS02-2.0.0", "MOYOUNG-V2")
			val todaySteps = StepsDay("2026-11-01", "2026-11-01T00:00:00-03:00", 21, 1, 17.0)
			assertEquals(
				listOf(
					SessionEvent.Completed(ids[0]),
					SessionEvent.Info(ids[1], info),
					SessionEvent.Info(ids[2], info),
					SessionEvent.BatteryLevel(ids[3], Battery(43, charging = false)),
					SessionEvent.Completed(ids[4]),
					SessionEvent.LiveSteps(ids[5], todaySteps),
					SessionEvent.SyncFinished(ids[6]),
					SessionEvent.Completed(ids[7]),
				),
				ids.map { outcome.listener.terminal(it) },
			)

			// MTU 515 (o que o iOS oferece) dá o mesmo bloco de 244 do 517 do Android, então todo TX sai com b2 = 0x20.
			assertEquals(
				2,
				outcome.logger.withTag("CrpDriver/handshake").count { it.contains("mtu 515, block 244") },
				outcome.logger.withTag("CrpDriver/handshake").toString(),
			)
			val writes = outcome.transport.writes.map { Hex.encode(it) }
			assertTrue(writes.all { it.startsWith("FEEA20") }, writes.toString())

			// Bateria: só a leitura pedida (2A19 = 0x2B); nenhuma notificação de 2A19 nos ~37 s da primeira conexão.
			assertEquals(
				listOf(SessionEvent.BatteryLevel(ids[3], Battery(43, charging = false))),
				outcome.listener.events.filterIsInstance<SessionEvent.BatteryLevel>(),
			)

			// Os três 31 (handshake, Set time do app e reconexão) trazem o relógio do telefone no envio; o fuso é comparado
			// inteiro.
			val recordedTimes = recording.packets.filter { it.direction == FrameDirection.TX && Hex.encode(it.value).startsWith("FEEA200A31") }
			assertEquals(listOf(889L, 13259L, 71649L), recordedTimes.map { it.atMs })
			for (packet in recordedTimes) {
				val sent = CrpTimeCodec.decodeWallClock(packet.value.copyOfRange(5, 10)).toInstant(TimeZone.UTC).epochSeconds
				val phone = TimeFormat.toLocalWallClock(recording.header.recordedAtEpochMillis + packet.atMs, zone)
					.toInstant(TimeZone.UTC).epochSeconds
				assertTrue(abs(sent - phone) <= 1, "31 at atMs ${packet.atMs}: watch time ${sent - phone} s away from the phone clock")
			}
			assertEquals(3, writes.count { it == "FEEA200BBB0700D0D5FFFF" })

			// O 59 01 (a página da tarde de hoje) traz a meia hora 9 (16:30) com 0x15 (21) nas duas leituras: a meia hora das
			// 16:30, ainda aberta na gravação do Android, aparece no histórico depois de fechar, com o valor de FEE1.
			val reassembler = CrpReassembler { message -> fail(message) }
			val afternoon = recording.packets.filter { it.direction == FrameDirection.RX }
				.flatMap { reassembler.accept(it.value) }
				.filter { it.cmd == CrpCommand.STEPS_HALF_HOURS && it.payload.firstOrNull()?.toInt() == 1 }
				.map { CrpCategoryPage.decodeSteps(it.payload)!!.values.toList() }
			assertEquals(2, afternoon.size)
			for (page in afternoon) assertEquals(List(24) { if (it == 9) 21L else 0L }, page)

			// Passos de ontem: o 59 03 trouxe 77 na meia hora 15:30 e o 33 01 respondeu 77 passos, 63 m e 4 kcal, de novo.
			val samples = outcome.store.loadSteps()
			val yesterday = samples.filter { TimeFormat.dayKey(it.atEpochMillis, zone) == "2026-10-31" }
			assertEquals(
				listOf(StepsSample(at("2026-10-31T15:30:00-03:00"), 77, CrpStepsMapper.MODE_BUCKET, 0, 0.0)),
				yesterday.filter { it.mode == CrpStepsMapper.MODE_BUCKET && it.steps != 0 },
			)
			assertEquals(
				listOf(StepsSample(at("2026-10-31T00:00:01-03:00"), 0, CrpStepsMapper.MODE_DAY_TOTALS, 4, 63.0)),
				yesterday.filter { it.mode == CrpStepsMapper.MODE_DAY_TOTALS },
			)
			// Hoje (relógio do replay às 17:02:59, meia hora 34): os 21 passos estão na meia hora fechada das 16:30 (mode 0),
			// e o resto de FEE1 na meia hora atual é 0. FEE1 deu 21 passos, 17 m e 1 kcal.
			val today = samples.filter { TimeFormat.dayKey(it.atEpochMillis, zone) == "2026-11-01" }
			assertEquals(
				listOf(StepsSample(at("2026-11-01T16:30:00-03:00"), 21, CrpStepsMapper.MODE_BUCKET, 0, 0.0)),
				today.filter { it.mode == CrpStepsMapper.MODE_BUCKET && it.steps != 0 },
			)
			assertEquals(
				listOf(StepsSample(at("2026-11-01T17:00:00-03:00"), 0, CrpStepsMapper.MODE_LIVE_REMAINDER, 0, 0.0)),
				today.filter { it.mode == CrpStepsMapper.MODE_LIVE_REMAINDER },
			)
			assertEquals(
				listOf(StepsSample(at("2026-11-01T00:00:01-03:00"), 0, CrpStepsMapper.MODE_DAY_TOTALS, 1, 17.0)),
				today.filter { it.mode == CrpStepsMapper.MODE_DAY_TOTALS },
			)
			val days = outcome.session.getStepsDays(3).associateBy { it.dayKey }
			assertEquals(setOf("2026-10-31", "2026-11-01"), days.keys)
			assertEquals(Triple(77, 4, 63.0), days.getValue("2026-10-31").let { Triple(it.steps, it.calories, it.distanceMeters) })
			assertEquals(Triple(21, 1, 17.0), days.getValue("2026-11-01").let { Triple(it.steps, it.calories, it.distanceMeters) })
			println(
				"STEPS-DAY $CRP_IOS_RECORDING: 2026-10-31 = 77 steps, 63 m, 4 kcal (15:30 = 77, from 59 03 and 33 01); " +
					"2026-11-01 = 21 steps, 17 m, 1 kcal (16:30 = 21, from 59 01; FEE1; CRP profile)",
			)

			// Nenhuma notificação de FEE1 na gravação: nenhuma releitura interna; o único onLiveSteps é o do Refresh steps.
			assertTrue(outcome.logger.withTag("CrpDriver/liveSteps").isEmpty(), outcome.logger.withTag("CrpDriver/liveSteps").toString())
			assertEquals(
				listOf(SessionEvent.LiveSteps(ids[5], todaySteps)),
				outcome.listener.events.filterIsInstance<SessionEvent.LiveSteps>(),
			)
			assertEquals(listOf("queued #${ids[5]}"), outcome.logger.withTag("Session/refreshSteps").filter { it.startsWith("queued") })

			// Sono: só a segunda leitura de passos veio com o 32; ele, o 33 03 e o 33 04 responderam com o eco vazio.
			assertEquals(emptyList(), outcome.session.getSleepSessions(3))
			assertEquals(
				listOf(
					"0 records for 2026-11-01 -> no session",
					"0 records for 2026-10-31 -> no session",
					"0 records for 2026-10-30 -> no session",
					"0 sleep sessions",
				),
				outcome.logger.withTag("CrpDriver/readSleep"),
			)
			val noAnswer = outcome.logger.messages.filter { it.third.contains("no answer") }
			assertTrue(noAnswer.isEmpty(), noAnswer.toString())
			println("SLEEP $CRP_IOS_RECORDING: 0 sessions (32, 33 03 and 33 04 answered with empty echoes, no timeout)")

			// F9 só vai para o log: o estado do clássico (00, depois 01) e o F9 02 com 00 duas vezes antes do 01. Sem 2A37.
			assertEquals(
				listOf(
					"classic=00", "classic=01", "sub=02 value=00", "sub=02 value=00",
					"sub=02 value=01", "sub=02 value=01", "sub=02 value=01", "sub=02 value=01",
				),
				outcome.logger.withTag("CrpDriver/classicState").map { it.removePrefix("watch reports ") },
			)
			assertTrue(outcome.logger.withTag("CrpDriver/heartRate").isEmpty())
			assertTrue(outcome.logger.unexpected().isEmpty(), outcome.logger.unexpected().toString())
		} finally {
			outcome.session.close()
			runCurrent()
		}
	}

	@Test
	fun withoutTheFee1BurstTheSameReadIsAHostAction() = runTest {
		val recording = RecordingLoader.loadFixture(CRP_ANDROID_RECORDING)
		val packets = recording.packets.filterNot { it.direction == FrameDirection.RX && it.characteristic == STEPS_TODAY }
		assertEquals(recording.packets.size - 6, packets.size)
		val withoutBurst = Recording(recording.header, packets, "without the FEE1 burst")
		val actions = CrpReplayScenario.deduce(withoutBurst)
		// Sem FEE1, nada pede a releitura: a primeira leitura de passos passa a ser um Sync([STEPS]) do host.
		assertEquals(
			listOf(
				ReplayAction.Pair, ReplayAction.Forget, ReplayAction.Pair, ReplayAction.ReadDeviceInfo, ReplayAction.ReadDeviceInfo,
				ReplayAction.ReadBattery, ReplayAction.SetTime, ReplayAction.Sync(listOf(DataKind.STEPS)),
				ReplayAction.Sync(listOf(DataKind.STEPS)), ReplayAction.Sync(listOf(DataKind.STEPS, DataKind.SLEEP)),
				ReplayAction.BluetoothCycle,
			),
			actions,
		)
		val outcome = runCrpReplay(withoutBurst, actions)
		try {
			assertEquals(emptyList(), outcome.replay.problems())
			assertEquals(38, outcome.replay.matchedTx)
			assertEquals(7, outcome.replay.matchedReads)
			assertTrue(outcome.logger.withTag("CrpDriver/liveSteps").isEmpty())
		} finally {
			outcome.session.close()
			runCurrent()
		}
	}

	@Test
	fun fee1AfterTheHalfHoursWereReadIsLiveStepsAndNoRead() = runTest {
		val recording = RecordingLoader.loadFixture(CRP_ANDROID_RECORDING)
		// FEE1 com 22 passos depois do Sync completo (antes do aviso de bateria de atMs 250823), quando a conexão já leu as
		// meias horas três vezes. Gravada logo antes de um pedido de passos, sairia no replay durante a leitura anterior.
		val battery = recording.packets.indexOfFirst { it.direction == FrameDirection.RX && it.atMs == 250823L }
		val packets = recording.packets.toMutableList()
		packets.add(battery, RecordedPacket(FrameDirection.RX, 250000, STEPS_TODAY, Hex.decode("160000120000010000")))
		val live = Recording(recording.header, packets, "FEE1 after the half hours")
		val outcome = runCrpReplay(live, CrpReplayScenario.deduce(live))
		try {
			assertEquals(CrpReplayScenario.deduce(recording), outcome.requestIds.map { it.first })
			assertEquals(emptyList(), outcome.replay.problems())
			assertEquals(38, outcome.replay.matchedTx)
			assertTrue(outcome.logger.withTag("CrpDriver/liveSteps").any { it.endsWith("-> half hour 33 = 22") })
			assertEquals(1, outcome.logger.withTag("CrpDriver/liveSteps").count { it.endsWith("asking for a refresh") })
		} finally {
			outcome.session.close()
			runCurrent()
		}
	}

	@Test
	fun aFee1RefreshTheRecordingNeverShowsIsRejected() {
		val recording = RecordingLoader.loadFixture(CRP_ANDROID_RECORDING)
		// FEE1 com passos antes do F8, sem as meias horas lidas: a sessão faria uma releitura que a gravação não tem.
		val forget = recording.packets.indexOfFirst { it.direction == FrameDirection.TX && Hex.encode(it.value) == "FEEA2005F8" }
		val packets = recording.packets.toMutableList()
		packets.add(forget, RecordedPacket(FrameDirection.RX, 38000, STEPS_TODAY, Hex.decode("1000000D0000010000")))
		val error = assertFailsWith<IllegalArgumentException> {
			CrpReplayScenario.deduce(Recording(recording.header, packets, "FEE1 before F8"))
		}
		assertTrue(error.message.orEmpty().contains("cannot place the internal refresh"), error.message)
	}

	@Test
	fun fee1BeforeTheHandshakeEndsOrAfterTheForgetReadsNothing() = runTest {
		val recording = RecordingLoader.loadFixture(CRP_ANDROID_RECORDING)
		// Duas FEE1 com passos, sem as meias horas lidas, fora de uma conexão pronta: entre o `BB 16` e o `31` do segundo
		// pareamento (no replay ela sai no atraso do `BB 16`, e o driver, ainda no handshake, a ignora) e logo depois do F8
		// (o driver ainda pede, no atraso pós-escrita, mas a sessão desconecta antes dos 2 s e a releitura não sai). O
		// deduce não conta nenhuma das duas, e a única releitura interna continua a da rajada de atMs 220546.
		val packets = recording.packets.toMutableList()
		val forget = packets.indexOfFirst { it.direction == FrameDirection.TX && Hex.encode(it.value) == "FEEA2005F8" }
		packets.add(forget + 1, RecordedPacket(FrameDirection.RX, 38050, STEPS_TODAY, Hex.decode("1000000D0000010000")))
		val secondSetTime = packets.indexOfFirst { it.direction == FrameDirection.TX && it.atMs == 79250L }
		packets.add(secondSetTime, RecordedPacket(FrameDirection.RX, 79000, STEPS_TODAY, Hex.decode("1000000D0000010000")))
		val notReady = Recording(recording.header, packets, "FEE1 after F8 and before READY")
		assertEquals(CrpReplayScenario.deduce(recording), CrpReplayScenario.deduce(notReady))
		val outcome = runCrpReplay(notReady)
		try {
			assertEquals(emptyList(), outcome.replay.problems())
			assertEquals(38, outcome.replay.matchedTx)
			assertEquals(7, outcome.replay.matchedReads)
			assertTrue(outcome.replay.remaining.isEmpty(), "unconsumed: ${outcome.replay.remaining}")
			assertEquals(1, outcome.logger.withTag("CrpDriver/liveSteps").count { it.startsWith("FEE1 before the handshake ignored") })
			assertEquals(listOf("queued #-1"), outcome.logger.withTag("Session/refreshSteps").filter { it.startsWith("queued") })
		} finally {
			outcome.session.close()
			runCurrent()
		}
	}

	@Test
	fun fee1DuringASleepOnlySyncIsFollowedByTheInternalRefresh() = runTest {
		val recording = RecordingLoader.loadFixture(CRP_ANDROID_RECORDING)
		// Recorte da gravação: o segundo pareamento (handshake e hora), o sono do Sync (32, 33 03, 33 04 e os ecos) e uma
		// leitura de passos. Com uma FEE1 com passos entre o eco do 32 e o 33 03, ela chega no meio de um Sync([SLEEP]),
		// sem as meias horas lidas: o driver pede a releitura, e a sessão a faz depois do sono.
		fun between(from: Long, to: Long) = recording.packets.filter { it.atMs in from..to }
		val sleep = between(232662, 234750).toMutableList()
		val fee1 = RecordedPacket(FrameDirection.RX, 233295, STEPS_TODAY, Hex.decode("150000110000010000"))
		sleep.add(sleep.indexOfFirst { it.atMs == 233291L } + 1, fee1)
		val handshake = between(78358, 79457)
		val stepsRead = between(229950, 231677)
		val withFee1 = Recording(recording.header, handshake + sleep + stepsRead, "FEE1 during a sleep-only sync")
		val withoutFee1 = Recording(recording.header, handshake + sleep.filterNot { it === fee1 } + stepsRead, "sleep-only sync")
		assertEquals(
			listOf(ReplayAction.Pair, ReplayAction.Sync(listOf(DataKind.SLEEP)), ReplayAction.Sync(listOf(DataKind.STEPS))),
			CrpReplayScenario.deduce(withoutFee1),
		)
		val actions = CrpReplayScenario.deduce(withFee1)
		assertEquals(listOf(ReplayAction.Pair, ReplayAction.Sync(listOf(DataKind.SLEEP))), actions)
		val outcome = runCrpReplay(withFee1, actions)
		try {
			assertEquals(emptyList(), outcome.replay.problems())
			assertEquals(13, outcome.replay.recordedTx)
			assertEquals(13, outcome.replay.matchedTx)
			assertEquals(2, outcome.replay.matchedReads)
			assertTrue(outcome.replay.remaining.isEmpty(), "unconsumed: ${outcome.replay.remaining}")
			assertEquals(1, outcome.logger.withTag("CrpDriver/liveSteps").count { it.endsWith("asking for a refresh") })
			assertEquals(
				listOf(SessionEvent.LiveSteps(WatchSession.NO_REQUEST_ID, StepsDay("2026-11-01", "2026-11-01T00:00:00-03:00", 21, 1, 17.0))),
				outcome.listener.events.filterIsInstance<SessionEvent.LiveSteps>(),
			)
		} finally {
			outcome.session.close()
			runCurrent()
		}
	}

	private fun at(iso: String): Long = Instant.parse(iso).toEpochMilliseconds()

	private companion object {
		const val STEPS_TODAY = "0000fee1-0000-1000-8000-00805f9b34fb"
	}
}

/**
 * A primeira gravação real do Condor Reebok (P85, firmware `MOY-VS02-2.0.0`), feita com o app de exemplo num Android em
 * 2026-11-01, caminho relativo a `fixtures/`.
 */
internal const val CRP_ANDROID_RECORDING: String =
	"real/condorreebok/MOY-VS02-2.0.0/pair-forget-pair-commands-sync-bt-reconnect.jsonl"

/**
 * A gravação do Condor Reebok (P85, firmware `MOY-VS02-2.0.0`) feita com o app de exemplo num iPhone em 2026-11-01,
 * caminho relativo a `fixtures/`.
 */
internal const val CRP_IOS_RECORDING: String =
	"real/condorreebok/MOY-VS02-2.0.0/ios-pair-commands-sync-reconnect.jsonl"
