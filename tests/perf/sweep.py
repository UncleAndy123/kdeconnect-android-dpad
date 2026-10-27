#!/usr/bin/env python3
"""
sweep.py <label>: functional check of the plugins the desktop drives, through the stand-in desktop.
Every check sends what the desktop sends and verifies the phone's answer (packet, file, activity, call state).
Prints one PASS/FAIL line per check and a summary.
"""
import json, os, os, subprocess, sys, time, socket
S = os.environ.get("STANDIN_STATE") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "devices", ".standin"); LOG = os.path.join(S, "packets.jsonl")
ADB = os.environ.get("ADB", "adb")
STANDIN = ["python3", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "devices", "standin_pc.py"), "send"]
results = []


def send(t, body=None, file=None):
    cmd = STANDIN + [t, json.dumps(body or {})] + (["--file", file] if file else [])
    out = subprocess.run(cmd, capture_output=True, text=True).stdout.strip()
    return out == "ok"


def mark():
    return sum(1 for _ in open(LOG))


def wait_packet(start, ptype, pred=lambda b: True, timeout=40):
    t0 = time.time()
    while time.time() - t0 < timeout:
        with open(LOG) as f:
            for l in f.readlines()[start:]:
                r = json.loads(l)
                if r.get("dir") == "phone->pc" and r["type"] == ptype and pred(r.get("body") or {}):
                    return r
        time.sleep(0.3)
    return None


def adb(*a):
    return subprocess.run([ADB, *a], capture_output=True, text=True).stdout


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (" - " + detail if detail else ""), flush=True)


# battery / connectivity: pushed by the phone when it connects (protocol 8 has no request for them)
with open(LOG) as f:
    lines = f.readlines()
connected_at = max(i for i, l in enumerate(lines) if "identity over TLS" in l)
check("battery report on connect", wait_packet(connected_at, "kdeconnect.battery", lambda b: "currentCharge" in b, 5))
check("connectivity report on connect", wait_packet(connected_at, "kdeconnect.connectivity_report", lambda b: "signalStrengths" in b, 5))

# notifications: ask for all current ones
m = mark(); send("kdeconnect.notification.request", {"request": True})
check("notifications list", wait_packet(m, "kdeconnect.notification", lambda b: "id" in b))

# incoming text -> SMS push + notification (needs the conversation list asked for once)
send("kdeconnect.sms.request_conversations", {}); time.sleep(5)
body = "sweep-%d" % int(time.time()); m = mark()
adb("emu", "sms", "send", "5554321", body)
check("incoming SMS pushed", wait_packet(m, "kdeconnect.sms.messages", lambda b: any(x.get("body") == body for x in b.get("messages", []))))
check("incoming SMS notification", wait_packet(m, "kdeconnect.notification", lambda b: body in json.dumps(b)))

# contacts: uid list + vCards for the first 5, each with a name
m = mark(); send("kdeconnect.contacts.request_all_uids_timestamps", {})
r = wait_packet(m, "kdeconnect.contacts.response_uids_timestamps")
ok = False
if r:
    uids = r["body"]["uids"][:5]; m = mark()
    send("kdeconnect.contacts.request_vcards_by_uid", {"uids": uids})
    v = wait_packet(m, "kdeconnect.contacts.response_vcards")
    ok = v and all(("FN:" in v["body"].get(u, "") and "TEL" in v["body"].get(u, "")) for u in v["body"].get("uids", [])) and len(v["body"].get("uids", [])) == len(uids)
check("contacts vCards (name + number)", ok)

# telephony: incoming call -> ringing event
m = mark(); adb("emu", "gsm", "call", "5551212")
check("incoming call reported", wait_packet(m, "kdeconnect.telephony", lambda b: b.get("event") == "ringing"))
adb("emu", "gsm", "cancel", "5551212"); time.sleep(3)

# find my phone: rings (activity on top), then the PC's second request stops it
m = mark(); send("kdeconnect.findmyphone.request", {}); time.sleep(6)
top = adb("shell", "dumpsys", "window")
check("find my phone rings", "FindMyPhoneActivity" in top.split("mCurrentFocus")[1][:200] if "mCurrentFocus" in top else False)
adb("shell", "input", "keyevent", "SOFT_RIGHT"); time.sleep(3)
top = adb("shell", "dumpsys", "window")
check("find my phone stops on Found it", "FindMyPhoneActivity" not in top.split("mCurrentFocus")[1][:200])

# share: text + file
m = mark(); send("kdeconnect.share.request", {"text": "sweep text"}); time.sleep(3)
fn = "/tmp/kdeconnect-sweep-file.txt"; open(fn, "w").write("sweep file %d" % time.time())
send("kdeconnect.share.request", {"filename": "sweep-file.txt"}, fn); time.sleep(15)
ls = adb("shell", "ls", "/sdcard/Download/")
check("share: received file saved", "sweep-file" in ls, ls.strip().replace("\n", " ")[:120])

# SFTP: start browsing -> server details -> real SSH login + listing
m = mark(); send("kdeconnect.sftp.request", {"startBrowsing": True})
r = wait_packet(m, "kdeconnect.sftp", lambda b: "port" in b, 60)
ok = False; detail = ""
if r:
    try:
        import paramiko
        port = r["body"]["port"]
        subprocess.run([ADB, "forward", "tcp:%d" % port, "tcp:%d" % port], capture_output=True)
        t = paramiko.Transport(("127.0.0.1", port)); t.connect(username=r["body"]["user"], password=r["body"]["password"])
        sftp = paramiko.SFTPClient.from_transport(t)
        path = r["body"].get("path", "/")
        ok = len(sftp.listdir(path)) > 0; t.close()
    except Exception as e:
        detail = repr(e)[:100]
check("SFTP browse", ok, detail)

# remote keyboard: key with ack -> echo
m = mark(); send("kdeconnect.mousepad.request", {"key": "a", "sendAck": True})
check("remote keyboard ack", wait_packet(m, "kdeconnect.mousepad.echo"))

# ping
m = mark(); send("kdeconnect.ping", {"message": "sweep"}); time.sleep(4)
nd = adb("shell", "dumpsys", "notification")
check("ping shows a notification", "sweep" in nd)

passed = sum(1 for _, ok in results if ok)
print("%s SWEEP %d/%d passed%s" % (sys.argv[1], passed, len(results), "" if passed == len(results) else " FAILED: " + ", ".join(n for n, ok in results if not ok)))
