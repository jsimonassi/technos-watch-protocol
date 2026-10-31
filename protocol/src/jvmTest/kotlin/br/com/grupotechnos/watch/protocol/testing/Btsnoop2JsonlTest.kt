package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.util.Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `scripts/btsnoop2jsonl.py` sobre um btsnoop H4 sintético montado aqui, num diretório temporário (nada binário é
 * versionado). Os endereços (relógio, telefone e um par clássico) são inventados e só existem neste teste. Confere:
 * descoberta de handles, READ de 2A29 e 2A28, TX e RX do canal FE EA e de FEE1, frames 0x41, 0x42 e 0x43 (este em dois
 * pacotes) descartados, 0x12 trocado pelo valor fictício, cabeçalho com as chaves de referência, nenhum byte de endereço
 * na saída, a trava que aborta quando o endereço do relógio, o do telefone (Read_BD_ADDR) ou o de um par clássico
 * aparece num pacote, o rótulo do `--list` (HMAC com chave tirada da captura, não o SHA-256 do endereço) e que nenhum
 * rótulo real está versionado.
 */
class Btsnoop2JsonlTest {
	private val script = File(File(RecordingLoader.fixturesDir()).parentFile, "scripts/btsnoop2jsonl.py")

	/** Endereço sintético do relógio na ordem do HCI (little-endian). */
	private val address = byteArrayOf(0x5C, 0x4B, 0x3A, 0x29, 0x18, 0x07)

	/** Endereço sintético do telefone (Command Complete do Read_BD_ADDR). */
	private val phone = byteArrayOf(0x66, 0x55, 0x44, 0x33, 0x22, 0x11)

	/** Endereço sintético de um par clássico (Connection Complete e User Confirmation Request). */
	private val classicPeer = byteArrayOf(0x9A.toByte(), 0x89.toByte(), 0x78, 0x67, 0x56, 0x45)

	/** Qual endereço o pacote extra deixa vazar num TX (nenhum por padrão). */
	private enum class Leak { NONE, WATCH, PHONE, CLASSIC_PEER }

	private val expectedPackets = listOf(
		"""{"type":"packet","dir":"READ","atMs":140,"characteristic":"00002a29-0000-1000-8000-00805f9b34fb","hex":"4D4F594F554E472D5632"}""",
		"""{"type":"packet","dir":"TX","atMs":150,"characteristic":"0000fee2-0000-1000-8000-00805f9b34fb","hex":"FEEA20065A00"}""",
		"""{"type":"packet","dir":"RX","atMs":160,"characteristic":"0000fee3-0000-1000-8000-00805f9b34fb","hex":"FEEA20105A004D4F594F554E472D5632"}""",
		"""{"type":"packet","dir":"TX","atMs":180,"characteristic":"0000fee2-0000-1000-8000-00805f9b34fb","hex":"FEEA200912B24B1800"}""",
		"""{"type":"packet","dir":"RX","atMs":190,"characteristic":"0000fee1-0000-1000-8000-00805f9b34fb","hex":"1000000D0000010000"}""",
		"""{"type":"packet","dir":"READ","atMs":210,"characteristic":"00002a28-0000-1000-8000-00805f9b34fb","hex":"4D4F592D565330322D322E302E30"}""",
	)

