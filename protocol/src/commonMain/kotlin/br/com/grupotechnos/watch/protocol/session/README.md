# `session/` — a sessão com o relógio

**Propósito.** `WatchSession` é a fachada única do host: conexão, configuração GATT, handshake, fila de comandos, sincronização de histórico, reconexão e as consultas ao histórico local. O desenho (camadas, fluxos, threading, máquina de estados, erros) está em `docs/arquitetura.md`; as decisões, em `docs/decisoes.md`.

**Tipos**

| Tipo | Visibilidade | Papel |
|---|---|---|
| `WatchSession` | pública | fachada; delega tudo ao `SessionEngine` |
| `WatchSessionListener` | pública | saída para o host (estado, resultados, erros) |
| `SampleStore`, `InMemorySampleStore` | pública | persistência do histórico, implementada pelo host |
| `WatchClock`, `SystemWatchClock` | pública | relógio de parede e fuso do telefone |
| `SessionEngine` | interna | máquina de estados, caixa de entrada, roteamento dos eventos do host |
| `SessionDriverLink` | interna | `DriverLink` da conexão: write/read serializados, log de cada pacote |
| `GattOperations` | interna | esperas GATT casadas pelo `requestId` interno |
| `SessionCommand` | interna | comandos da fila, com o guarda do callback terminal |
| `SyncEngine` | interna | leitura de passos e sono para o histórico |
| `HistoryStore`, `StepsAggregator` | interna | instantâneos do histórico, mescla, poda e totais por dia |

**Fica de fora.** BLE de plataforma (é do host, via `Transport`), qualquer byte de protocolo (é do driver), persistência em disco (é do host, via `SampleStore`), mapeamento para o JavaScript (é do Core RN).

**Dependências.** `domain/`, `transport/`, `profile/`, `log/`, `driver/` (só `Drivers` e os contratos), `util/TimeFormat`, `kotlinx-coroutines`, `kotlinx-datetime` (interno). **Não** importa `sma/`. Quem depende: o host.

**Invariantes**

- Todo estado mutável só é tocado na thread da sessão (paralelismo 1); chamadas e eventos só enfileiram.
- Cada `requestId` recebe exatamente um callback terminal (`TerminalCallbackInvariantTest`).
- Comandos só em `READY`/`SYNCING`, um de cada vez, em ordem.
- O primeiro pareamento nunca reconecta sozinho; a reconexão nunca tem timeout, espera cada vez mais entre tentativas que falharam e desiste quando o relógio recusa o LOGIN (ADR 11).
- `getStepsDays`/`getSleepSessions` devolvem todo dia não confirmado, qualquer que seja `daysBefore` (ADR 15).
- `getStepsDays`/`getSleepSessions` nunca falam com o relógio e nunca bloqueiam.
- Nada do escopo de uma conexão sobrevive à queda dela.

**Testes.** `commonTest/.../session/` (`WatchSessionLifecycleTest`, `WatchSessionCommandsTest`, `TerminalCallbackInvariantTest`, `HistoryStoreTest`, todos com `FakeTransport` + `SmaWatchSimulator` em tempo virtual) e `jvmTest/.../session/` (`SmaSessionReplayTest`, `RealFixturesReplayTest`, com as gravações de `fixtures/`).
