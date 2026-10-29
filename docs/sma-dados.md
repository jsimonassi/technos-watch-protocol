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

**Agregação diária (`Quirks.stepsAggregation`)**: a sessão soma as amostras de um dia. O padrão do fitGPS é `HOURLY_SUM`, ou seja, cada amostra horária é o total daquela hora e o total do dia é a soma. É o que o Core Android faz hoje em `getHistoryPedometer`: ordena as amostras horárias e soma `pedometer`, `calories` e `distance * 1000` por dia (`SMAService.java:856-866`). Se o fixture real mostrar que `mode 0` é um acumulado do dia (hipótese H3), o perfil passa a `DAY_CUMULATIVE` sem mudar o driver. A indicação contrária vem do próprio Core: ele calcula atividade por diferença de passos entre START e END (`SMAService.java:426`), o que sugere contador acumulado nos registros de exercício; isso não foi confirmado para `mode 0`.

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

## 6. O que falta confirmar com o relógio

- H3: `mode 0` é o total da hora ou o acumulado do dia (decide `stepsAggregation`).
- H1/H2: o cursor avança com o ACK e os registros são apagados depois de lidos (decide se a sessão pode confiar em reler).
- H4: o fitGPS responde `05/45`; se responder vazio sempre, investigar a variante legada `05/03` do iOS (`SmaBLE.m:2837-2862`).
- Como o relógio grava horários na mudança de horário de verão (os timestamps são de parede; a hora que se repete cai no mesmo balde).
