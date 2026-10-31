#!/usr/bin/env python3
"""Converte uma captura HCI do Android (btsnoop, H4) numa gravação .jsonl do technos-watch-protocol.

Entrada: um ou mais btsnoop_hci.log, na ordem (o .log.last antes do .log), gravados com o "registro de snoop HCI do
Bluetooth" completo nas opções do desenvolvedor. Os horários do btsnoop são o relógio de parede do telefone; o fuso
de --time-zone dá o offset do cabeçalho.

Saída: o formato de log/FrameJsonl.kt (docs/fixtures.md), com as chaves extras de uma fixture de referência no
cabeçalho (source, app, connection, mtuInitiator, gatt, cccdWrites, redactions), que o RecordingLoader ignora.
Com --family crp:
- TX: ATT Write Command/Request do telefone em fee2;
- RX: Handle Value Notification/Indication do relógio em fee3, fee1, 2a19 e 2a37;
- READ: par Read Request (telefone) / Read Response (relógio) em 2a29, 2a28, 2a19 e fee1;
- todo o resto fica de fora (CCCD, descoberta, FEC9, FEA1, 190E, AE00, BR/EDR, SMP).

Redação no nível do frame (FE EA remontado nos dois sentidos):
- todos os pacotes dos frames com cmd em --redact-cmds (padrão 41, 42, 43, B5: texto de notificação, previsão do tempo
  com as temperaturas de cada dia, clima e localização) são descartados;
- o payload do frame 0x12 (perfil: altura, peso, idade, sexo) é trocado por --fake-user-info (padrão B24B1800: 178 cm,
  75 kg, 24 anos, masculino, os valores do app de exemplo da SDK), mantendo o cabeçalho.

Privacidade:
- nenhum endereço é impresso. O --list e o --peer usam um rótulo dev-xxxxxx = HMAC-SHA256 do endereço com uma chave
  tirada da própria captura (o primeiro MiB do primeiro arquivo), então o rótulo não se reverte sem a captura e muda de
  uma captura para outra. Use os mesmos arquivos, na mesma ordem, no --list e na conversão. O rótulo nunca vai para a
  saída e não se versiona (nos docs, dev-xxxxxx);
- a saída inteira é conferida contra todos os endereços que a captura mostra (o do telefone no Read_BD_ADDR; os pares de
  conexão LE e clássica, de Connection Request, de Create Connection e de LE Create Connection; os de SSP, link key e PIN;
  os de advertising reports e listas de filtro; o Identity Address do SMP), em bytes diretos e invertidos e no texto com
  dois-pontos: se algum aparecer, o script aborta sem escrever nada.

Só biblioteca padrão. Mensagens de erro e o --list em inglês; só esta ajuda em português. Rode isolado e de fora da
pasta da captura:
  python3 -I scripts/btsnoop2jsonl.py --list btsnoop_hci.log.last btsnoop_hci.log
  python3 -I scripts/btsnoop2jsonl.py --family crp --peer dev-xxxxxx --conn 11 --since 15:43 --until 15:46:11 \\
      btsnoop_hci.log.last btsnoop_hci.log -o saida.jsonl
"""

import argparse
import datetime
import hashlib
import hmac
import json
import struct
import sys

try:
	from zoneinfo import ZoneInfo
except ImportError:  # pragma: no cover - Python < 3.9
	ZoneInfo = None

# Microssegundos entre 0000-01-01 (época do btsnoop) e 1970-01-01, como no btsnoop.py da análise.
EPOCH_DELTA = 0x00DCDDB30F2F8000
BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

ATT_ERROR_RSP = 0x01
ATT_MTU_REQ = 0x02
ATT_MTU_RSP = 0x03
ATT_FIND_INFO_RSP = 0x05
ATT_READ_BY_TYPE_REQ = 0x08
ATT_READ_BY_TYPE_RSP = 0x09
ATT_READ_REQ = 0x0A
ATT_READ_RSP = 0x0B
ATT_READ_BY_GROUP_RSP = 0x11
ATT_WRITE_REQ = 0x12
ATT_WRITE_CMD = 0x52
ATT_NOTIFY = 0x1B
ATT_INDICATE = 0x1D

