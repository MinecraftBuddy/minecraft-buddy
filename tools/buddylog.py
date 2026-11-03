#!/usr/bin/env python3
"""Read the buddy's events back across restarts, and look for trouble in them.

The mod empties events.jsonl at every start and, with keepEvents on, first puts the old one gzipped in
events-archive/ (named after the moment it was last written). This reads both as one timeline.

  buddylog.py show [--day D | --from T --to T] [--server S] [--type a,b] [--grep RE] [--all]
      the events of a stretch of time, oldest first; status, location, chat and other chatter only with --all
  buddylog.py scan [--day D | --from T --to T] [--server S] [--out DIR]
      a report of what went wrong: deaths, damage, getting stuck and giving up, the same failure again and again,
      doors left open, refused beds, errors, standing still while busy
  buddylog.py where --at T [--server S]
      where the buddy was and what they did at that moment

D is a date (2026-10-23), "today" or "yesterday"; T is "2026-10-23 14:05", "14:05" (today) or a date. Times are
the machine's local time. --server takes the start of the address ("play" for play.example.net, "127" for the test
server). --dir is the exchange folder (default $BUDDY_EXCHANGE, or ~/Development/minecraft-afk).
"""
import argparse
import datetime as dt
import gzip
import json
import os
import re
import sys
from collections import Counter, defaultdict

DIR = os.environ.get("BUDDY_EXCHANGE") or os.path.expanduser("~/Development/minecraft-afk")
CHATTER = {"status", "location", "chat", "inventory", "players_near", "players", "farmstatus", "player_join",
           "player_leave", "scoreboard", "sent", "jump", "crouch", "menu", "profile", "wheat"}
# what "busy" means for standing still: he should be moving while doing these
BUSY = {"chest", "chore", "work", "goto", "open door", "close door", "carrier", "get loose", "farm mobs"}


def parse_time(s, end=False):
    now = dt.datetime.now()
    if s in ("today", "yesterday"):
        d = now.date() - dt.timedelta(days=1 if s == "yesterday" else 0)
        return dt.datetime.combine(d, dt.time.max if end else dt.time.min)
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            t = dt.datetime.strptime(s, fmt)
            if fmt == "%Y-%m-%d" and end:
                t = dt.datetime.combine(t.date(), dt.time.max)
            return t
        except ValueError:
            pass
    for fmt in ("%H:%M:%S", "%H:%M"):
        try:
            return dt.datetime.combine(now.date(), dt.datetime.strptime(s, fmt).time())
        except ValueError:
            pass
    sys.exit(f"cannot read the time {s!r}")


def window(a):
    if a.day:
        return parse_time(a.day), parse_time(a.day, end=True)
    if a.at:
        t = parse_time(a.at)
        return t - dt.timedelta(minutes=10), t + dt.timedelta(seconds=30)
    lo = parse_time(a.frm) if a.frm else parse_time("today")
    hi = parse_time(a.to, end=True) if a.to else dt.datetime.now()
    return lo, hi


def files(folder, lo):
    """The archive's files that can hold events after lo (a file is named after its last write), then the live file."""
    # events-archief until buddy 0.4.151, when the exchange folder's names became English
    arch = os.path.join(folder, "events-archive")
    if not os.path.isdir(arch):
        arch = os.path.join(folder, "events-archief")
    out = []
    if os.path.isdir(arch):
        for n in sorted(os.listdir(arch)):
            m = re.match(r"events-(\d{8}-\d{6})\.jsonl\.gz$", n)
            if m and dt.datetime.strptime(m.group(1), "%Y%m%d-%H%M%S") >= lo:
                out.append(os.path.join(arch, n))
    live = os.path.join(folder, "events.jsonl")
    if os.path.exists(live):
        out.append(live)
    return out


def events(folder, lo, hi, server=None):
    """(datetime, event, server, session) in time order; the server is the last one the session named."""
    lo_ms, hi_ms = lo.timestamp() * 1000, hi.timestamp() * 1000
    for path in files(folder, lo):
        opener = gzip.open if path.endswith(".gz") else open
        cur = None
        try:
            with opener(path, "rt", encoding="utf-8", errors="replace") as f:
                for line in f:
                    try:
                        e = json.loads(line)
                    except ValueError:
                        continue
                    s = e.get("server")
                    if isinstance(s, str) and s:
                        cur = s
                    t = e.get("t", 0)
                    if t > hi_ms:
                        return
                    if t < lo_ms:
                        continue
                    if server and not (cur or "").startswith(server):
                        continue
                    yield dt.datetime.fromtimestamp(t / 1000), e, cur, os.path.basename(path)
        except (OSError, EOFError):
            continue


def brief(e, skip=("t", "type", "surroundings")):
    d = {k: v for k, v in e.items() if k not in skip}
    s = json.dumps(d, ensure_ascii=False)
    return s if len(s) <= 200 else s[:197] + "..."


