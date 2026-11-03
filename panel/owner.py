"""The owner of the panel: one password that may do everything (Docker).

The panel trusts the addresses in its allow file, but that is not enough: the page is on the internet behind Caddy, and
whoever installs the image has no address list yet. So the panel has an owner,
one password, kept as a PBKDF2 hash (owner.json, readable by the buddy's user alone). A login gives a cookie of thirty
days, signed with a key of its own and over the hash, so a new password ends every session. Each login is a session of
its own, kept in owner-sessions.json beside it, and Log out takes that session away on the panel too, so a copied cookie
is worth nothing afterwards. A cookie of an older version, without a session of its own, no longer counts: log in once
more.

Before there is an owner the page asks for a setup code, which the container prints in its log at the start (docker
compose logs <buddy>): whoever reaches the page first cannot take it without that code. The code lies in setup-code
beside owner.json and goes once the owner is set. Ten wrong tries from one address in ten minutes and that address
waits. An IPv6 address counts as its /64, since one machine has a whole /64 to pick from; a try counts from the moment
it is made, so tries sent side by side cannot all slip in before the first is counted; and a hundred wrong passwords in
ten minutes from all addresses together make every password login wait, so that a crowd of addresses cannot guess on.
A password costs a PBKDF2 of 600,000 rounds: one at a time, so that a flood of logins takes one core and not the
machine the buddy's game runs on. An address of the allow file needs no password and is never held up by this.

An address of the allow file may also set the first password without the code (Settings, Access: first).
"""
import hashlib
import hmac
import ipaddress
import json
import os
import secrets
import threading
import time

ITERATIONS = 600_000
MIN_LEN, MAX_LEN = 10, 200


