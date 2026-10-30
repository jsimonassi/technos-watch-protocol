# `testKit/`, `testKitJvm/`, `testKitIos/` — kit de teste compartilhado entre `:protocol` e `:bridge`

Não são source sets: são pastas que os dois módulos acrescentam aos próprios testes (`kotlin.srcDir`), para o `:bridge`
testar o `CoreBridge` sobre uma `WatchSession` de verdade com o mesmo relógio simulado e o mesmo replay que o
`:protocol` usa, sem publicar código de teste e sem copiar arquivos.

| Pasta | Entra em (`:protocol`) | Entra em (`:bridge`) | Conteúdo |
|---|---|---|---|
| `testKit/kotlin` | `commonTest` | `commonTest` | `testing/`: `FakeTransport` (+ `FakeTransport.ido`/`scriptedIdo`, `replyBeforeWriteCompleted`), `SmaWatchSimulator` (+ `WatchReply`), `IdoWatchSimulator` (o Life Ultra GPS com as respostas do log da SDK, a confirmação do vínculo agendada por quem monta o teste e os casos de recusa, já vinculado, sem resposta, `pairFlag` 0, `03/01` recusado, respostas curtas e ruído: V3 e frames com o mesmo `cmd` e outra `key`), `IdoReplay.kt` (`IdoReplayScenario`, `IdoTxMasker`), `SessionTestKit.kt` (`TestClock`, `SessionTestLogger`, `RecordingListener`, `SessionEvent`), `Recording`, `RecordingLoader`, `ReplayDriver` (+ `PacketMasker`, `TxMasker`), `ReplayScenario` (+ `runReplay`, `REPLAY_ADDRESS`), `FixtureFiles.kt` (os `expect` de leitura de arquivo e `Fixtures`); `session/SessionHarness.kt` (`SessionHarness`, `runSession`, `ADDRESS`, `NOW_ISO`) |
| `testKitJvm/kotlin` | `jvmTest` e `androidUnitTest` | `jvmSharedTest` (o lado de teste do grupo JVM + Android) | `FixtureFilesJvm.kt` (`java.io.File`, propriedade `fixtures.dir`) |
| `testKitIos/kotlin` | `iosTest` | `iosTest` | `FixtureFilesIos.kt` (`NSFileManager`, variável `FIXTURES_DIR`) |

O que é só do `:protocol` continua nos source sets dele: `commonTest/.../testing/` (`HostQueue`, `RealtimeReplay`,
`SyntheticFitGpsFixture`), `jvmSharedTest/` (`HostQueueJvm`) e `iosTest/` (`HostQueueIos`, a ponte de bytes).

**Por que o `:bridge` enxerga o `internal` daqui.** O kit usa o `internal` do `:protocol`: o codec iDO (`IdoFrameCodec`, `IdoAuth`), o codec SMA (`SmaFrameCodec`,
`SmaReassembler`, `SmaLocalTime`…), o `Hex`, o `ByteWriter` e o construtor da `WatchSession` que recebe o dispatcher de
teste (tempo virtual). Compilado no `:bridge`, ele só funciona porque as compilações de teste do `:bridge` tratam o
`:protocol` como módulo amigo (`bridge/build.gradle.kts`, bloco "Testes enxergam o `internal` do :protocol"; ADR 21): o
`friendPaths` na JVM e no unit Android e uma compilação `protocolFriends` sem fontes no Kotlin/Native. O código
principal do `:bridge` não enxerga nada disso. A regra de dependência continua de um lado só: o `:protocol` não sabe que
o `:bridge` existe, nem nos testes.

**Regras.**
- Tudo aqui é `internal` e de teste: nada de `@Test` (as classes de teste ficam em cada módulo) e nada que dependa do
  `:bridge`.
- Os `expect` de `FixtureFiles.kt` têm `actual` em `testKitJvm/` e `testKitIos/`; um `expect` novo no kit precisa dos dois
  `actual` aqui, senão a compilação de um dos módulos quebra.
- Mudou o kit, rode os testes dos dois módulos: `./gradlew :protocol:allTests :bridge:allTests`.

As fixtures (`fixtures/`) chegam aos dois módulos do mesmo jeito: a pasta é input de toda tarefa de teste, e o caminho
vai por `fixtures.dir` (JVM e unit Android) e `SIMCTL_CHILD_FIXTURES_DIR` (simulador) (`docs/fixtures.md`, seção 4.1).
