# `golden/` — o que o JavaScript vê, gravado uma vez e conferido em todo teste

**Propósito.** Arquivos de referência do `CoreBridgeGoldenTest` (`bridge/src/commonTest/.../CoreBridgeGoldenTest.kt`): o
diário completo de uma gravação real passando pelo `CoreBridge` como o app a usaria, método JS por método JS. O teste
roda nos quatro targets (JVM, unit Android debug e release, simulador iOS) e falha na primeira linha diferente. É a rede
de segurança de ponta a ponta do contrato JS: uma mudança no `EventMapper`, no `StateMapper`, na ordem evento/`resolve`,
nas regras do pareamento salvo ou do trabalho em segundo plano aparece aqui, mesmo que nenhum teste unitário a pegue.

| Arquivo | Gravação | O que o roteiro chama |
|---|---|---|
| `fitgps-0.2.1-pair-commands-bt-reconnect-sync.txt` | `fixtures/real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl` | `initialize`, e cada ação deduzida da gravação (`ReplayScenario.deduce`) como o método JS que a produz: `pairDevice`, `refreshSteps` (o `Sync([STEPS])`), `getDeviceInfo`, `getBatteryStatus`, o ciclo de Bluetooth pelos ganchos do host (`onBluetoothTurnedOff`, `onBluetoothTurnedOn`), `setTime`, `syncDataHistory`; depois `isConnected`, `getLastPairedDevice`, `getHistoryPedometer`, `getHistorySleep` (sem sono na gravação: `NO_DATA`), `deletedSavedData` do primeiro dia, `getHistoryPedometer` de novo, `disconnectCurrentDevice` e `isConnected` |

## Como ler

Uma linha por acontecimento, na ordem em que o JS e a plataforma os veem:

| Linha | Quer dizer |
|---|---|
| `call <método>(<args>)` | o teste chamou o método JS (ou, entre colchetes, um gancho do host) |
| `event <nome> <payload>` | o `CoreEventSink.emit`; o payload com os tipos visíveis: `5` é `Int`, `5.0` é `Double` (arredondado a seis casas, para o texto ser igual na JVM e no Kotlin/Native), texto entre aspas, `null`, `true`/`false` |
| `resolve <método> <valor>` / `reject <método> <código> <mensagem>` | o `CoreCompletion` |
| `host <método> <argumento>` | um efeito pedido ao `CoreHost` (scan, trabalho em segundo plano, pareamento salvo, cabeçalho da gravação). As consultas (permissões, Bluetooth, pareamento salvo) não aparecem |

O `onLog` fica de fora (é volumoso e depende do logger do host). O endereço é o fictício dos testes,
`AA:BB:CC:DD:EE:FF`; o MAC do relógio real nunca entra aqui (a gravação de frames não o tem).

## Regenerar

Só quando a mudança é intencional:

```sh
BRIDGE_GOLDEN_REGENERATE=1 ./gradlew :bridge:jvmTest --tests '*CoreBridgeGoldenTest*'
```

A JVM regrava o arquivo (o simulador nunca grava) e o teste confere de novo. Depois, **revise o diff à mão** contra as
regras do host Android antigo (`docs/arquitetura.md`, seção 13): códigos 0/1/2/4, nomes e tipos dos campos de cada
evento, evento antes do `resolve`, efeitos do host antes do evento, `NO_DATA` como evento e rejeição. Um diff que você
não sabe explicar é regressão, não golden velho. Gravação nova em `fixtures/real/`: acrescente uma linha na tabela
acima e um teste no `CoreBridgeGoldenTest`, com um arquivo novo aqui.

A pasta é input das tarefas de teste do `:bridge` (`bridge/build.gradle.kts`): mudar o arquivo roda os testes de novo.
O caminho chega ao processo de teste por `golden.dir` (JVM e unit Android) e `GOLDEN_DIR` (simulador, passada como
`SIMCTL_CHILD_GOLDEN_DIR`).
