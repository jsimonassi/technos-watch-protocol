# Pendências: validação no relógio e itens técnicos abertos

A primeira gravação real (`fixtures/real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl`, fitGPS com firmware 0.2.1 e um telefone Android, 2026-10-29) já reproduz no replay e decidiu parte das hipóteses (`protocolo-sma.md`, seção 8). O que ela não cobriu ficou para uma sessão com o relógio de cerca de 90 min e duas noites, que foi despriorizada: isto é uma prova de conceito, e o caminho de passos, que é o critério de aceite, já funcionou de ponta a ponta. Esta lista guarda o roteiro para quando a sessão acontecer.

**O sono não é prioridade**: o app de produção nunca leu sono do fitGPS (nenhum Core pede `05/45`, `SMAService.java:561, 573, 591`; `SMAService.m:1077-1080`), então não há o que regredir; os itens de sono ficam no fim.

Cada item diz a hipótese ou o critério que fecha e onde registrar o resultado. Ao fechar um item, marque a caixa, cite a fixture nova pelo `atMs` (ou o log pelo `ts`) e atualize os documentos indicados na mesma alteração. Os critérios A1 a A8 e T1 a T7 são os do plano da PoC.

O caminho do iOS (o que as etapas sem relógio já provaram e o que falta até um host iOS) está na seção [iOS](#ios), no fim.

As pendências do host Android e da integração com o app Mormaii (o watchdog de 50 s do sync do lado do app, polling de passos ao vivo, `FileProvider`, `deviceId`, eventos sem equivalente) estão no [`docs/pendencias.md` do Repo 2](../../react-native-technos-core-next/docs/pendencias.md), que aponta para cá para o roteiro com o relógio.

## Preparação

- [ ] Build do app de exemplo (Repo 2) com: o replay corrigido deste repositório (já está aqui: uma gravação copiada para `fixtures/real/` roda no `./gradlew :protocol:jvmTest`); a linha `SmaDriver/frame` (já está no `SmaDriver`); `recordedAt` com milissegundos (já está no `FrameRecorder` do Repo 2); o log das propriedades da characteristic `6e400002` (falta no Repo 2, seção 6 do `docs/pendencias.md` de lá); opcional, um botão de depuração "pular o próximo ACK" para o teste do H1.
- [ ] **HCI snoop** ligado (`fixtures.md`, seção 3.2). Ele mostra o intervalo de conexão e as propriedades da characteristic em todos os blocos, o que o log do app não mostra.
- [ ] Perguntar a quem fez a primeira gravação:
  - se o relógio pediu para tocar em algo na tela no primeiro BIND (fecha H7 para a gravação de 2026-10-29);
  - quantos passos a tela mostrava: **respondido** em 2026-10-29. À noite a tela mostrava 56 passos no dia, com o relógio parado desde a manhã, o mesmo valor das duas gravações; isso fechou o H3 (`decisoes.md`, ADR 1);
  - se o relógio já tinha sido usado antes (explicaria os 1262 registros de sono de 2023-08 a 2024-11).
- Regras da sessão:
  - **Export antes de cada Clear**: o Clear apaga o anel de log e o recorder. Os exports são cumulativos; versione só o último de cada cenário (`fixtures.md`, seção 3.1). Os `logs-*.jsonl` têm o MAC e nunca entram no repositório.
  - Um cenário por arquivo, com o nome em kebab-case em `fixtures/real/fitgps/<firmware>/`.
  - O anel de 2000 entradas guarda cerca de dois syncs de 64 páginas.
  - Começar por volta de hh:15, para o bloco da virada de hora cair entre hh:40 e hh+1:10.

## Sessão com o relógio (cerca de 90 min)

### 1. Dois syncs seguidos (fazer primeiro, antes de qualquer Forget)

O `03/05` pode limpar o histórico do relógio e misturar o resultado, por isso este bloco vem antes do bloco 2.

- [ ] Clear, reconectar, Sync completo, Sync completo de novo, Export.
  - **Fecha H2 para o sono**: o segundo sync volta com 0 páginas (apagado depois da leitura), só com registros novos, ou com as 64 páginas de novo? Se voltar tudo, a sessão relê o histórico inteiro a cada sync e o custo do item técnico 1 vale para todo sync.
  - **Mede o sync de 59 s**: a duração se repete no segundo sync? Quanto custa cada página (hoje cerca de 900 ms)?
  - Registrar em: linha H2 da tabela de `protocolo-sma.md`, seção 8; `sma-dados.md`, seção 6; fixture `sync-twice.jsonl` com a procedência em `fixtures.md`, seção 5.
  - Indício de 2026-10-29 21:37 (`pair-commands-sync-bt-reconnect-disconnect-reconnect.jsonl`): o Sync da noite trouxe o `05/46` vazio (`atMs` 39035), contra as 64 páginas da manhã, e levou 1,2 s. Não fecha o H2: entre as duas gravações o relógio mandou 12 frames não gravados (seq 79 a 90) e a sessão da noite começou com BIND, então pode ter havido um `03/05` no meio. Como o relógio não tem mais histórico de sono, este bloco só fecha depois de uma noite com ele (T3).
- [ ] Opcional, com o botão de depuração: pular o ACK de uma página `05/42` e repetir o `05/41`. É o teste do H1 (item técnico 5).

### 2. Disconnect, Forget e novo pareamento (A7, H7, A3 a A5, A8)

- [x] **Disconnect** e esperar 60 s sem reconexão; **Reconnect last** (só `03/03`, sem BIND).
  - Feito em 2026-10-29: Disconnect (log, ts 1793320739940), 71 s sem reconexão e Reconnect last só com LOGIN (`atMs` 187070 de `pair-commands-sync-bt-reconnect-disconnect-reconnect.jsonl`).
- [ ] **Forget**: `03/05 [00]` com ACK, estado `DISCONNECTED`, store vazio, "Último pareado: nenhum".
  - Fecha **A7**. Registrar em `fixtures.md`, seção 5 (fixture `disconnect-reconnect-forget.jsonl`).
  - Em 2026-10-29 o Forget e o novo pareamento funcionaram, segundo o usuário, mas não estão na gravação da noite.
- [ ] Scan e pair **olhando a tela do relógio no BIND**: ele pede toque? Quanto tempo do ACK do BIND até o `03/02`?
  - Fecha **H7**. Sem pedido de toque e com o `03/02` em poucos segundos, o `bindTimeoutMs` de 60 s pode cair (`decisoes.md`, ADR 4). Registrar na linha H7 da seção 8 de `protocolo-sma.md` e na ADR 4.
  - Na gravação de 2026-10-29 21:37, o `03/02` chegou 1,9 s depois do BIND (`atMs` 0 a 1899), contra 2,2 s de manhã. Falta confirmar se o relógio pediu toque.
- [ ] Bateria e versão comparadas com a tela do relógio (A4).
- [ ] **Set time com a hora do celular adiantada 5 min**, conferir a tela do relógio, depois voltar à hora automática (A5). Na gravação de 2026-10-29 o Set time do usuário (`atMs` 778427) mandou a mesma hora que o acerto automático já tinha posto, então a tela não mudou.
- [ ] Share e Export (A8). Fixture limpa `pair-basics.jsonl`.

### 3. Virada de hora andando (como a hora fecha, A6, T1)

- [ ] Seguir o roteiro T1: ler e fotografar a tela em hh:40, hh:53, hh+1:03 e hh+1:08; andar sem ler entre hh:56 e hh:59 (o que também serve para ver se chega algum `02/70` espontâneo). No fim, Sync e depois **Passos** (`getHistoryPedometer`). Export.
  - **O H3 já foi respondido** (2026-10-29, sem esta rodada): o `mode 0` do firmware 0.2.1 é o acumulado do dia, e o perfil passou a `DAY_CUMULATIVE` (`decisoes.md`, ADR 1). A prova veio com o relógio parado: o registro ao vivo da noite (`atMs` 29133 e 38440 de `pair-commands-sync-bt-reconnect-disconnect-reconnect.jsonl`) ainda tinha os 56 passos da manhã, e a tela mostrava 56 (`protocolo-sma.md`, seção 8).
  - Esta rodada fica para: **como a hora fecha** (registro em hh:00:00 ou em hh:59:xx, e se os de hh:00 e hh:30 são instantâneos gravados pelo relógio sozinho) e a confirmação com passos de que o valor segue crescendo ao cruzar a hora. Se ele cair depois de hh:00, a ADR 1 volta a ser discutida.
  - Fecha **A6**: total do dia no app igual ao da tela.
  - Registrar em: `sma-dados.md`, seções 2 e 6; ADR 1 só se contradizer o acumulado do dia; fixture `steps-hour-boundary.jsonl`.
- [ ] **Impacto na produção, sem relógio**: o relógio acumula por dia, e a produção soma um instantâneo por hora com registro (`SMAService.java:437-448, 859-866`; `SMAService.m:1277-1288, 334-342`), então os passos diários do fitGPS no backend provavelmente estão inflados. Comparar no backend (`/Users/joao.farias/smartBackend`) a distribuição de passos diários do fitGPS com a dos outros modelos. É o achado da seção 5 do `docs/pendencias.md` do Repo 2. Registrar na "Consequência para a produção" da ADR 1.

### 4. Robustez do link

- [ ] Fora de alcance por mais de 2 min e volta: um `onDisconnected`, `RECONNECTING` sem timeout e `READY` sozinho ao voltar.
- [ ] Fora de alcance e Bluetooth desligado e religado: nenhum `onDisconnected` depois do `onBluetoothStateChanged(false)` (contrato B, `decisoes.md`, ADR 18) e reconexão ao religar.
  - Parte com o relógio em alcance feita em 2026-10-29: Bluetooth desligado em `READY` sem nenhum `onDisconnected` (log, ts 1793320703419, `transport stopped without onDisconnected (active=true)`) e reconexão sozinha, só com LOGIN, 7 s depois de religar (ts 1793320722176 a 1793320729137). Falta o caso fora de alcance.
- [ ] Bluetooth desligado **durante** o sync: o sync termina com `onError(DISCONNECTED)`, o estado vai para `BLUETOOTH_OFF` e volta a `READY` depois de religar. Bateria pedida durante o sync espera na fila.
- [ ] Sync com o app em segundo plano e a tela apagada (serviço em primeiro plano do Repo 2).
- [ ] `adb shell am force-stop` e reabrir; abrir o app com o Bluetooth desligado e um pareamento salvo (o último estado que o JS vê deve ser `BLUETOOTH_OFF`, não `DISCONNECTED`; o primeiro `initialize()` pode reenviar 0 antes, `docs/api-js.md` do Repo 2); reload do JS em `READY` e em `SYNCING`.
  - Abrir com o Bluetooth desligado **sem** pareamento salvo, feito em 2026-10-29: estado `BLUETOOTH_OFF` (log, ts 1793320602042, `reportBluetoothOffAtStartup`). Faltam o caso com pareamento salvo, o force-stop e o reload.
- Fecha: a metade "conexão pendente" da ADR 12 e o contrato B no aparelho. Registrar em: `arquitetura.md`, seção 5, se algum comportamento divergir; ADRs 12 e 18; `docs/transporte-android.md` no Repo 2; uma fixture por caso (`robustness-<caso>.jsonl`).

### 5. H8 cruzado com o app iOS de produção (por último)

Precisa de um iPhone com o app Mormaii de produção, que faz BIND e LOGIN com USER_ID 1 (`SMAService.m:1093, 1196`).

- [ ] Com o relógio pareado por este core, parear pelo app iOS de produção. Desligar o Bluetooth do iPhone.
- [ ] No Android, **Reconnect last**: o LOGIN `ANDROID_FF` é recusado? O LOGIN alternativo com `USER_ID` é aceito? Ou termina em `HANDSHAKE_FAILED`?
- [ ] Depois, Forget e pair pelo Android, e conferir se o app iOS ainda reconecta.
- Fecha **H8** e decide a migração de quem usa o app iOS hoje. Registrar na linha H8 da seção 8 de `protocolo-sma.md` e no "Risco aberto" da ADR 11; fixture `h8-ios-bound-reconnect.jsonl`.

### Opcional: fuso horário (T6)

- [ ] Mudar o telefone para `America/Manaus`, reconectar, ver o `setTime` e procurar registros `mode 16` e registros reenviados. Fecha o gatilho da ADR 16 (item técnico 3).
- [ ] Janela da mescla: com o relógio conectado, trocar o telefone para `America/Manaus` entre 23:00 e 23:59 e fazer **Refresh steps** antes de reconectar. O log deve trazer o INFO `Session/mergeSteps` `kept … within 3 h of now`, nenhum `dropped … after today`, e os passos devem aparecer no dia seguinte. Fecha a emenda da ADR 16 (item técnico 3).

### Opcional: contador zerado no meio do dia

- [ ] Com passos no dia, Refresh steps; reiniciar o relógio (ou deixar a bateria acabar e carregar), reconectar, andar um pouco, Refresh steps e **Passos**, comparando o total do app com a tela. Se a tela recomeçar do zero, o app mostra o pico de antes até a contagem nova passá-lo, e depois perde os passos de antes do zero (`decisoes.md`, ADR 1, "O que continua aberto"). Registrar na ADR 1 e em `sma-dados.md`, seções 6 e 7; fixture `steps-midday-reset.jsonl`.

## Noites (sono, não prioritário)

- [ ] **Noite real (T3)**: Sync na hora de dormir, dormir com o relógio, anotar os horários de deitar e levantar. De manhã: Sync, depois **Sono** (`getHistorySleep`), Export.
  - Fecha o conteúdo do **H4** e testa o analisador com dados reais: as sessões batem com os horários anotados? O critério exploratório é a resposta em até 20 s.
  - Registrar em: linha H4 da seção 8 de `protocolo-sma.md`; `sma-dados.md`, seções 5 e 6; ADR 9; fixture `sleep-night.jsonl`.
- [ ] **Relógio parado na mesa (T4)**: relógio sobre a mesa desde as 13:00, Sync de manhã.
  - Confirma se o padrão da gravação de 2026-10-29 (um START repetido com ENDs de 2 em 2 min) é artefato de relógio parado, o que decide o filtro de plausibilidade (item técnico 4). Registrar em `sma-dados.md`, seção 6.

Ficam para depois: a virada da meia-noite (T5) e os estados de atividade (T7). A T5 também decide quando e como o acumulado do dia zera, com uma conferência explícita:

- [ ] **T5: existe registro em 00:00:00 (ou registro ao vivo logo depois da meia-noite) com o total de ontem?** Refresh steps perto de 23:55 (o fechamento de ontem), nenhuma leitura até de manhã, Sync de manhã e Export; numa segunda noite, Refresh steps às 00:01. Procurar, nos registros `mode 0` da hora 00, um valor igual ao fechamento de ontem, e anotar quando a tela zera. Se existir, com `DAY_CUMULATIVE` o dia de hoje mostra o total de ontem até a contagem de hoje passá-lo, e só outro registro da hora 00 o corrige (`decisoes.md`, ADR 1, "O que continua aberto"). Registrar na ADR 1 e em `sma-dados.md`, seções 2 e 6, e decidir a mitigação (por exemplo, pôr o registro de hh:00:00 exato na hora anterior); fixture `steps-midnight.jsonl`.

## Itens técnicos abertos

### 1. O sync de 59 s

O `syncHistory` da gravação real levou 59,3 s, quase todo nas 64 páginas de sono: cerca de 900 ms por página, sendo ~362 ms do pedido ao ACK do relógio e ~358 ms do nosso ACK ao pedido seguinte (pedidos de `atMs` 1032538 a 1090312). O app Mormaii marca o sync como concluído aos 50 s (`startSyncTimeout`, `src/store/MainStoreContext/WatchInfo/useWatchInfoData/index.ts:142-152` do app), e o envio ao backend se perde. O lado do app (o timer que não reinicia no progresso, com as linhas) e o do host Android estão na seção 1 do `docs/pendencias.md` do Repo 2. Três frentes, nenhuma testada ainda:

- [ ] **Progresso por página.** Hoje o progresso vai só 0, 50 e 100 (`SyncEngine.sync`). Com `onSyncProgress` a cada página, o app pode reiniciar o watchdog em cada progresso. Fecha: nenhum sync é dado como concluído enquanto chegam páginas. Registrar em `arquitetura.md`, seção 6, e no KDoc de `WatchSessionListener.onSyncProgress`.
- [ ] **`requestConnectionPriority(HIGH)` durante o sync.** A própria SDK SMA pede prioridade alta de conexão nas transferências grandes (`requestHighSpeedMode`, `SmaManager.java:1458`, que vira `requestConnectionPriority`, `SmaMessenger.java:163`; usado em `:485`, `:1131` e `:1191`, no XMODEM, no envio de arquivo e no stream), mas nunca na leitura de histórico, e isso não foi testado aqui. Pedir no host Android ao começar o sync e voltar ao normal no fim deve encurtar o intervalo de conexão (o log sugere ~180 ms depois do login, sem HCI snoop que confirme). Fecha: duração por página medida com e sem, no mesmo relógio. Registrar numa ADR nova em `decisoes.md` e na seção 8 de `protocolo-sma.md`.
- [ ] **Tipo de escrita do ACK.** O driver escreve tudo com `WITH_RESPONSE` (`SmaDriver.writeControl`), e a recepção só entrega a página depois que a escrita do ACK é confirmada: é a espera de ~358 ms por página. Duas saídas: escrever o ACK com `WITHOUT_RESPONSE`, se a `6e400002` aceitar (as propriedades dela ainda não foram registradas), ou não bloquear a entrega na confirmação da escrita do ACK (estimativa de 900 para 550 ms por página, uns 35 s nas 64 páginas). A segunda mexe na ordem da ADR 2 e precisa do H1. Fecha: duração por página e nenhuma página perdida. Registrar nas ADRs 2 e 5 e na seção 1 de `protocolo-sma.md` (tipo de escrita).
- Não parar a paginação em páginas antigas: o relógio entrega da mais antiga para a mais nova, então isso nunca chegaria às noites recentes.
- Sem histórico de sono no relógio, o Sync completo levou 1,2 s (log, ts 1793320666818 a 1793320668031, gravação de 2026-10-29 21:37). Os 59 s aparecem quando há muitas páginas antigas para ler, como no primeiro sync de um relógio usado ou depois de várias noites sem sync.

### 2. Risco do `HOURLY_SUM` (resolvido)

- [x] **Resolvido em 2026-10-29 pela troca do perfil do fitGPS para `DAY_CUMULATIVE`** (`decisoes.md`, ADR 1). O H3 mostrou que o `mode 0` é o acumulado do dia: o registro ao vivo do `05/42` da noite (`atMs` 29133 e 38440 de `pair-commands-sync-bt-reconnect-disconnect-reconnect.jsonl`, 21:37:37 e 21:37:47) traz `mode 0` com 56 passos, o mesmo valor do último registro da manhã (08:31:22, `atMs` 1032178 de `pair-commands-bt-reconnect-sync.jsonl`), e o usuário confirmou que o relógio ficou parado o dia todo e que a tela mostrava 56 passos no dia. Com as duas gravações no mesmo histórico, `HOURLY_SUM` daria 56 + 56 = 112 para o dia; `DAY_CUMULATIVE` dá 56 (`RealFixturesStepsTest`, nos quatro targets).
  - **Achado sobre a produção**: os dois Cores de produção usam a mesma soma (`SMAService.java:437-448, 859-866`; `SMAService.m:1277-1288, 334-342`). Ela não era um indício de que o valor fosse por hora: soma instantâneos horários de um acumulado do dia, e com registros em N horas o total chega a até N vezes o real (N quando todos os passos vêm antes da primeira hora com registro, como em 2026-10-29; cerca de (N+1)/2 com os passos espalhados por igual entre as horas com registro). Os passos do fitGPS que o app de produção manda ao backend provavelmente estão inflados (`docs/pendencias.md` do Repo 2, seção 5; conferência no backend no bloco 3).

### 3. ADR 16 (deduplicação pelo instante)

- [ ] A chave da mescla de passos é o instante convertido com o fuso do telefone. Se o fuso mudar entre dois syncs e o relógio reenviar registros já lidos (H1 falsa), a mesma hora vira duas amostras: com `HOURLY_SUM` ela contaria duas vezes; com `DAY_CUMULATIVE`, o padrão do fitGPS, não soma, mas pode cair no dia vizinho. Gatilho para reverter: uma fixture que mostre registros reenviados depois do ACK, como a do T6. Registrar na ADR 16.
- [ ] Com `DAY_CUMULATIVE`, o dia de cada amostra é calculado no fuso do telefone na hora da consulta. Com o telefone indo para leste depois da leitura, a última hora de um dia ainda não confirmado, com o acumulado do dia inteiro, cai no dia seguinte e vira o maior valor dele (com `HOURLY_SUM`, só os passos daquela hora mudariam de dia). Fecha com o T6; corrigir exige guardar o dia do relógio na amostra, o que muda o contrato do `SampleStore` e o host. Registrar na ADR 1 ("O que continua aberto").
- [ ] A janela da mescla descarta amostras de amanhã com mais de 3 h à frente de agora. Com o relógio num fuso mais de 3 h à frente do telefone (viagem internacional, sync antes do acerto de hora da conexão), os passos mais novos perto da meia-noite saem e, se H2 valer, se perdem; até 3 h (todos os fusos do Brasil) eles ficam. Fecha com o T6; se o caso internacional importar, aumentar a folga ou guardar as amostras do futuro até o sync seguinte. Registrar na emenda da ADR 16.

### 4. Filtro de plausibilidade do sono

- [ ] Um buffer de artefato dentro da janela de 30 dias publicaria uma sessão de cerca de 12 h de DEEP começando à tarde. Decidir o filtro (duração máxima, horário de início, START repetido) só depois das noites T3 e T4. Registrar em `sma-dados.md`, seção 5, e numa ADR nova (ou na ADR 9).

### 5. Teste do gatilho do H1 (pular um ACK)

- [ ] Na gravação real todo ACK saiu 0 a 5 ms depois do último fragmento, então não dá para separar "o cursor avança com o nosso ACK" de "avança com o pedido seguinte". Com o botão de depuração do app de exemplo, pular o ACK de uma página `05/42` e repetir o `05/41`: se o relógio devolver a mesma página, o cursor avança no ACK (H1 confirmada e a ordem da ADR 2 importa); se devolver a seguinte, avança no pedido, e a página sem ACK se perdeu (o que reforça a ADR 3, sem retentativa de GET). Registrar na linha H1 da seção 8 de `protocolo-sma.md` e nas ADRs 2 e 3.

### 6. Logger que lança fora da `SessionEngine` e do `HistoryStore`

- [ ] O `SessionDriverLink` (`frame` em `deliver` e `write`, e o WARN do `describe`), o `SyncEngine` e o `SmaDriver` (`log`) chamam o `WatchLogger` direto, sem o `try/catch` que a `SessionEngine` e o `HistoryStore` têm. O contrato diz que o logger não lança, e os hosts atuais (o `RingLogger` do Android) não lançam; mas um logger que lance faz a operação em andamento falhar: no RX, `deliver` lança antes de entregar, e a notificação se perde (o despacho a registra como `Session/dispatch`); no TX, o comando falha. Decidir se passam pelo mesmo `log` seguro (e se o `frame` que falhou ainda entrega o pacote). Fecha: um teste com um logger que lança no `frame` reproduzindo a gravação sintética até `READY`. Registrar no KDoc de `WatchLogger`, no `log/README.md`, na seção 4 de `arquitetura.md` e na emenda da ADR 19.

## iOS

### O que as etapas 1 e 2 provaram (2026-10-29, sem relógio)

- **Ponte de bytes** (`protocol/src/iosMain/.../ios/`, `arquitetura.md`, seção 11; ADR 19). O host Swift converte em uma chamada: `value.toData()` no `Transport.write` e no `WatchLogger.frame`, `session.events.onNotification(characteristic:data:)` e `session.events.onReadCompleted(requestId:data:status:)` com o `Data` do `CoreBluetooth`, `ByteBridgeKt.toByteArray(data)` para o resto. Toda conversão copia; vazio, os 256 valores de byte, 64 KiB, cópia nos dois sentidos e `subdataWithRange` têm teste no simulador (`ByteBridgeTest`), e a gravação sintética reproduz com os RX entregues como `NSData`.
- **O Swift compila contra o framework**: o `checkSwiftInterop` (parte do `check`) faz `swiftc -typecheck` de um host mínimo que implementa `Transport`, `WatchLogger`, `SampleStore`, `WatchSessionListener` e `WatchClock` e chama toda a API pública da `WatchSession` (hoje em `bridge/swift-interop/`, contra o framework guarda-chuva do `:bridge`, ADR 20). Só é conferido o que esse arquivo usa.
- **O protocolo roda no Kotlin/Native com a gravação real**: `iosSimulatorArm64Test` imprime `REPLAY real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl: 850 packets, TX matched 167/167, final state READY` (`fixtures.md`, seção 4.1).
- **O dispatcher de produção funciona no iOS com uma fila GCD de host**: o `RealtimeReplayTest` reproduz a mesma gravação em tempo real com `WatchSession(transport, logger, store, clock)` e os eventos vindos de um `dispatch_queue_create` serial, como a fila de delegate do `CBCentralManager`: `TX matched 167/167, final state READY` em cerca de 6,1 s, o mesmo histórico do replay virtual, nenhum callback na fila do host, nada depois do `close`. Que o dispatcher é serial, o replay não distingue; quem confere é o `SessionThreadingTest`, nos quatro targets. A thread da sessão é lógica (no iOS, o `Dispatchers.Default` roda no pool do GCD), e o host não deve depender da identidade da thread. O `WatchLogger` do host tem de ser thread-safe: as consultas síncronas o chamam na thread de quem chama (`arquitetura.md`, seção 4).
- **MTU**: o host iOS responde `requestMtu` com `maximumWriteValueLength(for: .withoutResponse) + 3`, nunca com o valor de `.withResponse`; `maxFragment` continua `null` (ADR 5, emenda).

- **Contrato JS comum** (`:bridge`, ADR 20): os payloads, os estados, o `deletedSavedData`, a fila de eventos e os pedidos em andamento do host Android passaram para código comum, com os testes do host rodando também no simulador; as datas são idênticas ao `java.time` antigo (teste diferencial); o `FoundationConverter` entrega `CFBoolean` de verdade (conferido pelo Core Foundation no simulador). O framework do host iOS passou a ser o guarda-chuva do `:bridge`, com os mesmos nomes.
- **Controlador comum** (`CoreBridge`, ADR 21; `arquitetura.md`, seção 13): o que cada método JS faz e o lado `WatchSessionListener` do host Android antigo passaram para o `:bridge`, com a plataforma atrás de `CoreHost`, `CoreEventSink` e `CoreCompletion`. Os testes de contrato rodam no simulador sobre uma `WatchSession` de verdade, e a gravação real passa pelo controlador, método JS por método JS, igual ao golden (`bridge/golden/`) nos quatro targets. O Swift implementa as três interfaces e chama todo o `CoreBridge` (`checkSwiftInterop`), e um TurboModule mínimo em Objective-C++ chama cada seletor como o shim vai chamar (`checkObjcInterop`). O host Android do Repo 2 já é uma casca sobre o `CoreBridge` (emenda da ADR 21): o `CoreHost` tem uma implementação em uso (`AndroidCoreHost`), e o iOS implementa a mesma interface.

Nada disso testou o `CoreBluetooth`, um iPhone ou o relógio.

### O que falta, em ordem

Os itens do host estão detalhados no [`docs/ios-pendente.md` do Repo 2](../../react-native-technos-core-next/docs/ios-pendente.md); aqui fica o que cada um fecha e onde registrar.

- [ ] **Pod e shim ObjC++.** O XCFramework (`bridge/build/XCFrameworks/release/`, gerado pelo `:bridge`) copiado para `ios/Frameworks/` do Repo 2 e o `RCTNativeCoreNext.mm` conformando ao `NativeCoreNextSpec`. O shim não tem regra: cada método do spec embrulha o par resolve/reject numa `TWPCoreCompletion` e chama o seletor de mesmo nome do `TWPCoreBridge` (o `initSMASDK:reject:` chama `doInitSMASDKCompletion:`), o módulo é o `TWPCoreEventSink` (`canEmit` = callback de eventos instalado; `emitName:payload:` converte com o `TWPFoundationConverter` e chama o `emitOnX:`), liga-se com `attachSink:` na primeira chamada e se desliga com `invalidateSink:`. A forma exata está em `bridge/objc-interop/MinimalTurboModule.mm`, que o `check` já compila. O `TWPCoreBridge` do processo vem do Swift (`CoreNext-Swift.h` importável num pod estático). Fecha: `pod install` e build do `example/ios`. Registrar no `ios/README.md` do Repo 2.
- [ ] **Ponte Swift e persistência.** `CoreGraph.swift`, um por processo: monta `WatchSession(transport:logger:store:)`, liga o transporte e cria `CoreBridge(session:logger:host:)`; implementa `CoreHost` (autorização e estado do `CBCentralManager`, endereço como UUID, scan, `beginBackgroundTask` no sync, pareamento em `UserDefaults`, cabeçalho da gravação, log em memória com `os_log`, `exportLogs` que `throws`); chama os ganchos (`onHostResume` ao voltar ao primeiro plano, `onBluetoothTurnedOff`/`onBluetoothTurnedOn`, `onScannedDevice`, `onScanFailed`, `onLogEntry`). Mais um `SampleStore` em JSON no Application Support. A regra de cada método JS já está no `CoreBridge` e testada no simulador; os testes do host iOS cobrem só essas peças de plataforma (o `SampleStore`, as preferências, o logger). Fecha: esses testes e um reload do JS sem perder o histórico. Registrar no `docs/ios-pendente.md` do Repo 2.
- [ ] **Export do log no iOS.** Gravar `frames-*.jsonl` com `FrameJsonl` a partir do `WatchLogger.frame` (o `value` vai direto, sem conversão) e tirar o arquivo do iPhone (Files ou `UIActivityViewController`). O `address` no iOS é o UUID do periférico, não o MAC, mas o log nativo continua fora do git. Fecha: uma gravação com `platform` `ios` em `fixtures/real/fitgps/<fw>/` que o `RealFixturesReplayTest` reproduz. Registrar em `fixtures.md`, seção 5.
- [ ] **`CentralTransport` sobre `CoreBluetooth`.** Fila de operações como a `GattQueue` do Android, os eventos entregues da fila de delegate (sem `dispatch` extra: a sessão já enfileira), um `onDisconnected` por `disconnect()` com link aberto ou pendente, o contrato B no `.poweredOff`, `requestMtu` pela regra da ADR 5, escrita `.withResponse`, scan sem filtro de serviço (o fitGPS não anuncia o NUS). Fecha: pareamento, sync e Bluetooth desligado e religado num iPhone com o fitGPS, com a gravação reproduzindo `TX matched N/N`. Registrar em `arquitetura.md`, seção 5, se algum comportamento divergir, e nas ADRs 5, 12 e 18.
- [ ] **H8 com uma gravação iOS.** O bloco 5 da sessão com o relógio, agora com este core também no iPhone: o LOGIN alternativo reencontra um relógio vinculado pelo app de produção? Fecha **H8**. Registrar na linha H8 da seção 8 de `protocolo-sma.md`, no "Risco aberto" da ADR 11 e na fixture `h8-ios-bound-reconnect.jsonl`.
- [ ] **Segundo plano e restauração.** `CoreNext.bootstrap(launchOptions:)` criando o `CBCentralManager` com o identificador de restauração antes do JS, `willRestoreState`, modo `bluetooth-central`. **Nota de desenho:** a espera crescente da reconexão (2 s, 4 s… até 60 s, `SessionEngine.scheduleReconnect`, `SessionEngine.kt:653-666`, depois de uma tentativa que falhou) é um `delay` de corrotina, e nesse intervalo não há `connect` pendente no host. Com o app suspenso no iOS o processo não roda, e sem um `connect` pendente o sistema não tem por que acordá-lo: a reconexão pararia até o usuário abrir o app. No iOS, a espera deveria ser o próprio `CBCentralManager.connect` pendente (que o sistema mantém com o app suspenso e que acorda o app quando o relógio volta), ou seja, chamar `connect(autoReconnect = true)` na hora em vez de esperar. A queda de um link pronto já reconecta na hora e não tem esse problema. Isso muda a sessão (por exemplo, um ajuste por plataforma no perfil ou no construtor) e fica para quando o host existir. Fecha: relógio fora de alcance com o app em segundo plano reconectando sozinho, e a restauração testada com pressão de memória (matar o app pelo app switcher desliga a restauração). Registrar numa ADR nova, na ADR 11 e em `arquitetura.md`, seção 5 (`RECONNECTING`).

