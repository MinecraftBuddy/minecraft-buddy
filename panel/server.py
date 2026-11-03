#!/usr/bin/env python3
"""The buddy's web panel: read the chat and type in it, see and rearrange the inventory, see a live map around the buddy
and pick a spot on it to go to, steer with the keys and the mouse, put a schematic up to be built, take the buddy off the
server and back, and look after the server profiles, who may open this page, and the Telegram bridge's settings
(bridgecfg.py).

On the page one reads the buddy's chat and types in it or gives / commands as the buddy, drops things from the
inventory, and sees the enchantments on the items and the armour and the effects the buddy has, behind IP allowlisting;
items are moved and dropped as close to the game's own way as can be, the profiles and the allow list are managed there,
the buddy leaves the server and joins again, and a minimap shows where the buddy is and takes a spot to go to. There is
no view password that lets others look: the page is for who may steer the buddy alone.

It talks to the mod only through the exchange folder: it reads events.jsonl, state.json, map.json and buddy.json and
appends lines to commands.jsonl (steer.json it writes whole). Python's standard library only. It runs in the buddy's
container (setup/supervise.py starts it) on a port of 127.0.0.1, without TLS: the machine's Caddy (docker/front) gives it
HTTPS. The addresses in the allow file (one
address or network a line, # for a note, read again when it changes) may do anything: every POST, the settings page,
the profiles, the allow list and the schematics. Anyone else gets the login page and nothing else. A POST needs the
header X-Buddy, so another site cannot post a form here.

The visitor's address is the one Caddy puts in X-Forwarded-For, believed only from where Caddy's connections come from
(--proxy-host: the container network's gateway, or localhost on the machine's network), and, when the machine's
compose.yaml has one, only beside the secret Caddy adds to every request (X-Buddy-Proxy; the panel keeps its SHA-256,
--proxy-secret): every other program on the machine reaches the panel from that same gateway or localhost, and could
otherwise name any allowed address in X-Forwarded-For. Every panel has a password
(owner.py) that may do what the allow file's addresses may, from any address: it is set at the first visit with a
setup code from the container's log, and gives access without an allowed address. The login page takes that password.
Until 1 Nov 2026 the panel could also run on a machine of its own with HTTPS of its own, where the password was
optional; that went with the old way of installing.
"""
import argparse
import hashlib
import hmac
import http.cookies
import ipaddress
import json
import os
import re
import threading
import time
import urllib.parse
import urllib.request
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# the panel's own modules lie beside it, also when Python is started without the script's folder on its path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bridgecfg  # noqa: E402
import game  # noqa: E402
import owner  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
# what the chat log shows: what was said and done, and what the mod reports of its jobs (the mod writes English names)
LOG_TYPES = {"chat", "chat_sent", "follow", "corpse guard", "carrier", "tap", "base", "torch", "furnaces", "helper", "drop",
             "forget", "profile", "error", "connection", "player_join", "player_leave", "death_cause", "teleport",
             "steer", "placement", "build", "buildstatus", "switch", "doing", "chore", "goto", "guard",
             "mode", "setting", "pen"}
QUIET_ACTIONS = {"sees", "clickable", "status", "trace"}
# a real player's name: the figures of a plugin (FancyNpcs) join and leave the player list under names of colour codes
PLAYER = re.compile(r"^[A-Za-z0-9_.]{2,17}$")
ASSET = re.compile(r"^[a-z0-9_]{1,64}$")
SCHEMATIC = re.compile(r"^[A-Za-z0-9 _().-]{1,80}\.litematic$")
MAX_SCHEMATIC = 8 * 1024 * 1024
STEER_KEYS = {"w", "a", "s", "d", "jump", "sneak", "sprint", "left", "use"}
# One page plays the buddy at a time: another page that tries is told that someone else (at an address) is controlling
# the buddy at the moment. The page that steers keeps control while it keeps sending (four times a second); another page
# is refused until it lets go or has sent nothing for LEASE_S seconds. What playing sends besides the steering is refused
# to the others too: clicks in the inventory and the screens, the hotbar slot, a picked block. Chat, the modes and the
# settings are not.
LEASE_S = 2.0
GAME_ACTIONS = {"invclick", "menuclose", "select", "pickblock", "menubutton", "trade", "rename", "beacon", "slotstate",
                "respawn", "wake", "sign", "book", "screenclose"}
PAGE = re.compile(r"^[A-Za-z0-9]{6,40}$")
INV_MODES = {"pickup", "quick", "swap", "throw", "pickup_all", "drag"}
NAME = re.compile(r"^[A-Za-z0-9_]{3,16}$")
DIM = re.compile(r"^[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}$")
# the switches of the mode card and the settings page (Modes in the mod)
SETTINGS = {"baseHarvest", "baseFurnaces", "baseStore", "baseFight", "baseSleep", "baseSleepOthers", "baseShear", "baseHerd", "basePenGates", "baseRenew", "assistDig", "assistChop",
            "assistFarm", "followGuard", "followGuardPlayers", "followCarry", "followMobFarm", "pathDig", "pathPlace", "stayOffline",
            "afk", "lookAround", "wander", "afkCrouch", "crouchAlong", "menuLog"}
# the amounts of the mode card (Modes in the mod): ingots of iron and gold left over when something is made, and how far
# (blocks) and how often (minutes) to walk about in AFK mode; the lowest and highest each may be
AMOUNTS = {"keepIron": (0, 10000), "keepGold": (0, 10000), "wanderMaxBlocks": (1, 8), "wanderEveryMin": (1, 60)}
# the logs Settings, Behavior lists: a name, where it lies (exchange folder, the panel's own, the game's state folder), what
# it is, and whether the page may empty it (what the mod or the game itself writes and reads back is only downloaded);
# "the buddy" in what it is becomes the buddy's name on the page (logs())
LOGS = [("events.jsonl", "exchange", "What the buddy does and sees since the game last started (the Activity and Chat tabs)", False),
        ("history.jsonl", "panel", "The chat and activity, kept across restarts of the game; past 30 MB the oldest goes", True),
        ("recording.jsonl", "exchange", "Everything the server sends the buddy: chat, titles, the line above the hotbar, clicks (with Record menus and screens)", True),
        ("menus.jsonl", "exchange", "Every menu a server opened for the buddy, with its items (with Record menus and screens)", True),
        ("parkour-runs.jsonl", "exchange", "Every Parkour game the buddy was in, step by step", True),
        ("yield.jsonl", "exchange", "What the buddy's farms brought in", True),
        ("game.log", "state", "The game's own output (Minecraft, Fabric and the mod)", False)]


class History(threading.Thread):
    """The chat and reports kept beyond a restart of the game, which empties events.jsonl, so that the
    chat scrolls back further. Follows events.jsonl once a second and appends what the log shows to
    history.jsonl beside the panel; past 30 MB it keeps the newest 20 MB. Where it was in events.jsonl (its inode and
    offset) is kept in history.pos, so a restart of the panel neither loses nor repeats lines."""

    MAX, KEEP = 30_000_000, 20_000_000

    def __init__(self, exchange, path):
        super().__init__(daemon=True)
        self.events, self.path, self.pos = os.path.join(exchange, "events.jsonl"), path, path + ".pos"
        self.lock = threading.Lock()

    def run(self):
        try:
            ino, off = json.load(open(self.pos))
        except (OSError, ValueError):
            ino, off = None, 0
        while True:
            try:
                st = os.stat(self.events)
                if st.st_ino != ino or st.st_size < off:
                    ino, off = st.st_ino, 0
                if st.st_size > off:
                    with open(self.events, "rb") as fh:
                        fh.seek(off)
                        raw = fh.read(st.st_size - off)
                    end = raw.rfind(b"\n")
                    if end >= 0:
                        off += end + 1
                        keep = [l for l in raw[:end + 1].decode("utf-8", "replace").splitlines() if shown(l)]
                        if keep:
                            with self.lock:
                                with open(self.path, "a", encoding="utf-8") as out:
                                    out.write("\n".join(keep) + "\n")
                                self.trim()
                        with open(self.pos, "w") as fh:
                            json.dump([ino, off], fh)
            except OSError:
                pass
            time.sleep(1)

    def trim(self):
        try:
            if os.path.getsize(self.path) <= self.MAX:
                return
            with open(self.path, "rb") as fh:
                fh.seek(-self.KEEP, 2)
                fh.readline()
                rest = fh.read()
            with open(self.path + ".tmp", "wb") as fh:
                fh.write(rest)
            os.replace(self.path + ".tmp", self.path)
        except OSError:
            pass


