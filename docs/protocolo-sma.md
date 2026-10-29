# Protocolo SMA (Mormaii fitGPS)

Este documento descreve o protocolo BLE dos relógios com firmware SMA (bestmafen), como o Mormaii fitGPS, com detalhe suficiente para reimplementar o codec sem abrir as SDKs. Cada afirmação sobre o relógio traz a fonte entre parênteses.

**Fontes citadas**

| Abreviação | Arquivo |
|---|---|
| `SmaManager.java`, `SmaMessenger.java`, `SmaBleHelper.java`, `SmaBleUtils.java`, `SmaTime.java`, `SmaTimezone.java`, `SmaUserInfo.java`, `SmaSleep.java`, `SmaSport.java`, `EaseConnector.java` | SDK Android decompilada (`com.bestmafen.smablelib` / `easeblelib`) |
| `SmaBLE.m`, `SmaBusinessTool.m` | SDK iOS (`SmaTechnosIosSdk/BLEAPI`) |
| `SMAService.java` | Core atual, Android (`TechnosWareCore/android/.../com/sma/bluetooth/SMAService.java`) |
| `SMAService.m` | Core atual, iOS (`TechnosWareCore/ios/smaBluetooth/SMAService.m`) |

**Código correspondente**: `protocol/src/commonMain/kotlin/br/com/grupotechnos/watch/protocol/sma/` (`codec/`, `commands/`, `data/`, `SmaDriver.kt`). Quando um byte mudar aqui, o vetor dourado em `SmaFrameGoldenTest` muda no mesmo PR, e vice-versa.

---

## 1. GATT

**Serviços candidatos, em ordem de prioridade** (`Profiles.MORMAII_FIT_GPS.gattCandidates`):

