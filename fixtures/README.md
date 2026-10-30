# `fixtures/` — gravações de tráfego para replay

Formato, como gravar, convenção de pastas, regras de máscara do replay e a procedência de cada arquivo: [`docs/fixtures.md`](../docs/fixtures.md).

| Pasta | Conteúdo |
|---|---|
| `synthetic/` | gravações geradas pelo codec (`SyntheticFitGpsFixture`, no `commonTest`); não editar à mão, regenerar com `FIXTURES_REGENERATE=1 ./gradlew :protocol:jvmTest --tests '*SmaSessionReplayTest.committedFixtureMatchesTheGenerator'` |
| `real/<perfil>/<firmware>/<cenário>.jsonl` | gravações do relógio de verdade; cada uma vira teste automaticamente nos quatro targets, JVM, unit Android debug e release e simulador iOS (`RealFixturesReplayTest` para o SMA e `IdoRealFixturesReplayTest` para o iDO, uma linha `REPLAY` por arquivo no XML de cada tarefa). Hoje: duas do fitGPS em `real/fitgps/0.2.1/` e `real/lifeultragps/1.1.1/pair-commands.jsonl`, esta reconstruída do log de bytes da SDK iDO, não do rádio (`docs/fixtures.md`, seção 5). Os exports do app de exemplo são cumulativos: versione só o último de cada sessão. Os `logs-*.jsonl` têm o MAC do relógio e nunca entram aqui |
| `tools/` | entrada de exemplo do `scripts/hci2jsonl.py` e a saída esperada (`Hci2JsonlTest`) |