def parsed(raw):
    """The lines of a piece of the history; a piece of a line (an offset from before a rewrite) is skipped."""
    out = []
    for l in raw.decode("utf-8", "replace").splitlines():
        if l.strip():
            try:
                out.append(json.loads(l))
            except ValueError:
                pass
    return out


def shown(line):
    """A line of events.jsonl the log shows."""
    try:
        e = json.loads(line)
    except ValueError:
        return False
    if e.get("type") not in LOG_TYPES or e.get("action") in QUIET_ACTIONS:
        return False
    return not (e["type"] in ("player_join", "player_leave") and not PLAYER.match(str(e.get("name", ""))))


class Allow:
    """The allow file, read again when it changes."""

    def __init__(self, path):
        self.path, self.mtime, self.nets = path, None, []
        self.lock = threading.Lock()

    def entries(self):
        out = []
        try:
            for line in open(self.path, encoding="utf-8"):
                net, _, note = line.partition("#")
                net = net.strip()
                if net:
                    out.append({"net": net, "note": note.strip()})
        except OSError:
            pass
        return out

    def ok(self, addr):
        with self.lock:
            try:
                m = os.path.getmtime(self.path)
            except OSError:
                return False
            if m != self.mtime:
                nets = []
                for e in self.entries():
                    try:
                        nets.append(ipaddress.ip_network(e["net"], strict=False))
                    except ValueError:
                        pass
                self.nets, self.mtime = nets, m
            return covered(addr, self.nets)


def covered(addr, nets):
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return any(ip.version == n.version and ip in n for n in nets)


def read_modules():
    """What each server module is and how its settings go (modules/<id>.json beside the panel, written with the module):
    the profile editor shows it under How it works, so that it is clear how a module is used."""
    out = []
    folder = os.path.join(HERE, "modules")
    for n in sorted(os.listdir(folder)) if os.path.isdir(folder) else []:
        if n.endswith(".json"):
            try:
                with open(os.path.join(folder, n), encoding="utf-8") as f:
                    d = json.load(f)
                if isinstance(d, dict) and d.get("id"):
                    out.append(d)
            except (OSError, ValueError):
                pass
    return out


def gateway():
    """The default route's gateway, from /proc/net/route (Linux): the address a published port's connections come from."""
    try:
        for line in open("/proc/net/route", encoding="ascii").read().splitlines()[1:]:
            f = line.split()
            if len(f) > 2 and f[1] == "00000000":
                return {str(ipaddress.IPv4Address(int(f[2], 16).to_bytes(4, "little")))}
    except (OSError, ValueError):
        pass
    return set()


def bare(addr):
    try:
        ip = ipaddress.ip_address(addr)
        return str(ip.ipv4_mapped) if ip.version == 6 and ip.ipv4_mapped else str(ip)
    except ValueError:
        return addr


