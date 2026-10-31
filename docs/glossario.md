# Glossário

Termos usados no código, nos KDocs e nos outros documentos. Quando o termo tem um tipo ou uma constante no código, o nome aparece entre crases. Detalhes de protocolo ficam em [`protocolo-sma.md`](protocolo-sma.md), [`protocolo-ido.md`](protocolo-ido.md) e [`protocolo-crp.md`](protocolo-crp.md); de sessão, em [`arquitetura.md`](arquitetura.md).

## BLE e GATT

| Termo | Significado |
|---|---|
| **BLE** | Bluetooth Low Energy. O relógio é o periférico; o telefone é o central. |
| **GATT** | Generic Attribute Profile: o relógio expõe **serviços**, cada um com **characteristics** identificadas por UUID. O telefone escreve numa characteristic e recebe notificações de outra. |
| **ATT** | Attribute Protocol, a camada abaixo do GATT. Cada operação GATT vira um pacote ATT; o cabeçalho ATT de uma escrita ocupa 3 bytes do MTU. |
| **UUID curto / longo** | Serviços padrão do Bluetooth têm forma curta (`FF60`) dentro da base `0000xxxx-0000-1000-8000-00805f9b34fb`. A biblioteca compara sempre a forma de 128 bits em minúsculas (`GattUuid.normalize`). |
| **MTU** | Maximum Transmission Unit do ATT. Sem negociação vale 23, o que deixa 20 bytes úteis por escrita. A sessão pede `Quirks.requestMtu` (247) e cai para 23 se o host não responder (`docs/decisoes.md`, ADR 5). Com o fitGPS 0.2.1 no Android, a negociação devolveu 23 com status 0. O Condor Reebok abre a troca sozinho, com 517, logo depois da conexão (`protocolo-crp.md`, seção 2). |
| **CCCD** | Client Characteristic Configuration Descriptor (`0x2902`). Escrever `01 00` nele liga as notificações da characteristic; no Android o host faz isso dentro de `Transport.enableNotifications`. |
| **Notify / notificação** | Mensagem que o relógio manda sem pedido, numa characteristic com notificações ligadas. Não tem confirmação na camada ATT. Cada notificação chega à sessão por `TransportEvents.onNotification`. |
| **Indication** | Como a notificação, mas confirmada pelo telefone na camada ATT. O SMA não usa; o `hci2jsonl.py` aceita as duas. |
| **Write request** | Escrita ATT com confirmação do relógio (`WriteType.WITH_RESPONSE`). É o que o driver SMA usa em todo frame. |
| **Write command** | Escrita ATT sem confirmação (`WriteType.WITHOUT_RESPONSE`). É o que os drivers iDO e CRP usam em todo frame (no CRP, o `FEE2` só declara essa propriedade); no Android o `onCharacteristicWrite` vem mesmo assim e dá o ritmo. |
| **Leitura GATT (ATT Read)** | O telefone pede o valor de uma characteristic e o relógio responde (`Transport.read`, `TransportEvents.onReadCompleted`). O CRP lê a versão do protocolo (`2A29`), o firmware (`2A28`), a bateria (`2A19`) e os passos do dia (`FEE1`); cada leitura bem-sucedida entra na gravação como `READ` (ADR 27). |
| **NUS** | Nordic UART Service, `6e400001-…` (write `6e400002`, notify `6e400003`). É o serviço que o Core iOS usa com o fitGPS e o único serviço de dados que o fitGPS 0.2.1 expõe. O relógio não o anuncia no advertising. |
| **FF60 ("ROUND")** | Serviço próprio da SMA (write `FF61`, notify `FF62`). A SDK Android prefere este quando existe; o fitGPS 0.2.1 não o expõe (hipótese H6 refutada para esse firmware, `protocolo-sma.md`, seção 1). |
| **Advertising / anúncio** | O pacote que o relógio transmite para ser achado no scan, com o nome e uma lista de serviços. O fitGPS 0.2.1 anuncia `Mormaii_fitGPS` e os serviços `180f`, `180a` e `00000001-…`, sem o NUS; por isso a identificação é só pelo nome (`DeviceRegistry.identify`). |
| **GattCandidate** | Um trio serviço + write + notify (mais as `extraNotify` e as `sideNotify`, se houver) que o driver sabe usar. O perfil lista os candidatos em ordem de prioridade e a sessão usa o primeiro que a descoberta encontrar inteiro. |
| **autoConnect** | Modo de conexão do Android em que o sistema mantém uma conexão pendente até o relógio aparecer. É o `Transport.connect(autoReconnect = true)` da reconexão. |
| **Bond / vínculo do sistema** | O pareamento do sistema operacional com o relógio (chaves LTK no BLE, ou a link key no Bluetooth clássico, guardadas pelo Bluetooth do telefone e do relógio), diferente do BIND/bind do protocolo. O Condor Reebok precisa do vínculo **clássico**, que o `CoreBridge` pede ao host (`CoreHost.requestClassicBond`; ADR 30); o BLE dele não pede vínculo. Se o relógio apaga as chaves (pareado com outro telefone) e o iPhone ainda as guarda, o iOS recusa toda conexão até o usuário esquecer o relógio em Ajustes > Bluetooth; app nenhum remove o bond. O host iOS entrega `GattStatus.PAIRING_REMOVED` (de `CBError.peerRemovedPairingInformation`) e a sessão termina com `WatchErrorCodes.PAIRING_REMOVED` sem reconectar. No Android o caso não foi observado, e não se sabe que status o `BluetoothGatt` entrega (`docs/decisoes.md`, ADR 23). |
| **HCI snoop / btsnoop** | Log de todo o tráfego Bluetooth do Android (`btsnoop_hci.log`, formato btsnoop H4), ligado nas Opções do desenvolvedor. Aberto no Wireshark e convertido com `scripts/hci2jsonl.py` (`docs/fixtures.md`, seção 3.2), ou lido direto, com redação, pelo `scripts/btsnoop2jsonl.py` (seção 3.3). O arquivo bruto tem os endereços dos aparelhos e nunca entra no repositório. |
| **BR/EDR (Bluetooth clássico)** | O Bluetooth de antes do BLE (Basic Rate/Enhanced Data Rate), usado para áudio e chamadas. O Condor Reebok tem os dois no mesmo endereço: BLE para o protocolo, BR/EDR para HFP e A2DP (`protocolo-crp.md`, seção 10). |
| **HFP / A2DP / AVRCP** | Perfis do Bluetooth clássico: mãos-livres (chamadas, sobre RFCOMM), áudio estéreo (música, sobre AVDTP) e controle remoto de mídia (sobre AVCTP). O Android os abre sozinho logo depois do vínculo clássico com o P85. |
| **SSP** | Secure Simple Pairing, o pareamento do Bluetooth clássico. Com IO capability NoInputNoOutput (o P85), não há PIN nem comparação de números: o Android só mostra um diálogo de consentimento. |
| **CoD** | Class of Device, os 24 bits que o aparelho clássico anuncia. O P85 anuncia `0x240404`: classe principal áudio/vídeo, secundária headset vestível, com os serviços Audio e Rendering. |
| **Link key** | A chave do vínculo clássico que o telefone e o relógio guardam depois do SSP. O tipo 4 (combinação não autenticada, P-192) é o do P85 na captura; com ela guardada, o próprio relógio reabre a conexão clássica. |
| **CTKD** | Cross-Transport Key Derivation: derivar a chave de um transporte (BLE ou BR/EDR) a partir da do outro, num aparelho que tem os dois no mesmo endereço. A captura do P85 não mostrou SMP no BLE, então não houve CTKD ali; o vínculo foi só o clássico. |
| **Transport bridging** | No iOS, `CBConnectPeripheralOptionEnableTransportBridgingKey`: a opção de `connect` que deixa o iOS levar a conexão LE ao BR/EDR do mesmo aparelho. A SDK iOS da CRP a usa no iOS 13 ou maior, e o perfil do Condor Reebok a liga pelo `Quirks.iosTransportBridging` (ADR 30). |