| Prioridade | Serviço | Write | Notify | Fonte |
|---|---|---|---|---|
| 1 | `0000ff60-0000-1000-8000-00805f9b34fb` ("ROUND") | `0000ff61-…` | `0000ff62-…` | `SmaManager.selectUUID`, `SmaManager.java:1577-1591`; constantes em `:63-68` |
| 2 | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` (Nordic UART, NUS) | `6e400002-…` | `6e400003-…` | mesmas linhas; o Core iOS só conhece este (`SMAService.m:1056-1061`) |

- A SDK Android escolhe FF60 se o serviço existir e cai para NUS caso contrário. O Core iOS, que funciona em produção com o fitGPS, só casa NUS; logo o fitGPS **expõe NUS**. Se ele também expõe FF60 ainda não se sabe (ver a seção 8, hipóteses).
- **CCCD**: notificação ligada escrevendo `01 00` no descritor `0x2902` da characteristic notify (`SmaMessenger.java:52-79`, descritor em `:62`). O iOS usa `setNotifyValue:YES` (`SMAService.m:1060-1061`).
- **MTU**: a SDK Android pede 512 logo depois de ligar as notificações (`SmaManager.java:202-204`) e passa a cortar em `mtu - 3` (`:305`); o padrão antes disso é 20 (`SmaMessenger.java:24`). O iOS não negocia MTU e corta sempre em 20 (`SmaBLE.m:579-605`). Esta biblioteca pede `Quirks.requestMtu = 247` e corta em `Quirks.maxFragment ?: (mtu - 3)`, nunca abaixo de 20 (`SmaFragmenter`).
- **Tipo de escrita**: o iOS escreve todo frame de protocolo com resposta (`CBCharacteristicWriteWithResponse`, `SmaBLE.m:3336`; ACK/NACK em `SmaBusinessTool.m:299, 320`). A Android não define o tipo (`SmaMessenger.java:118-119`), ficando o padrão da characteristic. O driver usa sempre `WriteType.WITH_RESPONSE`.
- **Fragmentação na ida**: o frame é cortado em pedaços de tamanho fixo, na ordem, **sem cabeçalho por fragmento**. O primeiro pedaço começa com `0xAB` e os outros são bytes crus (`SmaMessenger.java:222-250`). Cada pedaço é uma escrita GATT.
- **Fragmentação na volta**: o firmware manda o L1 de 8 bytes numa notificação própria e o corpo nas seguintes. A SDK Android só funciona se for assim (`SmaManager.java:506-528`).

## 2. Frame

Todo frame de dados tem um cabeçalho L1 de 8 bytes e um corpo L2 de 5 + N bytes. Campos multibyte em **big-endian**. Montagem em `SmaBleHelper.getBytes` (`SmaBleHelper.java:8-30`) e `SmaBusinessTool getSpliceCmd` (`SmaBusinessTool.m:108-134`).

**L1**

| Offset | Tamanho | Campo | Valor |
|---|---|---|---|
| 0 | 1 | magic | `0xAB` |
| 1 | 1 | flags | `0x00` dados; `0x10` ACK; `0x30` NACK (bit 4 = controle, bit 5 = erro) |
| 2 | 2 | `len(L2)` | uint16 = N + 5 |
| 4 | 2 | CRC | CRC-16/ARC do L2 inteiro (seção 3) |
| 6 | 2 | seq | sequence id uint16 (seção 4) |

**L2**

| Offset | Tamanho | Campo | Valor |
|---|---|---|---|
| 8 | 1 | cmd | família: `0x01` UPDATE, `0x02` SET, `0x03` CONNECT, `0x04` NOTICE, `0x05` DATA, `0x07` CONTROL (`SmaManager.java:1617-1625`) |
| 9 | 1 | versão | sempre `0x00` |
| 10 | 1 | key | comando; a resposta usa `key + 1` |
| 11 | 2 | N | uint16, tamanho do value |
| 13 | N | value | payload |

Tamanho total = `len(L2) + 8`. Um GET vai com N = 0, ou seja, 13 bytes.

**ACK / NACK** são só o L1, com `len = 0` e `CRC = 0`: `AB 10 00 00 00 00 SEQ_HI SEQ_LO` e `AB 30 …` (`SmaManager.java:1029-1037`; `SmaBusinessTool.m:286-321`).

**Exemplo anotado** (SET_TIME de 2026-10-28 14:30:00, seq 3):

```
AB          magic
00          flags: dados
00 09       len(L2) = 9 = 5 + 4
CD 2F       CRC-16/ARC de "02 00 01 00 04 6A B8 E7 80"
00 03       seq = 3
02          cmd  = SET
00          versão
01          key  = SET_TIME
00 04       N = 4
6A B8 E7 80 value: hora empacotada (seção 7)
```

**Limite**: o codec rejeita frames acima de 2048 bytes (`SmaFrameCodec.MAX_FRAME_SIZE`), que é o maior frame conhecido, o stream EPO `05/60` (2033 + 2 + 13; `SmaManager.java:1172-1234`). Isso também impede que um tamanho corrompido faça o reassembler esperar 64 KiB.

**Erros tipados de decodificação** (`SmaDecodeError`): `BAD_MAGIC`, `TRUNCATED`, `OVERSIZED`, `BAD_LENGTH` (bytes sobrando, L2 < 5 ou N ≠ len − 5) e `CRC_MISMATCH`.

## 3. CRC-16/ARC

- Parâmetros: polinômio refletido `0xA001` (`0x8005` normal), valor inicial `0x0000`, entrada e saída refletidas, sem XOR final.
- Laço: `crc = (crc >> 8) ^ T[(crc ^ byte) & 0xFF]` (`SmaBleHelper.java:49-58`; `SmaBusinessTool.m:50-62`).
- Tabela: começa em `0x0000, 0xC0C1, 0xC181, 0x0140, 0xC301, 0x03C0, 0x0280, 0xC241` e termina em `…, 0x4100, 0x81C1, 0x8081, 0x4040` (`SmaBleHelper.java:6`, em decimal; `SmaBusinessTool.m:14-48`). `Crc16Arc` gera a tabela a partir do polinômio e o teste confere as duas pontas.
- Bytes cobertos: do offset 8 até o fim (o L2 inteiro), na quantidade declarada em `len(L2)` (`SmaBleHelper.cmdCRC`, `SmaBleHelper.java:42-47`; `SmaBusinessTool getCRC16`, `SmaBusinessTool.m:252-256`). O seq **não** entra no CRC, por isso dois frames iguais com seqs diferentes têm o mesmo CRC.
- Gravado em big-endian nos bytes 4 (MSB) e 5 (LSB).

| Entrada | CRC | Origem |
|---|---|---|
| `"123456789"` (ASCII) | `0xBB3D` | valor de verificação padrão do CRC-16/ARC |
| `01 00 01 00 00` | `0x006C` | o frame OTA fixo do iOS grava `00 6C` à mão (`SmaBusinessTool.m:204-222`) |

## 4. Sequence, ACK/NACK e remontagem

**Sequence id**

- O telefone numera seus frames a partir de 1 e incrementa a cada frame (`SmaBleHelper.java:5, 18-19, 27`). Só os 16 bits baixos vão no frame, então depois de `0xFFFF` vem `0x0000` (`SmaSequence`). O iOS começa em 0 (`SmaBusinessTool.m:12`); nenhum dos dois valida o seq recebido.
- As retentativas desta biblioteca (SET e handshake, seção 5) usam um seq novo. Não é paridade com as SDKs: o iOS grava o seq nos bytes ao montar o comando (`SmaBusinessTool.m:118-126`), guarda o `NSData` na fila (`SmaBLE.m:3349-3364`) e, no primeiro timeout de 8 s, reenvia os mesmos bytes, com o mesmo seq (`SmaBLE.m:3302-3337`); a Android não reenvia, só segue depois de 4 s (`SmaMessenger.java:36-96`). Não se sabe se o firmware descarta repetições pelo seq (H9, seção 8).
- O relógio tem o próprio contador; o telefone só o copia no ACK.

**ACK/NACK nos dois sentidos**

- Para cada frame de dados que o telefone envia, o relógio responde com um ACK de 8 bytes. A SDK Android reconhece o ACK quando a notificação tem 8 bytes, começa com `0xAB` e tem flags `0x10` ou `0x30` (`SmaManager.java:508-516`); a iOS, quando `[0] == 0xAB` e `[1] == 0x10` (`SmaBusinessTool.m:267-279`), e passa o seq dos dois últimos bytes adiante (`SmaBLE.m:163-166`). Nenhuma confere o seq com o do pedido. O driver também não: o seq do ACK é só registrado no log quando difere.
- Para cada frame de dados completo que o relógio envia, o telefone responde ACK se o CRC bater. A Android não envia NACK (`SmaManager.java:535-542`); a iOS envia NACK quando o CRC falha (`SmaBLE.m:227, 611-620`). O driver segue o iOS: ACK com CRC certo, NACK com CRC errado.
- **O ACK sai antes de entregar o frame** a quem pediu. As duas SDKs fazem isso e, se o cursor do histórico avança quando o relógio recebe o ACK (seção 8), entregar antes e falhar depois perderia a página.

**Regras do reassembler** (`SmaReassembler`, aplicadas a cada notificação, nesta ordem)

1. **ACK/NACK**: notificação de exatamente 8 bytes, `[0] == 0xAB` e `[1] & 0x10 != 0` é controle, qualquer que seja o estado do buffer, e nunca entra no corpo de um frame. *Por quê*: a Android só reconhece ACK com o buffer vazio (`SmaManager.java:508`); um ACK chegando no meio de um frame seria concatenado ao corpo e o corromperia. Como o menor frame de dados tem 13 bytes, um pacote de 8 bytes com o bit `0x10` não pode ser início de dados.
2. **Intervalo entre fragmentos**: com frame em andamento, se a última notificação dele chegou há mais de `Quirks.fragmentGapTimeoutMs` (8000 ms), o pedaço é descartado (`GAP_TIMEOUT`) e a notificação atual é tratada como início. *Por quê*: é o timeout de 8 s do iOS (`SmaBLE.m:199-203`, `receiveTimeOut` em `:3282-3288`); a Android não tem e fica presa até reconectar.
3. **`AB` solto**: notificação de 1 byte `0xAB` **sem** frame em andamento é ignorada, como no iOS (`SmaBLE.m:134-136`). **Com** frame em andamento ela é corpo, como na Android (`SmaManager.java:519-520`). *Por quê*: o iOS descarta sempre, o que travaria um frame legítimo cujo último byte é `0xAB`; tratá-lo como corpo, no pior caso, gera CRC errado, que vira NACK e é recuperável.
4. **Início de frame**: bytes antes do primeiro `0xAB` são descartados (`GARBAGE`). O esperado é o L1 numa notificação de 8 bytes e o corpo nas seguintes. Cabeçalho e corpo juntos, e até dois frames na mesma notificação, são aceitos **como tolerância** (o iOS aceita, `SmaBLE.m:177-194`), não como comportamento esperado do firmware.
5. **Tamanho**: total = `len(L2) + 8`; acima de 2048 o resto da notificação é descartado (`OVERSIZED`).
6. **Frame completo**: decodificado; CRC certo → entrega; CRC errado ou tamanhos incoerentes → `Corrupted` com o seq, e o driver escreve NACK.

O reassembler não tem relógio próprio: recebe `nowMs` a cada chamada, e o driver passa o tempo monotônico (nos testes, o tempo virtual do `TestCoroutineScheduler`).

**Exclusão mútua na escrita**: o driver escreve todos os fragmentos de um frame dentro de um `Mutex`, e os ACK/NACK que a recepção escreve passam pelo mesmo mutex. Um ACK nunca fica entre dois fragmentos de outro frame: o relógio remonta pelo tamanho do L1 e um ACK no meio seria lido como corpo. `SmaDriverTest.ackNeverInterleavesWithAMultiFragmentFrame` prova isso.

## 5. Handshake

```mermaid
sequenceDiagram
	participant S as Sessão (WatchSession)
	participant H as Host (Transport)
	participant W as Relógio
	S->>H: connect(address)
	H-->>S: onConnected
	S->>H: discoverServices
	H-->>S: onServicesDiscovered (escolhe FF60 ou NUS)
	S->>H: enableNotifications(notify) — CCCD 01 00
	S->>H: requestMtu(247)
	H-->>S: onMtuChanged(mtu)
	Note over S,W: driver.handshake(FIRST_PAIR)
	S->>W: 03/01 BIND [32 bytes]
	W-->>S: ACK
	Note over W: pode pedir confirmação na tela (até bindTimeoutMs)
	W-->>S: 03/02 BIND_BACK [00]
	S->>W: ACK
	S->>W: 03/03 LOGIN [32 bytes]
	W-->>S: ACK
	W-->>S: 03/04 LOGIN_BACK [00]
	S->>W: ACK
	Note over S: READY na hora; o acerto de hora automático espera postLoginDelayMs (3 s)
	S->>W: 02/01 SET_TIME [4 bytes]
	W-->>S: ACK
	Note over S: enableDataChangeNotices(): 02/6F não é enviado (seção 6)
