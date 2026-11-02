# Arquitetura do `technos-watch-protocol`

Este documento descreve como o repositório é dividido em módulos e pacotes, quem pode depender de quem, por onde passam os bytes nos dois sentidos, em que thread tudo roda, a máquina de estados da sessão, o modelo de erros, a ponte de bytes do host iOS (seção 11) o contrato JS comum do Core React Native, no módulo `:bridge` (seção 12), e o controlador comum que implementa cada método JS para os dois hosts (`CoreBridge`, seção 13). O protocolo SMA em si está em [`protocolo-sma.md`](protocolo-sma.md) e [`sma-dados.md`](sma-dados.md), o iDO em [`protocolo-ido.md`](protocolo-ido.md) e o CRP em [`protocolo-crp.md`](protocolo-crp.md); as decisões e riscos, em [`decisoes.md`](decisoes.md); o formato das gravações, em [`fixtures.md`](fixtures.md).

## 1. Camadas

Dois módulos Gradle, com a dependência num sentido só:

| Módulo | Artefato | O que é | Depende de |
|---|---|---|---|
| `:protocol` | `br.com.grupotechnos.watch:protocol:0.1.0` | domínio, transporte, perfis, log, sessão e os protocolos SMA, iDO e CRP (o resto desta seção) | stdlib, `kotlinx-coroutines`, `kotlinx-datetime` |
| `:bridge` | `br.com.grupotechnos.watch:bridge:0.1.0` | o contrato JavaScript do Core React Native (`EventMapper`, `StateMapper`, `SavedDataMapper`, `HostErrorCodes`, `PendingEvents`, `RequestRegistry`, seção 12), o controlador que implementa cada método JS sobre a `WatchSession` (`CoreBridge`, com a plataforma atrás de `CoreHost`, `CoreEventSink` e `CoreCompletion`, seção 13) e o conversor para Foundation do iOS; gera o framework iOS `TechnosWatchProtocol` | `:protocol` (`api`), `kotlinx-datetime` |

O `:protocol` não conhece o `:bridge` (seria um ciclo, e o Gradle recusa). O host Android depende do artefato do `:bridge` pelo Maven local, que traz o do `:protocol` como `api` (o host usa os dois: o `CoreBridge` e, para montar a sessão, a `WatchSession` e o `Transport`); o host iOS consome um framework só, o guarda-chuva do `:bridge`, que exporta o `:protocol` inteiro.

```mermaid
flowchart LR
	rnjs["JS do Core RN<br/>(TechnosCore, TechnosEvents)"]
	android["Host Android (Repo 2)<br/>TurboModule (casca), AndroidCoreHost,<br/>BLE, store, log, conversores RN"]
	ios["Host iOS (Repo 2, futuro)<br/>shim ObjC++, Swift, CoreBluetooth"]
	bridge[":bridge<br/>contrato JS comum + CoreBridge<br/>(framework TechnosWatchProtocol)"]
	protocol[":protocol<br/>sessão, domínio, SMA, iDO, CRP"]
	rnjs --> android
	rnjs --> ios
	android --> bridge
	ios --> bridge
	bridge --> protocol
	android --> protocol
```

Dentro do `:protocol`:

```mermaid
flowchart TB
	host["Host (Core RN Android / iOS)<br/>implementa Transport, WatchLogger, SampleStore"]
	subgraph public["API pública"]
		session["session/ WatchSession<br/>(fachada)"]
		transport["transport/ Transport, TransportEvents"]
		profile["profile/ DeviceProfile, Quirks, Profiles"]
		domain["domain/ tipos puros"]
		log["log/ WatchLogger, FrameJsonl"]
	end
	subgraph internal["internal"]
		engine["session/ SessionEngine, SessionDriverLink,<br/>HistoryStore, SyncEngine"]
		driver["driver/ DeviceDriver, DriverLink, Drivers"]
		sma["sma/ codec, commands, data, SmaDriver"]
		ido["ido/ frame, comandos, payloads, IdoDriver"]
		crp["crp/ frame, remontagem, payloads, passos, sono, CrpDriver"]
		util["util/ ByteReader/Writer, Hex, TimeFormat"]
	end
	host --> session
	host --> transport
	session --> engine
	engine --> driver
	driver --> sma
	driver --> ido
	driver --> crp
	sma --> util
	ido --> util
	crp --> util
	engine --> util
```

| Pacote | Responsabilidade | Visibilidade |
|---|---|---|
| `domain/` | Tipos puros (`DeviceInfo`, `Battery`, `StepsSample`, `StepsDay`, `SleepSession`, `ConnectionState`, `DataKind`, `WatchErrorCodes`) e a `WatchException` interna | pública (menos a exceção) |
| `transport/` | `Transport` (o host implementa), `TransportEvents` (o host chama), tipos de descoberta GATT, `GattStatus`, `GattUuid` | pública |
| `profile/` | `DeviceProfile` (com `bleNamePrefixes`), `Family`, `GattCandidate` (com `extraNotify` e `sideNotify`), `NotifyChannel`, `Quirks`, `Profiles.MORMAII_FIT_GPS`, `Profiles.MORMAII_LIFE_ULTRA_GPS`, `Profiles.CONDOR_REEBOK`, `DeviceRegistry` (`identify`, `matchesName`, `byId`) | pública |
| `log/` | `WatchLogger` (o host implementa), `FrameDirection` (`TX`, `RX`, `READ`), `FrameJsonl` (formato das gravações) | pública |
| `session/` | `WatchSession` e `WatchSessionListener` (públicos); `SessionEngine`, `SessionDriverLink`, `GattOperations`, `SessionCommand`, `SyncEngine`, `HistoryStore`, `StepsAggregator` (internos) | fachada pública |
| `driver/` | `DeviceDriver`, `DriverLink` (canal de frames e canal lateral, `CharacteristicNotification`), `DriverNotice`, `DriverBattery`, `Drivers` (internos) e `HostDriver` (público, vazio) | interna |
| `sma/` | Protocolo SMA completo e `SmaDriver` | interna |
| `ido/` | Protocolo legado iDO (frame, catálogo, payloads, autenticação do vínculo) e `IdoDriver` | interna |
| `crp/` | Protocolo CRP (frame `FE EA`, remontagem, catálogo, payloads, passos por meia hora, sono) e `CrpDriver` | interna |
| `util/` | Bytes big-endian, hexadecimal, datas | interna |
| `ios/` (só `iosMain`) | Ponte de bytes para o host Swift: `ByteArray` ↔ `NSData` e as sobrecargas de `TransportEvents` com `NSData` (seção 11) | pública, só no iOS |

**Famílias**

| `Family` | Perfil (`id`) | Driver | GATT | Handshake | Protocolo |
|---|---|---|---|---|---|
| `SMA` | `MORMAII_FIT_GPS` (`fitgps`) | `sma/SmaDriver` | NUS (`FF60` antes, se houver); escrita com resposta; MTU 247 pedido | BIND + LOGIN; reconexão só LOGIN | [`protocolo-sma.md`](protocolo-sma.md), [`sma-dados.md`](sma-dados.md) |
| `IDO` | `MORMAII_LIFE_ULTRA_GPS` (`lifeultragps`) | `ido/IdoDriver` | `0af0`: escrita sem resposta em `0af6`, notificações `0af7` + `0af2`; MTU não pedido | informação básica + bind + autenticação com confirmação na tela; reconexão só a informação básica | [`protocolo-ido.md`](protocolo-ido.md) |
| `CRP` | `CONDOR_REEBOK` (`condorreebok`, nomes `P85`/`Condor Reebok` e prefixo `P85`) | `crp/CrpDriver` | `feea`: escrita sem resposta em `fee2`, frames em `fee3`; notificações laterais `2a37`, `fee1`, `2a19`; leituras `2a29`, `2a28`, `2a19`, `fee1`; MTU 512 pedido (o relógio abre com 517) | versão do protocolo (`2a29` + `5A 00`), igual no primeiro pareamento e na reconexão, sem vínculo de aplicação; vínculo Bluetooth clássico pedido ao host no `READY` (seção 13) | [`protocolo-crp.md`](protocolo-crp.md) |

O fitGPS e o Life Ultra GPS somam os passos em `DAY_CUMULATIVE` (o fitGPS lê o histórico por hora, o Life Ultra GPS só o total do dia ao vivo; ADR 22); o Condor Reebok soma em `HOURLY_SUM` as meias horas do histórico e uma amostra de totais do dia (ADR 28).

**Regras de dependência**

- `session/` não importa nada de `sma/`, `ido/` nem `crp/`: só `driver/Drivers.create(link)` conhece as famílias. Trocar ou acrescentar uma família não toca a sessão.
- `transport/` não conhece família nem protocolo: só bytes, UUIDs e status.
- `driver/`, `sma/`, `ido/` e `crp/` não conhecem `ConnectionState`, listener, fila nem store; recebem um `DriverLink` pronto.
- A API pública não usa `suspend`, `Flow`, `value class` nem tipos do `kotlinx-datetime` (Swift não os consome bem): datas são epoch millis e strings ISO. Por isso `TransportEvents`, `WatchSession` e o listener são de callback. A tarefa `./gradlew :bridge:checkObjcHeader` (parte do `check`) liga o framework guarda-chuva do simulador e falha se o header `TechnosWatchProtocol.h` contiver `completionHandler`, `Kotlinx_coroutines`, `Kotlinx_datetime`, `Kotlinx_serialization`, `Flow` ou `Coroutine`, ou se a `WatchSession`, o `Transport`, a ponte de bytes ou o contrato do `:bridge` não aparecerem exatamente uma vez. A regra vale para o `:bridge` também.
- `domain/` e `profile/` não dependem de ninguém; `transport/` também não. A única dependência de `driver/` para uma família é `Drivers` importando o `SmaDriver`, o `IdoDriver` e o `CrpDriver`.
- Toda declaração pública tem KDoc: `./gradlew dokkaHtml` roda nos dois módulos com `reportUndocumented` e `failOnWarning`, então um símbolo público sem documentação quebra o build. Vale também para o `iosMain` (o Dokka documenta o pacote `ios` de cada módulo no source set `iosMain`).
- O lado positivo do header: `./gradlew :bridge:checkSwiftInterop` (parte do `check`) compila com `swiftc -typecheck` um host Swift mínimo (`bridge/swift-interop/MinimalHost.swift`) contra o framework guarda-chuva; um nome, rótulo ou tipo que esse arquivo usa e que mude no header quebra o build (`decisoes.md`, ADRs 19 a 21). Ele usa toda a `WatchSession`, os protocolos que o host implementa (inclusive `CoreHost`, `CoreEventSink` e `CoreCompletion`), `TransportEvents` nas duas formas, a ponte de bytes, o domínio que o host mapeia e todo o `CoreBridge` (lista no `README.md` da pasta); o que ficar fora dele não é conferido. `./gradlew :bridge:checkObjcInterop` faz o mesmo para o shim Objective-C++ do TurboModule (`bridge/objc-interop/MinimalTurboModule.mm`, `clang++ -fsyntax-only` com ARC e `-Werror`): cada seletor do `TWPCoreBridge` que o shim chama e os protocolos que ele implementa.
- `ios/` só depende de `transport/` e da plataforma; nada do código comum depende dele.

