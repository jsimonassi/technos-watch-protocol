# CLAUDE.md

Guia curto para agentes e para quem chega agora. O que o repositório faz e como consumir está no [`README.md`](README.md); o desenho, em [`docs/arquitetura.md`](docs/arquitetura.md).

## O que é e o que não é

- **É**: um módulo Kotlin Multiplatform (`:protocol`, pacote `br.com.grupotechnos.watch.protocol`) com o domínio, a sessão com o relógio e o protocolo SMA (Mormaii fitGPS) implementado do zero, com testes que rodam sem relógio.
- **Não é**: React Native (isso é o `react-native-technos-core-next`), wrapper de SDK de fabricante (nenhuma SDK entra no build) nem BLE de plataforma (o host implementa `Transport`).
- Fora do escopo da PoC: outras famílias, DFU/OTA, notificações, chamadas, alarmes, HR/SpO2, host iOS, CI e publicação remota.
- O `TechnosWareCore` e as SDKs decompiladas são só referência de leitura; cite-os (`arquivo:linha`), não copie código.

## Comandos (na raiz do repositório)

| O quê | Comando |
|---|---|
| Testes rápidos (JVM) | `./gradlew :protocol:jvmTest` |
| Uma classe de teste | `./gradlew :protocol:jvmTest --tests 'br.com.grupotechnos.watch.protocol.sma.SmaDriverTest'` |
| Todos os targets (JVM, unit Android debug/release, simulador iOS) | `./gradlew :protocol:allTests` |
| Portão completo (testes + lint + header ObjC) | `./gradlew :protocol:check` |
| Header ObjC sem `suspend`/`Flow`/kotlinx | `./gradlew :protocol:checkObjcHeader` (relatório em `protocol/build/reports/objcHeader/`) |
| Documentação da API (falha com símbolo público sem KDoc) | `./gradlew :protocol:dokkaHtml` → `protocol/build/dokka/html/index.html` |
| Publicar no Maven local | `./gradlew publishToMavenLocal` → `br.com.grupotechnos.watch:protocol:0.1.0` |
| XCFramework | `./gradlew :protocol:assembleTechnosWatchProtocolXCFramework` → `protocol/build/XCFrameworks/{debug,release}/` |
| Regenerar a fixture sintética | `FIXTURES_REGENERATE=1 ./gradlew :protocol:jvmTest --tests '*SmaSessionReplayTest.committedFixtureMatchesTheGenerator'` |
| HCI snoop → fixture | `python3 -I scripts/hci2jsonl.py captura.json -o fixtures/real/fitgps/<fw>/<cenário>.jsonl --firmware <fw>` |

Antes de dizer que terminou: `./gradlew :protocol:check :protocol:dokkaHtml`. Se mexeu em algo que o Repo 2 consome, `./gradlew publishToMavenLocal` também.

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

Regras que não se negociam:
- `session/` nunca importa `sma/`; só `driver/Drivers.create` conhece as famílias.
- `transport/` não conhece família nem protocolo.
- A API pública não usa `suspend`, `Flow`, `value class` nem tipos do `kotlinx-datetime`/`kotlinx-coroutines` (o Swift não consome). Datas são epoch millis + ISO-8601 com offset. O `checkObjcHeader` pega o que escapar; corrija pela visibilidade (`internal`), nunca afrouxando a lista de padrões.
- `explicitApi()` está ligado: toda declaração precisa de visibilidade explícita. Só torne algo `public` se o host precisar.

## Convenções

- **Idioma**: documentação, READMEs, KDoc e comentários em português do Brasil. Identificadores, nomes de arquivo, mensagens de log, mensagens de exceção e códigos de erro em inglês.
- **Formato**: tabs em Kotlin e Gradle (`.editorconfig`); JSON/JSONL/TOML com 2 espaços.
- **KDoc** obrigatório em toda declaração pública (o `dokkaHtml` falha sem ele) e nos tipos internos que codificam regra de protocolo (`SmaReassembler`, `SmaDriver`, `SmaSleepAnalyzer`, `SessionEngine`…). KDoc de comando SMA cita o `cmd/key` e a seção do `docs/protocolo-sma.md`. KDoc multilinha é bem-vindo.
- **Comentários** explicam o porquê (quirk do firmware, decisão de compatibilidade), nunca o quê. Toda afirmação sobre o relógio cita a fonte (`SmaManager.java:1577-1591`, `SMAService.m:1056-1061`) ou a fixture que a prova.
- **Testes**: nomes em inglês que descrevem o comportamento (`ackIsWrittenBeforeTheResponseIsDelivered`). Codec e sessão ficam em `commonTest` (rodam em JVM, Android e iOS); o que lê arquivo fica em `jvmTest`. Tempo sempre virtual (`runTest`), nunca `Thread.sleep`.
- **Mudou um byte do protocolo** (frame, payload, catálogo): o vetor em `SmaFrameGoldenTest` e o `docs/protocolo-sma.md` (seções 6, 7 e 9) mudam na mesma alteração, com o vetor conferido de forma independente.
- **Fixtures**: `fixtures/real/<perfil>/<firmware>/<cenário>.jsonl`, cenário em kebab-case (`pair-sync.jsonl`); nunca editar à mão; as sintéticas só pelo gerador. Os exports do app de exemplo são cumulativos: versione só o último. Os `logs-*.jsonl` têm o MAC do relógio e nunca entram no repositório; confira que o `frames-*.jsonl` não tem MAC antes de copiar. A prova de que uma gravação rodou é a linha `REPLAY` no relatório do `RealFixturesReplayTest`.
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
| validação no relógio feita ou adiada, item técnico aberto ou fechado | `docs/pendencias.md` (marcar a caixa e registrar onde o item manda) |
| termo novo | `docs/glossario.md` |
| o que um pacote faz, depende ou garante | o `README.md` do pacote |
