# CLAUDE.md

Guia curto para agentes e para quem chega agora. O que o repositório faz e como consumir está no [`README.md`](README.md); o desenho, em [`docs/arquitetura.md`](docs/arquitetura.md).

## O que é e o que não é

- **É**: dois módulos Kotlin Multiplatform. `:protocol` (pacote `br.com.grupotechnos.watch.protocol`): o domínio, a sessão com o relógio e o protocolo SMA (Mormaii fitGPS) implementado do zero, com testes que rodam sem relógio. `:bridge` (pacote `br.com.grupotechnos.watch.bridge`): o contrato JavaScript do Core React Native em código comum (payloads dos eventos, estados, `deletedSavedData`, códigos de erro do host, fila de eventos, pedidos em andamento), o controlador `CoreBridge`, que implementa cada método JS sobre a `WatchSession` com a plataforma atrás de `CoreHost`/`CoreEventSink`/`CoreCompletion`, e o framework iOS guarda-chuva `TechnosWatchProtocol`, que exporta o `:protocol`.
- **Não é**: React Native (isso é o `react-native-technos-core-next`; o `:bridge` não depende de RN, só codifica o contrato em valores Kotlin), wrapper de SDK de fabricante (nenhuma SDK entra no build) nem BLE de plataforma (o host implementa `Transport`).
- Fora do escopo da PoC: outras famílias, DFU/OTA, notificações, chamadas, alarmes, HR/SpO2, host iOS, CI e publicação remota.
- O `TechnosWareCore` e as SDKs decompiladas são só referência de leitura; cite-os (`arquivo:linha`), não copie código.

## Comandos (na raiz do repositório)

| O quê | Comando |
|---|---|
| Testes rápidos (JVM) | `./gradlew :protocol:jvmTest` |
| Uma classe de teste | `./gradlew :protocol:jvmTest --tests 'br.com.grupotechnos.watch.protocol.sma.SmaDriverTest'` |
| Testes no simulador iOS (comuns + ponte de bytes) | `./gradlew :protocol:iosSimulatorArm64Test` (relatórios em `protocol/build/test-results/iosSimulatorArm64Test/`) |
| Todos os targets (JVM, unit Android debug/release, simulador iOS) | `./gradlew :protocol:allTests` |
| Contrato JS e `CoreBridge` (`:bridge`), todos os targets | `./gradlew :bridge:allTests` (o diferencial das datas contra o `java.time` é `:bridge:jvmTest --tests '*IsoDatesDifferentialTest'`, linhas `DIFFERENTIAL` no XML; o golden do fluxo de eventos imprime uma linha `GOLDEN` por target) |
| Regenerar o golden do `CoreBridge` (só com mudança intencional; revise o diff) | `BRIDGE_GOLDEN_REGENERATE=1 ./gradlew :bridge:jvmTest --tests '*CoreBridgeGoldenTest*'` (`bridge/golden/README.md`) |
| Portão completo dos dois módulos (testes + lint; no `:bridge`, header ObjC + interop Swift + interop ObjC++) | `./gradlew check` |
| Header ObjC guarda-chuva sem `suspend`/`Flow`/kotlinx, com `:protocol` e `:bridge` uma vez só | `./gradlew :bridge:checkObjcHeader` (relatório em `bridge/build/reports/objcHeader/`) |
| Host Swift mínimo compila contra o framework | `./gradlew :bridge:checkSwiftInterop` (`swiftc -typecheck` de `bridge/swift-interop/MinimalHost.swift`; saída em `bridge/build/reports/swiftInterop/`) |
| Shim Objective-C++ do TurboModule compila contra o framework | `./gradlew :bridge:checkObjcInterop` (`clang++ -fsyntax-only` de `bridge/objc-interop/MinimalTurboModule.mm`; saída em `bridge/build/reports/objcInterop/`) |
| Documentação da API (falha com símbolo público sem KDoc) | `./gradlew dokkaHtml` → `protocol/build/dokka/html/index.html` e `bridge/build/dokka/html/index.html` |
| Publicar no Maven local | `./gradlew publishToMavenLocal` → `br.com.grupotechnos.watch:protocol:0.1.0` e `br.com.grupotechnos.watch:bridge:0.1.0` |
| XCFramework (gerado pelo `:bridge`, com o `:protocol` exportado) | `./gradlew assembleTechnosWatchProtocolXCFramework` → `bridge/build/XCFrameworks/{debug,release}/TechnosWatchProtocol.xcframework` |
| Regenerar a fixture sintética | `FIXTURES_REGENERATE=1 ./gradlew :protocol:jvmTest --tests '*SmaSessionReplayTest.committedFixtureMatchesTheGenerator'` |
| HCI snoop → fixture | `python3 -I scripts/hci2jsonl.py captura.json -o fixtures/real/fitgps/<fw>/<cenário>.jsonl --firmware <fw>` |

