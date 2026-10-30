package br.com.grupotechnos.watch.protocol.testing

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.posix.getenv

// O processo de teste roda no simulador (`xcrun simctl spawn`), mas enxerga o disco do Mac: basta o caminho absoluto.
// O Gradle o passa como SIMCTL_CHILD_FIXTURES_DIR, e o simctl entrega ao processo como FIXTURES_DIR (protocol/build.gradle.kts).

@OptIn(ExperimentalForeignApi::class)
internal actual fun configuredFixturesDir(): String =
	getenv("FIXTURES_DIR")?.toKString()?.takeIf { it.isNotEmpty() }
		?: error(
			"environment variable FIXTURES_DIR is not set (run the tests through Gradle; protocol/build.gradle.kts passes " +
				"SIMCTL_CHILD_FIXTURES_DIR to the simulator)",
		)

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun readFixtureText(path: String): String =
	NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: error("cannot read $path as UTF-8")

@OptIn(ExperimentalForeignApi::class)
internal actual fun listFixtureFiles(directory: String): List<String> {
	val manager = NSFileManager.defaultManager
	val relative = manager.subpathsOfDirectoryAtPath(directory, null) ?: error("cannot list $directory")
	return relative.map { "$directory/$it" }.filter { isRegularFile(it) }
}

internal actual fun isFixtureDirectory(path: String): Boolean = exists(path, directory = true)

private fun isRegularFile(path: String): Boolean = exists(path, directory = false)

@OptIn(ExperimentalForeignApi::class)
private fun exists(path: String, directory: Boolean): Boolean = memScoped {
	val isDirectory = alloc<BooleanVar>()
	NSFileManager.defaultManager.fileExistsAtPath(path, isDirectory.ptr) && isDirectory.value == directory
}
