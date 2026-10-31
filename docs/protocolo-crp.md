# Protocolo CRP (Condor Reebok, P85)

Este documento descreve o pedaço do protocolo BLE dos relógios Moyoung (SDK `crpblelib`, família CRP) que o driver próprio usa com o Condor Reebok na fase 1: GATT, frame `FE EA`, fila e temporização, handshake de versão, catálogo de comandos, payloads, passos por meia hora com os totais do dia, sono, o vínculo Bluetooth clássico que o relógio exige para chamadas e música, as diferenças entre Android e iOS e os vetores dourados. A fase 1 cobre pareamento, reconexão, esquecer, bateria, firmware, hora, passos do dia e históricos de passos e de sono, as mesmas funções do fitGPS (SMA) e do Life Ultra GPS (iDO). O resto (atividades, frequência cardíaca, estresse, perfil, metas, OTA, mostradores) fica para as fases seguintes (seção 13).

## 1. Escopo e convenção de citação

- **Relógio**: Condor Reebok, nome BLE (GAP 2A00) `P85`, firmware `MOY-VS02-2.0.0`, protocolo `MOYOUNG-V2`. O perfil é `Profiles.CONDOR_REEBOK` (`condorreebok`, ADR 25).
- **Código**: `protocol/src/commonMain/kotlin/br/com/grupotechnos/watch/protocol/crp/` (`CrpFrame.kt`, `CrpReassembler.kt`, `CrpCommand.kt`, `CrpPayloads.kt`, `CrpStepsMapper.kt`, `CrpSleepAssembler.kt`, `CrpDriver.kt`; o `README.md` do pacote lista as invariantes). Quando um byte mudar aqui, o vetor em `CrpFrameGoldenTest` muda junto, e vice-versa.

**Fontes citadas**

| Abreviação | O que é |
|---|---|
| `sdk:<pacote>/<classe>.java:<linhas>` | SDK Android descompilada e ofuscada, `crpblelib` 2.0.3 (a mesma versão que o Core de produção usa, `core@v4.3.57:android/build.gradle:141`). O caminho é relativo a `com/crrepa/`; `sdk:m/f.java:94-130` é `com/crrepa/m/f.java`. Os dois caminhos que não são de `com/crrepa/` aparecem por inteiro (`sdk:com/realsil/...`). Só referência de leitura, fora do repositório. |
| `core@v4.3.57:<caminho>:<linhas>` | `git show v4.3.57:<caminho>` no `TechnosWareCore`, a versão que o app Condor fixa. |
| `iOS-bin@0x…` / `iOS-h:<linha>` | Endereço no binário da SDK iOS fechada (`CRPSmartBand.framework`, lido com `otool`) e linha do header Swift gerado. |
| `pair-sync@N`, `bt-reconnect@N`, `walk@N` | As fixtures de referência `fixtures/reference/condorreebok/MOY-VS02-2.0.0/condor-app-pair-sync.jsonl` (conexão 11, `recordedAt` 15:43:12.209), `condor-app-bt-reconnect.jsonl` (conexão 16, 15:46:26.727) e `condor-app-reconnect-sync-walk.jsonl` (conexão 17, 15:47:21.326), citadas pelo `atMs` do pacote. São a captura HCI do app Condor de produção pareando o P85 num Android em 2026-10-31, convertida e com redação (`fixtures.md`, seção 5; ADR 32). A hora de parede de um pacote é `recordedAt + atMs`: `pair-sync@1535` é 15:43:13.744. |
| `HCI hh:mm:ss.mmm` | A mesma captura, para o que a fixture não guarda (Bluetooth clássico, SMP, eventos de conexão, a conexão 10). O btsnoop bruto nunca entra no repositório; ele é citado pelo horário, como o iDO cita o log da SDK pela linha. |
| fase 0 | O levantamento do protocolo feito antes da implementação (afirmações com arquivo:linha, revisadas), citado pelo identificador da afirmação (`CON-17`) quando a fonte é ele. |

## 2. GATT

A descoberta da conexão 11, como o cabeçalho `gatt` de `pair-sync` a guarda (as três fixtures têm o mesmo GATT):

| Serviço | Characteristic | Propriedades | CCCD | Uso |
|---|---|---|---|---|
| `1800` | `2a00` | read, write | não | nome GAP, `P85` (lido pelo app em HCI 15:43:12.351) |
| `1801` | `2a05` | indicate | sim | Service Changed; não usada |
| `180d` | `2a37` | notify | sim | frequência cardíaca; notificação lateral, só log na fase 1 |
| | `2a38` | read | não | não usada |
| `180f` | `2a19` | read, notify | sim | bateria: leitura e notificação lateral |
| `180a` | `2a25`, `2a26` | read | não | não usadas |
| | `2a28` | read | não | firmware em texto (reserva do `5A 01`) |
| | `2a29` | read | não | versão do protocolo em texto (`MOYOUNG-V2`) |
| `feea` | `fee1` | read, notify | sim | passos do dia: leitura e notificação lateral |
| | `fee2` | writeWithoutResponse | não | escrita dos frames (perfil, `write`) |
| | `fee3` | notify | sim | respostas em frames (perfil, `notify`) |
| | `fee4` | read | não | não usada (o iOS a lê como `enMac`, `iOS-bin@0x5c524-0x5c538`) |
| | `fee5`, `fee6` | writeWithoutResponse | não | dados de OTA e de mostrador (`sdk:m/f.java:132-138`); fora da fase 1 |
| `fee7` | `fec9` | read | não | perfil WeChat de passos; o iOS lê `FEC9` como MAC (`iOS-bin@0x5c50c-0x5c520`); não usada |
| | `fea1` | read, notify | sim | perfil WeChat; a SDK não a usa como notificação; não usada |
| `190e` | `0004` / `0003` | writeWithoutResponse / notify | `0003` sim | desconhecido; nenhuma fonte usa; não usada |
| `ae00` | `ae01` / `ae02` | writeWithoutResponse / notify | `ae02` sim | serviço da JieLi (a plataforma provável do P85, fase 0 `C19`); não usada |