## Protocolo SMA

| Termo | Significado |
|---|---|
| **SMA** | Fabricante do firmware do Mormaii fitGPS (SDK `com.bestmafen.smablelib`). Aqui é a `Family.SMA`, com driver próprio (`sma/`) e sem a SDK. |
| **Frame** | Uma mensagem completa do protocolo: L1 + L2. Pode ser maior que uma escrita e ir em vários fragmentos. |
| **L1** | Cabeçalho de 8 bytes do frame: `AB`, flags, tamanho do L2, CRC e seq (`protocolo-sma.md`, seção 2). |
| **L2** | Corpo do frame: `cmd`, versão `00`, `key`, tamanho do value e o value. |
| **cmd / key** | Os dois bytes que identificam um comando, escritos `cmd/key` em hexadecimal (ex.: `02/08` BATTERY). `cmd` é a família (`02` SET, `03` CONNECT, `05` DATA…); a resposta de um pedido usa `key + 1`. Catálogo na seção 6 do `protocolo-sma.md`. |
| **value** | O payload do L2, de 0 a N bytes. Um GET vai com value vazio. |
| **CRC-16/ARC** | Checksum do L2, gravado no L1 (`Crc16Arc`). Vetor de conferência: `"123456789"` → `0xBB3D`. |
| **seq (sequence id)** | Número de 16 bits no L1. O telefone numera seus frames a partir de 1 (`SmaSequence`); o relógio tem o próprio contador, que o telefone copia no ACK. |
| **ACK / NACK** | Frame de controle de 8 bytes, só L1: `AB 10 00 00 00 00 SEQ` confirma o frame `SEQ`; `AB 30 …` recusa (CRC errado). Os dois lados confirmam cada frame de dados. |
| **Fragmento** | Pedaço de um frame do tamanho de uma escrita (`mtu - 3`, mínimo 20), sem cabeçalho próprio (`SmaFragmenter`). Na volta, o relógio manda o L1 numa notificação e o corpo nas seguintes. |
| **Reassembler / remontagem** | `SmaReassembler`: junta notificações em frames, separa ACK/NACK e descarta lixo, com as regras da seção 4 do `protocolo-sma.md`. |
| **GET / SET / HANDSHAKE / NOTICE** | Como o driver trata cada comando (`SmaCommandKind`): GET espera ACK e resposta, sem retentativa; SET espera só o ACK, com uma retentativa; HANDSHAKE é BIND/LOGIN; NOTICE é um aviso do relógio. |
| **BIND** | `03/01`: vincula o telefone ao relógio no primeiro pareamento. O relógio pode pedir confirmação na tela, por isso a espera é longa (`Quirks.bindTimeoutMs`). |
| **LOGIN** | `03/03`: abre a sessão de protocolo depois do BIND ou numa reconexão. Antes dele o driver (como a SDK Android, `SmaManager.java:1245`) só envia `cmd 03`; não se sabe se o firmware recusa os outros. |
| **UNBIND** | `03/05`: desfaz o vínculo; enviado em `disconnect(forget = true)`. |
| **Handshake** | A sequência BIND + LOGIN (`HandshakeMode.FIRST_PAIR`) ou só LOGIN (`HandshakeMode.RECONNECT`). |
| **Payload de bind** | Os 32 bytes do BIND/LOGIN: `ANDROID_FF` (`FF` + 31 zeros) ou `USER_ID` (userId de 4 bytes + 28 zeros) (`BindPayloadKind`). |
| **Época 2000** | Os timestamps do histórico SMA são segundos desde 2000-01-01 00:00 no horário local de parede do relógio (`SmaLocalTime`). |
| **Relógio de parede** | Data e hora locais sem fuso ("14:30 do dia 28"). O SMA grava e recebe hora assim; a biblioteca converte com o fuso do telefone. |
| **mode** | Byte de tipo de cada registro de histórico. Nos passos só `mode 0` vira amostra; no sono, 1/2/3 são profundo/leve/acordado e 17/34 início/fim (`sma-dados.md`). |
| **Página / paginação** | Uma resposta de histórico traz até 20 registros; com exatamente 20, o telefone pede a mesma key de novo para receber o lote seguinte. |
| **Cursor** | Posição do relógio no próprio histórico. A hipótese H1 é que ele avança quando o relógio recebe o nosso ACK de uma página, e H2 que os registros lidos são apagados; por isso a sessão guarda tudo no `SampleStore`. |
| **Registro ao vivo** | O último registro de cada resposta `05/42` do fitGPS 0.2.1, carimbado no segundo do pedido, com o acumulado do dia até ali (`sma-dados.md`, seção 6). É o que faz toda leitura de passos trazer pelo menos um registro. |
| **Vetor dourado** | Frame de referência em hex, conferido de forma independente, que o codec tem que gerar byte a byte (`SmaFrameGoldenTest`, `protocolo-sma.md`, seção 9). |
| **H1–H9** | Hipóteses sobre o relógio, com o default adotado, a situação depois das gravações reais do fitGPS 0.2.1 (H5 provada, H3 e H6 refutadas, H4 com o formato provado, H1 parcial, as outras abertas ou só sugeridas) e como fechar o que falta (`protocolo-sma.md`, seção 8; `pendencias.md`). |

