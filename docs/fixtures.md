# Fixtures: gravações de tráfego e replay

Uma *fixture* é uma gravação do tráfego GATT entre o telefone e o relógio, pacote a pacote, num arquivo `.jsonl`. Ela serve para rodar a sessão inteira (`WatchSession` → driver da família → codec) contra o relógio "gravado", sem relógio de verdade: é o critério A2a (sintética) e A2b (gravada do fitGPS) do plano. Uma **fixture de referência** (`fixtures/reference/`) é diferente: é a gravação de outro app (o de produção), convertida de uma captura HCI, que não reproduz contra a nossa sessão e serve de prova do que o relógio responde (seção 2; ADR 32).

## 1. Formato

Um objeto JSON por linha, UTF-8. A primeira linha é o cabeçalho; cada linha seguinte é **um pacote GATT cru** (um fragmento, nunca um frame remontado) ou o valor de uma leitura GATT (`READ`). O formatador é `log/FrameJsonl` (o mesmo que o host usa em produção) e o leitor é `testing/RecordingLoader` (no kit de teste compartilhado, `protocol/src/testKit/`), que roda nos quatro targets de teste (seção 4.1).

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
| `firmware` | string ou `null` | versão do firmware, quando conhecida: `x.y.z` no SMA (`02/0B`) e no iDO (`02/EB`); no CRP, o texto do relógio (`MOY-VS02-2.0.0`, do `5A 01` ou do `2A28`) |
| `platform` | string | `android`, `ios`, `synthetic`, `android-hci` (fixture de referência convertida de um btsnoop)… só informativo |
| `mtu` | inteiro | MTU efetivo do link (23 se não foi negociado) |
| `recordedAt` | string | início da gravação, ISO-8601 **com offset numérico**, com ou sem milissegundos (`2026-10-29T08:14:10-03:00` ou `2026-10-29T08:14:10.933-03:00`); o replay usa como hora do `WatchClock` |
| `timeZone` | string | fuso IANA do telefone durante a gravação; o replay usa como fuso do `WatchClock` |

O `FrameRecorder` do app de exemplo passa a escrever `recordedAt` com o padrão `yyyy-MM-dd'T'HH:mm:ss.SSSxxx` (o offset zero sai `+00:00`, nunca `Z`). Os exports anteriores truncavam no segundo: na fixture real o `recordedAt` é 08:14:10 e o primeiro pacote saiu às 08:14:10,933. O leitor aceita os dois formatos (`RecordingLoaderTest`).

O leitor ignora chaves que não conhece no cabeçalho. As fixtures de referência usam isso para levar a procedência: `source` (`"hci"`), `app`, `connection` (handle, início e fim da conexão e a janela pedida ao conversor), `mtuInitiator` (`"peripheral"` quando quem abriu a troca de MTU foi o relógio), `gatt` (serviços, characteristics, propriedades e se têm CCCD, sem handles), `cccdWrites` (as CCCDs que o app escreveu, na ordem) e `redactions` (o que a redação tirou ou trocou, com as contagens). As docs citam esse resumo em vez da captura bruta.

**Pacote**

| Campo | Tipo | Significado |
|---|---|---|
| `type` | `"packet"` | linhas com outro `type` são ignoradas pelo leitor (o formato pode crescer) |
| `dir` | `"TX"`, `"RX"` ou `"READ"` | TX = escrita do telefone; RX = notificação do relógio; READ = valor devolvido por uma leitura GATT bem-sucedida (`DriverLink.read`), com a characteristic lida (ADR 27). O CRP lê a versão do protocolo (`2a29`), o firmware (`2a28`), a bateria (`2a19`) e os passos do dia (`fee1`); sem as leituras gravadas, uma gravação CRP não reproduz |
| `atMs` | inteiro | milissegundos desde o início da gravação (informativo: o replay não espera) |
| `characteristic` | string | UUID de 128 bits em minúsculas |
| `hex` | string | bytes em hexadecimal maiúsculo sem separadores |

## 2. Pastas

```
fixtures/
	synthetic/<cenário>.jsonl              gravações geradas pelo codec (procedência na seção 5)
	real/<perfil>/<firmware>/<cenário>.jsonl   gravações do relógio de verdade, ex.: real/fitgps/1.2.3/pair-battery-version.jsonl
	reference/<perfil>/<firmware>/<cenário>.jsonl   gravações de outro app (captura HCI convertida, com redação); nenhum teste genérico lê
	tools/                                 entradas e saídas de exemplo dos scripts
```

- `<perfil>` é o `DeviceProfile.id` (`fitgps`, `lifeultragps`, `condorreebok`); `<firmware>` é o lido no relógio (`02/0A` no SMA, `02/EB` no iDO, `5A 01`/`2A28` no CRP, ex.: `MOY-VS02-2.0.0`); `<cenário>` diz o que foi feito, em kebab-case (`pair-sync`, `reconnect-battery`, `forget`).
- Um cenário por arquivo. Não editar à mão: se algo precisar sair, regrave. A única exceção até hoje é o campo `device` do cabeçalho da primeira gravação do iPhone, trocado por script e registrado na procedência dela (seção 5).
- Todo `.jsonl` em `fixtures/real/` (qualquer profundidade) vira teste automaticamente (`RealFixturesReplayTest` para o SMA; as gravações cujo `device` o `DeviceRegistry` identifica como de outra família saem dele como `REPLAY …: skipped (family IDO, replayed by its family test)` e rodam no teste da família: as iDO no `IdoRealFixturesReplayTest`, com `IdoReplayScenario` e `IdoTxMasker`, e as CRP no `CrpRealFixturesReplayTest`, com `CrpReplayScenario` e `CrpTxMasker`; sem nenhuma gravação CRP, ele imprime `REPLAY crp: no recordings yet` e passa), na JVM, nos testes unitários Android (debug e release) e no simulador iOS; não é preciso escrever código. A pasta `fixtures/` é input de toda tarefa de teste do Gradle, as `Test` (JVM e Android) e as `KotlinNativeTest` (simulador) (`protocol/build.gradle.kts`, seção 4.1): acrescentar, trocar ou renomear uma gravação roda os testes de novo, em vez de deixá-los `UP-TO-DATE` ou `FROM-CACHE`.
- Cada arquivo reproduzido imprime uma linha no stdout do teste, que fica no `system-out` do relatório XML de cada tarefa (`protocol/build/test-results/<tarefa>/TEST-br.com.grupotechnos.watch.protocol.session.RealFixturesReplayTest.xml`, com `<tarefa>` = `jvmTest`, `testDebugUnitTest`, `testReleaseUnitTest` ou `iosSimulatorArm64Test`): `REPLAY real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl: 850 packets, TX matched 167/167, final state READY`. Sem essa linha, a gravação não rodou naquele target. O total de passos do dia que as duas gravações de 2026-10-29 e a do iPhone de 2026-10-30 produzem tem teste próprio, `RealFixturesStepsTest` (também nos quatro targets), que imprime linhas `STEPS-DAY` no `system-out` de `TEST-…session.RealFixturesStepsTest.xml` (seção 5); a noite da gravação do iPhone, o `RealFixturesSleepTest`, com uma linha `SLEEP`; e os valores de tela das gravações iDO, testes nomeados do `IdoRealFixturesReplayTest`. Uma gravação nova não entra nesses testes nomeados sozinha, só no replay genérico.

