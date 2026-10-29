# technos-watch-protocol

Biblioteca Kotlin Multiplatform que fala o protocolo BLE dos relógios Mormaii/Technos sem SDK de fabricante. Ela cuida do domínio (passos, sono, bateria, versão), da sessão com o relógio (conexão, handshake, fila de comandos, sincronização, reconexão) e dos codecs de cada família. O BLE da plataforma fica com o host, que implementa uma interface pequena (`Transport`) e entrega os callbacks de volta.

**Estado: prova de conceito.** Família suportada: **SMA**, modelo **Mormaii fitGPS** (`Profiles.MORMAII_FIT_GPS`). O primeiro host é o `react-native-technos-core-next` (repositório irmão deste) no Android. O iOS já compila e gera o XCFramework, mas ainda não tem host. Nada foi validado com o relógio real: as hipóteses abertas estão em [`docs/protocolo-sma.md`](docs/protocolo-sma.md), seção 8.

**Não é:** um módulo React Native, um wrapper de SDK de fabricante nem uma implementação de BLE. Também ficam de fora DFU/OTA, notificações, chamadas, alarmes, HR, SpO2 e as outras famílias.

## Estrutura

```
protocol/                 único módulo KMP (android, jvm, iosArm64, iosSimulatorArm64)
	src/commonMain/...    código; cada pacote tem um README.md
	src/commonTest/...    testes de codec, driver e sessão (rodam em JVM, Android e iOS)
	src/jvmTest/...       replay de fixtures e teste do script de conversão
	Module.md             texto de abertura da documentação Dokka
docs/                     arquitetura, protocolo SMA, dados, fixtures, decisões, glossário
fixtures/                 gravações .jsonl para replay (synthetic/, real/, tools/)
scripts/hci2jsonl.py      converte um HCI snoop exportado do Wireshark em fixture
```

## Requisitos

- JDK 17 (o Gradle encontra sozinho pelo toolchain).
- Android SDK com a plataforma 35 e o build-tools 35.0.0; o caminho vai em `local.properties` (`sdk.dir=...`).
- macOS com Xcode para os targets iOS. Fora do macOS os targets iOS ficam desligados (`kotlin.native.ignoreDisabledTargets=true`).
- Versões fixas em `gradle/libs.versions.toml`: Kotlin 2.1.20, AGP 8.7.2, kotlinx-coroutines 1.10.1, kotlinx-datetime 0.6.2, Dokka 2.0.0; Gradle 8.12 pelo wrapper.

## Como consumir localmente

Não há publicação remota nesta PoC: o artefato vai para o Maven local (`~/.m2`).

```
./gradlew publishToMavenLocal
```

**Android / JVM.** Coordenada `br.com.grupotechnos.watch:protocol:0.1.0` (o Gradle escolhe `protocol-android` ou `protocol-jvm` pelos metadados). `minSdk 26`, bytecode Java 17. `kotlinx-coroutines-core` e `kotlinx-datetime` vêm como dependências de runtime.

```kotlin
repositories {
	mavenLocal {
		content { includeGroup("br.com.grupotechnos.watch") }
	}
}

dependencies {
	implementation("br.com.grupotechnos.watch:protocol:0.1.0")
}
```

**iOS.** Framework estático `TechnosWatchProtocol` dentro de um XCFramework (dispositivo + simulador arm64):

```
./gradlew :protocol:assembleTechnosWatchProtocolXCFramework
# protocol/build/XCFrameworks/release/TechnosWatchProtocol.xcframework  (e debug/)
```

No Swift os tipos aparecem sem prefixo (`WatchSession(transport:logger:store:)`); no Objective-C, com o prefixo `TWP`. A API pública não usa `suspend`, `Flow` nem tipos do `kotlinx-datetime`, e a tarefa `checkObjcHeader` garante isso no header gerado.

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

## Testes e verificação

```
./gradlew :protocol:jvmTest            # o mais rápido: codec, driver, sessão e replay das fixtures
./gradlew :protocol:allTests           # JVM + testes unitários Android (debug e release) + simulador iOS
./gradlew :protocol:check              # allTests + lint Android + checkObjcHeader
./gradlew :protocol:dokkaHtml          # documentação da API em protocol/build/dokka/html/index.html
./gradlew :protocol:jvmTest --tests 'br.com.grupotechnos.watch.protocol.sma.SmaFrameGoldenTest'   # uma classe
```

O `dokkaHtml` falha se qualquer declaração pública ficar sem KDoc. Os testes de sessão rodam em tempo virtual e não precisam de relógio. Todo `.jsonl` em `fixtures/real/` vira teste de replay automaticamente; como gravar e converter está em [`docs/fixtures.md`](docs/fixtures.md).

## Documentação

| Documento | Conteúdo |
|---|---|
| [`docs/arquitetura.md`](docs/arquitetura.md) | camadas e regras de dependência, fluxos ida e volta, threading, máquina de estados, fila, erros |
| [`docs/protocolo-sma.md`](docs/protocolo-sma.md) | protocolo SMA byte a byte: GATT, frame, CRC, ACK/NACK, remontagem, handshake, catálogo, payloads, vetores dourados, diferenças Android × iOS |
| [`docs/sma-dados.md`](docs/sma-dados.md) | registros de passos e sono, agrupamento por hora, estimativas, analisador de sono |
| [`docs/fixtures.md`](docs/fixtures.md) | formato `.jsonl`, como gravar (app de exemplo, HCI snoop), replay, procedência de cada fixture |
| [`docs/decisoes.md`](docs/decisoes.md) | decisões que não estão em nenhuma SDK (ADRs curtos), com como reverter |
| [`docs/glossario.md`](docs/glossario.md) | termos de BLE, do protocolo, da sessão e dos testes |
| [`CLAUDE.md`](CLAUDE.md) | regras para quem muda o código (pessoas e agentes) |
| `protocol/src/commonMain/.../<pacote>/README.md` | propósito, limites, dependências, invariantes e testes de cada pacote |
