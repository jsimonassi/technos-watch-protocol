# Fixtures: gravações de tráfego e replay

Uma *fixture* é uma gravação do tráfego GATT entre o telefone e o relógio, pacote a pacote, num arquivo `.jsonl`. Ela serve para rodar a sessão inteira (`WatchSession` → `SmaDriver` → codec) contra o relógio "gravado", sem relógio de verdade: é o critério A2a (sintética) e A2b (gravada do fitGPS) do plano.

## 1. Formato

Um objeto JSON por linha, UTF-8. A primeira linha é o cabeçalho; cada linha seguinte é **um pacote GATT cru** (um fragmento, nunca um frame remontado). O formatador é `log/FrameJsonl` (o mesmo que o host usa em produção) e o leitor é `testing/RecordingLoader` (no kit de teste compartilhado, `protocol/src/testKit/`), que roda nos quatro targets de teste (seção 4.1).

```
{"type":"header","device":"Mormaii_fitGPS","firmware":"1.2.3","platform":"android","mtu":247,"recordedAt":"2026-10-28T14:30:00-03:00","timeZone":"America/Sao_Paulo"}
{"type":"packet","dir":"TX","atMs":20,"characteristic":"6e400002-b5a3-f393-e0a9-e50e24dcca9e","hex":"AB00002519FB000103000100..."}
{"type":"packet","dir":"RX","atMs":40,"characteristic":"6e400003-b5a3-f393-e0a9-e50e24dcca9e","hex":"AB10000000000001"}
```

**Cabeçalho**

| Campo | Tipo | Significado |
|---|---|---|
| `type` | `"header"` | obrigatório na primeira linha |
| `device` | string | nome BLE do relógio |
| `firmware` | string ou `null` | versão `x.y.z` (do `02/0A`), quando conhecida |
| `platform` | string | `android`, `ios`, `synthetic`… só informativo |
| `mtu` | inteiro | MTU efetivo do link (23 se não foi negociado) |
| `recordedAt` | string | início da gravação, ISO-8601 **com offset numérico**, com ou sem milissegundos (`2026-10-29T08:14:10-03:00` ou `2026-10-29T08:14:10.933-03:00`); o replay usa como hora do `WatchClock` |
| `timeZone` | string | fuso IANA do telefone durante a gravação; o replay usa como fuso do `WatchClock` |

O `FrameRecorder` do app de exemplo passa a escrever `recordedAt` com o padrão `yyyy-MM-dd'T'HH:mm:ss.SSSxxx` (o offset zero sai `+00:00`, nunca `Z`). Os exports anteriores truncavam no segundo: na fixture real o `recordedAt` é 08:14:10 e o primeiro pacote saiu às 08:14:10,933. O leitor aceita os dois formatos (`RecordingLoaderTest`).

**Pacote**

| Campo | Tipo | Significado |
|---|---|---|
| `type` | `"packet"` | linhas com outro `type` são ignoradas pelo leitor (o formato pode crescer) |
| `dir` | `"TX"` ou `"RX"` | TX = escrita do telefone; RX = notificação do relógio |
| `atMs` | inteiro | milissegundos desde o início da gravação (informativo: o replay não espera) |
| `characteristic` | string | UUID de 128 bits em minúsculas |
| `hex` | string | bytes em hexadecimal maiúsculo sem separadores |

## 2. Pastas

```
fixtures/
	synthetic/<cenário>.jsonl              gravações geradas pelo codec (procedência na seção 5)
	real/<perfil>/<firmware>/<cenário>.jsonl   gravações do relógio de verdade, ex.: real/fitgps/1.2.3/pair-battery-version.jsonl
	tools/                                 entradas e saídas de exemplo dos scripts
```

