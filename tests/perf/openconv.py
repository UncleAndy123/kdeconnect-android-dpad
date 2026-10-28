#!/usr/bin/env python3
"""openconv.py <label>: the desktop opens the 3000-message conversation (thread 1000): newest 50 messages,
then scrolls back once (50 older than the oldest it got). Times each reply."""
import json, os, os, subprocess, sys, time
S = os.environ.get("STANDIN_STATE") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "devices", ".standin"); LOG = os.path.join(S, "packets.jsonl")
STANDIN = ["python3", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "devices", "standin_pc.py"), "send"]


def req(body):
    mark = sum(1 for _ in open(LOG))
    t0 = time.time()
    out = subprocess.run(STANDIN + ["kdeconnect.sms.request_conversation", json.dumps(body)], capture_output=True, text=True).stdout.strip()
    if out != "ok":
        sys.exit("send failed: " + out)
    while time.time() - t0 < 300:
        with open(LOG) as f:
            for l in f.readlines()[mark:]:
                r = json.loads(l)
                if r.get("dir") == "phone->pc" and r["type"] == "kdeconnect.sms.messages":
                    msgs = r["body"].get("messages", [])
                    if msgs and all(m.get("thread_id") == body["threadID"] for m in msgs):
                        return round(time.time() - t0, 1), msgs
        time.sleep(0.2)
    return None, []


t1, msgs = req({"threadID": 1000, "numberToRequest": 50})
oldest = min(m["date"] for m in msgs) if msgs else 0
t2, msgs2 = req({"threadID": 1000, "rangeStartTimestamp": oldest - 1, "numberToRequest": 50})
print(sys.argv[1], json.dumps({"newest50_s": t1, "n1": len(msgs), "older50_s": t2, "n2": len(msgs2)}))
