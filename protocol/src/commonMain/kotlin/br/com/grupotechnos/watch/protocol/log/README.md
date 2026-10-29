# `log/` — diagnóstico e gravações

**Propósito.** `WatchLogger` é a saída de log que o host implementa (Logcat, `os_log`, tela de log do app de exemplo): mensagens com nível e tag, e cada pacote GATT cru com o resumo dado pelo driver. `FrameJsonl` formata esses pacotes no formato `.jsonl` das fixtures, para o host gravar.

**Tipos.** `WatchLogger`, `LogLevel`, `FrameDirection`, `NoopWatchLogger`, `FrameJsonl` (`header`, `line`).

**Fica de fora.** Gravar em disco, rotacionar, exportar (é do host). Ler gravações (é do `RecordingLoader` dos testes JVM).

**Dependências.** `util/Hex`. Quem depende: todos os pacotes que registram algo, o host e os testes.

**Convenções**

- Tags `Classe/metodo` (ex.: `Session/transition`, `SmaDriver/handshake`); mensagens em inglês.
- A sessão registra em INFO toda transição de estado com `de -> para` e o motivo (tag `Session/transition`).
- `frame` recebe cada fragmento como foi escrito ou notificado (TX e RX), nunca o frame remontado; o resumo vem de `DeviceDriver.describe` e é vazio nas continuações.
- O logger é chamado na thread da sessão e não deve bloquear; uma exceção dele é engolida.
- O formato está em `docs/fixtures.md`, seção 1; mudar um campo exige atualizar o `RecordingLoader`, o `hci2jsonl.py` e a doc juntos.

**Testes.** `Hci2JsonlTest` (o script e o leitor concordam com o formato), `SmaSessionReplayTest.committedFixtureMatchesTheGenerator` (a fixture é escrita com `FrameJsonl`).
