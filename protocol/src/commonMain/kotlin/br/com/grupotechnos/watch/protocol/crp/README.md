# `crp/` — família CRP (Moyoung; Condor Reebok P85)

**Propósito.** Implementa, sem SDK de fabricante, o pedaço do protocolo CRP (Moyoung, SDK `crpblelib`) que a fase 1 usa com o Condor Reebok (nome BLE `P85`, firmware `MOY-VS02-2.0.0`): frame `FE EA`, remontagem, catálogo de comandos, payloads, passos por meia hora com os totais do dia, sono e o `CrpDriver`, que é o `DeviceDriver` da família. A especificação, com cada byte e a fonte, está em `docs/protocolo-crp.md`; as decisões, nas ADRs 25 a 32 de `docs/decisoes.md`.

**Fontes citadas.** `sdk:` = `com/crrepa/...` da SDK Android descompilada (crpblelib 2.0.3, a versão do Core de produção); `core@v4.3.57:` = `git show v4.3.57:<caminho>` no `TechnosWareCore`; `iOS-bin@0x…` = endereço no binário da SDK iOS. Os bytes reais vêm das fixtures de referência `fixtures/reference/condorreebok/MOY-VS02-2.0.0/` (captura HCI do app Condor de produção, citadas por arquivo e `atMs`); o que a fixture não guarda (BR/EDR, o nome GAP) é citado pelo horário da captura (`HCI hh:mm:ss.mmm`).

**Visibilidade.** Tudo `internal`. A sessão só enxerga a família via `driver/Drivers.create`.

**Arquivos**

| Arquivo | Conteúdo |
|---|---|
| `CrpFrame.kt` | `CrpFrame` (frame remontado, casamento por `cmd`/sub), `CrpProtocolVersion` (V1/V2/V3 pelo texto de 2A29, regra `contains` do iOS), `CrpFrameCodec` (`blockSize`, `encode`, `fragments`, `totalLength`, `describe`) |
| `CrpReassembler.kt` | remontagem de `FEE3`: comprimento sem sinal, nibble alto 1-3, ressincronização em `FE EA`, descarte de excesso e de continuação solta |
| `CrpCommand.kt` | `CrpCommand` (constantes de `cmd`/sub, `label`, `postDelayMs` com a tabela da fila da SDK) e `CrpGatt` (UUIDs de 2A29, 2A28, 2A19, FEE1, 2A37) |
| `CrpPayloads.kt` | `CrpRequest` (construtores de pedido), `CrpText`, `CrpTimeCodec` (`31`: relógio de parede relido como GMT+8 + `08`), `CrpTimeZoneCodec` (`BB 07 00` + int32 LE), `CrpStepsTotals` (FEE1, `33 01` e `B6 00`), `CrpBatteryReading` (regra > 100 = carregando), `CrpCategoryPage` (`59` u16; `5D`/`5C` u32, só catálogo), `CrpSleepRecord` + `CrpSleepPayload` (legado e novo), `CrpClassicState` (`F9`) |
| `CrpStepsMapper.kt` | meias horas e totais → `StepsSample` (`mode` 0, 1 e 2), `LiveStepsState`, `liveUpdate` (passos ao vivo de FEE1) |
| `CrpSleepAssembler.kt` | registros → `SleepSession`, igual à SDK, com a data de sono da virada às 20h |
| `CrpDriver.kt` | envio serial com o atraso por comando, recepção de `FEE3` e das notificações laterais, handshake de versão, bateria, firmware, hora, passos, sono e `F8` |

**Fica de fora.** BLE de plataforma (host); escolha do candidato, CCCDs (inclusive as laterais `2a37`, `fee1`, `2a19`) e MTU (sessão, pelo perfil); estado de conexão, fila de comandos, persistência, agregação e o limite de 2 s dos passos ao vivo (sessão); o vínculo Bluetooth clássico (host, pelo `CoreBridge`). Fora da fase 1: `5D`/`5C` e `B6` (o P85 não responde), reset `51 02` e formato 12/24 h `17` (só catálogo), perfil `0x12`, metas, atividades `B2`, FC `35`, estresse `B9`, OTA e mostradores.

