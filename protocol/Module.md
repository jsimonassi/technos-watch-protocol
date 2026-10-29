# Module technos-watch-protocol

API pública do protocolo dos relógios Technos/Mormaii em Kotlin Multiplatform (Android, JVM e iOS).

O host (o Core React Native, ou um app nativo) implementa três interfaces e conversa com uma fachada:

- [br.com.grupotechnos.watch.protocol.transport.Transport]: o BLE da plataforma (`BluetoothGatt`, `CoreBluetooth`);
  o host chama de volta [br.com.grupotechnos.watch.protocol.transport.TransportEvents].
- [br.com.grupotechnos.watch.protocol.log.WatchLogger]: log e cada pacote GATT cru.
- [br.com.grupotechnos.watch.protocol.session.SampleStore]: persistência do histórico lido do relógio.
- [br.com.grupotechnos.watch.protocol.session.WatchSession]: a fachada (parear, reconectar, comandos, sincronização);
  os resultados chegam por [br.com.grupotechnos.watch.protocol.session.WatchSessionListener].

Tudo o que é protocolo de fabricante (frame, CRC, handshake, catálogo de comandos do SMA) é `internal` e não aparece
aqui. A API não usa `suspend`, `Flow` nem tipos do `kotlinx-datetime`, para o Swift consumir o framework sem shim
(a tarefa `checkObjcHeader` confere o header gerado).

Documentação de desenho no repositório: `docs/arquitetura.md` (camadas, threading, máquina de estados, erros),
`docs/protocolo-sma.md` (protocolo byte a byte), `docs/sma-dados.md`, `docs/fixtures.md`, `docs/decisoes.md`,
`docs/pendencias.md` (validação no relógio que ficou para depois) e `docs/glossario.md`.

# Package br.com.grupotechnos.watch.protocol.domain

Tipos puros que atravessam a API: estado da conexão, identificação, bateria, passos, sono e os códigos de erro
estáveis. Datas são epoch millis mais strings ISO-8601 com offset; nada depende de plataforma.

# Package br.com.grupotechnos.watch.protocol.transport

A fronteira com o BLE do host: `Transport` (o host implementa), `TransportEvents` (o host chama), tipos de descoberta
GATT, status e normalização de UUIDs. Só bytes, UUIDs e status; nenhum protocolo de fabricante.

# Package br.com.grupotechnos.watch.protocol.profile

Os modelos suportados como dados: nomes de advertising, serviços GATT em ordem de prioridade e quirks (timeouts, MTU,
payload do BIND, agregação de passos, retenção). `DeviceRegistry` identifica o modelo a partir do scan.

# Package br.com.grupotechnos.watch.protocol.session

A fachada `WatchSession`, o listener de saída, a persistência (`SampleStore`) e o relógio do telefone
(`WatchClock`). A máquina de estados, a fila de comandos e a sincronização são internas.

# Package br.com.grupotechnos.watch.protocol.driver

Só `HostDriver`, a interface vazia reservada para adaptadores sobre SDKs de fabricante. O contrato dos drivers
internos (`DeviceDriver`, `DriverLink`) não é público.

# Package br.com.grupotechnos.watch.protocol.log

`WatchLogger`, que o host implementa, e `FrameJsonl`, o formato das gravações de tráfego usadas como fixtures.