class Panel(BaseHTTPRequestHandler):
    server_version = "BuddyPanel/2"
    # one connection for the page's many small requests (steering sends a few a second)
    protocol_version = "HTTP/1.1"
    exchange = allow = schematics = assets = icons = history = bridge = owner = None
    # Caddy in front: its addresses, looked up by name now and then, are the only ones whose X-Forwarded-For counts
    proxy_host, proxy_ips, proxy_looked = None, set(), [0.0]
    # the file with the SHA-256 of the secret Caddy sends in X-Buddy-Proxy (docker/compose.yaml); None: no secret asked
    proxy_secret = None
    last_ask = [0.0]
    lock = threading.Lock()
    # who plays the buddy now: the page (its own id, X-Buddy-Page), its address, when it last sent, what it has open over
    # the view ("inv": the inventory, for those who watch), and since when it has control
    lease = {"page": None, "ip": None, "t": 0.0, "ui": "", "since": 0.0}
    lease_lock = threading.Lock()
    last_watch = [0.0]

    def log_message(self, fmt, *args):
        pass

    def handle(self):
        try:
            super().handle()
        except OSError:
            pass

    def deny(self):
        self.send_response(403)
        self.send_header("Content-Length", "0")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

    def answer(self, code, body, kind="application/json; charset=utf-8", cache=False, cookies=()):
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        # a cookie is (name, value, seconds): seconds 0 takes it away; behind Caddy the browser sees https, so Secure
        for name, value, age in cookies:
            self.send_header("Set-Cookie", name + "=" + value + "; Max-Age=" + str(age) + "; Path=/; HttpOnly; SameSite=Strict" +
                             ("; Secure" if self.proxy_host else ""))
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "max-age=86400" if cache else "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        # the 3D view reads the game's block models and textures straight from Mojang's client jar, in the browser
        self.send_header("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                         "connect-src 'self' https://piston-meta.mojang.com https://piston-data.mojang.com; frame-ancestors 'none'; "
                         "base-uri 'none'; form-action 'self'")
        # no other site may show the panel in a frame: an allowed address needs no cookie, so a click there would count
        self.send_header("X-Frame-Options", "DENY")
        self.end_headers()
        self.wfile.write(data)

    def send_cmd(self, obj):
        line = json.dumps(obj, ensure_ascii=False) + "\n"
        with self.lock:
            with open(os.path.join(self.exchange, "commands.jsonl"), "a", encoding="utf-8") as f:
                f.write(line)

    def keep_state(self):
        """Keep the mod writing state.json and map.json while the page is open."""
        now = time.time()
        if now - self.last_ask[0] > 10:
            self.last_ask[0] = now
            self.send_cmd({"cmd": "state"})

    def file(self, name):
        try:
            return open(os.path.join(self.exchange, name), "rb").read()
        except OSError:
            return None

    def cookie(self, name):
        try:
            c = http.cookies.SimpleCookie(self.headers.get("Cookie", ""))
        except http.cookies.CookieError:
            return None
        return c[name].value if name in c else None

    def is_owner(self):
        return bool(self.owner) and self.owner.valid(self.cookie("buddy_owner"))

    def peer(self):
        """The visitor's address: the connection's own, or behind Caddy the last address of its X-Forwarded-For."""
        a = bare(self.client_address[0])
        if not self.proxy_host:
            return a
        now = time.time()
        if now - self.proxy_looked[0] > 30:
            self.proxy_looked[0] = now
            if self.proxy_host == "gateway":
                # the machine's shared Caddy (docker/front) reaches a container through its published port, so the
                # connection comes from the container network's gateway: its default route
                Panel.proxy_ips = gateway()
            else:
                try:
                    import socket
                    Panel.proxy_ips = {bare(i[4][0]) for i in socket.getaddrinfo(self.proxy_host, None)}
                except OSError:
                    pass
        if a in self.proxy_ips and self.from_proxy():
            fwd = [x.strip() for x in self.headers.get("X-Forwarded-For", "").split(",") if x.strip()]
            if fwd:
                return bare(fwd[-1])
        return a

    _secret = [None, None]

    def from_proxy(self):
        """Whether the request carries Caddy's secret, where the machine has one. A file that is named but cannot be
        read counts as a secret nobody has: no X-Forwarded-For is believed until it is there."""
        if not self.proxy_secret:
            return True
        try:
            m = os.path.getmtime(self.proxy_secret)
            if self._secret[0] != m:
                want = open(self.proxy_secret, encoding="ascii").read().strip().lower()
                Panel._secret = [m, want if re.fullmatch(r"[0-9a-f]{64}", want) else ""]
        except (OSError, ValueError):
            Panel._secret = [None, ""]
        want, got = self._secret[1], self.headers.get("X-Buddy-Proxy", "")
        return bool(want) and bool(got) and hmac.compare_digest(hashlib.sha256(got.encode("utf-8", "replace")).hexdigest(), want)

    def page(self, given=None):
        """The id the page made for itself when it loaded (header X-Buddy-Page, or the query's page=)."""
        p = given if given is not None else self.headers.get("X-Buddy-Page", "")
        return p if PAGE.match(p or "") else ""

    def holder(self):
        """The page that plays the buddy now, or None when nobody has steered for LEASE_S seconds."""
        with self.lease_lock:
            lease = dict(Panel.lease)
        return lease if lease["page"] and time.time() - lease["t"] < LEASE_S else None

    def name_now(self):
        """The buddy's name as the game last wrote it, for what the page says."""
        try:
            return json.loads(self.file("state.json") or b"{}").get("name") or "the buddy"
        except (ValueError, AttributeError):
            return "the buddy"

    def busy(self, h):
        """The refusal for a page while another one plays."""
        ip = h["ip"] or "an unknown address"
        who = ("Another window (at " + ip + ", your own address)") if ip == self.peer() else ("Someone else (at " + ip + ")")
        return self.answer(409, {"error": who + " is controlling " + self.name_now() + ".", "busy": True, "ip": h["ip"]})

    def keep_watch(self):
        """A Take control window is open: the mod writes everything that moves as often as while the buddy is played
        (World3d.watched), for a few seconds after each ask."""
        with self.lease_lock:
            ask = time.time() - self.last_watch[0] > 4
            if ask:
                self.last_watch[0] = time.time()
        if ask:
            self.send_cmd({"cmd": "world3d", "watch": True})

    def control_info(self, page):
        """For the page: whether someone plays the buddy, whether that is this page, from where, and what that page has open."""
        h = self.holder()
        if not h:
            return {"held": False}
        return {"held": True, "mine": bool(page) and h["page"] == page, "ip": h["ip"], "you": self.peer(), "ui": h["ui"],
                "for": round(time.time() - h["since"])}

    def control(self):
        """May steer the buddy: an address of the allow file, or the owner's session."""
        return self.allow.ok(self.peer()) or self.is_owner()

    def do_GET(self):
        control = self.control()
        path = self.path.split("?", 1)[0]
        if path == "/api/hello":
            # what the login page has to know: whether the password still has to be set with the container's code, and
            # whether there is one to type
            return self.answer(200, {"setup": self.owner.needs_setup(), "owner": not self.owner.needs_setup(),
                                     "control": control})
        if not control and not path.startswith("/fonts/"):
            if path in ("/", "/index.html"):
                return self.answer(200, open(os.path.join(HERE, "login.html"), "rb").read(), "text/html; charset=utf-8")
            return self.answer(401, {"error": "password"})
        if path in ("/", "/index.html"):
            return self.answer(200, open(os.path.join(HERE, "index.html"), "rb").read(), "text/html; charset=utf-8")
        # the 3D view (world3d.html, its scripts in 3d/) and the copy of the world around the buddy it draws (World3d in the mod)
        if path == "/3d":
            return self.answer(200, open(os.path.join(HERE, "world3d.html"), "rb").read(), "text/html; charset=utf-8")
        m = re.match(r"^/3d/([A-Za-z0-9_.-]+\.js)$", path)
        if m:
            try:
                return self.answer(200, open(os.path.join(HERE, "3d", m.group(1)), "rb").read(), "text/javascript; charset=utf-8",
                                   cache=m.group(1).startswith("three") or m.group(1) == "OrbitControls.js")
            except OSError:
                return self.answer(404, {"error": "not found"})
        if path == "/api/world3d/live":
            return self.world3d_live()
        if path == "/api/world3d/stream":
            return self.world3d_stream()
        if path == "/api/world3d":
            return self.world3d()
        if path == "/api/models":
            return self.models()
        if path == "/api/skin":
            return self.skin()
        if path == "/api/account-skin":
            return self.answer(200, self.account_skin())
        if path == "/api/containers":
            return self.answer(200, self.containers())
        if path == "/api/logs":
            return self.answer(200, {"logs": self.logs()})
        if path == "/api/logfile":
            return self.logfile()
        if path == "/api/state":
            self.keep_state()
            q = urllib.parse.parse_qs(self.path.split("?", 1)[1] if "?" in self.path else "")
            if (q.get("watch") or [""])[0] == "1":
                self.keep_watch()
            data = (self.file("state.json") or b"").strip()
            if not data.startswith(b"{"):
                return self.answer(200, {"empty": True})
            # who is playing, beside the buddy's state (the file is passed on as it is, with this in front)
            ctl = json.dumps({"control": self.control_info(self.page((q.get("page") or [""])[0]))}).encode("utf-8")
            rest = data[1:].lstrip()
            return self.answer(200, ctl[:-1] + (b"}" if rest.startswith(b"}") else b",") + (rest[1:] if rest.startswith(b"}") else rest))
        if path == "/api/map":
            self.keep_state()
            return self.answer(200, self.file("map.json") or {"empty": True})
        if path == "/api/log":
            q = dict(p.split("=", 1) for p in self.path.split("?", 1)[1].split("&") if "=" in p) if "?" in self.path else {}
            return self.answer(200, self.read_before(int(q["before"] or 0)) if "before" in q else self.read_log(int(q.get("after", "-1") or -1)))
        if path == "/api/schematics":
            return self.answer(200, self.list_schematics())
        if path == "/api/materials":
            # a placement's material list, as the mod wrote it on {"cmd":"placement","action":"materials"}
            return self.answer(200, self.file("materials.json") or {"empty": True})
        if path == "/api/profiles":
            return self.answer(200, {"profiles": self.read_profiles()})
        if path == "/api/allow":
            return self.answer(200, {"you": self.peer(), "entries": self.allow.entries()})
        if path == "/api/trusted":
            return self.answer(200, {"rows": self.read_trusted()})
        if path == "/api/modules":
            return self.answer(200, {"modules": read_modules()})
        if path == "/api/telegram":
            return self.answer(200, self.bridge.view())
        if path == "/api/version":
            # the image's version and the newest release: the header's update button (refresh=1 looks again now)
            q = urllib.parse.parse_qs(self.path.split("?", 1)[1] if "?" in self.path else "")
            return self.answer(200, game.version_info(refresh=(q.get("refresh") or [""])[0] == "1"))
        if path == "/api/game-log":
            return self.answer(200, {"lines": game.game_log()})
        if path == "/api/game":
            game.ensure_icons()
            st = game.status()
            st["owner"] = {"set": not self.owner.needs_setup(), "session": self.is_owner()}
            return self.answer(200, st)
        # the pictures and names of the game (make-icons.py, make-assets.py)
        m = re.match(r"^/(icon|effect)/([a-z0-9_]+)\.png$", path)
        if m and ASSET.match(m.group(2)):
            folder = self.icons if m.group(1) == "icon" else os.path.join(self.assets, "effect")
            try:
                return self.answer(200, open(os.path.join(folder, m.group(2) + ".png"), "rb").read(), "image/png", cache=True)
            except OSError:
                return self.answer(404, {"error": "not found"})
        # the page's own fonts (the policy keeps it to this server)
        m = re.match(r"^/fonts/([A-Za-z0-9-]+\.(woff2|css))$", path)
        if m:
            try:
                return self.answer(200, open(os.path.join(HERE, "fonts", m.group(1)), "rb").read(),
                                   "font/woff2" if m.group(2) == "woff2" else "text/css; charset=utf-8", cache=True)
            except OSError:
                return self.answer(404, {"error": "not found"})
        # what the buddy remembers of the map (MapMemory): which tiles there are in a box, and one tile as the mod wrote it
        if path == "/api/players":
            return self.answer(200, self.players())
        if path in ("/api/tiles", "/api/tile"):
            q = urllib.parse.parse_qs(self.path.split("?", 1)[1] if "?" in self.path else "")
        if path == "/api/tiles":
            return self.answer(200, self.list_tiles(q))
        if path == "/api/tile":
            dim, tx, tz = (q.get("dim") or [""])[0], (q.get("tx") or [""])[0], (q.get("tz") or [""])[0]
            if not DIM.match(dim) or not re.match(r"^-?\d{1,6}$", tx) or not re.match(r"^-?\d{1,6}$", tz):
                return self.answer(400, {"error": "dim, tx, tz"})
            try:
                with open(os.path.join(self.tile_folder(q, dim), tx + "_" + tz + ".bin"), "rb") as fh:
                    return self.answer(200, fh.read(), "application/octet-stream")
            except OSError:
                return self.answer(404, {"error": "not seen"})
        # the two lists are not kept by the browser: game.ensure_icons makes the pictures again after an update, and a list
        # kept for a day left the page without hearts and food
        if path == "/names.json":
            try:
                return self.answer(200, open(os.path.join(self.assets, "names.json"), "rb").read())
            except OSError:
                return self.answer(200, {})
        if path == "/icons.json":
            return self.answer(200, self.icon_sizes())
        return self.answer(404, {"error": "not found"})

    _sizes = None
    _sizes_at = None

    last_world3d = [0.0]

    def world3d(self):
        """The last copy of the world around the buddy; with fresh=1 a new one first, at most one every ten seconds, waited
        for up to ten seconds (a copy of six chunks around takes the mod about a second)."""
        q = urllib.parse.parse_qs(self.path.split("?", 1)[1] if "?" in self.path else "")
        f = os.path.join(self.exchange, "world3d.bin")
        if (q.get("fresh") or ["0"])[0] == "1":
            try:
                r = max(1, min(12, int((q.get("r") or ["6"])[0])))
            except ValueError:
                r = 6
            with self.lock:
                ask = time.time() - self.last_world3d[0] > 10
                if ask:
                    self.last_world3d[0] = time.time()
            if ask:
                before = os.path.getmtime(f) if os.path.exists(f) else 0
                self.send_cmd({"cmd": "world3d", "radius": r})
                until = time.time() + 10
                while time.time() < until and (os.path.getmtime(f) if os.path.exists(f) else 0) == before:
                    time.sleep(0.2)
        data = self.file("world3d.bin")
        if data is None:
            return self.answer(404, {"error": "no copy yet"})
        return self.answer(200, data, "application/octet-stream")

    def models(self):
        """The game's own models (models.json, Models in the mod): written once per game, asked for when it is not
        there yet or the page says it is of another version, and waited for up to ten seconds."""
        q = urllib.parse.parse_qs(self.path.split("?", 1)[1] if "?" in self.path else "")
        f = os.path.join(self.exchange, "models.json")
        if not os.path.exists(f) or (q.get("fresh") or ["0"])[0] == "1":
            before = os.path.getmtime(f) if os.path.exists(f) else 0
            self.send_cmd({"cmd": "models"})
            until = time.time() + 10
            while time.time() < until and (os.path.getmtime(f) if os.path.exists(f) else 0) == before:
                time.sleep(0.2)
        data = self.file("models.json")
        if data is None:
            return self.answer(404, {"error": "no models yet"})
        return self.answer(200, data, "application/json")

    skins = {}

    account_skins = {}

    def account_skin(self):
        """The account's own skin, by the name the state has, from Mojang: on a server in offline mode (a test server) the
        game shows a built-in skin, while the panel's figure should wear the buddy's own skin. By name, so a new name is
        followed by itself; kept half an hour."""
        try:
            name = json.loads(self.file("state.json") or b"{}").get("name") or ""
        except ValueError:
            name = ""
        if not PLAYER.match(name):
            return {}
        hit = self.account_skins.get(name)
        if hit and time.time() - hit[0] < 1800:
            return hit[1]
        out = {}
        try:
            prof = json.loads(urllib.request.urlopen("https://api.mojang.com/users/profiles/minecraft/" + name, timeout=8).read())
            sess = json.loads(urllib.request.urlopen("https://sessionserver.mojang.com/session/minecraft/profile/" + prof["id"], timeout=8).read())
            for prop in sess.get("properties", []):
                if prop.get("name") == "textures":
                    import base64
                    tex = json.loads(base64.b64decode(prop["value"])).get("textures", {}).get("SKIN", {})
                    m = re.search(r"/texture/([0-9a-f]{20,80})$", tex.get("url", ""))
                    if m:
                        out = {"hash": m.group(1), "slim": (tex.get("metadata") or {}).get("model") == "slim"}
        except Exception:
            out = {}
        with self.lock:
            self.account_skins[name] = (time.time() if out else time.time() - 1500, out)
        return out

    def containers(self):
        """What lay in the containers the buddy had open, on the current server, by "dim|x,y,z": the mod's containers.json
        (every container, by slot) and, where that has nothing, the chests of the base (chests.json, totals by item).
        The 3D view shows it when the mouse is on such a block."""
        try:
            server = json.loads(self.file("state.json") or b"{}").get("profile") or ""
        except ValueError:
            server = ""
        out = {}
        try:
            for c in json.loads(self.file("chests.json") or b"{}").get("chests", []):
                if c.get("server") == server:
                    items = c.get("items") or {}
                    out["%s|%d,%d,%d" % (c["dim"], c["x"], c["y"], c["z"])] = {
                        "seen": c.get("seen", 0), "size": c.get("size", 0),
                        "items": [[k if ":" in k else "minecraft:" + k, n] for k, n in sorted(items.items(), key=lambda e: -e[1])]}
        except (ValueError, KeyError, TypeError, AttributeError):
            pass
        try:
            for c in json.loads(self.file("containers.json") or b"{}").get("containers", []):
                if c.get("server") == server:
                    out["%s|%d,%d,%d" % (c["dim"], c["x"], c["y"], c["z"])] = {
                        "seen": c.get("seen", 0), "size": c.get("size", 0), "block": c.get("block", ""), "slots": c.get("slots") or []}
        except (ValueError, KeyError, TypeError, AttributeError):
            pass
        return {"server": server, "containers": out}

    def log_path(self, name):
        for n, where, _what, _empty in LOGS:
            if n == name:
                base = {"exchange": self.exchange, "panel": os.path.dirname(self.history), "state": game.STATE}[where]
                return os.path.join(base, n)
        return None

    def logs(self):
        out = []
        name = self.name_now()
        for n, _where, what, empty in LOGS:
            what = what.replace("the buddy", name)
            f = self.log_path(n)
            try:
                st = os.stat(f)
                out.append({"name": n, "what": what, "size": st.st_size, "at": int(st.st_mtime), "empty": empty})
            except OSError:
                out.append({"name": n, "what": what, "size": None, "empty": empty})
        return out

    def logfile(self):
        """One log as a download."""
        q = urllib.parse.parse_qs(self.path.split("?", 1)[1] if "?" in self.path else "")
        name = (q.get("name") or [""])[0]
        f = self.log_path(name)
        if not f:
            return self.answer(404, {"error": "no such log"})
        try:
            data = open(f, "rb").read()
        except OSError:
            return self.answer(404, {"error": "that log is not there yet"})
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Disposition", 'attachment; filename="%s"' % name)
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(data)

    def skin(self):
        """A player's skin for the 3D view, fetched from Mojang's texture server by its hash (the address the game
        downloaded it from) and kept in memory; the page cannot fetch it itself (no CORS there)."""
        q = urllib.parse.parse_qs(self.path.split("?", 1)[1] if "?" in self.path else "")
        h = (q.get("h") or [""])[0]
        if not re.fullmatch(r"[0-9a-f]{20,80}", h):
            return self.answer(400, {"error": "no skin hash"})
        data = self.skins.get(h)
        if data is None:
            try:
                r = urllib.request.urlopen("https://textures.minecraft.net/texture/" + h, timeout=10)
                data = r.read(200000)
            except Exception as e:
                return self.answer(502, {"error": str(e)})
            with self.lock:
                if len(self.skins) > 500:
                    self.skins.clear()
                self.skins[h] = data
        return self.answer(200, data, "image/png")

    def world3d_stream(self):
        """The buddy's place and what is around, as the mod writes it (world3d-ent.json, every tick while played), sent
        on one open connection as server-sent events the moment it changes: an answer per poll came late and unevenly,
        and moving looked jerky. Two minutes at a time; the page opens it again."""
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        f = os.path.join(self.exchange, "world3d-ent.json")
        # a new change of the world (a file in world3d-live) is told too, so that the page asks for it at once
        livedir = os.path.join(self.exchange, "world3d-live")
        last, until, asked, lastdir = None, time.time() + 120, 0.0, None
        try:
            while time.time() < until:
                if time.time() - asked > 5:
                    asked = time.time()
                    with self.lock:
                        ask = time.time() - self.last_live[0] > 5
                        if ask:
                            self.last_live[0] = time.time()
                    if ask:
                        self.send_cmd({"cmd": "world3d", "live": True})
                try:
                    m = os.stat(f).st_mtime_ns
                except OSError:
                    m = None
                try:
                    d = os.stat(livedir).st_mtime_ns
                except OSError:
                    d = None
                if d is not None and d != lastdir:
                    if lastdir is not None:
                        self.wfile.write(b"event: live\ndata: 1\n\n")
                        self.wfile.flush()
                    lastdir = d
                if m is not None and m != last:
                    last = m
                    data = self.file("world3d-ent.json")
                    if data:
                        self.wfile.write(b"data: " + data.replace(b"\n", b"") + b"\n\n")
                        self.wfile.flush()
                time.sleep(0.015)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        self.close_connection = True

    last_live = [0.0]

    def world3d_live(self):
        """What changed since the page's last seq: the newest positions (world3d-ent.json), then the files of
        world3d-live/ after it, each with its length before it (four bytes). It keeps the mod writing them."""
        try:
            since = int((urllib.parse.parse_qs(self.path.split("?", 1)[1] if "?" in self.path else "").get("since") or ["0"])[0])
        except ValueError:
            since = 0
        with self.lock:
            ask = time.time() - self.last_live[0] > 5
            if ask:
                self.last_live[0] = time.time()
        if ask:
            self.send_cmd({"cmd": "world3d", "live": True})
        ent = self.file("world3d-ent.json") or b"{}"
        out = [len(ent).to_bytes(4, "big"), ent]
        d = os.path.join(self.exchange, "world3d-live")
        try:
            seqs = sorted(int(n[:-4]) for n in os.listdir(d) if n.endswith(".bin") and n[:-4].isdigit())
        except OSError:
            seqs = []
        for q in [q for q in seqs if q > since][:20]:
            try:
                b = open(os.path.join(d, str(q) + ".bin"), "rb").read()
            except OSError:
                break
            out += [len(b).to_bytes(4, "big"), b]
        return self.answer(200, b"".join(out), "application/octet-stream")

    def icon_sizes(self):
        """Every icon with its width: 16 is a flat item (drawn pixel sharp), 64 a block rendered smooth. Read again when the
        folder changed: game.ensure_icons adds pictures in the background after the panel has started."""
        try:
            at = os.stat(self.icons).st_mtime_ns
        except OSError:
            at = None
        if Panel._sizes is None or at != Panel._sizes_at:
            Panel._sizes_at = at
            sizes = {}
            try:
                for f in os.listdir(self.icons):
                    if f.endswith(".png"):
                        with open(os.path.join(self.icons, f), "rb") as fh:
                            head = fh.read(24)
                        if head[:8] == b"\x89PNG\r\n\x1a\n":
                            sizes[f[:-4]] = int.from_bytes(head[16:20], "big")
            except OSError:
                pass
            Panel._sizes = sizes
        return Panel._sizes

    def players(self):
        """Who is online now and since when. The game's list (the mod's `players` event, then its joins and leaves) says
        who; since when comes from the Telegram bridge's online_since.json, which keeps it across reconnects, or else from
        the join that was seen. `exact` false: on already at the buddy's arrival, so not later than that. The buddy's own
        time online starts at the last connection, which the log has exactly; and the bridge's file counts only while the
        bridge keeps it up to date (written in the last quarter of an hour, as the bridge itself reads it), or the buddy
        would show as online since 21 Oct, the last time the file was written with that name, an unrealistic time online."""
        online, seen_at, connected = {}, 0, 0
        try:
            with open(os.path.join(self.exchange, "events.jsonl"), encoding="utf-8", errors="replace") as fh:
                for line in fh:
                    if '"players"' not in line and '"player_join"' not in line and '"player_leave"' not in line and '"connection"' not in line:
                        continue
                    try:
                        e = json.loads(line)
                    except ValueError:
                        continue
                    if e.get("type") == "players":
                        names = [n for n in str(e.get("names", "")).split() if PLAYER.match(n)]
                        online = {n: online.get(n, [e["t"], False]) for n in names}
                        seen_at = e["t"]
                    elif e.get("type") == "player_join" and PLAYER.match(str(e.get("name", ""))):
                        online[e["name"]] = [e["t"], True]
                    elif e.get("type") == "player_leave":
                        online.pop(e.get("name"), None)
                    elif e.get("type") == "connection" and e.get("action") == "connected":
                        connected = e.get("t") or 0
        except OSError:
            pass
        try:
            with open(self.since_file, encoding="utf-8") as fh:
                known = json.load(fh)
        except (OSError, ValueError):
            known = {}
        now_ms = int(time.time() * 1000)
        known = {n: k for n, k in known.items() if isinstance(k, list) and len(k) > 2 and now_ms - k[2] < 900_000}
        try:
            me = json.loads(self.file("state.json") or b"{}").get("name") or ""
        except ValueError:
            me = ""
        out = []
        for name, (t, exact) in online.items():
            k = known.get(name)
            if name == me and connected:
                t, exact = connected, True
            elif k and k[0] < t and (not exact or k[1]):
                t, exact = k[0], bool(k[1])
            out.append({"name": name, "since": t, "exact": exact})
        out.sort(key=lambda p: p["since"])
        return {"players": out, "listed": seen_at}

    def tile_folder(self, q, dim):
        """The tiles of one server's world (MapMemory keeps one folder per server profile, as a folder name)."""
        srv = re.sub(r"[^A-Za-z0-9._-]", "_", (q.get("srv") or [""])[0])[:80] or "unknown"
        if srv in (".", ".."):
            srv = "unknown"
        # the dimension's folder as the mod names it (MapMemory.dimFolder): '/' and ".." cannot reach out of maptiles
        d = re.sub(r"[^A-Za-z0-9._-]", "_", dim.replace(":", "_")).replace("..", "__")
        folder = os.path.join(self.exchange, "maptiles", srv, "_" if d == "." else d)
        # a cave layer (MapMemory keeps what the buddy saw underground per sixteen blocks of height): its own folder
        layer = (q.get("layer") or [""])[0]
        return os.path.join(folder, layer) if re.match(r"^cave-?\d{1,3}$", layer) else folder

    def list_tiles(self, q):
        dim = (q.get("dim") or [""])[0]
        try:
            box = [int((q.get(k) or ["x"])[0]) for k in ("tx0", "tz0", "tx1", "tz1")]
        except ValueError:
            return {"tiles": []}
        if not DIM.match(dim) or box[2] - box[0] > 64 or box[3] - box[1] > 64:
            return {"tiles": []}
        folder, out = self.tile_folder(q, dim), []
        try:
            for f in os.listdir(folder):
                m = re.match(r"^(-?\d+)_(-?\d+)\.bin$", f)
                if m and box[0] <= int(m.group(1)) <= box[2] and box[1] <= int(m.group(2)) <= box[3]:
                    out.append({"tx": int(m.group(1)), "tz": int(m.group(2)), "t": int(os.path.getmtime(os.path.join(folder, f)) * 1000)})
        except OSError:
            pass
        return {"tiles": out}

    def list_schematics(self):
        out = []
        try:
            for f in sorted(os.listdir(self.schematics), key=str.lower):
                full = os.path.join(self.schematics, f)
                if f.endswith(".litematic") and os.path.isfile(full):
                    st = os.stat(full)
                    out.append({"name": f, "size": st.st_size, "t": int(st.st_mtime * 1000)})
        except OSError:
            pass
        return {"files": out}

    def read_trusted(self):
        """The players the buddy trusts, in one list (Trusted players): the mod's trusted names in buddy.json, which count
        in the game, and the bridge's admins, a Telegram user id with the Minecraft name that "to me" means. Either side may
        be empty."""
        try:
            cfg = json.loads(self.file("buddy.json") or b"{}")
        except ValueError:
            cfg = {}
        names = [n.strip() for n in cfg.get("trusted", []) if isinstance(n, str) and n.strip()] if isinstance(cfg.get("trusted"), list) else []
        rows, seen = [], set()
        for a in self.bridge.view().get("admins", []):
            rows.append({"name": a["name"], "telegram": a["who"]})
            if a["name"]:
                seen.add(a["name"].lower())
        for n in names:
            if n.lower() not in seen:
                seen.add(n.lower())
                rows.append({"name": n, "telegram": ""})
        return rows

    def set_trusted(self, body):
        """The list back to both sides: the names to the mod (it only reads that on a server), the Telegram ids with their
        names to the bridge."""
        rows = body.get("rows")
        if not isinstance(rows, list) or len(rows) > 50:
            return self.answer(400, {"error": "a list of up to fifty players"})
        names, admins, ids = [], [], set()
        for r in rows:
            name = str(r.get("name", "")).strip() if isinstance(r, dict) else ""
            tg = str(r.get("telegram", "")).strip() if isinstance(r, dict) else ""
            if not name and not tg:
                continue
            if name and not PLAYER.match(name):
                return self.answer(400, {"error": "not a Minecraft name: " + name})
            if tg and not bridgecfg.WHO.match(tg):
                return self.answer(400, {"error": "not a Telegram user id or name: " + tg})
            if name and name.lower() in (n.lower() for n in names):
                return self.answer(400, {"error": name + " is in the list twice"})
            if tg and tg.lower() in ids:
                return self.answer(400, {"error": tg + " is in the list twice"})
            if name:
                names.append(name)
            if tg:
                ids.add(tg.lower())
                admins.append({"who": tg, "name": name})
        try:
            cfg = json.loads(self.file("buddy.json") or b"{}")
        except ValueError:
            cfg = {}
        before = [n.lower() for n in cfg.get("trusted", []) if isinstance(n, str)] if isinstance(cfg.get("trusted"), list) else []
        try:
            online = bool(json.loads(self.file("state.json") or b"{}").get("online"))
        except ValueError:
            online = False
        # the mod takes a setting only while the buddy is on a server: say so before anything is saved
        if sorted(before) != sorted(n.lower() for n in names) and not online:
            return self.answer(400, {"error": "the players trusted in the game change only while " + self.name_now() +
                                              " is on a server"})
        err = self.bridge.save({"admins": admins})
        if err:
            return self.answer(400, {"error": err})
        self.send_cmd({"cmd": "set", "key": "trusted", "value": names})
        return self.ok()

    def read_profiles(self):
        raw = self.file("buddy.json") or self.file("afkhelper.json")
        try:
            cfg = json.loads(raw or b"{}")
        except ValueError:
            return []
        return cfg.get("profiles", cfg.get("profielen", []))

    def read_log(self, after):
        """What the log shows after this byte offset of the history; -1 is the last 200, with where they start."""
        f = self.history
        try:
            size = os.path.getsize(f)
        except OSError:
            return {"after": 0, "start": 0, "lines": []}
        if after > size:
            after = -1
        if after < 0:
            start = max(0, size - 200_000)
            with open(f, "rb") as fh:
                fh.seek(start)
                if start:
                    start += len(fh.readline())
                raw = fh.read(size - start)
            lines = raw.decode("utf-8", "replace").splitlines()
            if len(lines) > 200:
                start += sum(len(l.encode("utf-8")) + 1 for l in lines[:-200])
                lines = lines[-200:]
            return {"after": size, "start": start, "lines": [json.loads(l) for l in lines if l.strip()]}
        with open(f, "rb") as fh:
            # an offset that is no longer the start of a line: the history was rewritten under the page (trimmed and grown
            # again, or given more per line); -1 makes the page start over from the last 200 lines
            if after:
                fh.seek(after - 1)
                if fh.read(1) != b"\n":
                    return {"after": -1, "lines": []}
            raw = fh.read(size - after)
        return {"after": size, "lines": parsed(raw)}

    def read_before(self, before):
        """Up to 200 lines that end at this byte offset of the history, and where they start (0: nothing older)."""
        f = self.history
        start = max(0, before - 200_000)
        try:
            with open(f, "rb") as fh:
                fh.seek(start)
                if start:
                    start += len(fh.readline())
                raw = fh.read(max(0, before - start))
        except OSError:
            return {"start": 0, "lines": []}
        lines = raw.decode("utf-8", "replace").splitlines()
        if len(lines) > 200:
            start += sum(len(l.encode("utf-8")) + 1 for l in lines[:-200])
            lines = lines[-200:]
        return {"start": start, "lines": parsed("\n".join(lines).encode("utf-8"))}

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if path in ("/api/login", "/api/setup", "/api/logout") and self.headers.get("X-Buddy") == "1":
            return {"/api/login": self.login, "/api/setup": self.setup_owner, "/api/logout": self.logout}[path]()
        if not self.control() or self.headers.get("X-Buddy") != "1":
            return self.deny()
        n = int(self.headers.get("Content-Length") or 0)
        if path == "/api/schematic":
            return self.upload(n)
        if n > 65536:
            self.close_connection = True
            return self.answer(413, {"error": "too large"})
        try:
            body = json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return self.answer(400, {"error": "no json"})
        # playing the buddy while another page plays: refused, with who and from where
        if path in ("/api/inv", "/api/menu-close", "/api/select") or (path == "/api/command" and isinstance(body, dict) and
                isinstance(body.get("command"), dict) and body["command"].get("cmd") in GAME_ACTIONS):
            h = self.holder()
            if h and h["page"] != self.page():
                return self.busy(h)
        if path == "/api/say":
            text = " ".join(str(body.get("text", "")).split())
            if not text or len(text) > 256:
                return self.answer(400, {"error": "empty or longer than 256 characters"})
            self.send_cmd({"cmd": "chat", "text": text})
            return self.ok()
        if path == "/api/tab":
            text = str(body.get("text", ""))
            if not text.startswith("/") or len(text) > 256 or any(c in text for c in "\r\n"):
                return self.answer(400, {"error": "a /command of at most 256 characters"})
            return self.tab(text)
        if path == "/api/inv":
            mode = body.get("mode", "pickup")
            slot, button = body.get("slot", -1), body.get("button", 0)
            # a click in an open menu (a chest, /shards) names it, and its slots are the menu's own: up to a large chest
            # and the inventory's 36 below it, a little more for menus with extra slots
            menu = body.get("menu")
            top = 45 if menu is None else 127
            if menu is not None and (not isinstance(menu, int) or isinstance(menu, bool)):
                return self.answer(400, {"error": "not a menu"})
            if mode not in INV_MODES or not isinstance(slot, int) or not isinstance(button, int) or not (slot == -999 or 0 <= slot <= top):
                return self.answer(400, {"error": "not a click"})
            cmd = {"cmd": "invclick", "slot": slot, "button": button, "mode": mode}
            if menu is not None:
                cmd["menu"] = menu
            if mode == "drag":
                slots = body.get("slots", [])
                if not isinstance(slots, list) or not all(isinstance(s, int) and 0 <= s <= top for s in slots) or len(slots) > top + 1:
                    return self.answer(400, {"error": "not a drag"})
                cmd["slots"] = slots
            self.send_cmd(cmd)
            self.keep_state()
            return self.ok()
        if path == "/api/menu-close":
            self.send_cmd({"cmd": "menuclose"})
            self.keep_state()
            return self.ok()
        if path == "/api/select":
            slot = body.get("slot")
            if not isinstance(slot, int) or not 0 <= slot <= 8:
                return self.answer(400, {"error": "no hotbar slot"})
            self.send_cmd({"cmd": "select", "slot": slot})
            return self.ok()
        if path == "/api/goto":
            try:
                x, y, z = (int(body[k]) for k in ("x", "y", "z"))
            except (KeyError, TypeError, ValueError):
                return self.answer(400, {"error": "x, y and z"})
            self.send_cmd({"cmd": "goto", "x": x, "y": y, "z": z})
            return self.ok()
        if path == "/api/map-mode":
            mode = body.get("mode")
            if mode not in ("auto", "surface", "cave"):
                return self.answer(400, {"error": "auto, surface or cave"})
            self.send_cmd({"cmd": "map", "mode": mode})
            return self.ok()
        if path == "/api/mode":
            mode = body.get("mode")
            if mode not in ("base", "follow", "build", "afk"):
                return self.answer(400, {"error": "base, follow, build or afk"})
            cmd = {"cmd": "mode", "mode": mode}
            player = str(body.get("player", "")).strip()
            if player:
                if not NAME.match(player):
                    return self.answer(400, {"error": "not a player name"})
                cmd["player"] = player
            self.send_cmd(cmd)
            return self.ok()
        if path == "/api/setting":
            name, value = body.get("name"), body.get("value")
            if name in AMOUNTS:
                lo, hi = AMOUNTS[name]
                if isinstance(value, bool) or not isinstance(value, int) or not lo <= value <= hi:
                    return self.answer(400, {"error": "an amount from %d to %d" % (lo, hi)})
            elif name not in SETTINGS or not isinstance(value, bool):
                return self.answer(400, {"error": "unknown setting"})
            self.send_cmd({"cmd": "setting", "name": name, "value": value})
            self.keep_state()
            return self.ok()
        if path in ("/api/leave", "/api/join"):
            self.send_cmd({"cmd": path[5:]})
            return self.ok()
        if path == "/api/command":
            cmd = body.get("command")
            if not isinstance(cmd, dict) or not isinstance(cmd.get("cmd"), str):
                return self.answer(400, {"error": "a command is an object with cmd"})
            self.send_cmd(cmd)
            return self.ok()
        if path == "/api/steer":
            return self.steer(body)
        if path == "/api/profiles":
            return self.set_profiles(body)
        if path == "/api/allow":
            return self.set_allow(body)
        if path == "/api/trusted":
            return self.set_trusted(body)
        if path == "/api/telegram":
            err = self.bridge.save(body)
            return self.answer(400, {"error": err}) if err else self.ok()
        if path == "/api/log-empty":
            name = body.get("name")
            f = self.log_path(name) if isinstance(name, str) else None
            if not f or not next(e for n, _w, _x, e in LOGS if n == name):
                return self.answer(400, {"error": "that log cannot be emptied here"})
            try:
                with self.lock:
                    open(f, "r+b").truncate(0)
            except FileNotFoundError:
                pass
            except OSError as e:
                return self.answer(500, {"error": str(e)})
            return self.ok()
        if path == "/api/telegram-test":
            return self.answer(200, self.bridge.test(bool(body.get("send"))))
        if path == "/api/game":
            err = game.set_settings(body)
            return self.answer(400, {"error": err}) if err else self.ok()
        if path == "/api/game-job":
            kind = body.get("kind")
            err = game.stop_job() if kind == "stop" else game.start_job(str(kind))
            return self.answer(400, {"error": err}) if err else self.ok()
        if path == "/api/game-service":
            action = body.get("action")
            if action not in ("start", "stop", "restart"):
                return self.answer(400, {"error": "start, stop or restart"})
            code, out = game.control(action)
            return self.answer(400, {"error": out[:200] or action + " failed"}) if code else self.ok()
        if path == "/api/owner-password":
            # the first password (an address of the allow file needs no setup code) or a new one
            err = self.owner.first(body.get("new")) if self.owner.needs_setup() else self.owner.change(body.get("old"), body.get("new"))
            return self.answer(400, {"error": err}) if err else self.answer(200, {"ok": True}, cookies=[("buddy_owner", self.owner.token(), owner.Owner.DAYS * 86400)])
        if path == "/api/image-update":
            err = game.image_update()
            return self.answer(400, {"error": err}) if err else self.ok()
        if path == "/api/schematic-delete":
            name = str(body.get("name", ""))
            if not SCHEMATIC.match(name):
                return self.answer(400, {"error": "not a valid name"})
            try:
                os.remove(os.path.join(self.schematics, name))
            except OSError:
                return self.answer(404, {"error": "not found"})
            return self.ok()
        return self.answer(404, {"error": "not found"})

    def ok(self):
        return self.answer(200, {"ok": True})

    def tab(self, text):
        """What Tab offers after a command typed in the chat box: the mod asks the game, and the server where it fills them
        in, and writes a `tab` event with this question's id; this waits for it, two seconds at most. `start` is where in
        the text the suggestions begin: what they replace runs from there to the end."""
        events = os.path.join(self.exchange, "events.jsonl")
        try:
            pos = os.path.getsize(events)
        except OSError:
            pos = 0
        qid = os.urandom(6).hex()
        self.send_cmd({"cmd": "tab", "text": text, "id": qid})
        until, rest = time.time() + 2.0, b""
        while time.time() < until:
            time.sleep(0.05)
            try:
                size = os.path.getsize(events)
                if size < pos:  # the game started anew and emptied the file
                    pos, rest = 0, b""
                if size == pos:
                    continue
                with open(events, "rb") as fh:
                    fh.seek(pos)
                    rest += fh.read(size - pos)
                pos = size
            except OSError:
                continue
            *lines, rest = rest.split(b"\n")
            for line in lines:
                if b'"tab"' not in line or qid.encode() not in line:
                    continue
                try:
                    e = json.loads(line)
                except ValueError:
                    continue
                if e.get("type") == "tab" and e.get("id") == qid:
                    found = [str(x) for x in (e.get("suggestions") or [])][:200]
                    start = e.get("start") if isinstance(e.get("start"), int) else len(text)
                    return self.answer(200, {"start": max(0, min(len(text), start)), "suggestions": found})
        return self.answer(200, {"start": len(text), "suggestions": [], "late": True})

    def small_body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 1024:
            self.close_connection = True
            return None
        try:
            b = json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return None
        return b if isinstance(b, dict) else None

    def login(self):
        """The password gives a session of thirty days."""
        b = self.small_body()
        if b is None:
            return self.answer(400, {"error": "no json"})
        if self.owner.needs_setup():
            return self.answer(400, {"error": "no password yet: set it with the setup code"})
        right = self.owner.check(self.peer(), str(b.get("password", "")))
        if right is None:
            return self.answer(429, {"error": "too many tries"})
        if not right:
            return self.answer(401, {"error": "wrong password"})
        return self.answer(200, {"ok": True, "owner": True}, cookies=[("buddy_owner", self.owner.token(), owner.Owner.DAYS * 86400)])

    def setup_owner(self):
        b = self.small_body()
        if b is None:
            return self.answer(400, {"error": "no json"})
        err = self.owner.setup(self.peer(), b.get("code"), b.get("password"))
        if err:
            return self.answer(400, {"error": err})
        return self.answer(200, {"ok": True}, cookies=[("buddy_owner", self.owner.token(), owner.Owner.DAYS * 86400)])

    def logout(self):
        # the session goes on the panel too: the cookie, if it was copied, no longer opens anything
        self.owner.end(self.cookie("buddy_owner"))
        return self.answer(200, {"ok": True}, cookies=[("buddy_owner", "-", 0)])

    def set_profiles(self, body):
        """The whole list of profiles, handed to the mod, which checks it, keeps it in buddy.json and uses it at once."""
        profiles = body.get("profiles")
        if not isinstance(profiles, list) or len(profiles) > 30:
            return self.answer(400, {"error": "a list of profiles"})
        for p in profiles:
            if not isinstance(p, dict) or not isinstance(p.get("name"), str) or not p["name"].strip():
                return self.answer(400, {"error": "every profile needs a name"})
            for k in ("commands", "texts"):
                if k in p and (not isinstance(p[k], dict) or not all(isinstance(v, str) for v in p[k].values())):
                    return self.answer(400, {"error": k + " is a list of names and lines"})
            for k in ("afterJoin", "eventDims", "voidOk"):
                if k in p and (not isinstance(p[k], list) or not all(isinstance(v, str) for v in p[k])):
                    return self.answer(400, {"error": k + " is a list of lines"})
            if "sleepFrom" in p and not (isinstance(p["sleepFrom"], int) and not isinstance(p["sleepFrom"], bool) and 0 <= p["sleepFrom"] < 24000):
                return self.answer(400, {"error": "sleep from is a game time from 00:00 to 23:59"})
            if "modules" in p and not isinstance(p["modules"], dict):
                return self.answer(400, {"error": "modules is an object of modules and their settings"})
            for k in ("texts",):
                for name, line in p.get(k, {}).items():
                    try:
                        re.compile(line.replace("{player}", "x"))
                    except re.error as e:
                        return self.answer(400, {"error": "text " + name + ": " + str(e)})
        if game.service().get("active") == "active":
            self.send_cmd({"cmd": "profiles", "set": profiles})
        else:
            # no game: the mod reads no command until it starts, and buddy.json is ours meanwhile
            game.mod_settings({"profiles": profiles})
        return self.ok()

    def set_allow(self, body):
        """Who may open this page. Your own address has to stay in, or the page would lock you out at once."""
        entries = body.get("entries")
        if not isinstance(entries, list) or not 0 < len(entries) <= 50:
            return self.answer(400, {"error": "one to fifty entries"})
        lines, nets = [], []
        for e in entries:
            net = str(e.get("net", "")).strip() if isinstance(e, dict) else ""
            note = " ".join(str(e.get("note", "")).replace("#", "").split())[:60] if isinstance(e, dict) else ""
            try:
                n = ipaddress.ip_network(net, strict=False)
            except ValueError:
                return self.answer(400, {"error": "not an address or network: " + net})
            if n.prefixlen < (8 if n.version == 4 else 32):
                return self.answer(400, {"error": "too wide: " + net})
            nets.append(n)
            lines.append(str(n) + ("  # " + note if note else ""))
        if not covered(self.peer(), nets) and not self.is_owner():
            return self.answer(400, {"error": "your own address " + self.peer() + " has to stay in the list"})
        data = "# Who may open the buddy's panel; written by the panel itself\n" + "\n".join(lines) + "\n"
        with self.lock:
            with open(self.allow.path, "w", encoding="utf-8") as fh:
                fh.write(data)
        return self.ok()

    def steer(self, body):
        """What the page holds, in steer.json, written whole (a new file renamed over the old): the mod reads it the
        tick it changes and lets go of everything when it is more than a second old (Steer.java). Only the page that
        has control writes it (LEASE_S); another is refused, and its letting go changes nothing."""
        keys = [k for k in str(body.get("keys", "")).split() if k in STEER_KEYS]
        try:
            state = {"t": int(time.time() * 1000), "on": bool(body.get("on")), "session": str(body.get("session", ""))[:40],
                     "keys": " ".join(sorted(set(keys))), "yaw": max(-1e6, min(1e6, float(body.get("yaw", 0)))),
                     "pitch": max(-180.0, min(180.0, float(body.get("pitch", 0)))), "right": int(body.get("right", 0)),
                     "slot": int(body.get("slot", -1))}
            # the block the 3D view's crosshair is on, and which side: the mod clicks that one (Steer.target)
            tg = body.get("target")
            if isinstance(tg, dict) and isinstance(tg.get("entity"), int):
                state["target"] = {"entity": int(tg["entity"])}
            elif isinstance(tg, dict) and tg.get("face") in ("up", "down", "north", "south", "east", "west"):
                state["target"] = {"x": int(tg["x"]), "y": int(tg["y"]), "z": int(tg["z"]), "face": tg["face"]}
        except (TypeError, ValueError, KeyError):
            return self.answer(400, {"error": "invalid values"})
        page, ip, now = self.page() or "s" + state["session"], self.peer(), time.time()
        ui = body.get("ui") if body.get("ui") in ("inv",) else ""
        with self.lease_lock:
            lease = Panel.lease
            held = bool(lease["page"]) and now - lease["t"] < LEASE_S
            other = dict(lease) if held and lease["page"] != page else None
            if not other:
                if state["on"]:
                    Panel.lease = {"page": page, "ip": ip, "t": now, "ui": ui, "since": lease["since"] if held else now}
                else:
                    Panel.lease = {"page": None, "ip": None, "t": 0.0, "ui": "", "since": 0.0}
        if other:
            # not this page's to let go; and steering waits until the other lets go
            return self.ok() if not state["on"] else self.busy(other)
        f = os.path.join(self.exchange, "steer.json")
        with self.lock:
            with open(f + ".tmp", "w", encoding="utf-8") as fh:
                json.dump(state, fh)
            os.replace(f + ".tmp", f)
        if state["on"]:
            self.keep_state()
        return self.ok()

    def upload(self, n):
        """A .litematic for the builder, into the schematics folder of the buddy's game (the name in the header X-Name)."""
        name = os.path.basename(self.headers.get("X-Name", ""))
        if not SCHEMATIC.match(name):
            self.close_connection = True
            return self.answer(400, {"error": "a name of letters, digits and spaces, ending in .litematic"})
        if not 0 < n <= MAX_SCHEMATIC:
            self.close_connection = True
            return self.answer(413, {"error": "empty or larger than 8 MB"})
        data = self.rfile.read(n)
        if data[:2] != b"\x1f\x8b":
            return self.answer(400, {"error": "not a Litematica file"})
        os.makedirs(self.schematics, exist_ok=True)
        target = os.path.join(self.schematics, name)
        with open(target + ".tmp", "wb") as fh:
            fh.write(data)
        os.replace(target + ".tmp", target)
        return self.answer(200, {"ok": True, "name": name})


