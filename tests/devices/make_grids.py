#!/usr/bin/env python3
"""
Writes tests/devices/<device-id>/README.md for every phone folder: a grid of all its screenshots
(3 per row, numbered captions), plus the last gate result if one was saved in <device-id>/result.txt (and the last partial run's,
<device-id>/result-partial.txt).
Run after every gate run (full_coverage.sh calls it at the end).

  python3 tests/devices/make_grids.py
"""
import os, re

HERE = os.path.dirname(os.path.abspath(__file__))
NAMES = {
    "kyocera-cadence-s2720": "Kyocera Cadence S2720", "kyocera-e4810": "Kyocera e4810",
    "kyocera-duraxv": "Kyocera DuraXV", "tcl-flip-2": "TCL Flip 2", "sonim-xp3": "Sonim XP3 (XP3800)",
    "kyocera-duraxe-e4830": "Kyocera DuraXe e4830", "sonim-x320": "Sonim X320 (XP3 Plus 5G)",
    "sonim-x320-225": "Sonim X320 @ low density (225 dpi)",
}


def caption(fname):
    m = re.match(r"(\d+)-(.+)\.png$", fname)
    num, name = (m.group(1), m.group(2)) if m else ("", fname)
    return "%s. %s" % (int(num), name.replace("-", " ").capitalize()) if num else name


def grid(folder, rel):
    files = sorted(f for f in os.listdir(folder) if f.endswith(".png"))
    if not files:
        return "_No screenshots yet._\n"
    rows = []
    for i in range(0, len(files), 3):
        cells = ''.join('<td align="center"><img src="%s/%s" width="160" alt="%s"><br><sub>%s</sub></td>'
                        % (rel, f, caption(f), caption(f)) for f in files[i:i + 3])
        rows.append("  <tr>%s</tr>" % cells)
    return '<table align="center">\n%s\n</table>\n' % "\n".join(rows)


def main():
    for dev in sorted(os.listdir(HERE)):
        d = os.path.join(HERE, dev)
        shots = os.path.join(d, "screenshots")
        if not os.path.isdir(shots):
            continue
        out = ["# %s" % NAMES.get(dev, dev), ""]
        res = os.path.join(d, "result.txt")
        if os.path.exists(res):
            out += ["Last gate run:", "", "```", open(res).read().rstrip(), "```", ""]
        part = os.path.join(d, "result-partial.txt")
        if os.path.exists(part):
            out += ["Last partial run (only some phases; its frames replace those phases' frames below):", "",
                    "```", open(part).read().rstrip(), "```", ""]
        for sub in sorted(os.listdir(shots)):
            if os.path.isdir(os.path.join(shots, sub)):
                out += ["## screenshots/%s" % sub, "", grid(os.path.join(shots, sub), "screenshots/" + sub)]
        open(os.path.join(d, "README.md"), "w").write("\n".join(out))
        print("wrote", os.path.relpath(os.path.join(d, "README.md"), HERE))


if __name__ == "__main__":
    main()