	@Test
	fun syntheticCaptureBecomesARedactedReferenceRecording() {
		val dir = Files.createTempDirectory("btsnoop2jsonl").toFile()
		try {
			val capture = File(dir, "btsnoop_hci.log").apply { writeBytes(capture(Leak.NONE)) }
			val output = File(dir, "out.jsonl")
			val label = label(capture) ?: return
			val result = run("--family", "crp", "--peer", label, "--conn", "64", capture.path, "-o", output.path) ?: return
			assertEquals(0, result, "script failed")
			val text = output.readText()
			val lines = text.trimEnd('\n').lines()
			assertEquals(expectedPackets, lines.drop(1))
			val header = Json.parseToJsonElement(lines.first()).jsonObject
			assertEquals("P85", header.getValue("device").jsonPrimitive.content)
			assertEquals("MOY-VS02-2.0.0", header.getValue("firmware").jsonPrimitive.content)
			assertEquals("android-hci", header.getValue("platform").jsonPrimitive.content)
			assertEquals("517", header.getValue("mtu").jsonPrimitive.content)
			assertEquals("2026-10-31T15:00:00.000-03:00", header.getValue("recordedAt").jsonPrimitive.content)
			assertEquals("hci", header.getValue("source").jsonPrimitive.content)
			assertEquals("peripheral", header.getValue("mtuInitiator").jsonPrimitive.content)
			assertEquals(
				listOf("fee3"),
				header.getValue("cccdWrites").jsonArray.map { it.jsonObject.getValue("characteristic").jsonPrimitive.content },
			)
			val feea = header.getValue("gatt").jsonArray.map { it.jsonObject }.single { it.getValue("service").jsonPrimitive.content == "feea" }
			val fee2 = feea.getValue("characteristics").jsonArray.map { it.jsonObject }.single { it.getValue("uuid").jsonPrimitive.content == "fee2" }
			assertEquals("[\"writeWithoutResponse\"]", fee2.getValue("properties").toString())
			assertTrue(header.getValue("redactions").jsonPrimitive.content.contains("41, 42, 43, B5"))
			assertTrue(header.getValue("redactions").jsonPrimitive.content.contains("B24B1800"))
			// Nenhum byte de endereço, em nenhuma ordem nem como texto, e nem o rótulo.
			val upper = text.uppercase()
			for (raw in listOf(address, phone, classicPeer)) {
				assertFalse(Hex.encode(raw) in upper)
				assertFalse(Hex.encode(raw.reversedArray()) in upper)
				assertFalse(raw.reversedArray().joinToString(":") { Hex.encode(byteArrayOf(it)) } in upper)
			}
			assertFalse(label in text)
			// O RecordingLoader lê a saída (chaves extras ignoradas) e a dedução CRP entende os pedidos.
			val recording = RecordingLoader.parse(text, "btsnoop2jsonl")
			assertEquals(listOf(FrameDirection.READ, FrameDirection.TX, FrameDirection.RX, FrameDirection.TX, FrameDirection.RX, FrameDirection.READ), recording.packets.map { it.direction })
			assertEquals(listOf("R:2a29", "5A/00", "12", "R:2a28"), CrpReplayScenario.requests(recording))
		} finally {
			dir.deleteRecursively()
		}
	}

	@Test
	fun addressInAPacketAbortsWithoutWriting() {
		assertLeakAborts(Leak.WATCH)
	}

	@Test
	fun phoneAddressInAPacketAbortsWithoutWriting() {
		// O endereço do próprio telefone só aparece no Command Complete do Read_BD_ADDR, nunca numa conexão.
		assertLeakAborts(Leak.PHONE)
	}

	@Test
	fun classicPeerAddressInAPacketAbortsWithoutWriting() {
		assertLeakAborts(Leak.CLASSIC_PEER)
	}

	@Test
	fun listNeverPrintsTheAddressAndTheLabelIsKeyedByTheCapture() {
		val dir = Files.createTempDirectory("btsnoop2jsonl").toFile()
		try {
			val capture = File(dir, "btsnoop_hci.log").apply { writeBytes(capture(Leak.NONE)) }
			val out = File(dir, "list.txt")
			val result = run("--list", capture.path, stdout = out) ?: return
			assertEquals(0, result)
			val text = out.readText()
			val label = LABEL.find(text)?.value
			assertTrue(label != null && text.startsWith("$label conn=64 ") && " role=central " in text, text)
			val digits = text.uppercase().replace(":", "")
			for (raw in listOf(address, phone, classicPeer)) assertFalse(Hex.encode(raw.reversedArray()) in digits)
			// O rótulo não é o SHA-256 do endereço (que se reverte por força bruta): a chave vem da captura.
			assertNotEquals("dev-" + Hex.encode(MessageDigest.getInstance("SHA-256").digest(address)).lowercase().take(6), label)
			// Mesma captura, mesmo rótulo; outra captura com o mesmo relógio, outro rótulo.
			assertEquals(label, label(capture))
			val other = File(dir, "other.log").apply { writeBytes(capture(Leak.NONE, startMinute = 1)) }
			assertNotEquals(label, label(other))
		} finally {
			dir.deleteRecursively()
		}
	}