## 2. Fluxo app → relógio

Exemplo: `readBattery(7)` com a sessão em `READY`.

```mermaid
sequenceDiagram
	participant H as Host (qualquer thread)
	participant S as SessionEngine (thread da sessão)
	participant Q as Fila da conexão
	participant D as SmaDriver
	participant L as SessionDriverLink
	participant T as Transport (host)
	H->>S: readBattery(7) — só enfileira na caixa
	S->>Q: SessionCommand.ReadBattery(7)
	Q->>D: readBattery()
	D->>L: write(frame 02/08) sob o Mutex do driver
	L->>T: write(service, write, bytes, WITH_RESPONSE, gattId=42)
	T-->>S: events.onWriteCompleted(42, 0)
	S-->>L: GattOperations completa o id 42
	Note over D: espera ACK e resposta 02/09 (fluxo de volta)
	D-->>Q: 85
	Q-->>H: listener.onBattery(7, Battery(85))
```

1. A chamada pública só coloca uma entrada na caixa da sessão (`Channel` ilimitado) e retorna.
2. A corrotina da caixa trata a entrada na thread da sessão: comandos vão para a fila da conexão atual, ou falham na hora com `NOT_READY`.
3. Um único consumidor por conexão executa um comando de cada vez (`DeviceDriver` não aceita chamadas paralelas).
4. O driver monta o frame (`SmaFrameCodec`), fragmenta (`SmaFragmenter`, `MTU - 3`) e escreve cada pedaço pelo `DriverLink`.
5. `SessionDriverLink.write` registra o pacote em `WatchLogger.frame` (TX), aloca um id GATT interno em `GattOperations`, chama `Transport.write` e suspende até o `onWriteCompleted` com o mesmo id. Status diferente de 0 vira `WatchException(TRANSPORT)`.

## 3. Fluxo relógio → app

```mermaid
sequenceDiagram
	participant T as Transport (host)
	participant S as SessionEngine
	participant L as SessionDriverLink
	participant D as SmaDriver (recepção)
	participant C as Comando em andamento
	participant HS as HistoryStore
	T->>S: events.onNotification(notify, bytes)
	S->>L: deliver(bytes) — log RX + canal de notificações
	L->>D: notifications.receive()
	D->>D: SmaReassembler (L1 de 8 bytes + corpo)
	D->>L: write(ACK seq) antes de entregar
	D->>C: resposta 05/42 completa o pedido
	C->>HS: mergeSteps(amostras) + save no SampleStore
	C-->>T: listener.onLiveSteps / onSyncProgress / onSyncFinished
```

- Notificações chegam pelo `TransportEvents`, passam pela caixa (a ordem é preservada) e vão para o link da conexão atual: as da `notify` escolhida e das `extraNotify` vão para o canal de frames do driver; as das `sideNotify` (no CRP, `2a37`, `fee1` e `2a19`), para um canal lateral, com a characteristic de origem (`DriverLink.sideNotifications`, ADR 26). As de qualquer outra characteristic vão só para o log (DEBUG `Session/onNotification`, com o hex) e são descartadas. Elas não passam por `WatchLogger.frame`, então ficam fora da gravação: quem chama `frame` é só o link (`log/README.md`).
- O driver remonta, confere CRC, confirma com ACK **antes** de entregar (`docs/decisoes.md`, ADR 2) e casa a resposta com o pedido em andamento pelo `cmd` e `key + 1`.
- Avisos espontâneos (`02/70` DATA_CHANGED no SMA; no CRP, um `FEE1` sem o estado das meias horas desta conexão ou com o dia mudado) viram `DriverNotice.DataChanged`; a sessão espera 2 s sem novos avisos e enfileira uma releitura interna de passos, que termina em `onLiveSteps(NO_REQUEST_ID, hoje)`. O driver CRP manda um `DataChanged` só por releitura (segura os seguintes até um `readSteps` terminar ou 30 s passarem), porque andando o `FEE1` chega a cada segundo e o debounce reiniciaria sem fim (ADR 28). No CRP há mais dois avisos, que só agem com o link pronto: `BatteryReported` (notificação de `2a19`) vira `onBattery(NO_REQUEST_ID, …)` (ADR 31), e `StepsUpdated` (notificação de `FEE1`, com a meia hora atual e a amostra de totais do dia montadas pelo driver) é guardado e mesclado no máximo uma vez a cada 2 s por um comando interno `ApplySteps` na fila, que termina em `onLiveSteps(NO_REQUEST_ID, hoje)` (seção 6; ADR 28). O fitGPS 0.2.1 não mandou nenhum frame espontâneo (nem `02/70`, nem `04/62`) na gravação real, inclusive em dois períodos ociosos conectados de cerca de 384 s e 233 s (depois do `atMs` 123713 e entre os `atMs` 798532 e 1031806 de `fixtures/real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl`): sem o `02/6F`, passos novos só chegam por "Refresh steps" ou sync (`protocolo-sma.md`, seção 6).
- O driver registra uma linha `SmaDriver/frame` (DEBUG) por frame de dados completo em cada sentido, com seq, `cmd/key`, nome, tamanho do value e, no RX, `crc=ok` (no CRP, `CrpDriver/frame`, sem o payload); os pacotes crus saem só por `WatchLogger.frame`, uma vez (seção 2, passo 5). O valor de cada leitura GATT bem-sucedida também sai ali, com a direção `READ` (`DriverLink.read`; no CRP, `2a29`, `2a28`, `2a19` e `fee1`; ADR 27).
- O que é lido do relógio vai para o `HistoryStore` (instantâneo em memória + `SampleStore` do host). `getStepsDays`/`getSleepSessions` respondem só a partir dele, nunca do relógio (ADR 8).

## 4. Threading

- **Uma thread lógica.** `WatchSession` cria `CoroutineScope(SupervisorJob() + context)`, com `context = Dispatchers.Default.limitedParallelism(1)` em produção. Os testes usam o construtor interno com `StandardTestDispatcher(testScheduler)` e tempo virtual, menos o `RealtimeReplayTest` e a sonda do `SessionThreadingTest` (abaixo). Todo estado mutável da máquina de estados é tocado só ali, sem travas. "Thread da sessão" quer dizer execução serial, não uma thread do sistema fixa: o `limitedParallelism(1)` garante um bloco por vez, em ordem, mas cada bloco pode rodar numa thread diferente do `Dispatchers.Default`. No iOS, o `Dispatchers.Default` do `kotlinx-coroutines` roda no pool do GCD, o mesmo que executa a fila de delegate do `CoreBluetooth`: a mesma thread do sistema pode servir à fila do host num momento e à sessão no seguinte, nunca as duas ao mesmo tempo.
- **Caixa única.** Chamadas do host e eventos do `TransportEvents` entram no mesmo `Channel(UNLIMITED)` via `trySend` (nunca bloqueia nem suspende quem chama) e são tratados em ordem por uma corrotina. O tratador nunca suspende por muito tempo: configuração GATT, handshake, comandos, sync, timeouts e o debounce rodam em corrotinas filhas.
- **Escopo por conexão.** Cada tentativa de conexão (`Attempt`) tem um escopo filho com `SupervisorJob`; quando o link cai, a sessão falha os comandos, fecha driver e link, acorda as esperas GATT com `DISCONNECTED` e cancela esse escopo. Nada da conexão antiga sobrevive para tocar a nova.
- **Leituras de qualquer thread.** `state`, `currentAddress` e `currentProfileId` são `@Volatile`. `getStepsDays`/`getSleepSessions` leem instantâneos imutáveis do `HistoryStore`, trocados por inteiro a cada gravação; `confirmUploaded` troca o conjunto de dias confirmados na hora (compare-and-set) e grava no store depois, na thread da sessão. Os instantâneos iniciais são carregados do `SampleStore` no construtor.
- **Callbacks do listener.** `WatchSessionListener` é chamado na thread da sessão, um callback por vez. Única exceção: depois de `close`, quando a thread da sessão já não existe, um pedido novo recebe `onError(CANCELLED)` na thread de quem chamou, e esse callback pode coincidir com os últimos do próprio `close`. Um evento que o host ainda entrega depois do `close` não gera callback. Exceção lançada pelo listener é engolida e registrada; não derruba a sessão. O host não deve depender da identidade da thread do callback (thread-local, thread guardada para comparar), só de que os callbacks são seriais e nunca rodam na fila de callbacks do Bluetooth dele.
- **Logger.** `WatchLogger` é chamado de mais de uma thread, às vezes ao mesmo tempo, e por isso tem de ser thread-safe (no Repo 2, o `RingLogger` do Android é sincronizado; o host iOS precisa da mesma garantia). `frame` e quase todo `log` saem na thread da sessão. Fora dela: `getStepsDays`, `getSleepSessions` e `confirmUploaded` chamam `log` na thread de quem chama, antes de retornar (dias anteriores à janela pedida, dia em formato inválido), e o construtor também, quando o `SampleStore` falha ao carregar (`SessionThreadingTest.synchronousCallsLogOnTheCallersThread`); depois de `close`, um evento que o host ainda entrega (a resposta ao `disconnect` do próprio `close`, uma notificação atrasada) é descartado com um DEBUG `Session/post` na thread do host (visto no `RealtimeReplayTest`). O logger não deve bloquear nem lançar. Se lançar, a exceção é engolida, como a do listener, nas linhas da `SessionEngine` e do `HistoryStore`: ela nunca escapa das consultas síncronas nem do construtor, e não interrompe mescla, poda nem gravação (`HistoryStoreTest.aThrowingLoggerNeverEscapes*`). O link (`frame`), o driver e o `SyncEngine` chamam o logger direto, na thread da sessão; ali ela faz a operação em andamento falhar: um `frame` que lança no RX perde a notificação, no TX falha o comando (`pendencias.md`, itens técnicos, 6).
- **Eventos do host.** `TransportEvents` pode ser chamado de qualquer thread (no Android, a thread de callbacks do `BluetoothGatt`; no iOS, a fila de delegate do `CBCentralManager`). Cada chamada só faz um `trySend` na caixa e retorna; nunca espera a sessão.
- **Conferido em tempo real.** O `RealtimeReplayTest` (`commonTest`, roda nos quatro targets) reproduz a gravação real com o construtor público, o dispatcher de produção e um host que entrega todos os eventos de uma fila serial própria (`dispatch_queue_create` no iOS, um executor de uma thread na JVM). Ele confere: os 167 TX e o histórico iguais aos do replay virtual; todo `TransportEvents` vindo da fila do host e retornando rápido (o mais lento medido ficou abaixo de 2 ms; o teste falha acima de 100 ms); nenhum callback do listener na fila do host (pela identidade da fila, `dispatch_get_specific` no iOS); e nenhum callback depois do `close`, nem com um evento atrasado do host. Os 59 callbacks da gravação rodaram em 1 a 3 threads do sistema, conforme a execução (`decisoes.md`, ADR 19). Ele também os vê em série, mas isso é observação desta carga, não prova do dispatcher: o replay é pergunta e resposta e uma corrotina só consome a caixa, então com `Dispatchers.Default` sem limite os callbacks também saem um por vez (mutação conferida na JVM e no iOS).
- **Dispatcher serial, conferido direto.** O `SessionThreadingTest` (`commonTest`, os quatro targets) lança blocos no contexto de uma sessão criada por cada construtor público (`WatchSession.sessionContext`, interno). Cada bloco espera outro, sem suspender, por até 50 ms: num dispatcher serial nunca há dois ao mesmo tempo. O controle com `Dispatchers.Default` sem limite vê dois ou mais, o que prova que a sonda enxerga paralelismo no target. Trocar o `limitedParallelism(1)` por `Dispatchers.Default` falha o teste na JVM e no iOS. A sonda não pega um dispatcher que não é paralelo mas roda na thread de quem retoma a corrotina (`Dispatchers.Unconfined`); esse cai no `RealtimeReplayTest`, com callbacks na fila do host. As duas checagens se completam.
- **Exceções inesperadas** de qualquer corrotina da sessão vão para um `CoroutineExceptionHandler` que registra `Session/uncaught` em ERROR. Os testes de sessão conferem que esse log fica vazio.

