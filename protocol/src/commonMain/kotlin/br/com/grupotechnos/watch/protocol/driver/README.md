# `driver/` — costura entre a sessão e as famílias

**Propósito.** Define o contrato que a sessão usa para falar com qualquer família de relógio, sem saber o que é um BIND ou um CRC, e o único ponto que escolhe a família pelo perfil.

**Tipos**

| Tipo | Visibilidade | Papel |
|---|---|---|
| `DeviceDriver` | interna | operações `suspend` de uma conexão: handshake, versão, bateria, hora, passos, sono, unbind, avisos |
| `DriverLink` | interna | o que a sessão entrega ao driver: link GATT pronto (write/read, notificações, MTU, relógio, logger) |
| `DriverNotice` | interna | avisos espontâneos (`DataChanged`, `LinkReported`) |
| `HandshakeMode`, `DriverDeviceInfo` | interna | modo do handshake e versão lida |
| `Drivers` | interna | `create(link)`: `Family.SMA` → `SmaDriver`, `Family.IDO` → `IdoDriver` |
| `HostDriver` | pública, vazia | lugar reservado para adaptadores de SDK de fabricante (`docs/arquitetura.md`, seção 9) |

**Fica de fora.** Estado de conexão, fila, listener, store e agregação (sessão); bytes de cada família (`sma/`, `ido/`); BLE (host).

**Dependências.** `domain/`, `profile/`, `transport/` (`WriteType`), `log/`, `session/WatchClock`, `kotlinx-coroutines`, e `sma/` e `ido/` só em `Drivers` (para criar o `SmaDriver` e o `IdoDriver`: é o único lugar que conhece as famílias). Quem depende: `session/` (contratos e `Drivers`) e as famílias (`sma/` e `ido/`, que implementam `DeviceDriver`).

**Invariantes do contrato**

- Um driver vive uma conexão: `start` uma vez, `close` quando o link cai; a sessão cancela o escopo passado em `start`.
- A sessão nunca chama dois métodos `suspend` ao mesmo tempo.
- Erros saem como `WatchException` com código de `WatchErrorCodes`; outra exceção vira `PROTOCOL`.
- `DriverLink.write/read` são serializados pela sessão, todo pacote é logado, falha GATT vira `TRANSPORT` e queda vira `DISCONNECTED` (implementação em `session/SessionDriverLink`).

**Testes.** Pelo `SmaDriverTest` (com `FakeDriverLink`) e pelos testes de sessão, que usam `Drivers.create` de verdade (o iDO, pelo `IdoSessionTest`).
