#!/usr/bin/env python3
"""Converte uma captura HCI (btsnoop) exportada pelo Wireshark em uma gravação .jsonl do technos-watch-protocol.

Entrada: o arquivo de "File > Export Packet Dissections > As JSON..." do Wireshark, aberto sobre o
btsnoop_hci.log do Android (Opções do desenvolvedor > Ativar registro de snoop HCI do Bluetooth).

Saída: o mesmo formato de log/FrameJsonl.kt (docs/fixtures.md): uma linha de cabeçalho e uma linha por pacote GATT.
- TX: ATT Write Request (0x12) ou Write Command (0x52) na handle da characteristic de escrita.
- RX: ATT Handle Value Notification (0x1b) ou Indication (0x1d) da handle da characteristic de notificação.
Todo o resto (CCCD, descoberta, MTU, respostas de escrita, outros serviços) é ignorado.

As handles vêm de --write-handle/--notify-handle ou são detectadas pelo UUID quando a descoberta de serviços está na
captura (Read By Type Response com as declarações de characteristic) ou quando o Wireshark anotou o UUID em cada
pacote (btatt.handle_tree). A ordem de preferência é a mesma da sessão: FF60 (FF61/FF62) antes do NUS.

Só biblioteca padrão. Rode isolado: python3 -I scripts/hci2jsonl.py captura.json -o saida.jsonl
"""

import argparse
import datetime
import json
import sys

NUS_WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
NUS_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
ROUND_WRITE = "0000ff61" + BASE_SUFFIX
ROUND_NOTIFY = "0000ff62" + BASE_SUFFIX

# Pares (escrita, notificação) na ordem de prioridade do perfil fitGPS (profile/Profiles.kt).
DEFAULT_CANDIDATES = [(ROUND_WRITE, ROUND_NOTIFY), (NUS_WRITE, NUS_NOTIFY)]

OP_MTU_REQUEST = 0x02
OP_MTU_RESPONSE = 0x03
OP_WRITE_REQUEST = 0x12
OP_WRITE_COMMAND = 0x52
OP_NOTIFICATION = 0x1B
OP_INDICATION = 0x1D
OP_PREPARE_WRITE = 0x16

UUID_FIELDS = (
	"btatt.characteristic_uuid128",
	"btatt.characteristic_uuid16",
	"btatt.uuid128",
	"btatt.uuid16",
)


def merge_duplicates(pairs):
	"""O JSON do Wireshark repete chaves no mesmo objeto; guarda todas as ocorrências numa lista."""
	result = {}
	for key, value in pairs:
		if key in result:
			previous = result[key]
			if isinstance(previous, list) and getattr(previous, "_dup", False):
				previous.append(value)
			else:
				merged = _DupList([previous, value])
				result[key] = merged
		else:
			result[key] = value
	return result


class _DupList(list):
	_dup = True


def first(value):
	"""Primeira ocorrência de um campo que pode ter vindo repetido."""
	if isinstance(value, _DupList):
		return value[0] if value else None
	return value


def parse_int(text):
	if text is None:
		return None
	text = str(first(text)).strip()
	try:
		return int(text, 16) if text.lower().startswith("0x") else int(text)
	except ValueError:
		return None


def normalize_uuid(text):
	"""Aceita "6e:40:00:02:...", "6E400002-...", "0xff61" ou "ff61" e devolve o UUID de 128 bits minúsculo."""
	if text is None:
		return None
	clean = str(first(text)).strip().lower()
	if clean.startswith("0x"):
		clean = clean[2:]
	clean = clean.replace(":", "").replace("-", "")
	if len(clean) == 4:
		return "0000" + clean + BASE_SUFFIX
	if len(clean) == 8:
		return clean + BASE_SUFFIX
	if len(clean) == 32:
		return "-".join([clean[0:8], clean[8:12], clean[12:16], clean[16:20], clean[20:32]])
	return None


def normalize_hex(text):
	return str(first(text)).replace(":", "").replace(" ", "").upper()


def walk(node):
	"""Todos os dicionários aninhados (inclusive dentro de listas)."""
	if isinstance(node, dict):
		yield node
		for value in node.values():
			yield from walk(value)
	elif isinstance(node, list):
		for value in node:
			yield from walk(value)


def layers_of(packet):
	source = packet.get("_source", packet)
	return source.get("layers", source)