## 5. Máquina de estados

```mermaid
stateDiagram-v2
	[*] --> DISCONNECTED
	DISCONNECTED --> CONNECTING: pair (connect autoReconnect=false, timeout connectTimeoutMs)
	DISCONNECTED --> RECONNECTING: reconnect (connect autoReconnect=true, sem timeout)
	CONNECTING --> CONFIGURING: onConnected
	CONNECTING --> DISCONNECTED: timeout (CONNECT_TIMEOUT), queda (DISCONNECTED) ou vínculo apagado (PAIRING_REMOVED)
	RECONNECTING --> CONFIGURING: onConnected
	RECONNECTING --> DISCONNECTED: vínculo apagado no relógio (PAIRING_REMOVED, sem nova tentativa)
	CONFIGURING --> HANDSHAKING: serviços + CCCD + MTU ok
	CONFIGURING --> DISCONNECTED: falha no primeiro pareamento (GATT_NOT_SUPPORTED, TRANSPORT)
	CONFIGURING --> RECONNECTING: falha em modo reconexão (nova tentativa com espera)
	CONFIGURING --> DISCONNECTED: vínculo apagado, em qualquer modo (PAIRING_REMOVED)
	HANDSHAKING --> READY: handshake ok (onCompleted do pair/reconnect)
	HANDSHAKING --> DISCONNECTED: FIRST_PAIR falhou (HANDSHAKE_FAILED) ou o link caiu (DISCONNECTED)
	HANDSHAKING --> RECONNECTING: RECONNECT sem resposta ou o link caiu (nova tentativa com espera)
	HANDSHAKING --> DISCONNECTED: RECONNECT com LOGIN recusado (HANDSHAKE_FAILED, sem BIND)
	READY --> SYNCING: syncHistory
	SYNCING --> READY: fim do sync (sucesso ou erro)
	READY --> RECONNECTING: queda do link (comandos falham com DISCONNECTED)
	READY --> DISCONNECTED: queda com vínculo apagado (PAIRING_REMOVED com NO_REQUEST_ID)
	SYNCING --> RECONNECTING: queda do link
	READY --> DISCONNECTED: disconnect (UNBIND antes, se forget)
	DISCONNECTED --> BLUETOOTH_OFF: Bluetooth desligado
	READY --> BLUETOOTH_OFF: Bluetooth desligado
	RECONNECTING --> BLUETOOTH_OFF: Bluetooth desligado
	BLUETOOTH_OFF --> RECONNECTING: Bluetooth ligado, relógio pareado e sem disconnect do usuário
	BLUETOOTH_OFF --> DISCONNECTED: Bluetooth ligado, nada a reconectar
```

O diagrama omite, para legibilidade, as setas de `disconnect` e de "Bluetooth desligado" saindo de todos os estados: as duas valem de qualquer estado. Também omite as de `PAIRING_REMOVED` saindo de `HANDSHAKING` e de `SYNCING`, que levam a `DISCONNECTED` como as desenhadas. O código está em `session/SessionEngine.kt`.

**CONNECTING** (só no primeiro pareamento). `Transport.connect(address, autoReconnect = false)` e um timeout de `Quirks.connectTimeoutMs` (30 s no fitGPS). Sem `onConnected` a tempo: `Transport.disconnect`, `DISCONNECTED`, `onError(CONNECT_TIMEOUT)`.

**CONFIGURING.** Em ordem:
1. `discoverServices` (espera até 15 s). Status ≠ 0 ou silêncio → `TRANSPORT`.
2. Escolhe o primeiro `GattCandidate` do perfil cujo serviço **e** as duas characteristics (write e notify) apareceram, com UUIDs normalizados (`GattUuid.normalize`). No fitGPS: `FF60/FF61/FF62` antes do NUS (ADR 6); o firmware 0.2.1 só expõe o NUS. Nenhum → `GATT_NOT_SUPPORTED`. Das `sideNotify` do candidato ficam só as cujo serviço e characteristic apareceram; as que faltam vão para um WARN e são puladas (ADR 26).
3. `enableNotifications` na notify e depois em cada `extraNotify` do candidato, um de cada vez e na ordem (espera `commandTimeoutMs` por cada). Falha → `TRANSPORT`. Só o iDO tem `extraNotify` (`0af2`, depois de `0af7`, como a SDK); uma `extraNotify` que não apareceu na descoberta é pulada com WARN no passo 2. As notificações de todas essas characteristics vão para o canal de frames do driver.
   Depois, `enableNotifications` em cada `sideNotify` (no CRP, `2a37`, `fee1` e `2a19`, nesta ordem, depois de `fee3`), uma de cada vez, **sem ser fatal**: status de falha, silêncio em `commandTimeoutMs` ou exceção do host viram WARN e a sessão segue sem aquela notificação; só `PAIRING_REMOVED` encerra, como no resto da configuração (ADR 23). As notificações delas vão para o canal lateral do driver, com a origem (ADR 26).
4. `requestMtu(quirks.requestMtu)` com espera de 2 s, depois de todos os CCCD; falha ou silêncio → MTU 23 (ADR 5). Não é erro. Com `requestMtu` até 23 (o iDO, cuja SDK não pede MTU) o pedido não é feito e o link fica com 23 (ADR 22). O Condor Reebok pede 512, mas o próprio relógio já abriu a troca com 517 logo depois da conexão, e o host responde com o valor trocado (`protocolo-crp.md`, seção 2).
5. Cria o `SessionDriverLink` (os canais de notificações existem desde o passo 2, então nada notificado antes do driver se perde), `Drivers.create(link)`, `driver.start(escopo da conexão)` e começa a coletar `driver.notices`.

**HANDSHAKING.** `driver.handshake(FIRST_PAIR | RECONNECT)`. Cada fase (`"BIND"`, `"LOGIN"`; no CRP, `"VERSION"` nos dois modos) é anunciada como `onStateChanged(HANDSHAKING, fase)`. O BIND pode esperar `bindTimeoutMs` (60 s no fitGPS, 90 s no Life Ultra GPS) pela confirmação na tela do relógio; durante esse tempo a sessão não reconecta (ADR 4). No iDO, `"BIND"` cobre a informação básica, o bind e a autenticação, e `"LOGIN"` é a reconexão, só com a informação básica (`protocolo-ido.md`, seção 4). No CRP não há vínculo de aplicação: `"VERSION"` lê `2a29`, manda `5A 00` e, no V2/V3, `B7 0E` e `BB 16 00`; sem versão nenhuma, o driver lança `TIMEOUT`, que no primeiro pareamento vira `HANDSHAKE_FAILED` e na reconexão é uma tentativa que falhou (`protocolo-crp.md`, seção 5; ADR 25).
- `FIRST_PAIR` falhou: `DISCONNECTED` + `onError(HANDSHAKE_FAILED)` (o código original, por exemplo `TIMEOUT`, vai na mensagem). Link caiu: `onError(DISCONNECTED)`. O primeiro pareamento nunca reconecta sozinho.
- `RECONNECT` recusado pelo relógio (LOGIN recusado com os dois formatos de payload; o driver nunca manda BIND numa reconexão): `DISCONNECTED` + `onError(HANDSHAKE_FAILED)` no `reconnect` pendente, se houver, e a sessão para de tentar sozinha (inclusive ao religar o Bluetooth). Só um `pair` refaz o vínculo (ADR 11).
- `RECONNECT` falhou de outro jeito (sem resposta, link caiu): continua em `RECONNECTING` e tenta de novo, com espera crescente.