- **`fixtures/reference/`** guarda gravações de outro app que não reproduzem contra a nossa sessão (a sequência de pedidos é a do outro app). Nenhum teste genérico as lê; só testes nomeados as decodificam (`CrpReferenceFixtureTest`). Hoje: as três do Condor Reebok, convertidas da captura HCI do app Condor de produção com o `scripts/btsnoop2jsonl.py` (seção 3.3; procedência na seção 5). Elas nunca são editadas à mão: se algo precisar sair, rode o conversor de novo com outra redação.

## 3. Como gravar

### 3.1 Pelo app de exemplo (FrameRecorder)

O host recebe cada pacote em `WatchLogger.frame` (uma chamada por pacote, só do link da sessão; uma notificação que a sessão descarta por não ter link fica só no log, em DEBUG `Session/onNotification`, e não entra na gravação) e pode gravá-lo com `FrameJsonl.line`; o app de exemplo do `react-native-technos-core-next` faz isso no `RingLogger`/`FrameRecorder` e grava o arquivo pela aba **Log → Export** (`frames-<carimbo>.jsonl` em `Android/data/<app>/files/corenext/`, copiado com `adb pull`; o **Share** manda só as últimas entradas do log como texto). Implementação e detalhes no Repo 2. As linhas `SmaDriver/frame` do log, uma por frame remontado (`log/README.md`), são só diagnóstico e não entram na gravação.

Para a gravação reproduzir sem ajustes:
1. Comece a gravar antes de tocar no relógio na lista do scan (o cabeçalho precisa da hora e do fuso do início).
2. Depois que o estado chegar em `connected`, **espere 3 s** antes do primeiro comando: é o `postLoginDelayMs` do fitGPS (1 s no Life Ultra GPS, 250 ms no Condor Reebok), depois do qual a sessão manda o acerto de hora automático. O replay assume que ele vem logo depois do LOGIN (também depois de uma reconexão).
3. Faça um comando de cada vez, esperando o resultado na tela.
4. Exporte e copie para `fixtures/real/<perfil>/<firmware>/<cenário>.jsonl` (`fitgps`, `lifeultragps` ou `condorreebok`; no Condor Reebok, `fixtures/real/condorreebok/MOY-VS02-2.0.0/`). Basta isso: `./gradlew :protocol:jvmTest` reproduz o arquivo e imprime a linha `REPLAY` (seção 2); `./gradlew :protocol:allTests` reproduz nos quatro targets, inclusive no simulador iOS.

**Cuidados com o export**
- **Os exports são cumulativos.** O recorder guarda tudo desde o último **Clear**, então cada export contém o anterior como prefixo exato (`frames-20261029-082857.jsonl`, 84 linhas, é o começo de `frames-20261029-083254.jsonl`, 851 linhas). Versione só o último export de cada sessão de gravação; dois arquivos com o mesmo começo viram dois testes da mesma conversa. Para um cenário por arquivo, faça **Export** e depois **Clear** entre um cenário e o outro (o Clear apaga o recorder).
- **Uma exportação tem um cabeçalho só.** O `device` e o `firmware` do cabeçalho são do último relógio que chegou ao handshake (o `CoreBridge` troca o nome quando a sessão chega em `HANDSHAKING` com outro relógio e apaga o firmware até o `onDeviceInfo` do novo; um pedido que nunca conecta ou um link que falha na configuração não mexe em nada, `docs/decisoes.md`, ADR 24). Os pacotes, porém, são todos os gravados desde o último **Clear**: trocar de relógio sem **Export** e **Clear** no meio deixa os pacotes do primeiro sob o cabeçalho do segundo, e o replay os mandaria para a família errada. O `mtu` também não é o do link gravado: os dois hosts leem o MTU atual do transporte na hora do export (no iOS, o transporte volta a 23 a cada tentativa de conexão, mesmo uma que falha). Antes de versionar, confira que o cabeçalho e os pacotes são do mesmo relógio. Até a ADR 24, o nome mudava já no pedido: a primeira gravação do iPhone (`frames-20261030-093133.jsonl`, só pacotes do fitGPS) saiu com `device` `"Life Ultra GPS"` e `firmware` `"0.2.1"`, por causa dos pedidos ao Life Ultra GPS que falharam depois. Ela foi versionada com o `device` corrigido para `Mormaii_fitGPS` e nada mais mudado (`real/fitgps/0.2.1/ios-pair-info-time-steps-sleep-sync.jsonl`, seção 5).
- **No CRP, o `mtu` do cabeçalho decide o `b2` de todo TX no replay.** O `FakeTransport` responde ao pedido de MTU com o `mtu` do cabeçalho (seção 4), e o driver calcula o bloco a partir dele: 517 dá bloco 244 e `b2 = 0x20`; 23 dá bloco 20 e `b2 = 0x10`. Uma gravação feita num link de 517 e exportada com `mtu` 23 (o transporte já tinha voltado a 23, por exemplo depois de uma tentativa de conexão no iOS) falha no primeiro `5A 00`. Exporte com o link ainda aberto, ou confira o `mtu` contra o `b2` dos TX antes de versionar.
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

### 3.3 Por btsnoop bruto do Android (`btsnoop2jsonl.py`, fixtures de referência)

Para o CRP, a captura foi lida direto do arquivo btsnoop (H4), sem passar pelo Wireshark, porque as docs precisavam das leituras GATT, do GATT descoberto e da ordem das CCCDs, e a captura tinha dados pessoais que só dá para tirar remontando os frames. O resultado é uma fixture de referência (seção 2; ADR 32).

1. Grave a captura como na seção 3.2 (passos 1 a 3). Guarde o `btsnoop_hci.log.last` e o `btsnoop_hci.log` **fora do repositório**: o btsnoop bruto tem os endereços do relógio e de outros aparelhos e nunca entra no git.
2. Liste as conexões, de fora da pasta da captura e com `python3 -I`:

```
python3 -I scripts/btsnoop2jsonl.py --list btsnoop_hci.log.last btsnoop_hci.log
```

   Cada conexão LE sai com um rótulo `dev-xxxxxx` (o endereço nunca é impresso), o handle, o início e o fim, o papel (`role=central` ou `peripheral`), a contagem de pacotes ATT e os serviços descobertos. O rótulo é o HMAC-SHA256 do endereço com uma chave tirada da própria captura (o SHA-256 do primeiro MiB do primeiro arquivo): sem a captura ele não se reverte, e muda de uma captura para outra, então use os mesmos arquivos, na mesma ordem, no `--list` e na conversão. Mesmo assim o rótulo real não se versiona: os comandos dos docs usam `dev-xxxxxx`, e o `Btsnoop2JsonlTest.noRealDeviceLabelIsVersioned` falha se um rótulo de verdade aparecer nos scripts, docs, fixtures ou fontes.
3. Converta uma conexão, filtrando pelo rótulo, pelo handle e por uma janela de horário local:

```
python3 -I scripts/btsnoop2jsonl.py --family crp --peer dev-xxxxxx --conn 11 --since 15:43 --until 15:46:11 \
	--app "Condor (produção, Android)" btsnoop_hci.log.last btsnoop_hci.log \
	-o fixtures/reference/condorreebok/MOY-VS02-2.0.0/condor-app-pair-sync.jsonl
```

O script (`scripts/btsnoop2jsonl.py`, python3 3.9 ou maior, só biblioteca padrão, `--help` em português; as mensagens de erro e o `--list` em inglês, com o prefixo `btsnoop2jsonl:` nos erros, como o `hci2jsonl.py`):
- **Horário**: o btsnoop guarda o relógio de parede do telefone (`EPOCH_DELTA` entre a época dele e 1970); `--time-zone` (padrão `America/Sao_Paulo`) dá o offset do `recordedAt`, que é o início da janela pedida (ou da conexão).
- **Mapa de handles** pela descoberta (Read By Group Type, Read By Type `0x2803` → UUID de cada characteristic, Find Information → CCCD). Com `--family crp`: TX = ATT Write Command/Request em `fee2`; RX = Notification/Indication em `fee3`, `fee1`, `2a19` e `2a37`; `READ` = o par Read Request (telefone) / Read Response (relógio) em `2a29`, `2a28`, `2a19` e `fee1`. Todo o resto fica de fora: as escritas de CCCD (que vão só para o `cccdWrites` do cabeçalho), a descoberta, `FEC9`, `FEA1`, `190E`, `AE00`, o Bluetooth clássico e o SMP.
- **Redação no nível do frame** (`FE EA` remontado nos dois sentidos): todos os pacotes dos frames cujo `cmd` está em `--redact-cmds` (padrão `41,42,43,b5`: texto de notificação, previsão do tempo com as temperaturas de cada dia, clima e localização) são descartados; o payload do frame `0x12` (perfil: altura, peso, idade, sexo) é trocado por `--fake-user-info` (padrão `b24b1800`: 178 cm, 75 kg, 24 anos, masculino, os valores do app de exemplo da SDK), com o cabeçalho do frame refeito. As contagens vão para o `redactions` do cabeçalho.
- **Trava**: todos os endereços que a captura mostra ficam só na memória: o do telefone (Command Complete do `Read_BD_ADDR`), os pares de LE (Enhanced) Connection Complete (com os RPA), de Connection Complete e Connection Request clássicos, os alvos de Create Connection e de LE (Extended) Create Connection, os de SSP (IO Capability, User Confirmation, Passkey, Simple Pairing Complete), link key e PIN, os de advertising reports (legado, direcionado e estendido), das listas de filtro e de resolução, e o Identity Address do SMP. Se algum aparecer na saída (bytes diretos, invertidos ou como texto com dois-pontos), o script aborta sem escrever nada, e a mensagem não mostra o endereço. Endereços com todos os bytes iguais (o `00:00:…` de uma conexão que falhou) são pulados: não identificam ninguém e casariam com qualquer sequência de zeros.
- **Cabeçalho**: `device` = o 2A00 lido na captura (ou `--device`), `firmware` = o 2A28 lido (ou `--firmware`), `platform` `android-hci`, `mtu` = o menor dos valores da troca de MTU, mais as chaves extras da seção 1. O rótulo `dev-xxxxxx` nunca vai para a fixture.

**Antes de versionar**, confira com um script avulso (`python3 -I`, fora da pasta da captura) que a saída não tem nenhum frame `0x41`, `0x42`, `0x43` ou `0xB5`, que todo `0x12` é o valor fictício, que só há a conexão escolhida, e que nenhum endereço da captura aparece em nenhuma forma. O `CrpReferenceFixtureTest.redactionRemovedTextFramesAndFakedTheProfile` repete as duas primeiras conferências a cada execução, e o `Btsnoop2JsonlTest` (`jvmTest`, roda o `python3`) monta um btsnoop sintético num diretório temporário, com um endereço inventado só no código do teste, e confere a redação, a leitura de `2a29`, o cabeçalho, a trava contra o endereço do relógio, o do telefone e o de um par clássico, o rótulo com chave e que nenhum rótulo real está versionado (`syntheticCaptureBecomesARedactedReferenceRecording`, `addressInAPacketAbortsWithoutWriting`, `phoneAddressInAPacketAbortsWithoutWriting`, `classicPeerAddressInAPacketAbortsWithoutWriting`, `listNeverPrintsTheAddressAndTheLabelIsKeyedByTheCapture`, `noRealDeviceLabelIsVersioned`).

## 4. Replay

O `ReplayDriver` (`protocol/src/testKit/.../testing/ReplayDriver.kt`) liga a gravação ao `FakeTransport`:

1. Um "pedido" é um TX ou uma leitura. A cada pacote que a sessão escreve, compara com o **próximo pacote** da gravação, que tem de ser TX; se for RX ou `READ`, ou se a gravação tiver acabado, é um "TX inesperado". A cada leitura da sessão (`FakeTransport.onRead`), responde com o próximo pacote, que tem de ser um `READ` da mesma characteristic; senão, divergência e status `NOT_FOUND`.
2. Depois de cada pedido, emite como notificação todos os RX gravados até o pedido seguinte, cada um na characteristic gravada (`FakeTransport.notifyOn`; no CRP, `fee3`, `fee1`, `2a19` ou `2a37`). Os RX gravados depois de uma leitura saem depois da resposta dela (`FakeTransport.afterRead`), na ordem da gravação. RX antes do primeiro pedido saem quando as notificações são ligadas.
3. No fim, o teste confere que não houve divergência e que nenhum TX nem `READ` gravado ficou sem ser feito (`problems()`); `recordedReads`/`matchedReads` contam as leituras.

