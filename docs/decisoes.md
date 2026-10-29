# Decisões e riscos (ADRs curtos)

Uma entrada por decisão de comportamento que não está escrita em nenhuma SDK, ou que diverge do plano original. Cada uma tem o contexto, a decisão e como reverter. Quando um fixture real confirmar ou derrubar uma decisão, atualize a entrada com o caminho do fixture.

Fontes abreviadas como em [`protocolo-sma.md`](protocolo-sma.md). "A gravação real" é `fixtures/real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl` (fitGPS, firmware 0.2.1, telefone Android, 2026-10-29), citada pelo `atMs` dos pacotes; o que só aparece no log nativo do app de exemplo (descoberta de serviços, MTU, scan, eventos de Bluetooth) é citado pelo `ts` do log `logs-20261029-083254.jsonl`, que não é versionado porque traz o MAC do relógio (`docs/fixtures.md`, seção 3.1).

---

## ADR 1 — Passos do dia = soma das horas (`HOURLY_SUM`), não acumulado

**Contexto.** O plano assumiu `mode 0` cumulativo (`stepsAreCumulative = true`, risco 5). O Core em produção trata cada registro `mode 0` como o total da hora e soma as horas do dia (`SMAService.java:418-449` monta um mapa por hora; `SMAService.java:859-866` soma `pedometer`, `calories` e `distance * 1000`); o Core iOS também soma por dia (`SMAService.m:301-369`). Nenhum fixture real decidiu ainda (hipótese H3, `protocolo-sma.md`, seção 8).

**Decisão.** `Profiles.MORMAII_FIT_GPS.quirks.stepsAggregation = HOURLY_SUM`, igual ao que o app recebe hoje. `DAY_CUMULATIVE` existe e é testado (`StepsAggregator`, `HistoryStoreTest.dayCumulativeTakesTheLargestSample`): usa a amostra de mais passos do dia, não a última, para não ser enganado por um relógio que zere à meia-noite num fuso diferente do telefone.

**Evidência real.** Ainda não decide. A gravação real tem 7 registros `mode 0`, todos da hora 08, com totais acumulados dentro da hora (0, 14, 14, 14, 56, 56, 56; `atMs` 17228 a 1032178), e nenhuma leitura cruzou uma hora cheia (`sma-dados.md`, seção 6). O teste que decide é o bloco 3 de `pendencias.md`.

**Como reverter.** Trocar o valor no perfil (`profile/Profiles.kt`). Nada na sessão muda. Verificação: critério A6, total do dia no app igual ao da tela do relógio, com o fixture `fixtures/real/fitgps/<fw>/pair-sync.jsonl`.

## ADR 2 — ACK do frame recebido antes de entregá-lo

**Contexto.** As duas SDKs escrevem o ACK do frame de dados recebido antes de processá-lo (`SmaManager.java:540-542`; `SmaBLE.m:227`). Se o relógio avança o cursor do histórico quando recebe o ACK (H1), atrasar o ACK até o fim do processamento poderia fazê-lo reenviar a página ou desistir.

**Decisão.** O `SmaDriver` escreve `ACK(seq)` e só então entrega a resposta ao pedido. Como o ACK passa pelo mesmo mutex de escrita dos fragmentos, ele nunca se intercala com um frame que o telefone esteja enviando. Consequência para a sessão: o que o relógio mandou tem que ser persistido, porque pode não voltar (ADR 8).

**Como reverter.** `SmaDriver.handlePacket`: inverter a ordem de `writeControl(ack)` e `dispatch`. Os testes `ackIsWrittenBeforeTheResponseIsDelivered` e a gravação sintética (que exige o ACK antes do pedido seguinte) mostram o efeito.

## ADR 3 — GET sem retentativa

**Contexto.** A fila iOS repete comandos sem ACK (`SmaBLE.m:3302-3317`). Repetir um `05/41` cuja resposta só atrasou pode fazer o relógio entregar a página seguinte duas vezes ou pular uma, se o cursor avança por pedido (H1).