**READY.** `onStateChanged(READY, null)`, `onCompleted` do `pair`/`reconnect` pendente, e o consumidor da fila começa. Depois de `postLoginDelayMs` (3 s no fitGPS, 1 s no Life Ultra GPS, 250 ms no Condor Reebok) a sessão enfileira sozinha um `setTime` e um `enableDataChangeNotices` internos: erros deles só vão para o log. A partir daqui os avisos `BatteryReported` e `StepsUpdated` do driver passam a agir (seção 3).

**SYNCING.** Só durante um `syncHistory`; outros comandos continuam aceitos e esperam na fila. No fim (sucesso ou erro) volta para `READY` antes do terminal.

**RECONNECTING.** Entrada por queda do link depois de pronto, por falha de uma tentativa em modo reconexão, por `reconnect()` ou por Bluetooth religado. `Transport.connect(address, autoReconnect = true)` sem timeout: o host mantém a conexão pendente até o relógio voltar (no Android, `autoConnect = true`). Quedas de um link que já estava **pronto** (`READY`/`SYNCING`) reconectam na hora; tentativas que falharam (handshake sem resposta, configuração que falhou, conexão pendente que o host derrubou, link que caiu antes do `READY`) esperam 2 s, 4 s, 8 s… até 60 s, para não girar em falso. A espera volta a 2 s quando chega em `READY`.

**BLUETOOTH_OFF.** Bluetooth desligado: a tentativa é derrubada (comandos com `DISCONNECTED`; um `pair` em andamento falha com `DISCONNECTED`; um `reconnect` pendente continua pendente). A sessão não espera `onDisconnected` nenhum (contrato B, ADR 18): zera a contagem de desconexões esperadas, marca o transporte inativo e chama `Transport.disconnect` só para o host liberar recursos, sem contar. `pair` com Bluetooth desligado falha com `TRANSPORT`; `reconnect` também, mas a sessão guarda o alvo. Ao religar: se houver relógio pareado (chegou a `READY` ou veio de `reconnect`) e o usuário não pediu `disconnect`, vai para `RECONNECTING`; senão, `DISCONNECTED`. Uma conexão pendente que falhar depois de religar tem a espera crescente de sempre.

**Vínculo apagado no relógio (`PAIRING_REMOVED`, ADR 23).** Um `onDisconnected` com `GattStatus.PAIRING_REMOVED` (no iOS, `CBError.peerRemovedPairingInformation`: o telefone guarda um bond que o relógio apagou), ou uma descoberta ou CCCD com esse status, encerra a tentativa em qualquer modo e etapa: comandos com `DISCONNECTED`, `Transport.disconnect` se o link ainda estiver aberto, `DISCONNECTED`, e nenhuma tentativa agendada. O `pair`/`reconnect` pendente termina com `onError(PAIRING_REMOVED)`, mensagem começando por `SessionEngine.PAIRING_REMOVED_HINT`; sem pedido pendente (reconexão da própria sessão), sai `onError(NO_REQUEST_ID, PAIRING_REMOVED, …)`. Como no LOGIN recusado, o relógio deixa de contar como pareado para o Bluetooth religado; um `reconnect`/`pair` do host tenta uma vez.

**pair/reconnect com outra conexão em andamento.** Não existe `BUSY`: a tentativa atual é derrubada (`Transport.disconnect`, comandos e pedido pendente com `CANCELLED`) e a nova começa. `reconnect` para o mesmo relógio já pronto termina na hora; para o mesmo relógio já reconectando, o pedido novo substitui o anterior.

**disconnect(forget).** Para de reconectar e cancela o pedido de ciclo de vida e os comandos (`CANCELLED`). Com `forget` e a sessão pronta, envia UNBIND pelo driver (no SMA, `03/05`; no CRP, `F8`, sem esperar resposta; o iDO não tem bytes e falha sem escrever), esperando no máximo `commandTimeoutMs` (falha ou silêncio não impedem o resto). Depois `Transport.disconnect`, `DISCONNECTED`, e, com `forget`, `SampleStore.clear()`, instantâneos zerados e `currentAddress`/`currentProfileId` nulos. Termina com `onCompleted`.

**Desconexões esperadas.** Quando a sessão chama `Transport.disconnect` com link aberto ou conexão pendente, o host responde com um `onDisconnected` que pode chegar quando outra tentativa já começou (ex.: `pair` novo logo depois do anterior). A sessão conta essas respostas e ignora o mesmo número de `onDisconnected`; a contagem zera no próximo `onConnected`, porque o host é serial (ADR 12). O Bluetooth desligado é a exceção: ali nenhuma resposta vem, e a contagem zera (ADR 18). Um `onDisconnected` sem tentativa em andamento e fora da contagem é registrado em WARN (`link lost … with no connection attempt`): é o rastro de um host fora do contrato.

### Contrato que a sessão assume do host

- Toda operação com `requestId` termina com exatamente um evento com o mesmo id (já está no KDoc de `Transport`).
- `Transport.disconnect` termina com **um** `onDisconnected` quando havia link aberto ou conexão pendente, e com nenhum quando não havia. Um host que nunca responde ao cancelar uma conexão pendente faz a sessão ignorar a próxima queda verdadeira antes do próximo `onConnected` (ver `docs/decisoes.md`, ADR 12).
- O host entrega os eventos na ordem em que aconteceram (um `onDisconnected` antigo antes do `onConnected` novo).
- Um `connect` que falha sem chegar a conectar termina com **um** `onDisconnected`: a conexão direta que falha e o `connect` que o host recusa na hora (Bluetooth desligado, sem permissão, endereço inválido). Só a conexão pendente (`autoReconnect = true`) que falha antes de conectar pode ficar sem evento, porque o host a mantém até o relógio voltar ou até um `disconnect`; a exceção é o vínculo apagado no relógio, em que o host não re-arma e entrega o `onDisconnected` com `GattStatus.PAIRING_REMOVED` (ADR 23). Sem esse evento, um `reconnect` (sem timeout) ficaria preso em `RECONNECTING`; num `pair`, só o timeout de conexão encerraria a tentativa, e o `disconnect` dele contaria uma resposta que não vem (ADR 12).
- **Bluetooth desligado, contrato B** (ADR 18): o host entrega `onBluetoothStateChanged(false)` e nenhum `onDisconnected` pelos links abertos e conexões pendentes que isso derrubou, nem antes nem depois. Depois disso, `disconnect()` não emite nada até um novo `connect()`. Um `onDisconnected` que o host já tinha entregado antes do `onBluetoothStateChanged(false)` (o stack reportou a queda antes do aviso) é uma queda comum: a sessão passa por um `RECONNECTING` breve, com um `connect` para o adaptador que está desligando, e vai para `BLUETOOTH_OFF` quando o aviso chega. Na gravação real, o host Android antigo entregou três `onDisconnected` para um link só, todos fora do contrato B: o primeiro saiu do próprio tratamento do desligamento, logo antes de repassar o aviso (log, ts 1793272958346 e 1793272958357), e a sessão o tratou como essa queda comum; os outros dois chegaram depois do aviso. O host Android agora suprime os três, e a sessão vai direto para `BLUETOOTH_OFF`.

## 6. Fila de comandos

- Aceita só em `READY`/`SYNCING` e se a conexão não estiver sendo encerrada; fora disso, `onError(NOT_READY)` na hora.
- FIFO, um comando por vez, no escopo da conexão. Comandos internos (acerto de hora pós-login, `enableDataChangeNotices`, releitura depois de `02/70` ou de um `DataChanged` do CRP, `ApplySteps` dos passos ao vivo) usam a mesma fila.
- `readDeviceInfo` → `onDeviceInfo(DeviceInfo(profileId, address, bleName, firmware, bleVersion))`. O `bleName` é o primeiro nome do perfil: o `pair` recebe só o endereço. Nos perfis sem prefixo é o mesmo nome que o scan viu; no Condor Reebok é `P85`, o nome real do modelo, mesmo que o scan tenha visto uma variação aceita pelo prefixo (`DeviceRegistry.identify`). No CRP, `firmware` é o texto do relógio (`MOY-VS02-2.0.0`) e `bleVersion` é a versão do protocolo (`MOYOUNG-V2`).
- `readBattery` → `onBattery`, com `Battery(percent, charging)` do `DeviceDriver.readBatteryStatus` (no CRP, `2a19` acima de 100 = carregando; no SMA e no iDO, sempre `false`; ADR 31); `setTime` → `onCompleted` (hora e fuso do `WatchClock`); `refreshSteps` → lê passos, mescla e `onLiveSteps(id, hoje)`.
- `ApplySteps` (interno, `NO_REQUEST_ID`, nome `applyLiveSteps`): mescla as amostras que o driver montou com os passos ao vivo e emite `onLiveSteps(NO_REQUEST_ID, hoje)`. A sessão agenda no máximo um a cada `LIVE_STEPS_THROTTLE_MS` (2 s), sempre com a amostra mais nova de cada instante; passar pela fila mantém a ordem com um sync em andamento (ADR 28).
- `syncHistory(kinds)` → `SYNCING`, `onSyncStarted`, `onSyncProgress(0)`, passos (obrigatório), progresso, sono (exploratório: erro ou vazio só no log), `onSyncProgress(100)`, poda por `historyRetentionDays`, `READY`, `onSyncFinished`. Lista vazia = todos os tipos; a ordem é sempre passos → sono.

## 7. Histórico e consultas