**Modo estrito** (padrão): os bytes do TX precisam ser iguais depois da máscara (`TxMasker`), que zera o que muda legitimamente entre gravação e replay:

| Campo | Por quê |
|---|---|
| seq (bytes 6..7) do frame de dados e do ACK/NACK | o seq do telefone depende de quantos frames a sessão já mandou desde que o driver nasceu |
| value do `02/01` SET_TIME (offset 13 em diante, inclusive em continuações) | é a hora do telefone no momento do envio |
| CRC (bytes 4..5), **só** do `02/01` SET_TIME | cobre o value, que muda; nos outros frames o CRC cobre só o L2, igual entre gravação e replay, e por isso é comparado |

Tudo o mais (CRC dos outros frames, cmd, key, tamanho, payload de BIND/LOGIN, ordem dos pedidos e dos ACKs) precisa bater. Comparar o CRC é o que deixa o replay contra uma gravação real pegar uma regressão do CRC no encoder; contra a gravação sintética, que é gerada pelo próprio `SmaFrameCodec`, a âncora independente continua sendo o `SmaFrameGoldenTest`. O modo não estrito (`strict = false`) só confere a alternância TX/RX.

**Relógio do replay.** `TestClock` parado em `recordedAt`, no fuso `timeZone`. Tempo virtual de 120 s por ação (cobre o BIND de 60 s). O histórico (`InMemorySampleStore`) começa vazio, salvo quando o teste passa o store de outro replay (`runReplay(store = …)`): é assim que o `RealFixturesStepsTest` reproduz a gravação da noite em cima do histórico da manhã.

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

Uma gravação real passa quando: nenhuma divergência, todos os TX (e `READ`) consumidos, todos os pedidos com terminal de sucesso e estado final `READY` (ou `DISCONNECTED`, se termina com `forget`).

**CRP.** O `runCrpReplay` roda o replay com o perfil Condor Reebok, o `FakeTransport` com a descoberta do P85 (`FakeTransport.crp()`, notificações em `fee3`) e a máscara `CrpTxMasker`: no frame `31` (acerto de hora, `len` `0x0A`) zera os bytes 5 a 8, os segundos que dependem do relógio do replay; o `08` e todos os outros frames, inclusive o fuso `BB 07 00` (que só depende do fuso do cabeçalho), são comparados inteiros. Todo frame da fase 1 cabe num pacote, então a máscara não guarda estado. As ações saem do `CrpReplayScenario.deduce`, pela ordem dos pedidos (frames TX remontados e leituras):

| Pedidos | Ação na sessão |
|---|---|
| primeiro `[READ 2a29] 5A/00 [B7/0E BB/16]` | `pair` (o handshake de versão) |
| o mesmo no meio do arquivo | `pair` depois de um `F8`; senão, Bluetooth desligado e religado seguido de `reconnect`, como no SMA |
| primeiro `31 BB/07` logo depois de um handshake | nenhuma: é o acerto de hora automático |
| `31 BB/07` | `setTime` |
| `READ 2a19` | `readBattery` |
| `5A/01 [READ 2a28]` | `readDeviceInfo` |
| `READ fee1 59/00 59/01 59/02 59/03 [33/01]`, seguidos de `32 [33/03 [33/04]]` | `syncHistory([STEPS, SLEEP])`; sem o `32`, `syncHistory([STEPS])` (um `refreshSteps` gera os mesmos bytes) |
| `32 [33/03 [33/04]]` sozinho | `syncHistory([SLEEP])` |
| `F8` | `disconnect(forget = true)` |
| qualquer outro | o teste falha dizendo qual pedido a sessão não sabe fazer |

As fixtures de referência não passam por aqui: a sequência delas é a do app de produção (configurações, `5D`/`5C`, `B2`, `35`, `B9`, `B6`), que o `deduce` recusa.

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
- **Passos**, regenerados em 2026-10-29 como um acumulado do dia (o que o fitGPS 0.2.1 grava, `decisoes.md`, ADR 1): crescem dentro do dia e recomeçam depois da meia-noite (o recomeço é suposição: quando o fitGPS zera ainda não foi visto, T5). Página 1 = horas cheias de 27/10 19:00 a 28/10 14:00, mode 0: 6120, 6480, 6950, 7010, 7040 (27/10, 19h a 23h) e 0, 0, 0, 12, 12, 12, 230, 1180, 2350, 2890, 3020, 3410, 4170, 4400, 4520 (28/10, 00h a 14h). Página 2, do mais antigo para o mais novo dentro da página: 28/10 13:59:59 com 4500 (substitui 13:00), 14:20 com 4810 (substitui 14:00) e 14:25 mode 32 com 4830 (início de exercício, ignorado; se entrasse, seria o último da hora 14 e o total do dia viraria 4830). Entre as páginas a ordem não é cronológica, de propósito (já era assim antes da regeneração): o 13:59:59 chega depois do 14:00:00 da página 1, ordem que a gravação real não mostrou (o fitGPS 0.2.1 entregou do mais antigo para o mais novo), para uma hora já passada ser trocada por um registro que chega depois de uma hora mais nova; o último de cada hora continua sendo o maior dela. Amostras horárias que sobram: 20, com 4500 e 4810 no lugar de 4400 e 4520.
- **Totais esperados** com o perfil do fitGPS (`DAY_CUMULATIVE`, a amostra de mais passos do dia): **27/10 = 7040** (23h) e **28/10 = 4810** (14:20), com as calorias e a distância dessas amostras. A soma das horas (`HOURLY_SUM`, regra do Core de produção) daria 33600 e 26596, 4,8 e 5,5 vezes o total certo; o `SmaSessionReplayTest` confere os dois (`EXPECTED_STEPS` e `EXPECTED_STEPS_IF_HOURLY_SUM` do gerador). Antes da regeneração os passos eram 100, 110, …, 290 com 500 e 777 na página 2, totais 600 e 4007 pela soma das horas.
- **Sono**: 27/10 23:00 START, 23:30 LIGHT, 28/10 01:00 DEEP, 03:00 LIGHT, 06:30 END; o teste só confere uma sessão com `dayKey` 2026-10-28 (exploratório).
- **Conferência independente** (refeita em 2026-10-29, depois da regeneração): um script Python avulso, que não usa nada do código Kotlin (CRC-16/ARC próprio, remontagem dos frames pelo L1, decodificação dos registros de 8 bytes e a regra "só mode 0, hora cheia local, vale o último da hora"), leu o arquivo e achou 49 pacotes, 32 frames (16 de dados com CRC certo e 16 ACKs), 23 registros de passos, 20 amostras horárias iguais às da lista acima e os totais 7040 e 4810 pelo maior valor do dia (33600 e 26596 pela soma das horas). A regeneração só mudou os pacotes das duas respostas `05/42` (13 linhas); os frames de BIND, LOGIN e BATTERY continuam idênticos aos vetores dourados de `protocolo-sma.md`, seção 9.

