# Protocolo iDO (Mormaii Life Ultra GPS)

Este documento descreve o pedaço do protocolo BLE dos relógios iDO (VeryFit) que o driver próprio usa com o Mormaii Life Ultra GPS (plataforma SiFli 98, `gps_platform` 3): conexão GATT, frame legado, informação básica, firmware, dados ao vivo, acerto de hora, vínculo com confirmação na tela e reconexão. É o mínimo das funções básicas da PoC, com os mesmos métodos do fitGPS; o resto do protocolo (histórico V3, notificações, alarmes, EPO) fica fora.

**Fontes citadas**

| Abreviação | O que é |
|---|---|
| `20261029.log` | Log de bytes da SDK iDO 2.69.38 (`IDoBLELib-VeryFit`) dentro do app Mormaii de produção, com o mesmo Life Ultra GPS, 2026-10-29 22:07–22:12 (-03:00). Cada `send =>` é um frame escrito e cada `receive <=` um frame recebido. Citado por número de linha. **Não é versionado**: traz o MAC do relógio (inclusive na resposta `02 04`, que nunca deve ser copiada). |
| SDK decompilada | Classes Java da SDK iDO (só referência de leitura), citadas por `arquivo:linha`. A SDK não monta bytes em Java: a biblioteca nativa `libVeryFitMulti.so` (em `TechnosWareCore/android/src/main/jniLibs/`) monta e lê os frames; as funções nativas são citadas pelo nome (`protocol_write_set_cmd_key`). |
| Core atual | `TechnosWareCore` (`AvailableDevices.java`, `SDKCallbacks.java`), só leitura. |

**Código correspondente**: `protocol/src/commonMain/kotlin/br/com/grupotechnos/watch/protocol/ido/` (`IdoFrame.kt`, `IdoCommand.kt`, `IdoPayloads.kt`, `IdoDriver.kt`). Quando um byte mudar aqui, o vetor em `IdoFrameGoldenTest` muda junto, e vice-versa.

---

## 1. GATT

| Papel | UUID | Fonte |
|---|---|---|
| Serviço | `00000af0-0000-1000-8000-00805f9b34fb` | SDK decompilada, `basilisk/frost.java:8-18` |
| Escrita dos comandos normais | `00000af6-…` | idem; a SDK escolhe pelo primeiro byte: `0x08`/`0x09` vão para `0af1` (saúde), o resto para `0af6` (`connect/drizzle.java:270-289`) |
| Notificação normal | `00000af7-…` | CCCD ligado primeiro (`20261029.log` linhas 63-66) |
| Notificação de saúde | `00000af2-…` | CCCD ligado logo depois (linhas 67-70) |