- **Mescla.** Passos por `atEpochMillis` e sono por `startEpochMillis`: a leitura mais nova substitui a antiga. A chave é o instante já convertido com o fuso do telefone, não o relógio de parede cru do relógio (ADR 16). Cada mudança grava a lista inteira no `SampleStore`.
- **Janela da mescla de passos.** Antes de mesclar, a sessão descarta as amostras cujo dia (no fuso do telefone) é anterior a `hoje - historyRetentionDays`, a mesma fronteira da poda, e as do futuro: dia depois de hoje **e** mais de 3 h à frente de agora. Vale para `refreshSteps` e para `syncHistory`. Sem isso, um registro `mode 0` com data errada (relógio desacertado) viraria um dia pendente que nunca sai: a poda não apaga o futuro, e o `refreshSteps` não poda. A folga de 3 h guarda os passos reais de um relógio que ainda está no fuso antigo perto da meia-noite (telefone que foi para oeste, sync antes do acerto de hora da conexão); acima dela a amostra sai e, se o relógio apaga o que foi lido (H2), se perde (ADR 16, emenda). O log `Session/mergeSteps` diz quantas saíram (WARN quando alguma é do futuro) e quantas de amanhã entraram pela folga (INFO).
- **Agregação diária** (`StepsAggregator`), no fuso de `WatchClock.timeZoneId()` na hora da consulta, segundo `Quirks.stepsAggregation` do perfil atual (ou do fitGPS, sem perfil): `DAY_CUMULATIVE`, o do fitGPS e do Life Ultra GPS, usa a amostra de mais passos do dia, com as calorias e a distância dela, porque no firmware 0.2.1 o `mode 0` é o acumulado do dia; `HOURLY_SUM`, o do Condor Reebok, soma passos, calorias e distância das amostras (ADR 1). No CRP as amostras são as meias horas do histórico, com calorias e distância zeradas, mais uma amostra de totais do dia (início do dia + 1 s, `steps = 0`, as kcal e os metros do relógio), então a soma dá os passos das meias horas e os totais do relógio (ADR 28).
- **`getStepsDays(daysBefore)`**: todos os dias até hoje que têm amostra e não foram confirmados, em ordem crescente. `daysBefore` não corta os dias mais velhos, como nos dois Cores atuais (ADR 15): um dia não enviado de quem ficou mais tempo sem abrir o app volta mesmo quando o app pede 3 dias; o log registra quando isso acontece. **`onLiveSteps`** traz hoje mesmo sem amostra (zerado) e mesmo se hoje já foi confirmado.
- **`getSleepSessions(daysBefore)`**: sessões cujo `dayKey` (dia em que a pessoa acordou) vai até hoje, sem os dias confirmados de `SLEEP`; mesma regra de `daysBefore`. No CRP o `dayKey` é a data de sono da SDK, com a virada às 20h: uma noite lida às 20h ou depois só aparece no dia seguinte (ADR 29).
- **Poda**: depois de cada sync, amostras, sessões e dias confirmados anteriores a `hoje - historyRetentionDays` (30 no fitGPS) saem do store.

## 8. Erros

Internamente tudo é `WatchException(code, message)`; nada atravessa a API pública: a sessão converte em `onError(requestId, code, message)`. Códigos (`WatchErrorCodes`):

| Código | Quem produz |
|---|---|
| `NOT_READY` | comando fora de `READY`/`SYNCING`; driver recusando comando antes do LOGIN |
| `UNSUPPORTED_DEVICE` | `pair`/`reconnect` com `profileId` desconhecido |
| `CONNECT_TIMEOUT` | primeiro pareamento sem `onConnected` em `connectTimeoutMs` |
| `GATT_NOT_SUPPORTED` | nenhum candidato GATT do perfil na descoberta |
| `HANDSHAKE_FAILED` | qualquer falha do handshake no primeiro pareamento (recusa, timeout, protocolo); numa reconexão, só o LOGIN recusado |
| `TIMEOUT` | comando sem ACK ou sem resposta (GET não tem retentativa); no CRP, página de passos sem resposta, firmware sem `5A 01` nem `2a28`, e a falta de versão no handshake (na reconexão; no primeiro pareamento vira `HANDSHAKE_FAILED`) |
| `PROTOCOL` | frame inválido, resposta indecifrável, exceção inesperada do driver |
| `TRANSPORT` | status GATT de falha, host que não confirma uma operação, Bluetooth desligado em `pair`/`reconnect` |
| `DISCONNECTED` | link caiu com o pedido em andamento ou na fila |
| `PAIRING_REMOVED` | o host entregou `GattStatus.PAIRING_REMOVED` (o relógio apagou o vínculo do sistema que o telefone guarda) num `onDisconnected`, numa descoberta ou num CCCD, em `pair` ou `reconnect`, em qualquer etapa; sem pedido pendente, com `NO_REQUEST_ID` (ADR 23) |
| `CANCELLED` | `disconnect`, `close`, ou um `pair`/`reconnect` posterior substituiu o pedido |
| `NO_DATA`, `BUSY` | reservados; a sessão atual não emite (sono vazio é só log; não há `BUSY`, ver seção 5). O host pode usar `NO_DATA` por conta própria: o Core RN usa quando `getHistorySleep` não tem nenhuma noite gravada |

**Regra do callback terminal.** Cada pedido com `requestId` termina com **exatamente um** terminal (`onDeviceInfo`, `onBattery`, `onLiveSteps`, `onSyncFinished`, `onCompleted` ou `onError`). Como é garantido:
- `pair`/`reconnect`: o pedido fica num campo único da sessão e sai dele ao terminar (sucesso, erro ou substituição).
- Comandos: `SessionCommand.markFinished()` só devolve `true` uma vez; todo terminal passa por ele. Ao derrubar a conexão, a sessão falha todos os comandos aceitos e ainda não terminados (inclusive um que o canal já entregou ao consumidor, mas que ainda não começou), antes de cancelar o escopo.
- Recusas imediatas emitem e retornam. Depois de `close`, pedidos novos recebem `CANCELLED` na hora.
- Comandos internos usam `NO_REQUEST_ID` e nunca emitem `onError`; o host não deve usar `-1` como id. O único `onError` da sessão com `NO_REQUEST_ID` é o `PAIRING_REMOVED` de uma reconexão que a própria sessão começou (ADR 23); ele não é terminal de nenhum pedido. Também não são terminais o `onLiveSteps` e o `onBattery` com `NO_REQUEST_ID` (releitura interna, `ApplySteps` e o aviso de bateria do CRP). O `CLASSIC_BOND_FAILED` com `requestId` `-1` é do `CoreBridge`, não da sessão (seção 13).
- Teste: `TerminalCallbackInvariantTest` percorre sucesso, recusa, timeout, queda, Bluetooth, substituição, `disconnect(forget)` e `close` e confere um terminal por id.

## 9. Como um `VendorSdkDriver` entraria

O iDO, que este plano usava de exemplo, ganhou driver próprio (`ido/`, ADR 22). Famílias que ainda dependam da SDK do fabricante não têm codec próprio: a SDK fala com o relógio. O ponto de costura é `driver/HostDriver` (pública e vazia nesta PoC):

1. Definir em `HostDriver` métodos de callback simples (sem `suspend`, que o Swift não implementa), espelhando `DeviceDriver`: `handshake(mode, callback)`, `readBattery(callback)`, `readSteps(callback)` etc., e um canal de avisos.
2. Criar um `VendorSdkDriver` interno que implementa `DeviceDriver` adaptando os callbacks com `suspendCancellableCoroutine` e ignora o `DriverLink` (a SDK é dona do BLE).
3. `Drivers.create` passa a receber o `HostDriver` registrado pelo host para a família, e o perfil ganha uma `Family` nova.
4. Para essas famílias, o `Transport` do host não abre GATT: a sessão precisaria de um modo em que `CONFIGURING` é pulado (o driver faz conexão e handshake). Essa é a única mudança na máquina de estados, e fica para quando o primeiro adaptador existir.

O resto (fila, sync, store, regra do terminal, listener) não muda.

## 10. Testes da sessão

