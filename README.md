# technos-watch-protocol

Biblioteca Kotlin Multiplatform que fala o protocolo BLE dos relógios Mormaii/Technos sem SDK de fabricante. Ela cuida do domínio (passos, sono, bateria, versão), da sessão com o relógio (conexão, handshake, fila de comandos, sincronização, reconexão) e dos codecs de cada família. O BLE da plataforma fica com o host, que implementa uma interface pequena (`Transport`) e entrega os callbacks de volta.

**Estado: prova de conceito.** Famílias suportadas: **SMA**, modelo **Mormaii fitGPS** (`Profiles.MORMAII_FIT_GPS`); **iDO**, modelo **Mormaii Life Ultra GPS** (`Profiles.MORMAII_LIFE_ULTRA_GPS`), este só com as funções básicas (pareamento com confirmação na tela, reconexão, bateria, firmware, hora e o total de passos do dia ao vivo; [`docs/protocolo-ido.md`](docs/protocolo-ido.md)); e **CRP** (Moyoung), modelo **Condor Reebok** (`Profiles.CONDOR_REEBOK`, nome BLE `P85`), na fase 1: pareamento e reconexão com o handshake de versão, o vínculo Bluetooth clássico pedido ao host, esquecer, bateria (com carga e aviso espontâneo), firmware, hora, passos por meia hora com os totais do dia e passos ao vivo, e sono ([`docs/protocolo-crp.md`](docs/protocolo-crp.md)). O CRP rodou com o relógio num Android em 2026-11-01 (pareamento, vínculo clássico aceito e recusado, esquecer, bateria, firmware, hora, passos de hoje e de ontem, sync e Bluetooth desligado e religado) e, no mesmo dia, num iPhone (pareamento com o relógio já pareado nos Ajustes, firmware, bateria, hora, Refresh steps, sync e uma reconexão); o usuário validou a fase 1 nas duas plataformas. As gravações reais `fixtures/real/condorreebok/MOY-VS02-2.0.0/pair-forget-pair-commands-sync-bt-reconnect.jsonl` (Android) e `ios-pair-commands-sync-reconnect.jsonl` (iPhone) reproduzem no replay, ao lado da fixture sintética e das fixtures de referência da captura HCI do app Condor de produção (`fixtures/reference/`); o que falta validar está em [`docs/pendencias.md`](docs/pendencias.md#condor-reebok-crp). O host é o `react-native-technos-core-next` (repositório irmão deste), no Android e no iOS: o XCFramework gerado aqui (com a ponte de bytes para o host Swift, pacote `ios`, só no `iosMain`) é o que o host iOS de lá usa. O host iOS rodou num iPhone com o fitGPS e o Life Ultra GPS em 2026-10-30, sem reconexão, segundo plano nem restauração validados; o Condor Reebok rodou com o relógio no Android e no iPhone em 2026-11-01 (as gravações acima; no iPhone, sem o esquecer e sem as notificações espontâneas, que não vieram na gravação e ficam como pendência inconclusiva). O contrato JavaScript do Core React Native (payloads dos eventos, estados, `deletedSavedData`, fila de eventos, pedidos em andamento) e o controlador que implementa cada método JS sobre a sessão (`CoreBridge`, com a plataforma atrás de `CoreHost`) moram no módulo `:bridge`, em código comum, para os dois hosts entregarem o mesmo ao JS. A primeira gravação real (fitGPS com firmware 0.2.1 e um telefone Android, `fixtures/real/fitgps/0.2.1/`) reproduz inteira no replay, na JVM, nos testes unitários Android e no simulador iOS: pareamento, comandos, Bluetooth desligado e religado, passos e 64 páginas de sono. Ela também roda em tempo real no dispatcher de produção, com os eventos vindos de uma fila de host, nos mesmos quatro targets. O que ela provou e as hipóteses que continuam abertas estão em [`docs/protocolo-sma.md`](docs/protocolo-sma.md), seção 8; a validação no relógio que ficou para depois está em [`docs/pendencias.md`](docs/pendencias.md).

**Não é:** um módulo React Native, um wrapper de SDK de fabricante nem uma implementação de BLE. Também ficam de fora DFU/OTA, notificações, chamadas, alarmes, HR, SpO2, o histórico V3 e o desvínculo do iDO, as fases 2 a 4 do CRP (reset de fábrica, que será um método separado do esquecer, formato de hora, perfil, metas, atividades, frequência cardíaca, estresse), o pareamento pelo CDM do Android (fora do prazo de 2026-11-05, ADR 34) e as outras famílias e modelos.

## Estrutura

```
protocol/                 módulo KMP do protocolo (android, jvm, iosArm64, iosSimulatorArm64); não depende do :bridge
	src/commonMain/...    código; cada pacote tem um README.md
	src/iosMain/.../ios/  ponte de bytes para o host Swift (ByteArray <-> NSData), só iOS
	src/commonTest/...    testes de codec, driver e sessão, replay das fixtures (rodam em JVM, Android e iOS)
	src/testKit*/...      kit de teste compartilhado com o :bridge (FakeTransport, relógios SMA, iDO e CRP simulados, SessionHarness, replay)
	src/iosTest/...       testes da ponte de bytes e os actual de teste do iOS (arquivo, fila GCD)
	src/jvmSharedTest/... actual de teste da JVM, compilados por jvmTest e androidUnitTest
	src/jvmTest/...       o que precisa da JVM: scripts de conversão, regravação das fixtures sintéticas, oráculo java.time
	Module.md             texto de abertura da documentação Dokka
bridge/                   módulo KMP do contrato JS do Core React Native (mesmos targets); depende do :protocol (api)
	src/commonMain/...    CoreBridge (o controlador), CoreHost, CoreEventSink, CoreCompletion, CorePairing, CoreLogEntry,
	                      EventMapper, StateMapper, SavedDataMapper, HostErrorCodes, PendingEvents, RequestRegistry
	src/iosMain/.../ios/  FoundationConverter (payloads como NSDictionary/NSArray, booleanos como CFBoolean), só iOS
	src/jvmSharedMain/... actual da trava reentrante (source set jvmSharedMain, grupo JVM + Android da hierarquia)
	src/commonTest/...    testes portados do host Android e os de contrato do CoreBridge sobre uma WatchSession de verdade,
	                      com o golden do fluxo de eventos (rodam em JVM, Android e iOS)
	src/iosTest/...       testes do FoundationConverter no simulador
	src/jvmTest/...       teste diferencial das datas contra o java.time
	golden/               o diário JS de referência da gravação real (CoreBridgeGoldenTest)
	swift-interop/        host Swift mínimo que o checkSwiftInterop compila contra o framework guarda-chuva
	objc-interop/         TurboModule mínimo em Objective-C++ que o checkObjcInterop compila contra o framework
	Module.md             texto de abertura da documentação Dokka
docs/                     arquitetura, protocolos SMA, iDO e CRP, dados, fixtures, decisões, glossário, pendências
fixtures/                 gravações .jsonl (synthetic/, real/ para replay; reference/ de outro app, só decodificadas; tools/)
scripts/hci2jsonl.py      converte um HCI snoop exportado do Wireshark em fixture
scripts/btsnoop2jsonl.py  converte um btsnoop bruto do Android em fixture de referência, com redação e trava contra endereço
```

## Requisitos

- JDK 17 (o Gradle encontra sozinho pelo toolchain).
- Android SDK com a plataforma 35 e o build-tools 35.0.0; o caminho vai em `local.properties` (`sdk.dir=...`).
- macOS com Xcode para os targets iOS, o simulador (`iosSimulatorArm64Test`), o `swiftc` do `checkSwiftInterop` e o `clang++` (com o SDK do simulador) do `checkObjcInterop`. Fora do macOS os targets iOS ficam desligados (`kotlin.native.ignoreDisabledTargets=true`) e as tarefas `checkObjcHeader`, `checkSwiftInterop` e `checkObjcInterop` são puladas.
- Versões fixas em `gradle/libs.versions.toml`: Kotlin 2.1.20, AGP 8.7.2, kotlinx-coroutines 1.10.1, kotlinx-datetime 0.6.2, Dokka 2.0.0; Gradle 8.12 pelo wrapper.

## Como consumir localmente

Não há publicação remota nesta PoC: o artefato vai para o Maven local (`~/.m2`).

```
./gradlew publishToMavenLocal
```

O `publishToMavenLocal` publica os dois módulos.

**Android / JVM.** Coordenadas `br.com.grupotechnos.watch:protocol:0.1.0` e `br.com.grupotechnos.watch:bridge:0.1.0` (o Gradle escolhe `-android` ou `-jvm` pelos metadados; o `bridge` traz o `protocol` como dependência `api`). `minSdk 26`, bytecode Java 17. `kotlinx-coroutines-core` e `kotlinx-datetime` vêm como dependências de runtime.

```kotlin
repositories {
	mavenLocal {
		content { includeGroup("br.com.grupotechnos.watch") }
	}
}

dependencies {
	implementation("br.com.grupotechnos.watch:protocol:0.1.0")
	// O contrato JS e o CoreBridge do Core React Native (o host Android do Repo 2 depende só desta linha: o
	// bridge traz o protocol como api).
	implementation("br.com.grupotechnos.watch:bridge:0.1.0")
}
```

**iOS.** Framework estático `TechnosWatchProtocol` dentro de um XCFramework (dispositivo + simulador arm64). Ele é gerado pelo `:bridge`, com o `:protocol` exportado inteiro: um framework só, com a sessão e o contrato JS lado a lado (`docs/decisoes.md`, ADR 20).

```
./gradlew assembleTechnosWatchProtocolXCFramework      # a tarefa só existe no :bridge
# bridge/build/XCFrameworks/release/TechnosWatchProtocol.xcframework  (e debug/)
```

No Swift os tipos aparecem sem prefixo (`WatchSession(transport:logger:store:)`); no Objective-C, com o prefixo `TWP`. A API pública não usa `suspend`, `Flow` nem tipos do `kotlinx-datetime`, e a tarefa `checkObjcHeader` garante isso no header gerado.

Bytes no Swift, pela ponte do `iosMain` (`protocol/src/iosMain/.../ios/README.md`):

```swift
// Transport.write e WatchLogger.frame entregam KotlinByteArray: uma cópia para Data.
func write(service: String, characteristic: String, value: KotlinByteArray, type: WriteType, requestId: Int32) {
	peripheral.writeValue(value.toData(), for: writeCharacteristic, type: .withResponse)
}
// peripheral(_:didUpdateValueFor:error:): o Data do CoreBluetooth entra direto.
session.events.onNotification(characteristic: characteristic.uuid.uuidString, data: value)
session.events.onReadCompleted(requestId: id, data: characteristic.value, status: 0)
// Data -> KotlinByteArray, para o resto da API que recebe bytes (ex.: FrameJsonl.line).
let bytes = ByteBridgeKt.toByteArray(data)
```

O host iOS responde `requestMtu` com `maximumWriteValueLength(for: .withoutResponse) + 3`, nunca com o valor de `.withResponse` (KDoc de `Transport.requestMtu`; `docs/decisoes.md`, ADR 5). A tarefa `checkSwiftInterop` compila um host Swift mínimo (`bridge/swift-interop/MinimalHost.swift`) contra o framework a cada `check`.

Payloads para o React Native, pelo `FoundationConverter` do `:bridge` (`bridge/src/iosMain/.../ios/README.md`):

```swift
// O Map do EventMapper vira NSDictionary com booleanos CFBoolean (o JS recebe true/false, não 1/0).
emitOnBatteryStatus(FoundationConverter.shared.toDictionary(payload: EventMapper.shared.battery(battery: battery)))
// Valor solto (isConnected): uma lista de um elemento, lida no Swift.
resolve(FoundationConverter.shared.toArray(values: [connected])[0])
```

## Uso mínimo (host)

```kotlin
// O host implementa Transport (BLE da plataforma), WatchLogger e SampleStore.
val session = WatchSession(transport, logger, store)
// Nos callbacks BLE, o host chama session.events.onConnected(), onNotification(...), onWriteCompleted(...) etc.
session.setListener(listener)  // WatchSessionListener: estado, resultados e erros, na thread da sessão

val profile = DeviceRegistry.identify(advertisedName, advertisedServiceUuids) ?: return  // no scan
session.pair(requestId = 1, address = macAddress, profileId = profile.id)                // termina em onCompleted(1)
session.readBattery(requestId = 2)                                                        // termina em onBattery(2, ...)
session.syncHistory(requestId = 3, kinds = emptyList())                                   // onSyncStarted/Progress/Finished
val days = session.getStepsDays(daysBefore = 3)                                           // síncrono, do SampleStore
```

O contrato completo (threading, regra de um callback terminal por `requestId`, o que o host precisa garantir) está em [`docs/arquitetura.md`](docs/arquitetura.md) e nos KDocs de `WatchSession`, `WatchSessionListener` e `Transport`.

O Core React Native não usa a sessão direto: passa por um `CoreBridge`, que faz o que cada método JS faz (seção 13 da arquitetura):

```kotlin
val session = WatchSession(transport, logger, store)
transport.attach(session.events)
val bridge = CoreBridge(session, logger, host)   // host: CoreHost (permissões, scan, FGS, pareamento salvo, log)
// No TurboModule, um método JS = uma chamada; o módulo é o CoreEventSink e a Promise vira uma CoreCompletion:
bridge.attach(module)                             // na primeira chamada de um método JS
bridge.pairDevice(deviceId, name, PromiseCompletion(promise))
```

## Testes e verificação

```
./gradlew :protocol:jvmTest                  # o mais rápido: codec, driver, sessão e replay das fixtures
./gradlew :protocol:iosSimulatorArm64Test    # os mesmos testes comuns + a ponte de bytes, no simulador iOS
./gradlew :protocol:allTests                 # JVM + testes unitários Android (debug e release) + simulador iOS
./gradlew :bridge:allTests                   # o contrato JS nos quatro targets + o diferencial das datas (JVM) + o FoundationConverter (iOS)
./gradlew check                              # os dois módulos: allTests + lint Android; no :bridge, checkObjcHeader + checkSwiftInterop + checkObjcInterop
./gradlew :bridge:checkSwiftInterop          # swiftc -typecheck de bridge/swift-interop/MinimalHost.swift contra o framework guarda-chuva
./gradlew :bridge:checkObjcInterop           # clang++ -fsyntax-only do TurboModule mínimo (bridge/objc-interop/) contra o framework
BRIDGE_GOLDEN_REGENERATE=1 ./gradlew :bridge:jvmTest --tests '*CoreBridgeGoldenTest*'   # regrava bridge/golden/ (revise o diff)
./gradlew dokkaHtml                          # documentação da API em protocol/build/dokka/html/ e bridge/build/dokka/html/
./gradlew :protocol:jvmTest --tests 'br.com.grupotechnos.watch.protocol.sma.SmaFrameGoldenTest'   # uma classe
```

O `dokkaHtml` falha se qualquer declaração pública ficar sem KDoc, nos dois módulos, inclusive no `iosMain`. Os testes de sessão rodam em tempo virtual e não precisam de relógio; a exceção é o `RealtimeReplayTest`, que reproduz a gravação real em tempo real no dispatcher de produção (cerca de 8 s por target). Todo `.jsonl` em `fixtures/real/` vira teste de replay automaticamente nos quatro targets, e a pasta é input de todas as tarefas de teste, inclusive a do simulador, então uma gravação nova roda na execução seguinte. Cada arquivo reproduzido imprime uma linha `REPLAY` no `system-out` do XML de cada tarefa (`protocol/build/test-results/<tarefa>/TEST-…RealFixturesReplayTest.xml`, `…IdoRealFixturesReplayTest.xml` para as gravações iDO e `…CrpRealFixturesReplayTest.xml` para as CRP; no iOS, `<tarefa>` é `iosSimulatorArm64Test`). As fixtures de referência (`fixtures/reference/`) não passam pelo replay; o `CrpReferenceFixtureTest` as decodifica. Como gravar, o que versionar e como converter está em [`docs/fixtures.md`](docs/fixtures.md).

## Documentação

| Documento | Conteúdo |
|---|---|
| [`docs/arquitetura.md`](docs/arquitetura.md) | módulos, camadas e regras de dependência, fluxos ida e volta, threading, máquina de estados, fila, erros, ponte de bytes do iOS, contrato JS comum (`:bridge`) |
| [`docs/protocolo-sma.md`](docs/protocolo-sma.md) | protocolo SMA byte a byte: GATT, frame, CRC, ACK/NACK, remontagem, handshake, catálogo, payloads, vetores dourados, diferenças Android × iOS |
| [`docs/sma-dados.md`](docs/sma-dados.md) | registros de passos e sono, agrupamento por hora, estimativas, analisador de sono |
| [`docs/protocolo-ido.md`](docs/protocolo-ido.md) | protocolo iDO legado do Life Ultra GPS: GATT, frame, informação básica, firmware, dados ao vivo, hora, vínculo com a autenticação e reconexão, o que não se sabe |
| [`docs/protocolo-crp.md`](docs/protocolo-crp.md) | protocolo CRP (Moyoung) do Condor Reebok: GATT, frame `FE EA`, fila, handshake de versão, catálogo, payloads, passos por meia hora, sono, Bluetooth clássico, diferenças Android × iOS, vetores dourados, o que não se sabe |
| [`docs/fixtures.md`](docs/fixtures.md) | formato `.jsonl` (TX, RX e READ), como gravar (app de exemplo, HCI snoop, btsnoop bruto), fixtures de referência, replay, procedência de cada fixture |
| [`docs/decisoes.md`](docs/decisoes.md) | decisões que não estão em nenhuma SDK (ADRs curtos), com como reverter |
| [`docs/pendencias.md`](docs/pendencias.md) | validação no relógio que ficou para depois (checklist) e itens técnicos abertos, com o que cada um fecha e onde registrar |
| [`docs/glossario.md`](docs/glossario.md) | termos de BLE, do protocolo, da sessão e dos testes |
| [`CLAUDE.md`](CLAUDE.md) | regras para quem muda o código (pessoas e agentes) |
| `protocol/src/commonMain/.../<pacote>/README.md`, `bridge/src/{commonMain,iosMain}/.../README.md` | propósito, limites, dependências, invariantes e testes de cada pacote |
