# Fixtures: gravações de tráfego e replay

Uma *fixture* é uma gravação do tráfego GATT entre o telefone e o relógio, pacote a pacote, num arquivo `.jsonl`. Ela serve para rodar a sessão inteira (`WatchSession` → `SmaDriver` → codec) contra o relógio "gravado", sem relógio de verdade: é o critério A2a (sintética) e A2b (gravada do fitGPS) do plano.

## 1. Formato

Um objeto JSON por linha, UTF-8. A primeira linha é o cabeçalho; cada linha seguinte é **um pacote GATT cru** (um fragmento, nunca um frame remontado). O formatador é `log/FrameJsonl` (o mesmo que o host usa em produção) e o leitor é `jvmTest/.../testing/RecordingLoader`.

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
| `recordedAt` | string | início da gravação, ISO-8601 **com offset numérico**; o replay usa como hora do `WatchClock` |
| `timeZone` | string | fuso IANA do telefone durante a gravação; o replay usa como fuso do `WatchClock` |

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
- Todo `.jsonl` em `fixtures/real/` (qualquer profundidade) vira teste automaticamente (`RealFixturesReplayTest`); não é preciso escrever código.

## 3. Como gravar

### 3.1 Pelo app de exemplo (FrameRecorder)

O host recebe cada pacote em `WatchLogger.frame` e pode gravá-lo com `FrameJsonl.line`; o app de exemplo do `react-native-technos-core-next` faz isso no `RingLogger`/`FrameRecorder` e grava o arquivo pela aba **Log → Export** (`frames-<carimbo>.jsonl` em `Android/data/<app>/files/corenext/`, copiado com `adb pull`; o **Share** manda só as últimas entradas do log como texto). Implementação e detalhes no Repo 2.

Para a gravação reproduzir sem ajustes:
1. Comece a gravar antes de tocar no relógio na lista do scan (o cabeçalho precisa da hora e do fuso do início).
2. Depois que o estado chegar em `connected`, **espere 3 s** antes do primeiro comando: é o `postLoginDelayMs`, depois do qual a sessão manda o acerto de hora automático. O replay assume que ele vem logo depois do LOGIN.
3. Faça um comando de cada vez, esperando o resultado na tela.
4. Exporte e copie para `fixtures/real/fitgps/<firmware>/<cenário>.jsonl`.

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

O `ReplayDriver` (`commonTest/.../testing/ReplayDriver.kt`) liga a gravação ao `FakeTransport`:

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
| qualquer outro | o teste falha dizendo qual `cmd/key` a sessão não sabe pedir |

Uma gravação real passa quando: nenhuma divergência, todos os TX consumidos, todos os pedidos com terminal de sucesso e estado final `READY` (ou `DISCONNECTED`, se termina com `forget`).

## 5. Procedência das fixtures

### `synthetic/fitgps-pair-sync.jsonl`

- **Gerada**, não gravada: `jvmTest/.../testing/SyntheticFitGpsFixture.kt` monta cada frame com `SmaFrameCodec`, `SmaFragmenter` e os payload codecs, sem rodar a sessão. O teste `SmaSessionReplayTest.committedFixtureMatchesTheGenerator` falha se o arquivo commitado divergir do gerador; para regenerar: `FIXTURES_REGENERATE=1 ./gradlew :protocol:jvmTest`.
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

### `real/`

Vazia até a primeira gravação com o fitGPS (etapa E3/E4 do plano).
