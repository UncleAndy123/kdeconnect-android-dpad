#!/usr/bin/env python3
"""
artcheck.py <label>: album art sent by the desktop (the payload transfer path). The stand-in desktop announces
payload support, sets an albumArtUrl, waits for the phone to ask for it, sends the image, then checks the phone
stored it and decoded it without errors. Prints one JSON line.
"""
import json, os, os, struct, subprocess, sys, time, zlib
S = os.environ.get("STANDIN_STATE") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "devices", ".standin"); LOG = os.path.join(S, "packets.jsonl")
ADB = os.environ.get("ADB", "adb")
STANDIN = ["python3", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "devices", "standin_pc.py"), "send"]
PKG = "com.android.cts.kdeconnect"
PLAYER = {"player": "Elisa", "title": "Clair de Lune", "artist": "Claude Debussy", "album": "Suite bergamasque",
          "isPlaying": True, "canPause": True, "canPlay": True, "canGoNext": True, "canGoPrevious": True,
          "canSeek": True, "pos": 95000, "length": 302000, "volume": 70}


def png(path, w=1200, h=1200):
    """A real 1200x1200 PNG (bigger than any phone screen, so it must be scaled down)."""
    row = b"\x00" + bytes((x * 255 // w, 80, 200) [i % 3] for x in range(w) for i in range(3))
    raw = row * h
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


def send(t, body, file=None):
    return subprocess.run(STANDIN + [t, json.dumps(body)] + (["--file", file] if file else []),
                          capture_output=True, text=True).stdout.strip() == "ok"


def adb(*a):
    return subprocess.run([ADB, *a], capture_output=True, text=True).stdout


res = {}
art = "/tmp/kdeconnect-art-test.png"; png(art)
url = "file:///home/user/Music/art-%d.png" % int(time.time())
adb("logcat", "-c")
mark = sum(1 for _ in open(LOG))
t0 = time.time()
send("kdeconnect.mpris", {"playerList": ["Elisa"], "supportAlbumArtPayload": True})
send("kdeconnect.mpris", dict(PLAYER, albumArtUrl=url))
asked = None
while time.time() - t0 < 60 and asked is None:
    with open(LOG) as f:
        for l in f.readlines()[mark:]:
            r = json.loads(l)
            if r.get("dir") == "phone->pc" and r["type"] == "kdeconnect.mpris.request" and (r.get("body") or {}).get("albumArtUrl") == url:
                asked = round(time.time() - t0, 2)
    time.sleep(0.2)
res["phone_asked_for_art_s"] = asked
if asked is not None:
    t1 = time.time()
    send("kdeconnect.mpris", {"player": "Elisa", "albumArtUrl": url, "transferringAlbumArt": True}, art)
    stored = None
    while time.time() - t1 < 60:
        files = adb("shell", "ls", "-l", "/data/data/%s/cache/album_art_files/" % PKG).split("\n")
        files = [l for l in files if l.strip() and not l.startswith("total") and ".tmp" not in l]
        if files:
            stored = round(time.time() - t1, 2)
            res["stored_bytes"] = files[0].split()[3] if len(files[0].split()) > 4 else files[0]
            break
        time.sleep(0.3)
    res["stored_s"] = stored
    time.sleep(3)
    res["media_session"] = [l.strip() for l in adb("shell", "dumpsys", "media_session").split("\n") if "metadata" in l][:2]
    errs = [l for l in adb("logcat", "-d").split("\n") if "AlbumArtCache" in l or "FATAL" in l]
    res["errors"] = errs[:3]
    res["old_coil_dir_gone"] = "No such file" in adb("shell", "ls", "/data/data/%s/cache/album_art" % PKG) or \
        adb("shell", "ls", "/data/data/%s/cache/album_art" % PKG).strip() == ""
ok = res.get("stored_s") is not None and not res.get("errors")
print(sys.argv[1], "ART", "PASS" if ok else "FAIL", json.dumps(res))