- **Perfil**: `Profiles.IDO_MAIN` (`service`, `write`, `notify` e `extraNotify = [0af2]`). A sessão liga os CCCD de `notify` e de cada `extraNotify`, um de cada vez e na ordem, antes do handshake (`SessionEngine.configure`). As notificações das duas characteristics chegam ao driver: a SDK entrega as duas ao mesmo decodificador nativo e não passa adiante de qual veio (`ido/ble/drizzle.java:36-53`). A escrita de saúde (`0af1`) não é usada.
- **Tipo de escrita**: ATT Write Command (`WRITE_TYPE_NO_RESPONSE`), sempre: é o que a SDK faz em produção, porque o Core nunca chama `setIsNeedResponse` (`connect/drizzle.java:195-205`). O driver escreve com `WriteType.WITHOUT_RESPONSE`. A `0af6` declara a propriedade (`writeWithoutResponse`, seção 1.1), o que o iOS exige: o `CoreBluetooth` descarta uma escrita `.withoutResponse` numa characteristic que não a declara.
- **Um pacote em voo**: a SDK manda o próximo só depois do `onCharacteristicWrite` (ou de 3 s), inclusive para Write Command (`connect/drizzle.java:51-81`). No Android esse callback também vem para escrita sem resposta, então a fila GATT do host (`GattQueue`) e a espera do link da sessão fazem o mesmo ritmo.
- **MTU**: a SDK nunca pede (`requestMtu` só aparece no DFU da SiFli). O perfil usa `requestMtu = 23`, e a sessão não pede MTU quando o perfil pede até 23. O Android 14 ou mais novo negocia um MTU maior sozinho, e o iOS também (515 no iPhone, seção 1.1); não muda nada aqui, porque todo frame legado tem no máximo 20 bytes.
- **Advertising**: o scan de produção viu o nome `Life Ultra GPS` (linha 74). O catálogo do Core atual lista `Smart Watch 6010` como nome BLE e `Life Ultra GPS` como `appName` (`AvailableDevices.java:39`); o perfil aceita os dois nomes (`DeviceRegistry.identify`, comparação exata). No iPhone, o anúncio não trouxe nenhum UUID de serviço (log de texto do app de exemplo, ts 1793363703643): um filtro de scan pelo serviço `0af0` não acha o relógio; o nome acha, como no fitGPS.
- **Antes do primeiro comando** a SDK faz `refresh` do cache GATT, espera 1 s, descobre os serviços, espera 100 ms e liga os dois CCCD (linhas 57-70). A sessão não faz `refresh` nem as esperas (`docs/decisoes.md`, ADR 22).

### 1.1 O que o iPhone mostrou (2026-10-30)

Fonte: o log de texto do app de exemplo em Release no iPhone, depois de o usuário esquecer o vínculo antigo nos Ajustes (`docs/decisoes.md`, ADR 23), e a gravação `fixtures/real/lifeultragps/1.1.1/ios-pair-info-time-steps-sync.jsonl`. O log fica fora do git (traz o nome do iPhone e aparelhos vizinhos) e é citado pelo `ts`.

A descoberta (ts 1793363706739 e 1793363706740) devolveu dois serviços, com as propriedades que o `CoreBluetooth` informa:

| Serviço | Characteristic | Propriedades | Uso |
|---|---|---|---|
| `00000af0-…` | `00000af6-…` | read, write, writeWithoutResponse | escrita dos comandos (perfil) |
| | `00000af7-…` | read, notify | notificação normal (perfil) |
| | `00000af1-…` | read, write, writeWithoutResponse | escrita de saúde, não usada |
| | `00000af2-…` | read, notify | notificação de saúde (`extraNotify`) |
| `00000000-0000-0000-6473-5f696c666973` | `00000000-0000-0100-6473-5f696c666973` | read, write, writeWithoutResponse | não usada |
| | `00000000-0000-0200-6473-5f696c666973` | read, write, writeWithoutResponse, notify | não usada |

- **`0af6` aceita escrita sem resposta**: era a pergunta aberta do host iOS (seção 8). As 12 escritas da gravação saíram sem resposta e todas foram respondidas. A `0af6` aceita também escrita com resposta, que a SDK não usa.
- **O segundo serviço** não aparece na SDK Java nem no log de produção. Os 8 últimos bytes do UUID, `64 73 5f 69 6c 66 69 73`, são o ASCII `ds_ilfis`, que lido de trás para frente é `sifli_sd`; o relógio é da plataforma SiFli (98, seção 3.1). **Hipótese, não confirmada**: um serviço da SiFli (OTA/DFU ou serial do fabricante), com os bytes do UUID gravados em little-endian. A sessão o ignora; não se escreve nada nele.
- **MTU**: o `didConnect` deu 515, ou seja, `maximumWriteValueLength(.withoutResponse)` = 512 (ts 1793363706261): o iOS negociou sozinho um MTU grande, que o relógio aceitou (o fitGPS, no mesmo iPhone, ficou em 23). A sessão não pediu MTU (`MTU not requested (profile asks for 23); using 23`, ts 1793363706978) e manteve os frames de até 20 bytes; o relógio respondeu com os mesmos tamanhos do Android (12 a 20 bytes). O 515 ficou no cabeçalho da gravação, sem efeito no replay (`fixtures.md`, seção 5).
- **CCCD**: `0af7` e depois `0af2`, os dois com status 0 (ts 1793363706858 e 1793363706977), 0,24 s depois da descoberta.
- **Tempos**: do `connect` ao `didConnect`, 1,0 s; do `didConnect` ao primeiro `02/01`, 0,7 s, sem as esperas da SDK, e o relógio respondeu em 60 ms.
- **Vínculo do sistema**: o iPhone guardava um vínculo do sistema (bond com LTK) de um pareamento antigo pelo app de produção, e o relógio, pareado depois com um Android, tinha apagado as chaves do iPhone (`CBError.peerRemovedPairingInformation`, ADR 23). Ou seja, o par iPhone + app de produção criou um bond com este relógio. Não se sabe se o iOS mostrou o diálogo de pareamento do sistema no pareamento novo, que deu certo na primeira tentativa (seção 8 e `pendencias.md`).

