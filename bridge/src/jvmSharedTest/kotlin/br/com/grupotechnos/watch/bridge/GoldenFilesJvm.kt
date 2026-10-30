package br.com.grupotechnos.watch.bridge

import java.io.File

// Source set jvmSharedTest: o lado de teste do grupo jvmShared (jvmTest e unit Android); ver bridge/build.gradle.kts.

internal actual fun configuredGoldenDir(): String {
	val path = System.getProperty("golden.dir")
		?: error("system property golden.dir is not set (run the tests through Gradle; bridge/build.gradle.kts sets it)")
	return File(path).invariantSeparatorsPath
}

internal actual fun regenerateGoldenIfRequested(path: String, text: String): Boolean {
	if (System.getenv("BRIDGE_GOLDEN_REGENERATE") != "1") return false
	File(path).writeText(text)
	return true
}
