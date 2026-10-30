# Dados de histórico SMA: passos e sono

Como os registros de histórico do SMA viram `StepsSample` e `SleepSession`. O lado de protocolo (pedido, resposta, paginação, hipóteses H1-H9) está em [`protocolo-sma.md`](protocolo-sma.md), seção 8. As abreviações de fonte são as mesmas daquele documento.

Código: `protocol/src/commonMain/kotlin/br/com/grupotechnos/watch/protocol/sma/data/` (`SmaRecords.kt`, `SmaStepsMapper.kt`, `SmaSleepAnalyzer.kt`) e `sma/SmaEstimates.kt`.

## 1. Registros e época

**Época**: todo timestamp é um uint32 BE em **segundos desde 2000-01-01 00:00 no horário local de parede** do relógio. As SDKs somam `946684800000` ms (2000-01-01T00:00Z, `SmaManager.java:87`) e formatam em GMT (`SmaBleUtils.java:16-18`; iOS `SmaBLE.m:2677-2702`), ou seja, tratam o número como relógio de parede sem fuso. `SmaLocalTime` faz a mesma leitura e devolve um `LocalDateTime`; a conversão para instante real usa o fuso do telefone (`DriverLink.clock.timeZoneId()`) via `TimeFormat.fromLocalWallClock`, com horário de verão.

O Core Android fazia `time − TimeZone.getDefault().getRawOffset()` (`SMAService.java:420`), o que erra uma hora durante o horário de verão. A diferença está coberta por `SmaStepsMapperTest.summerTimeUsesTheRealOffsetNotTheRawOne`.

**Atividade, `05/42`, 8 bytes** (`SmaManager.java:783-797`; `SmaBLE.m:2698-2701`)

| Offset | Tamanho | Campo |
|---|---|---|
| 0 | 4 | timestamp (segundos desde 2000, local) |
| 4 | 1 | mode |
| 5 | 3 | passos, uint24 BE |

**Sono, `05/46`, 7 bytes** (`SmaManager.java:815-831`; `SmaBLE.m:2926-2931`)

| Offset | Tamanho | Campo |
|---|---|---|
| 0 | 4 | timestamp (segundos desde 2000, local) |
| 4 | 1 | mode |
| 5 | 1 | soft: contagem de movimentos leves |
| 6 | 1 | strong: contagem de movimentos fortes |

Número de registros = `len(value) / tamanho`, com divisão inteira (`SmaManager.java:784, 816`). Bytes que sobram são ignorados e registrados no log (`SmaRecordCodec.Decoded.trailingBytes`).

**Valores de `mode` da atividade** (`SmaSport.Mode`, `SmaSport.java:75-121`)

| Valor | Nome | Uso aqui |
|---|---|---|
| 0 | normal (amostra do pedômetro) | **único que vira `StepsSample`** |
| 16 / 17 / 18 | SIT / WALK / RUN | ignorado |
| 32 / 34 / 47 | START / GOING / END de exercício | ignorado (o Core usa para atividade por diferença de passos, `SMAService.java:422-436`) |
| 48, 49, 63 | START_IMED_6, START_IMED_12, END_IMED | ignorado |
| 64..226 | pares `*_START/_GOING/_END` (indoor, outdoor, ciclismo, natação, caminhada, alpinismo, ioga, esteira, bicicleta, basquete, futebol, badminton) | ignorado |

**Valores de `mode` do sono** (`SmaSleep.Mode`, `SmaSleep.java:129-135`)

| Valor | Nome |
|---|---|
| 1 | DEEP (profundo) |
| 2 | LIGHT (leve) |
| 3 | AWAKE (acordado) |
| 17 | START (adormeceu) |
| 34 | END (acordou) |

## 2. Passos: agrupamento por hora

Regras de `SmaStepsMapper`, iguais às do Core Android em produção (`SMAService.java:418-449`):

1. Só `mode == 0`.
2. O horário local é arredondado para baixo até a hora cheia (minuto e segundo zerados, `SMAService.java:439-443`).
3. Vários registros na mesma hora: vale o **último na ordem de chegada**. O Core faz `HashMap.put` na ordem da lista (`SMAService.java:448`), que sobrescreve; a ordem de chegada é a ordem das páginas e, dentro de uma página, a do value. Não é "o mais tardio": se um registro de 14:30 chegar depois de um de 14:55, vale o de 14:30 (`SmaStepsMapperTest.lastRecordInArrivalOrderWinsTheHour`).
4. A hora local vira instante com o fuso do telefone. Numa lacuna de horário de verão (ex.: 2018-11-04 00:00 em São Paulo) a hora é empurrada para frente (01:00 −02:00). Numa sobreposição, as duas ocorrências da mesma hora local caem no mesmo balde.
5. Calorias e distância estimadas por amostra (seção 4).
6. Saída ordenada por instante.

