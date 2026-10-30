# `bridge/` — o contrato JavaScript do Core e o controlador, em código comum

**Propósito.** Tudo o que o JavaScript do `react-native-technos-core-next` vê e que não depende de plataforma: os
payloads dos eventos (`src/types/events.ts` de lá), os códigos de `deviceConnectStates`, a leitura do
`deletedSavedData`, os códigos de erro do host, a fila de eventos até o módulo JS existir, os comandos em andamento
por `requestId`, e o **controlador** (`CoreBridge`) que implementa cada método JS do spec e o lado
`WatchSessionListener` da sessão. O host Android (Kotlin) e o host iOS (Swift e Objective-C++, pelo framework) usam o
mesmo código, então os dois entregam exatamente os mesmos nomes, valores, datas, ordem e rejeições; o TurboModule de
cada plataforma vira uma casca que chama o `CoreBridge`.

Veio do host Android do Repo 2 (o pacote `bridge/`, o `NativeCoreNextModule`, o `CoreSessionListener` e as regras do
`CoreGraph`), sem mudança de comportamento visível ao JS: os testes do mapeamento são os mesmos, as datas foram
conferidas contra o `java.time` antigo num teste diferencial (`IsoDatesDifferentialTest`, `jvmTest`), e o controlador
tem um teste de contrato por linha do inventário do host antigo e um golden do fluxo de eventos da gravação real. O
host Android do Repo 2 já é essa casca: o `NativeCoreNextModule` chama o `CoreBridge`, e o `AndroidCoreHost` implementa o
`CoreHost`.

**Não faz.** Não conhece BLE, arquivo, React Native nem thread de plataforma: o que depende do sistema fica atrás de
`CoreHost` (permissões, Bluetooth, endereço, scan, trabalho em segundo plano, pareamento salvo, gravação, log),
`CoreEventSink` (o módulo JS) e `CoreCompletion` (a Promise). Não decide nada sobre o relógio (isso é o `:protocol`):
chama a `WatchSession` e muda a forma do dado. A conversão para a ponte do React Native fica em cada host
(`WritableConverter`, `ReadableConverter` e `PromiseCompletion` no Android;
[`ios/FoundationConverter`](../../../../../../../iosMain/kotlin/br/com/grupotechnos/watch/bridge/ios/README.md) no iOS).

## Tipos

| Tipo | Visibilidade | Papel |
|---|---|---|
| `CoreBridge` | pública | Um método por método JS do spec (mesmo nome e argumentos; os números do JS como `Double`) e os ganchos do processo (`attach`, `invalidate`, `onHostResume`, `onBluetoothTurnedOff`/`On`, `onScannedDevice`, `onScanFailed`, `onLogEntry`). Dono do listener da sessão, da `RequestRegistry`, da `PendingEvents`, do `userDisconnected` e do sync em andamento. Montado pelo host com a sessão pronta: `CoreBridge(session, logger, host)`. |
| `CoreHost` | pública (interface) | O que o controlador pede à plataforma, um por processo: `missingScanPermission`, `missingConnectPermission`, `isBluetoothEnabled`, `isValidAddress`, `startScan`/`stopScan`, `startSyncWork`/`stopSyncWork`, `loadPairing`/`savePairing`/`clearPairing`, `setRecordingDeviceName`/`setRecordingFirmware`, `logEntries`, `exportLogs` (`@Throws`), `clearLogs`, `setLogLevel`. |
| `CoreEventSink` | pública (interface) | A instância atual do módulo JS: `canEmit()` e `emit(name, payload)`. Separado do `CoreHost` porque o módulo é recriado a cada reload do JS. |
| `CoreCompletion` | pública (interface) | `resolve(value)`/`reject(code, message)`, no lugar da Promise do React Native. Valores: `null`, `Boolean`, `String`, `Map`, `List` de `Map`. |
| `CorePairing` | pública | O relógio salvo: `address`, `profileId`, `name`. |
| `CoreLogEntry` | pública | Uma entrada do log do host na forma neutra (`ts`, `level`, `tag`, `message`, `dir`, `hex`), para o `onLog` e o `getLogs`. |
| `EventMapper` | pública | Domínio → `Map<String, Any?>` nos formatos de `src/types/events.ts` (mesmos nomes do Core atual). Só o `date` do sono é calculado aqui (`startOfDayIso`), no padrão `yyyy-MM-dd'T'HH:mm:ssXXX`. |
| `StateMapper` | pública | `ConnectionState` → `deviceConnectStates`: `DISCONNECTED`/`RECONNECTING` 0, `CONNECTING`/`CONFIGURING`/`HANDSHAKING` 1, `READY`/`SYNCING` 2, `BLUETOOTH_OFF` 4; `isConnected` só em `READY`/`SYNCING`. |
| `SavedDataMapper` | pública | `deletedSavedData`: categoria (5 passos, 0 sono) → `DataKind`; `registered_at` → dia local (`yyyy-MM-dd`), aceitando e recusando o mesmo que o `java.time` antigo. |
| `HostErrorCodes` | pública | Códigos que nascem no host: `PERMISSION_DENIED`, `BLUETOOTH_OFF`, `INVALID_ARGUMENT`, `IO_ERROR`. |
| `PendingEvents<P>` | pública | Fila de eventos até o módulo poder emitir; entrega em ordem, dentro de uma trava reentrante; capacidade 500, descarta o mais antigo. |
| `RequestRegistry<T>` | pública | `requestId` → o que liquida a Promise + nome do método JS; liquidado uma vez; ids de 1 a `Int.MAX_VALUE`, nunca `-1`. |
| `IsoDates` | interna | Leitura e escrita das datas reproduzindo o `java.time` (as quatro leituras do `registered_at`, o `XXX`, o início do dia com horário de verão); o `kotlinx-datetime` faz só a aritmética de fuso. |
| `BridgeLock` | interna | `expect fun newReentrantLock()`: `ReentrantLock` na JVM e no Android (source set `jvmSharedMain`, um grupo JVM + Android no template de hierarquia), `NSRecursiveLock` no iOS. |