### `synthetic/condorreebok-pair-sync.jsonl`

- **Gerada**, com os bytes do relógio reais: `commonTest/.../testing/SyntheticCondorReebokFixture.kt` monta os TX que o **nosso** driver manda (com o `CrpFrameCodec`, bloco do MTU 517) e copia os RX e READ de `reference/condorreebok/MOY-VS02-2.0.0/condor-app-pair-sync.jsonl` (o `atMs` de origem está em cada constante do gerador; `CrpSyntheticReplayTest.everyRxAndReadComesFromTheReference` confere que cada um existe na referência). O `CrpSessionReplayTest.committedFixtureMatchesTheGenerator` (`jvmTest`) falha se o arquivo divergir do gerador; para regenerar: `FIXTURES_REGENERATE=1 ./gradlew :protocol:jvmTest --tests '*CrpSessionReplayTest*'`.
- **Cabeçalho**: `P85`, firmware `MOY-VS02-2.0.0`, `platform` `synthetic`, MTU 517, `2026-10-31T15:43:12.209-03:00` (o `recordedAt` da referência), `America/Sao_Paulo`.
- **Conversa** (24 pacotes: 12 TX, 3 READ, 9 RX): `READ 2a29` (`MOYOUNG-V2`) → TX `5A 00` → RX `5A 00` → TX `B7 0E` → TX `BB 16 00` → RX `F9 01 01 00`; TX `31` (hora automática, com o relógio do replay parado no `recordedAt`: `6A E5 9C 10 08`) e TX `BB 07 00 D0 D5 FF FF`; `READ 2a19` = `32`; TX `5A 01` → RX `5A 01` (`MOY-VS02-2.0.0`); `READ fee1` (zeros) → `59 00` a `59 03`, cada um com a página zerada real → TX `33 01` sem resposta (a sonda do legado) → TX `32` → RX `32` vazio → RX `2a19` `32` (o aviso espontâneo de bateria).
- **Diferenças em relação ao app de produção**, todas do nosso driver: o `5A 01` sem a leitura de `2a28` (vale a resposta); o `33 01` sondado; nada de `5D`, `5C`, `B2`, `B6`, `35` e `B9`; e o acerto de hora 250 ms depois do READY (o app de produção esperou o vínculo clássico).
- **Ações deduzidas** (`CrpReplayScenario`): `pair`, `readBattery`, `readDeviceInfo`, `syncHistory([STEPS, SLEEP])`.
- **Replay**: `24 packets, TX matched 12/12, READ matched 3/3, final state READY` nos quatro targets (`CrpSyntheticReplayTest.pairBatteryFirmwareSyncReplaysStrictly`), com bateria 50 %, firmware `MOY-VS02-2.0.0`, `bleVersion` `MOYOUNG-V2`, nenhum dia de passos (relógio sem uso), nenhuma noite e o `onBattery(-1)` da notificação final. O `strictReplayCatchesAChangedTx` confere que um TX alterado derruba o replay. Com o relógio do replay no `recordedAt`, o `31` bate inteiro; o `shiftedReplayClockIsMaskedOnlyInTheTimeFrame` reproduz a mesma gravação com o `recordedAt` 7 s adiante: o `31` sai com outros segundos (`…6AE59C17 08` contra `…6AE59C10 08`), casa com o `CrpTxMasker` e, sem a máscara, é a única divergência.

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
- **Passos**: os registros `mode 0` são todos da hora 08 (0, 14, 14, 14, 56, 56, 56; `atMs` 17228 a 1032178), então a gravação vira uma amostra só, 08:00 com 56 passos, e o dia 2026-10-29 dá **56** com qualquer regra (`RealFixturesStepsTest.morningRecordingGivesTheDayTotalOnTheWatchScreen`).
- **Replay**: `850 packets, TX matched 167/167, final state READY`, todos os pedidos com terminal de sucesso, nos quatro targets (`jvmTest`, `testDebugUnitTest`, `testReleaseUnitTest`, `iosSimulatorArm64Test`; seção 4.1). Em tempo real, no dispatcher de produção, `TX matched 167/167, final state READY` em cerca de 6,1 s, nos mesmos quatro (seção 4.2). O que a gravação provou sobre o relógio está em `protocolo-sma.md` (seções 1, 4, 7 e a tabela H1–H9 da seção 8), `sma-dados.md` (seção 6) e `decisoes.md` (ADRs 4, 5, 6 e 18).

### `real/fitgps/0.2.1/pair-commands-sync-bt-reconnect-disconnect-reconnect.jsonl`

- **Origem**: o export `frames-20261029-214119.jsonl` do app de exemplo, copiado sem edição. Mesmo fitGPS (firmware 0.2.1) e mesmo telefone Android, 2026-10-29 à noite, já com o host Android fino sobre a `CoreBridge` do módulo `:bridge`; é a conferência no aparelho dessa mudança. O log nativo (`logs-20261029-214119.jsonl`) fica fora do git porque tem o MAC; os documentos citam o `ts` dele.
- **Conferência de MAC**: o MAC do log não aparece no arquivo em nenhuma das quatro formas (com dois-pontos, contínuo, com os bytes invertidos e com hífen).
- **Cabeçalho**: `Mormaii_fitGPS`, firmware `0.2.1`, `platform` `android`, MTU 23, `2026-10-29T21:37:08.691-03:00` (com milissegundos: é o instante do primeiro pacote), `America/Sao_Paulo`.
- **Conteúdo**: 66 pacotes (32 TX, 34 RX), todos de no máximo 20 bytes, só em `6e400002`/`6e400003`; 24 frames de dados (14 TX, 10 RX), nenhum CRC errado, nenhum NACK. O seq do relógio vai de 91 a 100 e continua o da gravação da manhã, que terminou em 78: houve 12 frames de outras sessões no meio, não gravados.
- **Conversa**: conexão 1 (`atMs` 0 a 39035) com BIND (o `03/02` chegou em `atMs` 1899) + LOGIN `ANDROID_FF` e o acerto de hora automático, `readDeviceInfo` duas vezes, `readBattery` (`0x37`, 55 %), `setTime`, Refresh steps e um Sync completo cujo `05/46` voltou vazio (`atMs` 39035, nenhum registro de sono); Bluetooth desligado por cerca de 19 s (log, ts 1793320703419 a 1793320722176) sem nenhum `onDisconnected` e reconexão sozinha só com LOGIN (`atMs` 101052); Disconnect (ts 1793320739940), 71 s sem reconexão, e Reconnect last só com LOGIN (`atMs` 187070). Depois do último pacote, o Sono do app respondeu `NO_DATA` (ts 1793320858513). O Forget e o novo pareamento do roteiro não estão nesta gravação.
- **Passos**: as duas leituras de `05/41` trouxeram só o registro ao vivo, `mode 0` com 56 passos às 21:37:37 e 21:37:47 (`atMs` 29133 e 38440): uma amostra, 21:00 com 56, e o dia 2026-10-29 dá **56**, o que a tela do relógio mostrava (`RealFixturesStepsTest.eveningRecordingGivesTheDayTotalOnTheWatchScreen`). Foi a gravação que decidiu o H3: com o relógio parado desde a manhã, o valor de 56 é o acumulado do dia (`decisoes.md`, ADR 1). Com o histórico da manhã no mesmo store, o dia continua 56 com o perfil do fitGPS e daria 112 pela soma das horas (`bothRecordingsInOneHistoryCountTheDayAccumulatorOnce`, `hourlySumOfTheSameSamplesDoublesTheDay`).
- **Replay**: `66 packets, TX matched 32/32, final state READY` (seção 4.1). O que ela trouxe de novo está anotado em `pendencias.md` (blocos 1, 2 e 4 e itens técnicos 1 e 2), na ADR 1 e na linha H3 de `protocolo-sma.md`, seção 8.

