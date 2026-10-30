import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.kotlin.konan.target.HostManager
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
	alias(libs.plugins.kotlin.multiplatform)
	alias(libs.plugins.android.library)
	alias(libs.plugins.dokka)
	`maven-publish`
}

group = "br.com.grupotechnos.watch"
version = "0.1.0"

// Pastas do kit de teste compartilhado, dentro do :protocol (protocol/src/testKit/README.md).
val testKitDir: Directory = rootProject.layout.projectDirectory.dir("protocol/src")

kotlin {
	// Mesma regra do :protocol: toda declaração pública precisa ser explícita.
	explicitApi()
	jvmToolchain(17)

	androidTarget {
		publishLibraryVariants("release")
		compilerOptions {
			jvmTarget.set(JvmTarget.JVM_17)
		}
	}

	jvm()

	// O framework que o host iOS consome sai daqui, e não do :protocol: um framework guarda-chuva com o :protocol
	// exportado inteiro e o contrato JS do Core por cima. Mesmo nome (TechnosWatchProtocol) e, por isso, o mesmo prefixo
	// ObjC (TWP) e os mesmos nomes no Swift. Exportar é o que faz os tipos do :protocol aparecerem uma vez só, sem
	// prefixo de módulo (docs/decisoes.md, ADR 20).
	val xcFramework = XCFramework("TechnosWatchProtocol")
	listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
		target.binaries.framework {
			baseName = "TechnosWatchProtocol"
			isStatic = true
			export(project(":protocol"))
			xcFramework.add(this)
		}
	}

	sourceSets {
		commonMain.dependencies {
			// `api` porque os tipos do :protocol (domínio, perfis, log) aparecem na API pública do :bridge, e porque o
			// `export` do framework exige a dependência como `api`.
			api(project(":protocol"))
			implementation(libs.kotlinx.datetime)
		}
		commonTest.dependencies {
			implementation(kotlin("test"))
			// Os testes de concorrência (runBlocking, Dispatchers.Default) e os do CoreBridge, sobre uma WatchSession de
			// verdade em tempo virtual (runTest, StandardTestDispatcher), nos quatro targets.
			implementation(libs.kotlinx.coroutines.core)
			implementation(libs.kotlinx.coroutines.test)
			// RecordingLoader do kit de teste do :protocol (leitura das gravações .jsonl).
			implementation(libs.kotlinx.serialization.json)
		}
		// Kit de teste do :protocol (FakeTransport, SmaWatchSimulator, SessionHarness, ReplayDriver, ReplayScenario,
		// RecordingLoader), compilado de novo nos testes daqui: as mesmas pastas que o :protocol compila nos dele, sem
		// publicar código de teste. O kit usa o `internal` do :protocol (o codec SMA e o construtor da WatchSession com
		// dispatcher de teste); o acesso vem do bloco "Testes enxergam o internal do :protocol" mais abaixo.
		// protocol/src/testKit/README.md.
		commonTest {
			kotlin.srcDir(testKitDir.dir("testKit/kotlin"))
		}
		// jvmSharedTest: o lado de teste do grupo jvmShared (JVM + unit Android) do template de hierarquia abaixo, que só
		// cria o source set depois deste bloco; daí o `matching` em vez de `named`.
		matching { it.name == "jvmSharedTest" }.configureEach {
			kotlin.srcDir(testKitDir.dir("testKitJvm/kotlin"))
		}
		iosTest {
			kotlin.srcDir(testKitDir.dir("testKitIos/kotlin"))
		}
	}

	// O `actual` da trava reentrante é o mesmo na JVM e no Android (as duas são JVM): um grupo `jvmShared` no template
	// padrão de hierarquia, com o source set `jvmSharedMain` (src/jvmSharedMain/kotlin). Estender o template mantém o
	// iosMain; um `dependsOn` manual o desligaria. Não dá para usar o `srcDir` compartilhado dos testes do :protocol: o
	// Dokka analisa o código principal e falha com a mesma pasta em dois source sets ("common source roots").
	@OptIn(ExperimentalKotlinGradlePluginApi::class)
	applyDefaultHierarchyTemplate {
		common {
			group("jvmShared") {
				withJvm()
				withAndroidTarget()
			}
		}
	}
}