PROPERTY_NAMES = [(0x02, "read"), (0x04, "writeWithoutResponse"), (0x08, "write"), (0x10, "notify"), (0x20, "indicate")]

FAMILIES = {
	"crp": {
		"tx": {"fee2"},
		"rx": {"fee3", "fee1", "2a19", "2a37"},
		"read": {"2a29", "2a28", "2a19", "fee1"},
		"frames": {"fee2", "fee3"},
		"firmware_read": "2a28",
	},
}

DEFAULT_REDACT = "41,42,43,b5"
DEFAULT_FAKE_USER_INFO = "b24b1800"

# Bytes do primeiro arquivo que formam a chave do rótulo (timestamps e endereços da captura: nada disso sai dela).
LABEL_KEY_BYTES = 1 << 20

# Eventos HCI com BD_ADDR: código -> deslocamentos nos parâmetros (Core spec, vol. 4, parte E, 7.7).
EVENT_ADDRESSES = {
	0x03: (3,),  # Connection Complete
	0x04: (0,),  # Connection Request
	0x07: (1,),  # Remote Name Request Complete
	0x12: (1,),  # Role Change
	0x16: (0,),  # PIN Code Request
	0x17: (0,),  # Link Key Request
	0x18: (0,),  # Link Key Notification
	0x2C: (3,),  # Synchronous Connection Complete
	0x2F: (1,),  # Extended Inquiry Result
	0x31: (0,),  # IO Capability Request
	0x32: (0,),  # IO Capability Response
	0x33: (0,),  # User Confirmation Request
	0x34: (0,),  # User Passkey Request
	0x35: (0,),  # Remote OOB Data Request
	0x36: (1,),  # Simple Pairing Complete
	0x3B: (0,),  # User Passkey Notification
	0x3C: (0,),  # Keypress Notification
	0x3D: (0,),  # Remote Host Supported Features Notification
}

# Comandos HCI com BD_ADDR: opcode -> deslocamentos nos parâmetros (7.1, 7.3 e 7.8).
COMMAND_ADDRESSES = {
	0x0405: (0,),  # Create Connection
	0x0408: (0,),  # Create Connection Cancel
	0x0409: (0,),  # Accept Connection Request
	0x040A: (0,),  # Reject Connection Request
	0x040B: (0,),  # Link Key Request Reply
	0x040C: (0,),  # Link Key Request Negative Reply
	0x040D: (0,),  # PIN Code Request Reply
	0x040E: (0,),  # PIN Code Request Negative Reply
	0x0419: (0,),  # Remote Name Request
	0x041A: (0,),  # Remote Name Request Cancel
	0x042B: (0,),  # IO Capability Request Reply
	0x042C: (0,),  # User Confirmation Request Reply
	0x042D: (0,),  # User Confirmation Request Negative Reply
	0x042E: (0,),  # User Passkey Request Reply
	0x042F: (0,),  # User Passkey Request Negative Reply
	0x0430: (0,),  # Remote OOB Data Request Reply
	0x0433: (0,),  # Remote OOB Data Request Negative Reply
	0x0434: (0,),  # IO Capability Request Negative Reply
	0x0C0D: (0,),  # Read Stored Link Key
	0x0C12: (0,),  # Delete Stored Link Key
	0x2005: (0,),  # LE Set Random Address
	0x200D: (6,),  # LE Create Connection
	0x2011: (1,),  # LE Add Device To Filter Accept List
	0x2012: (1,),  # LE Remove Device From Filter Accept List
	0x2027: (1,),  # LE Add Device To Resolving List
	0x2028: (1,),  # LE Remove Device From Resolving List
	0x2035: (1,),  # LE Set Advertising Set Random Address
	0x2043: (3,),  # LE Extended Create Connection
	0x2085: (5,),  # LE Extended Create Connection v2
	0x204E: (1,),  # LE Set Privacy Mode
}

# Command Complete cujo retorno é status + BD_ADDR (Read_BD_ADDR, o endereço do próprio telefone, e as respostas aos
# comandos de pareamento acima).
COMPLETE_WITH_ADDRESS = {0x1009, 0x0408, 0x040B, 0x040C, 0x040D, 0x040E, 0x041A, 0x042B, 0x042C, 0x042D, 0x042E,
	0x042F, 0x0430, 0x0433, 0x0434}