Antes de dizer que terminou: `./gradlew check dokkaHtml` (os dois módulos). Se mexeu em algo que o Repo 2 consome, `./gradlew publishToMavenLocal` também (e `assembleTechnosWatchProtocolXCFramework`, se mudou API que o iOS vê).

## Pacotes, visibilidade e dependências

| Pacote | Visibilidade | Pode depender de |
|---|---|---|
| `domain/` | pública (menos `WatchException`) | nada |
| `transport/` | pública (menos `TransportEvent`) | nada |
| `profile/` | pública | nada |
| `log/` | pública | `util` |
| `session/` | pública: `WatchSession`, `WatchSessionListener`, `SampleStore`, `InMemorySampleStore`, `WatchClock`, `SystemWatchClock`; o resto é interno | tudo acima, `driver`, `util` (nunca `sma`) |
| `driver/` | interna, menos `HostDriver` (pública e vazia) | `domain`, `profile`, `transport`, `log`, `session/WatchClock`; `sma` só em `Drivers` |
| `sma/` (`codec/`, `commands/`, `data/`) | interna | `driver`, `domain`, `profile`, `transport`, `log`, `util` |
| `util/` | interna | só a stdlib e `kotlinx-datetime` |
| `ios/` (só `iosMain`) | pública, só no iOS: a ponte de bytes do host Swift | `transport`, `platform.Foundation`, `kotlinx.cinterop` |

No `:bridge`:

| Pacote | Visibilidade | Pode depender de |
|---|---|---|
| `bridge/` | pública: `CoreBridge`, `CoreHost`, `CoreEventSink`, `CoreCompletion`, `CorePairing`, `CoreLogEntry`, `EventMapper`, `StateMapper`, `SavedDataMapper`, `HostErrorCodes`, `PendingEvents`, `RequestRegistry`; internos `IsoDates` e `BridgeLock` (`actual` no source set `jvmSharedMain`, grupo JVM + Android do template de hierarquia, e no `iosMain`) | API pública do `:protocol` (`domain`, `profile`, `log`, `session`), `kotlinx-datetime`, stdlib |
| `bridge/ios/` (só `iosMain`) | pública, só no iOS: `FoundationConverter` | `platform.CoreFoundation`, `platform.Foundation`, `kotlinx.cinterop` |

Regras que não se negociam:
- A dependência entre módulos é só `:bridge` → `:protocol` (`api`). O `:protocol` nunca depende do `:bridge`, nem para teste: regra de protocolo fica no `:protocol`; o `:bridge` muda a forma do dado para o contrato JS e decide o que cada método JS faz (o `CoreBridge`), sempre pela API pública da `WatchSession`. O `:bridge` não depende de React Native nem de Android SDK. Só as compilações de **teste** do `:bridge` enxergam o `internal` do `:protocol` (módulo amigo: `friendPaths` na JVM e no unit Android, a compilação `protocolFriends` no Kotlin/Native), para usar o kit de teste compartilhado (`protocol/src/testKit/README.md`; ADR 21); o código principal, nunca.
- O framework iOS sai só do `:bridge` (`export(project(":protocol"))`, `baseName` `TechnosWatchProtocol`). Não declare framework no `:protocol`: os tipos dele apareceriam em dois frameworks com nomes diferentes (`docs/decisoes.md`, ADR 20).
- `session/` nunca importa `sma/`; só `driver/Drivers.create` conhece as famílias.
- `transport/` não conhece família nem protocolo.
- A API pública (dos dois módulos) não usa `suspend`, `Flow`, `value class` nem tipos do `kotlinx-datetime`/`kotlinx-coroutines` (o Swift não consome). Datas são epoch millis + ISO-8601 com offset. No `:bridge`, o que recebe um `TimeZone` é `internal` e a versão pública usa o fuso do sistema. O `checkObjcHeader` pega o que escapar; corrija pela visibilidade (`internal`), nunca afrouxando a lista de padrões.
- `explicitApi()` está ligado: toda declaração precisa de visibilidade explícita. Só torne algo `public` se o host precisar.
- Mudou um nome, rótulo ou tipo que o `bridge/swift-interop/MinimalHost.swift` usa (toda a `WatchSession`, os protocolos do host, `TransportEvents`, a ponte, o domínio que o host mapeia, todo o `CoreBridge` e as interfaces `CoreHost`/`CoreEventSink`/`CoreCompletion`; lista no `README.md` da pasta): o `checkSwiftInterop` falha; ajuste o arquivo na mesma alteração (e avise o Repo 2). O mesmo vale para os seletores que o `bridge/objc-interop/MinimalTurboModule.mm` chama (`checkObjcInterop`). Só é conferido o que o arquivo usa: API pública nova que o host vai chamar entra nele junto. Nada do código comum depende de `ios/`, em nenhum dos módulos.

