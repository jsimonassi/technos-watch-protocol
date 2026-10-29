# `sma/codec/` — frame, CRC, fragmentação e remontagem

**Propósito.** Transformar comandos em bytes e bytes em frames, sem nenhum estado de conexão. É a parte do protocolo que tem vetores dourados (`docs/protocolo-sma.md`, seções 2 a 4 e 9).

**Tipos principais**

| Tipo | Papel |
|---|---|
| `SmaFrame` | frame de dados decodificado: `sequence`, `cmd`, `key`, `value` |
| `SmaFrameCodec` | `encode`, `decode` (resultado tipado `SmaDecoded`), `ack`, `nack`, `frameLength`, `isControl`; limite de 2048 bytes |
| `SmaDecoded` / `SmaDecodeError` | `Data`, `Ack`, `Nack`, `Invalid(BAD_MAGIC, TRUNCATED, OVERSIZED, BAD_LENGTH, CRC_MISMATCH)` |
| `Crc16Arc` | CRC-16/ARC (poly refletido `0xA001`, init 0, sem xorout) |
| `SmaReassembler` | junta notificações em frames; recebe `nowMs` a cada chamada |
| `SmaFragmenter` | corta um frame em escritas de `maxFragment ?: mtu − 3` (mínimo 20), sem cabeçalho por fragmento |
| `SmaSequence` | sequence id uint16, começa em 1, volta a 0 depois de `0xFFFF` |

**Fica de fora.** Significado de `cmd/key` e de payloads (`commands/`), quando enviar ACK e o que fazer com cada frame (`SmaDriver`).

**Dependências.** `util/` (`ByteReader`, `ByteWriter`, `Hex`) e `profile/Quirks` (só no `SmaFragmenter`). Quem depende: `SmaDriver`, `commands/` (nome de `cmd/key` para log) e os testes.

**Invariantes**

- `decode` nunca lança: todo erro vira `SmaDecoded.Invalid`.
- Uma notificação de exatamente 8 bytes com `AB` e bit `0x10` é ACK/NACK e nunca entra no corpo de um frame.
- Tamanhos lidos sem sinal (sem o bug de `SmaBleHelper.java:43`).
- `SmaReassembler` não é thread-safe; o driver chama de uma única corrotina.

**Testes.** `SmaFrameGoldenTest` (10 vetores nos dois sentidos), `SmaFrameCodecTest` (erros tipados, propriedade encode/decode, corrupção de 1 byte), `Crc16ArcTest`, `SmaSequenceTest`, `SmaReassemblerTest` (cabeçalho separado, junto, `AB` solto, ACK entre fragmentos, CRC errado, gap, lixo, propriedade de particionamento), `SmaFragmenterTest` (propriedade `concat(split) == frame` para fragmentos de 20 a 509).
