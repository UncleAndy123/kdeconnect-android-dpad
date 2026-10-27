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
--simulate-no-store: play a phone that doesn't store SMS sent by other apps (the reporter's): a trigger in the
SMS database (root adb + sqlite3) deletes the row the moment Android inserts it, so it is never there for the
phone's observer or anyone else. The fix must then echo the message itself (negative _id). (Deleting it with
`content delete` from the shell was too slow on the emulator: seconds per call, after the echo had gone out.)
Every run also counts the echoes: more than one for the same text is a duplicate on the computer.
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
nostore = "--simulate-no-store" in sys.argv
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
DB = "/data/user_de/0/com.android.providers.telephony/databases/mmssms.db"
TRIGGER = ("CREATE TRIGGER IF NOT EXISTS issue3_nostore AFTER INSERT ON sms WHEN new.body LIKE 'issue3nostore-%' "
           "BEGIN DELETE FROM sms WHERE _id = new._id; END;")
if nostore:
    body = "issue3nostore-%s-%d" % (label, int(time.time()))
    adb("shell", "sqlite3 %s \"%s\"" % (DB, TRIGGER))
    have = adb("shell", "sqlite3 %s \"select name from sqlite_master where type='trigger' and name='issue3_nostore'\"" % DB)
    if "issue3_nostore" not in have:
        sys.exit("could not install the no-store trigger (adb root?)")
t0 = time.time()
send("kdeconnect.sms.request", {"version": 2, "addresses": [{"address": TO}], "messageBody": body})
say("sent %r to %s through the stand-in%s" % (body, TO, " (the SMS database drops it on insert)" if nostore else ""))


def is_echo(r):
    return (r.get("dir") == "phone->pc" and r["type"] == "kdeconnect.sms.messages"
            and body in json.dumps(r["body"]))


echo = wait_for(is_echo, mark, 90)
say("echo back to the desktop: %s" % ("after %.1f s" % (time.time() - t0) if echo else "NONE within 90 s"))
if echo:
    for m in echo["body"].get("messages", []):
        if m.get("body") == body:
            say("  echoed as: type=%s thread=%s date=%s" % (m.get("type"), m.get("thread_id"), m.get("date")))
if echo:
    time.sleep(20)  # anything else for the same text (a duplicate) would come within seconds
echoes = [json.loads(l) for l in lines()[mark:]]
ids = [m.get("_id") for r in echoes if r.get("dir") == "phone->pc" and r["type"] == "kdeconnect.sms.messages"
       for m in r["body"].get("messages", []) if m.get("body") == body]
# The observer can send the same stored message more than once (insert, then update); the computer keeps one
# message per _id, so what would show twice is two DIFFERENT ids for the same text
distinct = sorted(set(ids), key=str)
say("echoes of this text: %d packets, %d distinct message id(s) %s" % (len(ids), len(distinct), distinct))
if nostore:
    adb("shell", "sqlite3 %s \"DROP TRIGGER IF EXISTS issue3_nostore\"" % DB)
say("SMS database row: %s" % in_db(body))
other = [json.loads(l) for l in lines()[mark:]]
other = [r["type"] for r in other if r.get("dir") == "phone->pc"]
say("other packets from the phone meanwhile: %s" % (", ".join(sorted(set(other))) or "none"))
log = adb("logcat", "-d", "-s", "SENDING_MESSAGE:*", "Transaction:*", "SMSPlugin:*", "SmsMmsUtils:*", "AndroidRuntime:E")
say("logcat:\n" + "\n".join(l for l in log.splitlines() if l.strip() and "beginning of" not in l)[-3000:])
if nostore:
    # The app's own echo has a negative _id; Android's copy never stays in the database, so nothing else can echo
    own = [i for i in ids if isinstance(i, int) and i < 0]
    say("the app's own echo: %s" % ("yes" if own else "NO"))
    verdict = "PASS" if len(own) == 1 and len(distinct) == 1 else ("FAIL-DUPLICATE" if len(distinct) > 1 else "FAIL")
else:
    verdict = "PASS" if echo and len(distinct) == 1 else ("FAIL-DUPLICATE" if echo else "FAIL")
say("%s ISSUE3 %s (restart=%s, simulate-no-store=%s)" % (label, verdict, restart, nostore))
os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
with open(os.path.join(HERE, "results", "issue3-%s%s%s.txt" % (label, "-restart" if restart else "", "-nostore" if nostore else "")), "w") as f:
    f.write("\n".join(out) + "\n")
