#!/usr/bin/env python3
"""
bench.py <label>: times every desktop-driven feature through the stand-in desktop.
Prints one JSON line: seconds per feature (lower is better), throughput in MB/s.
"""
import json, os, os, subprocess, sys, time, statistics
S = os.environ.get("STANDIN_STATE") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "devices", ".standin"); LOG = os.path.join(S, "packets.jsonl")
ADB = os.environ.get("ADB", "adb")
STANDIN = ["python3", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "devices", "standin_pc.py"), "send"]
res = {}


def send(t, body=None, file=None):
    cmd = STANDIN + [t, json.dumps(body or {})] + (["--file", file] if file else [])
    return subprocess.run(cmd, capture_output=True, text=True).stdout.strip() == "ok"


def mark():
    return sum(1 for _ in open(LOG))


def wait_packet(start, ptype, pred=lambda b: True, timeout=60, direction="phone->pc"):
    t0 = time.time()
    while time.time() - t0 < timeout:
        with open(LOG) as f:
            for l in f.readlines()[start:]:
                r = json.loads(l)
                if r.get("dir") == direction and r["type"] == ptype and pred(r.get("body") or {}):
                    return r
        time.sleep(0.05)
    return None


def adb(*a):
    return subprocess.run([ADB, *a], capture_output=True, text=True).stdout


def timed(name, fn):
    t0 = time.time()
    ok = fn()
    res[name] = round(time.time() - t0, 2) if ok else "FAIL"


def wait_focus(activity, timeout=30):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if activity in adb("shell", "dumpsys", "window", "windows").split("mCurrentFocus")[1][:200]:
            return True
        time.sleep(0.2)
    return False


# notifications: request -> all current notifications sent
def notif():
    m = mark(); send("kdeconnect.notification.request", {"request": True})
    return wait_packet(m, "kdeconnect.notification", lambda b: "id" in b)
timed("notifications_first", notif)

# remote keyboard: 20 keys with ack, median round trip
def keys():
    rt = []
    for i in range(20):
        m = mark(); t0 = time.time()
        send("kdeconnect.mousepad.request", {"key": "x", "sendAck": True})
        if not wait_packet(m, "kdeconnect.mousepad.echo", timeout=10):
            return False
        rt.append(time.time() - t0)
    res["keyboard_key_median"] = round(statistics.median(rt), 3)
    return True
keys()

# share: text, then 1 MB and 10 MB files (time until saved)
def share_file(size_mb):
    fn = "/tmp/kdeconnect-bench-%dmb.bin" % size_mb
    if not os.path.exists(fn):
        with open(fn, "wb") as f:
            f.write(os.urandom(size_mb * 1024 * 1024))
    name = "bench-%dmb-%d.bin" % (size_mb, int(time.time()))
    os.link(fn, "/tmp/" + name) if not os.path.exists("/tmp/" + name) else None
    t0 = time.time()
    send("kdeconnect.share.request", {"filename": name}, "/tmp/" + name)
    while time.time() - t0 < 300:
        out = adb("shell", "ls", "-l", "/sdcard/Download/" + name)
        parts = out.split()
        if len(parts) > 4 and parts[3].isdigit() and int(parts[3]) == size_mb * 1024 * 1024 or (len(parts) > 4 and parts[4].isdigit() and int(parts[4]) == size_mb * 1024 * 1024):
            dt = time.time() - t0
            res["share_%dmb_s" % size_mb] = round(dt, 2)
            res["share_%dmb_MBps" % size_mb] = round(size_mb / dt, 2)
            return True
        time.sleep(0.3)
    res["share_%dmb_s" % size_mb] = "FAIL"
    return False
share_file(1)
share_file(10)

# SFTP: start, list, download 10 MB file
def sftp():
    import paramiko
    m = mark(); t0 = time.time()
    send("kdeconnect.sftp.request", {"startBrowsing": True})
    r = wait_packet(m, "kdeconnect.sftp", lambda b: "port" in b, 90)
    if not r:
        return False
    res["sftp_start"] = round(time.time() - t0, 2)
    b = r["body"]
    subprocess.run([ADB, "forward", "tcp:%d" % b["port"], "tcp:%d" % b["port"]], capture_output=True)
    t1 = time.time()
    t = paramiko.Transport(("127.0.0.1", b["port"])); t.connect(username=b["user"], password=b["password"])
    sf = paramiko.SFTPClient.from_transport(t)
    res["sftp_login"] = round(time.time() - t1, 2)
    path = b.get("path", "/")
    t2 = time.time(); sf.listdir(path); res["sftp_list"] = round(time.time() - t2, 2)
    # find the 10 MB file under Download
    for root in [path] + ["%s/%s" % (path, d) for d in sf.listdir(path)]:
        try:
            names = sf.listdir(root + "/Download")
        except Exception:
            continue
        big = [n for n in names if n.startswith("bench-10mb")]
        if big:
            t3 = time.time(); sf.get(root + "/Download/" + big[0], "/tmp/kdeconnect-sftp-dl.bin")
            dt = time.time() - t3
            res["sftp_10mb_download_MBps"] = round(10 / dt, 2)
            break
    t.close()
    return True
try:
    timed("sftp_total", sftp)
except Exception as e:
    res["sftp_total"] = "FAIL " + repr(e)[:60]

# incoming call -> ringing event
def call():
    m = mark(); adb("emu", "gsm", "call", "5550001")
    ok = wait_packet(m, "kdeconnect.telephony", lambda b: b.get("event") == "ringing")
    adb("emu", "gsm", "cancel", "5550001")
    return ok
timed("incoming_call_event", call); time.sleep(3)

# find my phone: request -> ring screen in front; stop with Found it
def fmp():
    send("kdeconnect.findmyphone.request", {})
    return wait_focus("FindMyPhoneActivity")
timed("find_my_phone_rings", fmp)
adb("shell", "input", "keyevent", "SOFT_RIGHT"); time.sleep(3)

# ping -> notification posted
def ping():
    send("kdeconnect.ping", {"message": "bench-ping"})
    t0 = time.time()
    while time.time() - t0 < 30:
        if "bench-ping" in adb("shell", "dumpsys", "notification"):
            return True
        time.sleep(0.3)
    return False
timed("ping_notification", ping)

print(sys.argv[1], json.dumps(res))