def label_of(raw, key):
	"""Rótulo do endereço (nunca o endereço): HMAC com a chave tirada da captura, irreversível sem ela."""
	return "dev-" + hmac.new(key, raw, hashlib.sha256).hexdigest()[:6]


def label_key(paths):
	"""Chave do rótulo: SHA-256 do primeiro MiB do primeiro arquivo (o mesmo no --list e na conversão)."""
	with open(paths[0], "rb") as handle:
		return hashlib.sha256(handle.read(LABEL_KEY_BYTES)).digest()


def addresses_in(ptype, body):
	"""Os BD_ADDR (6 bytes, ordem do HCI) de um comando ou evento HCI; lista vazia nos outros pacotes."""
	found = []

	def take(params, *offsets):
		for off in offsets:
			if 0 <= off and off + 6 <= len(params):
				found.append(bytes(params[off:off + 6]))

	if ptype == 0x01 and len(body) >= 3:
		opcode = struct.unpack("<H", body[0:2])[0]
		take(body[3:3 + body[2]], *COMMAND_ADDRESSES.get(opcode, ()))
	elif ptype == 0x04 and len(body) >= 2:
		code, params = body[0], body[2:2 + body[1]]
		if code in EVENT_ADDRESSES:
			take(params, *EVENT_ADDRESSES[code])
		elif code in (0x02, 0x22) and params:  # Inquiry Result (with RSSI): num + BD_ADDR[num] em sequência
			take(params, *[1 + 6 * i for i in range(params[0])])
		elif code == 0x0E and len(params) >= 3 and struct.unpack("<H", params[1:3])[0] in COMPLETE_WITH_ADDRESS:
			take(params, 4)
		elif code == 0x3E and params:
			sub = params[0]
			if sub in (0x01, 0x0A, 0x29):  # LE (Enhanced) Connection Complete: par e, no Enhanced, os RPA local e do par
				take(params, *((6, 12, 18) if sub != 0x01 else (6,)))
			elif sub == 0x02 and len(params) >= 2:  # LE Advertising Report: campos em arrays de num
				num = params[1]
				take(params, *[2 + 2 * num + 6 * i for i in range(num)])
			elif sub == 0x0B and len(params) >= 2:  # LE Directed Advertising Report: 16 bytes por relatório
				for i in range(params[1]):
					take(params, 2 + 16 * i + 2, 2 + 16 * i + 9)
			elif sub == 0x0D and len(params) >= 2:  # LE Extended Advertising Report: endereço e endereço direcionado
				off = 2
				for _ in range(params[1]):
					if off + 24 > len(params):
						break
					take(params, off + 3, off + 17)
					off += 24 + params[off + 23]
	return found


def short_uuid(raw):
	if len(raw) == 2:
		return "%04x" % struct.unpack("<H", raw)[0]
	if len(raw) == 16:
		h = raw[::-1].hex()
		full = "%s-%s-%s-%s-%s" % (h[0:8], h[8:12], h[12:16], h[16:20], h[20:32])
		if full.endswith(BASE_SUFFIX) and full.startswith("0000"):
			return full[4:8]
		return full
	return raw.hex()


def full_uuid(short):
	return "0000%s%s" % (short, BASE_SUFFIX) if len(short) == 4 else short


def records(paths):
	for path in paths:
		with open(path, "rb") as handle:
			data = handle.read()
		if data[:8] != b"btsnoop\0":
			raise SystemExit("btsnoop2jsonl: %s is not a btsnoop file" % path)
		off = 16
		while off + 24 <= len(data):
			_orig, incl, flags, _drops, ts = struct.unpack(">IIIIq", data[off:off + 24])
			off += 24
			yield ts, flags, data[off:off + incl]
			off += incl


def wall_clock(ts):
	return datetime.datetime(1970, 1, 1) + datetime.timedelta(microseconds=ts - EPOCH_DELTA)


class Connection:
	def __init__(self, handle, raw, start, role, key):
		self.handle = handle
		self.raw = raw
		self.label = label_of(raw, key)
		self.start = start
		self.end = None
		self.role = role
		self.att = 0
		self.services = []


