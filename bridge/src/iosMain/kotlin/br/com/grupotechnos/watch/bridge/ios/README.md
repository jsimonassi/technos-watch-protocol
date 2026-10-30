# `ios/` (só `iosMain`) — payloads como objetos Foundation

**Propósito.** O host iOS entrega ao React Native `NSDictionary`/`NSArray` (`emitOnX:`, `resolve(...)`), e o RN
decide `true`/`false` ou número pela classe do `NSNumber` (`[value isKindOfClass:[@YES class]]`, isto é, ser o
`__NSCFBoolean`). `FoundationConverter` transforma os payloads do `EventMapper` em objetos Foundation de verdade, com
os booleanos como `CFBoolean`, para o JavaScript receber `charging: false`, e não `charging: 0`.

**Não faz.** Não emite evento nem resolve Promise (isso é do host Swift/ObjC++), não troca de thread, não guarda nada.

## Tipos e nomes no Swift

| Kotlin | Swift | Objective-C |
|---|---|---|
| `FoundationConverter.toDictionary(payload)` | `FoundationConverter.shared.toDictionary(payload: map)` → `[AnyHashable: Any]` | `[TWPFoundationConverter.shared toDictionaryPayload:map]` |
| `FoundationConverter.toArray(values)` | `FoundationConverter.shared.toArray(values: list)` → `[Any]` | `[TWPFoundationConverter.shared toArrayValues:list]` |

Regras de tipo (as do `WritableConverter` do Android): `null` → `kCFNull`; `String` → `NSString`; `Boolean` →
`kCFBooleanTrue`/`kCFBooleanFalse`; `Int` → `CFNumber` de 32 bits; `Long`, `Double`, `Float` e outros números →
`CFNumber` de 64 bits em ponto flutuante; `Map` → `NSDictionary` (chave com `toString()`); `List` → `NSArray`; outro
tipo → `NSString` do `toString()`.

**Valor solto** (o `isConnected`): `FoundationConverter.shared.toArray(values: [value])[0]` no Swift,
`[[TWPFoundationConverter.shared toArrayValues:@[value]] firstObject]` no Objective-C. Não existe um `toObject`: um
`Boolean` que sai de uma função Kotlin chega ao Objective-C sempre como `KotlinBoolean`.

**Com o `CoreBridge`.** O host iOS converte em dois lugares, e só neles: a `CoreCompletion` passa todo valor diferente
de `nil` por `toArray(values: [value])[0]` antes do `resolve` (o `Boolean` do `isConnected`, o mapa do
`getLastPairedDevice`, a lista do `getLogs`, a `String` do `exportLogs`), e o `CoreEventSink` passa cada payload por
`toDictionary(payload:)` antes do `emitOnX:`. As duas receitas estão escritas e compiladas em
`bridge/swift-interop/MinimalHost.swift` (`MinimalCompletion`, `MinimalEventSink`) e em
`bridge/objc-interop/MinimalTurboModule.mm` (`ShimCompletion`, `emitName:payload:`).

## Por que Core Foundation, e não `NSNumber.numberWithBool`

O Kotlin/Native converte toda referência Objective-C que entra no Kotlin, mesmo no retorno tipado de um método:
`NSNumber.numberWithBool(true)` é um `kotlin.Boolean` dentro do Kotlin e volta para o Objective-C como `KotlinBoolean`
(conferido no simulador: o `CFGetTypeID` dá `CFNumber`, e não `CFBoolean`; `NSMutableArray()` vira
`NSMutableArrayAsKMutableList`). A primeira versão do conversor fazia isso e os testes a pegaram. Por isso os
contêineres são criados e preenchidos com ponteiros crus (`CFDictionaryCreateMutable`, `CFDictionarySetValue`,
`CFArrayAppendValue`, `CFNumberCreate`, as constantes `kCFBoolean*` e `kCFNull`), e só o contêiner de fora passa pelo
Kotlin, que devolve ao Objective-C o mesmo objeto.

## Depende de

`platform.CoreFoundation`, `platform.Foundation`, `kotlinx.cinterop`. Nada do código comum depende daqui.

## Invariantes

- Todo booleano, em qualquer nível de aninhamento, é `CFBoolean`; todo número é `CFNumber`; `null` é `kCFNull` e a
  chave continua no dicionário (o JS recebe `null`, não `undefined`).
- Cada chamada cria contêineres novos (cópia profunda); pode ser chamada de qualquer thread.

## Testes

`iosTest/.../ios/FoundationConverterTest` (`iosSimulatorArm64Test`). O tipo de cada valor é lido pelo Core Foundation
(`CFDictionaryGetValue`/`CFArrayGetValueAtIndex` devolvem o ponteiro guardado), e não com `objectForKey`, que traria o
valor de volta pelo Kotlin/Native e mediria o objeto recriado. Cobre: booleanos são `CFBoolean` e 1/0 continuam números;
um `Map` do Kotlin entregue sem conversão não tem `CFBoolean` (o controle que justifica o conversor); `Int`, `Long`,
`Double` e `Float` com os valores certos; `String` e `null`; aninhamento de mapas e listas; contêineres vazios; o valor
solto; tipos inesperados como texto; payloads reais do `EventMapper`.