	@Test
	fun noRealDeviceLabelIsVersioned() {
		// Um rótulo dev-<6 hex> da captura real identifica o relógio a quem tem a captura: nos docs e scripts, dev-xxxxxx.
		val root = File(RecordingLoader.fixturesDir()).parentFile
		val roots = listOf("scripts", "docs", "fixtures", "protocol/src", "bridge/src", "bridge/swift-interop", "bridge/objc-interop")
			.map { File(root, it) } + root.listFiles { file -> file.isFile && file.name.endsWith(".md") }.orEmpty()
		val hits = roots.flatMap { base -> base.walkTopDown().filter { it.isFile }.toList() }
			.filter { file -> file.extension in setOf("md", "kt", "kts", "py", "jsonl", "json", "swift", "mm", "txt") }
			.flatMap { file -> file.readLines().mapIndexedNotNull { index, line -> if (LABEL.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}" else null } }
		assertEquals(emptyList(), hits)
	}

	private fun assertLeakAborts(leak: Leak) {
		val dir = Files.createTempDirectory("btsnoop2jsonl").toFile()
		try {
			val capture = File(dir, "btsnoop_hci.log").apply { writeBytes(capture(leak)) }
			val output = File(dir, "out.jsonl")
			val label = label(capture) ?: return
			val result = run("--family", "crp", "--peer", label, "--conn", "64", capture.path, "-o", output.path) ?: return
			assertNotEquals(0, result, "the $leak address leaked and the script did not abort")
			assertFalse(output.exists(), "nothing may be written when the $leak address leaks")
		} finally {
			dir.deleteRecursively()
		}
	}

	/** Roda o script com `python3 -I`; `null` (e o teste não confere) quando não há `python3`. */
	private fun run(vararg args: String, stdout: File? = null): Int? {
		val process = try {
			ProcessBuilder(listOf("python3", "-I", script.path) + args)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.redirectOutput(stdout?.let { ProcessBuilder.Redirect.to(it) } ?: ProcessBuilder.Redirect.DISCARD)
				.start()
		} catch (error: java.io.IOException) {
			println("Btsnoop2JsonlTest: python3 not available (${error.message}); script not exercised")
			return null
		}
		check(process.waitFor(60, TimeUnit.SECONDS)) { "btsnoop2jsonl.py did not finish" }
		return process.exitValue()
	}

	/** Rótulo do relógio que o `--list` imprime para [capture]; `null` sem `python3`. */
	private fun label(capture: File): String? {
		val out = File.createTempFile("btsnoop2jsonl-list", ".txt")
		try {
			if (run("--list", capture.path, stdout = out) == null) return null
			return LABEL.find(out.readText())?.value ?: error("no label in --list: ${out.readText()}")
		} finally {
			out.delete()
		}
	}

	/** Btsnoop H4 com uma conexão LE (handle 0x40) e a conversa descrita no KDoc da classe; um pacote a cada 10 ms. */
	private fun capture(leak: Leak, startMinute: Int = 0): ByteArray {
		val out = ByteArrayOutputStream()
		val data = DataOutputStream(out)
		data.write("btsnoop".toByteArray() + byteArrayOf(0))
		data.writeInt(1)
		data.writeInt(1002)
		val start = LocalDateTime.of(2026, 10, 31, 15, startMinute).toInstant(ZoneOffset.UTC).toEpochMilli() * 1000 + EPOCH_DELTA
		var step = 0
		fun record(received: Boolean, h4: ByteArray) {
			data.writeInt(h4.size)
			data.writeInt(h4.size)
			data.writeInt(if (received) 1 else 0)
			data.writeInt(0)
			data.writeLong(start + step * 10_000L)
			data.write(h4)
			step++
		}
		fun event(code: Int, params: ByteArray) = record(true, byteArrayOf(0x04, code.toByte(), params.size.toByte()) + params)
		fun command(opcode: Int, params: ByteArray) = record(false, byteArrayOf(0x01) + le16(opcode) + byteArrayOf(params.size.toByte()) + params)
		fun att(received: Boolean, payload: ByteArray) {
			val l2 = le16(payload.size) + le16(0x0004) + payload
			record(received, byteArrayOf(0x02) + le16(HANDLE or 0x2000) + le16(l2.size) + l2)
		}
		event(0x3E, byteArrayOf(0x01, 0x00) + le16(HANDLE) + byteArrayOf(0x00, 0x00) + address + le16(24) + le16(0) + le16(500) + byteArrayOf(0))
		att(true, byteArrayOf(0x02) + le16(517))
		att(false, byteArrayOf(0x03) + le16(517))
		att(false, byteArrayOf(0x08) + le16(1) + le16(0xFFFF) + le16(0x2A00))
		att(true, byteArrayOf(0x09, 0x05) + le16(0x03) + "P85".toByteArray())
		att(true, byteArrayOf(0x11, 0x06) + le16(0x10) + le16(0x14) + le16(0x180A) + le16(0x20) + le16(0x2A) + le16(0xFEEA))
		att(false, byteArrayOf(0x08) + le16(0x10) + le16(0x14) + le16(0x2803))
		att(true, byteArrayOf(0x09, 0x07) + decl(0x11, 0x02, 0x12, 0x2A29) + decl(0x13, 0x02, 0x14, 0x2A28))
		att(false, byteArrayOf(0x08) + le16(0x20) + le16(0x2A) + le16(0x2803))
		att(true, byteArrayOf(0x09, 0x07) + decl(0x21, 0x12, 0x22, 0xFEE1) + decl(0x24, 0x04, 0x25, 0xFEE2) + decl(0x26, 0x10, 0x27, 0xFEE3))
		att(true, byteArrayOf(0x05, 0x01) + le16(0x23) + le16(0x2902) + le16(0x28) + le16(0x2902))
		att(false, byteArrayOf(0x12) + le16(0x28) + Hex.decode("0100"))
		att(true, byteArrayOf(0x13))
		att(false, byteArrayOf(0x0A) + le16(0x12))
		att(true, byteArrayOf(0x0B) + "MOYOUNG-V2".toByteArray())
		att(false, byteArrayOf(0x52) + le16(0x25) + Hex.decode("FEEA20065A00"))
		att(true, byteArrayOf(0x1B) + le16(0x27) + Hex.decode("FEEA20105A00") + "MOYOUNG-V2".toByteArray())
		att(false, byteArrayOf(0x52) + le16(0x25) + Hex.decode("FEEA20064101"))
		att(false, byteArrayOf(0x52) + le16(0x25) + Hex.decode("FEEA200912A0461E00"))
		att(true, byteArrayOf(0x1B) + le16(0x22) + Hex.decode("1000000D0000010000"))
		att(false, byteArrayOf(0x0A) + le16(0x14))
		att(true, byteArrayOf(0x0B) + "MOY-VS02-2.0.0".toByteArray())
		// Previsão do tempo (0x42, só temperaturas): sai pelo padrão do --redact-cmds.
		att(false, byteArrayOf(0x52) + le16(0x25) + Hex.decode("FEEA200A4203131B03"))
		// Um 0x43 de 30 bytes em dois pacotes: os dois saem.
		val weather = Hex.decode("FEEA101E43") + ByteArray(25) { 0x41 }
		att(false, byteArrayOf(0x52) + le16(0x25) + weather.copyOfRange(0, 20))
		att(false, byteArrayOf(0x52) + le16(0x25) + weather.copyOfRange(20, 30))
		// Outros endereços da captura, fora da conexão LE: o do telefone, um par clássico com SSP e o alvo de um LE Create
		// Connection. A trava confere todos.
		event(0x0E, byteArrayOf(0x01) + le16(0x1009) + byteArrayOf(0x00) + phone)
		command(0x200D, le16(0x60) + le16(0x30) + byteArrayOf(0x00, 0x00) + address + byteArrayOf(0x00) + le16(24) + le16(40) + le16(0) + le16(500) + le16(0) + le16(0))
		event(0x03, byteArrayOf(0x00) + le16(0x80) + classicPeer + byteArrayOf(0x01, 0x00))
		event(0x33, classicPeer + byteArrayOf(0, 0, 0, 0))
		when (leak) {
			Leak.NONE -> Unit
			Leak.WATCH -> att(false, byteArrayOf(0x52) + le16(0x25) + Hex.decode("FEEA200B71") + address)
			Leak.PHONE -> att(false, byteArrayOf(0x52) + le16(0x25) + Hex.decode("FEEA200B71") + phone)
			Leak.CLASSIC_PEER -> att(false, byteArrayOf(0x52) + le16(0x25) + Hex.decode("FEEA200B71") + classicPeer.reversedArray())
		}
		event(0x05, byteArrayOf(0x00) + le16(HANDLE) + byteArrayOf(0x13))
		return out.toByteArray()
	}

	private fun decl(handle: Int, properties: Int, valueHandle: Int, uuid: Int): ByteArray =
		le16(handle) + byteArrayOf(properties.toByte()) + le16(valueHandle) + le16(uuid)

	private fun le16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr 8).toByte())

	private companion object {
		/** Microssegundos entre 0000-01-01 e 1970-01-01 (época do btsnoop). */
		const val EPOCH_DELTA = 0x00DCDDB30F2F8000L

		const val HANDLE = 0x40

		/** Um rótulo de aparelho do `--list`. */
		val LABEL = Regex("""\bdev-[0-9a-f]{6}\b""")
	}
}