**Decisão.** Só SET e handshake têm uma retentativa (com seq novo; o iOS reenvia com o mesmo seq e a Android não reenvia, `protocolo-sma.md`, seção 4 e H9). GET em timeout falha com `TIMEOUT`; no `syncHistory`, a falha de passos encerra o sync com `onError(TIMEOUT)` e o app tenta de novo depois. A mesclagem por instante no store torna repetições inofensivas.

**Como reverter.** `SmaDriver.request`: incluir `SmaCommandKind.GET` em `retryable`. Teste afetado: `WatchSessionCommandsTest.stepsFailureFailsTheSyncAndRestoresReady` (espera exatamente um `05/41`).

## ADR 4 — BIND espera 60 s e a sessão não reconecta durante o handshake

**Contexto.** O relógio pode pedir confirmação na tela no primeiro pareamento (a UI do app Mormaii pede; nenhum código das SDKs espera por isso). O `EaseConnector` da SDK Android refaz a conexão a cada 18 s (`EaseConnector.java:25, 26-44`), o que derrubaria uma confirmação mais lenta.

**Decisão.** `Quirks.bindTimeoutMs = 60000` para a resposta `03/02`, sem retentativa da resposta (a espera já é longa). Durante `HANDSHAKING` do primeiro pareamento a sessão não reconecta: queda vira `onError(DISCONNECTED)` e falha vira `onError(HANDSHAKE_FAILED)`; o app decide se tenta de novo.

**Evidência real.** Na gravação real o relógio confirmou o BIND (ACK) em `atMs` 194 e respondeu `03/02 [00]` em `atMs` 2424–2426: cerca de 2,2 s depois do ACK. O LOGIN, no mesmo link, respondeu 2 ms depois do próprio ACK (ACK em 2804, `03/04` em 2806–2807). A demora é só do BIND, mas 2,2 s ainda está muito abaixo dos 60 s, e não se sabe se houve toque na tela do relógio (H7 continua aberta, `protocolo-sma.md`, seção 8). O valor fica até o pareamento observado de `docs/pendencias.md`.

**Como reverter.** Reduzir `bindTimeoutMs` no perfil quando um pareamento real mostrar o `03/02` sem confirmação na tela (H7). Teste: `WatchSessionLifecycleTest.bindWithoutAnswerTimesOutAfterTheLongBindTimeoutWithoutReconnecting`.

## ADR 5 — MTU 247, e 23 se a negociação falhar

**Contexto.** A SDK Android pede 512 e fragmenta em `mtu - 3` (`SmaManager.java:204, 302-305`); a iOS fragmenta em 20 bytes fixos (`SmaBLE.m:663-668`). 247 é o maior MTU que cabe num único pacote de enlace LE com extensão de tamanho de dados (251 bytes), o que evita fragmentação L2CAP sem ganho.

**Decisão.** `Quirks.requestMtu = 247`; o fragmento é `maxFragment ?: mtu - 3`. Se o host não responder em 2 s ou responder com falha, a sessão segue com 23 (fragmentos de 20, que funcionam com o iOS em produção). `Quirks.maxFragment = 20` fica disponível para um firmware que não aceite escritas maiores.

**Evidência real.** No Android da gravação real, o pedido de 247 foi respondido com MTU 23 e status 0, nas duas conexões (log, ts 1793272450905 e 1793273190489: `requested=247 effective=23 status=0`). O 23 veio da **negociação**, não do fallback desta ADR: a sessão recebeu a resposta e usou o valor dela. O cabeçalho da fixture grava `mtu` 23 e nenhum dos 850 pacotes passa de 20 bytes; o BIND saiu em 20 + 20 + 5 (`atMs` 0 a 133). O caminho do fallback (host sem resposta ou com falha) continua coberto só pelo teste. Nada muda na decisão: pedir 247 não custou nada a um relógio que só aceita 23.