## Convenções

- **Idioma**: documentação, READMEs, KDoc e comentários em português do Brasil. Identificadores, nomes de arquivo, mensagens de log, mensagens de exceção e códigos de erro em inglês.
- **Formato**: tabs em Kotlin e Gradle (`.editorconfig`); JSON/JSONL/TOML com 2 espaços.
- **KDoc** obrigatório em toda declaração pública (o `dokkaHtml` falha sem ele) e nos tipos internos que codificam regra de protocolo (`SmaReassembler`, `SmaDriver`, `SmaSleepAnalyzer`, `SessionEngine`…). KDoc de comando SMA cita o `cmd/key` e a seção do `docs/protocolo-sma.md`. KDoc multilinha é bem-vindo.
- **Comentários** explicam o porquê (quirk do firmware, decisão de compatibilidade), nunca o quê. Toda afirmação sobre o relógio cita a fonte (`SmaManager.java:1577-1591`, `SMAService.m:1056-1061`) ou a fixture que a prova.
- **Testes**: nomes em inglês que descrevem o comportamento (`ackIsWrittenBeforeTheResponseIsDelivered`). Codec, sessão e replay das fixtures ficam em `commonTest` (rodam em JVM, unit Android e simulador iOS); o disco passa pelo `expect`/`actual` de `testing/FixtureFiles.kt`. O kit de teste (transporte falso, relógio SMA simulado, `SessionHarness`, replay, leitura das fixtures) fica em `protocol/src/testKit/kotlin` (com os `actual` em `testKitJvm/` e `testKitIos/`), pastas que o `:protocol` e o `:bridge` compilam nos próprios testes; nada de `@Test` nem de dependência do `:bridge` ali. Em `jvmTest` só o que precisa da JVM (`python3`, regravar a fixture, `java.time` como oráculo). Tempo sempre virtual (`runTest`), nunca `Thread.sleep`; as exceções são o `RealtimeReplayTest`, o teste de fumaça do dispatcher de produção, que espera de verdade com prazo por ação, e a sonda do `SessionThreadingTest`, que confere que esse dispatcher é serial com esperas ocupadas de até 50 ms. No `:bridge`, os testes de concorrência do `PendingEventsTest` e do `RequestRegistryTest` usam threads de verdade (`runBlocking` + `Dispatchers.Default`, com prazo, e uma espera de 200 ms) para exercitar a trava de cada plataforma; o teste diferencial das datas (`IsoDatesDifferentialTest`) fica em `jvmTest`, porque o oráculo é o `java.time`. Os testes do `CoreBridge` (`CoreBridge*Test`) rodam sobre uma `WatchSession` de verdade em tempo virtual, com o `FakeCoreHost` e um diário único de eventos, liquidações e efeitos (`CoreBridgeTestKit.kt`); uma regra nova do controlador ganha uma linha no inventário (`docs/arquitetura.md`, seção 13) e um teste.
- **Mudou um byte do protocolo** (frame, payload, catálogo): o vetor em `SmaFrameGoldenTest` e o `docs/protocolo-sma.md` (seções 6, 7 e 9) mudam na mesma alteração, com o vetor conferido de forma independente.
- **Fixtures**: `fixtures/real/<perfil>/<firmware>/<cenário>.jsonl`, cenário em kebab-case (`pair-sync.jsonl`); nunca editar à mão; as sintéticas só pelo gerador. Os exports do app de exemplo são cumulativos: versione só o último. Os `logs-*.jsonl` têm o MAC do relógio e nunca entram no repositório; confira que o `frames-*.jsonl` não tem MAC antes de copiar. A prova de que uma gravação rodou é a linha `REPLAY` no `system-out` do XML do `RealFixturesReplayTest`, em cada target (`protocol/build/test-results/{jvmTest,testDebugUnitTest,testReleaseUnitTest,iosSimulatorArm64Test}/`); o caminho de `fixtures/` chega ao simulador por `SIMCTL_CHILD_FIXTURES_DIR` (`docs/fixtures.md`, seção 4.1).
- **Contrato com o host** (`transport/`, `session/WatchSession.kt`, `WatchSessionListener.kt`, `SampleStore.kt`): o `react-native-technos-core-next` depende dele; mudança de assinatura exige mudar o host junto.