class Capture:
	"""Primeira passada: endereços, conexões LE, descoberta GATT por relógio e pacotes ATT com o tempo e o sentido."""

	def __init__(self, paths):
		self.connections = []
		self.att = []  # (when, conn, received, payload)
		self.addresses = set()
		secret = label_key(paths)
		open_by_handle = {}
		fragments = {}
		for ts, flags, pkt in records(paths):
			if not pkt:
				continue
			when = wall_clock(ts)
			received = bool(flags & 1)
			ptype, body = pkt[0], pkt[1:]
			self.addresses.update(addresses_in(ptype, body))
			if ptype == 0x01:
				continue
			if ptype == 0x04 and len(body) >= 2:
				code, params = body[0], body[2:]
				if code == 0x3E and params and params[0] in (0x01, 0x0A, 0x29) and len(params) >= 12 and params[1] == 0:
					handle = struct.unpack("<H", params[2:4])[0] & 0x0FFF
					raw = bytes(params[6:12])
					conn = Connection(handle, raw, when, params[4], secret)
					self.connections.append(conn)
					open_by_handle[handle] = conn
				elif code == 0x05 and len(params) >= 4:
					handle = struct.unpack("<H", params[1:3])[0] & 0x0FFF
					conn = open_by_handle.pop(handle, None)
					if conn is not None:
						conn.end = when
				continue
			if ptype != 0x02 or len(body) < 4:
				continue
			header, length = struct.unpack("<HH", body[0:4])
			handle = header & 0x0FFF
			boundary = (header >> 12) & 0x3
			acl = body[4:4 + length]
			key = (handle, received)
			if boundary in (0x0, 0x2):
				fragments[key] = bytearray(acl)
			elif key in fragments:
				fragments[key] += acl
			else:
				continue
			buf = fragments[key]
			if len(buf) < 4:
				continue
			l2len, cid = struct.unpack("<HH", buf[0:4])
			if len(buf) < 4 + l2len:
				continue
			payload = bytes(buf[4:4 + l2len])
			del fragments[key]
			if cid in (0x0006, 0x0007) and len(payload) >= 8 and payload[0] == 0x09:
				# SMP Identity Address Information (LE e BR/EDR): tipo + endereço de identidade.
				self.addresses.add(payload[2:8])
			conn = open_by_handle.get(handle)
			if cid != 0x0004 or not payload or conn is None:
				continue
			conn.att += 1
			self.att.append((when, conn, received, payload))
		self.gatt = {}
		for conn in self.connections:
			self.gatt.setdefault(conn.label, Gatt())
		pending_type = {}
		for when, conn, received, payload in self.att:
			gatt = self.gatt[conn.label]
			op = payload[0]
			if not received and op == ATT_READ_BY_TYPE_REQ and len(payload) >= 7:
				pending_type[conn] = short_uuid(payload[5:])
			elif received and op == ATT_READ_BY_TYPE_RSP and len(payload) >= 2:
				gatt.read_by_type(pending_type.get(conn), payload)
			elif received and op == ATT_READ_BY_GROUP_RSP and len(payload) >= 2:
				gatt.read_by_group(payload)
				conn.services = [s[2] for s in gatt.services]
			elif received and op == ATT_FIND_INFO_RSP and len(payload) >= 2:
				gatt.find_info(payload)