- `<perfil>` é o `DeviceProfile.id` (`fitgps`); `<firmware>` é o `02/0A` lido no relógio; `<cenário>` diz o que foi feito, em kebab-case (`pair-sync`, `reconnect-battery`, `forget`).
- Um cenário por arquivo. Não editar à mão: se algo precisar sair, regrave.
- Todo `.jsonl` em `fixtures/real/` (qualquer profundidade) vira teste automaticamente (`RealFixturesReplayTest`), na JVM, nos testes unitários Android (debug e release) e no simulador iOS; não é preciso escrever código. A pasta `fixtures/` é input de toda tarefa de teste do Gradle, as `Test` (JVM e Android) e as `KotlinNativeTest` (simulador) (`protocol/build.gradle.kts`, seção 4.1): acrescentar, trocar ou renomear uma gravação roda os testes de novo, em vez de deixá-los `UP-TO-DATE` ou `FROM-CACHE`.
- Cada arquivo reproduzido imprime uma linha no stdout do teste, que fica no `system-out` do relatório XML de cada tarefa (`protocol/build/test-results/<tarefa>/TEST-br.com.grupotechnos.watch.protocol.session.RealFixturesReplayTest.xml`, com `<tarefa>` = `jvmTest`, `testDebugUnitTest`, `testReleaseUnitTest` ou `iosSimulatorArm64Test`): `REPLAY real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl: 850 packets, TX matched 167/167, final state READY`. Sem essa linha, a gravação não rodou naquele target.

## 3. Como gravar

### 3.1 Pelo app de exemplo (FrameRecorder)

O host recebe cada pacote em `WatchLogger.frame` (uma chamada por pacote, só do link da sessão; uma notificação que a sessão descarta por não ter link fica só no log, em DEBUG `Session/onNotification`, e não entra na gravação) e pode gravá-lo com `FrameJsonl.line`; o app de exemplo do `react-native-technos-core-next` faz isso no `RingLogger`/`FrameRecorder` e grava o arquivo pela aba **Log → Export** (`frames-<carimbo>.jsonl` em `Android/data/<app>/files/corenext/`, copiado com `adb pull`; o **Share** manda só as últimas entradas do log como texto). Implementação e detalhes no Repo 2. As linhas `SmaDriver/frame` do log, uma por frame remontado (`log/README.md`), são só diagnóstico e não entram na gravação.

Para a gravação reproduzir sem ajustes:
1. Comece a gravar antes de tocar no relógio na lista do scan (o cabeçalho precisa da hora e do fuso do início).
2. Depois que o estado chegar em `connected`, **espere 3 s** antes do primeiro comando: é o `postLoginDelayMs`, depois do qual a sessão manda o acerto de hora automático. O replay assume que ele vem logo depois do LOGIN (também depois de uma reconexão).
3. Faça um comando de cada vez, esperando o resultado na tela.
4. Exporte e copie para `fixtures/real/fitgps/<firmware>/<cenário>.jsonl`. Basta isso: `./gradlew :protocol:jvmTest` reproduz o arquivo e imprime a linha `REPLAY` (seção 2); `./gradlew :protocol:allTests` reproduz nos quatro targets, inclusive no simulador iOS.

**Cuidados com o export**
- **Os exports são cumulativos.** O recorder guarda tudo desde o último **Clear**, então cada export contém o anterior como prefixo exato (`frames-20261029-082857.jsonl`, 84 linhas, é o começo de `frames-20261029-083254.jsonl`, 851 linhas). Versione só o último export de cada sessão de gravação; dois arquivos com o mesmo começo viram dois testes da mesma conversa. Para um cenário por arquivo, faça **Export** e depois **Clear** entre um cenário e o outro (o Clear apaga o recorder).
- **`logs-*.jsonl` nunca entram no repositório.** O log nativo traz o MAC do relógio (scan, `openGatt`, `reconnectIfPaired`). Ele é a fonte do que a fixture não tem (descoberta de serviços, MTU, eventos de Bluetooth) e fica fora do git; os documentos citam o `ts` dele.
- **Confira o `frames-*.jsonl` antes de versionar**: ele não deve ter MAC. O formato só grava `type`, `dir`, `atMs`, `characteristic` e `hex`, mas um payload poderia carregar o endereço. A conferência da fixture real procurou o MAC do log no arquivo em três formas (com `:`, hex contínuo e hex com os bytes invertidos) e não achou nenhuma.

### 3.2 Por HCI snoop do Android (qualquer app, inclusive o Mormaii atual)