## Onde está cada doc e quando atualizar

| Mudou… | Atualize |
|---|---|
| estado, transição, fila, threading, erro emitido | `docs/arquitetura.md` (seções 4 a 8) e o KDoc de `WatchSession`/`WatchErrorCodes` |
| byte, comando, payload, regra de remontagem, handshake | `docs/protocolo-sma.md` + vetor dourado |
| registro de histórico, agregação de passos, analisador de sono | `docs/sma-dados.md` |
| formato `.jsonl`, replay, script de conversão | `docs/fixtures.md` + `RecordingLoader` + `scripts/hci2jsonl.py` juntos |
| um default que nenhuma SDK define, ou uma hipótese confirmada/derrubada por fixture real | `docs/decisoes.md` (ADR) e a tabela H1–H9 do `protocolo-sma.md` |
| contrato com o host (`Transport`, `TransportEvents`, Bluetooth desligado) | KDoc de `Transport`/`TransportEvents`, `docs/arquitetura.md` (seção 5), `transport/README.md` e as ADRs 12 e 18 |
| ponte de bytes do iOS, nomes que o Swift vê | `ios/README.md` (no `iosMain`), `bridge/swift-interop/MinimalHost.swift`, `docs/arquitetura.md` (seção 11), ADR 19 e o `docs/ios-pendente.md` do Repo 2 |
| contrato JS (`:bridge`): payload, estado, código de erro do host, `deletedSavedData`, fila de eventos, conversor Foundation | `bridge/src/{commonMain,iosMain}/.../README.md`, `docs/arquitetura.md` (seção 12), ADR 20, e no Repo 2 o `src/types/events.ts`, o `docs/api-js.md` e o mock juntos |
| controlador (`CoreBridge`): o que um método JS faz, ordem evento/`resolve`, rejeição, pareamento salvo, trabalho em segundo plano, reconexão automática, ciclo de vida, `CoreHost` | KDoc do `CoreBridge`/`CoreHost`, `bridge/src/commonMain/.../README.md`, `docs/arquitetura.md` (seção 13, com a tabela por plataforma), ADR 21, o golden (`bridge/golden/`, regenerado e revisado), `MinimalHost.swift` e `MinimalTurboModule.mm` se a API mudou, e no Repo 2 o `AndroidCoreHost.kt`/`NativeCoreNextModule.kt` (se a API mudou; republicar e `--refresh-dependencies`), o `docs/api-js.md` e o `docs/arquitetura.md` |
| kit de teste compartilhado (`protocol/src/testKit*`) | `protocol/src/testKit/README.md`, os `srcDir` dos dois `build.gradle.kts`, `docs/arquitetura.md` (seção 10) |
| framework iOS, `export`, tarefas `checkObjcHeader`/`checkSwiftInterop`/`checkObjcInterop` | `bridge/build.gradle.kts`, ADRs 20 e 21, `README.md` (Como consumir) e, no Repo 2, o comentário do podspec, `ios/README.md` e `docs/ios-pendente.md` |
| leitura das fixtures nos targets, replay em tempo real | `docs/fixtures.md` (seções 4.1 e 4.2), `protocol/build.gradle.kts` (inputs e `SIMCTL_CHILD_FIXTURES_DIR`), ADR 19 |
| validação no relógio feita ou adiada, item técnico aberto ou fechado | `docs/pendencias.md` (marcar a caixa e registrar onde o item manda) |
| termo novo | `docs/glossario.md` |
| o que um pacote faz, depende ou garante | o `README.md` do pacote |
