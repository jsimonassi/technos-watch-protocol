# `profile/` — o que se sabe de cada modelo

**Propósito.** Descreve os modelos suportados como dados: nomes de advertising, serviços GATT em ordem de prioridade e quirks (timeouts, MTU, payload do BIND, agregação de passos, retenção). O mesmo driver serve modelos com quirks diferentes.

**Tipos.** `DeviceProfile`, `Family`, `GattCandidate`, `Quirks`, `BindPayloadKind`, `StepsAggregation`, `Profiles` (`MORMAII_FIT_GPS`, candidatos `SMA_ROUND` e `SMA_NUS`; `MORMAII_LIFE_ULTRA_GPS`, candidato `IDO_MAIN` com `extraNotify`), `DeviceRegistry` (`identify(bleName, advertisedServices)` para o scan, `byId(id)` para a sessão).

**Fica de fora.** Comportamento: nenhum código aqui fala com o relógio. Perfis só são acrescentados com uma `Family` que tenha driver.

**Dependências.** Nenhuma. Quem depende: `session/` (escolha do candidato, CCCD extras, MTU, timeouts, agregação, retenção), `driver/` (família), `sma/` e `ido/` (quirks do protocolo) e o host (scan).

**Valores do fitGPS e por quê** (cada um tem ADR em `docs/decisoes.md`): `requestMtu = 247` (ADR 5; o Android da gravação real negociou 23), `FF60` antes do NUS (ADR 6; o firmware 0.2.1 só expõe o NUS), `bindTimeoutMs = 60000` (ADR 4), `stepsAggregation = DAY_CUMULATIVE` (ADR 1; no firmware 0.2.1 o `mode 0` é o acumulado do dia, confirmado no relógio em 2026-10-29), `historyRetentionDays = 30` (ADR 8), `connectTimeoutMs = 30000`, `commandTimeoutMs = 4000` (o timeout dos semáforos da SDK, `SmaMessenger.java:36`), `postLoginDelayMs = 3000`.

**Valores do Life Ultra GPS e por quê** (`docs/protocolo-ido.md`, ADR 22): nomes `Life Ultra GPS` (o que o scan de produção viu) e `Smart Watch 6010` (catálogo do Core atual), `appName` `Life Ultra GPS`, slug `lifeultragps`; `IDO_MAIN` = serviço `0af0`, escrita `0af6`, notify `0af7` e `extraNotify` `0af2` (a SDK liga os dois CCCD, nessa ordem); `requestMtu = 23` (a SDK não pede MTU, e a sessão não pede até 23); `maxFragment = 20`; `bindTimeoutMs = 90000` (a autenticação só responde depois do toque na tela; o relógio espera até 240 s); `commandTimeoutMs = 4000`; `postLoginDelayMs = 1000`; `stepsAggregation = DAY_CUMULATIVE` (o `02/A0` é o acumulador do dia); `historyRetentionDays = 30`. `fragmentGapTimeoutMs`, `bindPayload` e `bindUserId` são do SMA e ficam em valores neutros.

**Invariantes.** UUIDs em 128 bits e minúsculas; `bleNames` comparados de forma exata; `id` estável (é o que o host persiste e o JavaScript recebe). A identificação no scan é só pelo nome: o fitGPS 0.2.1 não anuncia o NUS (anuncia `180f`, `180a` e `00000001-0000-1000-8000-00805f9b34fb`), então um filtro de scan por serviço não o encontra. `extraNotify` vazio no SMA.

**Testes.** Indiretos, pelos testes de sessão (`ff60IsPreferredOverNusWhenBothExist`, `incompleteFf60FallsBackToNus`, timeouts; no iDO, `IdoSessionTest`: os dois CCCD, sem MTU, `missingHealthCharacteristicIsSkipped`), e `IdoPayloadsTest.lifeUltraGpsIsIdentifiedByItsNames`.
