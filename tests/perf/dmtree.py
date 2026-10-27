#!/usr/bin/env python3
"""dmtree.py <trace> [min-ms] [thread]: call tree (wall time) of one thread in an Android .trace file.

dmtrace.py adds up time per method, which counts recursive and nested calls (View.measure) many times over; this
keeps every call path apart, so a node's time is real wall time, and prints the paths above min-ms (default 80).
Chains of single children are folded into one line ("a > b > c") so the tree stays readable.
"""
import struct, sys, collections

data = open(sys.argv[1], "rb").read()
min_ms = float(sys.argv[2]) if len(sys.argv) > 2 else 80
want = sys.argv[3] if len(sys.argv) > 3 else "main"
i = data.index(b"*end\n") + 5
threads, methods, section, clock = {}, {}, None, "dual"
for line in data[:i].decode("utf-8", "replace").split("\n"):
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
tids = {t for t, n in threads.items() if n == want} or {t for t, n in threads.items() if want in n}


class Node:
    __slots__ = ("kids", "us")

    def __init__(self):
        self.kids, self.us = {}, 0


root = Node()
last = 0
stack = []  # (method, start wall, node)
while pos + rec <= len(data):
    if ver == 1:
        tid = data[pos]; mv, = struct.unpack_from("<I", data, pos + 1); body = pos + 5
    else:
        tid, mv = struct.unpack_from("<HI", data, pos); body = pos + 6
    wall = struct.unpack_from("<II", data, body)[1] if clock == "dual" else struct.unpack_from("<I", data, body)[0]
    pos += rec
    if tid not in tids:
        continue
    last = wall
    action, mid = mv & 3, mv & ~3
    if action == 0:
        parent = stack[-1][2] if stack else root
        node = parent.kids.get(mid)
        if node is None:
            node = parent.kids[mid] = Node()
        stack.append((mid, wall, node))
    else:
        while stack:
            m, t0, node = stack.pop()
            node.us += wall - t0
            if m == mid:
                break

while stack:  # calls still running when the trace ended
    m, t0, node = stack.pop(); node.us += last - t0


def show(node, mid, depth):
    names = [methods.get(mid, hex(mid))]
    while len(node.kids) == 1:
        (k, n), = node.kids.items()
        if n.us < node.us * 0.9:
            break
        names.append(methods.get(k, hex(k))); node = n
    label = " > ".join(names) if len(names) <= 3 else " > ".join(names[:1] + ["..."] + names[-2:])
    print("%s%6.0f ms  %s" % ("  " * depth, node.us / 1000, label))
    for k, n in sorted(node.kids.items(), key=lambda kv: -kv[1].us):
        if n.us / 1000 >= min_ms:
            show(n, k, depth + 1)


print("thread:", [threads[t] for t in tids])
for k, n in sorted(root.kids.items(), key=lambda kv: -kv[1].us):
    if n.us / 1000 >= min_ms:
        show(n, k, 0)