### `real/lifeultragps/1.1.1/pair-commands.jsonl`

- **Reconstruída do log de bytes da SDK iDO, não gravada do rádio.** O app de exemplo ainda não gravou o Life Ultra GPS; a fixture junta, na ordem em que a `WatchSession` os faz, os pacotes de 8 trocas essenciais do log da SDK iDO 2.69.38 dentro do app Mormaii de produção com o mesmo relógio (`20261029.log`, 2026-10-29, não versionado porque tem o MAC). Linhas usadas: `02 01` (TX 88, RX 95), `04 01` (TX 144, RX 146), `04 05` (TX 154, RX 158), `03 01` (TX 5360, RX 5362), `02 01` + `02 eb` (TX 5745, RX 5747, TX 5774, RX 5776), `02 a0` (TX 5833, RX 5835) e `02 01` (TX 6222, RX 6225). Nenhuma outra troca entrou (em especial, nunca a `02 04`, cuja resposta tem o MAC). Gerada por um script avulso que confere a direção e o `cmd/key` de cada linha e procura MAC nos bytes; o arquivo não tem MAC em nenhuma forma.
- **O que ela não prova**: o GATT de verdade (tipo de escrita, CCCD, MTU, characteristic de cada notificação). O log da SDK não diz de qual characteristic veio cada RX; a fixture usa `00000af7` para todos e `00000af6` para os TX. O tempo entre trocas é o do log (a sessão faz o acerto de hora 1 s depois do READY; a SDK fez 25 s depois, entre outras trocas que não entraram).
- **Cabeçalho**: `Life Ultra GPS`, firmware `1.1.1`, `platform` `android`, MTU 23, `2026-10-29T22:08:21.941-03:00` (o instante do primeiro `02 01`), `America/Sao_Paulo`.
- **Conteúdo**: 16 pacotes (8 TX, 8 RX), todos de no máximo 20 bytes; as respostas com o tamanho que o relógio mandou (12 a 20 bytes).
- **Conversa**, com as ações que o `IdoReplayScenario` deduz: `pair` (informação básica, bind, a resposta ao desafio gravado e a confirmação, e o acerto de hora automático), `readDeviceInfo`, `syncHistory([STEPS])` e `readBattery`.
- **Replay**: `16 packets, TX matched 8/8, final state READY` nos quatro targets, com o `03/01` comparado sem data, hora e dia da semana (o relógio do replay fica parado no `recordedAt`) mas com o fuso (`dc 05`). O `IdoRealFixturesReplayTest` confere também os valores da tela: firmware `1.1.1`, bateria 14 %, 26 passos, 13 kcal e 25 m no dia (as 13 kcal são as totais do `02/A0`, com a de repouso; a tela mostrava 0, a de atividade: `protocolo-ido.md`, seção 3.3).

### `real/lifeultragps/1.1.1/pair-info-time-steps-sync.jsonl`

- **Origem**: a primeira gravação do Life Ultra GPS pelo nosso driver, sem nenhuma SDK de fornecedor: o export `frames-20261030-001459.jsonl` do app de exemplo, copiado sem edição. Mesmo relógio (firmware 1.1.1) e mesmo telefone Android, 2026-10-30 de madrugada. O log nativo (`logs-20261030-001459.jsonl`) fica fora do git porque tem o MAC; os documentos citam o `ts` dele.
- **Conferência de MAC**: nenhum dos MACs do log aparece no arquivo, em nenhuma das quatro formas; não há `02 04`.
- **Cabeçalho**: `Life Ultra GPS`, firmware `1.1.1`, `platform` `android`, MTU 23, `2026-10-30T00:13:24.524-03:00`, `America/Sao_Paulo`.
- **Conteúdo**: 24 pacotes (12 TX, 12 RX), todos de no máximo 20 bytes, TX em `0af6` e RX em `0af7`.
- **Conversa**: `pair` com informação básica (bateria 18 %), bind e um desafio novo, diferente do log de produção (`atMs` 171), cuja resposta pelo XOR (`atMs` 181) o relógio aceitou depois do toque na tela (`atMs` 1672); acerto de hora automático (`atMs` 2696: 2026-10-30 00:13:27, sexta = `04` na convenção segunda = 0, fuso `dc 05`); `readDeviceInfo` duas vezes (firmware `1.1.1`); `readBattery`; `setTime`; Refresh steps (`atMs` 60891: 51 passos, 6 kcal, 54 m) e um Sync com o mesmo total. Antes desse pareamento, a primeira tentativa falhou na conexão GATT com status 133 (log, ts 1793329992384), sem nenhum pacote; a segunda, 11 s depois, conectou.
- **Replay**: `24 packets, TX matched 12/12, final state READY` (`IdoRealFixturesReplayTest`).

### `real/fitgps/0.2.1/ios-pair-info-time-steps-sleep-sync.jsonl`

