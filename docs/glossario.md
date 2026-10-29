# Glossário

Termos usados no código, nos KDocs e nos outros documentos. Quando o termo tem um tipo ou uma constante no código, o nome aparece entre crases. Detalhes de protocolo ficam em [`protocolo-sma.md`](protocolo-sma.md); de sessão, em [`arquitetura.md`](arquitetura.md).

## BLE e GATT

| Termo | Significado |
|---|---|
| **BLE** | Bluetooth Low Energy. O relógio é o periférico; o telefone é o central. |
| **GATT** | Generic Attribute Profile: o relógio expõe **serviços**, cada um com **characteristics** identificadas por UUID. O telefone escreve numa characteristic e recebe notificações de outra. |
| **ATT** | Attribute Protocol, a camada abaixo do GATT. Cada operação GATT vira um pacote ATT; o cabeçalho ATT de uma escrita ocupa 3 bytes do MTU. |
| **UUID curto / longo** | Serviços padrão do Bluetooth têm forma curta (`FF60`) dentro da base `0000xxxx-0000-1000-8000-00805f9b34fb`. A biblioteca compara sempre a forma de 128 bits em minúsculas (`GattUuid.normalize`). |
| **MTU** | Maximum Transmission Unit do ATT. Sem negociação vale 23, o que deixa 20 bytes úteis por escrita. A sessão pede `Quirks.requestMtu` (247) e cai para 23 se o host não responder (`docs/decisoes.md`, ADR 5). Com o fitGPS 0.2.1 no Android, a negociação devolveu 23 com status 0. |
| **CCCD** | Client Characteristic Configuration Descriptor (`0x2902`). Escrever `01 00` nele liga as notificações da characteristic; no Android o host faz isso dentro de `Transport.enableNotifications`. |
| **Notify / notificação** | Mensagem que o relógio manda sem pedido, numa characteristic com notificações ligadas. Não tem confirmação na camada ATT. Cada notificação chega à sessão por `TransportEvents.onNotification`. |
| **Indication** | Como a notificação, mas confirmada pelo telefone na camada ATT. O SMA não usa; o `hci2jsonl.py` aceita as duas. |
| **Write request** | Escrita ATT com confirmação do relógio (`WriteType.WITH_RESPONSE`). É o que o driver SMA usa em todo frame. |
| **Write command** | Escrita ATT sem confirmação (`WriteType.WITHOUT_RESPONSE`). |
| **NUS** | Nordic UART Service, `6e400001-…` (write `6e400002`, notify `6e400003`). É o serviço que o Core iOS usa com o fitGPS e o único serviço de dados que o fitGPS 0.2.1 expõe. O relógio não o anuncia no advertising. |
| **FF60 ("ROUND")** | Serviço próprio da SMA (write `FF61`, notify `FF62`). A SDK Android prefere este quando existe; o fitGPS 0.2.1 não o expõe (hipótese H6 refutada para esse firmware, `protocolo-sma.md`, seção 1). |
| **Advertising / anúncio** | O pacote que o relógio transmite para ser achado no scan, com o nome e uma lista de serviços. O fitGPS 0.2.1 anuncia `Mormaii_fitGPS` e os serviços `180f`, `180a` e `00000001-…`, sem o NUS; por isso a identificação é só pelo nome (`DeviceRegistry.identify`). |
| **GattCandidate** | Um trio serviço + write + notify que o driver sabe usar. O perfil lista os candidatos em ordem de prioridade e a sessão usa o primeiro que a descoberta encontrar inteiro. |
| **autoConnect** | Modo de conexão do Android em que o sistema mantém uma conexão pendente até o relógio aparecer. É o `Transport.connect(autoReconnect = true)` da reconexão. |
| **HCI snoop** | Log de todo o tráfego Bluetooth do Android (`btsnoop_hci.log`), ligado nas Opções do desenvolvedor. Aberto no Wireshark e convertido com `scripts/hci2jsonl.py` (`docs/fixtures.md`, seção 3.2). |

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
| **Registro ao vivo** | O último registro de cada resposta `05/42` do fitGPS 0.2.1, carimbado no segundo do pedido, com o total acumulado da hora até ali (`sma-dados.md`, seção 6). É o que faz toda leitura de passos trazer pelo menos um registro. |
| **Vetor dourado** | Frame de referência em hex, conferido de forma independente, que o codec tem que gerar byte a byte (`SmaFrameGoldenTest`, `protocolo-sma.md`, seção 9). |
| **H1–H9** | Hipóteses sobre o relógio, com o default adotado, a situação depois da gravação real do fitGPS 0.2.1 (H5 provada, H6 refutada, H4 com o formato provado, H1 parcial, as outras abertas ou só sugeridas) e como fechar o que falta (`protocolo-sma.md`, seção 8; `pendencias.md`). |