Fuso: as funções públicas que dependem do fuso usam o do telefone na hora da chamada
(`TimeZone.currentSystemDefault()`, o `ZoneId.systemDefault()` do host antigo). As sobrecargas que recebem o fuso são
`internal`, como o construtor do `CoreBridge` que recebe um provedor de fuso: os testes os usam, e um `TimeZone` do
`kotlinx-datetime` não pode aparecer no header ObjC (`checkObjcHeader`).

No Objective-C e no Swift, `initSMASDK` aparece como `doInitSMASDK` (o Kotlin/Native renomeia seletores da família `init`
do ObjC); os outros métodos mantêm o nome (`pairDevice(deviceId:name:completion:)`,
`pairDeviceDeviceId:name:completion:`).

## Depende de

`:protocol` (`domain`, `profile`, `log`, `session`), `kotlinx-datetime` e a stdlib. Nada de React Native, Android SDK ou
Foundation no código comum. O `:protocol` nunca depende daqui.

## Invariantes

- Os nomes de campo seguem `src/types/events.ts` do Repo 2. Mudou lá, muda aqui na mesma alteração (com o
  `EventMapperTest`, o golden, o `docs/api-js.md` de lá e o mock).
- Tipos dos valores: inteiros como `Int`; decimais e epoch millis (`Long`) como `Double`; `String`, `Boolean`, `null`,
  mapas e listas aninhados. É o que o `WritableConverter` do Android sempre recebeu, e o `FoundationConverter` aceita os
  mesmos.
- `CoreBridge`: em cada callback da sessão, primeiro os efeitos no host (pareamento salvo, trabalho em segundo plano,
  cabeçalho da gravação), depois o evento, depois a liquidação; o evento sai sempre antes do `resolve`. Toda falha de
  comando vira `onError` com o método JS (ou `null` na reconexão automática e nos espontâneos) e depois `reject` com o
  mesmo código e mensagem. Rejeição antecipada (permissão, Bluetooth, endereço, nome, `NO_DATA`, `IO_ERROR`): `onError`
  com `requestId` `-1`, depois `reject`. O `startPairingAttempt` resolve na hora, sem completion no registro.
- `CoreBridge`: o pareamento é gravado em todo `READY`, menos depois de um `disconnectCurrentDevice`/`forgetDevice` (até o
  próximo `pairDevice`/`startPairingAttempt`); o `forgetDevice` apaga ao ser pedido e de novo quando a sessão conclui, se
  ninguém pediu outro pareamento no meio. A reconexão automática exige pareamento salvo, ninguém ter desconectado, sessão
  em `DISCONNECTED`/`BLUETOOTH_OFF`, Bluetooth ligado e permissão de conexão.
