# `driver/` — costura entre a sessão e as famílias

**Propósito.** Define o contrato que a sessão usa para falar com qualquer família de relógio, sem saber o que é um BIND ou um CRC, e o único ponto que escolhe a família pelo perfil.

**Tipos**

| Tipo | Visibilidade | Papel |
|---|---|---|
| `DeviceDriver` | interna | operações `suspend` de uma conexão: handshake, versão, bateria (`readBattery` e `readBatteryStatus`, com padrão `charging = false`), hora, passos, sono, unbind, avisos; `describe(value)` e `describe(characteristic, value)` (padrão: ignora a characteristic) |
| `DriverLink` | interna | o que a sessão entrega ao driver: link GATT pronto (write/read, notificações do canal de frames, `sideNotifications` com a origem, MTU, relógio, logger) |
| `CharacteristicNotification` | interna | uma notificação de `sideNotify` com a characteristic de origem |
| `DriverNotice` | interna | avisos espontâneos (`DataChanged`, `LinkReported`, `BatteryReported`, `StepsUpdated`) |
| `DriverBattery`, `HandshakeMode`, `DriverDeviceInfo` | interna | bateria com carga, modo do handshake e versão lida |
| `Drivers` | interna | `create(link)`: `Family.SMA` → `SmaDriver`, `Family.IDO` → `IdoDriver`, `Family.CRP` → `CrpDriver` |
| `HostDriver` | pública, vazia | lugar reservado para adaptadores de SDK de fabricante (`docs/arquitetura.md`, seção 9) |

**Fica de fora.** Estado de conexão, fila, listener, store e agregação (sessão); bytes de cada família (`sma/`, `ido/`, `crp/`); BLE (host).

**Dependências.** `domain/`, `profile/`, `transport/` (`WriteType`), `log/`, `session/WatchClock`, `kotlinx-coroutines`, e `sma/`, `ido/` e `crp/` só em `Drivers` (para criar o driver de cada família: é o único lugar que conhece as famílias). Quem depende: `session/` (contratos e `Drivers`) e as famílias (`sma/`, `ido/` e `crp/`, que implementam `DeviceDriver`).

**Invariantes do contrato**

- Um driver vive uma conexão: `start` uma vez, `close` quando o link cai; a sessão cancela o escopo passado em `start`.
- A sessão nunca chama dois métodos `suspend` ao mesmo tempo.
- Erros saem como `WatchException` com código de `WatchErrorCodes`; outra exceção vira `PROTOCOL`.
- `DriverLink.write/read` são serializados pela sessão, todo pacote é logado (TX, RX e READ), falha GATT vira `TRANSPORT` e queda vira `DISCONNECTED` (implementação em `session/SessionDriverLink`).
- As notificações de `notify` e `extraNotify` chegam em `notifications`; as de `sideNotify`, em `sideNotifications`, com a origem. Os dois canais fecham quando o link cai. Uma família sem `sideNotify` nunca recebe nada no segundo.
- Os métodos com corpo padrão (`describe(characteristic, value)`, `readBatteryStatus`) existem para o SMA e o iDO não mudarem; só o CRP os sobrescreve.
- `BatteryReported` e `StepsUpdated` só têm efeito com o link pronto (READY); a sessão emite `onBattery`/`onLiveSteps` com `NO_REQUEST_ID`.

**Testes.** Pelo `SmaDriverTest` (com `FakeDriverLink`, cujo `sideNotifications` é um canal fechado) e pelos testes de sessão, que usam `Drivers.create` de verdade (o iDO, pelo `IdoSessionTest`; o CRP, pelo `CrpSessionTest`).
