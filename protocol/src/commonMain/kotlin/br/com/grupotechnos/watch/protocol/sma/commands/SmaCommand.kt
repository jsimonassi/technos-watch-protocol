package br.com.grupotechnos.watch.protocol.sma.commands

import br.com.grupotechnos.watch.protocol.sma.codec.SmaFrameCodec

/** Famílias de `cmd` do L2 (`SmaManager.Cmd`, `SmaManager.java:1617-1625`). */
internal object SmaCmd {
	/** OTA, XMODEM e watchfaces. Fora do escopo. */
	const val UPDATE: Int = 0x01

	/** Ajustes e leituras de configuração (hora, bateria, versão...). */
	const val SET: Int = 0x02

	/** Vínculo: BIND, LOGIN, UNBIND. É a única família aceita antes do LOGIN (`SmaManager.java:1245`). */
	const val CONNECT: Int = 0x03

	/** Avisos do relógio e notificações do telefone. */
	const val NOTICE: Int = 0x04

	/** Histórico (passos, sono, frequência cardíaca...). */
	const val DATA: Int = 0x05

	/** Controles remotos (câmera, música). Fora do escopo. */
	const val CONTROL: Int = 0x07
}

/** Sentido de um comando no link. */
internal enum class SmaDirection {
	/** O telefone envia; o relógio responde com ACK e, nos GETs, com um frame `key + 1`. */
	PHONE_TO_WATCH,

	/** O relógio envia por conta própria; o telefone só responde ACK. */
	WATCH_TO_PHONE,
}

/** Como o driver trata um comando (regras de espera e retentativa, `docs/protocolo-sma.md`, seções 5 e 8). */
internal enum class SmaCommandKind {
	/** BIND e LOGIN: esperam resposta `key + 1`; uma retentativa em timeout. */
	HANDSHAKE,

	/** Ajuste: espera só o ACK; uma retentativa em timeout. */
	SET,

	/** Leitura: espera ACK e resposta `key + 1`; sem retentativa (pode pular uma página de histórico). */
	GET,

	/** Aviso do relógio. */
	NOTICE,
}

/** Situação do comando nesta PoC. */
internal enum class SmaCommandStatus {
	/** Implementado e enviado pelo driver. */
	SENT,

	/** Aviso recebido e convertido em `DriverNotice`. */
	NOTICE_HANDLED,

	/** Só codec e teste; o driver não envia. */
	CODEC_ONLY,

	/** Catalogado, mas não enviado porque o payload não pôde ser confirmado nas SDKs. */
	NOT_SENT,
}

/**
 * Catálogo dos comandos SMA que esta biblioteca conhece. A tabela completa, com payloads e fontes, está em
 * `docs/protocolo-sma.md`, seção 6; mudar um byte aqui exige atualizar a doc e o vetor dourado no mesmo PR.
 *
 * @property cmd família (L2 byte 0).
 * @property key comando (L2 byte 2).
 * @property responseKey key da resposta (`key + 1`) nos comandos que têm resposta de dados.
 */
