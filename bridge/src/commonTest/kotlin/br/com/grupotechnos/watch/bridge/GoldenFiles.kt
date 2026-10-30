package br.com.grupotechnos.watch.bridge

/**
 * Caminho absoluto da pasta `bridge/golden/`, como o Gradle o entrega ao processo de teste (`bridge/build.gradle.kts`):
 * a propriedade de sistema `golden.dir` na JVM e no unit Android, a variável `GOLDEN_DIR` no simulador iOS (passada como
 * `SIMCTL_CHILD_GOLDEN_DIR`). Lança quando o caminho não chegou: um teste de golden nunca passa sem ter lido o arquivo.
 *
 * A leitura do arquivo usa o `readFixtureText` do kit de teste do :protocol.
 */
internal expect fun configuredGoldenDir(): String

/**
 * Regrava o golden em [path] com [text] quando o teste roda com `BRIDGE_GOLDEN_REGENERATE=1`, e devolve `true` nesse
 * caso. Só a JVM (e o unit Android, que também é JVM) grava; no simulador iOS devolve sempre `false`. Para regenerar:
 * `BRIDGE_GOLDEN_REGENERATE=1 ./gradlew :bridge:jvmTest --tests '*CoreBridgeGoldenTest*'` (golden/README.md).
 */
internal expect fun regenerateGoldenIfRequested(path: String, text: String): Boolean