- **Canal de comandos**: a SDK acha o serviço e as characteristics por substring (`feea`, `fee1`, `fee2`, `fee3`) e só segue se as três existirem (`sdk:q/b.java:36-61`, `sdk:q/c.java:45-65`); escreve os frames em `FEE2` e remonta as notificações de `FEE3` (`sdk:m/f.java:132-140`, `sdk:m/a.java:3739-3765`). Bateria, firmware e versão do protocolo são characteristics padrão: o mapa da SDK é 16 = `FEE1`, 17 = `2A28`, 18 = `2A19`, 20 = `2A29` (`sdk:m/e.java:72-103`).
- **Perfil**: `Profiles.CRP_FEEA` (`service` `feea`, `write` `fee2`, `notify` `fee3`) com `sideNotify` = `2a37` (serviço `180d`), `fee1` (`feea`) e `2a19` (`180f`), nessa ordem (ADR 26). As notificações de `fee3` vão para o canal de frames do driver; as de `sideNotify`, para um canal à parte, com a characteristic de origem.
- **CCCDs, na ordem**: o app de produção escreveu `01 00` em `fee3`, `2a37` e `fee1` (cabeçalho `cccdWrites` das três fixtures), a ordem da SDK sem as que o P85 não tem: a SDK tentaria `FEE3`, `2A37`, `FEE7`, `FEE8`, `FEE1`, `4A02` e `FEE9` (`sdk:q/b.java:162-174`), mas o `FEEA` do P85 não tem `FEE7`/`FEE8`/`FEE9`, e o `FEE7` dele é outro serviço. A sessão liga `fee3` (obrigatória) e depois `2a37`, `fee1` e `2a19`, as três sem ser fatais (ADR 26). O `2a19` entra porque tem CCCD (propriedades `0x12`) e porque nem o Android nem o iOS entregam ao app a notificação de uma characteristic que ele não assinou; é o `subscribeDeviceBattery` da SDK (`sdk:h/a.java:423-429`, `sdk:m/d.java:14-43`), que o Core nunca chama.
- **A bateria notifica sem CCCD**: o relógio mandou a notificação de `2a19` uma vez por minuto (`pair-sync@48921`, `@108726`, `@169059`), com o mesmo valor da tela (0x32 = 50 %), apesar de o app nunca ter escrito o CCCD dela. No Android, ela para no registro do `BluetoothGatt.setCharacteristicNotification`; por isso a CCCD continua necessária (`CrpReferenceFixtureTest.batteryNotificationsWithoutCccd`).
- **Tipo de escrita**: `FEE2` declara só write-without-response (`0x04`), e a captura mostra ATT Write Command (`0x52`) em todo frame. O driver escreve sempre `WriteType.WITHOUT_RESPONSE` (seção 11 compara com a SDK).
- **MTU**: quem abre a troca é o relógio, com 517, logo depois da conexão (HCI 15:43:12.350; cabeçalho `mtu` 517 e `mtuInitiator` `"peripheral"`); o Android responde 517. O `requestMtu(512)` do app não gera tráfego: o Android responde na hora com o MTU já trocado (seção 5).
- **Sem SMP e sem criptografia no BLE**: a conexão LE nunca pediu pareamento nem criptografia. O vínculo do sistema que o relógio exige é o do Bluetooth clássico (seção 10).
- **Nome**: o Core aceita `Condor Reebok` ou qualquer nome que comece com `P85` depois de `trim` (o `trim` em `core@v4.3.57:android/src/main/java/com/crp/bluetooth/connectionmanager/CRPBleConnectHelper.java:873`, o `equals`/`startsWith` em `core@v4.3.57:android/src/main/java/com/reactlibrary/devices/AvailableDevices.java:55-57`); o app Condor ainda trata o nome cru `P85` como paliativo (`GTE-CondorSmart/src/helpers/general.ts:51-58`). O perfil tem `bleNames = ["P85", "Condor Reebok"]` e `bleNamePrefixes = ["P85"]` (`DeviceRegistry.identify`/`matchesName`).

## 3. Frame

```
FE EA | b2 | len | cmd | payload...        sem CRC, sem sequência, sem ACK de aplicação, sem criptografia
```

- **`len`** é o total do frame com o cabeçalho (payload + 5), e os 4 bits baixos de `b2` são os bits altos dele: um comprimento de 12 bits (`CrpFrameCodec.totalLength`).
- **`b2` no envio** (`sdk:f/b2.java:9-37`): `0x10` quando o bloco de escrita é 20; senão `0x20 + (total >> 8)`. Com bloco 20 e total acima de 255, a SDK manda `0x10` e o `len` truncado; o codec recusa (`IllegalArgumentException`, que o driver traduz para `PROTOCOL`), porque nenhum frame da fase 1 passa de 255. O iOS marca o V3 com `0x30` (`iOS-bin@0x82764-0x827a8`); o driver não, como o Android.
- **Bloco**: 20 até a versão ser conhecida, e sempre 20 no V1; no V2/V3, `max(20, x − x % 4)` com `x = min(MTU, 250) − 3` (`sdk:l/a.java:51-57`). MTU 23 → 20, 185 → 180, 247/512/517 → 244 (`CrpFrameCodec.blockSize`). O driver começa com o bloco do MTU como se fosse V2 e recalcula depois do handshake. Com o MTU 517 do P85, todo frame da fase 1 vai numa escrita só.
- **Fragmentação**: o frame é cortado em pedaços crus do tamanho do bloco, sem cabeçalho por pedaço; cada pedaço é uma escrita GATT. A SDK manda o próximo no `onCharacteristicWrite`, depois de um `sleep` de 20 ms (`sdk:h/d.java:121-133`); o driver espera `FRAGMENT_GAP_MS` = 20 ms entre pedaços.
- **Resposta**: repete o `cmd` e, nos comandos multiplexados, o primeiro byte do payload (o "sub"; dois bytes no `B6`). O despacho da SDK é por `cmd` e sub (`sdk:m/a.java:455-735`). O P85 responde com `b2 = 0x20` mesmo a um pedido com `0x10` (`walk@1196` → `walk@1280`), e com MTU 517 cada resposta chega numa notificação só, inclusive as de 54 e 78 bytes (`pair-sync@56234`, `@58558`).

**Remontagem** (`CrpReassembler`, uma instância por conexão):

1. Pacote que começa com `FE EA`, com nibble alto de `b2` em 1, 2 ou 3 e `len` de pelo menos 5: começa um frame. Uma parcial anterior é descartada com WARN (ressincronização). Se o pacote já tem o total, o frame sai, e o que passar do total é descartado com WARN.
2. Pacote que não começa um frame: com parcial, é concatenado até completar o total (o excesso vai para WARN); sem parcial, WARN e descarte.
3. Cada pacote que deixa uma parcial (re)arma um descarte em `Quirks.fragmentGapTimeoutMs` (3 s no perfil).

Diferenças em relação à SDK (`sdk:m/a.java:3700-3738`), todas de propósito (fase 0, `C09` com a correção): a SDK lê o `len` com sinal quando `b2` é `0x10` (`sdk:m/a.java:3736-3738`), e um frame de 128 bytes ou mais lança `NegativeArraySizeException` dentro do `onCharacteristicChanged` e trava o remontador; só confere o `FE EA` com o remontador ocioso (sem ressincronização); não tem timeout; e o estado vive no objeto criado em `sdk:h/d.java:39-41`, que não é zerado no disconnect. O comprimento aqui é sempre sem sinal (`CrpReassemblerTest`).

## 4. Fila e temporização

A fila da SDK tem um frame em voo e espera, depois de escrever o frame inteiro, um tempo que depende do `cmd` (`sdk:m/f.java:94-130`, com a correção da fase 0 `C13`):

| `cmd` | Espera na SDK | Driver (`CrpCommand.postDelayMs`) |
|---|---|---|
| `0x33` | 1000 ms | 1000 ms |
| `0x11`, `0x12`, `0x34`–`0x37`, `0x3B`, `0x3C` | 500 ms | 500 ms |
| `0x67` | 50 ms | 50 ms |
| `0x32` | nenhuma: a fila fica parada até o fim da escrita **e** uma resposta `0x32` ou `0x33` (`sdk:o/b.java:18-38`, `sdk:m/a.java:3044, 3094`) | 200 ms, e a resposta é esperada com timeout, como em todo pedido |
| demais | 200 ms | `Quirks.commandGapMs` = 200 ms |
| pseudo-`cmd` 1, 2, 4 (blocos em `FEE5`, `FEE6`, `FEEA/FEE9`) | 0, 0, 50 ms | não usados na fase 1 (`sdk:m/f.java:193-207`) |