1. Opções do desenvolvedor → **Ativar registro de snoop HCI do Bluetooth**; desligue e ligue o Bluetooth.
2. Faça o cenário no app. Para o script detectar as handles sozinho, comece **antes** de conectar ao relógio, para a descoberta de serviços entrar na captura.
3. `adb bugreport bugreport.zip` e extraia `FS/data/misc/bluetooth/logs/btsnoop_hci.log` (o caminho varia por fabricante).
4. Abra no Wireshark, filtre por `btatt` (opcional) e use **File → Export Packet Dissections → As JSON…**.
5. Converta:

```
python3 -I scripts/hci2jsonl.py captura.json -o fixtures/real/fitgps/1.2.3/pair-sync.jsonl --firmware 1.2.3
```

O script (`scripts/hci2jsonl.py`, só biblioteca padrão, `--help` em português):
- TX = ATT Write Request (`0x12`) ou Write Command (`0x52`) na handle de escrita; RX = Handle Value Notification (`0x1b`) ou Indication (`0x1d`) da handle de notificação. Todo o resto (CCCD, descoberta, MTU, respostas, outros serviços) é descartado. Prepare Write (escrita longa) é contado e descartado: o protocolo não usa.
- Handles: `--write-handle`/`--notify-handle`, ou detecção pelo UUID nas declarações de characteristic da descoberta (Read By Type Response) ou na anotação `btatt.handle_tree` que o Wireshark põe em cada pacote. Preferência igual à da sessão: `FF61/FF62` antes de `6e400002/6e400003`. `--write-uuid`/`--notify-uuid` forçam outro par.
- MTU do cabeçalho: o negociado (Exchange MTU Request/Response) ou `--mtu`; `recordedAt` é o instante do primeiro pacote no fuso `--time-zone` (padrão `America/Sao_Paulo`).
- Aceita `frame.time_epoch` como número de segundos ou como ISO-8601 em UTC (as versões mais novas do Wireshark mudaram o formato desse campo) e chaves repetidas no mesmo objeto (o JSON do Wireshark repete campos).

Uma captura de outro app só reproduz se ele mandou a mesma sequência de frames que esta sessão manda (veja a seção 4). Do Mormaii atual, que faz polling a cada 10 s, ela serve para responder às hipóteses H1–H9 de `protocolo-sma.md` (seção 8), não como teste.

## 4. Replay

O `ReplayDriver` (`protocol/src/testKit/.../testing/ReplayDriver.kt`) liga a gravação ao `FakeTransport`:

1. A cada pacote que a sessão escreve, compara com o **próximo TX** da gravação. Se o próximo pacote gravado for RX ou a gravação tiver acabado, é um "TX inesperado".
2. Depois de cada TX, emite como notificação todos os RX gravados até o TX seguinte. RX antes do primeiro TX saem quando as notificações são ligadas.
3. No fim, o teste confere que não houve divergência e que nenhum TX gravado ficou sem ser enviado (`problems()`).

**Modo estrito** (padrão): os bytes do TX precisam ser iguais depois da máscara (`TxMasker`), que zera o que muda legitimamente entre gravação e replay:

| Campo | Por quê |
|---|---|
| seq (bytes 6..7) do frame de dados e do ACK/NACK | o seq do telefone depende de quantos frames a sessão já mandou desde que o driver nasceu |
| value do `02/01` SET_TIME (offset 13 em diante, inclusive em continuações) | é a hora do telefone no momento do envio |
| CRC (bytes 4..5), **só** do `02/01` SET_TIME | cobre o value, que muda; nos outros frames o CRC cobre só o L2, igual entre gravação e replay, e por isso é comparado |

Tudo o mais (CRC dos outros frames, cmd, key, tamanho, payload de BIND/LOGIN, ordem dos pedidos e dos ACKs) precisa bater. Comparar o CRC é o que deixa o replay contra uma gravação real pegar uma regressão do CRC no encoder; contra a gravação sintética, que é gerada pelo próprio `SmaFrameCodec`, a âncora independente continua sendo o `SmaFrameGoldenTest`. O modo não estrito (`strict = false`) só confere a alternância TX/RX.