## 2. Frame legado

```
[cmd][key][payload...]        no máximo 20 bytes, uma escrita GATT
```

- Sem tamanho, sequência ou CRC. A biblioteca nativa zera um buffer de 20 bytes, põe `cmd` e `key` e copia o payload; mais de 20 é erro (`protocol_write_set_cmd_key`, limite `PROTOCOL_TX_MAX_SIZE`). O telefone escreve só os bytes úteis (`02 01` tem 2 bytes, linha 96).
- A resposta repete `[cmd][key]` nos dois primeiros bytes. O driver casa a resposta com o pedido em andamento por esse par (o mesmo `cmd` com outra `key` não serve). O pedido é registrado antes da escrita, porque a resposta pode chegar antes do callback dela: o segundo `02 01` saiu às 22:08:22.013 (linha 96), a resposta chegou às .068 (linha 120) e o `onCharacteristicWrite`, às .069 (linha 121). O `IdoSessionTest` cobre os dois casamentos (`paddedRepliesAndInterleavedNoiseAreHandled`, `repliesArrivingBeforeTheWriteConfirmationAreKept`).
- **Recepção completada com zeros**: um pacote com menos de 20 bytes é completado com zeros antes do parse, como a SDK faz (`ido/ble/drizzle.java:36-58`). Por isso os campos são lidos por offset, contados a partir do `cmd`. O relógio manda tamanhos variados: `02 eb` com 12 bytes (linha 5776), `04 01` com 17 (linha 146), `02 a0` com 19 (linha 5835), `02 01`, `03 01` e `04 05` com 20.
- **V3**: pacotes que começam com `0x33` são do protocolo V3 (JSON longo, `33 DA AD DA AD 01 <len> <cmd_id> <seq> <payload> <crc16>`, linha 330). A SDK não os completa; o driver os descarta, junto com qualquer frame legado que não responda ao pedido em andamento (log em DEBUG).
- Inteiros multibyte em **little-endian**.
- `IdoFrameCodec.describe` resume cada pacote para o log (`02/01 GET_BASIC_INFO len=20`).

## 3. Comandos

Catálogo em `IdoCommand`. Leituras e acerto de hora esperam a resposta em `commandTimeoutMs` (4 s) e têm uma retentativa em timeout (a fila da SDK reenvia até 3 vezes); bind e autenticação não têm retentativa.

### 3.1 `02/01` informação básica (`GET_BASIC_INFO`)

Pedido `02 01` (linha 88). Resposta (linha 95), com os nomes do JSON que a SDK imprimiu (linhas 98-116):

```
02 01 66 20 01 00 00 0e 01 00 01 f3 62 02 02 01 00 01 01 03
```