def packet_time(layers):
	"""Instante do pacote em segundos epoch (float), ou None."""
	frame = layers.get("frame", {})
	epoch = first(frame.get("frame.time_epoch"))
	if epoch is None:
		return None
	text = str(epoch).strip()
	try:
		return float(text)
	except ValueError:
		pass
	# Versões mais novas do Wireshark escrevem ISO-8601 em UTC ("2026-10-28T17:30:00.123456000Z").
	if text.endswith("Z"):
		text = text[:-1] + "+00:00"
	if "." in text:
		head, rest = text.split(".", 1)
		count = 0
		while count < len(rest) and rest[count].isdigit():
			count += 1
		digits, zone = rest[:count], rest[count:]
		text = head + "." + digits[:6] + zone
	try:
		return datetime.datetime.fromisoformat(text).timestamp()
	except ValueError:
		return None


def att_records(packets):
	"""(índice, layers, btatt) de cada pacote ATT da captura."""
	for index, packet in enumerate(packets):
		layers = layers_of(packet)
		att = layers.get("btatt")
		if isinstance(att, _DupList):
			att = att[0]
		if isinstance(att, dict):
			yield index, layers, att


def discover_handles(packets):
	"""Mapa UUID → handle de valor, a partir da descoberta ou das anotações do Wireshark."""
	by_uuid = {}
	for _, _, att in att_records(packets):
		for node in walk(att):
			value_handle = parse_int(node.get("btatt.characteristic_value_handle"))
			if value_handle is not None:
				for field in UUID_FIELDS:
					uuid = normalize_uuid(node.get(field))
					if uuid:
						by_uuid.setdefault(uuid, value_handle)
						break
		handle = parse_int(att.get("btatt.handle"))
		tree = first(att.get("btatt.handle_tree"))
		if handle is not None and isinstance(tree, dict):
			for field in ("btatt.uuid128", "btatt.uuid16"):
				uuid = normalize_uuid(tree.get(field))
				if uuid:
					by_uuid.setdefault(uuid, handle)
					break
	return by_uuid


def negotiated_mtu(packets):
	client = server = None
	for _, _, att in att_records(packets):
		opcode = parse_int(att.get("btatt.opcode"))
		if opcode == OP_MTU_REQUEST:
			client = parse_int(att.get("btatt.client_rx_mtu"))
		elif opcode == OP_MTU_RESPONSE:
			server = parse_int(att.get("btatt.server_rx_mtu"))
	if client and server:
		return min(client, server)
	return None


def iso_with_offset(epoch_seconds, zone_name):
	"""Mesmo formato do TimeFormat.isoWithOffset: segundos e offset numérico, nunca "Z"."""
	try:
		from zoneinfo import ZoneInfo

		zone = ZoneInfo(zone_name)
	except Exception:  # noqa: BLE001 - sem tzdata ou fuso desconhecido: cai para UTC
		zone = datetime.timezone.utc
	moment = datetime.datetime.fromtimestamp(int(epoch_seconds), tz=zone)
	text = moment.strftime("%Y-%m-%dT%H:%M:%S%z")
	return text[:-2] + ":" + text[-2:]


def convert(packets, args):
	by_uuid = discover_handles(packets)
	write_handle, notify_handle = args.write_handle, args.notify_handle
	write_uuid, notify_uuid = normalize_uuid(args.write_uuid), normalize_uuid(args.notify_uuid)
	if write_handle is None or notify_handle is None:
		candidates = [(write_uuid, notify_uuid)] if args.write_uuid or args.notify_uuid else DEFAULT_CANDIDATES
		for cand_write, cand_notify in candidates:
			if cand_write in by_uuid and cand_notify in by_uuid:
				write_uuid, notify_uuid = cand_write, cand_notify
				write_handle = by_uuid[cand_write] if write_handle is None else write_handle
				notify_handle = by_uuid[cand_notify] if notify_handle is None else notify_handle
				break
	if write_handle is None or notify_handle is None:
		known = ", ".join(f"{uuid}=0x{handle:04x}" for uuid, handle in sorted(by_uuid.items())) or "none"
		raise SystemExit(
			"hci2jsonl: could not detect the write/notify handles; "
			f"handles seen in the capture: {known}. Pass --write-handle and --notify-handle."
		)
	write_uuid = write_uuid or NUS_WRITE
	notify_uuid = notify_uuid or NUS_NOTIFY

	lines = []
	start = None
	skipped_long = 0
	for _, layers, att in att_records(packets):
		opcode = parse_int(att.get("btatt.opcode"))
		handle = parse_int(att.get("btatt.handle"))
		value = att.get("btatt.value")
		if opcode == OP_PREPARE_WRITE and handle == write_handle:
			skipped_long += 1
			continue
		if opcode in (OP_WRITE_REQUEST, OP_WRITE_COMMAND) and handle == write_handle and value is not None:
			direction, characteristic = "TX", write_uuid
		elif opcode in (OP_NOTIFICATION, OP_INDICATION) and handle == notify_handle and value is not None:
			direction, characteristic = "RX", notify_uuid
		else:
			continue
		moment = packet_time(layers)
		if start is None:
			start = moment
		at_ms = int(round((moment - start) * 1000)) if moment is not None and start is not None else 0
		lines.append(
			{
				"type": "packet",
				"dir": direction,
				"atMs": at_ms,
				"characteristic": characteristic,
				"hex": normalize_hex(value),
			}
		)

	mtu = args.mtu or negotiated_mtu(packets) or 23
	recorded_at = iso_with_offset(start if start is not None else 0, args.time_zone)
	header = {
		"type": "header",
		"device": args.device,
		"firmware": args.firmware,
		"platform": args.platform,
		"mtu": mtu,
		"recordedAt": recorded_at,
		"timeZone": args.time_zone,
	}
	stats = {
		"tx": sum(1 for line in lines if line["dir"] == "TX"),
		"rx": sum(1 for line in lines if line["dir"] == "RX"),
		"writeHandle": write_handle,
		"notifyHandle": notify_handle,
		"skippedLongWrites": skipped_long,
	}
	return header, lines, stats