**Agregação diária (`Quirks.stepsAggregation`)**: a sessão combina as amostras de um dia num total. O padrão do fitGPS é `DAY_CUMULATIVE` desde 2026-10-29: no firmware 0.2.1 o registro `mode 0` é o **acumulado do dia** até aquele registro, e não o total da hora (H3 refutada; seção 6 e `decisoes.md`, ADR 1). O total do dia é a amostra de mais passos do dia, com as calorias e a distância dela. Como o relógio entrega os registros do mais antigo para o mais novo e o contador só cresce durante o dia, o "último da hora" do passo 3 é também o maior da hora, e o maior das horas é a leitura mais nova do dia. Usa-se o maior, e não o último, por causa da meia-noite: um relógio que zere à meia-noite no fuso dele, e não no do telefone, deixaria a última hora do dia com um valor pequeno. `HOURLY_SUM` (cada amostra é o total da hora; o dia soma passos, calorias e distância) é o que os dois Cores fazem hoje em `getHistoryPedometer` (`SMAService.java:856-866`; `SMAService.m:334-342`); com o acumulado do dia, ele conta o acumulado de novo a cada hora com registro e infla o total (em 2026-10-29, 56 + 56 = 112 para um dia de 56 passos). Fica disponível para um relógio cujo contador zere a cada hora. Limite de `DAY_CUMULATIVE`: o dia é calculado no fuso do telefone na hora da consulta, então, com o telefone indo para leste depois da leitura, a última hora de um dia ainda não confirmado pode cair no dia seguinte com o acumulado do dia inteiro (ADR 1, "O que continua aberto").

**Janela da mescla** (sessão, `HistoryStore.mergeSteps`): amostras cujo dia, no fuso do telefone, é anterior a `hoje - historyRetentionDays` (30 no fitGPS) ou que são do futuro (dia depois de hoje **e** mais de 3 h à frente de agora) são descartadas antes de mesclar, em `refreshSteps` e em `syncHistory`, com uma linha `Session/mergeSteps` no log (WARN se alguma é do futuro). A fronteira antiga é a mesma da poda. Sem isso, um registro `mode 0` com a data errada viraria um dia pendente em `getStepsDays` que nunca sai: a poda não apaga o futuro e o `refreshSteps` não poda.

A folga de 3 h existe porque o relógio grava hora de parede e só é acertado no começo de cada conexão. Com o telefone já em `America/Manaus` às 23:30 e o relógio ainda em São Paulo, o registro ao vivo sai 00:30 (o relógio carimba o pedido, seção 6) e vira 00:00 de amanhã em Manaus, 30 min à frente: são passos reais, e o relógio não os devolve de novo se H2 valer. 3 h é a maior diferença entre fusos do Brasil; numa diferença maior (viagem internacional) a amostra ainda sai e se perde (`decisoes.md`, ADR 16, emenda; `WatchSessionCommandsTest.stepsFromAWatchStillInTheOldTimeZoneAreKeptAcrossMidnight`).

**Passos ao vivo**: não existe comando de passos atuais. Os dois Cores fazem polling de `05/41` e usam o registro mais recente (`SMAService.java:388-406`; iOS a cada 1 s). Aqui, "Refresh steps" é uma leitura de `05/41` sob demanda pela sessão.

## 3. Sono: formato

Ver a seção 1. O SMA não tem estágio REM; nenhum estágio REM é produzido.

## 4. Estimativas

O relógio só informa passos. Calorias e distância vêm das fórmulas da SDK (`SmaBleUtils.java:89-95`) com o usuário fixo do Core: **60 kg, 173,5 cm, gênero 1** (`SMAService.java:112-114`), constantes em `SmaEstimates`.