**Como reverter.** Mudar `requestMtu` ou fixar `maxFragment` no perfil. Teste: `WatchSessionLifecycleTest.unansweredMtuFallsBackTo23AndFragmentsIn20`.

## ADR 6 — `FF60` antes do NUS

**Contexto.** A SDK Android usa `FF60/FF61/FF62` quando o serviço existe e só senão o NUS (`SmaManager.selectUUID`, `SmaManager.java:1577-1591`). O Core iOS em produção só conhece o NUS (`SMAService.m:1056-1061`), o que prova que o fitGPS tem NUS; não se sabe se tem `FF60` (H6).

**Decisão.** `gattCandidates = [SMA_ROUND, SMA_NUS]`. A sessão usa o primeiro candidato cujo serviço **e** as duas characteristics aparecem na descoberta; um `FF60` incompleto cai para o NUS.

**Evidência real.** O fitGPS com firmware 0.2.1 **não** expõe `FF60`: a descoberta devolveu `1800`, `1801`, `6e400001` (NUS) e `180a`, nas duas conexões (log, ts 1793272450855 e 1793273190432), e todos os pacotes da gravação real estão em `6e400002`/`6e400003`. H6 está refutada para esse firmware. A ordem fica como está: com o serviço ausente, a preferência custa uma busca num mapa, e um firmware que tenha `FF60` continua sendo tratado como a SDK Android trata.

**Como reverter.** Inverter a lista no perfil. Testes: `ff60IsPreferredOverNusWhenBothExist`, `incompleteFf60FallsBackToNus`.

## ADR 7 — Passos: vale o último registro da hora

**Contexto.** O Core Android arredonda cada registro `mode 0` para a hora cheia e o `HashMap.put` faz o último valer (`SMAService.java:439-448`). Se o relógio manda mais de um registro por hora, não se sabe se é atualização ou incremento.

**Decisão.** `SmaStepsMapper` faz o mesmo (último na ordem de chegada, só `mode 0`); a sessão mescla por instante e a leitura mais nova substitui a antiga.

**Evidência real.** A gravação real confirma que os registros da mesma hora são atualizações: os valores `mode 0` da hora 08 se repetem (14, 14, 14 e depois 56, 56, 56; `atMs` 85468 a 1032178) em vez de somar, então "vale o último" dá 56, e somar daria 210. Continua aberto o que acontece na virada da hora (H3, ADR 1).

**Como reverter.** Somar os registros da mesma hora no `SmaStepsMapper` (e não na sessão). A gravação sintética tem os dois casos (13:59:59 e 14:20 substituem as horas cheias) e o total esperado muda junto.

## ADR 8 — Histórico guardado no store; consultas nunca vão ao relógio

**Contexto.** Se H1/H2 forem verdadeiras, o relógio entrega cada registro uma vez só. Os dois Cores já respondem `getHistoryPedometer` de um repositório local (`SMAService.java:829-850`; `SMAService.m:301-312`).

**Decisão.** Tudo o que a sessão lê vai para o `SampleStore` do host (mesclado por instante) e para um instantâneo imutável em memória. `getStepsDays`/`getSleepSessions` são síncronos, de qualquer thread, e leem o instantâneo; `confirmUploaded` guarda um **conjunto** de dias (o JS manda uma lista, não um "até"); a sessão poda tudo o que for mais velho que `historyRetentionDays` (30). `disconnect(forget = true)` apaga o store.

**Evidência real.** H2 está sugerida para os passos: nenhum registro lido voltou numa leitura seguinte, nem depois de 230 s de Bluetooth desligado; para o sono não há evidência (lido uma vez só). Do H1, o avanço do cursor foi visto (os registros de passos não voltam e as páginas de sono se encadeiam), mas o gatilho, o nosso ACK ou o pedido seguinte, continua aberto (`protocolo-sma.md`, seção 8). A decisão fica.

