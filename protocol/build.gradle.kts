import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.konan.target.HostManager

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

	val xcFramework = XCFramework("TechnosWatchProtocol")
	listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
		target.binaries.framework {
			baseName = "TechnosWatchProtocol"
			isStatic = true
			xcFramework.add(this)
		}
	}

	sourceSets {
		commonMain.dependencies {
			implementation(libs.kotlinx.coroutines.core)
			implementation(libs.kotlinx.datetime)
		}
		commonTest.dependencies {
			implementation(kotlin("test"))
			implementation(libs.kotlinx.coroutines.test)
		}
		jvmTest.dependencies {
			implementation(libs.kotlinx.serialization.json)
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

// Os testes JVM leem as fixtures da pasta fixtures/ na raiz do repositório. A pasta é input de toda tarefa de teste:
// sem isso, copiar ou trocar uma gravação em fixtures/real deixava o teste UP-TO-DATE ou FROM-CACHE (o cache está
// ligado em gradle.properties), e o replay da gravação nova nunca rodava. O caminho relativo entra na chave, então
// renomear uma fixture também roda os testes de novo.
tasks.withType<Test>().configureEach {
	val fixtures = rootProject.layout.projectDirectory.dir("fixtures")
	inputs.dir(fixtures).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
	systemProperty("fixtures.dir", fixtures.asFile.absolutePath)
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

/**
 * Falha se o header Objective-C do framework expõe tipos que o Swift não consome bem.
 *
 * A API pública precisa ser usável pelo host iOS sem shim: `suspend` vira `completionHandler`,
 * `Flow` e tipos do kotlinx viram classes opacas com prefixo `Kotlinx_`. Ver `docs/arquitetura.md`.
 */
abstract class CheckObjcHeaderTask : DefaultTask() {
	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val header: RegularFileProperty

	@get:Input
	abstract val forbiddenPatterns: ListProperty<String>

	@get:OutputFile
	abstract val report: RegularFileProperty

	@TaskAction
	fun check() {
		val headerFile = header.get().asFile
		val lines = headerFile.readLines()
		val hits = mutableListOf<String>()
		for (pattern in forbiddenPatterns.get()) {
			lines.forEachIndexed { index, line ->
				if (line.contains(pattern)) hits += "${headerFile.name}:${index + 1}: [$pattern] ${line.trim()}"
			}
		}
		val reportFile = report.get().asFile
		reportFile.parentFile.mkdirs()
		if (hits.isNotEmpty()) {
			reportFile.writeText(hits.joinToString("\n", postfix = "\n"))
			throw GradleException(
				"The ObjC header exposes ${hits.size} forbidden symbol(s); see ${reportFile.absolutePath}\n" +
					hits.take(20).joinToString("\n"),
			)
		}
		reportFile.writeText("OK: ${lines.size} lines, none of ${forbiddenPatterns.get()}\n")
		logger.lifecycle("ObjC header OK (${lines.size} lines): ${headerFile.absolutePath}")
	}
}

val checkObjcHeader = tasks.register<CheckObjcHeaderTask>("checkObjcHeader") {
	group = "verification"
	description = "Liga o framework iosSimulatorArm64 de debug e verifica que o header ObjC não expõe coroutines, kotlinx-datetime nem suspend."
	val framework = kotlin.iosSimulatorArm64().binaries.getFramework(NativeBuildType.DEBUG)
	dependsOn(framework.linkTaskProvider)
	header.set(layout.file(provider { framework.outputFile.resolve("Headers/TechnosWatchProtocol.h") }))
	forbiddenPatterns.set(
		listOf(
			// Função `suspend` pública: o Kotlin/Native gera um bridge com callback.
			"completionHandler",
			// Tipos de kotlinx-coroutines e kotlinx-datetime em assinatura pública. O kotlinx-datetime arrasta junto os
			// serializadores do kotlinx-serialization, que também não podem aparecer.
			"Kotlinx_coroutines",
			"Kotlinx_datetime",
			"Kotlinx_serialization",
			// `Flow` ou `StateFlow` em assinatura pública, mesmo que viessem por outro caminho.
			"Flow",
			// `CoroutineContext`/`CoroutineScope` vêm da stdlib e não têm o prefixo `Kotlinx_`.
			"Coroutine",
		),
	)
	report.set(layout.buildDirectory.file("reports/objcHeader/forbidden-symbols.txt"))
	onlyIf("o link de frameworks iOS só roda em macOS") { HostManager.hostIsMac }
}

tasks.named("check") {
	dependsOn(checkObjcHeader)
}
