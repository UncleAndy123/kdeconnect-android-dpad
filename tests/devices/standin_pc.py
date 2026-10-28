#!/usr/bin/env python3
"""
Stand-in for the KDE Connect desktop app, for testing the Android app on an emulator or a phone over adb.

Speaks protocol version 8: announces itself over UDP (sent to the host's 1716, which the emulator
redirects to the phone), accepts the phone's TCP connection, upgrades it to TLS (we are the TLS client,
the phone the server, as in LanLinkProvider), exchanges identities, pairs, and then sends/receives
packets including payload transfers. Every packet is logged to packets.jsonl.

  standin_pc.py serve                 run the peer (foreground)
  standin_pc.py send TYPE [BODY_JSON] [--file PATH]   queue a packet to the phone (optionally with payload)

It answers the phone's media, volume and Run Command requests with sample data (an "Elisa" player,
two audio outputs, three commands), so those screens show real content. State (certificate, packet
log, control socket) lives in $STANDIN_STATE (default: tests/devices/.standin).
"""
import json, os, shutil, socket, ssl, sys, threading, time, hashlib, datetime, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
STATE = os.environ.get("STANDIN_STATE") or os.path.join(HERE, ".standin")
LOG = os.path.join(STATE, "packets.jsonl")
CTL = os.path.join(STATE, "ctl.sock")
RECV = os.path.join(STATE, "received")
DEVICE_ID = "standin_pc_0123456789abcdef0123456789"   # 36 chars, matches ^[a-zA-Z0-9_-]{32,38}$
TCP_PORT = 1720            # in the phone's accepted range 1716..1764
PAYLOAD_PORTS = [1763, 1764]
PHONE_FWD = 1721           # host port forwarded to the phone's link port (phone_port())
ADB = shutil.which("adb") or os.path.join(os.environ.get("ANDROID_HOME", "/opt/android-sdk"), "platform-tools", "adb")

# Sample desktop data, sent when the phone asks for it
PLAYER = {"player": "Elisa", "title": "Clair de Lune", "artist": "Claude Debussy", "album": "Suite bergamasque",
          "isPlaying": True, "canPause": True, "canPlay": True, "canGoNext": True, "canGoPrevious": True,
          "canSeek": True, "pos": 95000, "length": 302000, "volume": 70}
SINKS = [{"name": "speakers", "description": "Speakers", "volume": 65, "muted": False, "maxVolume": 100, "enabled": True},
         {"name": "headphones", "description": "Headphones", "volume": 40, "muted": False, "maxVolume": 100, "enabled": False}]
COMMANDS = {"lock": {"name": "Lock screen", "command": "loginctl lock-session"},
            "sus": {"name": "Suspend", "command": "systemctl suspend"},
            "up": {"name": "Check for updates", "command": "apt list --upgradable"}}

TYPES = ("kdeconnect.battery kdeconnect.battery.request kdeconnect.clipboard kdeconnect.clipboard.connect "
         "kdeconnect.connectivity_report kdeconnect.connectivity_report.request kdeconnect.digitizer kdeconnect.digitizer.session "
         "kdeconnect.contacts.request_all_uids_timestamps kdeconnect.contacts.request_vcards_by_uid "
         "kdeconnect.contacts.response_uids_timestamps kdeconnect.contacts.response_vcards "
         "kdeconnect.findmyphone.request kdeconnect.mousepad.echo kdeconnect.mousepad.keyboardstate "
         "kdeconnect.mousepad.request kdeconnect.mpris kdeconnect.mpris.request kdeconnect.notification "
         "kdeconnect.notification.action kdeconnect.notification.reply kdeconnect.notification.request "
         "kdeconnect.ping kdeconnect.presenter kdeconnect.runcommand kdeconnect.runcommand.output "
         "kdeconnect.runcommand.request kdeconnect.sftp kdeconnect.sftp.request kdeconnect.share.request "
         "kdeconnect.share.request.update kdeconnect.shareinputdevices kdeconnect.shareinputdevices.request "
         "kdeconnect.sms.attachment_file kdeconnect.sms.messages kdeconnect.sms.request "
         "kdeconnect.sms.request_attachment kdeconnect.sms.request_conversation kdeconnect.sms.request_conversations "
         "kdeconnect.systemvolume kdeconnect.systemvolume.request kdeconnect.telephony "
         "kdeconnect.telephony.request_mute").split()


