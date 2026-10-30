import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
	alias(libs.plugins.kotlin.multiplatform)
	alias(libs.plugins.android.library)
	alias(libs.plugins.dokka)
	`maven-publish`
}

group = "br.com.grupotechnos.watch"
version = "0.1.0"

kotlin {
	// Toda declaração pública precisa ser explícita: é o que separa a API do host do que é interno.
	explicitApi()
	jvmToolchain(17)

	androidTarget {
		publishLibraryVariants("release")
		compilerOptions {
			jvmTarget.set(JvmTarget.JVM_17)
		}
	}

	// Só para rodar testes e ferramentas na JVM sem emulador.
	jvm()

	// Só klibs e testes no simulador. O framework e o XCFramework que o host iOS consome saem do módulo :bridge, que
	// exporta este módulo inteiro no mesmo framework guarda-chuva (docs/decisoes.md, ADR 20).
	iosArm64()
	iosSimulatorArm64()

	sourceSets {
		commonMain.dependencies {
			implementation(libs.kotlinx.coroutines.core)
			implementation(libs.kotlinx.datetime)
		}
		commonTest.dependencies {
			implementation(kotlin("test"))
			implementation(libs.kotlinx.coroutines.test)
			// Leitor das gravações .jsonl (testing/RecordingLoader), que roda nos quatro targets de teste.
			implementation(libs.kotlinx.serialization.json)
		}
		// Kit de teste compartilhado com o :bridge (transporte falso, relógio SMA simulado, replay de gravações, a
		// SessionHarness): pastas que os dois módulos compilam nos próprios testes, sem publicar código de teste. O
		// :bridge enxerga o `internal` daqui só nos testes dele (bridge/build.gradle.kts). Ver src/testKit/README.md.
		commonTest {
			kotlin.srcDir("src/testKit/kotlin")
		}
		// Arquivos e fila do host dos testes na JVM. O teste unitário Android também roda na JVM, então os dois compilam
		// a mesma pasta. Um source set intermediário com dependsOn desligaria o template padrão de hierarquia (e o iosMain).
		jvmTest {
			kotlin.srcDir("src/jvmSharedTest/kotlin")
			kotlin.srcDir("src/testKitJvm/kotlin")
		}
		named("androidUnitTest") {
			kotlin.srcDir("src/jvmSharedTest/kotlin")
			kotlin.srcDir("src/testKitJvm/kotlin")
		}
		iosTest {
			kotlin.srcDir("src/testKitIos/kotlin")
		}
	}
}

android {
	namespace = "br.com.grupotechnos.watch.protocol"
	compileSdk = 35
	buildToolsVersion = "35.0.0"
	defaultConfig {
		minSdk = 26
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
}

// Os testes leem as fixtures da pasta fixtures/ na raiz do repositório, nos quatro targets. A pasta é input de toda
// tarefa de teste: sem isso, copiar ou trocar uma gravação em fixtures/real deixava o teste UP-TO-DATE ou FROM-CACHE
// (o cache está ligado em gradle.properties), e o replay da gravação nova nunca rodava. O caminho relativo entra na
// chave, então renomear uma fixture também roda os testes de novo.
val fixturesDir: Directory = rootProject.layout.projectDirectory.dir("fixtures")

// JVM e testes unitários Android (debug e release): tarefas `Test`, caminho em -Dfixtures.dir.
tasks.withType<Test>().configureEach {
	inputs.dir(fixturesDir).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
	systemProperty("fixtures.dir", fixturesDir.asFile.absolutePath)
}

// Kotlin/Native (iosSimulatorArm64Test): não é uma tarefa `Test`, então precisa da mesma ligação à parte. O processo de
// teste roda no simulador via `xcrun simctl spawn`, que só repassa as variáveis com o prefixo SIMCTL_CHILD_ (e tira o
// prefixo): o teste lê FIXTURES_DIR. A variável sem prefixo cobre um teste nativo que rode direto no macOS.
tasks.withType<KotlinNativeTest>().configureEach {
	inputs.dir(fixturesDir).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
	environment("SIMCTL_CHILD_FIXTURES_DIR", fixturesDir.asFile.absolutePath)
	environment("FIXTURES_DIR", fixturesDir.asFile.absolutePath)
}

// Documentação da API pública (critério D2 do plano). Só o que é `public` entra; `internal` fica de fora
// porque não é contrato com o host. `failOnWarning` transforma qualquer símbolo público sem KDoc em erro de build.
dokka {
	moduleName.set("technos-watch-protocol")
	dokkaPublications.html {
		failOnWarning.set(true)
		suppressInheritedMembers.set(true)
	}
	dokkaSourceSets.configureEach {
		reportUndocumented.set(true)
		documentedVisibilities.set(setOf(VisibilityModifier.Public))
		skipEmptyPackages.set(true)
		includes.from("Module.md")
		jdkVersion.set(17)
	}
}

// Nome do plano (`dokkaHtml`, do Dokka 1) mantido como atalho para a tarefa do Dokka 2.
tasks.register("dokkaHtml") {
	group = "documentation"
	description = "Gera a documentação HTML da API pública em build/dokka/html (atalho para dokkaGeneratePublicationHtml)."
	dependsOn("dokkaGeneratePublicationHtml")
}