- O kit de teste, em `protocol/src/testKit/` (pastas que o `:protocol` e o `:bridge` compilam nos próprios testes; `protocol/src/testKit/README.md`): `FakeTransport` (host síncrono e controlável; `FakeTransport.scriptedIdo` e `scriptedCrp` montam os do iDO e do CRP; `replyBeforeWriteCompleted` entrega a resposta antes da confirmação da escrita; `notifyOn` notifica em qualquer characteristic; `onRead`/`ReadReply` respondem leituras; `notificationsStatusOf` faz uma CCCD falhar ou ficar sem resposta), `SmaWatchSimulator` (relógio SMA em nível de byte, compartilhado com os testes do driver), `IdoWatchSimulator` (o Life Ultra GPS em nível de byte, com as respostas do log da SDK, a confirmação do vínculo agendada no tempo virtual, recusa, já vinculado, `pairFlag` 0, `03/01` recusado, respostas curtas e, no meio, pacotes V3 e frames com o mesmo `cmd` e outra `key`), `CrpWatchSimulator` (o P85 em nível de byte, com as respostas reais da captura do app de produção: resposta por `(cmd, sub)` e por leitura, páginas `59`, sono legado ou `B6`, `33` sondável, silêncio configurável, byte 2 `0x10`/`0x20`, respostas fragmentadas e os empurrões de `FEE1`, `2A19`, `F9` e `2A37`), `ReplayDriver` + `PacketMasker` (`TxMasker` do SMA, `IdoTxMasker` do iDO, `CrpTxMasker` do CRP; replay de gravações; um pedido é um TX ou uma leitura, respondida com o `READ` gravado, e cada RX sai na characteristic gravada), `ReplayScenario`, `IdoReplayScenario` e `CrpReplayScenario` (deduzem as ações de uma gravação SMA, iDO ou CRP; o do CRP reconhece a releitura que a sessão faz sozinha depois de um `FEE1`, que não é ação, `fixtures.md`, seção 4; `runCrpReplay`; a ação `ReplayAction.RefreshSteps`, com os mesmos bytes de um `Sync([STEPS])`, nenhum `deduce` produz e só um roteiro escrito à mão usa), `RecordingListener`, `SessionTestLogger`, `TestClock`, a `SessionHarness` (`runSession`) e a leitura das gravações nos quatro targets (`RecordingLoader`, o `expect` de arquivo `FixtureFiles`, com os `actual` em `testKitJvm/` e `testKitIos/`). O `:bridge` usa o mesmo kit para testar o `CoreBridge` sobre uma sessão de verdade (seção 13).
- `commonTest/.../session/`: `WatchSessionLifecycleTest`, `WatchSessionCommandsTest`, `BluetoothOffContractTest` (contrato B), `TerminalCallbackInvariantTest`, `HistoryStoreTest`, `IdoSessionTest` (o Life Ultra GPS pela sessão: CCCD `0af7` e `0af2`, bind com confirmação, recusa, timeout, bind sem reenvio, reconexão sem autenticação, comandos, resposta antes da confirmação da escrita, `03/01` recusado, passos do dia, esquecer), `CrpSessionTest` (o Condor Reebok pela sessão, 25 testes: CCCDs `fee3`, `2a37`, `fee1`, `2a19`, handshake de versão nos dois modos, versão só pela notificação, V1, falta de versão, acerto de hora com o atraso, regra de carga, bateria espontânea, firmware pelo `2a28`, meias horas e totais, `5D`/`5C` nunca pedidos, passos ao vivo no máximo a cada 2 s, releitura sem estado (uma só durante uma caminhada, e de novo só 30 s depois de uma que falhou), respostas em pedaços de 20 bytes com `b2 = 0x10`, parcial descartada pelo prazo entre pedaços com o pedido vencendo depois, sono como a SDK, `B6` no lugar do `32`, `32` sem resposta, sonda do `33`, `F8` no esquecer, `F9` só no log, CCCD lateral que falha ou falta). Todos com `runTest` e tempo virtual; `runSession` fecha a sessão mesmo quando uma asserção falha (senão uma reconexão em ciclo não deixaria o teste terminar).
- `commonTest/.../testing/` tem o que é só do `:protocol`: `SyntheticFitGpsFixture`, `SyntheticCondorReebokFixture` e o replay em tempo real, `RealtimeReplay` e a `HostQueue` (fila serial do host, `expect`/`actual`; os `actual` em `src/jvmSharedTest/`, compilado por `jvmTest` e `androidUnitTest`, e em `iosTest/`).
- `commonTest/.../session/RealFixturesReplayTest` (toda gravação SMA em `fixtures/real/` e a sintética, uma linha `REPLAY` por arquivo no stdout de cada target; as de outra família saem como `skipped`) e `IdoRealFixturesReplayTest` (as gravações iDO, com `IdoReplayScenario` e `IdoTxMasker`, e os valores de cada gravação nomeada), `RealFixturesStepsTest` (o total do dia das gravações reais do fitGPS; linha `STEPS-DAY`), `RealFixturesSleepTest` (a noite da gravação do iPhone contra a saída da SDK; linha `SLEEP`) e `RealtimeReplayTest` (a gravação real no dispatcher de produção, em tempo real, seção 4; linha `REALTIME-REPLAY`); `CrpSyntheticReplayTest` (replay estrito da sintética do Condor Reebok, `TX matched 12/12, READ matched 3/3`, e o mesmo replay com o relógio 7 s depois do `recordedAt`, que só casa com o `CrpTxMasker`), `CrpReferenceFixtureTest` (decodifica as fixtures de referência derivadas da captura HCI do app de produção; ADR 32) e `CrpRealFixturesReplayTest` (as gravações CRP de `fixtures/real/`; desde 2026-11-01, a do Android e a do iPhone, que a listagem tem de achar, cada uma com a linha `REPLAY` e um teste nomeado dos valores, linhas `STEPS-DAY` e `SLEEP`); `SessionThreadingTest` (o dispatcher dos construtores públicos é serial; o logger nas consultas síncronas, seção 4).
- `jvmTest/`: só o que precisa da JVM: `RecordingLoaderTest` (o `java.time` como oráculo do `recordedAt`; `READ` e chaves extras no cabeçalho), `SmaSessionReplayTest` e `CrpSessionReplayTest` (as gravações sintéticas são a saída dos geradores; regravação com `FIXTURES_REGENERATE=1`), `Hci2JsonlTest` e `Btsnoop2JsonlTest` (rodam o `python3`; o segundo monta um btsnoop sintético num diretório temporário e confere a redação, a trava contra os endereços do relógio, do telefone e de um par clássico, o rótulo com chave da captura e que nenhum rótulo real está versionado).
- `commonTest/.../crp/`: `CrpFrameGoldenTest`, `CrpFrameCodecTest`, `CrpReassemblerTest`, `CrpPayloadsTest`, `CrpSleepAssemblerTest`, `CrpStepsMapperTest`; `commonTest/.../profile/DeviceRegistryTest` (nomes e prefixo do Condor Reebok, nomes exatos do SMA e do iDO).
- `iosTest/.../ios/ByteBridgeTest`: a ponte de bytes do iOS (seção 11).

## 11. Ponte de bytes do iOS

No Swift, todo `ByteArray` da API pública aparece como `KotlinByteArray`, que só se lê byte a byte, e o `CoreBluetooth` só fala `Data`. O pacote `br.com.grupotechnos.watch.protocol.ios` (só no `iosMain`, compilado para `iosArm64` e `iosSimulatorArm64`) fecha essa distância sem mudar a API comum:

| Sentido | Onde o host encontra os bytes | Swift |
|---|---|---|
| sessão → host | `Transport.write(service:characteristic:value:type:requestId:)`, `WatchLogger.frame(direction:characteristic:value:summary:)` (`value: KotlinByteArray`) | `let data: Data = value.toData()` |
| host → sessão | `peripheral(_:didUpdateValueFor:error:)` de uma notificação (`characteristic.value: Data?`) | `session.events.onNotification(characteristic: uuid, data: value)` |
| host → sessão | o mesmo callback, de uma leitura pedida por `Transport.read` | `session.events.onReadCompleted(requestId: id, data: value, status: status)` |
| `Data` → `KotlinByteArray` | o resto da API que recebe bytes (`FrameJsonl.line`) | `ByteBridgeKt.toByteArray(data)` |

```mermaid
flowchart LR
	cb["CBPeripheral<br/>(fila de delegate)"]
	ev["session.events<br/>onNotification(characteristic:data:)"]
	inbox["caixa da sessão<br/>(cópia em ByteArray)"]
	tr["Transport.write(value: KotlinByteArray)"]
	w["value.toData() → writeValue(_:for:type:)"]
	cb -- "Data" --> ev --> inbox
	inbox -. "thread da sessão" .-> tr --> w --> cb
```

- Toda conversão copia (`initWithBytes:length:` num sentido, `getBytes:length:` no outro): o host pode reutilizar o buffer, e mudar a origem depois não muda o resultado. Tamanho 0 não toca em endereço (um `NSData` vazio pode ter `bytes` nulo; um `ByteArray` vazio não tem elemento 0 para fixar).
- As sobrecargas com `NSData` chamam o método comum: mesmo evento, mesma ordem, mesmo "retorna na hora, de qualquer thread". O rótulo é `data:`, e não `value:`, para não colidir no Objective-C com os métodos que recebem `KotlinByteArray`; o nome `toData()` vem de `@ObjCName`.
- O `value` de `WatchLogger.frame` vai direto para `FrameJsonl.shared.line(...)`, sem conversão.
- Tabela completa, invariantes e testes: `protocol/src/iosMain/.../ios/README.md`. A decisão: `decisoes.md`, ADR 19. O MTU que o host iOS informa (`maximumWriteValueLength(for: .withoutResponse) + 3`): KDoc de `Transport.requestMtu` e ADR 5, emenda.

## 12. Contrato JS comum (`:bridge`)

O Core React Native (`react-native-technos-core-next`) promete ao JavaScript os nomes e formatos do Core atual (`src/types/events.ts`, `src/specs/NativeCoreNext.ts` de lá). A parte desse contrato que não depende de plataforma mora no `:bridge`, em código comum, para os hosts Android e iOS entregarem exatamente o mesmo:

| Tipo | O que garante |
|---|---|
| `EventMapper` | O payload de cada evento e retorno (`onScannedDevice`, `onConnectStateChanged`, `onDeviceInfo`, `onFirmwareInfo`, `onBatteryStatus`, `onPedometer`, `onHistoryPedometer`, `onHistorySleep`, `onSyncProgress`, `onError`, `onLog`, `getLastPairedDevice`) como `Map<String, Any?>`; o `date` do sono pelo início do dia no fuso do telefone; o `charging` do `onBatteryStatus` vem de `Battery.charging` (no CRP, `2a19` acima de 100; ADR 31). |
| `StateMapper` | `ConnectionState` → `deviceConnectStates` (0, 1, 2, 4) e o `isConnected`. |
| `SavedDataMapper` | `deletedSavedData`: a categoria do Core atual → `DataKind`, e cada `registered_at` → dia local, com as mesmas leituras (e recusas) do `java.time` do host Android antigo. |
| `HostErrorCodes` | Os códigos que o host produz antes de chamar a sessão (`PERMISSION_DENIED`, `BLUETOOTH_OFF`, `INVALID_ARGUMENT`, `IO_ERROR`) e o do vínculo Bluetooth clássico (`CLASSIC_BOND_FAILED`, só `onError`, seção 13). |
| `PendingEvents` | A fila de eventos enquanto o módulo JS não pode emitir (antes de o JS criar o TurboModule, entre um reload e outro): entrega em ordem, dentro de uma trava reentrante, capacidade 500. |
| `RequestRegistry` | `requestId` → quem liquida a Promise e o nome do método JS (o `method` do `onError`), um terminal só; ids de 1 a `Int.MAX_VALUE`, nunca `-1`. |
| `ios/FoundationConverter` (só iOS) | Os payloads como `NSDictionary`/`NSArray` com `CFBoolean` de verdade (seção 11 tem a ponte de bytes; esta é a dos payloads). |

```mermaid
flowchart LR
	session["WatchSession<br/>(:protocol)"]
	mapper["EventMapper<br/>StateMapper<br/>(:bridge, comum)"]
	queue["PendingEvents<br/>(:bridge, comum)"]
	wc["WritableConverter<br/>(Android, Repo 2)"]
	fc["FoundationConverter<br/>(:bridge, iosMain)"]
	js["emitOnX → JavaScript"]
	session -- "domínio" --> mapper -- "payload (Map)" --> queue
	queue -- "Android" --> wc --> js
	queue -- "iOS" --> fc --> js
```

