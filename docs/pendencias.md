# Pendências: validação no relógio e itens técnicos abertos

A primeira gravação real (`fixtures/real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl`, fitGPS com firmware 0.2.1 e um telefone Android, 2026-10-29) já reproduz no replay e decidiu parte das hipóteses (`protocolo-sma.md`, seção 8). O que ela não cobriu ficou para uma sessão com o relógio de cerca de 90 min e duas noites, que foi despriorizada: isto é uma prova de conceito, e o caminho de passos, que é o critério de aceite, já funcionou de ponta a ponta. Esta lista guarda o roteiro para quando a sessão acontecer.

**O sono não é prioridade**: o app de produção nunca leu sono do fitGPS (nenhum Core pede `05/45`, `SMAService.java:561, 573, 591`; `SMAService.m:1077-1080`), então não há o que regredir; os itens de sono ficam no fim.

Cada item diz a hipótese ou o critério que fecha e onde registrar o resultado. Ao fechar um item, marque a caixa, cite a fixture nova pelo `atMs` (ou o log pelo `ts`) e atualize os documentos indicados na mesma alteração. Os critérios A1 a A8 e T1 a T7 são os do plano da PoC.

O caminho do iOS (o que as etapas sem relógio já provaram e o que falta até um host iOS) está na seção [iOS](#ios), no fim.

A validação do Condor Reebok (família CRP, fase 1) no Android e no iPhone, o que ficou para as fases 2 a 4 e os bugs do Condor de produção que a fase 0 achou estão na seção [Condor Reebok (CRP)](#condor-reebok-crp).

As pendências do host Android e da integração com o app Mormaii (o watchdog de 50 s do sync do lado do app, polling de passos ao vivo, `FileProvider`, `deviceId`, eventos sem equivalente) estão no [`docs/pendencias.md` do Repo 2](../../react-native-technos-core-next/docs/pendencias.md), que aponta para cá para o roteiro com o relógio.

## Preparação

- [ ] Build do app de exemplo (Repo 2) com: o replay corrigido deste repositório (já está aqui: uma gravação copiada para `fixtures/real/` roda no `./gradlew :protocol:jvmTest`); a linha `SmaDriver/frame` (já está no `SmaDriver`); `recordedAt` com milissegundos (já está no `FrameRecorder` do Repo 2); o log das propriedades da characteristic `6e400002` (o host iOS já loga: no iPhone, em 2026-10-30, ela declarou `write` e `writeWithoutResponse`, log de texto, ts 1793363361204; no host Android ainda falta, seção 6 do `docs/pendencias.md` de lá); opcional, um botão de depuração "pular o próximo ACK" para o teste do H1.
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
  - Indício de 2026-10-30 (iPhone, `ios-pair-info-time-steps-sleep-sync.jsonl`): o Sync leu uma página de 9 registros, todos da noite anterior (`atMs` 50729), nenhum dos antigos. Combina com o H2 para o sono, mas não fecha: houve um sync só, e não se sabe se a página voltaria num segundo.
- [ ] Opcional, com o botão de depuração: pular o ACK de uma página `05/42` e repetir o `05/41`. É o teste do H1 (item técnico 5).

### 2. Disconnect, Forget e novo pareamento (A7, H7, A3 a A5, A8)

- [x] **Disconnect** e esperar 60 s sem reconexão; **Reconnect last** (só `03/03`, sem BIND).
  - Feito em 2026-10-29: Disconnect (log, ts 1793320739940), 71 s sem reconexão e Reconnect last só com LOGIN (`atMs` 187070 de `pair-commands-sync-bt-reconnect-disconnect-reconnect.jsonl`).
- [ ] **Forget**: `03/05 [00]` com ACK, estado `DISCONNECTED`, store vazio, "Último pareado: nenhum".
  - Fecha **A7**. Registrar em `fixtures.md`, seção 5 (fixture `disconnect-reconnect-forget.jsonl`).
  - Em 2026-10-29 o Forget e o novo pareamento funcionaram, segundo o usuário, mas não estão na gravação da noite.
- [ ] Scan e pair **olhando a tela do relógio no BIND**: ele pede toque? Quanto tempo do ACK do BIND até o `03/02`?
  - Fecha **H7**. Sem pedido de toque e com o `03/02` em poucos segundos, o `bindTimeoutMs` de 60 s pode cair (`decisoes.md`, ADR 4). Registrar na linha H7 da seção 8 de `protocolo-sma.md` e na ADR 4.
  - Na gravação de 2026-10-29 21:37, o `03/02` chegou 1,9 s depois do BIND (`atMs` 0 a 1899), contra 2,2 s de manhã. No iPhone, em 2026-10-30, 3,6 s depois do ACK do BIND (`atMs` 175 a 3775 de `ios-pair-info-time-steps-sleep-sync.jsonl`). Falta confirmar se o relógio pediu toque.
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
  - A primeira página real recente (2026-10-30) veio de um relógio parado, não de uma noite com ele no pulso (T4, abaixo): o formato e as regras da SDK ficaram conferidos (`sma-dados.md`, seção 6), o conteúdo não.
  - Registrar em: linha H4 da seção 8 de `protocolo-sma.md`; `sma-dados.md`, seções 5 e 6; ADR 9; fixture `sleep-night.jsonl`.
- [x] **Relógio parado na mesa (T4)**: relógio sobre a mesa desde as 13:00, Sync de manhã.
  - Confirma se o padrão da gravação de 2026-10-29 (um START repetido com ENDs de 2 em 2 min) é artefato de relógio parado, o que decide o filtro de plausibilidade (item técnico 4). Registrar em `sma-dados.md`, seção 6.
  - Feito sem roteiro em 2026-10-30: o relógio ficou parado desde a manhã de 29/10 (56 passos o dia todo, ADR 1) até o pareamento no iPhone às 09:29 de 30/10, e o usuário não dormiu com ele. **Não é o padrão antigo**: veio uma noite só, com START às 21:58:00 (e não à tarde; o Sync das 21:37 de 29/10 tinha voltado vazio), estágios de 00:09:38 a 09:30:05 e nenhum END; o analisador e a SDK dão 692 min, 632 de profundo (`ios-pair-info-time-steps-sleep-sync.jsonl`, `atMs` 50729; `sma-dados.md`, seção 6). A origem dos 1262 registros antigos continua sem explicação (pergunta da Preparação).

Ficam para depois: a virada da meia-noite (T5) e os estados de atividade (T7). A T5 também decide quando e como o acumulado do dia zera, com uma conferência explícita:

- [ ] **T5: existe registro em 00:00:00 (ou registro ao vivo logo depois da meia-noite) com o total de ontem?** Refresh steps perto de 23:55 (o fechamento de ontem), nenhuma leitura até de manhã, Sync de manhã e Export; numa segunda noite, Refresh steps às 00:01. Procurar, nos registros `mode 0` da hora 00, um valor igual ao fechamento de ontem, e anotar quando a tela zera. Se existir, com `DAY_CUMULATIVE` o dia de hoje mostra o total de ontem até a contagem de hoje passá-lo, e só outro registro da hora 00 o corrige (`decisoes.md`, ADR 1, "O que continua aberto"). Registrar na ADR 1 e em `sma-dados.md`, seções 2 e 6, e decidir a mitigação (por exemplo, pôr o registro de hh:00:00 exato na hora anterior); fixture `steps-midnight.jsonl`.
  - Indício de 2026-10-30, com o relógio parado (`ios-pair-info-time-steps-sleep-sync.jsonl`, `atMs` 46437): o dia fechou com `mode 16` às 23:59:00 com 56 (o total de 29/10), o de 00:00:00 também é `mode 16`, com 0, e o ao vivo de 09:30:07 tem 0. O acumulado zera à meia-noite e o total de ontem não reaparece no dia novo; os dois `mode 16` são ignorados (`sma-dados.md`, seção 6). Essa leitura não prova que nenhum `mode 0` foi gravado à noite (o seq do relógio pulou de 100 para 104 entre as gravações, uma conversa não gravada no meio). Como o relógio gravou sozinho o instantâneo `mode 0` de 08:30:00 depois de passos (gravação da manhã), falta sobretudo o caso dos passos depois do último instantâneo do dia: andar depois das 23:30, sem sync até de manhã, e ver se algum `mode 0` traz o total final; se ele só existir no `mode 16` de 23:59:00, esses passos se perdem para o dia, e a mitigação é aceitar o `mode 16` de 23:59:00 como amostra do dia que fecha. Registrar também na ADR 1 ("O que continua aberto").

## Life Ultra GPS (iDO)

O driver iDO (`docs/protocolo-ido.md`, ADR 22) foi escrito a partir do log de bytes da SDK de produção e roda no replay da fixture reconstruída (`fixtures/real/lifeultragps/1.1.1/pair-commands.jsonl`) e, desde 2026-10-30, também no relógio de verdade (abaixo). Antes de testar, desfaça o pareamento no app Mormaii de produção ou force a parada dele, para os dois apps não disputarem o relógio. Grave tudo pelo app de exemplo (Export) e copie o `frames-*.jsonl` mais recente para `fixtures/real/lifeultragps/1.1.1/<cenário>.jsonl`: ele roda no `IdoRealFixturesReplayTest` (uma reconexão no meio da gravação ainda não é deduzida; ver `IdoReplayScenario`).

Primeiro teste no relógio em 2026-10-30, com o driver próprio e sem SDK de fornecedor: fixture `real/lifeultragps/1.1.1/pair-info-time-steps-sync.jsonl` (`fixtures.md`, seção 5), `TX matched 12/12`. O usuário conferiu os valores na tela do relógio. No mesmo dia, no iPhone, depois de esquecer o vínculo antigo nos Ajustes (ADR 23): `real/lifeultragps/1.1.1/ios-pair-info-time-steps-sync.jsonl`, `TX matched 12/12`, com a tabela GATT e as propriedades das characteristics em `protocolo-ido.md`, seção 1.1.

- [x] **Scan**: o relógio aparece como `Life Ultra GPS`, com `profileId` `lifeultragps` (log, ts 1793329978799). Se não aparecer, anotar o nome e os serviços anunciados do log `BleScanner/onScanResult`.
- [x] **Pareamento com confirmação**: fase `BIND`, a tela do relógio pede a confirmação, o toque em "sim" leva a `READY`. Conferir no log `Session/configure` os dois CCCD (`0af7` e depois `0af2`), "MTU not requested" e nenhuma falha de escrita sem resposta (`AndroidGattTransport/write`). Registrar em: seção 4 de `protocolo-ido.md` e esta caixa.
  - Feito em 2026-10-30: os dois CCCD com status 0, MTU não pedido, um desafio novo (`atMs` 171) respondido pelo XOR (`atMs` 181) e a confirmação na tela 1,5 s depois (`atMs` 1672). Prova que a resposta ao desafio vale para qualquer desafio, não só para o do log de produção.
  - No iPhone, no mesmo dia: outro desafio (`atMs` 118), a confirmação 1,4 s depois (`atMs` 1528), os dois CCCD com status 0 e o MTU não pedido, embora o iOS tenha oferecido 515 (`protocolo-ido.md`, seção 1.1).
- [ ] **Falha 133 na primeira conexão**: em 2026-10-30 o primeiro `pair` falhou na conexão GATT com status 133 antes de qualquer pacote (log, ts 1793329992384) e o segundo, 11 s depois, conectou. Candidato: uma nova tentativa rápida no 133 durante o primeiro pareamento, como a Garmin faz (relatório Garmin, motor BLE). Registrar na ADR 13 ou numa ADR nova.
- [ ] **Recusa**: tocar em "não" dá `HANDSHAKE_FAILED`; um `pair` novo (link novo) pede a confirmação de novo.
- [ ] **Reconexão**: Bluetooth desligado e religado, e o app fechado e aberto (Reconnect last): fase `LOGIN`, só `02/01` e o acerto de hora, sem tela de confirmação no relógio (seção 4.4).
- [x] **Bateria**: o valor do `getBatteryStatus` igual ao da tela do relógio (no log de produção, 14 %; no teste de 2026-10-30, 18 %; no iPhone, nove horas depois, o relógio respondeu 12 %, sem conferência com a tela).
- [x] **Firmware**: `getFirmwareInfo` dá `1.1.1` (o Core atual mostraria `1.01.01`). Conferido em 2026-10-30 (`atMs` 24980).
- [x] **Hora**: depois do pareamento e de um `setTime`, a hora e o dia da semana na tela do relógio iguais aos do telefone. Em 2026-10-30 o acerto mandou sexta como `04` (`atMs` 2696 e 57487) e o relógio mostrou o dia certo. O dia da semana decide a convenção (segunda = 0, a da SDK); o relógio aceitou também a de domingo = 0 do Core de produção (`protocolo-ido.md`, seção 3.4).
- [x] **Passos**: Refresh steps e Sync dão os passos e a distância da tela do relógio; andar um pouco e repetir. Em 2026-10-30: 51 passos, 54 m e 6 kcal totais (`atMs` 60891); o Sync levou 0,2 s. As calorias não batem com a tela, e não é falha: o `02/A0` traz as totais (repouso + atividade, 13 no log de produção) e a tela mostra só as de atividade (0 no mesmo momento; `protocolo-ido.md`, seção 3.3). Anotar os dois valores. No iPhone, nove horas depois, o relógio respondeu 57 passos, 59 m e 7 kcal totais (`atMs` 15328 de `ios-pair-info-time-steps-sync.jsonl`), sem conferência com a tela.
- [ ] **Esquecer**: o `forgetDevice` não escreve nada no relógio (o relógio continua se dizendo vinculado); um `pair` depois pede a confirmação de novo.
- [ ] **Bytes do desvínculo**: capturar o unbind do app de produção (HCI snoop, `fixtures.md`, seção 3.2, ou o log da SDK) e implementar o `IdoDriver.unbind`.
- [ ] **Histórico V3** (passos por hora, sono): fora da PoC; decidir se entra (fluxo de várias etapas com CRC-16, `protocolo-ido.md`, seção 8).
- [x] **Se o relógio não responder ao primeiro comando**: acrescentar as esperas da SDK (1 s antes da descoberta, 100 ms antes dos CCCD) e conferir se o relógio exige vínculo BLE do sistema (SiFli); ADR 22.
  - Não foi preciso: sem as esperas, o relógio respondeu ao primeiro `02/01` no Android e no iPhone (no iPhone, 0,7 s depois do `didConnect` e em 60 ms; `protocolo-ido.md`, seção 1.1). A pergunta do vínculo do sistema continua no item abaixo.
- [ ] **O iPhone mostra o diálogo de pareamento do sistema?** Perguntar a quem testou em 2026-10-30 se, no pareamento que deu certo depois de esquecer o vínculo antigo, o iOS mostrou o pedido de pareamento Bluetooth do sistema (o alerta do iOS que pede para emparelhar o acessório), antes ou depois da confirmação na tela do relógio. Responde se o relógio pede criptografia do link por conta própria: o vínculo do sistema que o iPhone guardava do app de produção (ADR 23) mostra que alguém pediu, o relógio ou a SDK iOS. Se pedir, o iOS cria um vínculo novo a cada pareamento por este core e o `PAIRING_REMOVED` volta toda vez que o relógio for pareado com outro telefone. Registrar em `protocolo-ido.md`, seções 1.1 e 8, e na ADR 23.
- [ ] **Vínculo apagado no Android (o par do `PAIRING_REMOVED`)**: relógio pareado neste core num Android, depois pareado com outro telefone (o iPhone), e de volta ao Android com **Reconnect last** e com um `pair` novo. Só existe se o Android tiver criado um vínculo do sistema (o driver nunca chama `createBond`; olhar a lista de pareados das configurações e o `ACTION_BOND_STATE_CHANGED` no log). Anotar o status que o `BluetoothGatt` entrega (falha de autenticação ou de criptografia, ou queda logo depois de conectar), se a sessão entra em laço de reconexão e se o usuário precisa esquecer o relógio nas configurações (um app não remove o vínculo sem a API oculta `removeBond`). Hoje o host Android repassa o status nativo e a sessão trata como falha comum (ADR 23; `docs/transporte-android.md` do Repo 2). Registrar na ADR 23 e no `docs/transporte-android.md` do Repo 2.

## Condor Reebok (CRP)

O driver CRP (`docs/protocolo-crp.md`, ADRs 25 a 32) foi escrito a partir da SDK Android descompilada (`crpblelib` 2.0.3), do binário iOS e de uma captura HCI do app Condor de produção pareando o P85 num Android (as fixtures de referência, `fixtures.md`, seção 5). Ele roda no replay estrito da fixture sintética (os TX do nosso driver contra os RX e READ reais) e decodifica as três referências, mas ainda não falou com o relógio. Nenhuma gravação real dele existe: `CrpRealFixturesReplayTest` imprime `REPLAY crp: no recordings yet`.

**Antes de testar**: no Android, desfaça o pareamento no app Condor de produção (ou force a parada dele) e esqueça o P85 nas configurações de Bluetooth, para o vínculo clássico ser criado de novo por este core e os dois apps não disputarem o relógio. No iPhone, esqueça o P85 em Ajustes > Bluetooth se o Condor iOS já o pareou. Grave tudo pelo app de exemplo (**Export**, com o link ainda aberto: o `mtu` do cabeçalho decide o `b2` no replay, `fixtures.md`, seção 3.1) e copie o `frames-*.jsonl` mais recente para `fixtures/real/condorreebok/MOY-VS02-2.0.0/<cenário>.jsonl`; nunca o `logs-*`, que tem o MAC. Ele roda no `CrpRealFixturesReplayTest` na execução seguinte.

### Android

- [ ] **Scan**: o relógio aparece como `Condor Reebok` (`name` = appName, `bleName` `P85`, `profileId` `condorreebok`), com o scan sem filtro (o perfil tem prefixo). Se não aparecer, anotar o nome que o `BleScanner/onScanResult` viu. Registrar em `protocolo-crp.md`, seção 2.
- [ ] **Pareamento**: fase `VERSION` e `READY` em cerca de 1 s. Conferir no log `Session/configure` as CCCDs `fee3`, `2a37`, `fee1` e `2a19` (as três últimas sem ser fatais: um WARN ali não derruba nada, mas tem de ser anotado), o MTU 517 e o `CrpDriver/handshake` (`protocol 'MOYOUNG-V2' -> V2, mtu 517, block 244`). Registrar em `protocolo-crp.md`, seções 2 e 5.
- [ ] **Vínculo clássico**: logo depois do `READY`, o diálogo do sistema pede o emparelhamento; aceitar. Se a primeira tentativa falhar (na captura falhou com `0x06`, HCI 15:43:08.248), o diálogo tem de voltar sozinho, uma vez (nova tentativa 1,5 s depois). Depois do aceite: o INFO `CrpDriver/classicState` com `classic=01`, o INFO `CoreBridge/classicBond` com `ready`, e as chamadas e o áudio do relógio ligados nas configurações de Bluetooth sem o app abrir HFP/A2DP. Registrar em `protocolo-crp.md`, seção 10, e na ADR 30.
- [ ] **Recusa do diálogo**: tocar em "Cancelar" dá `onError` `CLASSIC_BOND_FAILED` (`requestId` `-1`, `method` `"classicBond"`), a sessão BLE continua pronta e o diálogo não volta até o próximo `pairDevice`. Registrar na ADR 30.
- [ ] **Bateria**: o `getBatteryStatus` igual ao da tela; um `onBatteryStatus` espontâneo por minuto (o aviso de `2A19`). **No carregador**: anotar o byte cru (o `hex` do `READ` de `2a19` no export) e se o `charging` vem `true`; a regra "> 100 = carregando" é do Core e nunca foi vista no P85. Registrar na ADR 31 e em `protocolo-crp.md`, seção 7.
- [ ] **Firmware**: `getFirmwareInfo` dá `MOY-VS02-2.0.0` e o `bleVersion` é `MOYOUNG-V2`.
- [ ] **Hora**: depois do pareamento e de um `setTime`, a hora, o dia e o fuso na tela do relógio iguais aos do telefone. Registrar em `protocolo-crp.md`, seção 7.
- [ ] **Passos ao vivo**: andar com o app aberto; o `onPedometer` acompanha a tela do relógio, no máximo uma vez a cada 2 s, com a distância e as calorias do relógio (o Condor de produção mostrava zeros durante a atividade). Registrar na ADR 28.
- [ ] **Sync**: `syncDataHistory` e conferir hoje (passos, metros e kcal iguais à tela) e ontem. Anotar se o `33 01` foi respondido (log `CrpDriver/legacyHistory`; sem resposta, `0x33 history disabled for this connection`). Registrar em `protocolo-crp.md`, seções 8 e 13.
- [ ] **Sono, depois de uma noite com o relógio no pulso**: o `32` com registros, a sessão com o `dayKey` de hoje, e os totais contra a tela (o total é leve + profundo + REM, sem o acordado; sem o REM fabricado da SDK). Anotar se `33 03`/`33 04` responderam. Fixture `sleep-night.jsonl`. Registrar em `protocolo-crp.md`, seção 9, e na ADR 29.
- [ ] **Bluetooth desligado e religado**: reconexão sozinha, fase `VERSION`, o `31` 250 ms depois do `READY`, e o relógio reabrindo o clássico com a chave guardada (`classic=01` sem diálogo).
- [ ] **Fora de alcance e de volta**: reconexão sozinha, como acima.
- [ ] **Esquecer**: o `F8` sai (TX `FEEA2005F8` no export), o P85 some das configurações de Bluetooth (`removeBond`) e um `pair` novo pede o diálogo de novo. Anotar o que o relógio mostra depois do `F8`. Registrar em `protocolo-crp.md`, seção 10.
- [ ] **Esquecer em dois casos de borda** (host Android, `ClassicBonder`): (1) com o Bluetooth desligado, o vínculo fica pendente e sai quando o Bluetooth liga (o relógio não pode reabrir HFP/A2DP com a chave guardada); (2) com o diálogo do pareamento ainda aberto, o `cancelBondProcess()` fecha o diálogo, ou, se o usuário aceitar mesmo assim, o vínculo é removido assim que aparece, e nenhum `onError` `CLASSIC_BOND_FAILED` sai. Anotar no log `ClassicBonder/remove` o que a reflexão devolveu. Registrar no `docs/transporte-android.md` do Repo 2.

### iPhone

- [ ] **Pareamento**: anotar se o iOS mostra algum pedido de pareamento do sistema (do LE ou das chamadas) e quando; o WARN `CoreBridge/classicBond` com a dica "iOS apps cannot create a classic Bluetooth bond; pair calls in Settings > Bluetooth", sem `onError`. Anotar se, com o transport bridging, as chamadas do relógio aparecem sozinhas em Ajustes ou se é preciso parear lá. Registrar em `protocolo-crp.md`, seções 10 e 11, e na ADR 30.
- [ ] **Os mesmos comandos** do Android (bateria, firmware, hora, passos ao vivo, sync, sono, Bluetooth desligado, esquecer), com o `mtu` do cabeçalho do export (o transporte iOS volta a 23 a cada tentativa) conferido antes de versionar.

### O que ficou para depois

- **Fase 2**: o reset de fábrica `51 02` como método explícito da tela Dispositivo (o Core de produção o manda em todo "Desconectar", `core@v4.3.57:android/src/main/java/com/crp/bluetooth/connectionmanager/CRPBleConnectHelper.java:400-429`); o formato 12/24 h (`17 01`/`17 00`, `pair-sync@144792` e `@162416`); o perfil do usuário `0x12`; as metas (`B9`).
- **Fase 3**: atividades (`B2`, com o registro final da caminhada da captura, 77 passos e 4 kcal), frequência cardíaca (`35`, `B6 02`, `2A37`) e estresse (`B9 11`).
- **Fase 4**: `deviceId` no iOS (o Condor de produção usa o MAC do anúncio, `CRPDiscovery.mac`, e não o UUID do periférico; a SDK iOS lê `FEC9`/`FEE4`); a distância e as calorias trocadas no Android de produção (abaixo) e o que o backend espera receber.
- **Sondas no relógio**: `0x33` e `B6` no P85 (a primeira gravação real responde); o vínculo LE no iPhone; a regra > 100 no carregador; o significado de `F9 02 xx`.
- [x] **Máscara do replay** (2026-10-31): o `CrpTxMasker` (os segundos do `31`) passou a ser exercitado pelo `CrpSyntheticReplayTest.shiftedReplayClockIsMaskedOnlyInTheTimeFrame`, que reproduz a sintética com o relógio 7 s depois do `recordedAt` (casa com a máscara e falha só no `31` sem ela). A primeira gravação real o exercita de novo.
- [x] **Frames `0x42` nas referências** (2026-10-31): a previsão do tempo (código do clima e temperaturas mínima e máxima, `sdk:f/a2.java:23-45`) saiu das três fixtures de referência na revisão de privacidade; o `0x42` entrou no padrão do `--redact-cmds` (ADR 32; `fixtures.md`, seção 5).
- **Vínculo clássico, comportamento aceito**: só o `READY` que fecha um handshake pergunta `hasClassicBond` (o do fim de um sync, não); com o vínculo já feito, um INFO por conexão. A recusa, o pedido em andamento e os pedidos abandonados por um esquecer vivem só na memória, então um processo novo pede de novo no primeiro `READY` (ADR 30).

### Bugs do Condor de produção (registro; esta biblioteca não os copia)

Achados pela fase 0 e pela captura, para quem mantiver o app Condor atual (`core@v4.3.57`) ou comparar números com ele:

- **Distância e calorias trocadas no `onHistoryPedometer` do Android**: `item.putDouble("distance", calories)` e `item.putInt("calorie", distance)` (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/CRPService.java:391-392`; ainda no HEAD). O iOS está certo. O `EventMapper` daqui manda metros em `distance` e kcal em `calorie`.
- **Distância e calorias por meia hora zeradas**: o Core pede `5D`/`5C` e grava as meias horas, mas o P85 nunca responde (`pair-sync@56403`, `@56631`, `@58957`, `@59184`).
- **`B6 01` ignorado**: o `onHistorySleepChange` do Core Android é vazio; um firmware que só respondesse `B6 01` nunca teria sono salvo (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/CRPService.java:1713-1741`).
- **Fila travada no `0x32`**: sem resposta ao `0x32`, a fila da SDK fica parada até o watchdog, que só é avaliado quando outro comando entra (`sdk:o/b.java:18-38`; `sdk:p/c.java:33-43`), e o `B6 01 00` fica preso atrás.
- **Zeros durante a atividade**: o Core Android só aceita a primeira notificação de `FEE1` depois de cada sync (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/CRPService.java:1418-1452`); na captura, o Condor mostrou zeros durante a caminhada (`walk@106148` a `@136148`).
- **Timeout de 12 s durante o diálogo do vínculo**: o timeout do estado "conectando" não é cancelado enquanto o usuário decide; o GATT é fechado, o handshake se repete e um `BOND_NONE` faz o diálogo voltar em laço (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/connectionmanager/CRPBleConnectHelper.java:187-188, 513-518, 788-795`).
- **`markYesterdayDataAsSynced`** é gravado no primeiro sinal de "ontem" de qualquer tipo (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/CRPService.java:1279-1281`).
- **`safeSum`** descarta o último bucket quando a lista tem tamanho ímpar (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/steps/CRPStepsAndRelatedListenerManager.java:117-121`).
- **`> 20` contra `≥ 20`**: o `onSleepChange` do Core usa hora `> 20`, estrito, e a SDK usa `≥ 20` (`core@v4.3.57:android/src/main/java/com/crp/bluetooth/CRPService.java:1721`; `sdk:j/o0.java:169-173`).

## Itens técnicos abertos

### 1. O sync de 59 s

O `syncHistory` da gravação real levou 59,3 s, quase todo nas 64 páginas de sono: cerca de 900 ms por página, sendo ~362 ms do pedido ao ACK do relógio e ~358 ms do nosso ACK ao pedido seguinte (pedidos de `atMs` 1032538 a 1090312). O app Mormaii marca o sync como concluído aos 50 s (`startSyncTimeout`, `src/store/MainStoreContext/WatchInfo/useWatchInfoData/index.ts:142-152` do app), e o envio ao backend se perde. O lado do app (o timer que não reinicia no progresso, com as linhas) e o do host Android estão na seção 1 do `docs/pendencias.md` do Repo 2. Três frentes, nenhuma testada ainda:

- [ ] **Progresso por página.** Hoje o progresso vai só 0, 50 e 100 (`SyncEngine.sync`). Com `onSyncProgress` a cada página, o app pode reiniciar o watchdog em cada progresso. Fecha: nenhum sync é dado como concluído enquanto chegam páginas. Registrar em `arquitetura.md`, seção 6, e no KDoc de `WatchSessionListener.onSyncProgress`.
- [ ] **`requestConnectionPriority(HIGH)` durante o sync.** A própria SDK SMA pede prioridade alta de conexão nas transferências grandes (`requestHighSpeedMode`, `SmaManager.java:1458`, que vira `requestConnectionPriority`, `SmaMessenger.java:163`; usado em `:485`, `:1131` e `:1191`, no XMODEM, no envio de arquivo e no stream), mas nunca na leitura de histórico, e isso não foi testado aqui. Pedir no host Android ao começar o sync e voltar ao normal no fim deve encurtar o intervalo de conexão (o log sugere ~180 ms depois do login, sem HCI snoop que confirme). Fecha: duração por página medida com e sem, no mesmo relógio. Registrar numa ADR nova em `decisoes.md` e na seção 8 de `protocolo-sma.md`.
- [ ] **Tipo de escrita do ACK.** O driver escreve tudo com `WITH_RESPONSE` (`SmaDriver.writeControl`), e a recepção só entrega a página depois que a escrita do ACK é confirmada: é a espera de ~358 ms por página. Duas saídas: escrever o ACK com `WITHOUT_RESPONSE`, que a `6e400002` aceita (no iPhone, em 2026-10-30, ela declarou `write` e `writeWithoutResponse`; log de texto, ts 1793363361204), ou não bloquear a entrega na confirmação da escrita do ACK (estimativa de 900 para 550 ms por página, uns 35 s nas 64 páginas). A segunda mexe na ordem da ADR 2 e precisa do H1. Fecha: duração por página e nenhuma página perdida. Registrar nas ADRs 2 e 5 e na seção 1 de `protocolo-sma.md` (tipo de escrita).
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
  - A T4 veio em 2026-10-30 (acima): o relógio parado gerou uma noite com horário de noite (21:58 a 09:30, 692 min), sem END, com 91 % de profundo (632 min), e não o padrão antigo. Um corte por horário de início ou por duração não a pega; a fração de profundo e a falta de END talvez peguem. Falta a noite com o relógio no pulso (T3) para saber como fica uma noite verdadeira nesses dois critérios (`sma-dados.md`, seção 6).

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

### O que o host iOS do Repo 2 já fez (2026-10-30, sem iPhone)

O host existe no `react-native-technos-core-next` (`ios/`, estado no `docs/ios-pendente.md` de lá, "iOS: estado e
pendências"), sem mudar nada neste repositório: o framework `release` copiado por `ios/scripts/sync-xcframework.sh`, o
shim ObjC++ e o host Swift. No simulador, o `CoreGraph` de lá (com o `CoreBridge` e a sessão deste framework) passou
pelas gravações reais `real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl` (`TX 167/167`, passos 56),
`real/fitgps/0.2.1/pair-commands-sync-bt-reconnect-disconnect-reconnect.jsonl` (`32/32`) e
`real/lifeultragps/1.1.1/pair-info-time-steps-sync.jsonl` (`12/12`, escrita sem resposta), com as máscaras deste kit
de teste portadas para Swift; o app de exemplo registra o TurboModule e resolve o `initialize` no simulador, e compila
para iPhone.

### O primeiro teste num iPhone (2026-10-30)

App de exemplo em Release, com o fitGPS e o Life Ultra GPS (`fixtures.md`, seção 5):

- **fitGPS**: o fluxo inteiro (pareamento com BIND e LOGIN, versão, bateria, hora, passos, Sync com sono, desconectar), `TX matched 22/22`. O que ele trouxe sobre o relógio: a primeira página de sono real recente e o fechamento do dia em `mode 16` (`sma-dados.md`, seção 6; T4 e T5 acima), e as propriedades da `6e400002` (item técnico 1).
- **Life Ultra GPS**: todo `pair` falhou com status 14 até o usuário esquecer o vínculo antigo nos Ajustes (ADR 23, item abaixo); depois, o fluxo inteiro na primeira tentativa, `TX matched 12/12`, com a tabela GATT em `protocolo-ido.md`, seção 1.1.
- **Cabeçalho da gravação** com o relógio errado: corrigido no `CoreBridge` (ADR 24) e, na fixture do fitGPS, por script, só no campo `device` (`fixtures.md`, seção 5).

### O que falta, em ordem

Os itens do host estão detalhados no [`docs/ios-pendente.md` do Repo 2](../../react-native-technos-core-next/docs/ios-pendente.md); aqui fica o que cada um fecha e onde registrar.

- [x] **Pod e shim ObjC++.** Feito no Repo 2 em 2026-10-30 (`ios/CoreNext/turbomodule/RCTNativeCoreNext.mm`, registro
  por `codegenConfig.ios.modulesProvider`; `pod install` e build do `example/ios` para simulador e iPhone). Texto
  original: O XCFramework (`bridge/build/XCFrameworks/release/`, gerado pelo `:bridge`) copiado para `ios/Frameworks/` do Repo 2 e o `RCTNativeCoreNext.mm` conformando ao `NativeCoreNextSpec`. O shim não tem regra: cada método do spec embrulha o par resolve/reject numa `TWPCoreCompletion` e chama o seletor de mesmo nome do `TWPCoreBridge` (o `initSMASDK:reject:` chama `doInitSMASDKCompletion:`), o módulo é o `TWPCoreEventSink` (`canEmit` = callback de eventos instalado; `emitName:payload:` converte com o `TWPFoundationConverter` e chama o `emitOnX:`), liga-se com `attachSink:` na primeira chamada e se desliga com `invalidateSink:`. A forma exata está em `bridge/objc-interop/MinimalTurboModule.mm`, que o `check` já compila. O `TWPCoreBridge` do processo vem do Swift (`CoreNext-Swift.h` importável num pod estático). Fecha: `pod install` e build do `example/ios`. Registrar no `ios/README.md` do Repo 2.
- [x] **Ponte Swift e persistência.** Feito no Repo 2 em 2026-10-30 (`CoreGraph`, `IosCoreHost`, `FileSampleStore`,
  `PairingStore`, `RingLogger`), com os testes no simulador, incluindo o `invalidate` de um reload e o histórico relido
  por uma instância nova. Texto original: `CoreGraph.swift`, um por processo: monta `WatchSession(transport:logger:store:)`, liga o transporte e cria `CoreBridge(session:logger:host:)`; implementa `CoreHost` (autorização e estado do `CBCentralManager`, endereço como UUID, scan, `beginBackgroundTask` no sync, pareamento em `UserDefaults`, cabeçalho da gravação, log em memória com `os_log`, `exportLogs` que `throws`); chama os ganchos (`onHostResume` ao voltar ao primeiro plano, `onBluetoothTurnedOff`/`onBluetoothTurnedOn`, `onScannedDevice`, `onScanFailed`, `onLogEntry`). Mais um `SampleStore` em JSON no Application Support. A regra de cada método JS já está no `CoreBridge` e testada no simulador; os testes do host iOS cobrem só essas peças de plataforma (o `SampleStore`, as preferências, o logger). Fecha: esses testes e um reload do JS sem perder o histórico. Registrar no `docs/ios-pendente.md` do Repo 2.
- [x] **Export do log no iOS.** Implementado no Repo 2 (`frames-*.jsonl` com `platform` `ios` em `Documents/corenext/`,
  app Arquivos e "Compartilhar arquivo"; relido pelo leitor de fixture no teste do simulador). Feito num iPhone em
  2026-10-30: `real/fitgps/0.2.1/ios-pair-info-time-steps-sleep-sync.jsonl` (`TX matched 22/22`, com o `device` do
  cabeçalho corrigido, ADR 24) e `real/lifeultragps/1.1.1/ios-pair-info-time-steps-sync.jsonl` (`12/12`), nos quatro
  targets (`fixtures.md`, seção 5). Texto original: Gravar `frames-*.jsonl` com `FrameJsonl` a partir do `WatchLogger.frame` (o `value` vai direto, sem conversão) e tirar o arquivo do iPhone (Files ou `UIActivityViewController`). O `address` no iOS é o UUID do periférico, não o MAC, mas o log nativo continua fora do git. Fecha: uma gravação com `platform` `ios` em `fixtures/real/fitgps/<fw>/` que o `RealFixturesReplayTest` reproduz. Registrar em `fixtures.md`, seção 5.
- [ ] **`CentralTransport` sobre `CoreBluetooth`.** Implementado no Repo 2 (contrato B, `requestMtu` pela ADR 5,
  escrita sem resposta por `canSendWriteWithoutResponse`, scan sem filtro de serviço). No iPhone, em 2026-10-30:
  pareamento, comandos e sync com o fitGPS (MTU 23, `maximumWriteValueLength(.withoutResponse)` = 20; escrita com
  resposta na `6e400002`) e com o Life Ultra GPS (MTU 515 oferecido e não usado; escrita sem resposta na `0af6`), as
  duas gravações reproduzindo `TX matched N/N`; o Bluetooth foi desligado e religado só sem link (`DISCONNECTED` →
  `BLUETOOTH_OFF` → `DISCONNECTED`, log de texto, ts 1793363321922 e 1793363331976). Falta o Bluetooth desligado e
  religado em `READY`. Texto original: Fila de operações como a `GattQueue` do Android, os eventos entregues da fila de delegate (sem `dispatch` extra: a sessão já enfileira), um `onDisconnected` por `disconnect()` com link aberto ou pendente, o contrato B no `.poweredOff`, `requestMtu` pela regra da ADR 5, escrita `.withResponse`, scan sem filtro de serviço (o fitGPS não anuncia o NUS). Fecha: pareamento, sync e Bluetooth desligado e religado num iPhone com o fitGPS, com a gravação reproduzindo `TX matched N/N`. Registrar em `arquitetura.md`, seção 5, se algum comportamento divergir, e nas ADRs 5, 12 e 18.
- [ ] **Vínculo apagado no relógio (`PAIRING_REMOVED`, ADR 23) no iPhone.** Achado no primeiro teste no iPhone
  (2026-10-30): o Life Ultra GPS pareado com o iPhone pelo app de produção e depois com um Android fez todo `connect`
  falhar com `CBError.peerRemovedPairingInformation` até o usuário esquecer o relógio nos Ajustes. A sessão e o host iOS
  agora param com `PAIRING_REMOVED` (sem re-arme nem espera crescente), o que só foi testado sem relógio. Fecha: repetir
  o cenário (parear num Android, voltar ao iPhone) no `pairDevice` e no "Reconnect last", ver o `onError`
  `PAIRING_REMOVED` com a dica na tela, um `connect` por tentativa no log, e a reconexão ao voltar ao app depois de
  esquecer o relógio. Registrar na ADR 23.
- [ ] **H8 com uma gravação iOS.** O bloco 5 da sessão com o relógio, agora com este core também no iPhone: o LOGIN alternativo reencontra um relógio vinculado pelo app de produção? Fecha **H8**. Registrar na linha H8 da seção 8 de `protocolo-sma.md`, no "Risco aberto" da ADR 11 e na fixture `h8-ios-bound-reconnect.jsonl`.
- [ ] **Segundo plano e restauração.** No Repo 2: `CoreNext.bootstrap(launchOptions:)` no `AppDelegate`, identificador
  de restauração com `bluetooth-central`, `willRestoreState` mínimo; falta testar, e a nota de desenho abaixo continua
  aberta. Texto original: `CoreNext.bootstrap(launchOptions:)` criando o `CBCentralManager` com o identificador de restauração antes do JS, `willRestoreState`, modo `bluetooth-central`. **Nota de desenho:** a espera crescente da reconexão (2 s, 4 s… até 60 s, `SessionEngine.scheduleReconnect`, `SessionEngine.kt:653-666`, depois de uma tentativa que falhou) é um `delay` de corrotina, e nesse intervalo não há `connect` pendente no host. Com o app suspenso no iOS o processo não roda, e sem um `connect` pendente o sistema não tem por que acordá-lo: a reconexão pararia até o usuário abrir o app. No iOS, a espera deveria ser o próprio `CBCentralManager.connect` pendente (que o sistema mantém com o app suspenso e que acorda o app quando o relógio volta), ou seja, chamar `connect(autoReconnect = true)` na hora em vez de esperar. A queda de um link pronto já reconecta na hora e não tem esse problema. Isso muda a sessão (por exemplo, um ajuste por plataforma no perfil ou no construtor) e fica para quando o host existir. Fecha: relógio fora de alcance com o app em segundo plano reconectando sozinho, e a restauração testada com pressão de memória (matar o app pelo app switcher desliga a restauração). Registrar numa ADR nova, na ADR 11 e em `arquitetura.md`, seção 5 (`RECONNECTING`).