- **A espera serial do driver** (`CrpDriver.send`): um pedido por vez, sob um `Mutex`. O pedido em andamento é registrado antes da escrita (a resposta pode chegar antes do callback da escrita sem resposta); os pedaços saem com 20 ms entre eles; depois vem o atraso da tabela; com resposta esperada, espera `Quirks.commandTimeoutMs` (4 s), contado depois do atraso. Sem resposta, `send` devolve `null` e quem chamou decide entre `TIMEOUT` e tolerar (seção 6). É mais restrito que a SDK, que põe vários pedidos na fila e deixa as respostas chegarem fora de ordem: aqui não há ambiguidade de casamento.
- **O `0x32` não trava nada**: na SDK, um `0x32` sem resposta deixa a fila parada até o watchdog, que só é avaliado quando outro comando entra (`sdk:p/c.java:33-43, 129-146`); o `B6 01 00` que a SDK põe logo depois fica preso atrás dele (`sdk:h/a.java:301-305`). Aqui o `0x32` sem resposta termina em 4 s e o sync segue sem o sono de hoje (`CrpSessionTest.silent32TimesOutWithoutBlockingTheQueue`).
- **O timeout da SDK** é de 5 s, conta desde o último despacho e só é conferido ao enfileirar algo (`sdk:p/c.java:114-123`); quando estoura, abandona o frame em voo sem reenviar (`sdk:p/c.java:38-42`). O driver também não reenvia.
- **Na captura**, o app espaçou os frames de um mesmo lote em cerca de 228 ms (200 ms da fila mais a escrita; por exemplo `pair-sync@56165`, `@56403`, `@56631`, `@56858`, `@57084`, `@57313`).

## 5. Handshake

Não há vínculo de aplicação, desafio nem confirmação na tela: a SDK não tem bind no handshake (`sdk:h/d.java:151-169`; fase 0 `CON-08`), e o app de produção não manda nenhum. O primeiro pareamento e a reconexão são iguais (`HandshakeMode.FIRST_PAIR` e `RECONNECT`), com a fase `"VERSION"` (`CrpDriver.PHASE_VERSION`).

```mermaid
sequenceDiagram
	participant W as P85
	participant S as Sessão
	participant D as CrpDriver
	W->>S: ATT MTU Request 517 (o relógio abre a troca)
	S->>W: CCCD 01 00 em fee3, depois 2a37, fee1, 2a19 (as três últimas sem ser fatais)
	Note over S: requestMtu(512): sem tráfego, o Android responde com o 517 já trocado
	S->>D: handshake(mode), fase VERSION
	Note over D: espera 300 ms (sdk:h/d.java:45-56)
	D->>W: ATT Read 2a29
	W-->>D: "MOYOUNG-V2"
	D->>W: FE EA 20 06 5A 00
	W-->>D: FE EA 20 10 5A 00 "MOYOUNG-V2"
	Note over D: versão pelo texto de 2a29 (ou da resposta), bloco recalculado
	D->>W: FE EA 20 06 B7 0E (V2/V3, sem resposta no P85)
	D->>W: FE EA 20 07 BB 16 00 (V2/V3, sem resposta no P85)
	D-->>S: pronto: a partir daqui FEE1 e 2A19 viram avisos
	Note over S: READY; 250 ms depois, 31 (hora) e BB 07 00 (fuso)
```

1. **MTU**: a sessão pede o MTU depois das CCCDs e antes do handshake, como já fazia; com o P85 isso não muda nada no ar (seção 2). A SDK pede `requestMtu(512)` só depois de saber que é V2/V3 (`sdk:e/f.java:16-52`) e só declara conectado no `onMtuChanged` (`sdk:h/d.java:151-168`); pedir antes evita dar `requestMtu` ao `DriverLink` (ADR 25).
2. **Versão**: o driver lê `2A29` e manda **sempre** `5A 00`, como a captura (`pair-sync@1535`, `@1542`, resposta `@1596`; `bt-reconnect@1814`, `@1818`; `walk@1187`, `@1196`). Vale o texto de `2A29`; sem ele (leitura falhou ou vazia), vale o da resposta `5A 00`. A SDK põe as duas na fila e usa a primeira que chegar, quase sempre a leitura (`sdk:m/e.java:121-127`, `sdk:e/g.java:24-33`). A regra é a do iOS: tira `NUL` e espaços, `contains("MOYOUNG-V3")` → V3, `contains("MOYOUNG-V2")` → V2, senão V1 (`iOS-bin@0x82548-0x8262c`); ela cobre o `equals` do Android (`sdk:j/h0.java:13-20`). Os dois textos diferentes só geram um INFO.
3. **Sem versão nenhuma** (sem `2A29` e sem resposta ao `5A 00` em 4 s): `WatchException(TIMEOUT, "no protocol version (2A29 and 5A 00)")`. No primeiro pareamento a sessão traduz para `HANDSHAKE_FAILED`; na reconexão continua tentando, com espera crescente (ADR 11). `TIMEOUT`, e não `HANDSHAKE_FAILED`, é o que mantém a reconexão viva.
4. **V2/V3**: `B7 0E` (consulta de SPP, `sdk:f/a0.java:64-68`) e `BB 16 00` (contagem de emojis, `sdk:f/d2.java:11-17`, `sdk:e/d.java:23-28`), sem esperar resposta, como a SDK depois do `onMtuChanged` (`sdk:h/d.java:160-166`). O P85 não respondeu a nenhum dos dois (`pair-sync@1773`, `@2025`). A paginação `BB 16 01 nn` que a SDK faria com uma resposta não existe aqui. **V1**: bloco 20 e nenhuma das duas consultas (`CrpSessionTest.v1WatchUsesBlock20AndSkipsTheExtraQueries`).
5. **Pronto**: o driver passa a tratar `FEE1` e `2A19` como avisos (antes disso eles só vão para o DEBUG; o relógio manda `FEE1` zerado logo depois da conexão, `pair-sync@69`).
6. **Depois do READY**: `postLoginDelayMs` = 250 ms e o acerto de hora interno (`31` e `BB 07 00`, seção 7). O valor vem da reconexão da captura: o `31` saiu 249 ms depois do `B7 0E` (`bt-reconnect@2046` → `@2295`). O `enableDataChangeNotices` interno do CRP só registra um INFO: as notificações vêm das CCCDs da sessão.

**`b2` do `5A 00`.** Com o MTU 517 já trocado, o `5A 00` sai com `0x20`, como nas conexões 11 e 16 da captura (`pair-sync@1542`, `bt-reconnect@1818`). Na conexão 17, a primeira de um processo novo do app, a SDK mandou `0x10` (`walk@1196`): o tamanho de bloco fica num singleton que começa em 20 e não é zerado entre conexões (`sdk:l/a.java:12-17`), então cada conexão começa com o bloco da anterior (fase 0, `C07`). O relógio aceitou os dois. Com MTU 23 o driver também manda `0x10`.