- **Tipos dos valores**: `Int` para inteiros, `Double` para decimais e epoch millis, `String`, `Boolean`, `null`, mapas e listas aninhados. É o que o `WritableConverter` do Android sempre recebeu; o `FoundationConverter` aplica as mesmas regras no iOS e põe os booleanos como `CFBoolean`, porque o React Native separa `true`/`false` de número pela classe (`__NSCFBoolean`), e o `KotlinBoolean` que o Kotlin/Native gera viraria `1`/`0`.
- **Datas**: `IsoDates` (interno) lê e escreve o texto à mão, reproduzindo o `java.time`; o `kotlinx-datetime` faz só o fuso. Prova: `IsoDatesDifferentialTest` (ADR 20).
- **Fuso**: o do telefone na hora da chamada (`TimeZone.currentSystemDefault()`); as sobrecargas com fuso são internas, porque um tipo do `kotlinx-datetime` não pode ir para o header ObjC.
- **Exceções no iOS**: exceção Kotlin que cruza para o Swift sem `@Throws` encerra o processo. `EventMapper.startOfDayIso`, `sleepDay` e `historySleep`, que lançam `IllegalArgumentException` com um `dayKey` inválido, têm `@Throws`: no Swift são `throws`, e o erro chega como `NSError` (ADR 20).
- **Threads**: `EventMapper`, `StateMapper` e `SavedDataMapper` não têm estado. `PendingEvents` e `RequestRegistry` são chamados de qualquer thread (a da sessão, a do módulo nativo, a principal) e usam uma trava reentrante de plataforma (`ReentrantLock` na JVM, `NSRecursiveLock` no iOS).
- **Framework iOS**: o `:bridge` gera o `TechnosWatchProtocol` com o `:protocol` exportado (`export(project(":protocol"))`): um framework só, prefixo `TWP`, os nomes de sempre no Swift (`WatchSession`, `EventMapper`, `FoundationConverter`), e os tipos do `:protocol` uma vez só. `./gradlew assembleTechnosWatchProtocolXCFramework` → `bridge/build/XCFrameworks/{debug,release}/TechnosWatchProtocol.xcframework`.
- **O controlador** que implementa cada método JS (checagens do host, registro do pedido, regra do `NO_DATA`, pareamento salvo, ciclo de vida) e o lado `WatchSessionListener` também é comum: `CoreBridge`, seção 13. Tabela, invariantes e testes de cada tipo: `bridge/src/commonMain/.../bridge/README.md` e `bridge/src/iosMain/.../bridge/ios/README.md`.

## 13. Controlador comum (`CoreBridge`)

O `CoreBridge` implementa, em código comum, cada método JS de `src/specs/NativeCoreNext.ts` (Repo 2) e o lado `WatchSessionListener` da sessão. Ele veio do host Android antigo (`NativeCoreNextModule`, `CoreSessionListener` e as regras do `CoreGraph`) sem mudar nada que o JavaScript observa: os mesmos eventos e payloads, a mesma ordem (o evento sai antes do `resolve`), as mesmas rejeições, o mesmo `NO_DATA`, as mesmas regras do pareamento salvo e da reconexão automática, o mesmo ciclo de vida. O TurboModule de cada plataforma vira uma casca: cada método chama o de mesmo nome do `CoreBridge` com uma `CoreCompletion` que embrulha a Promise. Decisão e custos: `decisoes.md`, ADR 21.

```mermaid
flowchart LR
	js["JS (TechnosCore)"]
	tm["TurboModule<br/>(casca: um método = uma chamada)<br/>é o CoreEventSink"]
	core["CoreBridge (:bridge, comum)<br/>regras, RequestRegistry, PendingEvents,<br/>listener da sessão"]
	host["CoreHost (plataforma)<br/>permissões, scan, sync em 2º plano,<br/>pareamento salvo, gravação, log"]
	session["WatchSession (:protocol)"]
	js -- "método + Promise" --> tm -- "método + CoreCompletion" --> core
	core -- "comandos" --> session
	session -- "WatchSessionListener" --> core
	core -- "consultas e efeitos" --> host
	core -- "emit(nome, payload)" --> tm -- "emitOnX" --> js
	host -- "onScannedDevice, onScanFailed,<br/>onLogEntry, ganchos de Bluetooth" --> core
```

**Montagem, uma vez por processo.** O host cria a `WatchSession` com o próprio `Transport`, `WatchLogger` e `SampleStore`, liga o transporte aos `TransportEvents` e passa a sessão, o logger e o `CoreHost` ao construtor `CoreBridge(session, logger, host)`, que chama `session.setListener`. A sessão vem pronta, e não é montada dentro do controlador, porque o host já a precisa para ligar o transporte, porque o construtor da `WatchSession` é o contrato do `:protocol` que o Swift já usa, e porque os testes do controlador montam uma `WatchSession` de verdade em tempo virtual (com o dispatcher de teste, o que só um teste consegue).

**Quem faz o quê.**

| `CoreBridge` (comum) | Host (cada plataforma) |
|---|---|
| Ordem das checagens e as rejeições antecipadas (`PERMISSION_DENIED`, `BLUETOOTH_OFF`, `INVALID_ARGUMENT`, `UNSUPPORTED_DEVICE`), cada uma com `onError` (`requestId` `-1`) antes do `reject` | Responder às consultas: permissão de scan e de conexão, Bluetooth ligado, endereço válido (MAC no Android, UUID no iOS) |
| `resolveProfile(name)` (`appName`, nome BLE ou `id`), o `requestId` de cada comando (`RequestRegistry`) e a liquidação no callback terminal | Embrulhar a Promise numa `CoreCompletion` (no iOS, o valor passa pelo `FoundationConverter`) |
| A fila de eventos (`PendingEvents`) e quando entregá-la: no `attach`, a cada evento, no `onHostResume` | Ser o `CoreEventSink`: `canEmit` e o `emitOnX` de cada nome |
| Os efeitos do listener, na ordem do host antigo: pareamento salvo em todo `READY` (salvo depois de um disconnect/forget do usuário), o pedido do vínculo Bluetooth clássico no `READY` que fecha um handshake de um perfil com `Quirks.classicBond` (abaixo), trabalho em segundo plano ligado no `onSyncStarted` e desligado no fim, no erro do sync ou fora de `READY`/`SYNCING`, cabeçalho da gravação (o nome quando outro relógio chega ao handshake, em `HANDSHAKING`, com o firmware anterior apagado; nome e firmware no `onDeviceInfo`; ADR 24); depois o evento; depois a liquidação | Executar os efeitos: scan, FGS (Android) ou tarefa de segundo plano (iOS), guardar o pareamento, o cabeçalho da gravação de frames, o vínculo Bluetooth clássico (pedir, conferir, remover) |
| A regra do `NO_DATA` do `getHistorySleep`, a janela do sono (retenção do perfil), o `deletedSavedData` (strings ou mapas, categoria, datas no fuso do telefone) | Converter os argumentos do JS para Kotlin (no Android, `ReadableConverter.toList`, pelo `getType` de cada item) |
| `userDisconnected`, `reconnectIfPaired` (pareamento salvo, ninguém desconectou, sessão em `DISCONNECTED`/`BLUETOOTH_OFF`, Bluetooth e permissão), `emitCurrentState` | Avisar os ganchos: módulo destruído (`invalidate`), app de volta ao primeiro plano (`onHostResume`), Bluetooth desligado/ligado, relógio achado no scan, falha do scan, entrada nova no log |
| `getLogs` (entradas → `EventMapper.log`), `setLogLevel` (0..3, inválido só loga) | O log em memória, o export para arquivo e o nível |
| — | `Transport` (BLE), `SampleStore`, `WatchLogger`, o Bluetooth já desligado na partida (no Android, `reportBluetoothOffAtStartup`) |

**`CoreHost` por plataforma.** A coluna do Android é o `AndroidCoreHost` do Repo 2 (montado pelo `CoreGraph`), que só delega para as mesmas peças que o host antigo usava; a do iOS é o plano do `CoreGraph.swift`. No Android o TurboModule já é a casca descrita acima: cada `override` é uma chamada ao `CoreBridge` com um `PromiseCompletion` (mapas e listas pelo `WritableConverter`), e a prova de equivalência com o host antigo está no `docs/arquitetura.md` do Repo 2.

| Método | Quando o `CoreBridge` chama | Android (`AndroidCoreHost`) | iOS (planejado) |
|---|---|---|---|
| `missingScanPermission()` | `scan` | `BlePermissions.hasScanPermission` / `scanPermissionName()` (`android.permission.BLUETOOTH_SCAN` na API 31+, `ACCESS_FINE_LOCATION` na 30-) | `CBManager.authorization` diferente de `.allowedAlways` → um nome para a mensagem (ex.: `"Bluetooth"`); `null` quando autorizado |
| `missingConnectPermission()` | `pairDevice`, `startPairingAttempt`, reconexão automática | `"BLUETOOTH_CONNECT"` sem `BlePermissions.hasConnectPermission` (API 31+); `null` na 30- | a mesma autorização do `CBManager` |
| `isBluetoothEnabled()` | `scan`, conexão, reconexão automática | `BlePermissions.isBluetoothEnabled` | `CBCentralManager.state == .poweredOn` |
| `isValidAddress(address)` | `pairDevice`, `startPairingAttempt` | `BluetoothAdapter.checkBluetoothAddress` | `UUID(uuidString:) != nil` |
| `startScan()` / `stopScan()` | `scan`; `pairDevice`, `stopScan`, Bluetooth desligado | `BleScanner.start/stop` | `scanForPeripherals` sem filtro de serviço / `stopScan` |
| `startSyncWork()` / `stopSyncWork()` | `onSyncStarted`; fim, erro ou queda do sync | `SyncForegroundService.start/stop` | `beginBackgroundTask` / `endBackgroundTask` |
| `loadPairing()` / `savePairing()` / `clearPairing()` | `getLastPairedDevice`, reconexão, janela do sono; `READY`; `forgetDevice` (duas vezes) | `PairingPrefs` (`SharedPreferences` `corenext.pairing`) | `UserDefaults` |
| `setRecordingDeviceName()` / `setRecordingFirmware()` | handshake (`HANDSHAKING`) de um relógio diferente do que o cabeçalho nomeia (o firmware vai `null`), `onDeviceInfo` | `FrameRecorder.deviceName/firmware` | `FrameRecorder.deviceName/firmware` do iOS |
| `logEntries(limit)` / `exportLogs()` / `clearLogs()` / `setLogLevel(level)` | `getLogs`, `exportLogs`, `clearLogs`, `setLogLevel` | `RingLogger.entries/writeTo/clear/minLevel`; o `exportLogs` grava frames e log em `getExternalFilesDir(null)/corenext/` | anel em memória + `os_log`; export para o diretório de documentos (`throws` no Swift) |
| `hasClassicBond(address)` | `READY` que fecha um handshake de um perfil com `Quirks.classicBond`, antes de pedir | `BluetoothDevice.getBondState() == BOND_BONDED` (sem permissão, `false`) | sempre `false` (o app não enxerga os vínculos do sistema) |
| `requestClassicBond(address)` | o mesmo `READY`, quando o sistema não tem o vínculo e não há pedido nem recusa | `ClassicBonder`: `createBond(TRANSPORT_BREDR)` por reflexão (reserva `createBond()`), nova tentativa automática 1,5 s depois da primeira falha, 45 s por tentativa; resultado por `onClassicBondResult`; não abre HFP nem A2DP | `UNSUPPORTED`, assíncrono, com a dica de parear em Ajustes > Bluetooth |
| `removeClassicBond(address)` | fim do `forgetDevice` de um perfil com `Quirks.classicBond`, depois do `F8` e antes do segundo `clearPairing` | `removeBond()` por reflexão, resultado só no log; com o Bluetooth desligado, fica pendente (preferência) e roda quando ele liga; com o vínculo em curso, `cancelBondProcess()` e remoção se ele aparecer depois | só um log com a dica de esquecer o relógio em Ajustes |