| Byte | Campo | Valor no log |
|---|---|---|
| 2-3 | `deivceId` (u16 LE) | `0x2066` = 8294 |
| 4 | `firmwareVersion` | 1 |
| 5 | `mode` | 0 |
| 6 | `battStatus` (0 normal, 1 carregando, 2 cheia, 3 baixa) | 0 |
| 7 | `energe`: bateria em % | 14 |
| 8 | `pairFlag` (1 = vinculado) | 1 |
| 9 | `reboot` | 0 |
| 10-11 | `cloud_clock_dial_version` 1, `bind_confirm_method` 3, `bind_confirm_timeout` 240, empacotados (não lidos) | `01 f3` |
| 12 | `platform` | 98 (SiFli) |
| 13 | `shape` | 2 |
| 14 | `dev_type` | 2 |
| 19 | `gps_platform` | 3 |

`IdoBasicInfo.decode`. A bateria do `readBattery` é o byte 7; o comando de bateria próprio da SDK (`02 05`) não é necessário.

### 3.2 `02/EB` firmware (`GET_FIRMWARE`)

Pedido `02 eb` (linha 5774). Resposta `02 eb 01 01 01 00 00 00 00 00 00 02` (linha 5776): `firmware_version1..3` nos bytes 2, 3 e 4 → **`"1.1.1"`** (`IdoFirmwarePayload.decode`). Os bytes 5-11 levam `BT_flag`, `BT_version1..3` e `BT_match_version1..3` (todos 0, menos `BT_match_version3` = 2, linhas 5777-5788); a ordem exata não foi conferida e o driver devolve `bleVersion = null`. O Core atual formata a versão como `1.01.01` (`SDKCallbacks.java:1022-1025`); a PoC usa `1.1.1`.

### 3.3 `02/A0` dados ao vivo (`GET_LIVE_DATA`)

Pedido `02 a0` (linha 5833). Resposta (linha 5835):

```
02 a0 1a 00 00 00 0d 00 00 00 19 00 00 00 00 00 00 00 8e
```

| Byte | Campo | Valor |
|---|---|---|
| 2-5 | `totalStep` (u32 LE) | 26 |
| 6-9 | `totalCalories` (kcal totais: repouso + atividade) | 13 |
| 10-13 | `totalDistances` (m) | 25 |
| 14-17 | `totalActiveTime` | 0 |
| 18 | frequência cardíaca (não lida) | `0x8e` |

São os acumuladores do dia no relógio: a tela mostrava 26 passos, 0,02 milha (25 m = 0,016 milha) e 0 caloria na mesma hora. `IdoLiveData.decode`.

**As calorias não são as da tela.** O byte 6 é a caloria total do dia, com a de repouso: o sync V3 do mesmo dia deu `total_activity_calories` 0 e `total_rest_activity_calories` 13 (linhas 3891 e 3893), e 13 = 13 + 0. A tela mostra só a caloria de atividade, e o `02/A0` não tem campo para ela (o `LiveData` da SDK tem só `totalStep`, `totalCalories`, `totalDistances`, `totalActiveTime`, `heartRate`, `sbp` e `dbp`). O driver repassa o total como veio, como o caminho ao vivo do Core de produção (`SDKCallbacks.java:348`, `emitOnCalories(liveData.totalCalories)`); o histórico do Core guarda a de atividade (`HelpersFunctions.java:373`, `total_activity_calories`), que só vem do V3, fora da PoC. Na conferência com o relógio, passos e distância batem com a tela; as calorias, não.

### 3.4 `03/01` acerto de hora (`SET_TIME`)

```
03 01 <ano u16 LE> <mês> <dia> <hora> <minuto> <segundo> <semana> 00 00 00 00 <fuso u16 LE>
```