**Ordem do app de produção no primeiro pareamento** (para comparar): `2A29`, `5A 00`, `B7 0E`, `BB 16 00` (`pair-sync@1535` a `@2025`); `F9 01 01 00` (`@3172`, o clássico desconectado); o diálogo do vínculo clássico (HCI 15:43:15-18, seção 10); só então `31` e `BB 07 00` (`@6821`, `@7047`), porque o Core espera o `BOND_BONDED` antes da hora (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/connectionmanager/CRPBleConnectHelper.java:513-518`; fase 0 `CON-20`). Aqui a hora sai 250 ms depois do READY, e o pedido do vínculo clássico corre em paralelo, pelo `CoreBridge` (seção 10). Na reconexão, o app de produção mandou o `31` antes do `BB 16 00` (`bt-reconnect@2295` e `@3216`): o `31` vai pela fila prioritária da SDK (`sdk:h/a.java:400-403`, `sdk:m/f.java:167-169`).

## 6. Catálogo

Bytes com bloco ≥ 24 (`b2` = `0x20`); com bloco 20, `b2` vira `0x10` e o resto é igual. Atraso pós-escrita pela seção 4. O timeout de resposta de um frame é `Quirks.commandTimeoutMs` (4 s), contado depois do atraso; uma leitura GATT (ATT Read) espera o dobro, 8 s, como toda operação do link da sessão (`SessionDriverLink`), e falha com `TRANSPORT`. Constantes em `CrpCommand`, pedidos em `CrpRequest`, UUIDs em `CrpGatt`.

| Função | Pedido | Resposta esperada | Fonte | Espera / falha | Fase 1 |
|---|---|---|---|---|---|
| Versão (`2A29`, serviço `180A`) | ATT Read | ASCII `4D4F594F554E472D5632` (`MOYOUNG-V2`) | `sdk:m/e.java:121-127`; `pair-sync@1535` | 8 s; falha → vale o `5A 00` | handshake |
| `5A 00` versão do protocolo | `FE EA 20 06 5A 00` | `FE EA 20 10 5A 00` + `MOYOUNG-V2` | `sdk:f/a0.java:21-23, 34-38`; `sdk:m/a.java:2816-2865`; `pair-sync@1542`/`@1596`; `walk@1196` (`FE EA 10 06 5A 00`) | 4 s; obrigatória só sem `2A29` | handshake |
| `B7 0E` consulta de SPP | `FE EA 20 06 B7 0E` | nenhuma no P85 | `sdk:f/a0.java:64-68`; `sdk:h/d.java:160-166`; `pair-sync@1773` | sem espera de resposta; 200 ms | handshake V2/V3 |
| `BB 16 00` contagem de emojis | `FE EA 20 07 BB 16 00` | nenhuma no P85 (a paginação `BB 16 01` da SDK não é feita) | `sdk:f/d2.java:11-17`; `sdk:e/d.java:23-28`; `pair-sync@2025` | sem espera de resposta; 200 ms | handshake V2/V3 |
| `31` hora | `FE EA 20 0A 31` + u32 BE + `08` | nenhuma | `sdk:f/v1.java:18-45`; `pair-sync@6821` = `6A E5 9C 17 08` | 200 ms | `setTime` |
| `BB 07 00` fuso | `FE EA 20 0B BB 07 00` + int32 LE (s) | nenhuma | `sdk:f/v1.java:74-85`; `pair-sync@7047` = `D0 D5 FF FF` (−10800) | 200 ms | `setTime` |
| Bateria (`2A19`, `180F`) | ATT Read | 1 byte; `0x32` = 50 %; > 100 = carregando | `sdk:m/e.java:85-87`; `core@v4.3.57:android/src/main/java/com/crp/bluetooth/CRPService.java:1814-1822`; `pair-sync@105394` | 8 s; falha → `TRANSPORT`; vazio → `PROTOCOL` | `readBattery` |
| Aviso de bateria | CCCD `01 00` em `2A19` | notificação de 1 byte, uma por minuto | `sdk:h/a.java:423-429`; `pair-sync@48921` (0x32), `walk@99511` (0x31) | — | aviso (`BatteryReported`) |
| `5A 01` firmware | `FE EA 20 06 5A 01` | `FE EA 20 14 5A 01` + `MOY-VS02-2.0.0` | `sdk:f/a0.java:17-19`; `pair-sync@98761`/`@98835` | 4 s; sem resposta ou vazio → `2A28` | `readDeviceInfo` |
| Firmware (`2A28`) | ATT Read | `MOY-VS02-2.0.0` | `sdk:m/e.java:105-107`; `pair-sync@98758` | 8 s; sem nenhum dos dois → `TIMEOUT` | reserva |
| Passos do dia (`FEE1`) | ATT Read | 9 bytes: passos, metros e kcal em u24 LE (+ tempo, opcional); campo todo `FF` vale 0 | `sdk:m/e.java:113-115`; `sdk:j/p0.java:12-35`; `sdk:g1/d.java:127-141`; `pair-sync@56158` | 8 s; falha → sem totais, com WARN | `readSteps` |
| Passos ao vivo | CCCD em `FEE1` | o mesmo formato | `walk@106148` `10 00 00 0D 00 00 01 00 00` (16/13/1) … `walk@136148` `4D 00 00 3F 00 00 04 00 00` (77/63/4) | — | aviso (`StepsUpdated`) |
| `59 pp` passos por meia hora | `FE EA 20 06 59 pp` (`00`, `01` hoje; `02`, `03` ontem) | `FE EA 20 36 59 pp` + 24 × u16 LE | `sdk:f/u.java:19-21, 51-53`; `sdk:j/q0.java:78-108`; `pair-sync@56165`/`@56234`, `@60613`/`@60960`, `@58729`/`@59313`, `@61368`/`@61707` | 4 s por página; sem resposta → `TIMEOUT` (passos são obrigatórios) | `readSteps` |
| `5D pp` distância / `5C pp` calorias | `FE EA 20 06 5D pp` / `5C pp` | **nenhuma no P85**; pela SDK, `[pp]` + u32 LE (cm; kcal × 10000) | `sdk:f/u.java:33-49`; `sdk:j/m.java:78-90`; `sdk:j/j.java:78-90`; `pair-sync@56403`, `@56631`, `@58957`, `@59184` | **não enviados**; só codec e teste | catálogo |
| `33 01` passos de ontem (legado) | `FE EA 20 06 33 01` | `33 01` + u24 passos + u24 m + u24 kcal (+ tempo), ou `B6 00 01` | `sdk:f/p1.java:10-18`; `sdk:m/a.java:3093-3104`; `sdk:j/p0.java:12-60` | 1000 ms + 4 s; sem resposta → legado desligado nesta conexão | `readSteps` (sonda) |
| `32` sono de hoje | `FE EA 20 05 32` | `32` + `[estado, h, m]` × N (vazio = sem sono, `pair-sync@57733`) **ou** `B6 01 00` + u16 × N | `sdk:f/m1.java:16-18`; `sdk:m/a.java:3042-3060`; `sdk:j/o0.java:24-133` | 200 ms + 4 s; sem resposta → sem sono de hoje (a fila não trava) | `readSleep` |
| `33 03` / `33 04` sono de 1 e 2 dias atrás | `FE EA 20 06 33 03` / `33 04` | `33 03`/`33 04` + `[estado, h, m]` × N, ou `B6 01 01` / `B6 01 02` + u16 × N | `sdk:f/m1.java:20-35`; `sdk:m/a.java:3093-3133` | 1000 ms + 4 s; com o legado desligado nem sai; sem resposta ao `33 03` → legado desligado e o `33 04` não sai | `readSleep` |
| `F8` remover vínculo | `FE EA 20 05 F8` | nenhuma (sucesso = fim da escrita, `sdk:m/f.java:213-220`) | `sdk:f/r.java:7-9`; `sdk:h/a.java:1774-1777`; `core@v4.3.57:android/src/main/java/com/crp/bluetooth/connectionmanager/CRPBleConnectHelper.java:443-473` | 200 ms | `unbind` |
| `51 02` reset de fábrica | `FE EA 20 06 51 02` | nenhuma | `sdk:f/x.java:21-25` | **não enviado** (fase 2) | catálogo |
| `17 0x` formato 12/24 h | `FE EA 20 06 17 01` (24 h), `17 00` (12 h) | nenhuma | `sdk:f/v1.java:60-64`; `pair-sync@144792` (24 h), `@162416` (12 h) | não enviado (fase 2) | catálogo |
| `F9` (só RX) | — | `F9 01 01 xx`, `F9 02 01`, `F9 02 00` | não tratado pela SDK (não há `case -7` em `sdk:m/a.java:455-745`); `pair-sync@3172`, `@7070`, `@7257`, `@176570` | log INFO | log |
| `77 04` / `87 04` (RX) | — | confirmação de vínculo do protocolo de confirmação da SDK, que o Core não usa | `sdk:m/a.java:632-633, 881-889` | tolerado, log DEBUG | log |
| `2A37` (RX) | CCCD | flags + bpm (`06 41` = 65) | `walk@116988` | log DEBUG `hr=<byte 1>` | log (fase 3) |

- **Casamento resposta–pedido** por `(cmd, sub)`: o sub é o primeiro byte do payload em `5A`, `59`, `5D`, `5C` e `33`, e os dois primeiros em `B6` (`CrpFrame.isCmd`, `isHistory`). O `32` aceita `32` ou `B6 01 00`; o `33 03`, `33 03` ou `B6 01 01`; o `33 04`, `33 04` ou `B6 01 02`; o `33 01`, `33 01` ou `B6 00 01` (`CrpSessionTest.b6ReplyIsAcceptedInsteadOf32`).
- **Frame que não casa**: `F9` vai para o INFO `CrpDriver/classicState` (`classic=xx` ou `sub=02 value=xx`); `77`, `87`, `B7` e `BB` para o DEBUG `notice`; o resto para o DEBUG `unsolicited … ignored` (`CrpSessionTest.f9AndBondNoticesAreOnlyLogged`). Os logs nunca levam o payload (os frames de notificação e de clima trazem texto do usuário).
- **`5D`, `5C` e `B6` nunca saem**: na conexão 11, `5D 00`, `5C 00`, `5D 02` e `5C 02` ficaram sem resposta, e a SDK só pediu a página seguinte do `59` (`59 01` e `59 03` aparecem; `5D 01` e `5C 01`, não). `B6 01 00` (`pair-sync@57743`), `B6 02 00 00` e `B6 02 01 00` também ficaram sem resposta. Mandá-los só gastaria timeouts (ADR 28; `CrpSessionTest.distanceAndCaloriesCategoriesAreNeverRequested`).

## 7. Payloads

**Texto** (`2A29`, `2A28`, `5A 00`, `5A 01`; `CrpText`): bytes como ISO-8859-1 (um caractere por byte, nunca falha), sem `NUL` e espaços nas pontas. Na resposta `5A`, o texto começa no byte 1 do payload (depois do sub).

**Hora `31`** (`CrpTimeCodec`; `sdk:f/v1.java:18-45`, `sdk:g1/d.java:50-58`): o relógio de parede do telefone, truncado no segundo, relido como se fosse GMT+8, em segundos u32 big-endian, mais o byte fixo `08`. O resultado não depende do fuso do telefone: 15:43:19 local de 2026-10-31 → `2026-10-31T15:43:19+08:00` → `0x6AE59C17` → `6A E5 9C 17 08` (`pair-sync@6821`). O `CrpReferenceFixtureTest.everySetTimeIsTheWallClockOfItsInstant` confere que cada `31` da captura decodifica para um instante entre `recordedAt + atMs − 2 s` e `recordedAt + atMs`.

**Fuso `BB 07 00`** (`CrpTimeZoneCodec`; `sdk:f/v1.java:74-85`, `sdk:g1/d.java:70-78`): `07 00` + o offset do telefone em segundos (com horário de verão), int32 little-endian. São Paulo, −10800 s → `D0 D5 FF FF` (`pair-sync@7047`).

**Totais do dia** (`CrpStepsTotals`; `sdk:j/p0.java:12-35`, `sdk:g1/d.java:127-141`): `FEE1` e a resposta `33 01` sem o primeiro byte. Tamanho múltiplo de 3 e pelo menos 9: `[passos u24 LE][distância u24 LE, m][kcal u24 LE]` e, com 12 bytes, `[tempo u24 LE]` (unidade não documentada). Um campo com os três bytes em `FF` vale 0. As unidades (m e kcal) vêm da caminhada: 16 passos, 13 m e 1 kcal no começo (`walk@106148`) e 77 passos, 63 m e 4 kcal no fim (`walk@136148`), o que o relógio mostrou. O formato novo do `B6 00 dd` (sem os dois primeiros bytes) é `[passos u32][kcal × 10000 u32][cm u32]` (+ tempo), de que a SDK só lê os 24 bits baixos de cada campo (`sdk:j/p0.java:37-60`); o driver só o aceita como resposta ao `33 01` (`CrpStepsTotals.decodeHistoryNew`).

**Bateria** (`CrpBatteryReading`): o primeiro byte, sem sinal. Acima de 100 = carregando, com nível = valor − 100; a regra é do Core de produção (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/CRPService.java:1814-1822`), não da SDK, que repassa o byte. A captura só tem `0x32` (50 %) e `0x31` (49 %); o caso de carga ainda não foi visto no P85 (`pendencias.md`). Um nível calculado acima de 100 é limitado a 100 com WARN.