## Protocolo iDO

| Termo | Significado |
|---|---|
| **iDO** | Fabricante do firmware do Mormaii Life Ultra GPS (SDK `IDoBLELib-VeryFit`, protocolo numa biblioteca nativa). Aqui é a `Family.IDO`, com driver próprio (`ido/`) e sem a SDK (`protocolo-ido.md`). |
| **Frame legado (V2)** | `[cmd][key][payload]`, até 20 bytes, sem tamanho, seq nem CRC; a resposta repete `cmd/key`. Uma escrita GATT por frame; na recepção, um pacote curto é completado com zeros (`IdoFrameCodec`). |
| **V3** | O protocolo de JSON longo da SDK iDO (pacotes que começam com `0x33`, mensagem `DA AD DA AD …` com CRC-16), usado para histórico e configurações. Fora da PoC: o driver descarta esses pacotes. |
| **Informação básica (`02/01`)** | Resposta com id do modelo, bateria (`energe`), estado de carga, `pairFlag`, plataforma e `gps_platform` (`IdoBasicInfo`). |
| **`pairFlag`** | Byte 8 da informação básica: 1 quando o relógio se diz vinculado. A reconexão exige 1; o primeiro pareamento manda o bind de qualquer jeito. |
| **Bind / autenticação (`04/01`, `04/05`)** | O vínculo iDO: o relógio responde o bind com um desafio de 12 bytes, o telefone responde com o embaralhamento `E[6..11] + (E[0..5] XOR E[6..11])` (`IdoAuth`), e o relógio só confirma (`auth_code` 0) depois do toque na tela. |
| **Dados ao vivo (`02/A0`)** | Os acumuladores do dia no relógio: passos, calorias totais (repouso + atividade; a tela mostra só as de atividade), distância e tempo ativo (`IdoLiveData`). É a única fonte de passos do iDO na PoC. |
| **`extraNotify`** | Characteristics de notificação além da `notify` de um `GattCandidate`, ligadas em ordem antes do handshake (no iDO, `0af2` depois de `0af7`). |