- **Semana**: segunda = 0 … domingo = 6, como o `SystemTime` que a própria SDK monta (`com.ido.ble.common.ember.acorn(boolean)`, decompilado: domingo vira 6 e os outros dias `DAY_OF_WEEK - 2`). O relógio não prova a convenção: logo depois do vínculo foram duas quintas-feiras com o fuso inteiro, `… 18 03 …` (linha 182, a da SDK, linha 176) e `… 18 04 …` (linha 186, a do `setTime` do Core de produção, linha 172, que conta de domingo = 0, `SDKCallbacks.java:596`), e as duas tiveram `03 01 00`. Quem decide é o dia da semana na tela depois do acerto (`pendencias.md`, caixa **Hora** do Life Ultra GPS).
- **Fuso** com `v2_support_set_time_zone_float` (verdadeiro neste relógio, linha 931): centésimos de hora, `offsetMillis / 36000`; um valor negativo vira `|valor - 1200|`. São Paulo (-3 h): -300 → 1500 = `dc 05`. Sem esse recurso a SDK manda horas inteiras, negativo como `|horas - 12|` (-3 → 15 = `0f 00`).
- Log: `SystemTime{year=2026, month=10, day=29, hour=22, minute=8, second=49, week=3, time_zone=1500}` (linha 5355) virou `03 01 ea 07 0a 1d 16 08 31 03 00 00 00 00 dc 05` (linha 5360), uma quinta-feira; a resposta `03 01 00 …` (linha 5362) é sucesso (byte 2 = 0). Antes de ler a tabela de funções, logo depois do vínculo, a SDK mandou o fuso inteiro (`… 18 03 00 00 00 00 0f 00`, linha 182), também aceito (a linha 186 repete com semana `04`; ver **Semana**).
- `IdoTimeCodec`; o driver usa sempre o fuso fracionário (`IdoDriver.FRACTIONAL_TIME_ZONE`), porque o Life Ultra GPS é o único perfil iDO. A sessão manda o acerto de hora sozinha `postLoginDelayMs` (1 s) depois do READY; a SDK manda 25 ms depois do vínculo (linhas 165-182).

## 4. Vínculo e reconexão

### 4.1 `04/01` bind

Pedido fixo (linha 144):

```
04 01 f1 01 01 02 02 01 00
```

A biblioteca nativa monta `04 01 F1 01 <bind_version> <os_type> <os_version> <is_clean_data> 00` com o `BindPara` do Core (`bind_version` 1, `os_type` 2 = Android, `os_version` 2, `is_clean_data` 1). O driver manda esses bytes (`IdoBindPayload.REQUEST`).

Resposta (linha 146): `04 01 00 0c 00 31 bc c3 54 3f 1c e0 3e 1c 85 03 7c`

| Byte | Campo |
|---|---|
| 2 | `bind_ret_code`: 0 sucesso, 2 já vinculado; outro valor é recusa |
| 3 | `auth_length`: 12 quando o relógio pede a autenticação |
| 4 | `encrypted_version` |
| 5-16 | `encrypted_data`: o desafio `E` de 12 bytes |

### 4.2 `04/05` autenticação (o embaralhamento)

Não há chave. A resposta ao desafio é um embaralhamento fixo dos 12 bytes (`protocol_data_encrypted_auth_gt01`):

```
04 05 0c <encrypted_version> E[6..11] (E[0..5] XOR E[6..11])
```

Com o desafio do log, `E = 31 bc c3 54 3f 1c | e0 3e 1c 85 03 7c`:

| Saída | Conta |
|---|---|
| `e0 3e 1c 85 03 7c` | `E[6..11]` |
| `d1 82 df d1 3c 60` | `31^e0`, `bc^3e`, `c3^1c`, `54^85`, `3f^03`, `1c^7c` |

O que dá `04 05 0c 00 e0 3e 1c 85 03 7c d1 82 df d1 3c 60`, igual ao envio da linha 154 (`IdoAuth.reply`). O relógio mostra a tela de confirmação e só responde depois do toque: `04 05 00 …` (`auth_code` 0, sucesso) chegou 1,9 s depois (linha 158; o JSON da SDK, linhas 159-163; `bind ok!`, linha 165). Com o driver próprio, o toque levou 1,5 s no Android e 1,4 s no iPhone (`atMs` 181 → 1672 de `pair-info-time-steps-sync.jsonl` e 119 → 1528 de `ios-pair-info-time-steps-sync.jsonl`), cada um com um desafio diferente. O relógio espera até 240 s (`bind_confirm_timeout`, linha 101); o perfil espera 90 s (`bindTimeoutMs`), pelo `04/01` e pelo `04/05`.

