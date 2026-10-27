#!/usr/bin/env python3
"""dmtrace.py <trace> [thread-name-substring] [top]: inclusive wall time per method from an Android .trace file."""
import struct, sys, collections

data = open(sys.argv[1], "rb").read()
want = sys.argv[2] if len(sys.argv) > 2 else "main"
top = int(sys.argv[3]) if len(sys.argv) > 3 else 40
i = data.index(b"*end\n") + 5
header = data[:i].decode("utf-8", "replace")
threads, methods, section, clock = {}, {}, None, "dual"
for line in header.split("\n"):
    if line.startswith("*"):
        section = line[1:]; continue
    if section == "version" and line.startswith("clock="):
        clock = line.split("=")[1]
    elif section == "threads" and "\t" in line:
        t, n = line.split("\t", 1); threads[int(t)] = n
    elif section == "methods" and "\t" in line:
        p = line.split("\t"); methods[int(p[0], 16)] = p[1].rsplit(".", 1)[-1] + "." + p[2]
magic, ver, off, start = struct.unpack_from("<IHHQ", data, i)
rec = struct.unpack_from("<H", data, i + 16)[0] if ver >= 3 else (10 if ver == 1 else 14)
pos = i + off
tid_main = [t for t, n in threads.items() if want in n]
incl = collections.Counter(); stacks = collections.defaultdict(list)
while pos + rec <= len(data):
    if ver == 1:
        tid = data[pos]; mv, = struct.unpack_from("<I", data, pos + 1); body = pos + 5
    else:
        tid, mv = struct.unpack_from("<HI", data, pos); body = pos + 6
    if clock == "dual":
        _, wall = struct.unpack_from("<II", data, body)
    else:
        wall, = struct.unpack_from("<I", data, body)
    pos += rec
    if tid not in tid_main:
        continue
    action, mid = mv & 3, mv & ~3
    st = stacks[tid]
    if action == 0:
        st.append((mid, wall))
    elif st:
        # pop to the matching method (sampling traces can be unbalanced)
        while st:
            m, t0 = st.pop()
            incl[m] += wall - t0
            if m == mid:
                break
print("threads:", [threads[t] for t in tid_main], "clock:", clock)
for mid, us in incl.most_common(top):
    print("%8.0f ms  %s" % (us / 1000, methods.get(mid, hex(mid))))
