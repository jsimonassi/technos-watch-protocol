# `transport/` — a fronteira com o BLE do host

**Propósito.** O host (Android `BluetoothGatt`, iOS `CoreBluetooth`) implementa `Transport` e chama `TransportEvents`; é a única parte do sistema que toca a pilha Bluetooth. Só bytes, UUIDs e status: nada de protocolo de fabricante.

**Tipos.** `Transport` (connect/disconnect, descoberta, notificações, MTU, write, read), `TransportEvents` (classe concreta, um método por callback, cada um só enfileira na sessão), `TransportEvent` (interno, os mesmos eventos como valores), `WriteType`, `GattStatus`, `DiscoveredService`/`DiscoveredCharacteristic`, `GattUuid.normalize`.

**Fica de fora.** Scan (o host faz e usa `DeviceRegistry.identify`), escolha do serviço (sessão, pela ordem do perfil), fragmentação e ACK (driver). As versões com `NSData` de `onNotification` e `onReadCompleted` e a conversão do `value` de `write` para `Data` são do pacote `ios/`, só no `iosMain` (`docs/arquitetura.md`, seção 11).

**Dependências.** Nenhuma além da biblioteca padrão. Quem depende: `session/` (consome os eventos e chama o `Transport`), `driver/`/`sma/` (só `WriteType`) e o host.

**Contrato que a sessão assume** (detalhes em `docs/arquitetura.md`, seção 5)

- Toda operação com `requestId` termina com exatamente um evento com o mesmo `requestId`.
- `disconnect()` termina com um `onDisconnected` quando havia link aberto ou conexão pendente, e com nenhum quando não havia (`docs/decisoes.md`, ADR 12).
- `connect()` que falha sem chegar a conectar (conexão direta que falha, ou recusa na hora: Bluetooth desligado, sem permissão, endereço inválido) termina com exatamente um `onDisconnected`; só a conexão pendente que falha antes de conectar pode ficar sem evento, porque o host a mantém. A exceção é o vínculo do sistema apagado no relógio: o host não re-arma e entrega `onDisconnected` com `GattStatus.PAIRING_REMOVED`, com que a sessão desiste em vez de reconectar (`docs/decisoes.md`, ADR 23).
- `GattStatus`: os status nativos (positivos) só vão para log e mensagem; os negativos são neutros de plataforma, e só `PAIRING_REMOVED` muda o comportamento da sessão.
- **Bluetooth desligado, contrato B** (`docs/decisoes.md`, ADR 18): o host entrega `onBluetoothStateChanged(false)` e **nenhum** `onDisconnected` pelos links abertos e conexões pendentes que isso derrubou, nem antes nem depois; depois disso, `disconnect()` não emite nada até um novo `connect()`. Um `onDisconnected` entregue antes do `onBluetoothStateChanged(false)` (o stack reportou a queda antes do aviso) é uma queda comum. O `CoreBluetooth` já se comporta assim (`poweredOff` sem `didDisconnectPeripheral`); no Android o host suprime os `onDisconnected` que o `BluetoothGatt` entrega ao desligar.
- Eventos na ordem em que aconteceram; `TransportEvents` pode ser chamado de qualquer thread e nunca bloqueia.
- `connect(autoReconnect = true)` mantém a conexão pendente sem timeout (Android `autoConnect = true`).
- O host copia o array da notificação se a plataforma reutilizar o buffer (no iOS, a sobrecarga com `NSData` já copia).
- `requestMtu` responde com o MTU efetivo; no iOS, `maximumWriteValueLength(for: .withoutResponse) + 3`, nunca o valor de `.withResponse` (KDoc de `Transport.requestMtu`, ADR 5).

**Testes.** `testKit/.../testing/FakeTransport` (o kit de teste compartilhado com o `:bridge`, `protocol/src/testKit/README.md`) implementa o contrato (inclusive o contrato B em `setBluetooth`) e é usado por todos os testes de sessão; `BluetoothOffContractTest` cobre o Bluetooth desligado.
