#!/usr/bin/env python3
"""Read the buddy's recorded runs (RunRecorder.java has the format).

The mod records while the file recordings/on exists in its exchange folder: segments recordings/seg-<ms>.bin, one gzip
stream each of records (kind, ms, tk, payload), and recordings/index.jsonl, a line per closed segment.

  recording.py list [DIR]
      the segments, oldest first: when, the game ticks, the dimension, the size, the copy their chain starts from
  recording.py dump [DIR] [--seg FILE] [--kinds CKDLT] [--text]
      the records: kind, time, tick, size (with --text the JSON of K, D and T records, and the head of C and L)
  recording.py frame [DIR] --tk N
      the positions frame at game tick N (the last one at or before it), as JSON, and on stderr the copy and the changes
      the world at that moment is made of
  recording.py check [DIR]
      reads everything back: each segment starts with a key frame, every delta applies, every change follows the one
      before it in its chain

DIR is a recordings folder (default: recordings/ in $BUDDY_EXCHANGE, or the current folder). A segment still open, or
cut short when the game stopped, is read as far as it goes.
"""
import argparse
import datetime as dt
import json
import os
import struct
import sys
import zlib

HEADER = struct.Struct(">cqiI")
LISTS = ("entities", "players")


def default_dir():
    ex = os.environ.get("BUDDY_EXCHANGE")
    return os.path.join(ex, "recordings") if ex else "."


def when(ms):
    return dt.datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]


def segment_files(d):
    """The segment files, oldest first (their names carry the time they began)."""
    names = [n for n in os.listdir(d) if n.startswith("seg-") and n.endswith(".bin")]
    return sorted(names, key=lambda n: int(n[4:-4]))


