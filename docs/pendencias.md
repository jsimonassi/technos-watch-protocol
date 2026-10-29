# Pendências: validação no relógio e itens técnicos abertos

A primeira gravação real (`fixtures/real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl`, fitGPS com firmware 0.2.1 e um telefone Android, 2026-10-29) já reproduz no replay e decidiu parte das hipóteses (`protocolo-sma.md`, seção 8). O que ela não cobriu ficou para uma sessão com o relógio de cerca de 90 min e duas noites, que foi despriorizada: isto é uma prova de conceito, e o caminho de passos, que é o critério de aceite, já funcionou de ponta a ponta. Esta lista guarda o roteiro para quando a sessão acontecer.

**O sono não é prioridade**: o app de produção nunca leu sono do fitGPS (nenhum Core pede `05/45`, `SMAService.java:561, 573, 591`; `SMAService.m:1077-1080`), então não há o que regredir; os itens de sono ficam no fim.

Cada item diz a hipótese ou o critério que fecha e onde registrar o resultado. Ao fechar um item, marque a caixa, cite a fixture nova pelo `atMs` (ou o log pelo `ts`) e atualize os documentos indicados na mesma alteração. Os critérios A1 a A8 e T1 a T7 são os do plano da PoC.

As pendências do host Android e da integração com o app Mormaii (o watchdog de 50 s do sync do lado do app, polling de passos ao vivo, `FileProvider`, `deviceId`, eventos sem equivalente) estão no [`docs/pendencias.md` do Repo 2](../../react-native-technos-core-next/docs/pendencias.md), que aponta para cá para o roteiro com o relógio.

## Preparação

- [ ] Build do app de exemplo (Repo 2) com: o replay corrigido deste repositório (já está aqui: uma gravação copiada para `fixtures/real/` roda no `./gradlew :protocol:jvmTest`); a linha `SmaDriver/frame` (já está no `SmaDriver`); `recordedAt` com milissegundos (já está no `FrameRecorder` do Repo 2); o log das propriedades da characteristic `6e400002` (falta no Repo 2, seção 6 do `docs/pendencias.md` de lá); opcional, um botão de depuração "pular o próximo ACK" para o teste do H1.
- [ ] **HCI snoop** ligado (`fixtures.md`, seção 3.2). Ele mostra o intervalo de conexão e as propriedades da characteristic em todos os blocos, o que o log do app não mostra.
- [ ] Perguntar a quem fez a primeira gravação:
  - se o relógio pediu para tocar em algo na tela no primeiro BIND (fecha H7 para a gravação de 2026-10-29);
  - quantos passos a tela mostrava por volta de 08:31 (pela gravação, eram 56; `sma-dados.md`, seção 6);
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
- [ ] Opcional, com o botão de depuração: pular o ACK de uma página `05/42` e repetir o `05/41`. É o teste do H1 (item técnico 5).

### 2. Disconnect, Forget e novo pareamento (A7, H7, A3 a A5, A8)

- [ ] **Disconnect** e esperar 60 s sem reconexão; **Reconnect last** (só `03/03`, sem BIND).
- [ ] **Forget**: `03/05 [00]` com ACK, estado `DISCONNECTED`, store vazio, "Último pareado: nenhum".
  - Fecha **A7**. Registrar em `fixtures.md`, seção 5 (fixture `disconnect-reconnect-forget.jsonl`).
- [ ] Scan e pair **olhando a tela do relógio no BIND**: ele pede toque? Quanto tempo do ACK do BIND até o `03/02`?
  - Fecha **H7**. Sem pedido de toque e com o `03/02` em poucos segundos, o `bindTimeoutMs` de 60 s pode cair (`decisoes.md`, ADR 4). Registrar na linha H7 da seção 8 de `protocolo-sma.md` e na ADR 4.
- [ ] Bateria e versão comparadas com a tela do relógio (A4).
- [ ] **Set time com a hora do celular adiantada 5 min**, conferir a tela do relógio, depois voltar à hora automática (A5). Na gravação de 2026-10-29 o Set time do usuário (`atMs` 778427) mandou a mesma hora que o acerto automático já tinha posto, então a tela não mudou.
- [ ] Share e Export (A8). Fixture limpa `pair-basics.jsonl`.

### 3. Virada de hora andando (H3, A6, T1)