class Gatt:
	"""Mapa de handles de um relógio, montado pela descoberta (os handles não mudam entre conexões)."""

	def __init__(self):
		self.services = []  # [start, end, uuid]
		self.characteristics = {}  # value handle -> (uuid, props)
		self.cccd = set()
		self.device_name = None

	def read_by_group(self, payload):
		size = payload[1]
		for i in range(2, len(payload) - size + 1, size):
			start, end = struct.unpack("<HH", payload[i:i + 4])
			entry = [start, end, short_uuid(payload[i + 4:i + size])]
			if entry not in self.services:
				self.services.append(entry)

	def read_by_type(self, requested, payload):
		size = payload[1]
		for i in range(2, len(payload) - size + 1, size):
			value = payload[i + 2:i + size]
			if requested == "2803" and len(value) in (5, 19):
				self.characteristics[struct.unpack("<H", value[1:3])[0]] = (short_uuid(value[3:]), value[0])
			elif requested == "2a00":
				self.device_name = value.decode("latin-1").strip("\x00 ")

	def find_info(self, payload):
		size = 4 if payload[1] == 1 else 18
		for i in range(2, len(payload) - size + 1, size):
			if short_uuid(payload[i + 2:i + size]) == "2902":
				self.cccd.add(struct.unpack("<H", payload[i:i + 2])[0])

	def uuid_of(self, handle):
		entry = self.characteristics.get(handle)
		return entry[0] if entry else None

	def cccd_owner(self, handle):
		candidates = [h for h in self.characteristics if h < handle]
		return self.uuid_of(max(candidates)) if candidates else None

	def summary(self):
		out = []
		for start, end, uuid in sorted(self.services):
			chars = []
			for handle in sorted(h for h in self.characteristics if start <= h <= end):
				cuuid, props = self.characteristics[handle]
				names = [name for bit, name in PROPERTY_NAMES if props & bit]
				nxt = [h for h in self.characteristics if h > handle] + [end + 1]
				has_cccd = any(handle < c < min(nxt) for c in self.cccd)
				chars.append({"uuid": cuuid, "properties": names, "cccd": has_cccd})
			out.append({"service": uuid, "characteristics": chars})
		return out


class FrameRedactor:
	"""Remonta os frames FE EA de um sentido e decide o destino dos pacotes de cada frame."""

	def __init__(self, redact_cmds, fake_user_info):
		self.redact = redact_cmds
		self.fake = fake_user_info
		self.parts = []
		self.data = b""
		self.total = 0
		self.dropped = 0
		self.replaced = 0

	@staticmethod
	def total_length(packet):
		if len(packet) < 5 or packet[0] != 0xFE or packet[1] != 0xEA or (packet[2] >> 4) not in (1, 2, 3):
			return None
		total = ((packet[2] & 0x0F) << 8) | packet[3]
		return total if total >= 5 else None

	def accept(self, item):
		"""item = (when, characteristic, bytes); devolve os itens a escrever (zero ou mais)."""
		out = []
		packet = item[2]
		total = self.total_length(packet)
		if total is not None:
			if self.parts:
				sys.stderr.write("btsnoop2jsonl: warning: incomplete frame dropped (%d bytes)\n" % len(self.data))
				self.parts = []
			self.parts = [item]
			self.data = packet
			self.total = total
		elif self.parts:
			self.parts.append(item)
			self.data += packet
		else:
			sys.stderr.write("btsnoop2jsonl: warning: continuation without a frame start dropped (%d bytes)\n" % len(packet))
			return out
		if len(self.data) < self.total:
			return out
		parts, data = self.parts, self.data
		self.parts, self.data, self.total = [], b"", 0
		cmd = data[4]
		if cmd in self.redact:
			self.dropped += 1
			return out
		if cmd == 0x12:
			self.replaced += 1
			total = 5 + len(self.fake)
			rebuilt = bytes([0xFE, 0xEA, (data[2] & 0xF0) | (total >> 8), total & 0xFF, 0x12]) + self.fake
			return [(parts[0][0], parts[0][1], rebuilt)]
		return parts


def parse_clock(text, day):
	parts = [int(p) for p in text.split(":")]
	while len(parts) < 3:
		parts.append(0)
	return datetime.datetime.combine(day, datetime.time(parts[0], parts[1], parts[2]))


def list_connections(capture):
	for conn in capture.connections:
		end = conn.end.strftime("%H:%M:%S.%f")[:-3] if conn.end else "open"
		role = "central" if conn.role == 0 else "peripheral"
		print("%s conn=%d %s-%s role=%s att=%d services=%s" % (
			conn.label, conn.handle, conn.start.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3], end, role, conn.att,
			",".join(conn.services) or "-",
		))