**Página de meia hora** (`CrpCategoryPage`; `sdk:j/q0.java:78-108`): `[página] + valores`. No `59`, u16 LE por meia hora; o P85 manda 24 por página (`[página] + 48 bytes`, `pair-sync@56234`), 2 páginas por dia. No `5D` (cm) e no `5C` (kcal × 10000), u32 LE (`sdk:j/m.java:78-90`, `sdk:j/j.java:78-90`); só codec. A SDK aceita no `59` só payload de tamanho ímpar (`sdk:j/q0.java:82-83`); o codec recusa tamanho par (`null`), e o driver traduz para `PROTOCOL`.

**Sono legado** (`CrpSleepPayload.decodeLegacy`; `32` inteiro, `33 0x` sem o primeiro byte): `[estado, hora, minuto] × N`, tamanho múltiplo de 3 (`sdk:j/o0.java:27`); vazio = sem sono. Bytes lidos sem sinal (a SDK lê com sinal, `sdk:j/o0.java:37-38`).

**Sono novo** (`CrpSleepPayload.decodeNew`; `B6 01 dd` sem os dois primeiros bytes): u16 LE × N, com estado nos bits 0-3, hora nos bits 4-9 e minuto nos bits 10-15 (`sdk:j/o0.java:98-133`). Estados nos dois formatos: 0 acordado, 1 leve, 2 profundo, 3 REM (`sdk:j/o0.java:59-72`).

**`F9`** (`CrpClassicState`): não existe no despacho da SDK 2.0.3. O significado vem só da captura: `F9 01 01 xx` acompanha o Bluetooth clássico, 00 desconectado e 01 conectado (00 em `pair-sync@3172`, 01 em `@7070`, logo depois do vínculo fechar; na reconexão, 00 e 01 em `bt-reconnect@924` e `@1379`, com o relógio abrindo o clássico sozinho). `F9 02 01` chega periodicamente (`pair-sync@7257`, `@13089`, …); `F9 02 00` veio em `@176570`, junto com `F9 01 01 00`, 110 ms antes de a conexão clássica cair (HCI 15:46:08.889) e 1,6 s antes da queda da conexão LE 11 (HCI 15:46:10.358). Fica só no log.

## 8. Passos

O P85 dá duas coisas: os passos de cada meia hora (o `59`, hoje e ontem) e os totais do dia (`FEE1` para hoje; o `33 01` legado para ontem, se o firmware tiver). Não dá distância nem calorias por meia hora: o `5D` e o `5C` ficaram sem resposta (seção 6). Em produção, o Core grava essas meias horas de distância e calorias, que no P85 saem zeradas (`pendencias.md`).

**`readSteps`** (`CrpDriver.readSteps`):