**Dependências.** `driver/` (contratos), `domain/`, `profile/` (quirks), `transport/` (`WriteType`, `GattUuid`), `log/`, `util/` (`TimeFormat`, `Hex`), `kotlinx-coroutines`, `kotlinx-datetime`. Quem depende deste pacote: só `driver/Drivers`.

**Invariantes**

- Um pedido de cada vez, sob o `Mutex` do driver; o pedido em andamento é registrado antes da escrita (a resposta pode chegar antes da confirmação da escrita sem resposta).
- Toda escrita é `WITHOUT_RESPONSE` em `FEE2`, em pedaços do bloco (20 até a versão; depois `min(MTU, 250) − 3` arredondado a múltiplo de 4), com 20 ms entre pedaços e o atraso da fila da SDK depois do frame (`postDelayMs`: 200 ms padrão, 500 ms, 1000 ms no `33`, 50 ms no `67`).
- A resposta casa por `(cmd, sub)`: sub = primeiro byte do payload em `5A`, `59`, `5D`, `5C` e `33`, os dois primeiros em `B6`. O `32` aceita `32` ou `B6 01 00`; o `33 0x` aceita o `B6` equivalente.
- Pedido sem resposta não trava nada: espera `commandTimeoutMs` (contado depois do atraso) e quem chamou decide. Página de passos sem resposta é `TIMEOUT` (passos são obrigatórios); `32` sem resposta segue sem o sono de hoje; `33` sem resposta desliga o legado até a próxima conexão.
- O handshake é o mesmo no primeiro pareamento e na reconexão (fase `VERSION`): 2A29, `5A 00` sempre, e `B7 0E` + `BB 16 00` no V2/V3; sem texto nenhum, `TIMEOUT`.
- `5D`, `5C` e `B6` nunca são enviados: a captura mostra que o P85 não responde.
- FEE1 e 2A19 só viram avisos depois do handshake; FEE1 com totais zerados é ignorado; sem as meias horas lidas nesta conexão (ou com o dia mudado, ou depois de um `readSteps` que falhou), FEE1 vira **um** `DataChanged`, e os seguintes não pedem de novo até um `readSteps` terminar ou `REFRESH_RETRY_MS` (30 s) passar: o debounce do `DataChanged` na sessão reinicia a cada aviso, e andando o FEE1 chega a cada segundo (`walk@114170` a `@124147`).
- A remontagem é zerada a cada conexão (um driver por conexão) e descarta a parcial depois de `fragmentGapTimeoutMs`.
- Os logs nunca levam o payload dos frames (os de notificação e clima têm texto do usuário): `CrpDriver/frame` em DEBUG por frame, `F9` em INFO, o resto em DEBUG; o driver nunca chama `WatchLogger.frame`, que é só do link.
- Nenhum tempo real: toda espera é `delay`/`withTimeoutOrNull` de corrotina.

**Testes.** `commonTest/.../crp/`: `CrpFrameGoldenTest` (cada TX contra a captura ou a fase 0, e os RX reais decodificados), `CrpFrameCodecTest`, `CrpReassemblerTest`, `CrpPayloadsTest`, `CrpSleepAssemblerTest` (vetor da fase 0 nos dois formatos, virada às 20h), `CrpStepsMapperTest`. Pela sessão (`commonTest/.../session/`): `CrpSessionTest` (com o `CrpWatchSimulator` do kit de teste), `CrpSyntheticReplayTest` (replay estrito de `fixtures/synthetic/condorreebok-pair-sync.jsonl`), `CrpReferenceFixtureTest` (decodificação das fixtures de referência) e `CrpRealFixturesReplayTest`. Na JVM: `CrpSessionReplayTest` (a fixture sintética é a saída do gerador) e `Btsnoop2JsonlTest` (o conversor da captura).