## Sessão e dados

| Termo | Significado |
|---|---|
| **Host** | Quem usa a biblioteca e é dono do BLE da plataforma: hoje o Core React Native Android (`react-native-technos-core-next`); no futuro, o iOS. Implementa `Transport`, `WatchLogger` e `SampleStore`. |
| **Transport** | A interface que o host implementa sobre `BluetoothGatt`/`CoreBluetooth`. Só bytes, UUIDs e status; o resultado de cada operação volta por `TransportEvents`. |
| **Sessão** | `WatchSession`, a fachada única do host: conexão, handshake, fila de comandos, sincronização, reconexão e consultas ao histórico. |
| **Profile / perfil** | `DeviceProfile`: tudo o que se sabe de um modelo (nomes de advertising, candidatos GATT, quirks). Hoje só `Profiles.MORMAII_FIT_GPS` (`id = "fitgps"`). |
| **Quirk** | Um ajuste de comportamento por modelo guardado como dado em `Quirks` (timeouts, MTU, payload do BIND, agregação de passos, retenção), para o mesmo driver servir modelos diferentes. |
| **Family / família** | O protocolo de um grupo de modelos (`Family.SMA`). Cada família tem um driver. |
| **Driver** | `DeviceDriver` (interno): o protocolo de uma família sobre um `DriverLink`. A sessão chama `handshake`, `readBattery`, `readSteps`… sem saber o que é um frame. Hoje há um, o `SmaDriver`. |
| **DriverLink** | O link GATT já configurado (candidato escolhido, notificações ligadas, MTU negociado) que a sessão entrega ao driver em cada conexão. |
| **HostDriver** | Interface pública e vazia, reservada para adaptadores que o host escreveria sobre a SDK de um fabricante (`arquitetura.md`, seção 9). |
| **requestId** | Número que o host passa em cada comando e que volta no callback. Cada `requestId` recebe exatamente um **callback terminal** (`onDeviceInfo`, `onBattery`, `onLiveSteps`, `onSyncFinished`, `onCompleted` ou `onError`). |
| **NO_REQUEST_ID** | `-1`: o `requestId` dos eventos que o relógio provoca sozinho (ex.: `onLiveSteps` depois de um aviso `02/70`). |
| **Estados** | `ConnectionState`: `DISCONNECTED`, `CONNECTING`, `CONFIGURING`, `HANDSHAKING`, `READY`, `SYNCING`, `RECONNECTING`, `BLUETOOTH_OFF` (diagrama em `arquitetura.md`, seção 5). |
| **Thread da sessão** | O dispatcher de paralelismo 1 onde roda todo o estado da sessão e de onde saem todos os callbacks (`arquitetura.md`, seção 4). |
| **Sync** | `syncHistory`: lê o histórico de passos (e de sono, se pedido) do relógio e grava no `SampleStore`, com progresso de 0 a 100 (0, 50 e 100 quando lê passos e sono). |
| **SampleStore** | Persistência do histórico que o host implementa (no Core RN, arquivos JSON). A sessão grava tudo o que lê e responde as consultas só a partir dele. |
| **StepsSample** | Uma amostra de passos (no SMA, o total de uma hora), com calorias e distância estimadas. |
| **StepsDay** | O total de um dia local, agregado das amostras segundo `Quirks.stepsAggregation` (`HOURLY_SUM` soma as horas; `DAY_CUMULATIVE` usa o maior valor do dia). |
| **dayKey** | Dia local no fuso do telefone, no formato `yyyy-MM-dd`. É a chave de `StepsDay`, de `SleepSession` (o dia em que a pessoa acordou) e de `confirmUploaded`. |
| **confirmUploaded** | Marca `dayKeys` como já enviados ao backend; esses dias deixam de sair em `getStepsDays`/`getSleepSessions`. É o que o `deletedSavedData` do JavaScript alimenta. |
| **Retenção** | `Quirks.historyRetentionDays` (30 no fitGPS): depois de cada sync, o que for mais velho sai do store. A mescla de passos também usa essa janela e descarta amostras mais velhas ou do futuro (dia depois de hoje e mais de 3 h à frente de agora; a folga é para o relógio ainda em outro fuso, ADR 16). |
| **Contrato B** | O contrato do Bluetooth desligado (`decisoes.md`, ADR 18): o host entrega só `onBluetoothStateChanged(false)`, nenhum `onDisconnected` pelo que caiu, e `disconnect` fica mudo até um `connect` novo. A sessão não espera resposta e zera a contagem de desconexões esperadas. |
| **Desconexão esperada** | Um `onDisconnected` que responde a um `Transport.disconnect` que a sessão pediu com link aberto ou pendente; a sessão conta essas respostas e as ignora (ADR 12). |
| **Estimativas** | Calorias e distância calculadas a partir dos passos com as fórmulas da SDK e o usuário fixo do Core (60 kg, 173,5 cm), em `SmaEstimates`. |
| **Exploratório** | Funciona e é testado, mas nunca foi validado com o relógio real. Hoje: o sono do fitGPS. |