**Como reverter.** Não há reversão simples sem perder dados se H2 for verdadeira. Se o relógio sempre devolver o histórico completo, o store continua correto (a mesclagem é idempotente); dá para reduzir `historyRetentionDays`.

## ADR 9 — Sono é exploratório

**Contexto.** Nenhum Core consumiu sono do fitGPS (`SMAService.java:1423`, `SMAService.m:167`). O analisador é port da SDK, mas o agrupamento por noite (corte em END e em lacunas de mais de 12 h) e a duração de uma noite que termina depois das 10:00 são decisões nossas (`sma-dados.md`, seção 5).

**Decisão.** No `syncHistory`, erro ou resposta vazia de sono vai para o log e o sync termina com sucesso; só erro de passos é terminal. `DISCONNECTED` continua sendo tratado como queda. O critério de aceite da PoC é passos.

**Como reverter.** Em `SyncEngine.readSleep`, propagar o erro em vez de registrar. Teste: `sleepFailureDoesNotFailTheSync`.

## ADR 10 — Sessão numa thread só

**Contexto.** A SDK Android coordena envio, ACK recebido e ACK devolvido com três semáforos (`SmaMessenger.java:18-21, 35-87`), com timeouts de 4 s espalhados. O Core atual tem condições de corrida conhecidas entre callbacks BLE e a thread do React Native.

**Decisão.** Todo estado mutável da sessão fica num dispatcher de paralelismo 1; chamadas do host e eventos BLE só enfileiram numa caixa única; o que demora roda em corrotinas filhas, canceladas por conexão. As únicas leituras de outras threads são `@Volatile` ou instantâneos imutáveis (`arquitetura.md`, seção 4). Os testes rodam em tempo virtual, determinísticos.

**Como reverter.** Não recomendado. Para paralelizar algo (ex.: IO do store), fazer fora da sessão, no host.

## ADR 11 — Reconexão com espera crescente, sem BIND e sem insistir num LOGIN recusado

**Contexto.** O contrato pede "continuar em RECONNECTING e tentar de novo" quando a reconexão falha. Um relógio que recusa o LOGIN toda vez (desvinculado na própria tela, ou vinculado por outro app) faria um laço de conectar–recusar–conectar. A primeira versão ainda fazia, a cada tentativa, o BIND de fallback com `USER_ID`, que pode pedir confirmação na tela do relógio, em segundo plano, de 2 a 60 s para sempre. E como o driver vive uma conexão só, o formato que deu certo no fallback não era lembrado: cada reconexão seguinte começava com `ANDROID_FF`, era recusada e refazia o BIND. Também uma queda logo depois de abrir o link (GATT 133/8, ou um relógio que derruba o link no LOGIN) reconectava na hora, sem espera nem contagem.

**Decisão.**
- O fallback com BIND (`FIRST_PAIR` completo com `USER_ID`, uma vez, risco 3 do plano) só existe no primeiro pareamento, que só vem de um `pair` pedido pelo usuário.
- Na reconexão o driver **nunca** manda BIND. LOGIN recusado com o formato do perfil → um LOGIN com o outro formato (`ANDROID_FF` ↔ `USER_ID`). Isso reencontra um relógio vinculado pelo fallback sem guardar nada: custa um LOGIN a mais por reconexão, em vez de uma preferência persistida no host (que exigiria mudar o contrato do `SampleStore` ou do perfil).
- LOGIN recusado com os dois formatos numa reconexão: a sessão desiste. `DISCONNECTED`, `onError(HANDSHAKE_FAILED)` no `reconnect` pendente (se houver) e nenhuma tentativa sozinha depois, nem ao religar o Bluetooth. O host pode chamar `reconnect` de novo (um LOGIN por pedido, sem BIND) ou pedir ao usuário um `pair`.
- Queda de um link que estava **pronto** reconecta na hora. Tentativa que falhou de outro jeito (LOGIN sem resposta, configuração que falhou, conexão pendente derrubada pelo host, link que caiu antes do `READY`) espera 2 s, 4 s, 8 s… até 60 s; volta a 2 s ao chegar em `READY`. `disconnect` interrompe o ciclo.