def at(e):
    if all(k in e for k in ("x", "y", "z")):
        try:
            return "%d %d %d" % (round(float(e["x"])), round(float(e["y"])), round(float(e["z"])))
        except (TypeError, ValueError):
            pass
    return ""


def cmd_show(a):
    lo, hi = window(a)
    types = set(a.type.split(",")) if a.type else None
    rx = re.compile(a.grep) if a.grep else None
    for t, e, srv, _ in events(a.dir, lo, hi, a.server):
        typ = e.get("type")
        if types and typ not in types:
            continue
        if not types and not a.all and typ in CHATTER:
            continue
        line = brief(e)
        if rx and not rx.search(typ + " " + line):
            continue
        print(t.strftime("%m-%d %H:%M:%S"), typ, line)


def cmd_where(a):
    t0 = parse_time(a.at)
    lo, hi = window(a)
    last = {}
    for t, e, srv, _ in events(a.dir, lo, hi, a.server):
        typ = e.get("type")
        if typ in ("location", "status", "doing", "inventory"):
            last[typ] = (t, e)
        if typ not in CHATTER and t >= t0 - dt.timedelta(minutes=2):
            print(t.strftime("%H:%M:%S"), typ, brief(e))
    print("-- at", t0.strftime("%H:%M:%S"))
    for k in ("location", "status", "doing", "inventory"):
        if k in last:
            print(" ", k, last[k][0].strftime("%H:%M:%S"), brief(last[k][1]))


TROUBLE = [  # (type, action or None, label) that count as something going wrong
    ("follow", "stuck", "stuck"), ("helper", "chest stuck", "chest stuck"), ("helper", "chest stopped", "chest stopped"),
    ("helper", "gives up", "gives up"), ("helper", "cannot get there", "cannot get there"),
    ("follow", "no path", "no path"), ("follow", "lost", "lost the player"), ("follow", "getting loose", "getting loose"),
    ("follow", "jump failed", "jump failed"), ("follow", "door left open", "door left open"),
    ("follow", "door not opened", "door not opened"), ("follow", "door stays shut", "door stays shut"),
    ("follow", "bed refuses", "bed refuses"), ("helper", "chest refuses", "chest refuses"),
    ("helper", "chest gone", "chest gone"), ("helper", "tree stopped", "tree stopped"),
    ("guard", "done", "fight ended"), ("pen", "gives up", "pen gives up"), ("pen", "spot not reached", "pen spot"),
]
OK_REASONS = {"done", "dead", "asked", "rest", "was off already", "the player sleeps", "panel", "teleported"}