def convert(args, capture):
	family = FAMILIES[args.family]
	chosen = [c for c in capture.connections if c.label == args.peer and (args.conn is None or c.handle == args.conn)]
	if not chosen:
		raise SystemExit("btsnoop2jsonl: no connection of %s with handle %s (see --list)" % (args.peer, args.conn))
	day = chosen[0].start.date()
	since = parse_clock(args.since, day) if args.since else None
	until = parse_clock(args.until, day) if args.until else None
	if since:
		chosen = [c for c in chosen if (c.end or datetime.datetime.max) >= since]
	if until:
		chosen = [c for c in chosen if c.start <= until]
	if not chosen:
		raise SystemExit("btsnoop2jsonl: no connection of %s in the requested window" % args.peer)
	gatt = capture.gatt[args.peer]
	redact = {int(x, 16) for x in args.redact_cmds.split(",") if x.strip()}
	fake = bytes.fromhex(args.fake_user_info)
	redactors = {"TX": FrameRedactor(redact, fake), "RX": FrameRedactor(redact, fake)}
	start = max(chosen[0].start, since) if since else chosen[0].start
	items = []  # (when, dir, characteristic, bytes)
	pending_read = {}
	mtu_values = {}
	mtu_initiator = None
	cccd_writes = []
	firmware = None
	for when, conn, received, payload in capture.att:
		if conn not in chosen or (since and when < since) or (until and when > until):
			continue
		op = payload[0]
		if op == ATT_MTU_REQ and len(payload) >= 3:
			mtu_values["req"] = struct.unpack("<H", payload[1:3])[0]
			mtu_initiator = "peripheral" if received else "central"
		elif op == ATT_MTU_RSP and len(payload) >= 3:
			mtu_values["rsp"] = struct.unpack("<H", payload[1:3])[0]
		elif not received and op in (ATT_WRITE_CMD, ATT_WRITE_REQ) and len(payload) >= 3:
			handle = struct.unpack("<H", payload[1:3])[0]
			uuid = gatt.uuid_of(handle)
			if handle in gatt.cccd:
				cccd_writes.append({"characteristic": gatt.cccd_owner(handle), "value": payload[3:].hex().upper()})
			elif uuid in family["tx"]:
				items.append((when, "TX", uuid, payload[3:]))
		elif received and op in (ATT_NOTIFY, ATT_INDICATE) and len(payload) >= 3:
			uuid = gatt.uuid_of(struct.unpack("<H", payload[1:3])[0])
			if uuid in family["rx"]:
				items.append((when, "RX", uuid, payload[3:]))
		elif not received and op == ATT_READ_REQ and len(payload) >= 3:
			pending_read[conn] = gatt.uuid_of(struct.unpack("<H", payload[1:3])[0])
		elif received and op == ATT_READ_RSP:
			uuid = pending_read.pop(conn, None)
			if uuid == family["firmware_read"]:
				firmware = payload[1:].decode("latin-1").strip("\x00 ")
			if uuid in family["read"]:
				items.append((when, "READ", uuid, payload[1:]))
		elif received and op == ATT_ERROR_RSP:
			pending_read.pop(conn, None)
	out_items = []
	for when, direction, uuid, value in items:
		if uuid in family["frames"]:
			for w, c, v in redactors[direction].accept((when, uuid, value)):
				out_items.append((w, direction, c, v))
		else:
			out_items.append((when, direction, uuid, value))
	tz = args.time_zone
	if ZoneInfo is None:
		raise SystemExit("btsnoop2jsonl: Python 3.9 or newer is required (zoneinfo)")
	zone = ZoneInfo(tz)
	recorded_at = start.replace(tzinfo=zone).isoformat(timespec="milliseconds")
	mtu = min(mtu_values.values()) if mtu_values else 23
	header = {
		"type": "header",
		"device": args.device or gatt.device_name or args.peer,
		"firmware": args.firmware or firmware,
		"platform": "android-hci",
		"mtu": mtu,
		"recordedAt": recorded_at,
		"timeZone": tz,
		"source": "hci",
		"app": args.app,
		"connection": {
			"handle": chosen[0].handle,
			"start": chosen[0].start.strftime("%H:%M:%S.%f")[:-3],
			"end": chosen[-1].end.strftime("%H:%M:%S.%f")[:-3] if chosen[-1].end else None,
			"window": [args.since, args.until],
		},
		"mtuInitiator": mtu_initiator,
		"gatt": gatt.summary(),
		"cccdWrites": cccd_writes,
		"redactions": (
			"Gerada por scripts/btsnoop2jsonl.py a partir da captura HCI, só com a conexão escolhida. Frames com cmd %s "
			"descartados inteiros (%d no TX, %d no RX); o padrão 41, 42, 43, B5 tira texto de notificação, previsão do tempo, "
			"clima e localização. Payload do 0x12 (perfil do usuário) trocado por %s (%d frames), um valor fictício. "
			"Endereços e BR/EDR nunca entram."
			% (", ".join("%02X" % c for c in sorted(redact)), redactors["TX"].dropped, redactors["RX"].dropped,
				args.fake_user_info.upper(), redactors["TX"].replaced + redactors["RX"].replaced)
		),
	}
	lines = [json.dumps(header, ensure_ascii=False, separators=(",", ":"))]
	for when, direction, uuid, value in out_items:
		at_ms = int(round((when - start).total_seconds() * 1000))
		lines.append(json.dumps({
			"type": "packet", "dir": direction, "atMs": at_ms, "characteristic": full_uuid(uuid), "hex": value.hex().upper(),
		}, separators=(",", ":")))
	text = "\n".join(lines) + "\n"
	guard(text, capture.addresses)
	return text


