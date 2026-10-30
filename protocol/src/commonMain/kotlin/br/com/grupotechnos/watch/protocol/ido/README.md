# `ido/` — família iDO (Mormaii Life Ultra GPS)

**Propósito.** Implementa, sem SDK de fabricante, o pedaço do protocolo iDO legado que a PoC usa com o Life Ultra GPS: frame, catálogo de comandos, parsers das respostas, a autenticação do vínculo e o `IdoDriver`, que é o `DeviceDriver` da família. A especificação, com cada byte e a linha do log da SDK de produção que o prova, está em `docs/protocolo-ido.md`; a decisão, na ADR 22 de `docs/decisoes.md`.

**Visibilidade.** Tudo `internal`. A sessão só enxerga a família via `driver/Drivers.create`.

**Arquivos**

| Arquivo | Conteúdo |
|---|---|
| `IdoFrame.kt` | `IdoFrame` (frame recebido, completado com zeros até 20 bytes, leitura por offset em little-endian), `IdoDecoded` (frame, V3, curto demais), `IdoFrameCodec` (`encode`, `decode`, `describe`) |
| `IdoCommand.kt` | catálogo `cmd/key`: `02/01` informação básica, `02/EB` firmware, `02/A0` dados ao vivo, `03/01` hora, `04/01` bind, `04/05` autenticação |
| `IdoPayloads.kt` | `IdoBasicInfo`, `IdoBindPayload` + `IdoBindReply`, `IdoAuth` (o embaralhamento do desafio), `IdoTimeCodec` (fuso em centésimos de hora, semana com segunda = 0), `IdoFirmwarePayload`, `IdoLiveData` |
| `IdoDriver.kt` | handshake (`BIND` no primeiro pareamento, `LOGIN` na reconexão), bateria, firmware, hora, passos do dia; sono, avisos e desvínculo vazios |

**Fica de fora.** BLE de plataforma (host); escolha do candidato GATT, CCCD (inclusive o `0af2`) e MTU (sessão, pelo perfil); estado de conexão, fila, persistência e agregação (sessão). Também fora da PoC: protocolo V3 (histórico, sono, configurações), canal de saúde (`0af1`), desvínculo (bytes desconhecidos), notificações, alarmes, EPO e OTA.

**Dependências.** `driver/` (contrato `DeviceDriver`/`DriverLink`), `domain/`, `profile/` (quirks), `transport/` (`WriteType`), `log/`, `util/` (`TimeFormat`, `Hex`), `kotlinx-coroutines`, `kotlinx-datetime`. Quem depende deste pacote: só `driver/Drivers`.

**Invariantes**

- Todo frame tem no máximo 20 bytes e vai numa escrita `WITHOUT_RESPONSE` (ATT Write Command), uma de cada vez.
- A resposta é o primeiro frame com o mesmo `cmd/key` do pedido em andamento (o mesmo `cmd` com outra `key` não serve); pacotes V3 (`0x33`) e frames que ninguém pediu vão para o log em DEBUG e são descartados.
- O pedido em andamento é registrado antes da escrita: no Android a resposta pode chegar antes da confirmação da escrita sem resposta (`20261029.log` linhas 96, 120 e 121).
- Pacotes recebidos com menos de 20 bytes são completados com zeros antes do parse, como a SDK.
- O bind (`04/01`) e a autenticação (`04/05`) nunca são reenviados e esperam `bindTimeoutMs`; leituras e hora têm uma retentativa em timeout. Um `03/01` com status diferente de 0 é `PROTOCOL`, sem retentativa.
- As calorias dos passos são as totais do `02/A0` (repouso + atividade), não as de atividade que a tela mostra (`IdoLiveData`).
- Uma reconexão nunca manda `04/01`; o relógio tem de responder `pairFlag` 1, senão `HANDSHAKE_FAILED`.
- Nenhum tempo real: toda espera é `withTimeoutOrNull` de corrotina.
- Uma linha `IdoDriver/frame` (DEBUG) por frame em cada sentido; o driver nunca chama `WatchLogger.frame`, que é só do link.

**Testes.** `commonTest/.../ido/`: `IdoFrameGoldenTest` (cada TX e RX contra a linha do log da SDK) e `IdoPayloadsTest` (fuso, semana, autenticação com desafios aleatórios, recepção, perfil). Pela sessão: `IdoSessionTest` e `IdoRealFixturesReplayTest` (`commonTest/.../session/`), com o `IdoWatchSimulator` e o `IdoReplay.kt` do kit de teste (`protocol/src/testKit/`). O XOR, a fórmula do fuso e a semana passaram por teste de mutação (cinco ou seis testes caem para cada mutação), assim como o casamento por `cmd/key`, o registro do pedido antes da escrita, o status do `03/01`, o bind sem reenvio, o `pairFlag` da reconexão e o `auth_code` (pelo menos um teste cai para cada um).