- **Origem**: o export `frames-20261030-093133.jsonl` do app de exemplo em Release num iPhone, 2026-10-30, a primeira gravação pelo host iOS (`CoreBluetooth`). FitGPS com firmware 0.2.1, o mesmo das gravações de 2026-10-29. O log de texto da mesma sessão (o **Share**, `texto-*.txt`) fica fora do git: não tem MAC (no iOS o endereço é o UUID do periférico), mas traz o nome do iPhone e os aparelhos vizinhos do scan; os documentos citam o `ts` dele.
- **Cabeçalho corrigido**: o export saiu com `device` `"Life Ultra GPS"`, o bug da ADR 24 (dois `pair` com o Life Ultra GPS que falharam depois desta conversa trocaram o nome). Todos os pacotes são do fitGPS: NUS (`6e400002`/`6e400003`), frames `0xAB` com CRC certo e o firmware `0.2.1` do `02/0B`. Só o campo `device` mudou, para `Mormaii_fitGPS` (o primeiro nome BLE do perfil, o que o `CoreBridge` grava hoje), com um script avulso (`python3 -I`) que confere que as outras 52 linhas ficaram idênticas byte a byte; a versão original não foi versionada. Sem a correção, o `DeviceRegistry.identify` mandaria a gravação para o `IdoRealFixturesReplayTest`, que a recusaria no primeiro TX (`AB/00`, não `02/01`). Regravar não era opção: a página de sono desta noite já foi lida (e, se H2 valer para o sono, apagada).
- **Resto do cabeçalho**, como veio: firmware `0.2.1`, `platform` `ios`, MTU 23, `2026-10-30T09:29:21.323-03:00`, `America/Sao_Paulo`. O MTU 23 é o do link do fitGPS (`requestMtu` 247 respondido com `maximumWriteValueLength(.withoutResponse) + 3` = 23; log, ts 1793363361318), e é também o valor a que o transporte iOS volta a cada tentativa de conexão (ADR 24): aqui os dois coincidem.
- **Conferência de MAC**: o log de texto não tem nenhum endereço no formato de MAC, e os frames de dados do relógio são só `03/02`, `03/04`, `02/0B`, `02/09`, `05/42` e `05/46`, nenhum com endereço.
- **Conteúdo**: 52 pacotes (22 TX, 30 RX), todos de no máximo 20 bytes; 10 frames de dados TX (seq 1 a 10) e 8 RX (seq do relógio 104 a 111; a gravação da noite de 2026-10-29 terminou em 100), nenhum CRC errado, nenhum NACK.
- **Conversa**: `pair` com BIND (o `03/02` chegou em `atMs` 3775, 3,6 s depois do ACK do BIND em 175) + LOGIN `ANDROID_FF` e o acerto de hora automático (`atMs` 7319); `readDeviceInfo` duas vezes (`0.2.1`); `readBattery` (`0x23`, 35 %); `setTime` (`atMs` 43673); Refresh steps (`atMs` 46389, 3 registros) e um Sync completo (`05/41` em `atMs` 50541 com 1 registro e `05/45` em 50671, com uma página de 9 registros de sono, a primeira página real recente). Antes desta conversa, três `pair` com o Life Ultra GPS falharam com status 14 (log, ts 1793363257438 a 1793363346442; ADR 23). Depois do último pacote: Disconnect (ts 1793363458171), Forget com o link já fechado, que por isso não escreveu o `03/05` (ts 1793363462476), e mais dois `pair` com o Life Ultra GPS que falharam do mesmo jeito.
- **Passos**: o `05/42` da primeira leitura trouxe `mode 16` com 56 às 23:59:00 de 29/10 (o total que a tela mostrou ontem), `mode 16` com 0 às 00:00:00 e o registro ao vivo `mode 0` com 0 às 09:30:07; a segunda, só o ao vivo de 09:30:11. Só `mode 0` vira amostra: 09:00 com 0, e o dia 2026-10-30 dá **0**, o que o usuário esperava de um relógio parado (`RealFixturesStepsTest.iosRecordingAfterMidnightIgnoresYesterdaysTotalOnTheSitRecord`; `sma-dados.md`, seção 6).
- **Sono**: o relógio ficou parado na mesa a noite toda (o usuário não dormiu com ele) e mesmo assim gravou uma noite: START às 21:58:00 de 29/10, estágios de 00:09:38 a 09:30:05, um registro fora de ordem e nenhum END. A sessão produz uma `SleepSession` de 21:58:00 a 09:30:05 (`dayKey` 2026-10-30, 692 min, 632 profundo, 54 leve, 6 acordado), igual ao `SmaSleep.java` da SDK rodado à parte sobre os mesmos registros (`RealFixturesSleepTest`; os registros e a comparação em `sma-dados.md`, seção 6).
- **Replay**: `52 packets, TX matched 22/22, final state READY` nos quatro targets, com as ações `pair`, `readDeviceInfo` duas vezes, `readBattery`, `setTime`, `syncHistory([STEPS])` e `syncHistory([STEPS, SLEEP])`; o `RealFixturesSleepTest` confere também o firmware, a bateria e a noite inteira (linha `SLEEP`).

### `real/lifeultragps/1.1.1/ios-pair-info-time-steps-sync.jsonl`

- **Origem**: o export `frames-20261030-093537.jsonl` do app de exemplo em Release no mesmo iPhone, 2026-10-30, copiado sem edição: a primeira gravação do Life Ultra GPS por `CoreBluetooth`, feita depois de o usuário esquecer o relógio em Ajustes > Bluetooth do iPhone (o vínculo antigo do app de produção fazia todo `connect` falhar com status 14, ADR 23). Mesmo relógio (firmware 1.1.1) das gravações Android. O log de texto (`texto-*.txt`) fica fora do git pelo mesmo motivo da gravação acima.
- **Conferência de MAC**: não há `02/04` (a resposta que traz o MAC). Os `cmd/key` são só `02/01`, `04/01`, `04/05`, `03/01`, `02/EB` e `02/A0`. Comparadas com as respostas das duas gravações Android já conferidas, as daqui só diferem na bateria (byte 7 do `02/01`), nos totais do `02/A0` (e no byte 18, não lido) e no desafio do bind, que é outro a cada bind: as três fixtures do Life Ultra GPS têm três desafios diferentes (`atMs` 118 aqui). O log de texto não tem nenhum endereço no formato de MAC.
- **Cabeçalho**: `Life Ultra GPS`, firmware `1.1.1`, `platform` `ios`, MTU **515**, `2026-10-30T09:35:06.979-03:00`, `America/Sao_Paulo`. O 515 é o que o iOS ofereceu no `didConnect` (`maximumWriteValueLength(.withoutResponse) + 3`; log, ts 1793363706261), e o export saiu com o link ainda aberto. A sessão não usou: o perfil pede 23, o MTU não foi pedido ("MTU not requested", ts 1793363706978) e todo pacote tem no máximo 20 bytes. No replay, a sessão não pede MTU, e o valor do cabeçalho não muda nada.
- **Conteúdo**: 24 pacotes (12 TX em `0af6`, 12 RX em `0af7`), todos de no máximo 20 bytes.
- **Conversa**: `pair` com informação básica (bateria 12 %), bind com um desafio novo (`atMs` 118) respondido pelo XOR (`atMs` 119) e a confirmação na tela 1,4 s depois (`atMs` 1528); acerto de hora automático (`atMs` 2547: 2026-10-30 09:35:09, sexta = `04`, fuso `dc 05`); `readDeviceInfo` duas vezes (`1.1.1`); `readBattery`; `setTime` (`atMs` 11869); Refresh steps (`atMs` 15328: 57 passos, 7 kcal, 59 m) e um Sync com o mesmo total (`atMs` 18299). Depois do último pacote, o Sono do app respondeu `NO_DATA` (log, ts 1793363728430), como previsto (sem sono no iDO da PoC).
- **Replay**: `24 packets, TX matched 12/12, final state READY` nos quatro targets; o `IdoRealFixturesReplayTest.iosRecordingGivesTheValuesTheWatchSent` confere também firmware `1.1.1`, bateria 12 %, 57 passos, 7 kcal e 59 m no dia, as escritas sem resposta e o fuso dos dois acertos de hora. O que o iPhone mostrou do GATT está em `protocolo-ido.md`, seção 1.1.

