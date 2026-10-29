# `domain/` — tipos puros

**Propósito.** Os valores que atravessam a API pública: `ConnectionState`, `DataKind`, `DeviceInfo`, `Battery`, `StepsSample`, `StepsDay`, `SleepStageType`, `SleepStage`, `SleepSession` e os códigos de erro estáveis `WatchErrorCodes`. Também a `WatchException` interna, que carrega um desses códigos e nunca sai do módulo.

**Fica de fora.** Lógica: agregação, conversão de datas e decodificação estão em `session/`, `util/` e `sma/`.

**Dependências.** Nenhuma. Quem depende: todos os outros pacotes e o host.

**Invariantes**

- Datas como epoch millis (instantes reais, UTC) mais strings ISO-8601 com offset numérico; nada de `kotlinx-datetime` na superfície (Swift).
- `dayKey` é sempre `yyyy-MM-dd` no fuso do telefone; nas sessões de sono, é o dia em que a pessoa acordou.
- `SleepStageType.code` igual ao `TechnosSleepType` do Core atual, para o mapeamento ao JavaScript ser direto.
- `WatchErrorCodes` são strings estáveis; mudar um valor quebra o JavaScript. O significado de cada um está em `docs/arquitetura.md`, seção 8.

**Testes.** Indiretos, pelos testes de sessão e de dados.