### 4.3 Primeiro pareamento (`HandshakeMode.FIRST_PAIR`, fase `BIND`)

1. `02/01`: só para o log (bateria, `pairFlag`, plataforma).
2. `04/01`: `bind_ret_code` 2 encerra com sucesso (a SDK trata como vínculo feito); outro diferente de 0 é `HANDSHAKE_FAILED`; 0 sem desafio (`auth_length` < 12, iDO antigo) também encerra com sucesso.
3. `04/05` com a resposta ao desafio; espera a confirmação na tela. `auth_code` diferente de 0 ou timeout é recusa (`HANDSHAKE_FAILED`, pela sessão).

Depois de uma recusa o relógio não aceita outro bind no mesmo link; a sessão já fecha o link quando o pareamento falha, e um `pair` novo abre outro.

Observação: o `pairFlag` já era 1 antes do bind (linha 110), e mesmo assim o relógio pediu a autenticação e a confirmação. Não se sabe se o `pairFlag` 1 indica um vínculo anterior com outro telefone; por isso o primeiro pareamento sempre manda o bind, sem olhar o `pairFlag`.

### 4.4 Reconexão (`HandshakeMode.RECONNECT`, fase `LOGIN`)

Só `02/01`. Este relógio não pede autenticação ao reconectar: `ex_table_main8_encrypted_auth` e `support_encrypted_code_resend_04_05` são falsos na tabela de funções (linhas 592 e 789), e a SDK registrou `encryptedAtConnected, is not bind, not need` (linhas 71-72). Se o `pairFlag` vier 0, o relógio desfez o vínculo do lado dele: o driver falha com `HANDSHAKE_FAILED` (como o `onDeviceInNotBindStatus` da SDK), e a sessão vai para `DISCONNECTED` sem reconectar sozinha (ADR 11); só um `pair` refaz o vínculo. A reconexão nunca manda `04/01`.

Nota: a SDK guarda o desafio do bind (`setEncryptedAuth`, linha 150) para reenviar o `04/05` a cada conexão nos modelos que pedem; este não pede, e o driver não guarda nada entre conexões.

## 5. Mapa para o `DeviceDriver`

| Método | Bytes | Resultado |
|---|---|---|
| `handshake(FIRST_PAIR)` | `02/01`, `04/01`, `04/05` | fase `BIND`; seção 4.3 |
| `handshake(RECONNECT)` | `02/01` | fase `LOGIN`; seção 4.4 |
| `readDeviceInfo` | `02/01`, `02/EB` | firmware `"1.1.1"`, `bleVersion` `null` |
| `readBattery` | `02/01` | `energe` (byte 7), limitado a 0-100 |
| `setTime` | `03/01` | fuso fracionário, semana com segunda = 0 |
| `readSteps` | `02/A0` | uma amostra no início da hora local corrente com o acumulado do dia, `mode` 0 (seção 6) |
| `readSleep` | nenhum | lista vazia (o sono vem do V3) |
| `enableDataChangeNotices` | nenhum | sem efeito |
| `unbind` | nenhum | falha sem escrever (seção 8); o `disconnect(forget = true)` segue e apaga o estado local |
| `notices` | — | vazio |

## 6. Passos

O total de passos do dia vem do `02/A0` ao vivo. O `refreshSteps` e o `syncHistory` leem o mesmo comando, e o driver devolve uma amostra só, no início da hora local corrente, com os totais do dia: relida na mesma hora, ela substitui a anterior (o `HistoryStore` mescla pelo instante); numa hora nova, entra outra amostra. O perfil soma os dias em `DAY_CUMULATIVE` (a amostra de maior número de passos do dia), então `onLiveSteps`, `getStepsDays` e `onPedometer` dão o total que a tela mostra (26 no log). O histórico por hora que o app de produção mostra vem do sync V3 da SDK (`SyncV3HealthTask`, linhas 2914-2943), fora da PoC: um dia em que o telefone não leu o relógio fica sem amostra.