| Grandeza | Fórmula (em `Float`, na ordem da expressão Java) | Unidade |
|---|---|---|
| Calorias | `floor(k × peso × passos / 1000)`, `k = 0.55` (gênero ≠ 0) ou `0.46` (gênero 0) | kcal, inteiro |
| Distância | `altura × passos × 45 / 10000 / 1000` | **km** (passada = 45% da altura em cm) |
| Distância em metros | `distânciaKm × 1000` em `Float`, como `SMAService.java:864` (`SMADataPedometer.distance` é `float`) | m |

`getDistance` devolve quilômetros; o Core converte multiplicando por 1000 ao agregar o dia. Aqui a conversão acontece por amostra (`StepsSample.distanceMeters`). Exemplos tirados rodando o código Java da SDK: 1000 passos → 33 kcal e 780,75 m; 10 000 passos → 330 kcal e 7807,5 m (`SmaEstimatesTest`).

## 5. Sono: analisador (exploratório)

**Status**: exploratório. O fitGPS nunca teve sono consumido em produção: os dois Cores deixam `getHistorySleep` vazio (`SMAService.java:1423`; `SMAService.m:167`). As regras abaixo reproduzem a SDK, não um comportamento já observado no relógio. A validação depende de uma noite real (hipótese H4).

`SmaSleepAnalyzer.analyze(records, timeZoneId)`:

**Passo 1. Agrupamento por noite** (`SmaSleepAnalyzer.nights`). É uma decisão desta biblioteca: a SDK recebe a lista já recortada pelo app. Em ordem de tempo, uma noite termina no registro END (34) e o registro seguinte começa outra; uma lacuna de mais de 12 h entre dois registros também separa noites, para o caso de o relógio não gravar o END (12 h cabe uma noite inteira sem registro no meio e é menor que o intervalo entre acordar e dormir de novo). Não há corte por horário. A primeira versão cortava às 10:00 (o mesmo minuto que `getSleepStatus` usa para "virar o dia", `SmaSleep.java:100-106`), e quem acordava depois das 10:00 tinha o END jogado na noite seguinte: a noite certa perdia o fim e o `analyseSleeps` transformava o último registro antes das 10:00 em END. Sem agrupar, um lote com várias noites perderia todas menos a última, porque `analyseSleeps` descarta tudo o que vem antes de cada START. Consequência a conferir com uma noite real: quem acorda no meio da noite (END) e volta a dormir (START) gera duas sessões no mesmo `dayKey`, onde a SDK, com a lista inteira, ficaria só com a segunda.

**Passo 2. `analyseSleeps`**, port 1:1 de `SmaSleep.java:17-70`, sobre cópias (a SDK altera os próprios registros):

| Regra | Linhas |
|---|---|
| Menos de 2 registros → nada | `:18, 67-68` |
| Ordena por tempo, de forma estável | `:19`, `compareTo` em `:117-123` |
| START (17): limpa o resultado, insere LIGHT em `t`; o registro vira DEEP em `t + 15 min` | `:25-37` |
| END (34): só marca que houve despertar | `:38-39` |
| Senão, `strong > 2` → AWAKE em `t − 15 min` | `:40-42` |
| Senão, `soft > 2` → LIGHT em `t − 15 min` | `:43-45` |
| Os demais mantêm `mode` e `time` | — |
| O ponto só entra se o `mode` for diferente do último incluído | `:48-50` |
| Sem END e com mais de um ponto: o último vira END | `:53-55` |
| Sem START e com mais de um ponto: o primeiro vira LIGHT e um DEEP entra 15 min depois dele | `:57-63` |
| Ordena de novo | `:65` |

**Passo 3. Totais, `getSleepStatus`**, port 1:1 de `SmaSleep.java:72-115`:

- profundo = soma de `p[i+1] − p[i]` com `p[i].mode == 1`; leve = idem com `mode == 2`; os dois truncados para minutos no fim;
- duração = minuto do dia do último ponto − minuto do dia do primeiro, somando 1440 a quem estiver até 10:00 (minuto ≤ 600);
- acordado = duração − profundo − leve (não é a soma dos intervalos `mode == 3`).

`SleepSession.totalMinutes` = duração, `deepMinutes`, `lightMinutes`, `awakeMinutes` = acordado, `remMinutes` = 0. Quando a noite termina depois das 10:00 tendo começado antes (na véspera, ou no mesmo dia antes das 10:00), o corte da fórmula dá duração negativa; a SDK devolveria o número negativo (`SmaSleep.java:100-108`). Aqui, só nesse caso, a duração é o intervalo real entre o primeiro e o último ponto e o acordado é recalculado com ela (`SmaSleepAnalyzerTest.lateWakeUpStaysInItsNight`). Nos outros casos a fórmula da SDK vale igual.

