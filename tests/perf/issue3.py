#!/usr/bin/env python3
"""
Issue #3: "Messages sent via KDE Connect do not appear" on the desktop.
  issue3.py <label> [--restart]
The desktop SMS app's flow: open the window (request_conversations), open a conversation, type, send
(kdeconnect.sms.request). The desktop shows the sent message only once the phone echoes it back in a
kdeconnect.sms.messages packet. This sends one SMS through the stand-in desktop and reports:
  - whether Android stored it in the SMS database (content://sms, by body) and with which type
    (4 = outbox, 2 = sent, 5 = failed)
  - whether and when the phone echoed it back
--restart: force-stop and restart the app between opening the window and sending (a low-memory phone
kills the app in the background; the desktop doesn't ask for the conversations again on reconnect).
Writes results/issue3-<label>.txt.
"""
import json, os, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
S = os.environ.get("STANDIN_STATE") or os.path.join(HERE, "..", "devices", ".standin")
LOG = os.path.join(S, "packets.jsonl")
STANDIN = ["python3", os.path.join(HERE, "..", "devices", "standin_pc.py"), "send"]
ADB = os.path.join(os.environ.get("ANDROID_HOME", "/opt/android-sdk"), "platform-tools", "adb")
PKG = "com.android.cts.kdeconnect"
TO = "5551234"


def send(t, body):
    out = subprocess.run(STANDIN + [t, json.dumps(body)], capture_output=True, text=True).stdout.strip()
    if out != "ok":
        sys.exit("send %s failed: %s" % (t, out))


def adb(*a):
    return subprocess.run([ADB, *a], capture_output=True, text=True).stdout


def lines():
    with open(LOG) as f:
        return f.readlines()


def wait_for(pred, start, timeout):
    t = time.time()
    while time.time() - t < timeout:
        for l in lines()[start:]:
            r = json.loads(l)
            if pred(r):
                return r
        time.sleep(0.5)
    return None


def connected_since(start):
    return any("identity over TLS" in l for l in lines()[start:])


def in_db(body):
    out = adb("shell", "content query --uri content://sms --projection _id:type:date:address:thread_id "
              "--where \"body='%s'\"" % body)
    return out.strip() or "(not in the SMS database)"


label = sys.argv[1]
restart = "--restart" in sys.argv
out = []
say = lambda s: (out.append(s), print(s, flush=True))

mark = len(lines())
send("kdeconnect.sms.request_conversations", {})
first = wait_for(lambda r: r.get("dir") == "phone->pc" and r["type"] == "kdeconnect.sms.messages", mark, 120)
say("conversation list answered: %s" % ("yes" if first else "NO"))

if restart:
    mark = len(lines())
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "settings", "put", "secure", "enabled_accessibility_services",
        PKG + "/org.kde.kdeconnect.KdeConnectAccessibilityService")
    adb("shell", "am", "start", "-n", PKG + "/org.kde.kdeconnect.ui.MainActivity")
    t = time.time()
    while not connected_since(mark) and time.time() - t < 300:
        time.sleep(2)
    say("restarted the app; reconnected: %s after %.0f s" % (connected_since(mark), time.time() - t))
    time.sleep(10)

adb("logcat", "-c")
body = "issue3-%s-%d" % (label, int(time.time()))
mark = len(lines())
t0 = time.time()
send("kdeconnect.sms.request", {"version": 2, "addresses": [{"address": TO}], "messageBody": body})
say("sent %r to %s through the stand-in" % (body, TO))


def is_echo(r):
    return (r.get("dir") == "phone->pc" and r["type"] == "kdeconnect.sms.messages"
            and body in json.dumps(r["body"]))


echo = wait_for(is_echo, mark, 90)
say("echo back to the desktop: %s" % ("after %.1f s" % (time.time() - t0) if echo else "NONE within 90 s"))
if echo:
    for m in echo["body"].get("messages", []):
        if m.get("body") == body:
            say("  echoed as: type=%s thread=%s date=%s" % (m.get("type"), m.get("thread_id"), m.get("date")))
say("SMS database row: %s" % in_db(body))
other = [json.loads(l) for l in lines()[mark:]]
other = [r["type"] for r in other if r.get("dir") == "phone->pc"]
say("other packets from the phone meanwhile: %s" % (", ".join(sorted(set(other))) or "none"))
log = adb("logcat", "-d", "-s", "SENDING_MESSAGE:*", "Transaction:*", "SMSPlugin:*", "SmsMmsUtils:*", "AndroidRuntime:E")
say("logcat:\n" + "\n".join(l for l in log.splitlines() if l.strip() and "beginning of" not in l)[-3000:])
verdict = "PASS" if echo else "FAIL"
say("%s ISSUE3 %s (restart=%s)" % (label, verdict, restart))
os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
with open(os.path.join(HERE, "results", "issue3-%s%s.txt" % (label, "-restart" if restart else "")), "w") as f:
    f.write("\n".join(out) + "\n")
