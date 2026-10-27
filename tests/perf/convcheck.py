#!/usr/bin/env python3
"""
convcheck.py <label>: asks for the conversation list and checks every conversation the phone sends is that thread's
newest message according to the phone's own message database (SMS and MMS), and that every thread is there.
"""
import json, os, os, subprocess, sys, time
S = os.environ.get("STANDIN_STATE") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "devices", ".standin"); LOG = os.path.join(S, "packets.jsonl")
ADB = os.environ.get("ADB", "adb")
STANDIN = ["python3", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "devices", "standin_pc.py"), "send"]
DB = "/data/data/com.android.providers.telephony/databases/mmssms.db"


def rows(uri):
    """Every message's thread and date, straight from the message tables (not the app's conversations query)."""
    out = subprocess.run([ADB, "shell", "content query --uri %s --projection thread_id:date" % uri],
                         capture_output=True, text=True).stdout
    for l in out.split("\n"):
        if "thread_id=" in l and "date=" in l:
            t = l.split("thread_id=")[1].split(",")[0].strip(); d = l.split("date=")[1].split(",")[0].strip()
            if t.isdigit() and d.isdigit():
                yield int(t), int(d)


newest = {}
for tid, d in rows("content://sms"):
    newest[tid] = max(newest.get(tid, 0), d)
for tid, d in rows("content://mms"):
    newest[tid] = max(newest.get(tid, 0), d * 1000)

mark = sum(1 for _ in open(LOG))
t0 = time.time()
subprocess.run(STANDIN + ["kdeconnect.sms.request_conversations", "{}"], capture_output=True)
got = {}
last = None
while time.time() - t0 < 120:
    with open(LOG) as f:
        rows = [json.loads(l) for l in f.readlines()[mark:]]
    for r in rows:
        if r.get("dir") == "phone->pc" and r["type"] == "kdeconnect.sms.messages":
            msgs = r["body"].get("messages", [])
            if len({m.get("thread_id") for m in msgs}) == len(msgs):
                for m in msgs:
                    if m["thread_id"] not in got:
                        got[m["thread_id"]] = m["date"]; last = time.time() - t0
    if len(got) >= len(newest) or (last and time.time() - t0 > last + 15):
        break
    time.sleep(0.3)
missing = sorted(set(newest) - set(got))
wrong = sorted(t for t in got if t in newest and got[t] != newest[t])
ok = bool(newest) and not missing and not wrong
print(sys.argv[1], "CONV", "PASS" if ok else "FAIL",
      json.dumps({"threads": len(newest), "sent": len(got), "all_sent_s": round(last or -1, 1),
                  "missing": missing[:10], "not_newest": [(t, got[t], newest[t]) for t in wrong[:5]]}))