## Protocolo CRP

| Termo | Significado |
|---|---|
| **Moyoung / CRP** | Fabricante do firmware do Condor Reebok (SDK `crpblelib`, pacote `com.crrepa`). Aqui é a `Family.CRP`, com driver próprio (`crp/`) e sem a SDK (`protocolo-crp.md`). |
| **crpblelib** | A SDK Android da Moyoung, versão 2.0.3 no Core de produção; lida descompilada e ofuscada (`sdk:m/f.java:94-130`), só como referência. A do iOS é o `CRPSmartBand.framework`, fechado. |
| **JieLi** | Fabricante do chip provável do P85 (o Core o trata como JieLi na OTA; o relógio expõe o serviço `AE00` da JieLi). Na fase 1 não muda nada: o protocolo de comandos é o mesmo em todas as plataformas de chip. |
| **`MOYOUNG-V2`** | O texto da characteristic `2A29` (Manufacturer Name) e da resposta `5A 00` do P85. Decide a versão do protocolo: com `MOYOUNG-V2` ou `-V3` o bloco segue o MTU e o handshake manda `B7 0E` e `BB 16 00`; outro texto é V1, com bloco 20 (`CrpProtocolVersion`). |
| **`FEEA`** | O serviço de comandos do CRP: escrita dos frames em `FEE2`, respostas em `FEE3`, passos do dia em `FEE1` (leitura e notificação). |
| **Frame `FE EA`** | `FE EA \| b2 \| len \| cmd \| payload`, sem CRC, sequência nem ACK de aplicação; `len` é o total com o cabeçalho, com os 4 bits baixos de `b2` como bits altos (`CrpFrameCodec`). |
| **`b2`** | O terceiro byte do frame: `0x10` com bloco de escrita de 20 bytes, `0x20` mais os bits altos do comprimento nos outros casos (`0x30` no V3 da SDK iOS). |
| **Bloco** | O tamanho de cada escrita GATT de um frame CRP: 20 no V1 e antes de saber a versão; depois, `min(MTU, 250) − 3` arredondado a múltiplo de 4 (244 com o MTU 517 do P85). |
| **Sub** | O primeiro byte do payload (dois no `B6`) nos comandos multiplexados (`5A`, `59`, `5D`, `5C`, `33`, `B6`): a resposta repete o `cmd` e o sub, e o driver casa por `(cmd, sub)`. |
| **Meia hora / categoria** | O histórico de passos do `0x59`: os passos de cada meia hora, 24 por página, duas páginas por dia, hoje e ontem. As categorias de distância (`5D`) e calorias (`5C`) existem na SDK, mas o P85 não responde a elas. |
| **Amostra de totais do dia** | A `StepsSample` `mode 2` do CRP: no instante início do dia + 1 s, com `steps = 0` e as calorias e a distância do dia que o relógio dá (`FEE1` hoje, `33 01` ontem). Somada às meias horas com `HOURLY_SUM`, dá os totais do relógio (ADR 28). |
| **Passos ao vivo** | As notificações de `FEE1` (cerca de uma por segundo andando): o driver monta a meia hora atual e a amostra de totais, e a sessão mescla no máximo uma vez a cada 2 s (`ApplySteps`, `LIVE_STEPS_THROTTLE_MS`). |
| **Legado (`0x33`) e `B6`** | Os dois formatos de histórico diário da SDK: `33 01` passos de ontem, `33 03`/`33 04` sono de 1 e 2 dias atrás; e o novo `B6 [tipo][dia]`. O app de produção nunca mandou `0x33` e o P85 não respondeu a nenhum `B6`; o driver sonda o `33 01` uma vez por conexão e aceita o `B6` como resposta. |
| **Janela das 20h / data de sono** | A data que a SDK dá a uma resposta de sono: hoje − d, mais 1 dia se agora for 20h ou mais. É o `dayKey` da sessão de sono no CRP, e o primeiro registro às 20h ou depois cai no dia anterior (ADR 29). |
| **Fase `VERSION`** | A única fase do handshake CRP, igual no primeiro pareamento e na reconexão: leitura de `2A29`, `5A 00` e, no V2/V3, `B7 0E` e `BB 16 00`. Não há vínculo de aplicação. |
| **`F8`** | O pedido de remover o vínculo no relógio, no `forget`; não tem resposta, e a SDK considera sucesso o fim da escrita. |
| **`F9`** | Notificação que a SDK 2.0.3 não trata: `F9 01 01 xx` acompanha o Bluetooth clássico (00 desconectado, 01 conectado); `F9 02 xx` chega periodicamente, com significado desconhecido. Só log. |
| **`sideNotify`** | Notificações fora do canal de frames, em qualquer serviço (no CRP, `2A37`, `FEE1` e `2A19`), entregues ao driver num canal à parte, com a characteristic de origem; as CCCDs delas não são fatais (ADR 26). |
| **`classicBond` / vínculo clássico** | O quirk de um relógio que usa Bluetooth clássico no mesmo endereço (o Condor Reebok): o `CoreBridge` pede o vínculo BR/EDR ao host no `READY` e o remove no `forgetDevice` (`ClassicBondResult`, `CLASSIC_BOND_FAILED`; ADR 30). |

