#!/usr/bin/env python3
"""
Issue #5: "Picture messages (MMS) on the computer" (on / off).
  issue5.py <label> [on|off ...]        default: on off
Needs seed.sh + seed-mms.sh data (pictures in threads 1000-1019). For each value: stop the app, write the setting
into its preferences (accessibility service off first, or the app restarts and writes its prefs back over the edit),
start it, wait for the stand-in desktop, then
  1. the conversation list (what the desktop asks on every connect): seconds to the last conversation, how many,
     bytes received, and how many of the listed newest messages are MMS;
  2. one conversation with pictures (thread 1000, newest 50 messages, what opening it on the desktop asks): seconds,
     bytes, MMS count.
Off must list every conversation (they all have texts) with no MMS in either reply.
Writes results/issue5-<label>.txt.
"""
import json, os, re, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
S = os.environ.get("STANDIN_STATE") or os.path.join(HERE, "..", "devices", ".standin")
LOG = os.path.join(S, "packets.jsonl")
STANDIN = ["python3", os.path.join(HERE, "..", "devices", "standin_pc.py"), "send"]
ADB = os.path.join(os.environ.get("ANDROID_HOME", "/opt/android-sdk"), "platform-tools", "adb")
PKG = "com.android.cts.kdeconnect"
PREFS = "/data/data/%s/shared_prefs/%s_preferences.xml" % (PKG, PKG)
KEY = "sms_send_mms_to_computer"
DB = "/data/user_de/0/com.android.providers.telephony/databases/mmssms.db"
THREAD = 1000


def adb(*a):
    return subprocess.run([ADB, *a], capture_output=True, text=True).stdout


def send(t, body):
    out = subprocess.run(STANDIN + [t, json.dumps(body)], capture_output=True, text=True).stdout.strip()
    if out != "ok":
        sys.exit("send %s failed: %s" % (t, out))


def lines():
    with open(LOG) as f:
        return f.readlines()


LISTENERS = None  # the notification listeners setting as it was, restored on every start


def set_pref(on):
    # Android restarts the app right after a force-stop for its accessibility service AND its notification listener;
    # a running app writes its own copy of the preferences back over an edit, so switch both off while writing
    global LISTENERS
    if LISTENERS is None:
        LISTENERS = adb("shell", "settings", "get", "secure", "enabled_notification_listeners").strip()
    others = ":".join(x for x in LISTENERS.split(":") if x and not x.startswith(PKG + "/") and x != "null")
    adb("shell", "settings", "put", "secure", "enabled_notification_listeners", others or '""')
    adb("shell", "settings", "put", "secure", "enabled_accessibility_services", "")
    adb("shell", "am", "force-stop", PKG)
    time.sleep(3)
    if adb("shell", "pidof", PKG).strip():
        adb("shell", "am", "force-stop", PKG)
        time.sleep(3)
    xml = adb("shell", "cat", PREFS)
    xml = re.sub(r'\s*<boolean name="%s" value="[a-z]*" />' % KEY, "", xml)
    xml = xml.replace("</map>", '    <boolean name="%s" value="%s" />\n</map>' % (KEY, "true" if on else "false"))
    tmp = "/data/local/tmp/kc_prefs.xml"
    subprocess.run([ADB, "shell", "cat > %s" % tmp], input=xml, text=True, capture_output=True)
    d = "/data/data/%s/shared_prefs" % PKG
    adb("shell", "cp %s %s && chown $(stat -c %%u:%%g %s) %s && chmod 660 %s && chcon $(ls -Zd %s | cut -d' ' -f1) %s"
        % (tmp, PREFS, d, PREFS, PREFS, d, PREFS))
    return adb("shell", "grep", KEY, PREFS).strip()


def start_and_connect():
    n = len(lines())
    if LISTENERS:
        adb("shell", "settings", "put", "secure", "enabled_notification_listeners", LISTENERS)
    adb("shell", "settings", "put", "secure", "enabled_accessibility_services",
        PKG + "/org.kde.kdeconnect.KdeConnectAccessibilityService")
    adb("shell", "am", "start", "-n", PKG + "/org.kde.kdeconnect.ui.MainActivity")
    t = time.time()
    while time.time() - t < 300:
        if any("identity over TLS" in l for l in lines()[n:]):
            return time.time() - t
        time.sleep(1)
    return None


def is_mms(m):
    # The phone sends MMS with attachments or the multi-target flag; an SMS has neither and an MMS id comes from pdu
    return bool(m.get("attachments")) or (m.get("_id", 0) >= 50000 and m.get("thread_id", 0) in range(1000, 1020))


def collect(t0, mark, quiet=15, timeout=300):
    """sms.messages packets from the phone until none for `quiet` seconds: (last time, messages, bytes)"""
    last, msgs, size, seen, quiet_since = None, [], 0, mark, time.time()
    while time.time() - t0 < timeout:
        new = lines()[seen:]
        seen += len(new)
        for l in new:
            r = json.loads(l)
            if r.get("dir") == "phone->pc" and r["type"] == "kdeconnect.sms.messages":
                last = time.time() - t0
                quiet_since = time.time()
                size += len(l)
                msgs += r["body"].get("messages", [])
        if last is not None and time.time() - quiet_since > quiet:
            break
        time.sleep(0.5)
    return last, msgs, size


label = sys.argv[1]
values = sys.argv[2:] or ["on", "off"]
out = []
say = lambda s: (out.append(s), print(s, flush=True))
threads = int(adb("shell", "sqlite3 %s 'select count(distinct thread_id) from sms'" % DB).strip() or 0)
mms = int(adb("shell", "sqlite3 %s 'select count(*) from pdu'" % DB).strip() or 0)
say("database: %d conversations with texts, %d MMS" % (threads, mms))
for v in values:
    on = v == "on"
    say("== MMS %s: %s" % (v, set_pref(on)))
    c = start_and_connect()
    say("connected after %s s" % (None if c is None else round(c)))
    time.sleep(20)
    kept = adb("shell", "grep", KEY, PREFS).strip()
    say("setting after the app started: %s" % (kept or "GONE (the app overwrote it)"))
    mark = len(lines()); t0 = time.time()
    send("kdeconnect.sms.request_conversations", {})
    last, msgs, size = collect(t0, mark)
    convs = {m.get("thread_id") for m in msgs}
    nm = sum(1 for m in msgs if is_mms(m))
    say("conversation list: last at %.1f s, %d conversations, %d KB, %d of the newest messages are MMS"
        % (last or -1, len(convs), size // 1024, nm))
    mark = len(lines()); t0 = time.time()
    send("kdeconnect.sms.request_conversation", {"threadID": THREAD, "numberToRequest": 50})
    last2, msgs2, size2 = collect(t0, mark, quiet=10)
    nm2 = sum(1 for m in msgs2 if is_mms(m))
    say("conversation %d: last at %.1f s, %d messages, %d KB, %d MMS" % (THREAD, last2 or -1, len(msgs2), size2 // 1024, nm2))
    if on:
        ok = len(convs) == threads and nm > 0 and nm2 > 0
    else:
        ok = len(convs) == threads and nm == 0 and nm2 == 0
    say("MMS %s: %s" % (v, "PASS" if ok else "FAIL"))
set_pref(True)
start_and_connect()
say("setting put back to on")
os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
with open(os.path.join(HERE, "results", "issue5-%s.txt" % label), "w") as f:
    f.write("\n".join(out) + "\n")