def parse_handle(text):
	value = parse_int(text)
	if value is None:
		raise argparse.ArgumentTypeError(f"handle inválida: {text} (use 0x000e ou 14)")
	return value


def build_parser():
	parser = argparse.ArgumentParser(
		prog="hci2jsonl.py",
		description=(
			"Converte o JSON exportado pelo Wireshark (Export Packet Dissections > As JSON) de um btsnoop_hci.log "
			"numa gravação .jsonl do technos-watch-protocol (formato em docs/fixtures.md). TX = Write Request/Command "
			"na characteristic de escrita; RX = notificações/indicações da characteristic de notificação."
		),
		epilog=(
			"Exemplos:\n"
			"  python3 -I scripts/hci2jsonl.py captura.json -o fixtures/real/fitgps/1.2.3/pair-sync.jsonl --firmware 1.2.3\n"
			"  python3 -I scripts/hci2jsonl.py captura.json --write-handle 0x000e --notify-handle 0x0010\n"
			"Sem --write-handle/--notify-handle, as handles são detectadas pelo UUID (FF61/FF62 antes de NUS) quando a\n"
			"descoberta de serviços está na captura; capture desde antes de conectar ao relógio."
		),
		formatter_class=argparse.RawDescriptionHelpFormatter,
	)
	parser.add_argument("input", help="arquivo JSON exportado pelo Wireshark")
	parser.add_argument("-o", "--output", default="-", help="arquivo .jsonl de saída (padrão: saída padrão)")
	parser.add_argument("--write-handle", type=parse_handle, help="handle ATT da characteristic de escrita (ex.: 0x000e)")
	parser.add_argument("--notify-handle", type=parse_handle, help="handle ATT da characteristic de notificação (ex.: 0x0010)")
	parser.add_argument("--write-uuid", help="UUID da characteristic de escrita (padrão: detecta FF61 ou NUS 6e400002)")
	parser.add_argument("--notify-uuid", help="UUID da characteristic de notificação (padrão: detecta FF62 ou NUS 6e400003)")
	parser.add_argument("--device", default="Mormaii_fitGPS", help="nome do relógio no cabeçalho (padrão: Mormaii_fitGPS)")
	parser.add_argument("--firmware", default=None, help="versão do firmware no cabeçalho, ex.: 1.2.3 (padrão: null)")
	parser.add_argument("--platform", default="android", help="plataforma no cabeçalho (padrão: android)")
	parser.add_argument("--mtu", type=int, default=None, help="MTU no cabeçalho (padrão: o negociado na captura, ou 23)")
	parser.add_argument(
		"--time-zone",
		default="America/Sao_Paulo",
		help="fuso IANA do telefone durante a captura (padrão: America/Sao_Paulo)",
	)
	return parser


def main(argv=None):
	args = build_parser().parse_args(argv)
	with open(args.input, encoding="utf-8") as handle:
		document = json.load(handle, object_pairs_hook=merge_duplicates)
	packets = document if isinstance(document, list) else [document]
	header, lines, stats = convert(packets, args)
	text = "\n".join(json.dumps(item, ensure_ascii=False, separators=(",", ":")) for item in [header] + lines) + "\n"
	if args.output == "-":
		sys.stdout.write(text)
	else:
		with open(args.output, "w", encoding="utf-8") as handle:
			handle.write(text)
	print(
		f"hci2jsonl: {stats['tx']} TX, {stats['rx']} RX (write 0x{stats['writeHandle']:04x}, "
		f"notify 0x{stats['notifyHandle']:04x}); {stats['skippedLongWrites']} long writes skipped",
		file=sys.stderr,
	)
	return 0


if __name__ == "__main__":
	sys.exit(main())