```
now, tz; idx = (hora * 60 + minuto) / 30                  meia hora atual, de 0 a 47
totals  = leitura de FEE1 (falha → WARN, sem totais)
today   = 59 00 + 59 01 → 48 meias horas                   página sem resposta → TIMEOUT
yday    = 59 02 + 59 03 → 48 meias horas
ydTotals = 33 01 (aceita B6 00 01), só se o legado não foi recusado nesta conexão; sem resposta → legado = false
amostras = CrpStepsMapper.map(today, yday, totals, ydTotals, now, tz)
estado ao vivo = meias horas de hoje antes de idx, idx e o resto atual
```

O `33 01` é uma sonda: o app de produção nunca mandou `0x33`, então não se sabe se o P85 responde. Sem resposta, `legacyHistory = false` desliga o `0x33` (passos e sono) até a próxima conexão (`CrpSessionTest.legacy33IsProbedOncePerConnection`). Com resposta (`33` ou `B6`), `legacyHistory = true`.

**Amostras** (`CrpStepsMapper.map`; o `mode` de cada uma está no KDoc de `StepsSample`):

| `mode` | Instante | `steps` | `calories` / `distanceMeters` |
|---|---|---|---|
| 0 (`MODE_BUCKET`) | início da meia hora `i` do dia, no fuso do telefone | passos da meia hora (`59`) | 0 |
| 1 (`MODE_LIVE_REMAINDER`) | início da meia hora atual | `max(0, FEE1.steps − Σ meias horas fechadas)` | 0 |
| 2 (`MODE_DAY_TOTALS`) | início do dia + 1 s (`DAY_TOTALS_OFFSET_MS`) | 0 | as do relógio (`FEE1` hoje; `33 01` ontem) |

- **Hoje**: as meias horas `i ≥ idx` do histórico são ignoradas, como a SDK (`sdk:j/q0.java:31-49`). O dia "tem passos" se a soma das fechadas é maior que 0 ou se algum total é maior que 0; senão não sai amostra nenhuma (um relógio sem uso não cria dias zerados). Com passos: uma amostra `mode 0` por meia hora `i < idx`, zeros inclusive (para sobrescrever valores velhos do store), e, **só com os totais de `FEE1` lidos**, a meia hora atual (`mode 1`) e a amostra de totais (`mode 2`). Sem `FEE1`, um resto 0 sobrescreveria a meia hora atual (ADR 28).
- **Ontem**: se a soma é maior que 0 ou há `ydTotals` diferente de zero, as 48 meias horas (`mode 0`) e, só com `ydTotals`, a amostra de totais de ontem. Sem `33 01`, a amostra de totais que já está no store (gravada pela última leitura feita ontem) continua valendo, porque a mescla é por instante (ADR 16).
- **Agregação**: o perfil usa `HOURLY_SUM`. O dia soma os passos das meias horas (com o resto) mais as calorias e a distância da amostra de totais; hoje, isso dá exatamente o `FEE1` em passos, metros e kcal. Exemplo: agora 15:43 → `idx` = 31; a meia hora 31 = `FEE1` − Σ(0..30).

**Passos ao vivo** (`FEE1` notificado, cerca de uma vez por segundo durante a caminhada, `walk@106148` a `@136148`):

- Antes do handshake: só DEBUG. Totais zerados: ignorados, com ou sem estado (o P85 manda `FEE1` zerado logo depois do acerto de hora de toda conexão, `pair-sync@7148`; tratá-lo pediria uma releitura inteira a cada conexão; ADR 28).
- Sem o estado das meias horas desta conexão (nenhum `readSteps` ainda, ou um que falhou) ou com o dia mudado: um único `DriverNotice.DataChanged`, e os `FEE1` seguintes não pedem de novo até um `readSteps` terminar ou `CrpDriver.REFRESH_RETRY_MS` (30 s) passar. A sessão espera 2 s sem avisos e faz um `RefreshSteps` interno; como o debounce reinicia a cada aviso e, andando, `FEE1` chega cerca de uma vez por segundo (`walk@114170` a `@124147`, nenhum intervalo de 2 s), sem a trava a releitura só sairia depois da caminhada. Uma releitura que falha (página `59` sem resposta) segura os pedidos pelos mesmos 30 s, para não reler a cada rajada. Durante um `readSteps` em andamento, o aviso é ignorado (a leitura monta o estado). ADR 28.
- Com o estado: `CrpStepsMapper.liveUpdate`. Se a meia hora mudou (`idx > state.index`), a anterior fecha com o último resto (aproximação até a próxima leitura do histórico) e as puladas ficam 0; o resto novo é `max(0, passos − Σ fechadas)`. Saem a meia hora atual (`mode 1`) e a amostra de totais (`mode 2`) num `DriverNotice.StepsUpdated`.
- A sessão guarda a amostra mais nova de cada instante e faz, no máximo uma vez a cada 2 s (`SessionEngine.LIVE_STEPS_THROTTLE_MS`), um `SessionCommand.ApplySteps` pela fila: mescla e `onLiveSteps(NO_REQUEST_ID, hoje)`. Um debounce que reiniciasse a cada aviso nunca dispararia durante a caminhada (`CrpSessionTest.liveFee1UpdatesTodayAtMostEveryTwoSeconds`, `firstLiveNotificationWithoutStateTriggersARefresh`).

