# Module technos-watch-bridge

O contrato JavaScript do Core React Native (`react-native-technos-core-next`) em Kotlin Multiplatform, sem React
Native: os mesmos nomes de evento, os mesmos payloads, os mesmos códigos de erro e a mesma fila de eventos nos dois
hosts, Android e iOS, e o controlador [br.com.grupotechnos.watch.bridge.CoreBridge], que implementa cada método JS do
spec sobre a `WatchSession`, com a plataforma atrás de [br.com.grupotechnos.watch.bridge.CoreHost], o módulo JS atrás
de [br.com.grupotechnos.watch.bridge.CoreEventSink] e a Promise atrás de [br.com.grupotechnos.watch.bridge.CoreCompletion].

Depende do módulo `technos-watch-protocol` (`api`), e nunca o contrário. Os payloads são `Map<String, Any?>` e
`List<Any?>` com os tipos que o host Android sempre usou (`Int`, `Double`, `String`, `Boolean`, `null`, mapas e listas
aninhados); cada host só converte para a sua ponte: `WritableConverter` no Android (Repo 2) e
[br.com.grupotechnos.watch.bridge.ios.FoundationConverter] no iOS.

O framework iOS `TechnosWatchProtocol` (estático, prefixo ObjC `TWP`) é gerado por este módulo, com o
`technos-watch-protocol` exportado inteiro: o host Swift importa um framework só e vê a `WatchSession` e o contrato JS
lado a lado. As tarefas `checkObjcHeader`, `checkSwiftInterop` e `checkObjcInterop` conferem esse header guarda-chuva
(o que não pode aparecer, o que o Swift usa e o que o shim Objective-C++ do TurboModule chama).

Documentação de desenho no repositório: `docs/arquitetura.md` (seção 12, o contrato JS comum; seção 13, o controlador,
com o vínculo Bluetooth clássico) e `docs/decisoes.md` (ADRs 20, 21 e 30).

# Package br.com.grupotechnos.watch.bridge

O controlador (`CoreBridge`) e o que o host implementa para ele (`CoreHost`, `CoreEventSink`, `CoreCompletion`, com
`CorePairing` e `CoreLogEntry`); o vínculo Bluetooth clássico dos relógios que usam chamadas e música pelo BR/EDR (o
Condor Reebok): `CoreHost.hasClassicBond`/`requestClassicBond`/`removeClassicBond`, o resultado em
`CoreBridge.onClassicBondResult` (`ClassicBondResult`) e o código `CLASSIC_BOND_FAILED`; domínio → payloads dos eventos JS (`EventMapper`), estado da sessão →
`deviceConnectStates` (`StateMapper`), `deletedSavedData` → dias locais (`SavedDataMapper`), códigos de erro do host
(`HostErrorCodes`), a fila de eventos até o módulo JS existir (`PendingEvents`) e os comandos em andamento por
`requestId` (`RequestRegistry`). As datas reproduzem o `java.time` do host Android antigo, inclusive nos dias de horário
de verão.

# Package br.com.grupotechnos.watch.bridge.ios

Só no iOS (`iosMain`): `FoundationConverter` converte os payloads em `NSDictionary`/`NSArray`/`NSNumber`/`NSString`/
`NSNull` de verdade, com `Boolean` como `CFBoolean`, para o React Native entregar `true`/`false` ao JavaScript, e não
`1`/`0`.