def cmd_scan(a):
    lo, hi = window(a)
    deaths, errors, hits, dmg_by = [], [], [], Counter()
    lost_hp = 0.0
    trouble = Counter()
    first, last_seen = {}, {}
    places = defaultdict(list)          # (label, reason, place) -> times, for loops
    doors = {}                          # place -> opened at
    doors_long = []
    starts, sessions, conns = [], set(), Counter()
    chores_bad = Counter()
    doing, still_from, still_pos, still_reported = None, None, None, False
    stills = []
    n = 0
    for t, e, srv, sess in events(a.dir, lo, hi, a.server):
        n += 1
        sessions.add(sess)
        typ, act = e.get("type"), str(e.get("action", ""))
        reason = str(e.get("reason", ""))
        if typ == "mod_started":
            starts.append((t, e.get("version")))
        elif typ == "connection":
            conns[act] += 1
        elif typ == "error":
            errors.append((t, brief(e)))
        elif typ == "damage":
            try:
                d = float(e.get("from", 0)) - float(e.get("to", 0))
            except (TypeError, ValueError):
                d = 0
            lost_hp += max(d, 0)
            dmg_by[str(e.get("by") or e.get("kind"))] += 1
            if d >= 4 or float(e.get("to", 1) or 0) <= 0:
                hits.append((t, d, brief(e, ("t", "type", "surroundings", "air", "under_water", "burning", "lava"))))
        if (typ == "alt" and act == "dead") or "death" in typ:
            deaths.append((t, brief(e)))
        if typ == "chore" and act == "done" and reason not in OK_REASONS:
            chores_bad[(str(e.get("what")), reason)] += 1
        for tt, aa, label in TROUBLE:
            if typ == tt and act == aa and reason not in OK_REASONS:
                key = (label, reason)
                trouble[key] += 1
                first.setdefault(key, t)
                last_seen[key] = t
                places[(label, reason, at(e))].append(t)
        if typ == "follow" and act == "door opened":
            doors[at(e)] = t
        elif typ == "follow" and act in ("door shut", "door left open"):
            o = doors.pop(at(e), None)
            if o and (t - o).total_seconds() > 10:
                doors_long.append((o, (t - o).total_seconds(), at(e), act))
        if typ == "doing":
            doing = act
            still_from, still_pos, still_reported = None, None, False
        if typ == "location" and doing in BUSY:
            p = (e.get("x"), e.get("y"), e.get("z"), e.get("dim"))
            if p != still_pos:
                still_pos, still_from, still_reported = p, t, False
            elif not still_reported and (t - still_from).total_seconds() >= 180:
                stills.append((still_from, doing, at(e)))
                still_reported = True
    for place, o in doors.items():
        if (hi - o).total_seconds() > 10 and (dt.datetime.now() - o).total_seconds() > 10:
            doors_long.append((o, None, place, "never shut in this stretch"))

    out = []
    w = out.append
    w(f"Buddy report {lo:%Y-%m-%d %H:%M} to {min(hi, dt.datetime.now()):%Y-%m-%d %H:%M}"
      f"{', server ' + a.server if a.server else ''}: {n} events in {len(sessions)} file(s)")
    if not n:
        w("Nothing logged in this stretch.")
    else:
        flags = []
        if deaths:
            flags.append(f"{len(deaths)} death(s)")
        if errors:
            flags.append(f"{len(errors)} error(s)")
        loops = {k: v for k, v in places.items() if len(v) >= 5 and k[2]}
        if loops:
            flags.append(f"{len(loops)} loop(s)")
        if doors_long:
            flags.append(f"{len(doors_long)} door(s) open over 10 s")
        if stills:
            flags.append(f"{len(stills)} stand-still(s) while busy")
        w("In short: " + (", ".join(flags) if flags else "nothing alarming") + f"; {lost_hp:.0f} hp lost to damage.")
        w("")
        if starts:
            w("Starts: " + ", ".join(f"{t:%H:%M} {v}" for t, v in starts))
        if conns:
            w("Connection: " + ", ".join(f"{k} {v}x" for k, v in conns.most_common()))
        if deaths:
            w("\nDeaths:")
            for t, s in deaths:
                w(f"  {t:%m-%d %H:%M:%S} {s}")
        if errors:
            w("\nErrors:")
            for t, s in errors[:20]:
                w(f"  {t:%m-%d %H:%M:%S} {s}")
        if dmg_by:
            w("\nDamage taken by: " + ", ".join(f"{k} {v}x" for k, v in dmg_by.most_common(8)))
            for t, d, s in hits[:15]:
                w(f"  {t:%m-%d %H:%M:%S} -{d:.1f} {s}")
        if loops:
            w("\nThe same failure at the same place, five times or more (a loop):")
            for (label, reason, place), ts in sorted(loops.items(), key=lambda kv: -len(kv[1]))[:15]:
                w(f"  {len(ts)}x {label}{' (' + reason + ')' if reason else ''} at {place}, {ts[0]:%H:%M} to {ts[-1]:%H:%M}")
        if doors_long:
            w("\nDoors and gates open longer than 10 s:")
            for o, secs, place, how in doors_long[:15]:
                w(f"  {o:%m-%d %H:%M:%S} at {place}: " + (f"{secs:.0f} s, then {how}" if secs else how))
        if stills:
            w("\nStood still three minutes or more while busy:")
            for t, d, place in stills[:15]:
                w(f"  {t:%m-%d %H:%M:%S} {d} at {place}")
        if trouble:
            w("\nStuck, gave up, could not (all, by count):")
            for (label, reason), c in trouble.most_common(25):
                w(f"  {c:5d}x {label}{' (' + reason + ')' if reason else ''}, {first[(label, reason)]:%H:%M} to "
                  f"{last_seen[(label, reason)]:%H:%M}")
        if chores_bad:
            w("\nChores that ended without being done:")
            for (what, reason), c in chores_bad.most_common(10):
                w(f"  {c:5d}x {what}: {reason}")
    text = "\n".join(out) + "\n"
    if a.out:
        os.makedirs(a.out, exist_ok=True)
        path = os.path.join(a.out, f"{lo:%Y-%m-%d}.txt")
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
    sys.stdout.write(text)


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    for name in ("show", "scan", "where"):
        s = sub.add_parser(name)
        s.add_argument("--dir", default=DIR)
        s.add_argument("--server")
        s.add_argument("--day")
        s.add_argument("--from", dest="frm")
        s.add_argument("--to")
        s.add_argument("--at")
        if name == "show":
            s.add_argument("--type")
            s.add_argument("--grep")
            s.add_argument("--all", action="store_true")
        if name == "scan":
            s.add_argument("--out")
    a = p.parse_args()
    if a.cmd == "where" and not a.at:
        sys.exit("where needs --at")
    {"show": cmd_show, "scan": cmd_scan, "where": cmd_where}[a.cmd](a)


if __name__ == "__main__":
    main()