## Sessão e dados

| Termo | Significado |
|---|---|
| **Host** | Quem usa a biblioteca e é dono do BLE da plataforma: hoje o Core React Native Android (`react-native-technos-core-next`); no futuro, o iOS. Implementa `Transport`, `WatchLogger` e `SampleStore` (`:protocol`) e, sobre o `CoreBridge`, `CoreHost`, `CoreEventSink` e `CoreCompletion` (`:bridge`). |
| **Transport** | A interface que o host implementa sobre `BluetoothGatt`/`CoreBluetooth`. Só bytes, UUIDs e status; o resultado de cada operação volta por `TransportEvents`. |
| **Sessão** | `WatchSession`, a fachada única do host: conexão, handshake, fila de comandos, sincronização, reconexão e consultas ao histórico. |
| **Profile / perfil** | `DeviceProfile`: tudo o que se sabe de um modelo (nomes de advertising e prefixos de nome, candidatos GATT, quirks). Hoje: `Profiles.MORMAII_FIT_GPS` (`fitgps`), `Profiles.MORMAII_LIFE_ULTRA_GPS` (`lifeultragps`) e `Profiles.CONDOR_REEBOK` (`condorreebok`). |
| **Quirk** | Um ajuste de comportamento por modelo guardado como dado em `Quirks` (timeouts, MTU, payload do BIND, agregação de passos, retenção), para o mesmo driver servir modelos diferentes. |
| **Family / família** | O protocolo de um grupo de modelos (`Family.SMA`, `IDO`, `CRP`). Cada família tem um driver. |
| **Driver** | `DeviceDriver` (interno): o protocolo de uma família sobre um `DriverLink`. A sessão chama `handshake`, `readBattery`, `readSteps`… sem saber o que é um frame. Hoje há três: `SmaDriver`, `IdoDriver` e `CrpDriver`. |
| **DriverLink** | O link GATT já configurado (candidato escolhido, notificações ligadas, MTU negociado) que a sessão entrega ao driver em cada conexão. |
| **HostDriver** | Interface pública e vazia, reservada para adaptadores que o host escreveria sobre a SDK de um fabricante (`arquitetura.md`, seção 9). |
| **requestId** | Número que o host passa em cada comando e que volta no callback. Cada `requestId` recebe exatamente um **callback terminal** (`onDeviceInfo`, `onBattery`, `onLiveSteps`, `onSyncFinished`, `onCompleted` ou `onError`). |
| **NO_REQUEST_ID** | `-1`: o `requestId` dos eventos que o relógio provoca sozinho (ex.: `onLiveSteps` depois de um aviso `02/70`, os passos ao vivo e o aviso de bateria do CRP). O `CoreBridge` também o usa no `onError` `CLASSIC_BOND_FAILED`. |
| **Estados** | `ConnectionState`: `DISCONNECTED`, `CONNECTING`, `CONFIGURING`, `HANDSHAKING`, `READY`, `SYNCING`, `RECONNECTING`, `BLUETOOTH_OFF` (diagrama em `arquitetura.md`, seção 5). |
| **Thread da sessão** | O dispatcher de paralelismo 1 onde roda todo o estado da sessão e de onde saem todos os callbacks (`arquitetura.md`, seção 4). É uma thread **lógica**: um bloco por vez, em ordem, mas a thread do sistema pode mudar entre um bloco e outro (no iOS, o `Dispatchers.Default` roda no pool do GCD). |
| **Fila do host** | A fila serial de onde o host entrega os eventos BLE (a fila de delegate do `CBCentralManager` no iOS, a thread de callbacks do `BluetoothGatt` no Android). Nos testes, `HostQueue` (`dispatch_queue_create` no iOS, um executor de uma thread na JVM). |
| **Ponte de bytes** | O pacote `ios/`, só no `iosMain`: `value.toData()` (`KotlinByteArray` → `Data`), `ByteBridgeKt.toByteArray(data)` e as sobrecargas `onNotification(characteristic:data:)` e `onReadCompleted(requestId:data:status:)`, que copiam os bytes (`arquitetura.md`, seção 11). |
| **Sync** | `syncHistory`: lê o histórico de passos (e de sono, se pedido) do relógio e grava no `SampleStore`, com progresso de 0 a 100 (0, 50 e 100 quando lê passos e sono). |
| **SampleStore** | Persistência do histórico que o host implementa (no Core RN, arquivos JSON). A sessão grava tudo o que lê e responde as consultas só a partir dele. |
| **StepsSample** | Uma amostra de passos (no SMA, uma por hora cheia, a do último registro da hora; no fitGPS 0.2.1 o valor é o acumulado do dia), com calorias e distância estimadas. No CRP, uma por meia hora (`mode` 0 e 1, sem calorias nem distância) e uma amostra de totais do dia (`mode` 2). |
| **StepsDay** | O total de um dia local, agregado das amostras segundo `Quirks.stepsAggregation` (`DAY_CUMULATIVE`, o do fitGPS e do Life Ultra GPS, usa o maior valor do dia; `HOURLY_SUM`, o do Condor Reebok, soma as amostras). |
| **Acumulado do dia** | O contador de passos do fitGPS 0.2.1: o `mode 0` de cada registro é o total do dia até aquele registro e não zera na virada da hora (hipótese H3 refutada; `decisoes.md`, ADR 1). Quando ele zera (meia-noite, T5) ainda não foi visto. Somar as horas, como o Core de produção, conta esse total de novo a cada hora com registro. |
| **dayKey** | Dia local no fuso do telefone, no formato `yyyy-MM-dd`. É a chave de `StepsDay`, de `SleepSession` (o dia em que a pessoa acordou; no CRP, a data de sono da SDK, com a janela das 20h) e de `confirmUploaded`. |
| **confirmUploaded** | Marca `dayKeys` como já enviados ao backend; esses dias deixam de sair em `getStepsDays`/`getSleepSessions`. É o que o `deletedSavedData` do JavaScript alimenta. |
| **Retenção** | `Quirks.historyRetentionDays` (30 no fitGPS): depois de cada sync, o que for mais velho sai do store. A mescla de passos também usa essa janela e descarta amostras mais velhas ou do futuro (dia depois de hoje e mais de 3 h à frente de agora; a folga é para o relógio ainda em outro fuso, ADR 16). |
| **Contrato B** | O contrato do Bluetooth desligado (`decisoes.md`, ADR 18): o host entrega só `onBluetoothStateChanged(false)`, nenhum `onDisconnected` pelo que caiu, e `disconnect` fica mudo até um `connect` novo. A sessão não espera resposta e zera a contagem de desconexões esperadas. |
| **Desconexão esperada** | Um `onDisconnected` que responde a um `Transport.disconnect` que a sessão pediu com link aberto ou pendente; a sessão conta essas respostas e as ignora (ADR 12). |
| **Estimativas** | Calorias e distância calculadas a partir dos passos com as fórmulas da SDK e o usuário fixo do Core (60 kg, 173,5 cm), em `SmaEstimates`. |
| **Exploratório** | Funciona e é testado, mas nunca foi validado com o relógio real. Hoje: o sono do fitGPS. |

