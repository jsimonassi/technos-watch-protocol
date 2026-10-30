package br.com.grupotechnos.watch.protocol.testing

import java.io.File

// Compilado por jvmTest e por androidUnitTest (os dois rodam na JVM); ver protocol/build.gradle.kts.

internal actual fun configuredFixturesDir(): String {
	val path = System.getProperty("fixtures.dir")
		?: error("system property fixtures.dir is not set (run the tests through Gradle; protocol/build.gradle.kts sets it)")
	return File(path).invariantSeparatorsPath
}

internal actual fun readFixtureText(path: String): String = File(path).readText()

internal actual fun listFixtureFiles(directory: String): List<String> =
	File(directory).walkTopDown().filter { it.isFile }.map { it.invariantSeparatorsPath }.toList()

internal actual fun isFixtureDirectory(path: String): Boolean = File(path).isDirectory