- [ ] Seguir o roteiro T1: ler e fotografar a tela em hh:40, hh:53, hh+1:03 e hh+1:08; andar sem ler entre hh:56 e hh:59 (o que também serve para ver se chega algum `02/70` espontâneo). No fim, Sync e depois **Passos** (`getHistoryPedometer`). Export.
  - **Fecha H3**: se o valor `mode 0` cai depois de hh:00, é o total da hora e o `HOURLY_SUM` está certo; se continua crescendo, é o acumulado do dia, e o perfil passa a `DAY_CUMULATIVE` (`decisoes.md`, ADR 1). Também mostra como a hora fecha (registro em hh:00:00 ou em hh:59:xx).
  - Fecha **A6**: total do dia no app igual ao da tela.
  - Registrar em: linha H3 da seção 8 de `protocolo-sma.md`; ADR 1; `sma-dados.md`, seções 2 e 6; fixture `steps-hour-boundary.jsonl`.
- [ ] **Conferência indireta do H3, sem relógio**: comparar no backend (`/Users/joao.farias/smartBackend`) a distribuição de passos diários do fitGPS com a dos outros modelos. Se o relógio acumulasse por dia, a soma das horas que a produção faz (`SMAService.java:437-448, 859-866`; `SMAService.m:1277-1288`) inflaria o total de 5 a 14 vezes, e isso apareceria ali. Registrar no contexto da ADR 1.

### 4. Robustez do link

- [ ] Fora de alcance por mais de 2 min e volta: um `onDisconnected`, `RECONNECTING` sem timeout e `READY` sozinho ao voltar.
- [ ] Fora de alcance e Bluetooth desligado e religado: nenhum `onDisconnected` depois do `onBluetoothStateChanged(false)` (contrato B, `decisoes.md`, ADR 18) e reconexão ao religar.
- [ ] Bluetooth desligado **durante** o sync: o sync termina com `onError(DISCONNECTED)`, o estado vai para `BLUETOOTH_OFF` e volta a `READY` depois de religar. Bateria pedida durante o sync espera na fila.
- [ ] Sync com o app em segundo plano e a tela apagada (serviço em primeiro plano do Repo 2).
- [ ] `adb shell am force-stop` e reabrir; abrir o app com o Bluetooth desligado e um pareamento salvo (o último estado que o JS vê deve ser `BLUETOOTH_OFF`, não `DISCONNECTED`; o primeiro `initialize()` pode reenviar 0 antes, `docs/api-js.md` do Repo 2); reload do JS em `READY` e em `SYNCING`.
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

## Noites (sono, não prioritário)

- [ ] **Noite real (T3)**: Sync na hora de dormir, dormir com o relógio, anotar os horários de deitar e levantar. De manhã: Sync, depois **Sono** (`getHistorySleep`), Export.
  - Fecha o conteúdo do **H4** e testa o analisador com dados reais: as sessões batem com os horários anotados? O critério exploratório é a resposta em até 20 s.
  - Registrar em: linha H4 da seção 8 de `protocolo-sma.md`; `sma-dados.md`, seções 5 e 6; ADR 9; fixture `sleep-night.jsonl`.
- [ ] **Relógio parado na mesa (T4)**: relógio sobre a mesa desde as 13:00, Sync de manhã.
  - Confirma se o padrão da gravação de 2026-10-29 (um START repetido com ENDs de 2 em 2 min) é artefato de relógio parado, o que decide o filtro de plausibilidade (item técnico 4). Registrar em `sma-dados.md`, seção 6.

Ficam para depois: a virada da meia-noite (T5) e os estados de atividade (T7).

## Itens técnicos abertos

### 1. O sync de 59 s

O `syncHistory` da gravação real levou 59,3 s, quase todo nas 64 páginas de sono: cerca de 900 ms por página, sendo ~362 ms do pedido ao ACK do relógio e ~358 ms do nosso ACK ao pedido seguinte (pedidos de `atMs` 1032538 a 1090312). O app Mormaii marca o sync como concluído aos 50 s (`startSyncTimeout`, `src/store/MainStoreContext/WatchInfo/useWatchInfoData/index.ts:142-152` do app), e o envio ao backend se perde. O lado do app (o timer que não reinicia no progresso, com as linhas) e o do host Android estão na seção 1 do `docs/pendencias.md` do Repo 2. Três frentes, nenhuma testada ainda:

- [ ] **Progresso por página.** Hoje o progresso vai só 0, 50 e 100 (`SyncEngine.sync`). Com `onSyncProgress` a cada página, o app pode reiniciar o watchdog em cada progresso. Fecha: nenhum sync é dado como concluído enquanto chegam páginas. Registrar em `arquitetura.md`, seção 6, e no KDoc de `WatchSessionListener.onSyncProgress`.
- [ ] **`requestConnectionPriority(HIGH)` durante o sync.** A própria SDK SMA pede prioridade alta de conexão nas transferências grandes (`requestHighSpeedMode`, `SmaManager.java:1458`, que vira `requestConnectionPriority`, `SmaMessenger.java:163`; usado em `:485`, `:1131` e `:1191`, no XMODEM, no envio de arquivo e no stream), mas nunca na leitura de histórico, e isso não foi testado aqui. Pedir no host Android ao começar o sync e voltar ao normal no fim deve encurtar o intervalo de conexão (o log sugere ~180 ms depois do login, sem HCI snoop que confirme). Fecha: duração por página medida com e sem, no mesmo relógio. Registrar numa ADR nova em `decisoes.md` e na seção 8 de `protocolo-sma.md`.
- [ ] **Tipo de escrita do ACK.** O driver escreve tudo com `WITH_RESPONSE` (`SmaDriver.writeControl`), e a recepção só entrega a página depois que a escrita do ACK é confirmada: é a espera de ~358 ms por página. Duas saídas: escrever o ACK com `WITHOUT_RESPONSE`, se a `6e400002` aceitar (as propriedades dela ainda não foram registradas), ou não bloquear a entrega na confirmação da escrita do ACK (estimativa de 900 para 550 ms por página, uns 35 s nas 64 páginas). A segunda mexe na ordem da ADR 2 e precisa do H1. Fecha: duração por página e nenhuma página perdida. Registrar nas ADRs 2 e 5 e na seção 1 de `protocolo-sma.md` (tipo de escrita).
- Não parar a paginação em páginas antigas: o relógio entrega da mais antiga para a mais nova, então isso nunca chegaria às noites recentes.

### 2. Risco do `HOURLY_SUM`

- [ ] Se o H3 mostrar acumulado do dia, o `HOURLY_SUM` conta de 5 a 14 vezes a mais. A produção usa a mesma soma (`SMAService.java:437-448, 859-866`; `SMAService.m:1277-1288`), o que é um indício fraco de que o valor é por hora. Fecha com o bloco 3 e a conferência no backend. Registrar na ADR 1 e na linha H3.

### 3. ADR 16 (deduplicação pelo instante)

- [ ] A chave da mescla de passos é o instante convertido com o fuso do telefone. Se o fuso mudar entre dois syncs e o relógio reenviar registros já lidos (H1 falsa), a mesma hora conta duas vezes. Gatilho para reverter: uma fixture que mostre registros reenviados depois do ACK, como a do T6. Registrar na ADR 16.
- [ ] A janela da mescla descarta amostras de amanhã com mais de 3 h à frente de agora. Com o relógio num fuso mais de 3 h à frente do telefone (viagem internacional, sync antes do acerto de hora da conexão), os passos mais novos perto da meia-noite saem e, se H2 valer, se perdem; até 3 h (todos os fusos do Brasil) eles ficam. Fecha com o T6; se o caso internacional importar, aumentar a folga ou guardar as amostras do futuro até o sync seguinte. Registrar na emenda da ADR 16.

### 4. Filtro de plausibilidade do sono

- [ ] Um buffer de artefato dentro da janela de 30 dias publicaria uma sessão de cerca de 12 h de DEEP começando à tarde. Decidir o filtro (duração máxima, horário de início, START repetido) só depois das noites T3 e T4. Registrar em `sma-dados.md`, seção 5, e numa ADR nova (ou na ADR 9).

### 5. Teste do gatilho do H1 (pular um ACK)

- [ ] Na gravação real todo ACK saiu 0 a 5 ms depois do último fragmento, então não dá para separar "o cursor avança com o nosso ACK" de "avança com o pedido seguinte". Com o botão de depuração do app de exemplo, pular o ACK de uma página `05/42` e repetir o `05/41`: se o relógio devolver a mesma página, o cursor avança no ACK (H1 confirmada e a ordem da ADR 2 importa); se devolver a seguinte, avança no pedido, e a página sem ACK se perdeu (o que reforça a ADR 3, sem retentativa de GET). Registrar na linha H1 da seção 8 de `protocolo-sma.md` e nas ADRs 2 e 3.
