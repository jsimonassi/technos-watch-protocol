# `transport/` — a fronteira com o BLE do host

**Propósito.** O host (Android `BluetoothGatt`, iOS `CoreBluetooth`) implementa `Transport` e chama `TransportEvents`; é a única parte do sistema que toca a pilha Bluetooth. Só bytes, UUIDs e status: nada de protocolo de fabricante.

**Tipos.** `Transport` (connect/disconnect, descoberta, notificações, MTU, write, read), `TransportEvents` (classe concreta, um método por callback, cada um só enfileira na sessão), `TransportEvent` (interno, os mesmos eventos como valores), `WriteType`, `GattStatus`, `DiscoveredService`/`DiscoveredCharacteristic`, `GattUuid.normalize`.

**Fica de fora.** Scan (o host faz e usa `DeviceRegistry.identify`), escolha do serviço (sessão, pela ordem do perfil), fragmentação e ACK (driver).

**Dependências.** Nenhuma além da biblioteca padrão. Quem depende: `session/` (consome os eventos e chama o `Transport`), `driver/`/`sma/` (só `WriteType`) e o host.

**Contrato que a sessão assume** (detalhes em `docs/arquitetura.md`, seção 5)

- Toda operação com `requestId` termina com exatamente um evento com o mesmo `requestId`.
- `disconnect()` termina com um `onDisconnected` quando havia link aberto ou conexão pendente, e com nenhum quando não havia (`docs/decisoes.md`, ADR 12).
- Eventos na ordem em que aconteceram; `TransportEvents` pode ser chamado de qualquer thread e nunca bloqueia.
- `connect(autoReconnect = true)` mantém a conexão pendente sem timeout (Android `autoConnect = true`).
- O host copia o array da notificação se a plataforma reutilizar o buffer.

**Testes.** `commonTest/.../testing/FakeTransport` implementa o contrato e é usado por todos os testes de sessão.