- `CoreBridge`: o trabalho em segundo plano liga no `onSyncStarted` (antes do evento) e desliga uma vez, no fim do sync,
  no erro do sync ou com a sessão fora de `READY`/`SYNCING`; o erro de outro pedido não o desliga.
- `CoreBridge`: o `attach` do mesmo `CoreEventSink` não entrega a fila de novo; o `invalidate` rejeita com `CANCELLED`
  (`"Module invalidated"`) toda Promise pendente, na ordem de registro; o `onLog` nunca entra na fila.
- `startOfDayIso` é o `LocalDate.atStartOfDay(zone)` do `java.time`: num dia cuja meia-noite não existe, o fim do buraco
  (`2018-11-04T01:00:00-02:00` em `America/Sao_Paulo`); offset zero sai `Z`; os segundos de um offset antigo caem. Um
  `dayKey` inválido lança `IllegalArgumentException`; `startOfDayIso`, `sleepDay` e `historySleep` têm
  `@Throws(IllegalArgumentException::class)`, então no Swift são `throws` (no ObjC, `...error:`) e a exceção chega como
  `NSError`, em vez de encerrar o processo como acontece com toda exceção Kotlin que cruza para o ObjC sem `@Throws`.
- `SavedDataMapper.dayKey` tenta, depois de `trim()`, `ISO_OFFSET_DATE_TIME`, o legado `yyyy-MM-dd'T'HH:mm:ss[.SSS]XX`
  (modo SMART: dia 30 de fevereiro vira 28, `24:00:00` vira o dia seguinte), `ISO_LOCAL_DATE_TIME` e `ISO_LOCAL_DATE`,
  nessa ordem. Única diferença deliberada: quando o `java.time` lançava `DateTimeException` ao converter um instante fora
  do intervalo de datas (anos perto de ±999.999.999), aqui o resultado é `null`.
- `PendingEvents` emite dentro da trava (um evento de outra thread nunca passa na frente de um que saiu da fila), e a
  trava é reentrante (um log emitido de dentro de `Sink.emit` não trava).
- `RequestRegistry.drain` devolve na ordem de registro (o host antigo usava um `ConcurrentHashMap`, sem ordem definida).
- As linhas de log do controlador têm a tag `CoreBridge/<método>`; as mensagens são as do host antigo.

## Testes

`commonTest` (JVM, unit Android debug e release, simulador iOS):
- `EventMapperTest`, `StateMapperTest`, `SavedDataMapperTest`, `PendingEventsTest` e `RequestRegistryTest`, com as
  asserções dos testes do host Android, mais os casos de horário de verão e de borda do `java.time`, a volta do contador
  e a concorrência pela trava de cada plataforma.
- `CoreBridgeLifecycleTest`, `CoreBridgeConnectionTest`, `CoreBridgeCommandsTest`, `CoreBridgeHistoryTest` e
  `CoreBridgeLogsTest`: os testes de contrato do controlador, sobre uma `WatchSession` de verdade em tempo virtual (o
  kit de teste do `:protocol`, `protocol/src/testKit/README.md`), com um `FakeCoreHost`, um `CoreEventSink` e
  completions que gravam tudo num diário só (`CoreBridgeTestKit.kt`), para conferir a ordem.
- `CoreBridgeGoldenTest`: a gravação real do fitGPS passa pelo controlador, método JS por método JS, com o replay
  estrito, e o diário inteiro é comparado com `bridge/golden/` (como ler e regenerar: `bridge/golden/README.md`). Cada
  target imprime uma linha `GOLDEN` no `system-out` do XML.

`jvmTest`: `IsoDatesDifferentialTest`, o `java.time` antigo como oráculo em todo dia de 1900 a 2100 em nove fusos e em
cerca de 260 mil `registered_at` gerados e mutados; cada caso imprime uma linha `DIFFERENTIAL` no `system-out` do XML.

O lado Swift e Objective-C++ do controlador é conferido pelo `checkSwiftInterop` (`bridge/swift-interop/`) e pelo
`checkObjcInterop` (`bridge/objc-interop/`).
