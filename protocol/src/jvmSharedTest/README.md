# `jvmSharedTest/` — `actual` de teste comuns à JVM e ao teste unitário Android

Não é um source set: é uma pasta que `protocol/build.gradle.kts` acrescenta a `jvmTest` e a `androidUnitTest` (`kotlin.srcDir`), porque os dois rodam na JVM e precisam das mesmas implementações. Um source set intermediário com `dependsOn` desligaria o template padrão de hierarquia do Kotlin (e com ele o `iosMain`/`iosTest`).

| Arquivo | `expect` em `commonTest/.../testing/` | Implementação |
|---|---|---|
| `HostQueueJvm.kt` | `HostQueue.kt` | `Executors.newSingleThreadExecutor`, a "fila do host" do `RealtimeReplayTest` |

O `actual` do iOS está em `iosTest/.../testing/`. O `actual` da leitura das fixtures (`FixtureFilesJvm.kt`) mudou para o kit de teste compartilhado com o `:bridge`, em `../testKitJvm/` ([`../testKit/README.md`](../testKit/README.md)), que o `protocol/build.gradle.kts` também acrescenta a `jvmTest` e a `androidUnitTest`. Como o processo de teste recebe o caminho em cada target está em `docs/fixtures.md`, seção 4.1.
