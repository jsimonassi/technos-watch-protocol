# `log/` — diagnóstico e gravações

**Propósito.** `WatchLogger` é a saída de log que o host implementa (Logcat, `os_log`, tela de log do app de exemplo): mensagens com nível e tag, e cada pacote GATT cru com o resumo dado pelo driver. `FrameJsonl` formata esses pacotes no formato `.jsonl` das fixtures, para o host gravar.

**Tipos.** `WatchLogger`, `LogLevel`, `FrameDirection`, `NoopWatchLogger`, `FrameJsonl` (`header`, `line`).

**Fica de fora.** Gravar em disco, rotacionar, exportar (é do host). Ler gravações (é do `RecordingLoader` dos testes JVM).

**Dependências.** `util/Hex`. Quem depende: todos os pacotes que registram algo, o host e os testes.

**Convenções**

- Tags `Classe/metodo` (ex.: `Session/transition`, `SmaDriver/handshake`); mensagens em inglês.
- A sessão registra em INFO toda transição de estado com `de -> para` e o motivo (tag `Session/transition`).
- `frame` recebe cada fragmento como foi escrito ou notificado (TX e RX), nunca o frame remontado; o resumo vem de `DeviceDriver.describe` e é vazio nas continuações. Só o `SessionDriverLink` chama `frame`, uma vez por pacote: é dele que o host grava a fixture, então nenhum outro código pode chamar `frame` (duplicaria pacotes na gravação). Uma notificação que não tem link para recebê-la (depois do fim da conexão, entre conexões ou em outra characteristic) fica só no log, em DEBUG `Session/onNotification` com o hex, e fora da gravação: gravada, o replay a reemitiria depois do TX anterior, talvez dentro de um link vivo (`WatchSessionLifecycleTest.droppedNotificationsGoToTheLogButNeverToTheRecording`).
- Frames remontados vão para `log`: o `SmaDriver` escreve uma linha em DEBUG com a tag `SmaDriver/frame` por frame de dados completo, nos dois sentidos (ACK/NACK ficam de fora). Formatos: `TX seq=<n> <cmd/key NOME> len=<tamanho do value>` (antes de escrever os fragmentos) e `RX seq=<n> <cmd/key NOME> len=<tamanho do value> crc=ok` (depois de remontar, antes do ACK). Exemplo: `TX seq=2 02/08 BATTERY len=0` e `RX seq=257 02/09 BATTERY_BACK len=1 crc=ok`. Um RX com CRC errado não gera esta linha: gera o WARN `SmaDriver/receive corrupted frame` e um NACK.
- Dois WARN que apontam problema fora da biblioteca: `Session/mergeSteps` com amostras do futuro (relógio com a data errada; as de amanhã até 3 h à frente de agora entram e geram só um INFO `kept … within 3 h of now`) e `Session/onDisconnected` `… with no connection attempt` (host que entregou uma queda que ninguém esperava, por exemplo depois do Bluetooth desligado, fora do contrato B).
- O logger é chamado na thread da sessão e não deve bloquear; uma exceção dele é engolida.
- O formato está em `docs/fixtures.md`, seção 1; mudar um campo exige atualizar o `RecordingLoader`, o `hci2jsonl.py` e a doc juntos.

**Testes.** `Hci2JsonlTest` (o script e o leitor concordam com o formato), `RecordingLoaderTest` (`recordedAt` com e sem milissegundos), `SmaSessionReplayTest.committedFixtureMatchesTheGenerator` (a fixture é escrita com `FrameJsonl`), `SmaDriverTest.everyDataFrameIsLoggedOnceInEachDirectionWithoutRecordingPackets` (a linha `SmaDriver/frame` e nenhum `frame` vindo do driver).