class Server(ThreadingHTTPServer):
    """One thread a connection; open to everyone since the page is public, so a cap on how many connections there are,
    in all and from one address, keeps a crowd of them from eating the VPS."""
    daemon_threads = True
    MAX_CONN, MAX_PER_ADDR = 200, 24

    def process_request(self, request, client_address):
        a = bare(client_address[0])
        with self.conn_lock:
            if sum(self.conns.values()) >= self.MAX_CONN or self.conns.get(a, 0) >= self.MAX_PER_ADDR:
                self.shutdown_request(request)
                return
            self.conns[a] = self.conns.get(a, 0) + 1
        try:
            super().process_request(request, client_address)
        except Exception:
            self.done(a)
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.done(bare(client_address[0]))

    def done(self, a):
        with self.conn_lock:
            self.conns[a] = self.conns.get(a, 1) - 1
            if self.conns[a] <= 0:
                del self.conns[a]

    def handle_error(self, request, client_address):
        # a failed handshake or a dropped connection: nothing to log
        pass

    def get_request(self):
        sock, addr = self.socket.accept()
        sock.settimeout(20)
        return sock, addr

    def __init__(self, addr, handler, v6):
        self.conns, self.conn_lock = {}, threading.Lock()
        if v6:
            import socket
            self.address_family = socket.AF_INET6
        super().__init__(addr, handler)

    def server_bind(self):
        import socket
        if self.address_family == socket.AF_INET6:
            # both families on one socket: IPv4 arrives as ::ffff:a.b.c.d
            self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        super().server_bind()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--exchange", default=os.path.expanduser("~/Development/minecraft-afk"))
    ap.add_argument("--allow", default=os.path.expanduser("~/.config/buddy-panel/allow.txt"))
    # the owner's password (owner.py), where to listen, and where Caddy's connections come from
    ap.add_argument("--owner", default=os.path.expanduser("~/.config/buddy-panel/owner.json"),
                    help="owner.json: the password, with a setup code until it is set")
    ap.add_argument("--host", default="127.0.0.1", help="the address to listen on")
    ap.add_argument("--proxy-host", default=None, help="the host name of the reverse proxy whose X-Forwarded-For counts")
    ap.add_argument("--proxy-secret", default=None,
                    help="a file with the SHA-256 (hex) of the secret the reverse proxy sends in X-Buddy-Proxy: then only "
                         "an X-Forwarded-For beside it counts")
    ap.add_argument("--port", type=int, default=8080)
    # the builder's schematics: in the game's folder (game.py's settings)
    ap.add_argument("--schematics", default=None)
    ap.add_argument("--assets", default=os.path.join(game.PANEL_DATA, "assets"))
    ap.add_argument("--icons", default=os.path.join(game.PANEL_DATA, "icons"))
    ap.add_argument("--history", default=os.path.expanduser("~/panel/history.jsonl"))
    ap.add_argument("--since", default=os.path.expanduser("~/online_since.json"))
    # the Telegram bridge's settings (bridge/chat2telegram.py); its status lies beside them
    ap.add_argument("--bridge-config", default=os.path.expanduser("~/telegram.json"))
    a = ap.parse_args()
    Panel.exchange = a.exchange
    Panel.allow = Allow(a.allow)
    Panel.owner = owner.Owner(a.owner)
    Panel.proxy_host = a.proxy_host
    Panel.proxy_secret = a.proxy_secret
    if a.schematics is None:
        a.schematics = os.path.join(game.instance(game.settings()), "schematics")
    Panel.schematics, Panel.assets, Panel.icons = a.schematics, a.assets, a.icons
    Panel.history = a.history
    Panel.since_file = a.since
    Panel.bridge = bridgecfg.Bridge(a.bridge_config)
    History(a.exchange, a.history).start()
    srv = Server((a.host, a.port), Panel, ":" in a.host)
    if a.proxy_host:
        # every connection comes from Caddy: no cap per address, Caddy's own limits hold
        srv.MAX_PER_ADDR = srv.MAX_CONN
    srv.serve_forever()


if __name__ == "__main__":
    main()
