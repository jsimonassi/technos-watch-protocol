package br.com.grupotechnos.watch.protocol.testing

import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.util.Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Lê gravações `.jsonl` no formato de `log/FrameJsonl` (`docs/fixtures.md`): a primeira linha não vazia é o
 * cabeçalho (`"type":"header"`), as demais são pacotes (`"type":"packet"`). Linhas com outro `type` são ignoradas,
 * para o formato poder ganhar linhas novas sem quebrar gravações antigas.
 *
 * Só existe na JVM porque o `kotlinx-serialization-json` está apenas nas dependências do `jvmTest`.
 */
internal object RecordingLoader {
	/** Pasta `fixtures/` da raiz do repositório; o Gradle passa em `-Dfixtures.dir`. */
	fun fixturesDir(): File {
		val path = System.getProperty("fixtures.dir") ?: error("system property fixtures.dir is not set (run through Gradle)")
		return File(path)
	}

	fun load(file: File): Recording = parse(file.readText(), file.path)

	fun parse(text: String, source: String): Recording {
		val lines = text.lineSequence().withIndex().filter { it.value.isNotBlank() }.toList()
		require(lines.isNotEmpty()) { "$source is empty" }
		val header = objectAt(lines.first().value, source, lines.first().index)
		require(header.string("type") == "header") { "$source:${lines.first().index + 1} first line must be the header" }
		val packets = lines.drop(1).mapNotNull { (index, line) ->
			val json = objectAt(line, source, index)
			if (json.string("type") != "packet") return@mapNotNull null
			val where = "$source:${index + 1}"
			RecordedPacket(
				direction = when (val dir = json.string("dir")) {
					"TX" -> FrameDirection.TX
					"RX" -> FrameDirection.RX
					else -> throw IllegalArgumentException("$where dir must be TX or RX, got $dir")
				},
				atMs = json["atMs"]?.jsonPrimitive?.longOrNull ?: throw IllegalArgumentException("$where atMs missing"),
				characteristic = json.string("characteristic") ?: throw IllegalArgumentException("$where characteristic missing"),
				value = Hex.decode(json.string("hex") ?: throw IllegalArgumentException("$where hex missing")),
			)
		}
		return Recording(
			header = RecordingHeader(
				device = header.string("device") ?: throw IllegalArgumentException("$source header.device missing"),
				firmware = header.string("firmware"),
				platform = header.string("platform") ?: "unknown",
				mtu = header["mtu"]?.jsonPrimitive?.intOrNull ?: 23,
				recordedAt = header.string("recordedAt") ?: throw IllegalArgumentException("$source header.recordedAt missing"),
				timeZone = header.string("timeZone") ?: throw IllegalArgumentException("$source header.timeZone missing"),
			),
			packets = packets,
			source = source,
		)
	}

	private fun objectAt(line: String, source: String, index: Int): JsonObject =
		try {
			Json.parseToJsonElement(line).jsonObject
		} catch (error: Exception) {
			throw IllegalArgumentException("$source:${index + 1} is not a JSON object: ${error.message}", error)
		}

	private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