**Como reverter.** Espera: `SessionEngine.RECONNECT_BASE_DELAY_MS`/`RECONNECT_MAX_DELAY_MS`. Desistência: o ramo `HANDSHAKE_FAILED` de `SessionEngine.onAttemptFailed`. LOGIN alternativo: `SmaDriver.handshake`. Testes: `failedReconnectAttemptRetriesWithBackoff`, `refusedReconnectLoginStopsWithoutBind`, `linkDropBeforeReadyOnReconnectBacksOff` (`WatchSessionLifecycleTest`); `reconnectTriesTheOtherLoginPayloadWithoutBind`, `reconnectRefusedWithBothPayloadsNeverBinds` (`SmaDriverTest`).

**Risco aberto.** Não se sabe se o fitGPS aceita um LOGIN com um payload diferente do BIND (H8). Se o relógio só aceitar o payload do BIND, a reconexão continua funcionando para os dois casos, porque o LOGIN alternativo cobre o outro formato.

## ADR 12 — Desconexões esperadas contadas, não identificadas

**Contexto.** `TransportEvents.onDisconnected` não diz de qual conexão é. Depois de um `Transport.disconnect`, a resposta pode chegar quando uma tentativa nova já começou (`pair` logo depois de outro).

**Decisão.** A sessão conta os `disconnect` que pediu com link aberto ou pendente e ignora o mesmo número de `onDisconnected`; a contagem zera no `onConnected` seguinte (o host é serial). Exige do host: um `onDisconnected` por `disconnect` quando havia algo aberto ou pendente, e nenhum quando não havia; e um `onDisconnected` por `connect` que falha sem conectar, salvo a conexão pendente, que o host mantém (`arquitetura.md`, seção 5). Sem esse último, o `disconnect` que a sessão faz ao desistir contaria uma resposta que não vem.

**Emenda (2026-10-29): Bluetooth desligado fica fora da contagem.** O contrato original não dizia o que o host faz quando o Bluetooth desliga, e a ambiguidade cobrava caro com a contagem: a sessão chamava `disconnect` ao receber `onBluetoothStateChanged(false)` e contava uma resposta; um host que, como o `FakeTransport` e o `CoreBluetooth`, não responde nada nesse momento deixava a contagem em 1, e a primeira queda real depois de religar (a conexão pendente nova falhando) era engolida como "a resposta esperada". Como a reconexão não tem timeout, a sessão ficava presa em `RECONNECTING`. O comportamento no desligamento agora é o contrato B da ADR 18: a sessão zera a contagem e não conta o `disconnect` de limpeza.

**Como reverter.** Se o host não puder garantir isso, acrescentar um identificador de conexão aos eventos de ciclo de vida do `TransportEvents` (mudança de contrato). A emenda se reverte com a ADR 18.

## ADR 13 — Sem `BUSY`: um `pair` novo derruba o anterior

**Contexto.** O KDoc original listava `BUSY` para `pair` com outra conexão em andamento. Um host pode chamar `pair` de novo quando o usuário toca outra vez num relógio da lista (ou em outro); recusar deixaria a UI presa esperando um pareamento que o usuário já abandonou, e o host teria que chamar `disconnect` antes de toda tentativa.

**Decisão.** `pair`/`reconnect` derrubam a tentativa atual (pedido anterior e comandos com `CANCELLED`) e começam de novo. `WatchErrorCodes.BUSY` fica reservado.

**Como reverter.** Em `SessionEngine.onPair`, recusar com `BUSY` quando `attempt != null` em vez de chamar `abandonCurrent`.

## ADR 14 — Portões de documentação e de interoperabilidade no build

