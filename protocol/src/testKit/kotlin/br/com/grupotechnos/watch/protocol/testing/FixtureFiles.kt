package br.com.grupotechnos.watch.protocol.testing

/**
 * Caminho absoluto da pasta `fixtures/` da raiz do repositório, como o Gradle o entrega ao processo de teste.
 *
 * Cada target recebe de um jeito (`docs/fixtures.md`, seção "Replay nos quatro targets"):
 * - JVM e testes unitários Android: a propriedade de sistema `fixtures.dir`;
 * - simulador iOS: a variável de ambiente `FIXTURES_DIR`, que o Gradle passa como `SIMCTL_CHILD_FIXTURES_DIR` porque o
 *   `simctl spawn` só repassa ao processo as variáveis com esse prefixo.
 *
 * Falha (lança) quando o caminho não chegou: um teste de fixture nunca passa em silêncio sem ter lido o arquivo.
 */
internal expect fun configuredFixturesDir(): String

/** Conteúdo UTF-8 do arquivo em [path] (absoluto). Lança se não conseguir ler. */
internal expect fun readFixtureText(path: String): String

/** Caminhos absolutos de todos os arquivos comuns abaixo de [directory], em qualquer profundidade e ordem. */
internal expect fun listFixtureFiles(directory: String): List<String>

/** `true` se [path] existe e é uma pasta. */
internal expect fun isFixtureDirectory(path: String): Boolean

/**
 * Acesso às fixtures a partir do `commonTest`: o mesmo código lê as gravações na JVM, nos testes unitários Android e no
 * simulador iOS. Caminhos sempre com `/`.
 */
internal object Fixtures {
	/** A pasta `fixtures/`, já conferida: lança se ela não existe. */
	fun root(): String {
		val root = configuredFixturesDir().trimEnd('/')
		check(isFixtureDirectory(root)) { "fixtures directory $root does not exist" }
		return root
	}

	/** Caminho absoluto de [relative] (ex.: `"synthetic/fitgps-pair-sync.jsonl"`). */
	fun path(relative: String): String = root() + "/" + relative.trimStart('/')

	/** Todo `.jsonl` abaixo de [relativeDir], em ordem de caminho. Lança se a pasta não existe. */
	fun jsonlUnder(relativeDir: String): List<String> {
		val dir = path(relativeDir)
		check(isFixtureDirectory(dir)) { "fixtures directory $dir does not exist" }
		return listFixtureFiles(dir).filter { it.endsWith(".jsonl") }.sorted()
	}

	/** [path] relativo a `fixtures/`, como aparece na doc e na linha `REPLAY`; o próprio [path] se estiver fora. */
	fun relative(path: String): String = path.removePrefix(root() + "/")
}