**Relógio do replay.** `TestClock` parado em `recordedAt`, no fuso `timeZone`. Tempo virtual de 120 s por ação (cobre o BIND de 60 s).

**Ações deduzidas** (`ReplayScenario.deduce`, usado nas gravações reais), na ordem dos frames de dados TX:

| Frames TX | Ação na sessão |
|---|---|
| primeiro `03/01` | `pair` (os `03/01`/`03/03` seguidos são o handshake, inclusive retentativa e fallback) |
| primeiro `03/03` | `reconnect` |
| primeiro `02/01` logo após o handshake | nenhuma: é o acerto de hora automático |
| `02/01` | `setTime` |
| `02/08` | `readBattery` |
| `02/0A` | `readDeviceInfo` |
| `05/41`… (páginas), seguidos ou não de `05/45`… | `syncHistory([STEPS])` ou `syncHistory([STEPS, SLEEP])` (um `refreshSteps` gera os mesmos bytes) |
| `05/45`… sozinho | `syncHistory([SLEEP])` |
| `03/05` | `disconnect(forget = true)` |
| `03/03` no meio do arquivo | Bluetooth desligado e religado no `FakeTransport` (mudo no desligamento, contrato B, `decisoes.md`, ADR 18), seguido de `reconnect`, como o `CoreBridge` faz (`reconnectIfPaired`) quando o host avisa que o Bluetooth voltou. Os `03/03` seguidos e o primeiro `02/01` depois deles são o handshake e o acerto de hora automático. Uma queda com reconexão automática, ou um `disconnect` seguido de "Reconnect last", geram os mesmos bytes e reproduzem igual |
| `03/01` no meio do arquivo | `pair` de novo (depois de um Forget), com o handshake e o acerto de hora que vêm junto |
| qualquer outro | o teste falha dizendo qual `cmd/key` a sessão não sabe pedir |

**Fim de uma leitura de histórico.** Uma sequência de `05/41` (ou de `05/45`) termina na primeira página de resposta com menos de 20 registros, a mesma regra do `SmaDriver.readPages`: o tamanho do value da resposta gravada (`05/42` ou `05/46`, remontada pelo mesmo `SmaReassembler` da sessão) dividido pelo tamanho do registro (8 ou 7). Assim dois "Refresh steps" seguidos viram duas ações, e uma leitura de 64 páginas de sono vira uma só.

**MTU.** O `FakeTransport` responde ao pedido de MTU com o `mtu` do cabeçalho da gravação, e não com 247. Num link de 23 a sessão corta o BIND de 45 bytes em 20 + 20 + 5, e só assim os TX batem com uma gravação feita no Android, que negociou 23 com o fitGPS.

Uma gravação real passa quando: nenhuma divergência, todos os TX consumidos, todos os pedidos com terminal de sucesso e estado final `READY` (ou `DISCONNECTED`, se termina com `forget`).

### 4.1 Nos quatro targets

O `SyntheticFitGpsFixture` e o `RealFixturesReplayTest` estão no `commonTest`; o `RecordingLoader`, o `ReplayDriver`, o `ReplayScenario` e o `expect`/`actual` do disco estão no kit de teste compartilhado com o `:bridge` (`protocol/src/testKit/`, que os dois módulos compilam nos próprios testes; `protocol/src/testKit/README.md`). O parse usa só o `JsonElement` do `kotlinx-serialization-json` (dependência do `commonTest`, sem plugin), e o disco fica atrás de um `expect`/`actual` de teste (`testKit/.../testing/FixtureFiles.kt`: ler um arquivo, listar uma pasta, achar a pasta `fixtures/`). O `:bridge` lê a mesma gravação real nos quatro targets no `CoreBridgeGoldenTest` (linha `GOLDEN` no `system-out` de `bridge/build/test-results/<tarefa>/`), com a mesma ligação de caminho e de input no `bridge/build.gradle.kts`.