### `reference/condorreebok/MOY-VS02-2.0.0/` (as três fixtures de referência)

- **Origem**: uma captura HCI (btsnoop) do app Condor de **produção** num Android, pareando o Condor Reebok (`P85`, firmware `MOY-VS02-2.0.0`) em 2026-10-31, horários locais de São Paulo. A captura bruta tem os endereços dos aparelhos e conexões de outro relógio (15:30-15:34) e **nunca entra no repositório**; só a conexão do P85 a partir das 15:42 foi convertida. Não reproduzem contra a nossa sessão (a sequência é a do app de produção): servem de prova do que o relógio responde, e as docs as citam por arquivo e `atMs` (`protocolo-crp.md`, seção 1; ADR 32).
- **Comando** (da raiz do repositório, com os dois arquivos btsnoop fora dele e em `python3 -I`), igual para as três, mais as opções de cada uma; `dev-xxxxxx` é o rótulo do P85 que o `--list` mostra (seção 3.3):

```
python3 -I scripts/btsnoop2jsonl.py --family crp --peer dev-xxxxxx --app "Condor (produção, Android)" \
	btsnoop_hci.log.last btsnoop_hci.log -o fixtures/reference/condorreebok/MOY-VS02-2.0.0/<arquivo>
```

| Arquivo | Opções | Conexão | `recordedAt` | Pacotes |
|---|---|---|---|---|
| `condor-app-pair-sync.jsonl` | `--conn 11 --since 15:43 --until 15:46:11` | 11, 15:43:12.209 a 15:46:10.358 (o primeiro pareamento, até o Bluetooth desligado) | `2026-10-31T15:43:12.209-03:00` | 114: 61 TX, 49 RX, 4 READ |
| `condor-app-bt-reconnect.jsonl` | `--conn 16 --since 15:46:26 --until 15:47:09` | 16, 15:46:26.727 a 15:47:08.707 (a reconexão depois de religar o Bluetooth) | `2026-10-31T15:46:26.727-03:00` | 25: 15 TX, 8 RX, 2 READ |
| `condor-app-reconnect-sync-walk.jsonl` | `--conn 17 --since 15:47:21` | 17, de 15:47:21.326 ao fim da captura (reconexão, sync e uma atividade de caminhada) | `2026-10-31T15:47:21.326-03:00` | 244: 150 TX, 89 RX, 5 READ |

- **Cabeçalho**: `device` `P85` (o 2A00 lido), `firmware` `MOY-VS02-2.0.0` (o 2A28 lido), `platform` `android-hci`, `mtu` 517, `mtuInitiator` `"peripheral"` (o relógio abriu a troca), `gatt` com os nove serviços e as propriedades de cada characteristic (a tabela de `protocolo-crp.md`, seção 2), `cccdWrites` `fee3`, `2a37`, `fee1` (nesta ordem, nas três) e `redactions`.
- **Redação** (o `redactions` de cada uma): frames `0x41`, `0x42`, `0x43` e `0xB5` descartados inteiros, 5 no TX em `condor-app-pair-sync.jsonl`, 2 em `condor-app-bt-reconnect.jsonl` e 11 em `condor-app-reconnect-sync-walk.jsonl`, nenhum no RX; o payload do `0x12` trocado por `B2 4B 18 00` em 2, 0 e 1 frames. Os `0x42` (previsão do tempo: código do clima e temperaturas mínima e máxima por dia, `sdk:f/a2.java:23-45`, sem nome de localização, mas a previsão do dia cruzada com dados públicos indica a cidade) ficaram na primeira versão e saíram na revisão de privacidade, quando as três foram geradas de novo com o padrão atual: só as três linhas `0x42` (`pair-sync@79960`, `walk@25410`, `walk@28370`) e o texto do `redactions` mudaram.
- **Conferência**: um script avulso (`python3 -I`) e o `CrpReferenceFixtureTest.redactionRemovedTextFramesAndFakedTheProfile` conferiram que não há frame `0x41`, `0x42`, `0x43` nem `0xB5` e que todo `0x12` é `B2 4B 18 00`; nenhum endereço real da captura aparece em nenhuma forma nos arquivos (a única coincidência foi o endereço `00:00:…` de uma conexão que falhou, que não identifica ninguém e que a trava do script pula). Na geração atual, a trava conferiu os 59 endereços distintos que a captura mostra (telefone, pares LE e clássicos, SSP, advertising reports).
- **O que cada uma prova** (`protocolo-crp.md`): o handshake de versão (`2a29` e `5A 00`, nas três, com `5A 00` em `b2 = 0x10` em `walk@1196`), a hora e o fuso (`pair-sync@6821`, `@7047`), o `F9` do clássico (`pair-sync@3172`, `@7070`; `bt-reconnect@924`, `@1379`), o firmware (`pair-sync@98758`, `@98835`), a bateria lida e notificada sem CCCD (`pair-sync@105394`, `@48921`), as páginas `59` zeradas de um relógio sem uso, o `32` vazio, os `5D`/`5C`/`B6` sem resposta, o `postLoginDelayMs` (`bt-reconnect@2046` → `@2295`) e os passos ao vivo da caminhada, de 16/13/1 a 77/63/4 (`walk@106148` a `@136148`), com a frequência cardíaca em `2a37` (`walk@116988`). O `CrpReferenceFixtureTest` decodifica as três (seção 2).