**Passo 4. Estágios.** Cada par consecutivo `(p[i], p[i+1])` vira um `SleepStage` do tipo de `p[i].mode`: 1 → DEEP, 2 → LIGHT, qualquer outro (3, um END no meio da noite, valor desconhecido) → AWAKE, que é como `getSleepStatus` conta. Estágios de duração zero são descartados e vizinhos do mesmo tipo são unidos, para não haver sobreposição. Os instantes vêm da conversão do relógio de parede com o fuso do telefone; as strings ISO usam `TimeFormat.isoWithOffset`.

**Sessão.** `start` = primeiro ponto, `end` = último ponto, `dayKey` = dia local do fim (o dia em que a pessoa acordou). Uma noite sem nenhum estágio de duração positiva não gera sessão.

**Exemplo** (`SmaSleepAnalyzerTest.fullNightCoversEveryRule`): registros START 23:00, LIGHT 00:00, DEEP 01:00, DEEP com strong 5 às 03:00, DEEP com soft 4 às 04:00 e END 06:00 viram LIGHT 23:00, DEEP 23:15, LIGHT 00:00, DEEP 01:00, AWAKE 02:45, LIGHT 03:45, END 06:00; totais 420 min, profundo 150, leve 210, acordado 60.

## 6. Observado no fitGPS 0.2.1

Fonte: a gravação real `fixtures/real/fitgps/0.2.1/pair-commands-bt-reconnect-sync.jsonl` (Android, 2026-10-29, `recordedAt` 08:14:10 em São Paulo, truncado no segundo: o início real foi 933 ms depois). Os `atMs` são dos pacotes; os registros estão em hexadecimal como chegaram.

**SPORT (`05/41` → `05/42`)**, seis leituras:

| Pedido (`atMs`) | Resposta (`atMs`) | Registros (relógio de parede, mode, passos) |
|---|---|---|
| 16902 | 17228 | `2EC94B4410000000` 2024-11-14 23:59:00, 16, 0 · `3275BCD910000000` 2026-10-29 08:14:17, 16, 0 · `3275BCE300000000` 08:14:27, 0, 0 |
| 85176 | 85468 | `3275BD270000000E` 08:15:35, 0, 14 |
| 123451 | 123711 | `3275BD4E0000000E` 08:16:14, 0, 14 |
| 777379 | 777838 | `3275BFDC0000000E` 08:27:08, 0, 14 |
| 798315 | 798532 | `3275BFF100000038` 08:27:29, 0, 56 |
| 1031806 | 1032178 | `3275C08800000038` 08:30:00, 0, 56 · `3275C0DA00000038` 08:31:22, 0, 56 |

- **Cada resposta termina com um registro "ao vivo"**, carimbado no segundo do pedido (com até 1 s de diferença, a do acerto de hora): o pedido de `atMs` 1031806 saiu às 08:31:22,7 no telefone e o último registro é 08:31:22. Por isso toda leitura traz pelo menos um registro, mesmo sem passo novo.
- **`mode 16` (SIT, `SmaSport.java:75-121`) com 0 passos aparece depois do salto do relógio.** O relógio estava com a data de 2024-11-14 (o último registro de sono é de 2024-11-14 06:01) até o `02/01` de `atMs` 5891 acertá-lo para 2026-10-29 08:14:16; a primeira leitura trouxe um `mode 16` na hora antiga (23:59:00) e outro logo depois da hora nova (08:14:17). Só `mode 0` vira amostra, então os dois são ignorados (seção 1).
- **Os valores `mode 0` são totais, não incrementos**: 0, 14, 14, 14, 56, 56, 56. Eles se repetem em vez de somar e não zeram às 08:30 (o registro de 08:30:00 exato ainda tem 56). Com a regra "vale o último da hora" (seção 2, ADR 7), a hora 08 fica com 56.
- **Todos são da hora 08**: esta gravação sozinha não mostra a virada da hora. Quem decidiu foi a gravação da noite (abaixo): o valor não zera na virada da hora, é o acumulado do dia (H3 refutada, ADR 1).
- Nenhum registro lido reaparece numa leitura seguinte, nem depois de 230 s de Bluetooth desligado (H2 sugerida para SPORT; H1: avanço visto, gatilho aberto; `protocolo-sma.md`, seção 8).