android {
	namespace = "br.com.grupotechnos.watch.bridge"
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

// Testes enxergam o `internal` do :protocol.
//
// O kit de teste compartilhado (protocol/src/testKit) usa o `internal` do :protocol: o codec SMA do relógio simulado e do
// replay, o Hex, e o construtor da WatchSession que recebe o dispatcher de teste (tempo virtual). No :protocol isso é
// natural, porque o teste é amigo do código principal do próprio módulo; aqui é outro módulo, e o compilador precisa
// tratar o :protocol como módulo amigo das compilações de teste do :bridge. O `associateWith` do KGP só liga
// compilações do mesmo target, do mesmo projeto ("Only associations between compilations of a single target are
// supported"), então:
// - JVM e unit Android: o artefato do :protocol que já está no classpath de cada compilação de teste entra em
//   `friendPaths` (API pública do KGP, `BaseKotlinCompile.friendPaths`);
// - Kotlin/Native: uma compilação sem fontes (`protocolFriends`) em cada target iOS, cuja saída é o klib do :protocol,
//   associada à compilação `test`; o KGP então passa os dois klibs (o do :bridge e o do :protocol) num `-friend-modules`
//   só. Um `-friend-modules` em `freeCompilerArgs` também funcionaria, mas repetiria o argumento que o KGP já passa (o
//   compilador avisa e fica com o último).
// Só as compilações de teste: o código principal do :bridge continua vendo apenas a API pública do :protocol.
val protocolBuildDir: Provider<Directory> = project(":protocol").layout.buildDirectory

tasks.withType<KotlinJvmCompile>().configureEach {
	if (name == "compileTestKotlinJvm" || name.endsWith("UnitTestKotlinAndroid")) {
		val classpath = libraries
		friendPaths.from(classpath.filter { it.startsWith(protocolBuildDir.get().asFile) })
	}
}

kotlin.targets.withType<KotlinNativeTarget>().configureEach {
	val main = compilations.getByName("main")
	val friends = compilations.create("protocolFriends")
	// O klib do :protocol que a compilação principal já recebe pela dependência `api`.
	friends.output.classesDirs.setFrom(main.compileDependencyFiles.filter { it.startsWith(protocolBuildDir.get().asFile) })
	compilations.getByName("test").associateWith(friends)
}

// As gravações de fixtures/ (o replay da gravação real) e o golden do CoreBridge (golden/) são inputs de toda tarefa de
// teste, como no :protocol: trocar um arquivo roda os testes de novo. O caminho chega ao processo de teste por
// propriedade de sistema na JVM e no unit Android e por variável de ambiente no simulador (o `simctl spawn` só repassa as
// com prefixo SIMCTL_CHILD_, e tira o prefixo). Ver golden/README.md e docs/fixtures.md, seção 4.1.
val fixturesDir: Directory = rootProject.layout.projectDirectory.dir("fixtures")
val goldenDir: Directory = layout.projectDirectory.dir("golden")

tasks.withType<Test>().configureEach {
	inputs.dir(fixturesDir).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.dir(goldenDir).withPropertyName("golden").withPathSensitivity(PathSensitivity.RELATIVE)
	// Regenerar o golden (BRIDGE_GOLDEN_REGENERATE=1) muda o input, então a tarefa não sai do cache sem rodar.
	inputs.property("goldenRegenerate", providers.environmentVariable("BRIDGE_GOLDEN_REGENERATE").orElse(""))
	systemProperty("fixtures.dir", fixturesDir.asFile.absolutePath)
	systemProperty("golden.dir", goldenDir.asFile.absolutePath)
}

tasks.withType<KotlinNativeTest>().configureEach {
	inputs.dir(fixturesDir).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.dir(goldenDir).withPropertyName("golden").withPathSensitivity(PathSensitivity.RELATIVE)
	environment("SIMCTL_CHILD_FIXTURES_DIR", fixturesDir.asFile.absolutePath)
	environment("FIXTURES_DIR", fixturesDir.asFile.absolutePath)
	environment("SIMCTL_CHILD_GOLDEN_DIR", goldenDir.asFile.absolutePath)
	environment("GOLDEN_DIR", goldenDir.asFile.absolutePath)
}

// Mesma regra de documentação do :protocol: só o que é `public`, e um símbolo público sem KDoc quebra o build.
dokka {
	moduleName.set("technos-watch-bridge")
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
 * Falha se o header Objective-C do framework expõe tipos que o Swift não consome bem, ou se uma declaração que tem de
 * existir uma vez só (a fachada do :protocol, o contrato do :bridge) some ou aparece duas vezes.
 *
 * A API pública precisa ser usável pelo host iOS sem shim: `suspend` vira `completionHandler`,
 * `Flow` e tipos do kotlinx viram classes opacas com prefixo `Kotlinx_`. A contagem pega um framework que deixou de
 * exportar o :protocol (os tipos dele voltariam com outro nome, ou duas vezes). Ver `docs/arquitetura.md`.
 */
abstract class CheckObjcHeaderTask : DefaultTask() {
	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val header: RegularFileProperty

	@get:Input
	abstract val forbiddenPatterns: ListProperty<String>

	/**
	 * Declarações que têm de aparecer exatamente uma vez no header. Uma entrada que termina em espaço, `<` ou `(` é um
	 * começo de linha (`@interface TWPWatchSession : `); as outras são a linha inteira (`@protocol TWPTransport`), para
	 * não casar um nome mais longo (`TWPTransportEvents`).
	 */
	@get:Input
	abstract val requiredOnce: ListProperty<String>

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
		for (prefix in requiredOnce.get()) {
			val count = lines.count { line ->
				val trimmed = line.trim()
				trimmed == prefix || (prefix.last() in " <(" && trimmed.startsWith(prefix))
			}
			if (count != 1) hits += "${headerFile.name}: [$prefix] expected exactly 1 declaration, found $count"
		}
		val reportFile = report.get().asFile
		reportFile.parentFile.mkdirs()
		if (hits.isNotEmpty()) {
			reportFile.writeText(hits.joinToString("\n", postfix = "\n"))
			throw GradleException(
				"The ObjC header has ${hits.size} problem(s); see ${reportFile.absolutePath}\n" +
					hits.take(20).joinToString("\n"),
			)
		}
		reportFile.writeText(
			"OK: ${lines.size} lines, none of ${forbiddenPatterns.get()}, exactly once: ${requiredOnce.get()}\n",
		)
		logger.lifecycle("ObjC header OK (${lines.size} lines): ${headerFile.absolutePath}")
	}
}

val checkObjcHeader = tasks.register<CheckObjcHeaderTask>("checkObjcHeader") {
	group = "verification"
	description = "Liga o framework iosSimulatorArm64 de debug e verifica que o header ObjC guarda-chuva não expõe coroutines, kotlinx-datetime nem suspend, e que tem o :protocol e o :bridge uma vez só."
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
	requiredOnce.set(
		listOf(
			// :protocol, exportado: a fachada, um protocolo do host e a ponte de bytes do iosMain (categoria).
			"@interface TWPWatchSession : ",
			"@protocol TWPTransport",
			"@interface TWPKotlinByteArray (Extensions)",
			// :bridge: o contrato JS comum, o controlador e o que o host implementa, e o conversor para Foundation do iosMain.
			"@interface TWPEventMapper : ",
			"@interface TWPPendingEvents<",
			"@interface TWPCoreBridge : ",
			"@protocol TWPCoreHost",
			"@protocol TWPCoreCompletion",
			"@protocol TWPCoreEventSink",
			"@interface TWPFoundationConverter : ",
		),
	)
	report.set(layout.buildDirectory.file("reports/objcHeader/forbidden-symbols.txt"))
	onlyIf("o link de frameworks iOS só roda em macOS") { HostManager.hostIsMac }
}

/**
 * Falha se um host Swift mínimo deixa de compilar contra o framework guarda-chuva do simulador.
 *
 * O `checkObjcHeader` procura o que não pode aparecer no header; esta tarefa confere o que precisa aparecer, do jeito que
 * o Swift lê: `swiftc -typecheck` (sem ligar, então o binário do framework não entra) de `swift-interop/MinimalHost.swift`,
 * que implementa `Transport`, `WatchLogger`, `SampleStore` e `WatchSessionListener`, monta uma `WatchSession`, usa a
 * ponte de bytes do `iosMain` do :protocol (`value.toData()`, `events.onNotification(characteristic:data:)`,
 * `events.onReadCompleted(requestId:data:status:)`, `ByteBridgeKt.toByteArray`) e o contrato do :bridge
 * (`FoundationConverter`, `EventMapper`, `HostErrorCodes`), e implementa `CoreHost`, `CoreCompletion` e `CoreEventSink`
 * para chamar todo método público do `CoreBridge`. Avisos também falham. Ver `swift-interop/README.md` e
 * `docs/decisoes.md`, ADRs 19 a 21.
 */
abstract class CheckSwiftInteropTask : DefaultTask() {
	@get:InputFiles
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val swiftSources: ConfigurableFileCollection

	/** O header e o module map do framework, que é o que o `swiftc` lê; mudou a API, a tarefa roda de novo. */
	@get:InputFiles
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val frameworkInterface: ConfigurableFileCollection

	/** Pasta que contém o `TechnosWatchProtocol.framework` (o `-F` do `swiftc`). */
	@get:Internal
	abstract val frameworkSearchPath: DirectoryProperty

	/** Ex.: `arm64-apple-ios15.1-simulator` (15.1 é o mínimo do pod do `react-native-technos-core-next`). */
	@get:Input
	abstract val targetTriple: Property<String>

	@get:Internal
	abstract val moduleCache: DirectoryProperty

	@get:OutputFile
	abstract val report: RegularFileProperty

	@get:Inject
	abstract val execOperations: ExecOperations

	@TaskAction
	fun check() {
		val output = ByteArrayOutputStream()
		val command = listOf(
			"xcrun", "--sdk", "iphonesimulator", "swiftc", "-typecheck", "-warnings-as-errors",
			"-target", targetTriple.get(),
			"-F", frameworkSearchPath.get().asFile.absolutePath,
			"-module-cache-path", moduleCache.get().asFile.absolutePath,
		) + swiftSources.files.sortedBy { it.name }.map { it.absolutePath }
		val result = execOperations.exec {
			commandLine(command)
			standardOutput = output
			errorOutput = output
			isIgnoreExitValue = true
		}
		val reportFile = report.get().asFile
		reportFile.parentFile.mkdirs()
		val text = output.toString(Charsets.UTF_8)
		reportFile.writeText(command.joinToString(" ", postfix = "\n") + text)
		if (result.exitValue != 0) {
			throw GradleException("Swift interop check failed (swiftc exit ${result.exitValue}); see ${reportFile.absolutePath}\n$text")
		}
		logger.lifecycle("Swift interop OK (${swiftSources.files.size} file(s), ${targetTriple.get()})")
	}
}

val checkSwiftInterop = tasks.register<CheckSwiftInteropTask>("checkSwiftInterop") {
	group = "verification"
	description = "Liga o framework iosSimulatorArm64 de debug e faz swiftc -typecheck de um host Swift mínimo (swift-interop/MinimalHost.swift) contra ele."
	val framework = kotlin.iosSimulatorArm64().binaries.getFramework(NativeBuildType.DEBUG)
	dependsOn(framework.linkTaskProvider)
	swiftSources.from(layout.projectDirectory.dir("swift-interop").asFileTree.matching { include("**/*.swift") })
	frameworkInterface.from(
		provider { framework.outputFile.resolve("Headers") },
		provider { framework.outputFile.resolve("Modules") },
	)
	frameworkSearchPath.set(layout.dir(provider { framework.outputFile.parentFile }))
	targetTriple.set("arm64-apple-ios15.1-simulator")
	moduleCache.set(layout.buildDirectory.dir("tmp/checkSwiftInterop/ModuleCache"))
	report.set(layout.buildDirectory.file("reports/swiftInterop/swiftc.txt"))
	onlyIf("swiftc e o framework iOS só existem em macOS") { HostManager.hostIsMac }
}

/**
 * Falha se um TurboModule mínimo em Objective-C++ deixa de compilar contra o framework guarda-chuva do simulador.
 *
 * É o par do `checkSwiftInterop` para o shim do iOS (`RCTNativeCoreNext.mm` do Repo 2), que é Objective-C++ e chama o
 * `TWPCoreBridge` direto: `clang++ -fsyntax-only` (sem ligar) de `objc-interop/MinimalTurboModule.mm`, que embrulha o par
 * resolve/reject do React Native num `TWPCoreCompletion`, é o `TWPCoreEventSink` (convertendo cada payload com o
 * `TWPFoundationConverter`) e chama cada método do `TWPCoreBridge` pelo seletor que o header gerado declara. Com ARC, um
 * seletor que não existe mais é erro; `-Werror` faz o resto falhar também (um método de protocolo não implementado, por
 * exemplo). Ver `objc-interop/README.md` e `docs/decisoes.md`, ADR 21.
 */
abstract class CheckObjcInteropTask : DefaultTask() {
	@get:InputFiles
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val sources: ConfigurableFileCollection

	/** O header e o module map do framework, que é o que o `clang++` lê; mudou a API, a tarefa roda de novo. */
	@get:InputFiles
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val frameworkInterface: ConfigurableFileCollection

	/** Pasta que contém o `TechnosWatchProtocol.framework` (o `-F` do `clang++`). */
	@get:Internal
	abstract val frameworkSearchPath: DirectoryProperty

	/** Ex.: `arm64-apple-ios15.1-simulator` (15.1 é o mínimo do pod do `react-native-technos-core-next`). */
	@get:Input
	abstract val targetTriple: Property<String>

	/** Opções do compilador (`-fobjc-arc`, `-fmodules`, `-std=c++20`, avisos). */
	@get:Input
	abstract val flags: ListProperty<String>

	@get:Internal
	abstract val moduleCache: DirectoryProperty

	@get:OutputFile
	abstract val report: RegularFileProperty

	@get:Inject
	abstract val execOperations: ExecOperations

	@TaskAction
	fun check() {
		val output = ByteArrayOutputStream()
		val command = listOf("xcrun", "--sdk", "iphonesimulator", "clang++", "-fsyntax-only") + flags.get() + listOf(
			"-target", targetTriple.get(),
			"-F", frameworkSearchPath.get().asFile.absolutePath,
			"-fmodules-cache-path=${moduleCache.get().asFile.absolutePath}",
		) + sources.files.sortedBy { it.name }.map { it.absolutePath }
		val result = execOperations.exec {
			commandLine(command)
			standardOutput = output
			errorOutput = output
			isIgnoreExitValue = true
		}
		val reportFile = report.get().asFile
		reportFile.parentFile.mkdirs()
		val text = output.toString(Charsets.UTF_8)
		reportFile.writeText(command.joinToString(" ", postfix = "\n") + text)
		if (result.exitValue != 0) {
			throw GradleException("Objective-C++ interop check failed (clang++ exit ${result.exitValue}); see ${reportFile.absolutePath}\n$text")
		}
		logger.lifecycle("Objective-C++ interop OK (${sources.files.size} file(s), ${targetTriple.get()})")
	}
}

val checkObjcInterop = tasks.register<CheckObjcInteropTask>("checkObjcInterop") {
	group = "verification"
	description = "Liga o framework iosSimulatorArm64 de debug e faz clang++ -fsyntax-only de um TurboModule mínimo em Objective-C++ (objc-interop/MinimalTurboModule.mm) contra ele."
	val framework = kotlin.iosSimulatorArm64().binaries.getFramework(NativeBuildType.DEBUG)
	dependsOn(framework.linkTaskProvider)
	sources.from(layout.projectDirectory.dir("objc-interop").asFileTree.matching { include("**/*.mm") })
	frameworkInterface.from(
		provider { framework.outputFile.resolve("Headers") },
		provider { framework.outputFile.resolve("Modules") },
	)
	frameworkSearchPath.set(layout.dir(provider { framework.outputFile.parentFile }))
	targetTriple.set("arm64-apple-ios15.1-simulator")
	flags.set(listOf("-fobjc-arc", "-fmodules", "-std=c++20", "-Wall", "-Wextra", "-Werror"))
	moduleCache.set(layout.buildDirectory.dir("tmp/checkObjcInterop/ModuleCache"))
	report.set(layout.buildDirectory.file("reports/objcInterop/clang.txt"))
	onlyIf("clang++ com o SDK do simulador e o framework iOS só existem em macOS") { HostManager.hostIsMac }
}

tasks.named("check") {
	dependsOn(checkObjcHeader)
	dependsOn(checkSwiftInterop)
	dependsOn(checkObjcInterop)
}