def index(d):
    """The index lines by file name."""
    out = {}
    try:
        with open(os.path.join(d, "index.jsonl"), encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    o = json.loads(line)
                    out[o["file"]] = o
    except FileNotFoundError:
        pass
    return out


def raw(path):
    """A segment ungzipped, as far as it goes (a segment still being written, or cut short, has no gzip end)."""
    with open(path, "rb") as f:
        data = f.read()
    d = zlib.decompressobj(16 + zlib.MAX_WBITS)
    try:
        return d.decompress(data) + d.flush()
    except zlib.error:
        return d.decompress(data[: len(data) - 1]) if len(data) > 1 else b""


def records(path):
    """(offset, kind, ms, tk, payload) of every whole record of a segment."""
    b = raw(path)
    at = 0
    while at + HEADER.size <= len(b):
        kind, ms, tk, n = HEADER.unpack_from(b, at)
        end = at + HEADER.size + n
        if end > len(b):
            break
        yield at, kind.decode("ascii"), ms, tk, b[at + HEADER.size:end]
        at = end


def world_meta(payload):
    """The JSON at the head of a C (MQ3D) or L (MQ3L) payload."""
    n = struct.unpack_from(">i", payload, 4)[0]
    return payload[:4].decode("ascii"), json.loads(payload[8:8 + n])


def apply(prev, d):
    """A frame with a delta applied (FrameDelta.java): a new dict, prev as it was."""
    out = json.loads(json.dumps(prev))
    for k, v in d.items():
        if k == "-":
            continue
        if k in LISTS and isinstance(v, dict) and isinstance(out.get(k), list):
            out[k] = apply_list(out[k], v)
        else:
            out[k] = v
    for k in d.get("-", []):
        out.pop(k, None)
    return out


def apply_list(was, d):
    gone = {str(i) for i in d.get("-", [])}
    changed = d.get("~", {})
    out = []
    for o in was:
        i = str(o["id"])
        if i in gone:
            continue
        if i in changed:
            o = dict(o)
            for k, v in changed[i].items():
                if k != "-":
                    o[k] = v
            for k in changed[i].get("-", []):
                o.pop(k, None)
        out.append(o)
    out += d.get("+", [])
    if "order" in d:
        by = {str(o["id"]): o for o in out}
        out = [by[str(i)] for i in d["order"] if str(i) in by]
    return out


def cmd_list(a):
    idx = index(a.dir)
    for name in segment_files(a.dir):
        o = idx.get(name)
        if o is None:
            print(f"{name}  open or cut short, {os.path.getsize(os.path.join(a.dir, name))} bytes")
            continue
        copy = o.get("copy") or {}
        print(f"{name}  {when(o['from_ms'])} to {when(o['to_ms'])[11:]}  tk {o['from_tk']}-{o['to_tk']}  {o.get('dim')}  "
              f"{o['bytes'] // 1024} KB  copy {copy.get('file')}@{copy.get('offset')}")


def cmd_dump(a):
    names = [a.seg] if a.seg else segment_files(a.dir)
    for name in names:
        print(f"== {name}")
        for off, kind, ms, tk, p in records(os.path.join(a.dir, name)):
            if a.kinds and kind not in a.kinds:
                continue
            line = f"{off:>10} {kind} {when(ms)} tk {tk:>8} {len(p):>9} B"
            if a.text:
                if kind in "CL":
                    magic, meta = world_meta(p)
                    line += f"  {magic} base {meta.get('base')} seq {meta.get('seq')} prev {meta.get('prev')} dim {meta.get('dim')}"
                else:
                    t = p.decode("utf-8")
                    line += "  " + (t if len(t) < 300 else t[:297] + "...")
            print(line)


def frame_at(d, tk):
    """The frame at or before tick tk, the segment it is in, and the copy and changes of its world."""
    idx = index(d)
    names = segment_files(d)
    # the segment holding tk: the last one that began at or before it
    pick = None
    for name in names:
        o = idx.get(name)
        first = o["from_tk"] if o else next((r[3] for r in records(os.path.join(d, name))), None)
        if first is not None and first <= tk:
            pick = name
    if pick is None:
        return None, None, None
    frame = None
    copy = None
    for off, kind, ms, t, p in records(os.path.join(d, pick)):
        if t > tk and kind in "KD":
            break
        if kind == "K":
            frame = json.loads(p)
        elif kind == "D" and frame is not None:
            frame = apply(frame, json.loads(p))
    # the world: the C its chain starts from and every L after it up to the frame
    o = idx.get(pick)
    start = (o or {}).get("copy") or {}
    changes = 0
    if start.get("file"):
        reading = False
        for name in names[names.index(start["file"]):names.index(pick) + 1]:
            for off, kind, ms, t, p in records(os.path.join(d, name)):
                if name == start["file"] and off == start["offset"]:
                    reading = True
                    copy = (name, off, world_meta(p)[1].get("base"))
                elif reading and kind == "C" and t <= tk:
                    copy, changes = (name, off, world_meta(p)[1].get("base")), 0
                elif reading and kind == "L" and t <= tk:
                    changes += 1
    return frame, pick, (copy, changes)


def cmd_frame(a):
    frame, seg, world = frame_at(a.dir, a.tk)
    if frame is None:
        sys.exit(f"no frame at tick {a.tk}")
    copy, changes = world
    print(f"segment {seg}, frame tk {frame.get('tk')}; world: copy {copy[0]}@{copy[1]} (base {copy[2]}) and {changes} changes"
          if copy else f"segment {seg}, frame tk {frame.get('tk')}; no copy found", file=sys.stderr)
    print(json.dumps(frame, indent=1))


def cmd_check(a):
    problems = 0
    seq = base = None
    for name in segment_files(a.dir):
        recs = list(records(os.path.join(a.dir, name)))
        kinds = "".join(r[1] for r in recs)
        if not (kinds.startswith("K") or kinds.startswith("CK")):
            print(f"{name}: begins with {kinds[:3]!r}, not a key frame")
            problems += 1
        frame = None
        for off, kind, ms, tk, p in recs:
            if kind == "C":
                magic, meta = world_meta(p)
                if magic != "MQ3D":
                    print(f"{name}@{off}: a copy that is {magic}")
                    problems += 1
                base, seq, frame = meta.get("base"), meta.get("seq"), None
            elif kind == "L":
                magic, meta = world_meta(p)
                if magic != "MQ3L" or meta.get("base") != base or (seq is not None and meta.get("prev") != seq):
                    print(f"{name}@{off}: change {meta.get('seq')} (base {meta.get('base')}, prev {meta.get('prev')}) after {seq} of base {base}")
                    problems += 1
                seq = meta.get("seq")
            elif kind == "K":
                frame = json.loads(p)
                if base is not None and "base" in frame and frame["base"] != base:
                    print(f"{name}@{off}: key frame of base {frame.get('base')} in the chain of {base}")
                    problems += 1
            elif kind == "D":
                if frame is None:
                    print(f"{name}@{off}: a delta without a key frame before it")
                    problems += 1
                else:
                    frame = apply(frame, json.loads(p))
            elif kind == "T":
                json.loads(p)
        print(f"{name}: {len(recs)} records, " + ", ".join(f"{k} {kinds.count(k)}" for k in "CLKDT" if k in kinds))
    print("ok" if problems == 0 else f"{problems} problems")
    sys.exit(1 if problems else 0)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("list", "dump", "frame", "check"):
        p = sub.add_parser(name)
        p.add_argument("dir", nargs="?", default=default_dir())
        if name == "dump":
            p.add_argument("--seg")
            p.add_argument("--kinds", default="")
            p.add_argument("--text", action="store_true")
        if name == "frame":
            p.add_argument("--tk", type=int, required=True)
    a = ap.parse_args()
    {"list": cmd_list, "dump": cmd_dump, "frame": cmd_frame, "check": cmd_check}[a.cmd](a)


if __name__ == "__main__":
    main()
