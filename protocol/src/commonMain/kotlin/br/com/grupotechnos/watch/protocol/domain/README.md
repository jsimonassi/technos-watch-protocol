# `domain/` — tipos puros

**Propósito.** Os valores que atravessam a API pública: `ConnectionState`, `DataKind`, `DeviceInfo`, `Battery`, `StepsSample`, `StepsDay`, `SleepStageType`, `SleepStage`, `SleepSession` e os códigos de erro estáveis `WatchErrorCodes`. Também a `WatchException` interna, que carrega um desses códigos e nunca sai do módulo.

**Fica de fora.** Lógica: agregação, conversão de datas e decodificação estão em `session/`, `util/`, `sma/`, `ido/` e `crp/`.

**Dependências.** Nenhuma. Quem depende: todos os outros pacotes e o host.

**Invariantes**

- Datas como epoch millis (instantes reais, UTC) mais strings ISO-8601 com offset numérico; nada de `kotlinx-datetime` na superfície (Swift).
- `dayKey` é sempre `yyyy-MM-dd` no fuso do telefone; nas sessões de sono, é o dia em que a pessoa acordou (no CRP, a data de sono da SDK, com a virada às 20h).
- `Battery.charging` tem padrão `false` (SMA e iDO); no CRP, uma leitura de 2A19 acima de 100 quer dizer carregando, com nível = valor − 100 (regra do Core de produção).
- `StepsSample.mode` é da família: no CRP, 0 = meia hora do histórico `0x59`, 1 = meia hora atual completada com FEE1, 2 = amostra de totais do dia (`steps = 0`, calorias e distância do relógio, em `início do dia + 1 s`). O tipo não ganhou campo para isso: a persistência JSON do host não muda.
- `SleepSession.totalMinutes` segue a família: no SMA inclui o acordado; no CRP é leve + profundo + REM, como a SDK e o Core.
- `DeviceInfo.bleVersion` é a pilha BLE no SMA e a versão do protocolo (2A29, `MOYOUNG-V2`) no CRP.
- `SleepStageType.code` igual ao `TechnosSleepType` do Core atual, para o mapeamento ao JavaScript ser direto.
- `WatchErrorCodes` são strings estáveis; mudar um valor quebra o JavaScript. O significado de cada um está em `docs/arquitetura.md`, seção 8.

**Testes.** Indiretos, pelos testes de sessão e de dados.
