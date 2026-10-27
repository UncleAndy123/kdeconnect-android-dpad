#!/usr/bin/env python3
"""
Reads the phone's current screen through `uiautomator dump` and answers questions the coverage gate asks.

  ui.py focus             print "<bounds>|<text of the focused view and its children>" (empty if nothing)
  ui.py has TEXT          exit 0 if TEXT (case-insensitive substring) is on screen
  ui.py check [TEXT]      print problems: nothing focused, focus outside the screen, views cut off the
                          right edge; exit 1 if any. With TEXT: exit 2 if TEXT isn't on screen (wrong or
                          not yet loaded screen)
"""
import os, re, shutil, subprocess, sys, xml.etree.ElementTree as ET

ADB = shutil.which("adb") or os.path.join(os.environ.get("ANDROID_HOME", "/opt/android-sdk"), "platform-tools", "adb")


def dump():
    for _ in range(3):
        out = subprocess.run([ADB, "exec-out", "uiautomator", "dump", "/dev/tty"], capture_output=True, text=True).stdout
        i, j = out.find("<?xml"), out.rfind("</hierarchy>")
        if i >= 0 and j > i:
            return ET.fromstring(out[i:j + len("</hierarchy>")])
    return ET.fromstring("<hierarchy/>")


def bounds(n):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", n.get("bounds", ""))
    return tuple(map(int, m.groups())) if m else (0, 0, 0, 0)


def texts(n):
    return [t for t in (x.get("text") or x.get("content-desc") for x in n.iter()) if t]


def screen_size():
    out = subprocess.run([ADB, "shell", "wm", "size"], capture_output=True, text=True).stdout
    m = re.findall(r"(\d+)x(\d+)", out)
    return tuple(map(int, m[-1])) if m else (0, 0)


def focused(root):
    f = [n for n in root.iter("node") if n.get("focused") == "true"]
    return f[-1] if f else None


def main():
    cmd = sys.argv[1]
    root = dump()
    if cmd == "focus":
        f = focused(root)
        print("" if f is None else "%s|%s" % (f.get("bounds"), " / ".join(texts(f))))
    elif cmd == "has":
        wants = [w.lower() for w in sys.argv[2].split("|")]
        sys.exit(0 if any(w in t.lower() for n in root.iter("node") for t in texts(n)[:1] for w in wants) else 1)
    elif cmd == "check":
        if len(sys.argv) > 2 and sys.argv[2]:
            want = sys.argv[2].lower()
            if not any(want in t.lower() for n in root.iter("node") for t in texts(n)[:1]):
                print("%r not on screen" % sys.argv[2])
                sys.exit(2)
        W, H = screen_size()
        problems = []
        f = focused(root)
        if f is None:
            problems.append("nothing focused")
        else:
            l, t, r, b = bounds(f)
            if l < 0 or t < 0 or r > W or b > H or r <= l or b <= t:
                problems.append("focus outside the screen %s" % f.get("bounds"))
        for n in root.iter("node"):
            l, t, r, b = bounds(n)
            if n.get("text") and r > W + 1 and l < W:
                problems.append("cut off at the right edge: %r %s" % (n.get("text")[:40], n.get("bounds")))
        print("; ".join(problems))
        sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
