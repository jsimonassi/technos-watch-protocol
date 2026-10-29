# `sma/data/` — histórico de passos e sono

**Propósito.** Decodificar os registros `05/42` (atividade) e `05/46` (sono) e transformá-los em `StepsSample` e `SleepSession` do domínio. As regras e as fontes estão em `docs/sma-dados.md`.

**Tipos principais**

| Tipo | Papel |
|---|---|
| `SmaLocalTime` | época do SMA: segundos desde 2000-01-01 00:00 no horário local de parede |
| `SmaSportRecord`, `SmaSleepRecord` | registros crus de 8 e 7 bytes |
| `SmaSportMode`, `SmaSleepMode` | valores de `mode` (`SmaSport.Mode`, `SmaSleep.Mode`) |
| `SmaRecordCodec` | decodificação dos values, `MAX_RECORDS_PER_PAGE = 20` |
| `SmaStepsMapper` | só `mode 0`, balde da hora local, último na ordem de chegada vence, estimativas por amostra |
| `SmaSleepAnalyzer` | agrupamento por noite (fecha no END e em lacunas de mais de 12 h), port 1:1 de `analyseSleeps` e `getSleepStatus`, estágios sem sobreposição |

**Status.** Passos: comportamento do Core em produção. Sono: **exploratório**, nunca validado com o fitGPS.

**Fica de fora.** Paginação e pedidos (`SmaDriver`), agregação diária e persistência (sessão), registros de exercício (`mode` 32/47) e todas as outras famílias de histórico (HR, pressão, ciclismo, sport V2).

**Dependências.** `domain/`, `sma/SmaEstimates`, `util/` (`ByteReader`, `TimeFormat`), `kotlinx-datetime`. Quem depende: `SmaDriver` e os testes.

**Invariantes**

- Funções puras: o fuso entra como parâmetro (`timeZoneId`), nada lê o relógio do sistema.
- A entrada do analisador nunca é alterada (a SDK altera os próprios registros; o port trabalha em cópias).
- `StepsSample` sai ordenado por instante; `SleepSession` também, cada uma com estágios contíguos e sem duração zero.
- Nenhum estágio REM é produzido.

**Testes.** `SmaDataTest` (`SmaRecordDecodingTest` com fixtures hex, `SmaStepsMapperTest` com horário de verão, `SmaEstimatesTest` com números tirados do Java da SDK) e `SmaSleepAnalyzerTest` (START, END, strong > 2, soft > 2, colapso, sem START/END, agrupamento por noite).
