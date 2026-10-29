# `sma/commands/` — catálogo e payloads

**Propósito.** Dizer o que cada `cmd/key` significa e como montar ou ler o seu value. O catálogo completo, com fonte de cada linha, está em `docs/protocolo-sma.md`, seção 6; os layouts de bits, na seção 7.

**Tipos principais**

| Tipo | Papel |
|---|---|
| `SmaCmd` | famílias de `cmd` (`0x02` SET, `0x03` CONNECT, `0x04` NOTICE, `0x05` DATA…) |
| `SmaCommand` | enum com `cmd`, `key`, direção, tipo (HANDSHAKE, SET, GET, NOTICE), `responseKey` e status na PoC; `nameOf`/`label` para log |
| `SmaBindPayload` | 32 bytes de BIND/LOGIN (`ANDROID_FF` ou `USER_ID`) e critérios de sucesso das respostas |
| `SmaTimeCodec` | hora local empacotada em uint32 (`02/01`, `02/07`) |
| `SmaTimezoneCodec` | offset em quartos de hora (`02/68`, só codec) |
| `SmaUserInfoCodec` | perfil em uint32 (`02/10`, só codec) |
| `SmaVersionPayload`, `SmaBatteryPayload`, `SmaUnbindPayload` | respostas `02/0B`, `02/09` e value do `03/05` |

**Status na PoC.** Enviados: `03/01`, `03/03`, `03/05`, `02/01`, `02/08`, `02/0A`, `05/41`, `05/45`. Avisos tratados: `02/70`, `04/62` (nenhum dos dois apareceu na gravação real do fitGPS 0.2.1). `02/0B` do fitGPS 0.2.1 tem 3 bytes, sem versão BLE: `SmaVersionPayload` devolve `bleVersion = null`. Só codec: `02/68`, `02/10`. Catalogado e não enviado: `02/6F` (payload desconhecido nas duas SDKs).

**Fica de fora.** Envio, espera e retentativa (`SmaDriver`); registros de histórico (`data/`).

**Dependências.** `codec/` (só para formatar `cmd/key`), `profile/BindPayloadKind`, `util/`, `kotlinx-datetime` (`LocalDateTime` interno). Quem depende: `SmaDriver` e os testes.

**Invariantes**

- Codecs de envio lançam `IllegalArgumentException` para valores que não cabem no campo; nunca truncam em silêncio.
- Toda resposta tem `key = key do pedido + 1` (conferido em `SmaCommandTest`).
- Mudou um byte aqui: atualizar `docs/protocolo-sma.md` e o vetor dourado no mesmo PR.

**Testes.** `SmaPayloadsTest` (`SmaTimeCodecTest` com bordas e propriedade contra as expressões Java de `SmaTime.java`, `SmaTimezoneCodecTest`, `SmaUserInfoCodecTest` contra `SmaUserInfo.java`, `SmaSimplePayloadsTest`, `SmaCommandTest`) e os vetores de `SmaFrameGoldenTest`.