```

- **FIRST_PAIR**: BIND e depois LOGIN. **RECONNECT**: só LOGIN, como a Android quando já há endereço salvo (`SmaManager.java:302-316`, `isBond` em `:1064-1066`). A leitura de `2A23` que a Android faz antes do BIND (`SmaManager.java:438`) serve ao Bluetooth clássico e não é feita.
- **Payload de BIND e LOGIN** (32 bytes, `SmaBindPayload`; `Quirks.bindPayload`):
  - `ANDROID_FF` (padrão): `FF` + 31 × `00` (`SmaManager.java:436-449`).
  - `USER_ID`: `bindUserId` uint32 BE + 28 × `00` (`SmaBLE.m:559-570`). O Core iOS usa userId 1 (`SMAService.m:1093`): `00 00 00 01` + 28 × `00`.
- **Critério de sucesso**:
  - BIND: resposta `03/02` com `value[0] == 0` (`SmaManager.java:726-735`; `SmaBLE.m:285-293`). Value vazio é recusa (as duas SDKs leriam um byte que não existe). Recusa → `HANDSHAKE_FAILED`.
  - LOGIN: resposta `03/04` com value vazio **ou** `value[0] == 0`. A Android exige `value[0] == 0` (`SmaManager.java:736-746`); a iOS aceita qualquer resposta com CRC certo (`SmaBLE.m:294-301`).
- **Timeouts**: ACK de cada comando em `commandTimeoutMs` (4000). Resposta do BIND em `bindTimeoutMs` (60000), porque o relógio pode pedir confirmação na tela e o código das SDKs não tem passo explícito para isso; a sessão não reconecta durante o handshake (o loop de 18 s do `EaseConnector`, `EaseConnector.java:25, 26-44`, derrubaria uma confirmação mais lenta). Resposta do LOGIN em `commandTimeoutMs`.
- **Retentativas**: BIND e LOGIN têm uma retentativa com seq novo (seção 4) em falta de ACK ou NACK. A falta de resposta do LOGIN também tem retentativa; a do BIND não, porque a espera já é de 60 s.
- **Fallback no primeiro pareamento** (`FIRST_PAIR`, só a partir de um `pair` pedido pelo usuário): se o LOGIN for recusado (`value[0] != 0`) com `ANDROID_FF`, o driver registra no log e faz **uma vez** o handshake FIRST_PAIR completo com `USER_ID` (BIND + LOGIN com o userId), cobrindo o caso de o relógio ter sido vinculado pelo app iOS. Recusa de novo → `HANDSHAKE_FAILED`. O BIND completo, e não só um LOGIN com userId, é porque o LOGIN provavelmente precisa repetir o payload do BIND.
- **Reconexão** (`RECONNECT`, que a sessão faz sozinha a cada queda): **nunca** há BIND, porque ele pode pedir confirmação na tela do relógio e a reconexão roda em segundo plano. LOGIN recusado com o formato do perfil → um LOGIN com o outro formato (`ANDROID_FF` ↔ `USER_ID`), que é o que reencontra um relógio vinculado pelo fallback acima sem guardar o formato entre conexões. Recusado de novo → `HANDSHAKE_FAILED`; a sessão vai para `DISCONNECTED`, falha o pedido de reconexão com esse código e não tenta mais sozinha (`decisoes.md`, ADR 11). Só um `pair` refaz o vínculo.
- **Antes do LOGIN** o driver recusa qualquer `cmd` diferente de `0x03` com `NOT_READY`, sem escrever nada. É a mesma regra de `SmaManager.write` (`SmaManager.java:1245`).
- **Unbind**: `03/05` com value `[00]` (`SmaManager.java:415-417`), só ACK. Depois dele o driver volta ao estado "sem LOGIN". O iOS monta o mesmo comando sem value e envia 20 bytes de um buffer de 14, lendo lixo de memória (`SmaBLE.m:458-467`); seguimos a Android.

## 6. Catálogo de comandos

Status: **enviado** = implementado e usado pelo driver; **aviso** = recebido e convertido em `DriverNotice`; **só codec** = codec e teste, não enviado; **não enviado** = catalogado, payload não confirmado; **fora** = fora do escopo da PoC.

| cmd/key | Nome | Direção | Payload | Resposta | Status | Fonte |
|---|---|---|---|---|---|---|
| `03/01` | BIND | tel → relógio | 32 bytes (seção 5) | `03/02`, `value[0]==0` ok | enviado | `SmaManager.java:436-442, 725-735`; `SmaBLE.m:448-455, 559-607, 285-293` |
| `03/03` | LOGIN | tel → relógio | 32 bytes (seção 5) | `03/04`, vazio ou `value[0]==0` ok | enviado | `SmaManager.java:444-449, 736-746`; `SmaBLE.m:470-476, 294-301` |
| `03/05` | UNBIND | tel → relógio | `[00]` | só ACK | enviado | `SmaManager.java:401-434`; `SmaBLE.m:458-467` |
| `02/01` | SET_TIME | tel → relógio | 4 bytes (seção 7) | só ACK | enviado | `SmaTime.java:26-33`; `SmaBLE.m:524-556, 2234-2255` |
| `02/08` | BATTERY | tel → relógio | vazio | `02/09`, `value[0]` = % | enviado | `SmaManager.java:652-658`; `SmaBLE.m:331-336, 1986-1995` |
| `02/0A` | VERSION | tel → relógio | vazio | `02/0B`, firmware `[0..2]`, BLE `[3..5]` | enviado | `SmaManager.java:641-651`; `SmaBLE.m:325-330, 1998-2007` |
| `05/41` | SPORT | tel → relógio | vazio | `05/42`, até 20 × 8 bytes | enviado | `SmaManager.java:783-814, 1444-1450`; `SmaBLE.m:1758-1768, 2672-2716` |
| `05/45` | SLEEP | tel → relógio | vazio | `05/46`, até 20 × 7 bytes | enviado (exploratório) | `SmaManager.java:815-840`; `SmaBLE.m:1771-1780, 2899-2945` |
| `02/70` | SET_SYNC_DATA_BACK (DATA_CHANGED) | relógio → tel | `[flag]` | ACK do telefone | aviso → `DataChanged` | `SmaManager.java:709-715, 1731` |
| `04/62` | BLE_CONNECTION | relógio → tel | `[estado]`, `0` = conectado | ACK do telefone | aviso → `LinkReported` | `SmaManager.java:755-760, 1710`; uso em `SMAService.java:254-262` |
| `02/68` | TIMEZONE | tel → relógio | 1 byte com sinal (seção 7) | só ACK | só codec | `SmaTimezone.java:6-21`; `SmaBLE.m:3601-3620` |
| `02/10` | USER_INFO | tel → relógio | 4 bytes (seção 7) | só ACK | só codec | `SmaUserInfo.java:9-16`; `SmaBLE.m:495-521, 2209-2229` |
| `02/6F` | SET_SYNC_SWITCH | tel → relógio | **desconhecido** | — | não enviado | só a constante, `SmaManager.java:1730` |
| `02/06` → `02/07` | GET_TIME | tel → relógio | vazio | hora no layout de SET_TIME | fora | `SmaBLE.m:1975-1984, 321-324` |
| `02/0C` → `02/0D` | MAC | tel → relógio | vazio | 6 bytes | fora | `SmaBLE.m:2012, 337-342` |
| `02/23` | PHONE_SYSTEM | tel → relógio | `[02]` | só ACK | fora | `SmaBLE.m:1540-1551` |
| `03/06` | LOGOUT | tel → relógio | `[00]` | só ACK | fora | `SmaBLE.m:479-490` |
| `05/54` → `05/55` | SPORT V2 | tel → relógio | vazio | até 15 × 16 bytes | fora | `SmaManager.java:986-1009, 1444-1450` |
| `01/xx` | OTA, XMODEM, watchfaces | — | — | — | fora | `SmaManager.java:451-504, 1357-1398` |

**Por que `02/6F` não é enviado.** A SDK Android só declara a constante `SET_SYNC_SWITCH = 111`; nenhum método da SDK nem do Core monta esse comando, e a callback `onDataChange` do `02/70` só registra no log no Core (`SMAService.java:381-383`). O `Syncdata:` da SDK iOS parece equivalente, mas é outro comando (`05/06`, documentado como "só para relógios 02/04/05", `SmaBLE.h:274-278`, `SmaBLE.m:1076-1090`). Sem payload confirmado, `enableDataChangeNotices()` só registra no log e não escreve nada. Se o fitGPS mandar `02/70` sozinho, o aviso é tratado; se não mandar, o "Refresh steps" manual cobre a PoC. Fechar isso exige um snoop HCI de um app que use o recurso.

## 7. Payloads

**SET_TIME (`02/01`) e GET_TIME (`02/07`)**: um uint32 BE com o horário local de parede, sem fuso e sem horário de verão (`SmaTime.java:26-33`; união `usercdata_union_t` em `SmaBLE.m:2234-2255`).

| Bits | 31-26 | 25-22 | 21-17 | 16-12 | 11-6 | 5-0 |
|---|---|---|---|---|---|---|
| Campo | ano − 2000 (0..63) | mês (1..12) | dia (1..31) | hora (0..23) | minuto | segundo |

`(y − 2000) << 26 | m << 22 | d << 17 | h << 12 | min << 6 | s`. Exemplos: 2026-10-28 14:30:00 → `0x6AB8E780`; 2000-01-01 00:00:00 → `0x00420000`; 2063-12-31 23:59:59 → `0xFF3F7EFB`. Anos fora de 2000..2063 não cabem e o driver falha com `PROTOCOL` sem enviar. `SmaTimeCodecTest` confere byte a byte contra as expressões Java de `SmaTime.toByteArray` em 5000 datas aleatórias.

**TIMEZONE (`02/68`)**: 1 byte com sinal, offset em unidades de 15 min, truncado em direção a zero. −3 h → `-12` → `0xF4`; +5:30 → `0x16`; +5:45 → `0x17`. A Android usa `getRawOffset()` (sem horário de verão) e zera offsets acima de 24 h (`SmaTimezone.java:6-21`); a iOS usa `secondsFromGMT` (com horário de verão, `SmaBLE.m:3601-3620`). Nenhum Core envia; aqui só existe o codec, sem decisão de qual offset usar.

**USER_INFO (`02/10`)**: um uint32 BE (`SmaBLE.m:2209-2229`; `SmaUserInfo.java:9-16`).

| Bits | 31 | 30-24 | 23-15 | 14-5 | 4-0 |
|---|---|---|---|---|---|
| Campo | gênero (0 F, 1 M) | idade (0..127) | altura em meio cm (0..511) | peso em meio kg (0..1023) | reservado |

As duas SDKs geram o mesmo layout, mas a Android faz `(int)this.height * 2`, que trunca a altura para cm inteiros antes de dobrar (173,5 → 346); a iOS faz `he*2` (173,5 → 347). O codec segue o iOS. Perfil fixo do Core (masculino, 30 anos, 173,5 cm, 60 kg) → `9E AD 8F 00`.

**BATTERY_BACK (`02/09`)**: `value[0]` sem sinal = percentual (`SmaManager.java:652-658`; `SmaBLE.m:331-336`). Value vazio → `PROTOCOL`; acima de 100 é limitado a 100 e registrado.

**VERSION_BACK (`02/0B`)**: `value[0].value[1].value[2]` = firmware, sem sinal (`SmaManager.java:641-651`); `value[3..5]` = versão BLE, lida só se os 6 bytes existirem (o iOS lê sem conferir, `SmaBLE.m:325-330`). Menos de 3 bytes → `PROTOCOL`.

## 8. Dados de histórico e paginação

Os formatos de registro, os modos e o mapeamento para o domínio estão em [`sma-dados.md`](sma-dados.md). Aqui fica a parte de protocolo.

- **Pedido**: `05/41` (atividade) ou `05/45` (sono) com value vazio. **Resposta**: `05/42` (registros de 8 bytes) ou `05/46` (7 bytes), no máximo 20 por resposta (`SmaManager.getMaxItem`, `SmaManager.java:1444-1450`).
- **Paginação**: se a resposta trouxer exatamente 20 registros, pede-se a mesma key de novo e o relógio devolve o lote seguinte; qualquer outra contagem, inclusive value vazio, encerra (`SmaManager.onDataRead`, `SmaManager.java:1408-1438`; o iOS refaz o pedido quando o value tem 160 ou 140 bytes, `SmaBLE.m:2706-2708, 2935-2937`, e trata value vazio como fim ou "NODATA", `:2683-2693, 2913-2923`).
- **Sem retentativa em GET**: se a resposta de uma página atrasar e o pedido fosse reenviado, o relógio poderia avançar o cursor duas vezes e uma página se perderia. Timeout de GET encerra a leitura com `TIMEOUT`.
- **Proteções extras** (não existem nas SDKs): uma página idêntica à anterior encerra a leitura sem duplicar os registros, e há um teto de 1000 páginas.

**Hipóteses abertas e como cada uma será verificada**

| # | Hipótese | Default desta biblioteca | Como verificar |
|---|---|---|---|
| H1 | O cursor do histórico avança quando o relógio recebe o nosso ACK da resposta | ACK antes de entregar; sem retentativa de GET; `SampleStore` persistente na sessão | Fixture real (E4): ler duas vezes seguidas e comparar; ler sem ACK (teste manual) e ver se a página se repete |
| H2 | Registros lidos são apagados do relógio | A sessão nunca depende de reler o passado | Mesmo fixture de H1: segunda leitura completa vazia ou só com o novo |
| H3 | `mode 0` é o total da hora (não acumulado do dia) | `Quirks.stepsAggregation = HOURLY_SUM`, igual ao Core (ver `sma-dados.md`) | Comparar a soma das horas de um dia com o total do relógio na tela (critério A6) |
| H4 | O fitGPS responde `05/45` com registros de 7 bytes | Sono exploratório; vazio → sem sessões | Uma noite com o relógio e o fixture correspondente |
| H5 | O L1 de 8 bytes sempre chega em notificação separada | Tolerância a cabeçalho e corpo juntos | Snoop HCI e o log de frames do app de exemplo |
| H6 | O fitGPS expõe FF60 além de NUS | FF60 tem prioridade, como a Android | Lista de serviços descobertos no log da E3 |
| H7 | O BIND pede confirmação na tela | `bindTimeoutMs = 60000` | Primeiro pareamento real, observando o tempo até `03/02` |
| H8 | O LOGIN aceita qualquer payload ou precisa repetir o do BIND | Fallback FIRST_PAIR com `USER_ID` uma vez; na reconexão, LOGIN com o outro formato, sem BIND | Parear pelo app iOS atual e depois conectar com este driver |
| H9 | O firmware descarta um frame repetido pelo seq (o iOS reenvia com o mesmo seq) | Retentativa de SET/handshake com seq novo; nada depende de o firmware deduplicar | Fixture real com um SET sem ACK forçado (atrasar o ACK no relógio é difícil; observar no snoop HCI uma retentativa do app iOS atual) |

## 9. Vetores dourados

Os 10 frames abaixo estão em `SmaFrameGoldenTest` (codificação e decodificação nos dois sentidos). Cada um foi conferido de forma independente com um CRC-16/ARC bit a bit. `Z31` = 31 bytes `00`.

| # | Frame | Hex |
|---|---|---|
| 1 | BIND, `ANDROID_FF`, seq 1 | `AB 00 00 25 19 FB 00 01 03 00 01 00 20 FF` + Z31 |
| 2 | LOGIN, `ANDROID_FF`, seq 2 | `AB 00 00 25 D9 A0 00 02 03 00 03 00 20 FF` + Z31 |
| 3 | SET_TIME 2026-10-28 14:30:00, seq 3 | `AB 00 00 09 CD 2F 00 03 02 00 01 00 04 6A B8 E7 80` |
| 4 | TIMEZONE −3 h, seq 4 | `AB 00 00 06 95 1D 00 04 02 00 68 00 01 F4` |
| 5 | BATTERY, seq 5 | `AB 00 00 05 02 F8 00 05 02 00 08 00 00` |
| 6 | VERSION, seq 6 | `AB 00 00 05 C2 59 00 06 02 00 0A 00 00` |
| 7 | SPORT, seq 7 | `AB 00 00 05 14 9C 00 07 05 00 41 00 00` |
| 8 | SLEEP, seq 8 | `AB 00 00 05 D5 DD 00 08 05 00 45 00 00` |
| 9 | UNBIND, seq 9 | `AB 00 00 06 6F 01 00 09 03 00 05 00 01 00` |
| 10 | ACK do seq `0x1234` | `AB 10 00 00 00 00 12 34` |

**Decomposição campo a campo**

| # | magic | flags | len(L2) | CRC | seq | cmd | ver | key | N | value |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `AB` | `00` | `0025` = 37 | `19FB` | `0001` | `03` CONNECT | `00` | `01` BIND | `0020` = 32 | `FF` + Z31 |
| 2 | `AB` | `00` | `0025` = 37 | `D9A0` | `0002` | `03` CONNECT | `00` | `03` LOGIN | `0020` = 32 | `FF` + Z31 |
| 3 | `AB` | `00` | `0009` = 9 | `CD2F` | `0003` | `02` SET | `00` | `01` SET_TIME | `0004` | `6AB8E780` = 26/10/28 14:30:00 |
| 4 | `AB` | `00` | `0006` = 6 | `951D` | `0004` | `02` SET | `00` | `68` TIMEZONE | `0001` | `F4` = −12 × 15 min |
| 5 | `AB` | `00` | `0005` = 5 | `02F8` | `0005` | `02` SET | `00` | `08` BATTERY | `0000` | — |
| 6 | `AB` | `00` | `0005` = 5 | `C259` | `0006` | `02` SET | `00` | `0A` VERSION | `0000` | — |
| 7 | `AB` | `00` | `0005` = 5 | `149C` | `0007` | `05` DATA | `00` | `41` SPORT | `0000` | — |
| 8 | `AB` | `00` | `0005` = 5 | `D5DD` | `0008` | `05` DATA | `00` | `45` SLEEP | `0000` | — |
| 9 | `AB` | `00` | `0006` = 6 | `6F01` | `0009` | `03` CONNECT | `00` | `05` UNBIND | `0001` | `00` |
| 10 | `AB` | `10` ACK | `0000` | `0000` | `1234` | — | — | — | — | — |

Vetor extra (não está entre os 10, mas está no teste): BIND com `USER_ID` 1 = `AB 00 00 25 FC 4A 00 01 03 00 01 00 20 00 00 00 01` + 28 × `00`.

## 10. Quirks e diferenças Android × iOS

| Tema | SDK Android | SDK iOS | Escolha desta biblioteca |
|---|---|---|---|
| Serviço GATT | FF60 se existir, senão NUS (`SmaManager.java:1577-1591`) | só NUS (`SMAService.m:1056-1061`) | candidatos em ordem FF60, NUS (`Profiles`) |
| MTU / fragmento | pede 512, corta em `mtu − 3` (`:204, 305`) | não negocia, corta em 20 (`SmaBLE.m:579-605`) | pede 247, corta em `maxFragment ?: mtu − 3`, mínimo 20 |
| Tipo de escrita | padrão da characteristic (`SmaMessenger.java:118-119`) | sempre com resposta (`SmaBLE.m:3336`) | sempre com resposta |
| Payload BIND/LOGIN | `FF` + 31 zeros (`SmaManager.java:436-449`) | userId BE32 + 28 zeros (`SmaBLE.m:559-570`) | `ANDROID_FF` por padrão; no primeiro pareamento, fallback `USER_ID` uma vez; na reconexão, LOGIN com o outro formato, nunca BIND |
| Sucesso do LOGIN | `value[0] == 0` (`SmaManager.java:736-739`) | só CRC (`SmaBLE.m:294-301`) | value vazio ou `value[0] == 0` |
| Seq inicial | 1 (`SmaBleHelper.java:5`) | 0 (`SmaBusinessTool.m:12`) | 1, com wrap em 16 bits |
| NACK enviado | nunca | quando o CRC falha (`SmaBLE.m:611-620`) | quando o CRC falha |
| NACK recebido | tratado como ACK (`SmaManager.java:509-515`) | não reconhecido como controle (`SmaBusinessTool.m:269`) | conta como resposta de controle e dispara a retentativa de SET/handshake; em GET falha com `PROTOCOL` |
| Remontagem | só com L1 isolado; sem timeout (`SmaManager.java:506-528`) | aceita L1 + corpo; timeout de 8 s; buffer estático de 256 bytes (`SmaBLE.m:40, 137-222`) | regras da seção 4; até 2048 bytes |
| ACK no meio de um frame | seria concatenado ao corpo | reconhecido | sempre reconhecido, nunca entra no corpo |
| Fila e timeout | 4 s por semáforo, segue sem erro (`SmaMessenger.java:36-96`) | 8 s, uma retentativa, depois descarta (`SmaBLE.m:3302-3344`) | 4 s para ACK e para resposta; retentativa só em SET/handshake; erro tipado |
| Seq na retransmissão | não retransmite (`SmaMessenger.java:36-96`) | mesmos bytes, mesmo seq (`SmaBusinessTool.m:118-126`, `SmaBLE.m:3330-3337`) | seq novo (H9) |
| ACK atrasado | não confere o seq (`SmaManager.java:508-516`) | não confere o seq (`SmaBLE.m:163-166`) | não confere o seq, exceto para descartar o ACK atrasado de um pedido cuja resposta já valeu como ACK |
| Fim da paginação | `count == 20` repete; menor encerra; 8 s sem nada encerra com falha (`SmaManager.java:1400-1438`) | 160/140 bytes repete; 0 entrega ou "NODATA" | `count == 20` repete; qualquer outra contagem encerra; página repetida e 1000 páginas encerram |
| Fuso no TIMEZONE | `getRawOffset()`, sem horário de verão | `secondsFromGMT`, com horário de verão | só codec; não enviado |
| Timestamps de histórico | relógio de parede lido como UTC; o Core subtrai `getRawOffset()` (`SMAService.java:420`) | relógio de parede em formatter GMT (`SmaBLE.m:2677-2702`) | relógio de parede convertido com o fuso do telefone, com horário de verão |
| Tamanho do frame | `res[2] << 8` com byte com sinal (`SmaBleHelper.java:43`) | `uint8_t`, correto | sem sinal; limite de 2048 |
| UNBIND | `03/05 [00]` | `03/05` sem value, 20 bytes lidos de um buffer de 14 (`SmaBLE.m:458-467`) | `03/05 [00]` |
| Altura no USER_INFO | trunca para cm inteiros | meio cm | meio cm |
| Reconexão | loop de 18 s com close/scan/connect até o LOGIN (`EaseConnector.java:25-44`) | sem loop; Core usa restauração | responsabilidade da sessão e do host, sem reconexão durante o handshake |
