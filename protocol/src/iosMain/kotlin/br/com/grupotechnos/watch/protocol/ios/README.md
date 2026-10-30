# `ios/` — ponte de bytes para o host Swift (só iOS)

**Propósito.** A API comum carrega bytes como `ByteArray`, que no Swift aparece como `KotlinByteArray`, um objeto que só se lê e escreve byte a byte. Este pacote, só do `iosMain` (`iosArm64` e `iosSimulatorArm64`), dá ao host Swift uma cópia em uma chamada nos dois sentidos e as entradas de `TransportEvents` que recebem `Data` direto do `CoreBluetooth`. É a única API pública do `:protocol` que existe só no iOS (no `:bridge`, o `FoundationConverter` faz o mesmo papel para os payloads do JavaScript).

**Tipos e nomes no Swift** (conferidos no header gerado e compilados pelo `checkSwiftInterop`)

| Kotlin (`iosMain`) | Swift | Uso no host |
|---|---|---|
| `ByteArray.toNSData(): NSData` | `value.toData() -> Data` (método de `KotlinByteArray`) | o `value` de `Transport.write(service:characteristic:value:type:requestId:)` antes do `CBPeripheral.writeValue`, e o de `WatchLogger.frame(direction:characteristic:value:summary:)` para log |
| `NSData.toByteArray(): ByteArray` | `ByteBridgeKt.toByteArray(data) -> KotlinByteArray` | o resto da API que recebe bytes, como `FrameJsonl.shared.line(direction:atMs:characteristic:value:)`; raramente necessário |
| `TransportEvents.onNotification(characteristic, data: NSData)` | `session.events.onNotification(characteristic:data:)` | `peripheral(_:didUpdateValueFor:error:)` de uma notificação |
| `TransportEvents.onReadCompleted(requestId, data: NSData?, status)` | `session.events.onReadCompleted(requestId:data:status:)` | `peripheral(_:didUpdateValueFor:error:)` de uma leitura pedida por `Transport.read` |

No Objective-C (prefixo `TWP`): `[value toNSData]`, `[TWPByteBridgeKt toByteArray:data]` e os métodos `onNotificationCharacteristic:data:` e `onReadCompletedRequestId:data:status:` da categoria `TWPTransportEvents (Extensions)`. O nome `toData()` vem de `@ObjCName(swiftName = "toData")`; o rótulo `data:` (e não `value:`) evita a colisão de seletor com os métodos que recebem `KotlinByteArray`.

O `value` que chega em `WatchLogger.frame` pode ir direto para `FrameJsonl.shared.line(...)` sem conversão: os dois são `KotlinByteArray`.

**Fica de fora.** `CoreBluetooth`, filas e threading (é do host: `CentralTransport` no `react-native-technos-core-next`), qualquer regra de protocolo, versões `NSData` de métodos que o host não chama com bytes vindos da plataforma. Nada aqui muda o comportamento da sessão: as sobrecargas só convertem e chamam o método comum.

**Dependências.** `transport/` (`TransportEvents`) e a plataforma (`platform.Foundation`, `kotlinx.cinterop`). Ninguém da biblioteca depende deste pacote; quem usa é o host Swift.

**Invariantes**

- Toda conversão **copia**: mudar o array ou o `NSData` (`NSMutableData`) de origem depois não muda o resultado. O host pode reutilizar o buffer da notificação.
- Tamanho 0 funciona nos dois sentidos sem tocar em endereço: um `ByteArray` vazio não tem elemento 0 para fixar (`addressOf(0)`), e o `bytes` de um `NSData` vazio pode ser nulo.
- Um `NSData` recorte de outro (`subdataWithRange`) copia só o recorte (`getBytes:length:`).
- As sobrecargas com `NSData` enfileiram exatamente o mesmo evento da versão com `ByteArray` e mantêm as regras dela: retornam na hora, de qualquer thread, uma chamada por notificação, na ordem.
- Um `NSData` com mais de `Int.MAX_VALUE` bytes lança `IllegalArgumentException` (limite do `ByteArray`); um pacote BLE nunca chega perto.

**Testes.** `iosTest/.../ios/ByteBridgeTest` (roda em `iosSimulatorArm64Test`): ida e volta, vazio nos dois sentidos (inclusive `NSData` com `bytes` nulo), os 256 valores de byte com o sinal, 64 KiB, cópia nos dois sentidos, `subdataWithRange`, as sobrecargas contra as versões com `ByteArray` (mesmos `TransportEvent`, inclusive a leitura com `data` nulo, passada como `NSData?` tipado para não cair no método membro; cópia do buffer do host) e a gravação sintética inteira com os RX entregues como `NSData`. A tarefa `./gradlew :bridge:checkSwiftInterop` compila um host Swift mínimo que usa os quatro nomes acima (`bridge/swift-interop/`) contra o framework `TechnosWatchProtocol`, que é gerado pelo `:bridge` com este módulo exportado inteiro (`docs/decisoes.md`, ADR 20); por isso os nomes no Swift não mudaram. O desenho está em `docs/arquitetura.md`, seção 11, e a decisão, na ADR 19 de `docs/decisoes.md`.