internal enum class SmaCommand(
	val cmd: Int,
	val key: Int,
	val direction: SmaDirection,
	val kind: SmaCommandKind,
	val responseKey: Int?,
	val status: SmaCommandStatus,
) {
	/**
	 * `03/01` BIND, 32 bytes ([SmaBindPayload]); resposta `03/02` com `value[0] == 0` em sucesso.
	 * Fontes: `SmaManager.java:436-442, 725-735`; `SmaBLE.m:448-455, 559-607, 285-293`.
	 */
	BIND(SmaCmd.CONNECT, 0x01, SmaDirection.PHONE_TO_WATCH, SmaCommandKind.HANDSHAKE, 0x02, SmaCommandStatus.SENT),

	/**
	 * `03/03` LOGIN, mesmo payload do BIND; resposta `03/04`. Fontes: `SmaManager.java:444-449, 736-746`;
	 * `SmaBLE.m:470-476, 294-301`.
	 */
	LOGIN(SmaCmd.CONNECT, 0x03, SmaDirection.PHONE_TO_WATCH, SmaCommandKind.HANDSHAKE, 0x04, SmaCommandStatus.SENT),

	/**
	 * `03/05` UNBIND, value `[00]`, sem resposta de dados. Fonte: `SmaManager.java:415-417`. O iOS
	 * (`SmaBLE.m:458-467`) monta sem value e envia 20 bytes de um buffer de 14, lendo lixo; seguimos o Android.
	 */
	UNBIND(SmaCmd.CONNECT, 0x05, SmaDirection.PHONE_TO_WATCH, SmaCommandKind.SET, null, SmaCommandStatus.SENT),

	/** `02/01` SET_TIME, 4 bytes ([SmaTimeCodec]). Fontes: `SmaTime.java:26-33`; `SmaBLE.m:524-556`. */
	SET_TIME(SmaCmd.SET, 0x01, SmaDirection.PHONE_TO_WATCH, SmaCommandKind.SET, null, SmaCommandStatus.SENT),

	/** `02/08` BATTERY; resposta `02/09` com `value[0]` = percentual. Fontes: `SmaManager.java:652-658`; `SmaBLE.m:331-336, 1986-1995`. */
	BATTERY(SmaCmd.SET, 0x08, SmaDirection.PHONE_TO_WATCH, SmaCommandKind.GET, 0x09, SmaCommandStatus.SENT),

	/**
	 * `02/0A` VERSION; resposta `02/0B` com firmware em `value[0..2]` e versão BLE em `value[3..5]`.
	 * Fontes: `SmaManager.java:641-651`; `SmaBLE.m:325-330, 1998-2007`.
	 */
	VERSION(SmaCmd.SET, 0x0A, SmaDirection.PHONE_TO_WATCH, SmaCommandKind.GET, 0x0B, SmaCommandStatus.SENT),

	/**
	 * `05/41` SPORT; resposta `05/42` com até 20 registros de 8 bytes. Fontes: `SmaManager.java:783-814, 1444-1450`;
	 * `SmaBLE.m:1758-1768, 2672-2716`.
	 */
	SPORT(SmaCmd.DATA, 0x41, SmaDirection.PHONE_TO_WATCH, SmaCommandKind.GET, 0x42, SmaCommandStatus.SENT),

	/**
	 * `05/45` SLEEP; resposta `05/46` com até 20 registros de 7 bytes. Fontes: `SmaManager.java:815-840`;
	 * `SmaBLE.m:1771-1780, 2899-2945`.
	 */
	SLEEP(SmaCmd.DATA, 0x45, SmaDirection.PHONE_TO_WATCH, SmaCommandKind.GET, 0x46, SmaCommandStatus.SENT),

	/** `02/10` USER_INFO, 4 bytes ([SmaUserInfoCodec]). Fontes: `SmaUserInfo.java:9-16`; `SmaBLE.m:495-521, 2210-2229`. */
	USER_INFO(SmaCmd.SET, 0x10, SmaDirection.PHONE_TO_WATCH, SmaCommandKind.SET, null, SmaCommandStatus.CODEC_ONLY),

	/** `02/68` TIMEZONE, 1 byte com sinal ([SmaTimezoneCodec]). Fontes: `SmaTimezone.java:6-21`; `SmaBLE.m:3601-3620`. */
	TIMEZONE(SmaCmd.SET, 0x68, SmaDirection.PHONE_TO_WATCH, SmaCommandKind.SET, null, SmaCommandStatus.CODEC_ONLY),

	/**
	 * `02/6F` SET_SYNC_SWITCH. Só a constante existe na SDK Android (`SmaManager.java:1730`); nenhuma das SDKs nem
	 * o Core monta esse comando, então o payload é desconhecido e o driver não envia. O `Syncdata:` do iOS
	 * (`SmaBLE.m:1076-1090`) é outro comando (`05/06`, relógios legados "02/04/05").
	 */
	SYNC_SWITCH(SmaCmd.SET, 0x6F, SmaDirection.PHONE_TO_WATCH, SmaCommandKind.SET, null, SmaCommandStatus.NOT_SENT),

	/** `02/70` SET_SYNC_DATA_BACK: o relógio avisa que os dados mudaram (`value[0]` = flag). Fonte: `SmaManager.java:709-715, 1731`. */
	DATA_CHANGED(SmaCmd.SET, 0x70, SmaDirection.WATCH_TO_PHONE, SmaCommandKind.NOTICE, null, SmaCommandStatus.NOTICE_HANDLED),

	/** `04/62` BLE_CONNECTION: `value[0] == 0` = conectado. Fonte: `SmaManager.java:755-760, 1710`. */
	BLE_CONNECTION(SmaCmd.NOTICE, 0x62, SmaDirection.WATCH_TO_PHONE, SmaCommandKind.NOTICE, null, SmaCommandStatus.NOTICE_HANDLED),
	;

	companion object {
		/** Comando cujo `cmd/key` é exatamente este, ou `null`. */
		fun byCmdKey(cmd: Int, key: Int): SmaCommand? = entries.firstOrNull { it.cmd == cmd && it.key == key }

		/** Comando cuja resposta tem este `cmd/key`, ou `null`. */
		fun byResponse(cmd: Int, key: Int): SmaCommand? = entries.firstOrNull { it.cmd == cmd && it.responseKey == key }

		/**
		 * Nome legível para log: o nome do comando (`"SET_TIME"`), o da resposta (`"BATTERY_BACK"`, mesma convenção
		 * dos logs da SDK Android) ou `null` quando o `cmd/key` não está no catálogo.
		 */
		fun nameOf(cmd: Int, key: Int): String? =
			byCmdKey(cmd, key)?.name ?: byResponse(cmd, key)?.let { it.name + "_BACK" }

		/** `"02/01 SET_TIME"` ou só `"02/99"` para o que não está no catálogo. */
		fun label(cmd: Int, key: Int): String {
			val name = nameOf(cmd, key)
			val cmdKey = SmaFrameCodec.cmdKey(cmd, key)
			return if (name == null) cmdKey else "$cmdKey $name"
		}
	}
}