| Target | Tarefa | Como o caminho de `fixtures/` chega ao processo | `actual` |
|---|---|---|---|
| JVM | `jvmTest` | propriedade de sistema `fixtures.dir` (`systemProperty` em toda tarefa `Test`) | `src/testKitJvm/.../FixtureFilesJvm.kt` (`java.io.File`) |
| unit Android | `testDebugUnitTest`, `testReleaseUnitTest` | idem | o mesmo arquivo: a pasta `src/testKitJvm/kotlin` é `srcDir` de `jvmTest` e de `androidUnitTest` (no `:bridge`, do `jvmSharedTest`) |
| simulador iOS | `iosSimulatorArm64Test` | variável de ambiente `FIXTURES_DIR` | `src/testKitIos/.../FixtureFilesIos.kt` (Foundation, `getenv`) |

**Como o caminho chega ao simulador.** A tarefa `KotlinNativeSimulatorTest` do KGP 2.1.20 roda `xcrun simctl spawn --standalone <device> test.kexe`, e o `simctl` só repassa ao processo as variáveis de ambiente com o prefixo `SIMCTL_CHILD_`, tirando o prefixo. Por isso o `build.gradle.kts` faz `environment("SIMCTL_CHILD_FIXTURES_DIR", <caminho absoluto>)` em toda `KotlinNativeTest` (e `FIXTURES_DIR` sem prefixo, para um teste nativo que rode direto no macOS); o processo no simulador lê `FIXTURES_DIR`. Ele enxerga o disco do Mac, então o caminho absoluto basta. Sem a variável, ou com a pasta inexistente, `configuredFixturesDir()`/`Fixtures.root()` lançam e o teste falha, nunca passa em silêncio; o mesmo vale para a JVM sem `fixtures.dir`. A listagem de `fixtures/real` também tem código por target (`File.walkTopDown` na JVM, `NSFileManager.subpathsOfDirectoryAtPath` no iOS): se ela não encontrar a gravação versionada (`COMMITTED_REAL_RECORDING`, a do fitGPS 0.2.1), o `RealFixturesReplayTest` falha com `listing of … misses …`, em vez de pular todas as gravações. Conferido em 2026-10-29: com a listagem devolvendo nada, o teste falhou na JVM e no iOS.

**Input do Gradle.** As tarefas do Kotlin/Native não são tarefas `Test`, então o `tasks.withType<Test>` não as alcança: o `build.gradle.kts` tem um bloco `tasks.withType<KotlinNativeTest>` com o mesmo `inputs.dir(fixtures)` (caminho relativo na chave). Prova, feita em 2026-10-29: com tudo `UP-TO-DATE`, copiar a gravação real para `fixtures/real/fitgps/0.2.1/zz-input-proof-copy.jsonl` fez o `iosSimulatorArm64Test` rodar de novo e imprimir uma segunda linha `REPLAY real/fitgps/0.2.1/zz-input-proof-copy.jsonl: 850 packets, TX matched 167/167, final state READY`; trocar um byte do primeiro TX da cópia fez o teste falhar no iOS (`recording must start with BIND or LOGIN, got 03/00`); apagar a cópia fez a tarefa rodar de novo e passar.

**Onde fica o stdout do Kotlin/Native.** O KGP roda o binário de teste com `--ktest_logger=TEAMCITY` e separa a saída por teste: o `println` de cada teste vai para o `system-out` do XML em `protocol/build/test-results/iosSimulatorArm64Test/`, como na JVM. Como na JVM, o console do Gradle não mostra o stdout de um teste que passa.

### 4.2 Em tempo real, no dispatcher de produção

O replay da seção 4 roda em tempo virtual, com o construtor interno da `WatchSession` e o `StandardTestDispatcher`. O `RealtimeReplayTest` (`commonTest`, os quatro targets) reproduz a gravação real do fitGPS 0.2.1 com o construtor público (`Dispatchers.Default.limitedParallelism(1)`), em tempo real, e um host que entrega todos os eventos de uma fila serial própria (`RealtimeReplay` + `HostQueue`: `dispatch_queue_create` no iOS, um executor de uma thread na JVM). O mesmo `FakeTransport` + `ReplayDriver` (TX estrito, MTU do cabeçalho) roda dentro dessa fila.