O Condor de produção mostrou zeros durante a atividade da captura (a caminhada de `walk`): o Core Android só aceita a primeira notificação de `FEE1` depois de cada sync (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/CRPService.java:1418-1452`). As atividades em si (`B2`, o registro final de 77 passos e 4 kcal) ficam para a fase 3.

## 9. Sono

**`readSleep`** (`CrpDriver.readSleep`): `32` (aceita `32` ou `B6 01 00`; sem resposta, WARN e segue sem o sono de hoje); depois, com o legado não recusado, `33 03` (sem resposta: legado desligado, e o `33 04` não sai) e `33 04` (sem resposta: WARN). Na captura, o `32` voltou vazio (`pair-sync@57733`): o relógio sem uso não tinha sono; nenhum `B6` foi respondido.

**Data de sono** (`CrpSleepAssembler.sleepDate`): a da SDK, hoje − d (d = 0 no `32`, 1 no `33 03`, 2 no `33 04`), mais 1 dia se agora for 20h ou mais (`sdk:m/a.java:3051-3058, 3118-3128`; a regra `≥ 20` é `sdk:j/o0.java:169-173`). Ela vira o `dayKey` da sessão: o dia em que se acorda. Uma noite parcial lida às 22h fica com o `dayKey` de amanhã e só aparece nas consultas quando o dia chega, já trocada pela noite inteira (a mescla é pelo início da sessão; ADR 29).

**Montagem** (`CrpSleepAssembler.assemble`, igual a `sdk:j/o0.java:24-96`):

1. Referência inicial `(0, 0)`, estado anterior nenhum. No formato legado a hora vem `% 24`; no `B6`, não.
2. Para cada registro: `d = (h' − hAnterior) * 60 + m − mAnterior`, com `h' = h + 24` se `hAnterior > h`. Com `d < 0`, o registro é ignorado e não move a referência. O primeiro é sempre aceito, porque é medido desde 00:00 (`sdk:j/o0.java:30-31, 40`).
3. O primeiro registro aceito fixa o início: `hora:minuto` de `wakeDay − 1` se a hora for 20 ou mais, senão de `wakeDay`. Um primeiro registro com hora acima de 23 ou minuto acima de 59 é pulado com WARN (a SDK montaria a hora mesmo assim).
4. Cada registro seguinte fecha um estágio do estado anterior, de `cursor` a `cursor + d` minutos. O último registro só fecha o anterior (o estado dele não conta).
5. Estado 0 → `AWAKE`, 1 → `LIGHT`, 2 → `DEEP`, 3 → `REM`; outro → `AWAKE`, com WARN. Estágios de 0 min saem. Sem estágio nenhum → sem sessão.

**Totais**: `totalMinutes = leve + profundo + REM`, sem o acordado, como a SDK (`sdk:j/o0.java:52`) e o Core, que grava esse total para o app Condor (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/CRPService.java:2018-2041`); `awakeMinutes` é a soma do acordado; `remMinutes` só conta o estado 3 que o relógio manda. O REM que a SDK fabrica quando o relógio não manda REM (`sdk:j/k0.java:13-101`, só nas respostas `0x32` e `0x33`) **não** é reproduzido.

**Vetor da fase 0** (`CrpSleepAssemblerTest`, `CrpFrameGoldenTest.phaseZeroSleepVectorsDecodeToTheSameRecords`): `01 17 05 02 17 32 01 01 0A 00 06 1E` = (leve, 23:05), (profundo, 23:50), (leve, 01:10), (acordado, 06:30): leve 45 + 320 = 365 min, profundo 80, total 445, das 23:05 de `wakeDay − 1` às 06:30 de `wakeDay`. A mesma noite no formato `B6 01 00` é `71 15 72 C9 11 28 60 78` e dá o mesmo resultado.

## 10. Bluetooth clássico

O P85 tem chamadas e música pelo Bluetooth clássico (BR/EDR) **no mesmo endereço do BLE**, e o Core de produção cria o vínculo do sistema depois de conectar (`core@v4.3.57:android/src/main/java/com/reactlibrary/devices/DeviceFeatures.kt:41-46`, o P85 é `CLASSIC_BT`). Na captura:

- **Classe e pareamento**: CoD `0x240404` (classe principal áudio/vídeo, secundária headset vestível; serviços Audio e Rendering), SSP com IO capability NoInputNoOutput, ou seja, sem PIN e sem comparação de números; o Android mostra só o diálogo de consentimento.
- **Primeira tentativa** (HCI 15:43:08.248): a conexão clássica caiu com o motivo `0x06` (PIN or Key Missing), e o app Condor mostrou um toast de erro. O app derrubou o LE (conexão 10, 15:43:01–15:43:09) e reconectou (conexão 11, 15:43:12.209).
- **Segunda tentativa** (HCI 15:43:15–15:43:18): o diálogo nativo apareceu, o usuário aceitou e veio a link key tipo 4 (combinação não autenticada, P-192). O `F9 01 01 xx` do relógio foi de 00 (`pair-sync@3172`) para 01 (`@7070`) quando o vínculo fechou.
- **Perfis**: SDP, RFCOMM (HFP), AVDTP (A2DP), AVCTP (AVRCP) e HID (PSM `0x11`/`0x13`). Logo depois do vínculo, foi o próprio Android que abriu os perfis, em menos de 1 s (HCI 15:43:18); o app não precisou conectar HFP nem A2DP (o Core de produção agenda isso 2 s depois, com até 3 tentativas, `core@v4.3.57:android/src/main/java/com/crp/bluetooth/connectionmanager/CRPBleConnectHelper.java:220-279`).
- **Reconexão** depois de desligar e religar o Bluetooth do telefone: foi o relógio que abriu a conexão clássica, com a chave guardada (o `F9 01 01 01` chega em `bt-reconnect@1379`, antes do primeiro pedido do app). A queda da conexão clássica antes disso aparece em HCI 15:46:08.889.

**Como o Core de produção cria o vínculo**: depois do conectado da SDK, se o endereço não está em `getBondedDevices`, `startBtBonding` chama `createBond` com transporte BR/EDR (`TRANSPORT_BREDR = 1`) por reflexão (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/connectionmanager/CRPBleConnectHelper.java:664-668` → `sdk:com/crrepa/ble/conn/bond/CRPBluetoothManager.java:93-96` → `sdk:com/realsil/sdk/core/bluetooth/compat/BluetoothDeviceCompat.java:78-160`), e só segue para a hora e o evento de conectado no `BOND_BONDED` (fase 0 `CON-13`). O timeout de 12 s do estado "conectando" do Core não é cancelado durante o diálogo: se o usuário demora, o GATT é fechado e o handshake inteiro se repete, e um `BOND_NONE` faz o diálogo voltar em laço (`CRPBleConnectHelper.java:187-188, 513-518, 788-795`; fase 0, revisor).

**O que esta biblioteca faz** (ADR 30):

- O perfil tem `Quirks.classicBond = true`. No `READY`, o `CoreBridge` pergunta ao host se o vínculo existe (`CoreHost.hasClassicBond`) e, se não, pede (`CoreHost.requestClassicBond`); o resultado volta por `CoreBridge.onClassicBondResult` (`ClassicBondResult.BONDED`, `FAILED` ou `UNSUPPORTED`). `FAILED` vira `onError(-1, CLASSIC_BOND_FAILED, …, method "classicBond")`; `FAILED` e `UNSUPPORTED` não são pedidos de novo até o próximo `pairDevice`, o que evita o laço de diálogos do Core. A sessão BLE continua pronta em qualquer caso.
- No Android (host do Repo 2): `createBond(TRANSPORT_BREDR)` por reflexão, com `createBond()` de reserva, uma nova tentativa automática 1,5 s depois de uma falha (a primeira tentativa da captura falhou com `0x06`) e 45 s por tentativa. O host não abre HFP nem A2DP.
- **Esquecer**: a sessão manda `F8` (seção 6), o host remove o vínculo do sistema (`removeBond()` por reflexão no Android, como `core@v4.3.57:android/src/main/java/com/crp/bluetooth/connectionmanager/CRPBleConnectHelper.java:443-473` e `sdk:com/realsil/sdk/core/bluetooth/compat/BluetoothDeviceCompat.java:198-221`) e só então o pareamento salvo é apagado. O `disconnectCurrentDevice` não remove o vínculo. O Core de produção manda o reset de fábrica `51 02` antes do `F8` (`CRPBleConnectHelper.java:400-429`); aqui o reset fica para a fase 2.
- **iOS**: um app não cria nem remove vínculo clássico. O host iOS responde `UNSUPPORTED` com a dica de parear as chamadas em Ajustes > Bluetooth, e o `removeClassicBond` só registra a dica de esquecer o relógio lá. A SDK iOS da CRP conecta com `CBConnectPeripheralOptionEnableTransportBridgingKey` no iOS 13 ou maior (literal em `iOS-bin 0xb2e60`; fase 0 `CON-17` com a correção), que deixa o iOS levar o link LE ao BR/EDR do mesmo aparelho; o perfil tem `Quirks.iosTransportBridging = true`, e o host iOS passa essa opção no `connect`.

## 11. Diferenças entre plataformas

| Assunto | SDK Android | SDK iOS | Driver |
|---|---|---|---|
| Pedaço de escrita com MTU grande | `min(MTU, 250) − 3` arredondado a múltiplo de 4: 244 (`sdk:l/a.java:51-57`) | `min(maximumWriteValueLength(.withoutResponse), 256)` arredondado a múltiplo de 4: 256 (`iOS-bin@0xb47bc-0xb4828`) | a regra do Android nos dois hosts (244 com o P85) |
| `b2` no V3 | `0x20` | `0x30` (`iOS-bin@0x82764-0x827a8`) | `0x20`; a recepção aceita nibble 1, 2 e 3 |
| `b2` do primeiro frame | o bloco do singleton (`0x10` na primeira conexão do processo, seção 5) | também começa com `0x10` (`fe ea 10 04` em `iOS-bin` `__data` 0x1c2df8) | o bloco do MTU já trocado |
| Tipo de escrita | V1 força sem resposta (`sdk:m/f.java:76-78`); no V2/V3 vale o padrão do Android para a characteristic, sem resposta quando ela só declara isso | com resposta, salvo quando as propriedades de `FEE2` são exatamente `0x04` (`iOS-bin@0x82100-0x82154`); 0,1 s de espera antes de cada escrita (`iOS-bin@0x82094-0x820a4`) | sempre `WITHOUT_RESPONSE` (o `FEE2` do P85 só declara `0x04`) |
| MTU | pede 512 depois da versão (`sdk:e/f.java:16-52`) | o iOS negocia sozinho | a sessão pede 512 antes do handshake; o P85 abre a troca com 517 |
| Versão do protocolo | `equals` (`sdk:j/h0.java:13-20`) | `contains` (`iOS-bin@0x82548-0x8262c`) | `contains` |
| Identificação | nome do scan | `CRPDiscovery.mac`, lido do anúncio, como `deviceId` (fase 0 `CON-37`) | nome do scan; no iOS o `deviceId` é o UUID do periférico (`pendencias.md`, fase 4) |
| Vínculo clássico | o Core chama `createBond(BR/EDR)` | não há; transport bridging no `connect` | `CoreHost` por plataforma (seção 10) |

## 12. Vetores dourados

`CrpFrameGoldenTest` (`commonTest`, quatro targets) compara byte a byte. Cada TX foi conferido de forma independente contra a captura ou contra os vetores da fase 0 (calculados pela SDK, `sdk:f/b2.java:9-37`), e cada RX é o hex real da captura.

| Vetor | Fonte |
|---|---|
| TX `FE EA 20 06 5A 00` (bloco 244) e `FE EA 10 06 5A 00` (bloco 20) | `pair-sync@1542`; `walk@1196` |
| TX `FE EA 20 06 B7 0E`, `FE EA 20 07 BB 16 00` | `pair-sync@1773`, `@2025` |
| TX `FE EA 20 0A 31 6A E5 9C 17 08` para 2026-10-31T15:43:19-03:00 (e os milissegundos truncados) | `pair-sync@6821` |
| TX `FE EA 20 0A 31 6A E5 67 C0 08` para 12:00:00 do mesmo dia | fase 0 |
| TX `FE EA 20 0B BB 07 00 D0 D5 FF FF` (−10800 s) | `pair-sync@7047` |
| TX `FE EA 20 06 5A 01` | `pair-sync@98761` |
| TX `FE EA 20 06 59 00` … `59 03` | `pair-sync@56165`, `@60613`, `@58729`, `@61368` |
| TX `FE EA 20 06 5D 00`, `FE EA 20 06 5C 00` (só catálogo) | `pair-sync@56403`, `@56631` |
| TX `FE EA 20 05 32` | `pair-sync@57313` |
| TX `FE EA 20 06 33 01`, `33 03`, `33 04` | fase 0 (`sdk:f/p1.java:10-18`; `sdk:f/m1.java:20-35`) |
| TX `FE EA 20 05 F8`, `FE EA 20 06 51 02` | fase 0 (`sdk:f/r.java:7-9`; `sdk:f/x.java:21-25`) |
| TX `FE EA 20 06 17 01` (24 h), `17 00` (12 h) | `pair-sync@144792`, `@162416` |
| RX `5A 00` `MOYOUNG-V2` (e a leitura de `2A29` com o mesmo texto) → V2 | `pair-sync@1596`, `@1535` |
| RX `5A 01` `MOY-VS02-2.0.0` | `pair-sync@98835` (e `@98758`) |
| RX `59 00` com 24 meias horas zeradas | `pair-sync@56234` |
| RX `32` vazio → sem sessão | `pair-sync@57733` |
| RX `F9 01 01 00`, `F9 01 01 01`, `F9 02 01` | `pair-sync@3172`, `@7070`, `@7257` |
| `FEE1` 16/13/1 e 77/63/4 | `walk@106148`, `@136148` |
| `2A19` `0x32` → 50 %, `0x31` → 49 %, sem carga | `pair-sync@48921`, `walk@99511` |
| Sono `0x32` e `B6 01 00` da fase 0 → os mesmos 4 registros | fase 0 (`sdk:j/o0.java:24-133`) |
| `31` `6A E5 9C 17 08` → 2026-10-31 15:43:19 (ida e volta) | `pair-sync@6821` |

O `CrpReferenceFixtureTest` decodifica as três fixtures de referência inteiras: versão e firmware iguais entre leitura e resposta, as páginas `59` de um relógio sem uso, o `32` vazio, `FEE1` de 16 a 77 passos na caminhada, as notificações de bateria sem CCCD, cada `31` contra o instante do pacote, o `BB 07 00` e o `5A 00` com bloco 20 iguais ao nosso encoder, e a redação (nenhum `0x41`, `0x42`, `0x43` ou `0xB5`; todo `0x12` igual a `B2 4B 18 00`). O `CrpSyntheticReplayTest` reproduz a conversa do nosso driver com os RX e READ reais (`fixtures.md`, seção 5) e, com o relógio do replay 7 s depois do `recordedAt`, prova que o `CrpTxMasker` só mascara os segundos do `31`.

## 13. O que não se sabe e o que ficou fora

- **`0x33` e `B6` no P85**: o app de produção nunca mandou `0x33`, e nenhum `B6` foi respondido. O driver sonda o `33 01` uma vez por conexão (seção 8); se o P85 não responder, não há totais de ontem nem sono de 1 e 2 dias atrás, só o de hoje pelo `32`. A primeira gravação real pelo app de exemplo responde (`pendencias.md`).
- **Sono de verdade**: a captura só tem o `32` vazio de um relógio sem uso. A montagem e a janela das 20h seguem a SDK, sem uma noite real que as confirme.
- **Regra > 100 = carregando**: do Core, nunca vista no P85.
- **`F9`**: o significado de `F9 02 xx` é desconhecido (chega periodicamente; o `00` veio antes de uma queda).
- **`2A19` sem CCCD**: o relógio notifica a bateria sem a CCCD escrita (seção 2); não se sabe se a escrita da CCCD muda o ritmo.
- **Vínculo no iPhone**: não se sabe se o iOS pede pareamento do link LE, nem como o transport bridging se comporta com o P85; o iOS não deixa o app criar o vínculo clássico. O `deviceId` no iOS é o UUID do periférico, e o Condor de produção usa o MAC do anúncio (fase 4).
- **Esquecer**: o `F8` foi conferido contra a SDK e o Core, mas não visto no ar (a captura não tem um esquecer). Não se sabe o que o relógio faz com ele além de não responder.
- **Ficou fora da fase 1**: reset de fábrica `51 02` e formato de hora `17` (só catálogo, fase 2), perfil `0x12`, metas `B9`, atividades `B2` e o registro de treino, frequência cardíaca `35`/`B6 02`, estresse `B9 11`, os pedidos de configuração do app (`1D`, `2D`, `1F`, `7C`, `8C`, `1A`, `71`, `7B`, `BB 08`, `7D`, `77`, `BB 00`, `94`, `42`, `67`, `68`), notificações `0x41`, clima `0x42`/`0x43`/`0xB5`, OTA e mostradores (JieLi, pelo `0x63`/`0x74` em `FEE5`/`FEE6`, fase 0 `C23`/`C24`), o `FEE7`/WeChat, `190E` e `AE00`.