def phone_port():
    """The app's link listener: the lowest TCP port listening in 1716-1738 on the phone (1739+ is SFTP's range)."""
    try:
        out = subprocess.run([ADB, "shell", "cat /proc/net/tcp /proc/net/tcp6"], capture_output=True, text=True,
                             timeout=20).stdout
        ports = sorted(int(f[1].rsplit(":", 1)[1], 16) for f in (l.split() for l in out.splitlines())
                       if len(f) > 3 and f[3] == "0A" and ":" in f[1])
        return next((p for p in ports if 1716 <= p <= 1738), 1716)
    except Exception:
        return 1716


def identity():
    return packet("kdeconnect.identity", {
        "deviceId": DEVICE_ID, "deviceName": "Desktop", "deviceType": "desktop",
        "protocolVersion": 8, "incomingCapabilities": TYPES, "outgoingCapabilities": TYPES,
        "tcpPort": TCP_PORT,
    })


def packet(ptype, body, payload_size=None, port=None):
    p = {"id": int(time.time() * 1000), "type": ptype, "body": body}
    if payload_size is not None:
        p["payloadSize"] = payload_size
        p["payloadTransferInfo"] = {"port": port}
    return p


def log(direction, p, extra=None):
    rec = {"t": datetime.datetime.now().strftime("%H:%M:%S"), "dir": direction, "type": p.get("type"),
           "body": p.get("body")}
    if "payloadSize" in p:
        rec["payloadSize"] = p["payloadSize"]
    if extra:
        rec.update(extra)
    with open(LOG, "a") as f:
        f.write(json.dumps(rec) + "\n")


def ensure_cert():
    key, crt = os.path.join(STATE, "key.pem"), os.path.join(STATE, "cert.pem")
    if not os.path.exists(crt):
        from cryptography import x509
        from cryptography.x509.oid import NameOID
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        k = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, DEVICE_ID),
                          x509.NameAttribute(NameOID.ORGANIZATION_NAME, "KDE"),
                          x509.NameAttribute(NameOID.ORGANIZATIONAL_UNIT_NAME, "Kde connect")])
        now = datetime.datetime.now(datetime.timezone.utc)
        c = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(k.public_key())
             .serial_number(1).not_valid_before(now - datetime.timedelta(days=365))
             .not_valid_after(now + datetime.timedelta(days=3650)).sign(k, hashes.SHA256()))
        open(key, "wb").write(k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL,
                                              serialization.NoEncryption()))
        open(crt, "wb").write(c.public_bytes(serialization.Encoding.PEM))
    return crt, key


def tls_context(server):
    crt, key = ensure_cert()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER if server else ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE   # the phone's cert is self-signed; we only log its hash
    ctx.maximum_version = ssl.TLSVersion.TLSv1_2
    ctx.set_ciphers("DEFAULT:@SECLEVEL=0")
    ctx.load_cert_chain(crt, key)
    return ctx


def readline(sock):
    buf = b""
    while not buf.endswith(b"\n"):
        ch = sock.recv(1)
        if not ch:
            raise ConnectionError("closed")
        buf += ch
    return buf.decode()