- As esperas são reais: cada ação espera o callback terminal (prazo de 20 s) e, depois de cada handshake, o acerto de hora automático, que sai `postLoginDelayMs` (3 s) depois do `READY`. A ação seguinte só é pedida depois desse `02/01`; antes, ela entraria na fila na frente dele e o TX sairia fora da ordem gravada.
- No Bluetooth desligado e religado, o `reconnect` do host só é pedido depois que a sessão sai de `BLUETOOTH_OFF` (antes, ele terminaria em `TRANSPORT`).
- Uma divergência de TX, um terminal de erro, uma exceção na fila do host ou um erro inesperado da sessão encerram o teste na hora, com a mensagem e o ponto da gravação; o prazo global (90 s) é só a última rede.
- Cada target imprime `REALTIME-REPLAY real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl: TX matched 167/167, final state READY, <duração> ms` (cerca de 6100 ms) e uma linha com as threads observadas, no `system-out` do XML de `RealtimeReplayTest`.
- O que ele **não** prova: que o dispatcher é serial. O replay é pergunta e resposta e uma corrotina só consome a caixa, então os callbacks saem um por vez com qualquer dispatcher; trocar o `limitedParallelism(1)` por `Dispatchers.Default` não falha este teste. Quem guarda isso é o `SessionThreadingTest` (`arquitetura.md`, seção 4).
- O que ele confere sobre threading está em `arquitetura.md`, seção 4; a decisão e as checagens de mutação, na ADR 19 de `decisoes.md`.

## 5. Procedência das fixtures

### `synthetic/fitgps-pair-sync.jsonl`

- **Gerada**, não gravada: `commonTest/.../testing/SyntheticFitGpsFixture.kt` monta cada frame com `SmaFrameCodec`, `SmaFragmenter` e os payload codecs, sem rodar a sessão. O teste `SmaSessionReplayTest.committedFixtureMatchesTheGenerator` falha se o arquivo commitado divergir do gerador; para regenerar: `FIXTURES_REGENERATE=1 ./gradlew :protocol:jvmTest`.
- **Cabeçalho**: `Mormaii_fitGPS`, firmware `1.2.3`, `platform` `synthetic`, MTU 247, `2026-10-28T14:30:00-03:00`, `America/Sao_Paulo`.
- **Fragmentação**: cada frame do telefone numa escrita só (MTU 247 → fragmento 244); cada resposta do relógio com o L1 de 8 bytes numa notificação e o corpo em pedaços de 20 bytes, como o firmware (`SmaManager.java:506-528`). Seq do telefone de 1 a 9; seq do relógio de `0x0100` a `0x0106`.
- **Conversa** (32 frames, 49 pacotes): `03/01` BIND `FF…` → ACK → `03/02 [00]` (1,5 s depois, simulando a confirmação na tela) → ACK do telefone; `03/03` LOGIN → `03/04 [00]`; `02/01` automático; `02/01` do host; `02/08` → `02/09 [55]` (85 %); `02/0A` → `02/0B [01 02 03 04 05 06]` (firmware 1.2.3, BLE 4.5.6); `05/41` → 20 registros (força a segunda página); `05/41` → 3 registros; `05/45` → 5 registros de sono.
- **Passos**: página 1 = horas de 27/10 19:00 a 28/10 14:00, mode 0, passos 100, 110, …, 290. Página 2 = 28/10 14:20 com 777 (substitui 14:00), 28/10 10:15 mode 32 com 5000 (exercício, ignorado) e 28/10 13:59:59 com 500 (substitui 13:00). Totais esperados: **27/10 = 600**, **28/10 = 4007** (3300 − 280 − 290 + 500 + 777), 20 amostras horárias.
- **Sono**: 27/10 23:00 START, 23:30 LIGHT, 28/10 01:00 DEEP, 03:00 LIGHT, 06:30 END; o teste só confere uma sessão com `dayKey` 2026-10-28 (exploratório).
- **Conferência independente**: um script Python avulso (CRC-16/ARC próprio, decodificação dos registros e regra "último da hora, só mode 0") leu o arquivo, achou 32 frames com CRC certo e os mesmos totais 600 e 4007. Os frames de BIND, LOGIN e BATTERY são idênticos aos vetores dourados de `protocolo-sma.md`, seção 9.