**Contexto.** O plano pede KDoc em toda a API pública verificado pelo `dokkaHtml` (critério D2) e "um teste que falha se o header ObjC contiver `completionHandler` ou `Flow`" (risco 13). O `dokkaHtml` é a tarefa do Dokka 1, que está obsoleto e não lê bem projetos KMP com AGP; e o header ObjC só existe depois do link do framework, que nenhum teste de `commonTest` enxerga.

**Decisão.**
- Dokka 2.0.0 no modo novo (`org.jetbrains.dokka.experimental.gradle.pluginMode=V2Enabled` em `gradle.properties`), com `reportUndocumented = true`, só visibilidade pública e `failOnWarning = true`: um símbolo público sem KDoc quebra o build. `dokkaHtml` continua existindo como atalho para `dokkaGeneratePublicationHtml`, para os comandos do plano valerem. O texto de abertura e o de cada pacote vêm de `protocol/Module.md`.
- O header vira uma tarefa Gradle, `checkObjcHeader`, ligada ao `check`: liga o framework de debug do `iosSimulatorArm64` e procura `completionHandler`, `Kotlinx_coroutines`, `Kotlinx_datetime`, `Kotlinx_serialization`, `Flow` e `Coroutine`. Uma sonda temporária com um `suspend fun`, um `Flow` e um `Instant` públicos gerou 39 ocorrências e falhou a tarefa, o que prova que a lista pega os três casos (o `Instant` também arrasta os serializadores do `kotlinx-serialization`, daí o quarto padrão).

**Como reverter.** Tirar `failOnWarning` (os avisos continuam no log) ou o `dependsOn(checkObjcHeader)` do `check`, ambos em `protocol/build.gradle.kts`. Para voltar ao Dokka 1, trocar o modo para `V1Enabled`; a configuração `dokka { }` teria que virar `tasks.dokkaHtml { dokkaSourceSets { … } }`.

## ADR 15 — `getStepsDays`/`getSleepSessions` devolvem todo dia não confirmado

**Contexto.** A primeira versão só devolvia os dias de `hoje - daysBefore` até hoje. Os dois Cores atuais ignoram `daysBefore` e devolvem todo dia ainda não enviado ao backend (`SMAService.java:829-850` filtra só os já enviados; `SMAService.m:301-312` percorre o histórico inteiro), e o app Mormaii sempre pede 3 dias (`src/services/Smartwatch.ts:177-178` do app). Quem passa 6 dias sem abrir o app teria os dias −6 a −4 lidos do relógio, guardados no store e nunca enviados, até a poda de 30 dias apagá-los: perda silenciosa em relação à produção.

**Decisão.** Os dois métodos devolvem todo dia até hoje que tem dado e não foi confirmado; o store já está limitado a `historyRetentionDays` pela poda. `daysBefore` continua na assinatura (contrato e compatibilidade com o Core) e só serve para o log registrar quando a resposta traz dias mais velhos que a janela pedida. Dias no futuro (relógio com data errada) continuam fora.

**Como reverter.** Voltar o filtro `dayKey in oldest..today` em `HistoryStore.stepsDays`/`sleepSessions`. Testes: `unconfirmedDaysOlderThanTheWindowAreStillReturned` (`HistoryStoreTest`) e `daysBeforeDoesNotHideUnconfirmedDays` (`WatchSessionCommandsTest`).

## ADR 16 — Amostras de passos deduplicadas pelo instante, não pelo `(ts, mode)` cru

**Contexto.** O plano pede deduplicar por `(ts, mode)` do relógio. O `ts` do SMA é relógio de parede local (`sma-dados.md`, seção 1), e o `SmaStepsMapper` o converte em instante com o fuso do telefone na hora da leitura; o `HistoryStore` mescla por esse instante. Se o fuso do telefone mudar entre dois syncs (viagem, inclusive entre fusos do Brasil) **e** o relógio reenviar registros antigos (H1 falsa), a mesma hora do relógio vira outro instante e é contada duas vezes no `HOURLY_SUM`. Exemplo: 2026-10-27 19:00 lido em `America/Sao_Paulo` vira 22:00Z; o mesmo registro lido depois em `America/Manaus` vira 23:00Z, outra chave.