def guard(text, addresses):
	"""Aborta se algum endereço da captura aparecer na saída (bytes diretos, invertidos ou texto com dois-pontos).

	Os endereços nunca são impressos, nem na mensagem de erro.
	"""
	upper = text.upper()
	for raw in addresses:
		if len(set(raw)) == 1:
			# Endereço trivial (00:00:… de uma conexão que falhou): não identifica ninguém e casaria com qualquer zero.
			continue
		for form in (raw, raw[::-1]):
			if form.hex().upper() in upper:
				raise SystemExit("btsnoop2jsonl: aborted: the output contains a device address; nothing was written")
			if ":".join("%02X" % b for b in form) in upper:
				raise SystemExit("btsnoop2jsonl: aborted: the output contains a device address; nothing was written")


def main(argv=None):
	parser = argparse.ArgumentParser(
		description="Converte btsnoop (H4) do Android numa gravação .jsonl (formato FrameJsonl), com redação e trava "
		"contra o endereço do relógio.",
	)
	parser.add_argument("captures", nargs="+", help="arquivos btsnoop, na ordem (o .log.last antes do .log)")
	parser.add_argument("--list", action="store_true", help="lista as conexões LE (rótulo, handle, início, fim, papel, ATT, serviços) e sai")
	parser.add_argument("--family", choices=sorted(FAMILIES), help="família do relógio (decide as characteristics)")
	parser.add_argument("--peer", help="rótulo dev-xxxxxx do relógio, como o --list mostra para estes mesmos arquivos")
	parser.add_argument("--conn", type=int, help="handle da conexão LE (veja --list)")
	parser.add_argument("--since", help="horário local inicial HH:MM[:SS] (inclusive)")
	parser.add_argument("--until", help="horário local final HH:MM[:SS] (inclusive)")
	parser.add_argument("--time-zone", default="America/Sao_Paulo", help="fuso IANA do telefone (padrão America/Sao_Paulo)")
	parser.add_argument("--device", help="nome do relógio no cabeçalho (padrão: o 2A00 lido na captura)")
	parser.add_argument("--firmware", help="firmware no cabeçalho (padrão: o 2A28 lido na captura)")
	parser.add_argument("--app", default="app de produção", help="app que gerou a captura, para o cabeçalho")
	parser.add_argument("--redact-cmds", default=DEFAULT_REDACT, help="cmds (hex, separados por vírgula) cujos frames são descartados (padrão 41,42,43,b5)")
	parser.add_argument("--fake-user-info", default=DEFAULT_FAKE_USER_INFO, help="payload fictício do 0x12 em hex (padrão b24b1800)")
	parser.add_argument("-o", "--output", help="arquivo de saída (padrão: saída padrão)")
	args = parser.parse_args(argv)
	capture = Capture(args.captures)
	if args.list:
		list_connections(capture)
		return 0
	if not args.family or not args.peer:
		parser.error("--family and --peer are required (or use --list)")
	text = convert(args, capture)
	if args.output:
		with open(args.output, "w", encoding="utf-8") as handle:
			handle.write(text)
	else:
		sys.stdout.write(text)
	return 0


if __name__ == "__main__":
	sys.exit(main())