## Testes e ferramentas

| Termo | Significado |
|---|---|
| **Fixture** | Gravação do tráfego GATT, pacote a pacote, num `.jsonl` (`docs/fixtures.md`). **Sintética** quando gerada pelo codec (`fixtures/synthetic/`); **real** quando gravada do relógio (`fixtures/real/<perfil>/<firmware>/`). |
| **Replay** | Rodar a sessão inteira contra uma fixture: o `ReplayDriver` confere cada TX com o gravado e devolve os RX gravados (`SmaSessionReplayTest`, `RealFixturesReplayTest`). |
| **Máscara** | O que o replay estrito ignora ao comparar um TX: o seq de todo frame e, só no `02/01`, o value (a hora muda a cada execução) e o CRC que o cobre (`TxMasker`). |
| **TX / RX** | `FrameDirection`: TX é escrita do telefone, RX é notificação do relógio. |
| **FrameJsonl** | O formatador do `.jsonl` (cabeçalho + uma linha por pacote), o mesmo no host e nos testes. |
| **FakeTransport** | Host falso, síncrono e controlável, usado por todos os testes de sessão. Segue o contrato B no Bluetooth desligado. |
| **Export cumulativo** | Cada **Export** do app de exemplo contém tudo desde o último **Clear**, então um export é prefixo exato do seguinte. Só o último vira fixture (`fixtures.md`, seção 3.1). |
| **Linha `REPLAY`** | A linha que o `RealFixturesReplayTest` imprime por arquivo reproduzido (caminho, pacotes, TX conferidos, estado final). É a prova de que a gravação rodou. |
| **`SmaDriver/frame`** | Linha de log em DEBUG, uma por frame de dados completo, em cada sentido: `TX seq=… cmd/key NOME len=…` e `RX seq=… cmd/key NOME len=… crc=ok` (`log/README.md`). |
| **Pendências** | A validação no relógio que ficou para depois e os itens técnicos abertos, em `docs/pendencias.md`. |
| **SmaWatchSimulator** | Relógio SMA simulado em nível de byte, usado pelos testes do driver e da sessão: responde BIND, LOGIN, bateria e versão por padrão, e cada teste programa as outras respostas (páginas de histórico, recusas, silêncio). |
| **Tempo virtual** | O relógio do `kotlinx-coroutines-test`: os testes avançam 60 s de BIND sem esperar. |
| **ADR** | Architecture Decision Record: uma entrada curta em `docs/decisoes.md` com contexto, decisão e como reverter. |
| **KMP** | Kotlin Multiplatform: o mesmo código `commonMain` compilado para Android (AAR), JVM e iOS (framework). |
| **XCFramework** | O pacote de frameworks iOS (dispositivo e simulador) que o host iOS vai consumir; gerado por `assembleTechnosWatchProtocolXCFramework`. |
| **Header ObjC** | `TechnosWatchProtocol.h`, a API que o Swift enxerga. A tarefa `checkObjcHeader` falha se ele expuser `suspend`, `Flow` ou tipos do kotlinx. |
| **Core atual** | O `react-native-technos-ware-core` 4.x (`TechnosWareCore`), com as SDKs dos fabricantes; é a referência de comportamento citada nos documentos (`SMAService.java`, `SMAService.m`). |