**Decisão.** Manter a chave pelo instante, como o Core Android (`SMAService.java:420, 447-448`). Guardar o `ts` cru exigiria um campo novo em `StepsSample` ou no `SampleStore`, ambos contrato público, para um caso que precisa de duas condições juntas, uma delas ainda não observada. Só mode 0 vira amostra, então `mode` não precisa entrar na chave.

**Como reverter.** Acrescentar o relógio de parede cru (`secondsSince2000`) à amostra ou à chave do store, mesclar por ele e converter para instante só na agregação. Gatilho: um fixture real que mostre o relógio reenviando registros já confirmados por ACK (H1 falsa).

**Emenda (2026-10-29): janela da mescla e mudança de fuso.** A mescla passou a descartar amostras do futuro (`arquitetura.md`, seção 7), e o fuso cobra ali também. O relógio grava hora de parede e a sessão só o acerta no começo de cada conexão, depois do `postLoginDelayMs`. Se o telefone vai para oeste com o relógio conectado, ou se o sync roda antes desse acerto, os registros mais novos são convertidos com o fuso novo e ficam até a diferença entre os fusos à frente de agora; perto da meia-noite, isso é amanhã. Exemplo (T6 de `pendencias.md`): 23:30 em `America/Manaus` com o relógio ainda em São Paulo; o registro ao vivo, carimbado na hora do pedido (`sma-dados.md`, seção 6), vem 00:30 e vira 00:00 de amanhã em Manaus, 30 min à frente. Descartar pelo dia apagaria esses passos, e se H2 valer (sugerida para SPORT) o relógio não os devolve.
- **Decisão.** Uma amostra só é do futuro com dia depois de hoje **e** mais de 3 h à frente de agora (`HistoryStore.FUTURE_TOLERANCE_MS`). 3 h é a maior diferença entre os fusos do Brasil (UTC-2 a UTC-5); nenhuma SDK define esse número. As que entram pela folga geram um INFO `Session/mergeSteps` `kept … within 3 h of now`.
- **Custo que fica.** Com uma diferença maior (viagem internacional), a amostra ainda sai e, se H2 valer, se perde. A que entra pela folga fica adiantada pela diferença entre os fusos, no dia seguinte, como era antes da janela.
- **Como reverter.** Em `HistoryStore.withinWindow`, voltar a condição do futuro a só "dia depois de hoje" (perde os passos do exemplo) ou mudar a folga. Teste: `WatchSessionCommandsTest.stepsFromAWatchStillInTheOldTimeZoneAreKeptAcrossMidnight`.

## ADR 17 — `compileSdk 35`, não 36

**Contexto.** O plano fixa `compileSdk 36`. O Kotlin 2.1.20 só garante compatibilidade com o AGP até 8.7.2 (risco 12 do plano), e o AGP 8.7.x só declara suporte até a API 35; a API 36 pede um AGP mais novo, fora dessa faixa.

**Decisão.** `compileSdk = 35` e `buildToolsVersion = "35.0.0"` em `protocol/build.gradle.kts`. A biblioteca não usa nenhuma API do Android: o alvo Android só empacota o AAR. Quem consome (o app de exemplo do Repo 2 e o app Mormaii) compila com 36 e só lê o AAR, o que funciona porque o `compileSdk` da biblioteca não se propaga.

**Como reverter.** Subir o AGP (e o Kotlin, se a faixa exigir) junto com `compileSdk = 36` e o build-tools 36, e rodar `./gradlew :protocol:check publishToMavenLocal`.

## ADR 18 — Bluetooth desligado: o host fica mudo (contrato B)

