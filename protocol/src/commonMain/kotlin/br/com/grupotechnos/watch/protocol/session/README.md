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

**Dependências.** `domain/`, `transport/`, `profile/`, `log/`, `driver/` (só `Drivers` e os contratos), `util/TimeFormat`, `kotlinx-coroutines`, `kotlinx-datetime` (interno). **Não** importa `sma/` nem `ido/`. Quem depende: o host.

**Invariantes**

- Todo estado mutável só é tocado na thread da sessão (paralelismo 1, conferido pelo `SessionThreadingTest`); chamadas e eventos só enfileiram. A thread é lógica: um bloco por vez, mas a thread do sistema pode mudar entre blocos (no iOS, o `Dispatchers.Default` roda no pool do GCD).
- Cada `requestId` recebe exatamente um callback terminal (`TerminalCallbackInvariantTest`).
- Comandos só em `READY`/`SYNCING`, um de cada vez, em ordem.
- O primeiro pareamento nunca reconecta sozinho; a reconexão nunca tem timeout, espera cada vez mais entre tentativas que falharam e desiste quando o relógio recusa o LOGIN (ADR 11).
- Bluetooth desligado não deixa resposta esperada para trás: a contagem de desconexões esperadas zera e o `disconnect` de limpeza não conta (contrato B, ADR 18), então a primeira falha depois de religar nunca é engolida.
- A mescla de passos só aceita amostras de `hoje - historyRetentionDays` até hoje; o resto vai para o log (`Session/mergeSteps`).
- `getStepsDays`/`getSleepSessions` devolvem todo dia não confirmado, qualquer que seja `daysBefore` (ADR 15).
- `getStepsDays`/`getSleepSessions` nunca falam com o relógio e nunca bloqueiam.
- Nada do escopo de uma conexão sobrevive à queda dela.
- No CONFIGURING, os CCCD são ligados um de cada vez: `notify` e depois cada `extraNotify` do candidato, na ordem do perfil; as notificações de todos chegam ao driver. O MTU só é pedido quando `Quirks.requestMtu` passa de 23.

**Testes.** `commonTest/.../session/` (`WatchSessionLifecycleTest`, `WatchSessionCommandsTest`, `BluetoothOffContractTest`, `TerminalCallbackInvariantTest`, `HistoryStoreTest`, todos com `FakeTransport` + `SmaWatchSimulator` em tempo virtual; `IdoSessionTest`, o Life Ultra GPS com `FakeTransport.scriptedIdo` + `IdoWatchSimulator`; `IdoRealFixturesReplayTest`, as gravações iDO de `fixtures/real/`; `RealFixturesReplayTest`, com as gravações de `fixtures/`, inclusive a real do fitGPS 0.2.1, nos quatro targets; `RealFixturesStepsTest`, o total do dia das duas gravações reais contra a tela do relógio, em cada uma e com as duas no mesmo histórico, com `DAY_CUMULATIVE` e `HOURLY_SUM` (ADR 1), e o da gravação do iPhone depois da meia-noite; `RealFixturesSleepTest`, a noite que a gravação do iPhone produz, igual à da SDK; `RealtimeReplayTest`, a gravação real no dispatcher de produção, em tempo real, com os eventos vindos de uma fila de host; `SessionThreadingTest`, que o dispatcher dos construtores públicos é serial e que as consultas síncronas logam na thread de quem chama) e `jvmTest/.../session/SmaSessionReplayTest` (a gravação sintética de ponta a ponta).
