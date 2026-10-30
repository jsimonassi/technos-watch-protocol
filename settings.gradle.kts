pluginManagement {
	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositories {
		google()
		mavenCentral()
	}
}

rootProject.name = "technos-watch-protocol"

// :protocol é o domínio, a sessão e o protocolo; :bridge é o contrato JS comum do Core e gera o framework iOS. A
// dependência é só bridge -> protocol (docs/arquitetura.md, seção 12).
include(":protocol")
include(":bridge")
