package br.com.grupotechnos.watch.protocol.session

import br.com.grupotechnos.watch.protocol.testing.RecordingLoader
import br.com.grupotechnos.watch.protocol.testing.SyntheticCondorReebokFixture
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A gravação sintética do Condor Reebok versionada é exatamente a saída do gerador
 * ([SyntheticCondorReebokFixture]); o replay dela roda em todos os targets no `CrpSyntheticReplayTest`.
 */
class CrpSessionReplayTest {
	private val fixture: File = File(RecordingLoader.fixturesDir(), SyntheticCondorReebokFixture.RELATIVE_PATH)

	@Test
	fun committedFixtureMatchesTheGenerator() {
		val generated = SyntheticCondorReebokFixture.build().toJsonl()
		if (System.getenv("FIXTURES_REGENERATE") == "1") {
			fixture.parentFile.mkdirs()
			fixture.writeText(generated)
		}
		assertEquals(
			generated,
			fixture.readText(),
			"regenerate with FIXTURES_REGENERATE=1 ./gradlew :protocol:jvmTest --tests '*CrpSessionReplayTest*'",
		)
	}
}