## Testes e ferramentas

| Termo | Significado |
|---|---|
| **Fixture** | Gravação do tráfego GATT, pacote a pacote, num `.jsonl` (`docs/fixtures.md`). **Sintética** quando gerada pelo codec (`fixtures/synthetic/`; a do Condor Reebok tem os TX do nosso driver e os RX e READ reais); **real** quando gravada do relógio pelo app de exemplo (`fixtures/real/<perfil>/<firmware>/`); **de referência** quando convertida da captura HCI de outro app, com redação, e não reproduz contra a nossa sessão (`fixtures/reference/<perfil>/<firmware>/`; ADR 32). |
| **Redação** | O que o `btsnoop2jsonl.py` tira ou troca antes de escrever uma fixture de referência: os frames de notificação (`0x41`), de previsão do tempo (`0x42`) e de clima com localização (`0x43`, `0xB5`) saem inteiros, e o perfil do usuário (`0x12`) vira um valor fictício (`B2 4B 18 00`); uma trava aborta se algum endereço da captura (telefone, relógio, pares LE e clássicos, advertising) aparecer na saída. |
| **Replay** | Rodar a sessão inteira contra uma fixture, em tempo virtual: o `ReplayDriver` confere cada TX com o gravado e devolve os RX gravados (`RealFixturesReplayTest`, nos quatro targets de teste; `SmaSessionReplayTest`, só na JVM). |
| **Replay em tempo real** | O `RealtimeReplayTest`: a gravação real com o construtor público da `WatchSession` (dispatcher de produção), esperas de verdade e os eventos entregues por uma fila do host. É o teste de fumaça do threading (`fixtures.md`, seção 4.2). Imprime a linha `REALTIME-REPLAY`. |
| **Sonda do dispatcher** | O `SessionThreadingTest.publicConstructorsRunTheSessionOnASerialDispatcher`: blocos lançados no contexto da sessão que esperam um parceiro sem suspender, com um controle em `Dispatchers.Default` sem limite. Prova que o dispatcher dos construtores públicos é serial, o que o replay em tempo real não distingue (`arquitetura.md`, seção 4). |
| **Máscara** | O que o replay estrito ignora ao comparar um TX: o seq de todo frame e, só no `02/01`, o value (a hora muda a cada execução) e o CRC que o cobre (`TxMasker`). |
| **TX / RX / READ** | `FrameDirection`: TX é escrita do telefone, RX é notificação do relógio, READ é o valor de uma leitura GATT bem-sucedida (ADR 27). |
| **FrameJsonl** | O formatador do `.jsonl` (cabeçalho + uma linha por pacote), o mesmo no host e nos testes. |
| **FakeTransport** | Host falso, síncrono e controlável, usado por todos os testes de sessão. Segue o contrato B no Bluetooth desligado. |
| **Export cumulativo** | Cada **Export** do app de exemplo contém tudo desde o último **Clear**, então um export é prefixo exato do seguinte. Só o último vira fixture (`fixtures.md`, seção 3.1). |
| **Linha `REPLAY`** | A linha que o `RealFixturesReplayTest` imprime por arquivo reproduzido (caminho, pacotes, TX conferidos, estado final), no `system-out` do XML de cada tarefa de teste. É a prova de que a gravação rodou naquele target. O `RealFixturesStepsTest` imprime, do mesmo jeito, uma linha `STEPS-DAY` com o total do dia que as gravações reais produzem, e o `RealFixturesSleepTest`, uma linha `SLEEP` com a noite da gravação do iPhone. |
| **`SIMCTL_CHILD_`** | Prefixo das variáveis de ambiente que o `xcrun simctl spawn` repassa ao processo no simulador, sem o prefixo. É como o caminho de `fixtures/` chega ao `iosSimulatorArm64Test` (`SIMCTL_CHILD_FIXTURES_DIR` → `FIXTURES_DIR`). |
| **`SmaDriver/frame`** | Linha de log em DEBUG, uma por frame de dados completo, em cada sentido: `TX seq=… cmd/key NOME len=…` e `RX seq=… cmd/key NOME len=… crc=ok` (`log/README.md`). |
| **Pendências** | A validação no relógio que ficou para depois e os itens técnicos abertos, em `docs/pendencias.md`. |
| **SmaWatchSimulator** | Relógio SMA simulado em nível de byte, usado pelos testes do driver e da sessão: responde BIND, LOGIN, bateria e versão por padrão, e cada teste programa as outras respostas (páginas de histórico, recusas, silêncio). |
| **CrpWatchSimulator** | O P85 simulado em nível de byte (`FakeTransport.scriptedCrp`): responde por `(cmd, sub)` e por leitura com os bytes reais da captura do app de produção, e cada teste programa páginas `59`, sono legado ou `B6`, o `33`, silêncio, `b2` e fragmentação, e empurra `FEE1`, `2A19`, `F9` e `2A37`. |
| **Tempo virtual** | O relógio do `kotlinx-coroutines-test`: os testes avançam 60 s de BIND sem esperar. |
| **ADR** | Architecture Decision Record: uma entrada curta em `docs/decisoes.md` com contexto, decisão e como reverter. |
| **KMP** | Kotlin Multiplatform: o mesmo código `commonMain` compilado para Android (AAR), JVM e iOS (framework). |
| **XCFramework** | O pacote de frameworks iOS (dispositivo e simulador) que o host iOS vai consumir; gerado pelo `:bridge` com `assembleTechnosWatchProtocolXCFramework`, em `bridge/build/XCFrameworks/`. |
| **Framework guarda-chuva** | O framework `TechnosWatchProtocol` gerado pelo `:bridge` com o `:protocol` exportado (`export(project(":protocol"))`): um framework só, com a sessão e o contrato JS, e os tipos do `:protocol` uma vez só e sem prefixo de módulo (ADR 20). |
| **Contrato JS** | O que o JavaScript do Core React Native vê: nomes de evento, payloads (`src/types/events.ts` do Repo 2), códigos de estado e de erro, `deletedSavedData`. A parte comum aos dois hosts mora no `:bridge` (`arquitetura.md`, seção 12). |
| **`CFBoolean`** | O booleano do Core Foundation (`__NSCFBoolean`, `kCFBooleanTrue`/`kCFBooleanFalse`). O React Native só entrega `true`/`false` ao JS para essa classe; um `KotlinBoolean` vira `1`/`0`. Por isso o `FoundationConverter` monta os payloads pelo Core Foundation. |
| **Teste diferencial** | Um teste que roda a implementação nova e uma antiga (o oráculo) sobre as mesmas entradas e exige a mesma saída: o `IsoDatesDifferentialTest` compara as datas do `:bridge` com o `java.time` do host Android antigo, linha `DIFFERENTIAL` no XML. |
| **Header ObjC** | `TechnosWatchProtocol.h`, a API que o Swift enxerga (o header guarda-chuva do `:bridge`). A tarefa `checkObjcHeader` falha se ele expuser `suspend`, `Flow` ou tipos do kotlinx, ou se a `WatchSession`, o `Transport`, a ponte de bytes ou o contrato do `:bridge` não aparecerem exatamente uma vez. |
| **`checkSwiftInterop`** | A tarefa que compila com `swiftc -typecheck` um host Swift mínimo (`bridge/swift-interop/MinimalHost.swift`) contra o framework guarda-chuva; falha se um nome, rótulo ou tipo que esse arquivo usa mudar no header. Só confere o que o arquivo usa (`decisoes.md`, ADRs 19 e 20). |
| **`checkObjcInterop`** | A tarefa que compila com `clang++ -fsyntax-only` (ARC, módulos, C++20, `-Werror`) um TurboModule mínimo em Objective-C++ (`bridge/objc-interop/MinimalTurboModule.mm`) contra o framework guarda-chuva: cada seletor do `TWPCoreBridge` que o shim do iOS chama e os protocolos que ele implementa. Faz parte do `check`. |
| **`CoreBridge`** | O controlador do Core React Native em código comum (`:bridge`): um método por método JS do spec e o lado `WatchSessionListener` da sessão, com as regras do host Android antigo (checagens, ordem evento/`resolve`, `NO_DATA`, pareamento salvo, reconexão automática, ciclo de vida). O TurboModule de cada plataforma só o chama (`docs/arquitetura.md`, seção 13; ADR 21). |
| **`CoreHost`** | O que o `CoreBridge` pede à plataforma, um por processo: permissões, Bluetooth, endereço válido, scan, trabalho em segundo plano no sync, pareamento salvo, cabeçalho da gravação, log e o vínculo Bluetooth clássico (`hasClassicBond`, `requestClassicBond`, `removeClassicBond`). No Android é o `AndroidCoreHost` do Repo 2 (montado pelo `CoreGraph`); no iOS será o `CoreGraph.swift`. |
| **`CoreEventSink`** | A instância atual do módulo JS como destino dos eventos (`canEmit`, `emit`). Separado do `CoreHost` porque o módulo é recriado a cada reload do JS; o `CoreBridge` guarda os eventos na fila enquanto não há módulo pronto. |
| **`CoreCompletion`** | O par `resolve(value)`/`reject(code, message)` que o host monta sobre a Promise do React Native; o `CoreBridge` liquida cada uma exatamente uma vez. |
| **Diário (golden do `CoreBridge`)** | A sequência única de eventos, liquidações e efeitos no host que os testes do `CoreBridge` gravam (`Journal`), e o arquivo de referência em `bridge/golden/` com o diário da gravação real passando pelo controlador, método JS por método JS. Regenerar só com `BRIDGE_GOLDEN_REGENERATE=1`, revisando o diff. |
| **Kit de teste compartilhado** | O transporte falso, o relógio SMA simulado, a `SessionHarness`, o replay e a leitura das fixtures, em `protocol/src/testKit*`: pastas que o `:protocol` e o `:bridge` compilam nos próprios testes, sem publicar código de teste. |
| **Módulo amigo** | Um módulo cujo `internal` o compilador deixa outro enxergar (`friendPaths`, `-friend-modules`). As compilações de teste do `:bridge` tratam o `:protocol` assim, para o kit de teste compartilhado funcionar; o código principal, não (ADR 21). |
| **Core atual** | O `react-native-technos-ware-core` 4.x (`TechnosWareCore`), com as SDKs dos fabricantes; é a referência de comportamento citada nos documentos (`SMAService.java`, `SMAService.m`). |
