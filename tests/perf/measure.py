#!/usr/bin/env python3
"""
Times what the desktop SMS app and contact sync ask of the phone, through the stand-in desktop.
  measure.py <label>
Scenario (what a desktop does right after connecting):
  t0: request_all_uids_timestamps, then request_vcards_by_uid for every uid it gets
  then immediately: request_conversations, and "send this SMS"
Reports seconds until: uid list, first/last vcard batch, first/last conversation, the sent SMS echoed back.
"""
import json, os, os, subprocess, sys, time

S = os.environ.get("STANDIN_STATE") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "devices", ".standin")
LOG = os.path.join(S, "packets.jsonl")
STANDIN = ["python3", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "devices", "standin_pc.py"), "send"]


def send(t, body):
    out = subprocess.run(STANDIN + [t, json.dumps(body)], capture_output=True, text=True).stdout.strip()
    if out != "ok":
        sys.exit("send %s failed: %s" % (t, out))


def lines_since(n):
    with open(LOG) as f:
        return [json.loads(l) for l in f.readlines()[n:]]


def wait(pred, start_line, timeout):
    t = time.time()
    while time.time() - t < timeout:
        for r in lines_since(start_line):
            if pred(r):
                return r
        time.sleep(0.5)
    return None


label = sys.argv[1]
mark = sum(1 for _ in open(LOG))
t0 = time.time()
body = "perf-%d" % int(t0)
send("kdeconnect.contacts.request_all_uids_timestamps", {})
send("kdeconnect.sms.request_conversations", {})
send("kdeconnect.sms.request", {"version": 2, "addresses": [{"address": "5551234"}], "messageBody": body})
res = {}
uids = wait(lambda r: r["type"] == "kdeconnect.contacts.response_uids_timestamps", mark, 900)
res["uid_list"] = time.time() - t0 if uids else None
if uids:
    send("kdeconnect.contacts.request_vcards_by_uid", {"uids": uids["body"]["uids"]})
want = len(uids["body"]["uids"]) if uids else 0
deadline = time.time() + 1800
first_conv = last_conv = first_vc = last_vc = echo = None
convs = 0
got = 0
seen = mark
while time.time() < deadline:
    rows = lines_since(seen); seen += len(rows)
    for r in rows:
        if r.get("dir") != "phone->pc":
            continue
        now = time.time() - t0
        if r["type"] == "kdeconnect.contacts.response_vcards":
            got += len(r["body"].get("uids", []))
            first_vc = first_vc or now; last_vc = now
        if r["type"] == "kdeconnect.sms.messages":
            msgs = r["body"].get("messages", [])
            if any(m.get("body") == body for m in msgs):
                echo = echo or now
            elif msgs and len({m.get("thread_id") for m in msgs}) == len(msgs):
                # conversation list entries: one message per thread (one per packet, or batched)
                convs += len(msgs); first_conv = first_conv or now; last_conv = now
    vc_done = got >= want or (last_vc is not None and time.time() - t0 > last_vc + 20)
    if vc_done and echo and last_conv and time.time() - t0 > (last_conv or 0) + 20:
        break
    time.sleep(0.5)
res.update(contacts=want, vcards_received=got, first_vcards=first_vc, all_vcards=last_vc,
           conversations=convs, first_conversation=first_conv, last_conversation=last_conv, sent_sms_echoed=echo)
print(label, json.dumps({k: (round(v, 1) if isinstance(v, float) else v) for k, v in res.items()}))
