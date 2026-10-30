# `sma/` — família SMA (Mormaii fitGPS)

**Propósito.** Implementa o protocolo dos relógios com firmware SMA (bestmafen) sobre um `DriverLink`, sem SDK de fabricante: codec do frame, catálogo de comandos, decodificação do histórico e o `SmaDriver`, que é o `DeviceDriver` da família. A especificação completa está em `docs/protocolo-sma.md` e `docs/sma-dados.md`.

**Visibilidade.** Tudo `internal`. A sessão só enxerga a família via `driver/Drivers.create`.

**Subpacotes**

| Pacote | Conteúdo |
|---|---|
| `codec/` | `SmaFrame`, `SmaFrameCodec`, `Crc16Arc`, `SmaReassembler`, `SmaFragmenter`, `SmaSequence` |
| `commands/` | `SmaCommand` (catálogo `cmd/key`), payload codecs (`SmaBindPayload`, `SmaTimeCodec`, `SmaTimezoneCodec`, `SmaUserInfoCodec`, `SmaVersionPayload`, `SmaBatteryPayload`, `SmaUnbindPayload`) |
| `data/` | registros `05/42` e `05/46`, `SmaStepsMapper`, `SmaSleepAnalyzer` |
| raiz | `SmaDriver`, `SmaEstimates` |

**Fica de fora.** BLE de plataforma (é do host), escolha do candidato GATT, MTU e CCCD (são da sessão), estados de conexão, fila de comandos, persistência e agregação diária (são da sessão). Também fora da PoC: OTA/DFU, XMODEM, watchfaces, notificações, chamadas, HR, pressão, ciclismo, exercícios.

**Dependências.** `driver/` (contrato `DeviceDriver`/`DriverLink`), `domain/`, `profile/` (quirks), `transport/` (`WriteType`), `log/`, `util/`, `kotlinx-coroutines`, `kotlinx-datetime`. Quem depende deste pacote: só `driver/Drivers`.

**Invariantes**

- Cada frame é escrito com todos os fragmentos dentro do `Mutex` do driver; ACK/NACK passam pelo mesmo mutex.
- Frame recebido com CRC certo: ACK é escrito **antes** de entregar.
- Antes do LOGIN só `cmd 0x03` é enviado.
- GET não tem retentativa; SET e handshake têm uma, com seq novo (o iOS reenvia com o mesmo seq; `docs/protocolo-sma.md`, seção 4).
- O ACK atrasado de um pedido cuja resposta já valeu como ACK é descartado, nunca creditado ao pedido seguinte.
- BIND só no primeiro pareamento; uma reconexão nunca envia BIND.
- Nenhum tempo real: toda espera é `withTimeoutOrNull`/`delay` de corrotina, e o relógio do reassembler é injetado.
- `02/6F` (SYNC_SWITCH) não é enviado enquanto o payload não for confirmado.
- Uma linha `SmaDriver/frame` (DEBUG, via `WatchLogger.log`) por frame de dados completo em cada sentido; o driver nunca chama `WatchLogger.frame`, que é só do link (`log/README.md`).

**Testes.** `commonTest/.../sma/`: `SmaDriverTest` (com `FakeDriverLink`, que usa o `SmaWatchSimulator` do kit de teste `protocol/src/testKit/.../testing/`, um relógio simulado em nível de byte compartilhado com os testes de sessão e com os do `CoreBridge` no `:bridge`; `FakeSmaWatch` é o nome antigo, mantido como `typealias`), `SmaFrameGoldenTest`, `SmaFrameCodecTest`, `SmaReassemblerTest`, `SmaSleepAnalyzerTest` e os arquivos `SmaPayloadsTest.kt` e `SmaDataTest.kt`, cada um com várias classes (detalhes nos READMEs de `commands/` e `data/`).