### `tools/sample-wireshark.json` e `tools/sample-wireshark.expected.jsonl`

- **Feita à mão** no formato do "Export Packet Dissections > As JSON" do Wireshark, com 11 pacotes ATT que exercitam o `hci2jsonl.py`: Exchange MTU Request/Response (247), Read By Type Response com as declarações de `6e400002` (handle `0x000e`) e `6e400003` (handle `0x0010`) e uma chave `btatt.handle` repetida, escrita no CCCD `0x0011` e Write Response (descartadas), Write Request do BATTERY `AB 00 00 05 02 F8 …` (vetor dourado), notificações do ACK, do L1 e do corpo de `02/09 [55]` (CRC `D1C3` calculado à parte), Write Command com o ACK do telefone (com `frame.time_epoch` em ISO-8601, o formato das versões novas do Wireshark; os outros pacotes usam segundos) e uma notificação de outra handle (descartada).
- A saída esperada foi gerada pelo script e conferida à mão (2 TX, 3 RX, `atMs` 0/40/80/100/150, `recordedAt` `2026-10-28T14:30:00-03:00`). `Hci2JsonlTest` roda o script (se houver `python3`) e compara com ela, e também a lê com o `RecordingLoader`.
- Para rodar à mão: `python3 -I scripts/hci2jsonl.py fixtures/tools/sample-wireshark.json | diff - fixtures/tools/sample-wireshark.expected.jsonl`.

### `real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl`

- **Origem**: o export `frames-20261029-083254.jsonl` do app de exemplo do `react-native-technos-core-next`, copiado sem edição. FitGPS com firmware 0.2.1, telefone Android, 2026-10-29. O export anterior da mesma sessão (`frames-20261029-082857.jsonl`) é prefixo exato deste e não foi versionado; o log nativo (`logs-*.jsonl`) também não, porque tem o MAC.
- **Conferência de MAC**: o MAC do log não aparece no arquivo em nenhuma das três formas (seção 3.1).
- **Cabeçalho**: `Mormaii_fitGPS`, firmware `0.2.1`, `platform` `android`, MTU 23, `2026-10-29T08:14:10-03:00` (truncado no segundo; o primeiro pacote saiu 933 ms depois), `America/Sao_Paulo`.
- **Conteúdo**: 850 pacotes (167 TX, 683 RX), todos de no máximo 20 bytes, nas characteristics `6e400002`/`6e400003` do NUS; 161 frames de dados, nenhum CRC errado, nenhum NACK, nenhuma retransmissão.
- **Conversa**, com as 15 ações que o `ReplayScenario` deduz: conexão 1 (`atMs` 0 a 123713) com BIND + LOGIN `ANDROID_FF` e o acerto de hora automático, `syncHistory([STEPS])`, `readDeviceInfo` duas vezes, `readBattery`, `syncHistory([STEPS])` duas vezes; Bluetooth desligado por cerca de 230 s; conexão 2 (`atMs` 739559 a 1090678) com só LOGIN e o acerto de hora automático, `readDeviceInfo` duas vezes, `readBattery`, `syncHistory([STEPS])`, `setTime`, `syncHistory([STEPS])` e `syncHistory([STEPS, SLEEP])` com 64 páginas de sono. No app, as cinco leituras só de passos foram "Refresh steps" (pedidos #2, #6, #7, #12 e #14 no log), que gera os mesmos bytes de `syncHistory([STEPS])`; a última (#15) foi um Sync completo.
- **Replay**: `850 packets, TX matched 167/167, final state READY`, todos os pedidos com terminal de sucesso, nos quatro targets (`jvmTest`, `testDebugUnitTest`, `testReleaseUnitTest`, `iosSimulatorArm64Test`; seção 4.1). Em tempo real, no dispatcher de produção, `TX matched 167/167, final state READY` em cerca de 6,1 s, nos mesmos quatro (seção 4.2). O que a gravação provou sobre o relógio está em `protocolo-sma.md` (seções 1, 4, 7 e a tabela H1–H9 da seção 8), `sma-dados.md` (seção 6) e `decisoes.md` (ADRs 4, 5, 6 e 18).
