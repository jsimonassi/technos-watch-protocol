package br.com.grupotechnos.watch.bridge

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

// O processo de teste roda no simulador e enxerga o disco do Mac; o Gradle passa o caminho como SIMCTL_CHILD_GOLDEN_DIR,
// que o simctl entrega ao processo como GOLDEN_DIR (bridge/build.gradle.kts).

@OptIn(ExperimentalForeignApi::class)
internal actual fun configuredGoldenDir(): String =
	getenv("GOLDEN_DIR")?.toKString()?.takeIf { it.isNotEmpty() }
		?: error(
			"environment variable GOLDEN_DIR is not set (run the tests through Gradle; bridge/build.gradle.kts passes " +
				"SIMCTL_CHILD_GOLDEN_DIR to the simulator)",
		)

// O golden só é regravado pela JVM: o simulador só confere.
internal actual fun regenerateGoldenIfRequested(path: String, text: String): Boolean = false