**À noite** (`fixtures/real/fitgps/0.2.1/pair-commands-sync-bt-reconnect-disconnect-reconnect.jsonl`, 2026-10-29, `recordedAt` 21:37:08,691): as duas leituras de `05/41` trouxeram só o registro ao vivo, `3276792100000038` (21:37:37, mode 0, 56; `atMs` 29133) e `3276792B00000038` (21:37:47, mode 0, 56; `atMs` 38440). O relógio ficou parado o dia todo depois da manhã, e a tela mostrava 56 passos no dia (confirmado pelo usuário no aparelho). Um total por hora leria 0 às 21:37; 56, 13 h depois do último passo, é o acumulado do dia. Com as duas gravações no mesmo histórico (amostras de 08:00 e de 21:00, as duas com 56), `DAY_CUMULATIVE` dá 56 para o dia, igual à tela, e `HOURLY_SUM` daria 112 (`RealFixturesStepsTest`). A sessão da noite começou com BIND (`03/01` em `atMs` 0, `03/02 [00]` em 1899), e os dois registros ao vivo depois dele ainda têm 56: no 0.2.1, o BIND não zera o contador do dia. Se houve um `03/05` (Forget) nas sessões não gravadas antes dela (`pendencias.md`, bloco 1), ele também não zerou. Nenhum registro das horas entre 08:31 e 21:37 apareceu, mas houve sessões não gravadas no meio (o seq do relógio pulou 12 frames, `fixtures.md`, seção 5), então isso não diz se o relógio grava instantâneos em hh:00 e hh:30 sozinho.

**SLEEP (`05/45` → `05/46`)**, uma leitura (pedidos de `atMs` 1032538 a 1090312):

- 64 páginas, 63 de 20 registros e a última de 2: 1262 registros de 2023-08-11 a 2024-11-14, sem byte sobrando. São dados antigos guardados no relógio, porque o Core de produção nunca pede `05/45` (`SMAService.java:561, 573, 591`; `SMAService.m:1077-1080`).
- O conteúdo não parece sono de verdade: 630 START, 629 END e 3 DEEP; o mesmo START de 2023-10-20 12:33:00 se repete 554 vezes, e as páginas alternam um START repetido com ENDs de 2 em 2 min. A hipótese é um relógio parado gravando artefatos, a confirmar com a noite T4 (`pendencias.md`).
- O analisador produziu 4 sessões, todas podadas no fim do sync por terem mais de 30 dias. Nenhuma noite real passou por ele (H4: formato provado, conteúdo não validado).

## 7. O que falta confirmar com o relógio

O roteiro de cada item, com o critério e onde registrar o resultado, está em [`pendencias.md`](pendencias.md).

- A virada da meia-noite (T5): quando e como o acumulado do dia zera, e se o registro de 00:00:00 ou o registro ao vivo de logo depois da meia-noite ainda traz o total de ontem. Se trouxer, com `DAY_CUMULATIVE` hoje mostra o total de ontem até a contagem de hoje passá-lo, e só outro registro da hora 00 o corrige (ADR 1, "O que continua aberto"). O H3 em si está fechado para o firmware 0.2.1 (acumulado do dia, seção 6 e ADR 1); falta também ver como a hora fecha (registro em hh:00:00 ou em hh:59:xx) e se os registros de hh:00 e hh:30 são instantâneos que o relógio grava sozinho.
- Se o contador zera no meio do dia sem Forget (relógio reiniciado ou com a bateria esgotada). Se zerar, com `DAY_CUMULATIVE` o dia vira o maior entre o pico de antes do zero e a contagem nova (ADR 1, "O que continua aberto"). O BIND não zera (seção 6).
- H2 para o sono: os registros são apagados depois de lidos. No SPORT ela está sugerida; o sono foi lido uma vez só.
- O gatilho do H1: o cursor avança com o nosso ACK ou com o pedido seguinte. O avanço foi visto nos dois (os registros de passos não voltam, as páginas de sono se encadeiam), o gatilho não (`pendencias.md`, item técnico 5).
- H4: o conteúdo do `05/46` numa noite real. Se o fitGPS responder vazio sempre, investigar a variante legada `05/03` do iOS (`SmaBLE.m:2837-2862`).
- Como o relógio grava horários na mudança de horário de verão (os timestamps são de parede; a hora que se repete cai no mesmo balde).
