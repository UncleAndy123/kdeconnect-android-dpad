#!/usr/bin/env python3
"""
incoming.py <label>: a text arrives on the phone (emulator modem). Times until the desktop gets
(a) the new SMS (kdeconnect.sms.messages) and (b) the Messaging app's notification (kdeconnect.notification).
Runs 3 times, prints each.
"""
import json, os, os, subprocess, sys, time
S = os.environ.get("STANDIN_STATE") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "devices", ".standin"); LOG = os.path.join(S, "packets.jsonl")
ADB = os.environ.get("ADB", "adb")
out = []
for i in range(3):
    body = "incoming-%d-%d" % (int(time.time()), i)
    mark = sum(1 for _ in open(LOG))
    t0 = time.time()
    subprocess.run([ADB, "emu", "sms", "send", "5559876", body], capture_output=True)
    sms = notif = nid = app = None
    while time.time() - t0 < 120 and (sms is None or notif is None or app is None):
        with open(LOG) as f:
            rows = [json.loads(l) for l in f.readlines()[mark:]]
        for r in rows:
            if r.get("dir") != "phone->pc":
                continue
            txt = json.dumps(r["body"])
            if body in txt:
                if r["type"] == "kdeconnect.sms.messages" and sms is None:
                    sms = round(time.time() - t0, 1)
                if r["type"] == "kdeconnect.notification" and notif is None:
                    notif = round(time.time() - t0, 1); nid = r["body"].get("id")
            # The SMS app's own notification: should update the early one (same id), silently
            if r["type"] == "kdeconnect.notification" and nid and not str(r["body"].get("id", "")).startswith("kdeconnect.sms|") \
                    and app is None and notif is not None and r["body"].get("appName"):
                app = {"t": round(time.time() - t0, 1), "same_id": r["body"].get("id") == nid, "silent": r["body"].get("silent")}
            if r["type"] == "kdeconnect.notification" and nid and r["body"].get("id") == nid and r is not None \
                    and app is None and r["body"].get("silent") is True:
                app = {"t": round(time.time() - t0, 1), "same_id": True, "silent": True}
        time.sleep(0.3)
        if notif is not None and app is None and time.time() - t0 > 40:
            break
    out.append({"sms": sms, "notification": notif, "app_notification": app})
    time.sleep(10)
print(sys.argv[1], json.dumps(out))