## 7. Vetores dourados

`IdoFrameGoldenTest` (`commonTest`, quatro targets) compara byte a byte:

| Vetor | Linha do log |
|---|---|
| TX `02 01` | 88 |
| TX `04 01 f1 01 01 02 02 01 00` | 144 |
| TX `04 05 …` calculado do desafio da linha 146 | 154 |
| TX `03 01 …` para 2026-10-29 22:08:49 em São Paulo (fuso fracionário) | 5360 |
| TX `03 01 …` com fuso inteiro (22:08:24) | 182 |
| TX `02 eb`, `02 a0` | 5774, 5833 |
| RX informação básica: bateria 14, `pairFlag` 1, `deviceId` 8294, plataforma 98, `gps_platform` 3 | 95 |
| RX bind: código 0, `auth_length` 12, desafio | 146 |
| RX autenticação 0 (e 1 = recusa) | 158 |
| RX acerto de hora aceito | 5362 |
| RX firmware `1.1.1` | 5776 |
| RX dados ao vivo 26 passos, 13 kcal totais, 25 m | 5835 |
| V3 do mesmo dia: 0 kcal de atividade, 13 de repouso | 3891, 3893 |

A gravação `fixtures/real/lifeultragps/1.1.1/pair-commands.jsonl` junta essas trocas na ordem em que a sessão as faz e roda num replay estrito pela `WatchSession` (`IdoRealFixturesReplayTest`); ela foi reconstruída do log de bytes da SDK, não do rádio (`fixtures.md`, seção 5). As gravações do rádio pelo driver próprio, uma no Android e uma no iPhone (`pair-info-time-steps-sync.jsonl` e `ios-pair-info-time-steps-sync.jsonl`), rodam no mesmo teste. O XOR, a fórmula do fuso e a convenção da semana passaram por teste de mutação: trocar cada um derruba cinco ou seis testes.

## 8. O que não se sabe e o que ficou fora

- **Bytes do desvínculo.** A SDK monta o unbind na biblioteca nativa (evento 201) e o log não o capturou. O driver não inventa bytes: `unbind` falha sem escrever, o `forget` apaga o estado local e desconecta, e o relógio continua se dizendo vinculado até um bind novo (que pede a confirmação de novo). Captura pendente (`pendencias.md`).
- **Histórico V3** (passos por hora, sono, frequência cardíaca): fluxo de várias etapas em JSON com CRC-16/CCITT-FALSE, fora da PoC.
- **Vínculo BLE da SiFli** (bonding/criptografia do link): o log não mostra `createBond`, e o relógio respondeu sem pareamento do sistema. Se um firmware exigir, o host Android vai ver falha de escrita ou queda. No iPhone, porém, havia um bond do sistema com este relógio, criado no pareamento antigo pelo app de produção (seção 1.1; ADR 23): alguém pediu criptografia do link, o relógio ou a SDK iOS. Falta saber se o relógio pede criptografia por conta própria, o que se vê pelo diálogo de pareamento do sistema no iPhone ou por um `createBond`/`ACTION_BOND_STATE_CHANGED` no Android (`pendencias.md`).
- **`pairFlag` antes do bind** (seção 4.3), bytes 10-11 da informação básica, ordem dos campos BT3 do `02/EB` e o byte 18 do `02/A0`: não lidos.
- **Canal de saúde** (`0af1`/`0af2`, comandos `0x08`/`0x09`): só a notificação é ligada.
- **iOS**: respondido no iPhone em 2026-10-30. A `0af6` declara escrita sem resposta, e o host iOS do Repo 2 escreve assim, no ritmo do `canSendWriteWithoutResponse`; as 12 escritas da gravação foram respondidas (seção 1.1).
