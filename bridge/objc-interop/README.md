# `objc-interop/` — TurboModule mínimo em Objective-C++ para o portão de interoperabilidade

**Propósito.** `MinimalTurboModule.mm` tem a forma do `RCTNativeCoreNext.mm` que o host iOS do
`react-native-technos-core-next` vai ter, e é compilado contra o framework `TechnosWatchProtocol` do jeito que o
Objective-C++ o enxerga. O shim do TurboModule é Objective-C++ (o codegen do React Native gera C++), chama o
`TWPCoreBridge` direto e não tem regra própria: este arquivo prova que isso compila.

**O que é conferido.** Só o que o arquivo usa:

- `ShimCompletion`, um `TWPCoreCompletion` sobre o par resolve/reject do React Native (os tipos de
  `React/RCTBridgeModule.h` estão copiados no arquivo): `resolveValue:` passa todo valor diferente de `nil` por
  `[[TWPFoundationConverter.shared toArrayValues:@[value]] firstObject]` (o `KotlinBoolean` do `isConnected` vira
  `CFBoolean`), e `rejectCode:message:` chama o reject;
- o módulo como `TWPCoreEventSink` (`canEmit`, `emitName:payload:`), com o payload convertido por
  `[TWPFoundationConverter.shared toDictionaryPayload:]` e entregue ao `emitOnX:` do nome (os `emitOnX:` do
  `NativeCoreNextSpecBase` estão num stub), e os 13 nomes de evento numa tabela C++. O stub tem a forma do codegen do
  RN 0.81: o ivar protegido `_eventEmitterCallback` (o `std::function` de `facebook::react::EventEmitterCallback`), e o
  `canEmit` devolve `static_cast<bool>(_eventEmitterCallback)`, como o shim de verdade fará;
- o ciclo de vida: `attachSink:` na primeira chamada de um método JS, `invalidateSink:` no `invalidate`, `onHostResume`
  ao voltar ao primeiro plano;
- cada um dos 23 métodos do spec (`src/specs/NativeCoreNext.ts`), com a assinatura que o codegen gera, chamando o
  seletor de mesmo nome do `TWPCoreBridge` (`pairDeviceDeviceId:name:completion:`, `getHistoryPedometerDaysBefore:completion:`,
  `deletedSavedDataFilteredArrayData:category:`, `setLogLevelLevel:`…). O `initSMASDK:reject:` chama
  `doInitSMASDKCompletion:`: o Kotlin/Native renomeia os seletores da família `init` do Objective-C.

O arquivo não roda nem liga. A tarefa `./gradlew :bridge:checkObjcInterop` (grupo `verification`, ligada ao `check`, só
em macOS) liga o framework de debug do `iosSimulatorArm64` e roda:

```
xcrun --sdk iphonesimulator clang++ -fsyntax-only -fobjc-arc -fmodules -std=c++20 -Wall -Wextra -Werror \
	-target arm64-apple-ios15.1-simulator -F bridge/build/bin/iosSimulatorArm64/debugFramework \
	-fmodules-cache-path=bridge/build/tmp/checkObjcInterop/ModuleCache bridge/objc-interop/MinimalTurboModule.mm
```

A saída do `clang++` fica em `bridge/build/reports/objcInterop/clang.txt`.

**Por que existe.** O `checkSwiftInterop` confere os nomes do lado Swift (`swift_name`); o shim vê os seletores
Objective-C, que mudam por outra regra (o primeiro parâmetro entra no nome: `getHistoryPedometer(daysBefore:)` é
`getHistoryPedometerDaysBefore:`). Com ARC, chamar um seletor que não existe mais no `TWPCoreBridge` é erro ("no visible
@interface ... declares the selector"), e um método de protocolo renomeado deixa o `ShimCompletion` sem implementá-lo
(aviso que o `-Werror` transforma em erro). As duas coisas foram conferidas tirando um rótulo de propósito
(`docs/decisoes.md`, ADR 21).

**Inputs da tarefa.** Os `.mm` desta pasta, o `Headers/` e o `Modules/` do framework e as opções do compilador. Mudou a
API pública, o arquivo ou as opções, a tarefa roda de novo.

**Ao mudar a API pública.** Mudou um nome ou rótulo do `CoreBridge`, do `CoreCompletion` ou do `CoreEventSink`: ajuste
este arquivo e o `MinimalHost.swift` na mesma alteração (e o `RCTNativeCoreNext.mm` do Repo 2). Use o seletor que o
header gerado mostra (`bridge/build/bin/iosSimulatorArm64/debugFramework/TechnosWatchProtocol.framework/Headers/TechnosWatchProtocol.h`).

**Fica de fora.** O React Native (os tipos são cópias), o codegen, o `CoreNext-Swift.h` e o comportamento (o
`CoreBridge` é testado em Kotlin, inclusive no simulador).