def write_private(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(data)
    os.replace(tmp, path)


def who(addr):
    """What a try counts against: the address, or for IPv6 its /64."""
    try:
        ip = ipaddress.ip_address(str(addr))
    except ValueError:
        return str(addr)
    if ip.version == 6 and ip.ipv4_mapped:
        return str(ip.ipv4_mapped)
    return str(ipaddress.ip_network(str(ip) + "/64", strict=False)) if ip.version == 6 else str(ip)


class Owner:
    DAYS, TRIES, WINDOW = 30, 10, 600
    # wrong passwords from all addresses together in WINDOW, after which every password login waits
    ALL_TRIES = 100
    # a login that does not get its turn at the PBKDF2 within this many seconds is told to try again
    TURN = 15

    def __init__(self, path):
        self.path = os.path.abspath(os.path.expanduser(path))
        self.code_path = os.path.join(os.path.dirname(self.path), "setup-code")
        self.key_path = os.path.join(os.path.dirname(self.path), "owner.key")
        self.sessions_path = os.path.join(os.path.dirname(self.path), "owner-sessions.json")
        self.sessions_seen = (None, {})
        self.lock = threading.Lock()
        self.hashing = threading.BoundedSemaphore(1)
        self.fails = {}
        self.all_fails = []
        self.cache = (None, None)

    # ---------- what there is ----------

    def record(self):
        """owner.json as it is, read again when it changes; None before the setup."""
        try:
            m = os.path.getmtime(self.path)
        except OSError:
            return None
        if self.cache[0] != m:
            try:
                with open(self.path, encoding="utf-8") as f:
                    r = json.load(f)
                r = r if isinstance(r, dict) and r.get("hash") and r.get("salt") else None
            except (OSError, ValueError):
                r = None
            self.cache = (m, r)
        return self.cache[1]

    def needs_setup(self):
        return self.record() is None

    def setup_code(self):
        """The code that lets the first visitor set the password: made once, kept until the owner is set."""
        if not self.needs_setup():
            return None
        try:
            code = open(self.code_path, encoding="utf-8").read().strip()
        except OSError:
            code = ""
        if len(code) < 8:
            alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            code = "-".join("".join(secrets.choice(alphabet) for _ in range(4)) for _ in range(3))
            write_private(self.code_path, code + "\n")
        return code

    def key(self):
        try:
            k = open(self.key_path, "rb").read()
        except OSError:
            k = b""
        if len(k) < 32:
            k = os.urandom(32)
            os.makedirs(os.path.dirname(self.key_path), exist_ok=True)
            fd = os.open(self.key_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            os.write(fd, k)
            os.close(fd)
        return k

    # ---------- the password ----------

    @staticmethod
    def hashed(word, salt, n=ITERATIONS):
        return hashlib.pbkdf2_hmac("sha256", word.encode("utf-8"), bytes.fromhex(salt), n).hex()

    def set(self, word):
        word = str(word or "")
        if not MIN_LEN <= len(word) <= MAX_LEN:
            return "the password needs %d to %d characters" % (MIN_LEN, MAX_LEN)
        salt = os.urandom(16).hex()
        write_private(self.path, json.dumps({"salt": salt, "iterations": ITERATIONS, "hash": self.hashed(word, salt),
                                             "set": int(time.time())}) + "\n")
        # a new password ends every session (their signature is over the old hash as well)
        self.save_sessions({})
        try:
            os.remove(self.code_path)
        except OSError:
            pass
        return None

    def setup(self, addr, code, word):
        """The first password, with the code of the container's log. None, or what is wrong."""
        with self.lock:
            if not self.needs_setup():
                return "a password is set already: log in"
            if self.waiting(addr):
                return "too many tries: wait ten minutes"
            right = self.setup_code() or ""
            given = "".join(str(code or "").upper().split()).replace("-", "")
            if not hmac.compare_digest(given.encode(), right.replace("-", "").encode()):
                self.failed(addr)
                return "that is not the setup code from the container's log"
            return self.set(word)

    def first(self, word):
        """The first password, set by an address of the allow file. None, or what is wrong."""
        with self.lock:
            if not self.needs_setup():
                return "a password is set already: change it instead"
            return self.set(word)

    def change(self, old, new):
        with self.hashing:
            with self.lock:
                if not self.matches(old):
                    return "the current password is wrong"
                return self.set(new)

    def matches(self, word):
        r = self.record()
        if not r:
            return False
        return hmac.compare_digest(self.hashed(str(word or ""), r["salt"], int(r.get("iterations", ITERATIONS))), r["hash"])

    def check(self, addr, word):
        """True for the owner's password, False for another, None while this address (or everyone) has to wait."""
        with self.lock:
            if self.waiting(addr) or self.everyone_waits():
                return None
            # counted before the check and taken back when it was right, so that tries sent side by side all count
            stamp = self.failed(addr)
        if not self.hashing.acquire(timeout=self.TURN):
            return None
        try:
            right = self.matches(word)
        finally:
            self.hashing.release()
        if right:
            with self.lock:
                self.unfail(addr, stamp)
        return right

    def waiting(self, addr):
        now, k = time.time(), who(addr)
        if len(self.fails) > 2000:
            self.fails = {a: t for a, t in self.fails.items() if t and now - t[-1] < self.WINDOW}
        tries = [t for t in self.fails.get(k, []) if now - t < self.WINDOW]
        if tries:
            self.fails[k] = tries
        else:
            self.fails.pop(k, None)
        return len(tries) >= self.TRIES

    def everyone_waits(self):
        now = time.time()
        self.all_fails = [t for t in self.all_fails if now - t < self.WINDOW]
        return len(self.all_fails) >= self.ALL_TRIES

    def failed(self, addr):
        stamp = time.time()
        self.fails.setdefault(who(addr), []).append(stamp)
        self.all_fails.append(stamp)
        return stamp

    def unfail(self, addr, stamp):
        for tries in (self.fails.get(who(addr), []), self.all_fails):
            if stamp in tries:
                tries.remove(stamp)

    # ---------- the session ----------
    # A cookie is "<until>.<session>.<signature>": the signature over both and the password's hash, the session one of
    # owner-sessions.json ({"<session>": <until>}). Log out takes it out of that file.
    MAX_SESSIONS = 50

    def read_sessions(self):
        """The sessions as they are, read again when the file changes (another panel process, a restart)."""
        try:
            m = os.path.getmtime(self.sessions_path)
        except OSError:
            return {}
        if self.sessions_seen[0] != m:
            try:
                with open(self.sessions_path, encoding="utf-8") as f:
                    d = json.load(f)
                d = {str(k): int(v) for k, v in d.items()} if isinstance(d, dict) else {}
            except (OSError, ValueError, TypeError):
                d = {}
            self.sessions_seen = (m, d)
        return self.sessions_seen[1]

    def save_sessions(self, d):
        write_private(self.sessions_path, json.dumps(d) + "\n")
        self.sessions_seen = (None, {})

    def sign(self, until, sid):
        r = self.record() or {}
        return hmac.new(self.key(), (str(until) + ":" + sid + ":" + r.get("hash", "")).encode(), hashlib.sha256).hexdigest()

    def token(self):
        """A new session: kept, and the cookie for it."""
        now = int(time.time())
        until, sid = now + self.DAYS * 86400, secrets.token_hex(16)
        with self.lock:
            d = {k: v for k, v in self.read_sessions().items() if v > now}
            # the oldest go first when there are too many (each login is one: a browser that keeps logging in)
            for k in sorted(d, key=d.get)[:max(0, len(d) - self.MAX_SESSIONS + 1)]:
                del d[k]
            d[sid] = until
            self.save_sessions(d)
        return "%d.%s.%s" % (until, sid, self.sign(until, sid))

    def parts(self, token):
        """(until, session) of a cookie with a right signature, else None."""
        bits = str(token or "").split(".")
        if len(bits) != 3 or not bits[0].isdigit() or len(bits[1]) != 32:
            return None
        until, sid, sig = int(bits[0]), bits[1], bits[2]
        return (until, sid) if hmac.compare_digest(sig, self.sign(until, sid)) else None

    def valid(self, token):
        if not token or self.needs_setup():
            return False
        p = self.parts(token)
        if not p or p[0] <= time.time():
            return False
        with self.lock:
            return self.read_sessions().get(p[1]) == p[0]

    def end(self, token):
        """Log out: this session goes from the panel, not only from the browser."""
        p = self.parts(token)
        if not p:
            return
        with self.lock:
            d = dict(self.read_sessions())
            if d.pop(p[1], None) is not None:
                self.save_sessions(d)
