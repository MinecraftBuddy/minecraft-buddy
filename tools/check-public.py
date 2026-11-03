#!/usr/bin/env python3
"""Look for what should not be in a public repository: secrets, mail addresses, public IP addresses, home folders.

  check-public.py                 every file git tracks, as it is in the working tree
  check-public.py --staged        what is staged for the next commit (the pre-commit hook)
  check-public.py --message FILE  a commit message (the commit-msg hook)
  check-public.py --rev REV       the files of a commit, as git has them

Besides the checks below, every line of a denylist counts as a regular expression (case-insensitive) that may not occur:
`.private/denylist.txt` when it exists, or the file BUDDY_DENYLIST names. Keep names of people, accounts, machines and
domains there, never in this file: the denylist itself is private. `.private/` is in .gitignore.

A finding that is fine (an example address, a version that looks like an IP) goes into ALLOWED below, as the exact
text. Exit code 0: nothing found; 1: findings, one per line; 2: wrong use.
"""
import ipaddress
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# exact texts that look like a finding and are not
ALLOWED = {
    "noreply@anthropic.com",
}
ALLOWED_MAIL_DOMAINS = ("example.com", "example.net", "example.org", "users.noreply.github.com")
# IP ranges that say nothing about anyone: loopback, private, link-local, documentation, broadcast, two public resolvers
ALLOWED_NETS = [ipaddress.ip_network(n) for n in (
    "0.0.0.0/32", "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16",
    "192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24", "255.255.255.255/32", "1.1.1.1/32", "8.8.8.8/32")]
# home folders that are no one's: the image's user lives in /data, a CI runner in /home/runner
ALLOWED_HOMES = ("runner", "user", "you", "buddy", "minecraft-buddy")

SECRETS = [
    ("private key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("GitHub token", re.compile(r"\b(gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})")),
    ("Telegram bot token", re.compile(r"\b\d{8,10}:[A-Za-z0-9_-]{35}\b")),
    ("Discord token", re.compile(r"\b[MN][A-Za-z\d]{23,27}\.[\w-]{6}\.[\w-]{27,}")),
    ("AWS key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("Slack token", re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}")),
]
MAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")
IPV4 = re.compile(r"(?<![\w.])(\d{1,3}(?:\.\d{1,3}){3})(?![\w.])")
HOME = re.compile(r"(?<![\w.~-])(/Users/|/home/|C:\\Users\\)([A-Za-z0-9_-][A-Za-z0-9._-]*)")


def denylist():
    path = os.environ.get("BUDDY_DENYLIST") or os.path.join(ROOT, ".private", "denylist.txt")
    out = []
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#"):
                    out.append(re.compile(line, re.IGNORECASE))
    except OSError:
        pass
    return out


def findings(text, deny):
    for n, line in enumerate(text.splitlines(), 1):
        for kind, rx in SECRETS:
            for m in rx.finditer(line):
                yield n, kind, m.group(0)[:12] + "..."
        for m in MAIL.finditer(line):
            v = m.group(0)
            if v not in ALLOWED and not v.lower().endswith(ALLOWED_MAIL_DOMAINS):
                yield n, "mail address", v
        for m in IPV4.finditer(line):
            v = m.group(1)
            try:
                ip = ipaddress.ip_address(v)
            except ValueError:
                continue
            if v not in ALLOWED and not any(ip in net for net in ALLOWED_NETS):
                yield n, "IP address", v
        for m in HOME.finditer(line):
            if m.group(2) not in ALLOWED_HOMES and m.group(0) not in ALLOWED:
                yield n, "home folder", m.group(0)
        for rx in deny:
            for m in rx.finditer(line):
                yield n, "denylist", m.group(0)


def git(*args):
    return subprocess.run(["git", "-C", ROOT, *args], capture_output=True, check=True).stdout


def binary(data):
    return b"\0" in data[:8192]


def files_worktree():
    try:
        names = git("ls-files", "-z").decode().split("\0")
    except (OSError, subprocess.CalledProcessError):
        names = []
        for d, dirs, fs in os.walk(ROOT):
            dirs[:] = [x for x in dirs if x not in (".git", ".private", "build", ".gradle", "__pycache__")]
            names += [os.path.relpath(os.path.join(d, f), ROOT) for f in fs]
    for name in filter(None, names):
        try:
            with open(os.path.join(ROOT, name), "rb") as f:
                yield name, f.read()
        except OSError:
            continue


def files_staged():
    names = git("diff", "--cached", "--name-only", "-z", "--diff-filter=ACMR").decode().split("\0")
    for name in filter(None, names):
        yield name, git("show", ":" + name)


def files_rev(rev):
    names = git("ls-tree", "-r", "-z", "--name-only", rev).decode().split("\0")
    for name in filter(None, names):
        yield name, git("show", "%s:%s" % (rev, name))


def main(argv):
    deny = denylist()
    if argv[:1] == ["--message"] and len(argv) == 2:
        with open(argv[1], encoding="utf-8", errors="replace") as f:
            source = [("commit message", f.read().encode())]
    elif argv == ["--staged"]:
        source = files_staged()
    elif argv[:1] == ["--rev"] and len(argv) == 2:
        source = files_rev(argv[1])
    elif not argv:
        source = files_worktree()
    else:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    found = 0
    for name, data in source:
        if binary(data):
            continue
        for line, kind, what in findings(data.decode("utf-8", "replace"), deny):
            print("%s:%d: %s: %s" % (name, line, kind, what))
            found += 1
    if found:
        print("\n%d finding(s). Take them out, or add an exact text to ALLOWED in tools/check-public.py when it is"
              " fine to publish." % found, file=sys.stderr)
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
