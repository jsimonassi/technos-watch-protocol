# `profile/` — o que se sabe de cada modelo

**Propósito.** Descreve os modelos suportados como dados: nomes de advertising, serviços GATT em ordem de prioridade e quirks (timeouts, MTU, payload do BIND, agregação de passos, retenção). O mesmo driver serve modelos com quirks diferentes.

**Tipos.** `DeviceProfile`, `Family`, `GattCandidate`, `Quirks`, `BindPayloadKind`, `StepsAggregation`, `Profiles` (hoje só `MORMAII_FIT_GPS`, candidatos `SMA_ROUND` e `SMA_NUS`), `DeviceRegistry` (`identify(bleName, advertisedServices)` para o scan, `byId(id)` para a sessão).

**Fica de fora.** Comportamento: nenhum código aqui fala com o relógio. Perfis só são acrescentados com uma `Family` que tenha driver.

**Dependências.** Nenhuma. Quem depende: `session/` (escolha do candidato, timeouts, agregação, retenção), `driver/` (família), `sma/` (quirks do protocolo) e o host (scan).

**Valores do fitGPS e por quê** (cada um tem ADR em `docs/decisoes.md`): `requestMtu = 247` (ADR 5), `FF60` antes do NUS (ADR 6), `bindTimeoutMs = 60000` (ADR 4), `stepsAggregation = HOURLY_SUM` (ADR 1), `historyRetentionDays = 30` (ADR 8), `connectTimeoutMs = 30000`, `commandTimeoutMs = 4000` (o timeout dos semáforos da SDK, `SmaMessenger.java:36`), `postLoginDelayMs = 3000`.

**Invariantes.** UUIDs em 128 bits e minúsculas; `bleNames` comparados de forma exata; `id` estável (é o que o host persiste e o JavaScript recebe).

**Testes.** Indiretos, pelos testes de sessão (`ff60IsPreferredOverNusWhenBothExist`, `incompleteFf60FallsBackToNus`, timeouts).
