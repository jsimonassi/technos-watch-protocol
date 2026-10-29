# `fixtures/` — gravações de tráfego para replay

Formato, como gravar, convenção de pastas, regras de máscara do replay e a procedência de cada arquivo: [`docs/fixtures.md`](../docs/fixtures.md).

| Pasta | Conteúdo |
|---|---|
| `synthetic/` | gravações geradas pelo codec (`SyntheticFitGpsFixture`); não editar à mão, regenerar com `FIXTURES_REGENERATE=1 ./gradlew :protocol:jvmTest` |
| `real/<perfil>/<firmware>/<cenário>.jsonl` | gravações do relógio de verdade; cada uma vira teste automaticamente (`RealFixturesReplayTest`) |
| `tools/` | entrada de exemplo do `scripts/hci2jsonl.py` e a saída esperada (`Hci2JsonlTest`) |