**Contexto.** O contrato do `Transport` não dizia se, ao desligar o Bluetooth do telefone, o host entrega `onDisconnected` pelos links abertos e conexões pendentes. Cada lado fazia uma coisa:
- o `CoreBluetooth` passa para `poweredOff` e não chama `didDisconnectPeripheral`;
- o `FakeTransport` dos testes já ficava mudo;
- o host Android da gravação real entregou **três** `onDisconnected` para um link só (log, ts 1793272958357, 1793272958524 e 1793272958525; na fixture, o último pacote antes do desligamento é o `atMs` 123713 e o LOGIN da reconexão é o `atMs` 739559). O primeiro não veio do stack: o próprio host o emitiu ao tratar o desligamento, logo antes de repassar o aviso (log, ts 1793272958346, `onBluetoothStateChanged enabled=false`, e a mensagem `Bluetooth turned off` no ts 1793272958357). Por chegar antes do aviso, a sessão o tratou como queda comum (`READY → RECONNECTING`, com um `connect` para o adaptador já desligado); o segundo, a recusa desse `connect`, foi consumido pela contagem da ADR 12; o terceiro, a resposta ao `disconnect`, caiu em "link lost … with no connection attempt".

Com a contagem da ADR 12, essa ambiguidade fazia a sessão engolir uma falha real e ficar presa em `RECONNECTING` num host mudo (ADR 12, emenda). O iOS seria uma terceira implementação do mesmo ponto, então o contrato precisava ser fixado antes do transporte iOS.

**Decisão.** Contrato B, o mesmo do `CoreBluetooth`:
- Quando o Bluetooth do telefone desliga, o host entrega `onBluetoothStateChanged(false)` e **nenhum** `onDisconnected` pelos links abertos e conexões pendentes que isso derrubou, nem antes nem depois.
- Depois disso, `Transport.disconnect()` não emite nada até um novo `connect()`. Um `connect()` novo é uma tentativa nova: se o host o recusa na hora (o adaptador ainda desligado), termina com exatamente um `onDisconnected`, como qualquer `connect` que falha sem conectar (`arquitetura.md`, seção 5).
- Um `onDisconnected` que o host já tinha entregado **antes** do `onBluetoothStateChanged(false)` (o stack reportou a queda antes do aviso) é uma queda comum, tratada como sempre: a sessão reconecta e, em seguida, o desligamento derruba essa tentativa.

Na sessão (`SessionEngine.releaseTransportOnBluetoothOff`): o desligamento zera a contagem de desconexões esperadas, inclusive a de um `disconnect` anterior ainda sem resposta, e marca o transporte inativo. A sessão ainda chama `Transport.disconnect()` para o host liberar o que é dele (no Android, o `BluetoothGatt`), mas não conta essa chamada. Um `onDisconnected` que chegue depois do desligamento (host fora do contrato) só gera um WARN `link lost … with no connection attempt`; se chegar depois de religar, vira a falha da conexão nova e a espera crescente de sempre (ADR 11), nunca uma sessão presa. O host Android do `react-native-technos-core-next` passa a suprimir os `onDisconnected` do desligamento.

**Como reverter.** Para o contrato A (o host entrega exatamente um `onDisconnected` por link aberto ou conexão pendente derrubados): em `SessionEngine.onBluetoothState`, trocar `releaseTransportOnBluetoothOff()` por `disconnectTransport("bluetooth off")`, fazer o `FakeTransport.setBluetooth(false)` entregar esse `onDisconnected`, ajustar os testes e mudar os KDocs de `Transport`/`TransportEvents.onBluetoothStateChanged`, a seção 5 de `arquitetura.md` e o host Android. A saída que não depende de nenhum dos dois contratos é a de reverter a ADR 12: um identificador de conexão nos eventos de ciclo de vida. Testes: `BluetoothOffContractTest` (link aberto, conexão pendente em `RECONNECTING`, queda reportada antes do desligamento, `disconnect` ainda sem resposta quando o Bluetooth desliga, `onDisconnected` atrasado) e `bluetoothOffFailsCommandsAndOnReconnectsWithLogin`, `bluetoothOffDuringPairFailsItAndDoesNotReconnect` (`WatchSessionLifecycleTest`).
