#!/usr/bin/env python3
"""
Issue #4: "Conversations sent to the computer" (All / newest 50 / newest 20).
  issue4.py <label> [setting ...]      settings: all 50 20 (default: all 20)
For each setting: stop the app, write the setting into its preferences, start it, wait until the stand-in desktop is
connected, then ask for the conversation list (what the desktop SMS window does on every connect) and report the
seconds until the first and the last conversation, how many conversations came, and whether they are the newest ones
(each conversation's newest message must be newer than that of every conversation left out).
Writes results/issue4-<label>.txt.
"""
import json, os, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
S = os.environ.get("STANDIN_STATE") or os.path.join(HERE, "..", "devices", ".standin")
LOG = os.path.join(S, "packets.jsonl")
STANDIN = ["python3", os.path.join(HERE, "..", "devices", "standin_pc.py"), "send"]
ADB = os.path.join(os.environ.get("ANDROID_HOME", "/opt/android-sdk"), "platform-tools", "adb")
PKG = "com.android.cts.kdeconnect"
PREFS = "/data/data/%s/shared_prefs/%s_preferences.xml" % (PKG, PKG)
KEY = "sms_conversations_to_computer"
DB = "/data/user_de/0/com.android.providers.telephony/databases/mmssms.db"


def adb(*a):
    return subprocess.run([ADB, *a], capture_output=True, text=True).stdout


def send(t, body):
    out = subprocess.run(STANDIN + [t, json.dumps(body)], capture_output=True, text=True).stdout.strip()
    if out != "ok":
        sys.exit("send %s failed: %s" % (t, out))


def lines():
    with open(LOG) as f:
        return f.readlines()


def set_pref(value):
    # The app's accessibility service restarts the app right after a force-stop, and a running app writes its own
    # copy of the preferences back over an edit: switch the service off first, and check the app stays stopped
    adb("shell", "settings", "put", "secure", "enabled_accessibility_services", "")
    adb("shell", "am", "force-stop", PKG)
    time.sleep(3)
    if adb("shell", "pidof", PKG).strip():
        adb("shell", "am", "force-stop", PKG)
        time.sleep(3)
    xml = adb("shell", "cat", PREFS)
    import re
    xml = re.sub(r'\s*<string name="%s">[^<]*</string>' % KEY, "", xml)
    xml = xml.replace("</map>", '    <string name="%s">%s</string>\n</map>' % (KEY, value))
    tmp = "/data/local/tmp/kc_prefs.xml"
    p = subprocess.run([ADB, "shell", "cat > %s" % tmp], input=xml, text=True, capture_output=True)
    d = "/data/data/%s/shared_prefs" % PKG
    adb("shell", "cp %s %s && chown $(stat -c %%u:%%g %s) %s && chmod 660 %s && chcon $(ls -Zd %s | cut -d' ' -f1) %s"
        % (tmp, PREFS, d, PREFS, PREFS, d, PREFS))
    got = adb("shell", "grep", KEY, PREFS).strip()
    running = adb("shell", "pidof", PKG).strip()
    return got + (" (WARNING: the app was running while it was written)" if running else "")


def start_and_connect():
    n = len(lines())
    adb("shell", "settings", "put", "secure", "enabled_accessibility_services",
        PKG + "/org.kde.kdeconnect.KdeConnectAccessibilityService")
    adb("shell", "am", "start", "-n", PKG + "/org.kde.kdeconnect.ui.MainActivity")
    t = time.time()
    while time.time() - t < 300:
        if any("identity over TLS" in l for l in lines()[n:]):
            return time.time() - t
        time.sleep(1)
    return None


def newest_per_thread():
    """thread_id -> newest message date (ms), straight from the database (SMS only; the seed has no MMS)"""
    out = adb("shell", "sqlite3 %s \"select thread_id, max(date) from sms group by thread_id\"" % DB)
    res = {}
    for l in out.split():
        if "|" in l:
            a, b = l.split("|")
            res[int(a)] = int(b)
    return res


label = sys.argv[1]
settings = sys.argv[2:] or ["all", "20"]
out = []
say = lambda s: (out.append(s), print(s, flush=True))
truth = newest_per_thread()
say("database: %d conversations" % len(truth))
for value in settings:
    say("== setting %s: %s" % (value, set_pref(value)))
    c = start_and_connect()
    say("connected after %s s" % (None if c is None else round(c)))
    kept = adb("shell", "grep", KEY, PREFS).strip()
    say("setting after the app started: %s" % (kept or "GONE (the app overwrote it)"))
    time.sleep(20)  # let the connect's own traffic (mpris, contacts, ...) pass
    mark = len(lines())
    t0 = time.time()
    send("kdeconnect.sms.request_conversations", {})
    first = last = None
    threads = {}
    quiet_since = time.time()
    seen = mark
    while time.time() - t0 < 300:
        new = lines()[seen:]
        seen += len(new)
        for l in new:
            r = json.loads(l)
            if r.get("dir") == "phone->pc" and r["type"] == "kdeconnect.sms.messages":
                now = time.time() - t0
                first = first if first is not None else now
                last = now
                quiet_since = time.time()
                for m in r["body"].get("messages", []):
                    threads[m.get("thread_id")] = max(threads.get(m.get("thread_id"), 0), m.get("date", 0))
        if first is not None and time.time() - quiet_since > 15:
            break
        time.sleep(0.5)
    want = None if value == "all" else int(value)
    expected = len(truth) if want is None else min(want, len(truth))
    sent = set(threads)
    left = [t for t in truth if t not in sent]
    newest_ok = not left or not sent or min(truth.get(t, 0) for t in sent) >= max(truth[t] for t in left)
    ok = len(sent) == expected and newest_ok
    say("first conversation %.1f s, last %.1f s, %d conversations (expected %d), the newest ones: %s -> %s"
        % (first or -1, last or -1, len(sent), expected, newest_ok, "PASS" if ok else "FAIL"))
set_pref("all")
start_and_connect()
say("setting put back to all")
os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
with open(os.path.join(HERE, "results", "issue4-%s.txt" % label), "w") as f:
    f.write("\n".join(out) + "\n")
