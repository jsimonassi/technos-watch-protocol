package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.log.FrameDirection
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `scripts/hci2jsonl.py` sobre a captura feita à mão em `fixtures/tools/sample-wireshark.json` (descrita em
 * `docs/fixtures.md`). A saída esperada está ao lado e também é lida pelo [RecordingLoader], o que garante que o
 * script produz o mesmo formato que a sessão grava.
 */
class Hci2JsonlTest {
	private val tools = File(RecordingLoader.fixturesDir(), "tools")
	private val expected = File(tools, "sample-wireshark.expected.jsonl")

	@Test
	fun expectedOutputIsAValidRecording() {
		val recording = RecordingLoader.load(expected.path)
		assertEquals(247, recording.header.mtu)
		assertEquals("2026-10-28T14:30:00-03:00", recording.header.recordedAt)
		assertEquals(
			listOf(FrameDirection.TX, FrameDirection.RX, FrameDirection.RX, FrameDirection.RX, FrameDirection.TX),
			recording.packets.map { it.direction },
		)
		assertEquals(listOf("02/08"), ReplayScenario.txFrames(recording))
	}

	@Test
	fun scriptReproducesTheExpectedOutput() {
		val script = File(File(RecordingLoader.fixturesDir()).parentFile, "scripts/hci2jsonl.py")
		val process = try {
			ProcessBuilder("python3", "-I", script.path, File(tools, "sample-wireshark.json").path)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start()
		} catch (error: java.io.IOException) {
			println("Hci2JsonlTest: python3 not available (${error.message}); script not exercised")
			return
		}
		val output = process.inputStream.bufferedReader().readText()
		check(process.waitFor(30, TimeUnit.SECONDS)) { "hci2jsonl.py did not finish" }
		assertEquals(0, process.exitValue())
		assertEquals(expected.readText(), output)
	}
}
