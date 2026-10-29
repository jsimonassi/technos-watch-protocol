# `util/` — bytes, hexadecimal e datas

**Propósito.** Utilitários sem conhecimento de protocolo, usados por todas as famílias e pela sessão.

**Tipos principais**

| Tipo | Papel |
|---|---|
| `ByteReader` | leitura sequencial big-endian sem sinal (`u8`, `u16`, `u24`, `u32`); `u16At`/`u32At` estáticos |
| `ByteWriter` | escrita big-endian com checagem de faixa; `zeros`, `bytes` |
| `Hex` | `encode` (maiúsculo, sem separador), `encodeSpaced` (log), `decode` (aceita espaço e `:`) |
| `TimeFormat` | `isoWithOffset`, `dayKey`, `startOfDayEpochMillis`, `startOfDayIso`, `toLocalWallClock`, `fromLocalWallClock`, `formatLocal`, `parseDayKey` |

**Formatos de data.** ISO sempre `yyyy-MM-dd'T'HH:mm:ssXXX` com offset numérico, inclusive `+00:00` em UTC (nunca `Z`). Chave de dia `yyyy-MM-dd`. Em lacuna de horário de verão o horário local é empurrado para frente; em sobreposição vale a primeira ocorrência (regras do `kotlinx-datetime`, fixadas em `TimeFormatTest`).

**Fica de fora.** Qualquer regra de protocolo (época de 2000 do SMA está em `sma/data/SmaLocalTime`).

**Dependências.** Só `kotlinx-datetime` e a biblioteca padrão; nada de `java.*`. Quem depende: `sma/`, `log/`, e a sessão (datas do `StepsDay` e do `SampleStore`).

**Invariantes**

- Tudo `internal`; os tipos do `kotlinx-datetime` não vazam para a API pública.
- Leituras além do fim lançam `IndexOutOfBoundsException`; escritas fora da faixa lançam `IllegalArgumentException`.
- Nenhuma função lê o relógio ou o fuso do sistema: o fuso é sempre parâmetro.

**Testes.** `commonTest/.../util/UtilTest.kt`: `ByteReaderWriterTest` e `TimeFormatTest` (São Paulo com e sem horário de verão, início em 2018 sem meia-noite, fim em 2019 com sobreposição, Lisboa com lacuna e sobreposição em 2026, Katmandu +05:45).