class Peer:
    def __init__(self):
        self.tls = None
        self.lock = threading.Lock()
        self.connected = threading.Event()

    def send(self, p):
        with self.lock:
            self.tls.sendall((json.dumps(p) + "\n").encode())
        log("pc->phone", p)

    # -- connection: we connect to the phone's TCP listener through adb forward (the emulator's NAT
    # blocks discovery); the phone then sees a local (loopback) peer, is the TLS client, we the server.
    def connect_loop(self):
        while True:
            if not self.connected.is_set():
                try:
                    # (re)create the forward each try, so a rebooted or swapped emulator is picked up. Forward to
                    # the port the app really listens on: like upstream it takes the first free one from 1716 up,
                    # and 1716 can still be held for a moment when the app restarts (it then uses 1717)
                    subprocess.run([ADB, "forward", f"tcp:{PHONE_FWD}", f"tcp:{phone_port()}"], capture_output=True)
                    s = socket.create_connection(("127.0.0.1", PHONE_FWD), timeout=30)
                    s.sendall((json.dumps(identity()) + "\n").encode())
                    t = tls_context(server=True).wrap_socket(s, server_side=True)
                    t.sendall((json.dumps(identity()) + "\n").encode())
                    secure = json.loads(readline(t))
                    log("phone->pc", secure, {"note": f"identity over TLS {t.version()} {t.cipher()[0]}"})
                    t.settimeout(None)
                    self.tls = t
                    self.connected.set()
                    threading.Thread(target=self.read_loop, daemon=True).start()
                    threading.Thread(target=self.push_state, daemon=True).start()
                except Exception as e:
                    log("error", {"type": "connect", "body": {"error": repr(e)}})
            time.sleep(5)

    def read_loop(self):
        t = self.tls
        buf = b""
        try:
            while True:
                data = t.recv(65536)
                if not data:
                    break
                buf += data
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    if line.strip():
                        self.handle(json.loads(line))
        except Exception as e:
            log("error", {"type": "read", "body": {"error": repr(e)}})
        self.connected.clear()
        log("info", {"type": "disconnected", "body": {}})

    def handle(self, p):
        extra = None
        if "payloadTransferInfo" in p:
            extra = self.receive_payload(p)
        log("phone->pc", p, extra)
        if p["type"] == "kdeconnect.pair" and p["body"].get("pair"):
            # Accept the phone's pairing request (what clicking Accept on the desktop does)
            self.send(packet("kdeconnect.pair", {"pair": True, "timestamp": int(time.time())}))
            threading.Thread(target=self.push_state, daemon=True).start()
        self.answer(p)

    def answer(self, p):
        """Replies a desktop would give to the phone's requests (sample data)."""
        t, b = p["type"], p["body"]
        if t == "kdeconnect.mpris.request":
            if b.get("requestPlayerList"):
                self.send(packet("kdeconnect.mpris", {"playerList": [PLAYER["player"]], "supportAlbumArtPayload": False}))
            if b.get("player") == PLAYER["player"] and (b.get("requestNowPlaying") or b.get("requestVolume")):
                self.send(packet("kdeconnect.mpris", dict(PLAYER)))
        elif t == "kdeconnect.systemvolume.request" and b.get("requestSinks"):
            self.send(packet("kdeconnect.systemvolume", {"sinkList": SINKS}))
        elif t == "kdeconnect.runcommand.request":
            if b.get("requestCommandList"):
                self.send(packet("kdeconnect.runcommand", {"commandList": json.dumps(COMMANDS), "canAddCommand": False}))
            elif b.get("key") in COMMANDS:
                n = getattr(self, "cmd_id", 0) + 1; self.cmd_id = n
                self.send(packet("kdeconnect.runcommand.output",
                                 {"commandStarted": True, "id": n, "command": COMMANDS[b["key"]]["command"]}))
                self.send(packet("kdeconnect.runcommand.output",
                                 {"commandOutput": True, "id": n, "stdout": ["Listing... Done", "firefox/stable 132.0 amd64"],
                                  "stderr": []}))
                self.send(packet("kdeconnect.runcommand.output", {"commandFinished": True, "id": n, "success": True}))

    def push_state(self):
        """What a paired desktop pushes unasked once connected: its audio outputs and media players."""
        time.sleep(3)
        try:
            self.send(packet("kdeconnect.systemvolume", {"sinkList": SINKS}))
            self.send(packet("kdeconnect.mpris", {"playerList": [PLAYER["player"]], "supportAlbumArtPayload": False}))
        except Exception as e:
            log("error", {"type": "push_state", "body": {"error": repr(e)}})

    def receive_payload(self, p):
        port = p["payloadTransferInfo"]["port"]
        size = p.get("payloadSize", -1)
        subprocess.run([ADB, "forward", f"tcp:{port}", f"tcp:{port}"], capture_output=True)
        s = socket.create_connection(("127.0.0.1", port), timeout=20)
        t = tls_context(server=False).wrap_socket(s, server_side=False)
        name = p["body"].get("filename") or f'{p["type"]}-{p["id"]}'
        os.makedirs(RECV, exist_ok=True)
        path = os.path.join(RECV, os.path.basename(name))
        h, n = hashlib.sha256(), 0
        with open(path, "wb") as f:
            while size < 0 or n < size:
                chunk = t.recv(min(65536, size - n) if size > 0 else 65536)
                if not chunk:
                    break
                f.write(chunk); h.update(chunk); n += len(chunk)
        t.close()
        return {"payload_received": n, "payload_sha256": h.hexdigest()[:16], "saved": path}

    def send_with_payload(self, ptype, body, path):
        data = open(path, "rb").read()
        srv = None
        for port in PAYLOAD_PORTS:
            # the phone connects back to the address of the main connection (its loopback, via adb forward)
            subprocess.run([ADB, "reverse", f"tcp:{port}", f"tcp:{port}"], capture_output=True)
            try:
                srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                srv.bind(("0.0.0.0", port)); srv.listen(1); break
            except OSError:
                srv = None
        p = packet(ptype, body, len(data), port)
        self.send(p)
        srv.settimeout(30)
        s, _ = srv.accept()
        t = tls_context(server=True).wrap_socket(s, server_side=True)
        t.sendall(data); t.close(); srv.close()
        log("info", {"type": "payload_sent", "body": {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()[:16]}})

    # -- control socket for the "send" command
    def control_loop(self):
        if os.path.exists(CTL):
            os.unlink(CTL)
        srv = socket.socket(socket.AF_UNIX); srv.bind(CTL); srv.listen(4)
        while True:
            c, _ = srv.accept()
            req = json.loads(c.makefile().readline())
            try:
                if not self.connected.wait(5):
                    raise RuntimeError("phone not connected")
                if req.get("file"):
                    threading.Thread(target=self.send_with_payload, args=(req["type"], req["body"], req["file"]),
                                     daemon=True).start()
                else:
                    self.send(packet(req["type"], req["body"]))
                c.sendall(b"ok\n")
            except Exception as e:
                c.sendall(f"error {e!r}\n".encode())
            c.close()


def main():
    os.makedirs(STATE, exist_ok=True)
    if sys.argv[1] == "serve":
        peer = Peer()
        ensure_cert()
        for fn in (peer.connect_loop, peer.control_loop):
            threading.Thread(target=fn, daemon=True).start()
        log("info", {"type": "started", "body": {"deviceId": DEVICE_ID}})
        while True:
            time.sleep(3600)
    elif sys.argv[1] == "send":
        body = json.loads(sys.argv[3]) if len(sys.argv) > 3 and not sys.argv[3].startswith("--") else {}
        req = {"type": sys.argv[2], "body": body}
        if "--file" in sys.argv:
            req["file"] = sys.argv[sys.argv.index("--file") + 1]
        c = socket.socket(socket.AF_UNIX); c.connect(CTL)
        c.sendall((json.dumps(req) + "\n").encode())
        print(c.makefile().readline().strip())


if __name__ == "__main__":
    main()