**Ganchos do processo.** `attach(sink)` na primeira chamada de um método JS de cada instância do módulo (no Android, o antigo `graph()`); `invalidate(sink)` quando a instância morre (rejeita com `CANCELLED`, `"Module invalidated"`, toda Promise pendente); `onHostResume()` ao voltar ao primeiro plano (entrega a fila e reconecta); `onBluetoothTurnedOff()` (para o scan) e `onBluetoothTurnedOn()` (reconecta, chamado depois de o transporte entregar `onBluetoothStateChanged(true)` à sessão); `onScannedDevice`/`onScanFailed` do scanner; `onLogEntry` do logger (o `onLog` não entra na fila); `onClassicBondResult(address, result, message)` do host, de qualquer thread, uma vez por `requestClassicBond` (pode vir de dentro da própria chamada). Os três primeiros só para um módulo que se ligou.

**Vínculo Bluetooth clássico (perfis com `Quirks.classicBond`, hoje só o Condor Reebok; ADR 30).** O relógio usa HFP e A2DP pelo Bluetooth clássico no mesmo endereço do BLE, e o BLE não cria esse vínculo (`protocolo-crp.md`, seção 10). Regras do controlador, cada uma com um teste em `CoreBridgeCrpTest`:

- No `READY` que fecha um handshake (o que vem de `HANDSHAKING`, thread da sessão), depois de gravar o pareamento e antes do evento: `hasClassicBond(address)`; se o sistema não tem o vínculo, o controlador marca o pedido em andamento e chama `requestClassicBond(address)`. Não pergunta nem pede com o "usuário desconectou", sem endereço na sessão, com outro pedido em andamento ou para um endereço recusado nesta execução. Com o vínculo já no sistema, só um INFO `CoreBridge/classicBond` (`Classic bond with <endereço> already exists`), uma vez por conexão. O `READY` da volta de `SYNCING` não consulta nada (sem chamada ao sistema nem INFO a cada sync).
- `onClassicBondResult`: se o endereço tem um pedido abandonado por um `forgetDevice` (abaixo), só um INFO (`Classic bond result for forgotten <endereço> ignored: …`), sem mexer em mais nada; senão limpa o pedido em andamento (qualquer que seja o endereço). `BONDED` → INFO; `FAILED` → o endereço entra na lista de recusados e sai `onError` com `requestId` `-1`, `CLASSIC_BOND_FAILED`, a mensagem do host e `method` `"classicBond"`, sem `reject` (não há Promise) e com a sessão BLE pronta; `UNSUPPORTED` → o endereço entra na lista e só sai um WARN com a mensagem do host (no iOS, a dica de parear em Ajustes).
- `pairDevice` tira o `deviceId` da lista de recusados: o próximo `READY` pede de novo. A lista e o pedido em andamento vivem só na memória do processo.
- `forgetDevice`: o alvo é o endereço da sessão ou, sem sessão, o do pareamento salvo (lido antes do primeiro `clearPairing`), se o perfil tem o quirk. No terminal da sessão (`onCompleted` ou `onError`), a ordem é: o relógio já recebeu o `F8`, `removeClassicBond`, o segundo `clearPairing` e a liquidação. Diário do teste: `host clearPairing`, `watch write FEEA2005F8`, evento `DISCONNECTED`, `host removeClassicBond`, `host clearPairing`, `resolve forgetDevice null`. A remoção é procurada pelo `requestId` em todo terminal, então acontece mesmo depois de um `invalidate` que já drenou a completion. Com um pedido de vínculo ainda em andamento para esse endereço, ele é abandonado antes do `removeClassicBond`: deixa de contar como em andamento, e o resultado que o host ainda manda (no Android, a falha do pedido que a remoção cancela) só vai para o log, sem `onError` nem recusa; o próximo `pairDevice` do endereço limpa o abandono.
- `disconnectCurrentDevice` nunca remove o vínculo.
- O SMA e o iDO nunca chamam nenhum dos três métodos do host, e o golden não muda.

**Inventário resumido** (o completo, com as linhas do host antigo, está nos testes de contrato e no README do pacote):

| Método JS | Checagens | Evento(s) | Liquidação | Efeitos |
|---|---|---|---|---|
| `initialize`, `initSMASDK` | — | `onConnectStateChanged` do estado atual (fase `null`), relido até ficar estável | `resolve(null)` depois | reconexão automática (`"initialize"`) |
| `scan` | permissão de scan, Bluetooth | `onError` (`-1`, `method` `scan`) se falhar | sem Promise | `startScan` |
| `pairDevice` | permissão de conexão, Bluetooth, endereço, nome (`appName`, `id`, nome BLE exato ou, por último, `DeviceRegistry.identify` com os prefixos: `P85-1234` é o Condor Reebok) | os estados da sessão | `resolve(null)` no `READY`; `onError` + `reject` na falha (inclusive `PAIRING_REMOVED`) | `stopScan`, `userDisconnected = false`, o endereço sai da lista de recusados do vínculo clássico; o nome da gravação só quando o link abre |
| `startPairingAttempt` | as mesmas | os estados; `onError` com o método na falha | `resolve(null)` **na hora** | sem `stopScan` |
| `disconnectCurrentDevice` | — | `DISCONNECTED` | `resolve(null)` | `userDisconnected = true`; o vínculo clássico fica |
| `forgetDevice` | — | `DISCONNECTED` | `resolve(null)` | `userDisconnected = true`, `clearPairing` no pedido e de novo no fim, com `onCompleted` ou `onError` (se ninguém pareou no meio); num perfil com `Quirks.classicBond`, `removeClassicBond` antes do segundo `clearPairing` |
| `isConnected`, `getLastPairedDevice` | — | — | `Boolean`; `{deviceId, name, profileId}` ou `null` | — |
| `getDeviceInfo` / `getFirmwareInfo` | — | só `onDeviceInfo` / só `onFirmwareInfo`; sem pedido registrado, os dois | `resolve(null)` depois | firmware e nome da gravação |
| `getBatteryStatus`, `refreshSteps` | — | `onBatteryStatus` (com `charging`), `onPedometer` | `resolve(null)` depois | — |
| (sem método: avisos do relógio) | — | `onBatteryStatus` do aviso de bateria e `onPedometer` dos passos ao vivo do CRP (sessão com `NO_REQUEST_ID`); `onError` `CLASSIC_BOND_FAILED` (`-1`, `method` `"classicBond"`) | sem Promise | o pedido do vínculo clássico no `READY` de um handshake |
| `setTime` | — | — | `resolve(null)` | — |
| `syncDataHistory` | — | `onSyncStarted`, `onSyncProgress`…, `onSyncFinished` | `resolve(null)` depois | `startSyncWork` antes do `onSyncStarted`, `stopSyncWork` antes do `onSyncFinished` (ou do `onError`) |
| `getHistoryPedometer` | — | `onHistoryPedometer` (lista vazia também) | `resolve(null)` depois; `IO_ERROR` | — |
| `getHistorySleep` | — | `onHistorySleep`, ou `onError` `NO_DATA` (`"No sleep sessions stored"`) | `resolve(null)` depois; `reject(NO_DATA)`; `IO_ERROR` | — |
| `deletedSavedData` | — | — | sem Promise | `confirmUploaded` por tipo; WARN/INFO no log |
| `getLogs`, `exportLogs`, `clearLogs`, `setLogLevel` | — | `onError` `IO_ERROR` se o export lançar qualquer `Exception` (`IOException` no Android, `NSError` no iOS) | lista de entradas; caminho | o log do host |

Toda falha de comando que vem da sessão sai como `onError` com o nome do método JS (`method` `null` para a reconexão automática e para eventos espontâneos) e depois rejeita com o mesmo código e mensagem. As linhas de log do controlador têm a tag `CoreBridge/<método>` (no host antigo eram `CoreGraph/…`, `CoreSessionListener/…` e `NativeCoreNextModule/…`); as mensagens não mudaram.

**Threads.** Os métodos JS chegam da thread dos módulos nativos do React Native; os callbacks da sessão, da thread lógica dela; os ganchos, da principal ou da do Bluetooth; o `onLogEntry`, de qualquer uma. O estado compartilhado é o mesmo do host antigo: a fila e os pedidos em andamento são travados, e os dois sinalizadores (`userDisconnected`, o sync em andamento) são `@Volatile`. O `CoreEventSink.emit` roda dentro da trava reentrante da fila: não deve bloquear e pode voltar a chamar o controlador.

**Testes.** No `commonTest` do `:bridge`, nos quatro targets, sobre uma `WatchSession` de verdade com o kit de teste do `:protocol` (transporte falso, relógio SMA simulado, tempo virtual; `protocol/src/testKit/README.md`): um teste por linha do inventário, um `FakeCoreHost` e um `CoreEventSink` que gravam tudo num diário só (para conferir a ordem), e o `CoreBridgeGoldenTest`, que passa a gravação real do fitGPS pelo controlador, método JS por método JS, e compara o diário inteiro com `bridge/golden/` (com o replay estrito: `TX matched 167/167`). As outras famílias passam pelo controlador em `CoreBridgeIdoTest` e `CoreBridgeCrpTest` (14 testes: perfil pelo nome e pelo prefixo, vínculo clássico, esquecer, avisos espontâneos), com o `IdoWatchSimulator` e o `CrpWatchSimulator` do kit. Do lado iOS, o `checkSwiftInterop` e o `checkObjcInterop` conferem que o Swift (um `CoreHost`, uma `CoreCompletion` e um `CoreEventSink` em Swift chamando todo o `CoreBridge`) e o shim Objective-C++ compilam contra o framework.
