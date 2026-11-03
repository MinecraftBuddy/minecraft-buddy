#!/usr/bin/env python3
"""The buddy's game in his container, set up from nothing: from the panel (Getting started, Settings) or from a shell in
the container (python3 game.py <command>; on the machine: docker exec <container> python3 /opt/buddy/panel/game.py ...).

A buddy can be set up from nothing through the web panel. Docker is the one way to run it; the way without Docker is
gone.

The game is HeadlessMC (a Minecraft launcher without a window) in one folder, game_dir, with Minecraft and its loader, the
mods of setup/mods.json and the buddy's own jars, which come with the image (/opt/buddy/jars) and are put in the mods folder
at every start of the game, so a new image is a new buddy; with BUDDY_JARS=folder (compose.yaml) the mods folder's
own jars stay. The game is a child of setup/supervise.py, the container's own process, which runs it while
game-want.json in the state folder says so and writes what it does to game-runs.json; start, stop and restart here write
the first and wait for the second. An update is asking Watchtower for the newest image (image_update). A job (install,
login) is a process of its own that outlives the panel: its lines go to ~/.local/state/buddy/job.log and its state to
job.json beside it. The panel's pictures of the game go to BUDDY_PANEL_DATA, as the panel's own folder is the image's.

The settings are ~/buddy-game.json (600): game_dir, headlessmc, minecraft, loader, server, memory, language, modules, render_distance,
exchange, offline. What the mod needs to know of the account and the server (altName, servers in buddy.json) is written
while the game is stopped: a new account or another server takes a restart of the game anyway.

    python3 game.py status          what is installed, the account, the game, the image
    python3 game.py install         HeadlessMC, Minecraft and its loader, the mods, the buddy's jars, the starting files
    python3 game.py login           log the Minecraft account in (prints a Microsoft link to open)
    python3 game.py start|stop|restart
    python3 game.py set key=value ...   a setting, as the panel's Save (a new server: the game restarts when it runs)
    python3 game.py server ADDRESS      the server, and the game (re)started there (the test rig: 127.0.0.1:25590)
    python3 game.py path mods|game|instance   a folder of the game, for scripts
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HOME = os.path.expanduser("~")
HERE = os.path.dirname(os.path.abspath(__file__))
# beside panel/, in the image as in the repo
SETUP = next((p for p in (os.path.join(HERE, "setup"), os.path.join(HERE, "..", "setup")) if os.path.isdir(p)),
             os.path.join(HERE, "setup"))
SETTINGS = os.path.join(HOME, "buddy-game.json")
STATE = os.path.join(HOME, ".local", "state", "buddy")
JOB, JOBLOG = os.path.join(STATE, "job.json"), os.path.join(STATE, "job.log")
HMC_REPO = "3arthqu4ke/headlessmc"
DEFAULTS = {"game_dir": "~/game", "headlessmc": "3.0.0-RC8", "minecraft": "26.3", "loader": "fabric", "server": "",
            "memory": "2G", "language": "en_us", "modules": [], "render_distance": 10,
            "exchange": "~/Development/minecraft-afk", "offline": False}
# the image's own: the jars and the version with the image, a supervisor for the game, Watchtower for an update
IMAGE = os.environ.get("BUDDY_IMAGE_DIR", "/opt/buddy")
WANT, RUNS = os.path.join(STATE, "game-want.json"), os.path.join(STATE, "game-runs.json")
# where the panel's pictures and names of the game are made (make-icons.py, make-assets.py): in the volume
PANEL_DATA = os.environ.get("BUDDY_PANEL_DATA") or HERE
SERVER = re.compile(r"^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?(:\d{1,5})?$")
MEMORY = re.compile(r"^([2-9]|1[0-6])G$|^(20[4-9]\d|2[1-9]\d\d|[3-9]\d{3}|1[0-6]\d{3})M$")   # HeadlessMC itself asks -Xms2G
VERSION = re.compile(r"^[A-Za-z0-9._+-]{1,40}$")
MODULE = re.compile(r"^[a-z0-9_-]{1,40}$")
LANG = re.compile(r"^[a-z]{2,3}_[a-z]{2,3}$")
PATH = re.compile(r"^[/~][^\x00-\x1f\x7f]{0,299}$")
LOGIN_LINK = re.compile(r"(https://(?:www\.)?microsoft\.com/link\S*)")


# ---------- settings ----------

def settings():
    try:
        with open(SETTINGS, encoding="utf-8") as f:
            cfg = json.load(f)
        if not isinstance(cfg, dict):
            cfg = {}
    except (OSError, ValueError):
        cfg = {}
    return {**DEFAULTS, **cfg}


def write_private(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(data)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


def save_settings(cfg):
    keep = {k: v for k, v in cfg.items() if k in DEFAULTS}
    write_private(SETTINGS, json.dumps(keep, ensure_ascii=False, indent=2) + "\n")


def set_settings(body):
    """Merge what the page sends. Returns None, or what is wrong."""
    if not isinstance(body, dict):
        return "no settings"
    cfg = settings()
    rules = {"server": lambda v: v == "" or SERVER.match(v), "memory": MEMORY.match, "minecraft": VERSION.match,
             "loader": lambda v: v in ("fabric",), "headlessmc": VERSION.match, "language": LANG.match,
             "game_dir": PATH.match, "exchange": PATH.match}
    words = {"server": "the server's address", "memory": "the memory (2G to 16G)", "minecraft": "the Minecraft version",
             "loader": "the loader (fabric)", "headlessmc": "the HeadlessMC version", "language": "the game's language (en_us, nl_nl, ...)",
             "game_dir": "the game folder", "exchange": "the exchange folder"}
    for k, rule in rules.items():
        if k in body:
            v = str(body[k] or "").strip()
            if not rule(v):
                return words[k] + " is not valid"
            cfg[k] = v
    if "offline" in body:
        # a test machine without his account: HeadlessMC's offline account, which only an offline-mode server takes
        cfg["offline"] = bool(body["offline"])
    if "render_distance" in body:
        # how many chunks far their game asks the server for, set from the panel's settings: the panel's 3D view shows
        # what their game holds
        try:
            rd = int(body["render_distance"])
        except (TypeError, ValueError):
            return "the render distance is a number of chunks (2 to 32)"
        if not 2 <= rd <= 32:
            return "the render distance is a number of chunks (2 to 32)"
        cfg["render_distance"] = rd
    if "modules" in body:
        mods = [str(m).strip() for m in (body["modules"] or []) if str(m).strip()]
        if len(mods) > 10 or not all(MODULE.match(m) for m in mods):
            return "the modules are names like tweakcraft"
        cfg["modules"] = mods
    old = settings()
    save_settings(cfg)
    if int(cfg.get("render_distance", 10)) != int(old.get("render_distance", 10)):
        # at once while the game runs (the mod sets it and the game tells the server), and in options.txt for the next start
        if service().get("active") == "active":
            send_mod({"cmd": "renderdistance", "chunks": int(cfg["render_distance"])})
        else:
            set_option(cfg, "renderDistance", int(cfg["render_distance"]))
    host = host_of(cfg["server"]).lower()
    if cfg["server"] != old["server"] and cfg["server"]:
        # the mod acts on a server only when it knows it, and the server gets a profile of its own when none fits it: both
        # while the game is stopped (the mod writes buddy.json itself while it runs)
        with_game_stopped(lambda: mod_settings({"servers+": host, "profile+": host}))
    elif cfg["server"] and not profile_fits(host):
        # the same server, saved again, without a profile: handed to the mod while it runs, written while it does not
        if service().get("active") == "active":
            send_mod({"cmd": "profiles", "set": profiles_with(host)})
        else:
            mod_settings({"profile+": host})
    return None


# ---------- a server is a profile: setting a server links it to a (new) profile named after that server ----------

def buddy_json():
    try:
        with open(os.path.join(expand(settings(), "exchange"), "buddy.json"), encoding="utf-8") as f:
            b = json.load(f)
        return b if isinstance(b, dict) else {}
    except (OSError, ValueError):
        return {}


def profile_fits(host, profiles=None):
    """Does a profile fit this server, as the mod picks one: an empty address fits every server, otherwise the address is
    a part of the server's."""
    profiles = buddy_json().get("profiles") if profiles is None else profiles
    return any(isinstance(p, dict) and (not p.get("address") or str(p["address"]).lower() in host) for p in (profiles or []))


def profiles_with(host, profiles=None):
    """The profiles, with a new one for this server when none fits: named after it, with the defaults, for the owner to
    fill in (Settings, Server profiles)."""
    profiles = [p for p in ((buddy_json().get("profiles") if profiles is None else profiles) or []) if isinstance(p, dict)]
    if host and not profile_fits(host, profiles):
        profiles.append({"name": host, "address": host, "resourcepack": "accept"})
    return profiles


def set_option(cfg, key, value):
    """One line of the game's options.txt (only while the game is stopped: the game writes the file itself)."""
    opt = os.path.join(instance(cfg), "options.txt")
    try:
        with open(opt, encoding="utf-8") as f:
            lines = f.read().splitlines()
    except OSError:
        return
    lines = [l for l in lines if not l.startswith(key + ":")] + ["%s:%s" % (key, value)]
    with open(opt + ".tmp", "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    os.replace(opt + ".tmp", opt)


def send_mod(obj):
    """A command for the mod while it runs: a line in commands.jsonl, as the panel sends them."""
    path = os.path.join(expand(settings(), "exchange"), "commands.jsonl")
    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(obj, ensure_ascii=False) + "\n")


def expand(cfg, key):
    return os.path.abspath(os.path.expanduser(cfg[key]))


def game_dir(cfg):
    return expand(cfg, "game_dir")


def instance(cfg):
    # HeadlessMC 3 gives each version its own game folder (seen with RC5 and RC8: home/.minecraft/fabric-26.3)
    return os.path.join(game_dir(cfg), "home", ".minecraft", cfg["loader"] + "-" + cfg["minecraft"])


def mods_dir(cfg):
    return os.path.join(instance(cfg), "mods")


def host_of(server):
    return server.rsplit(":", 1)[0] if server.count(":") == 1 else server


# ---------- the buddy's own settings (buddy.json in the exchange folder) ----------

def mod_settings(change):
    """Write what the mod has to know beforehand into buddy.json: only while the game is stopped (the mod writes the file
    itself while it runs). "servers+" adds one server, "profile+" a profile for a server that none fits."""
    cfg = settings()
    path = os.path.join(expand(cfg, "exchange"), "buddy.json")
    try:
        with open(path, encoding="utf-8") as f:
            b = json.load(f)
    except (OSError, ValueError):
        b = {}
    for k, v in change.items():
        if k == "servers+":
            lst = [s for s in b.get("servers", []) if isinstance(s, str)]
            if v and v not in lst:
                lst.append(v)
            b["servers"] = lst
        elif k == "profile+":
            b["profiles"] = profiles_with(v, b.get("profiles"))
        else:
            b[k] = v
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(b, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def with_game_stopped(fn):
    """Do fn while the game is stopped, and start it again if it ran."""
    was = service().get("active") == "active"
    if was:
        control("stop")
    try:
        fn()
    finally:
        if was:
            control("start")


# ---------- the game, which the container's supervisor runs (setup/supervise.py) ----------

def want():
    try:
        with open(WANT, encoding="utf-8") as f:
            w = json.load(f)
        return w if isinstance(w, dict) else {}
    except (OSError, ValueError):
        return {}


def runs():
    try:
        with open(RUNS, encoding="utf-8") as f:
            r = json.load(f)
        return r if isinstance(r, dict) else {}
    except (OSError, ValueError):
        return {}


def control(action):
    """start, stop, restart for the supervisor: what is wanted, then a wait for what it does. A stop stays a stop over
    a restart of the container (the game runs when it was started last)."""
    if action not in ("start", "stop", "restart"):
        return 1, "not here: " + action
    w = want()
    before = runs().get("started", 0)
    w["run"] = action != "stop"
    if action == "restart":
        w["restart"] = int(w.get("restart", 0)) + 1
    write_private(WANT, json.dumps(w))
    end = time.time() + (60 if action == "stop" else 15)
    while time.time() < end:
        r = runs()
        if action == "stop" and r.get("state") in ("inactive", "failed"):
            return 0, ""
        if action != "stop" and r.get("state") in ("active", "activating") and (action == "start" or r.get("started", 0) > before):
            return 0, ""
        time.sleep(0.5)
    if time.time() - runs().get("at", 0) > 20:
        return 1, "the container's supervisor does not answer"
    return 0, ""


def service():
    r, w = runs(), want()
    if not r or time.time() - r.get("at", 0) > 30:
        return {"error": "the container's supervisor does not answer"}
    return {"loaded": True, "active": r.get("state", "inactive"), "sub": r.get("why", ""), "enabled": "enabled" if w.get("run") else "disabled",
            "since": r.get("since", ""), "restarts": r.get("restarts", 0)}


# ---------- HeadlessMC ----------

def hmc(cfg, *args):
    """The command line and environment that run HeadlessMC with everything it keeps inside game_dir."""
    d = game_dir(cfg)
    env = dict(os.environ, XDG_CONFIG_HOME=d + "/config", XDG_DATA_HOME=d + "/data", XDG_CACHE_HOME=d + "/cache",
               XDG_STATE_HOME=d + "/state")
    return ["java", "-Duser.home=" + d + "/home", "-jar", d + "/headlessmc.jar", *args], env, d


def run_hmc(cfg, *args, timeout=1800):
    argv, env, cwd = hmc(cfg, *args)
    p = subprocess.Popen(argv, env=env, cwd=cwd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         text=True, bufsize=1)
    end = time.time() + timeout
    for line in p.stdout:
        say("  " + line.rstrip())
        if time.time() > end:
            p.kill()
            raise RuntimeError("HeadlessMC took longer than %d minutes" % (timeout // 60))
    return p.wait()


def accounts(cfg):
    """The names of the accounts HeadlessMC keeps (the keys of its account file: nothing of the tokens is read out)."""
    out = []
    # default holds the accounts; last only HeadlessMC's pointer to the one used last (its key is "latest")
    for f in ("default",):
        try:
            with open(os.path.join(game_dir(cfg), "data", "headlessmc", ".auth", f, ".accounts.json"), encoding="utf-8") as fh:
                a = json.load(fh).get("accounts") or {}
            out += [str(k) for k in (a if isinstance(a, dict) else {}) if str(k) not in out]
        except (OSError, ValueError, AttributeError):
            pass
    return out


# ---------- GitHub ----------

def gh(path):
    """A question to GitHub's API, without a login: HeadlessMC's public releases."""
    req = urllib.request.Request("https://api.github.com" + path, headers={"Accept": "application/vnd.github+json",
                                                                         "User-Agent": "minecraft-buddy-panel"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode())


def download(url, dest, sha256="", sha512="", headers=None):
    """A file to dest, checked against its hash, written whole. A redirect is followed by hand, without the headers."""
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k):
            return None
    opener = urllib.request.build_opener(NoRedirect)
    req = urllib.request.Request(url, headers={"User-Agent": "minecraft-buddy-panel", **(headers or {})})
    try:
        r = opener.open(req, timeout=60)
    except urllib.error.HTTPError as e:
        if e.code not in (301, 302, 303, 307, 308):
            raise
        r = urllib.request.urlopen(urllib.request.Request(e.headers["Location"], headers={"User-Agent": "minecraft-buddy-panel"}),
                                   timeout=60)
    h256, h512 = hashlib.sha256(), hashlib.sha512()
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest + ".part", "wb") as f:
        while True:
            chunk = r.read(1 << 16)
            if not chunk:
                break
            f.write(chunk)
            h256.update(chunk)
            h512.update(chunk)
    r.close()
    if (sha256 and h256.hexdigest() != sha256) or (sha512 and h512.hexdigest() != sha512):
        os.remove(dest + ".part")
        raise RuntimeError("%s: the download does not match its hash" % os.path.basename(dest))
    os.replace(dest + ".part", dest)


# ---------- what is there ----------

def status():
    cfg = settings()
    d, inst = game_dir(cfg), instance(cfg)
    mods = sorted(f for f in os.listdir(mods_dir(cfg)) if f.endswith(".jar")) if os.path.isdir(mods_dir(cfg)) else []
    try:
        pinned_all = json.load(open(os.path.join(SETUP, "mods.json"), encoding="utf-8"))
    except (OSError, ValueError):
        pinned_all = {}
    pinned = pinned_all.get("mods", [])
    buddy = [f for f in mods if f.startswith("minecraft-buddy-")]
    ver = None
    for f in buddy:
        m = re.match(r"^minecraft-buddy-(\d[\w.]*)\+mc", f)
        if m:
            ver = m.group(1)
    try:
        hmc_version = open(os.path.join(d, "headlessmc.version"), encoding="utf-8").read().strip()
    except OSError:
        hmc_version = "unknown" if os.path.exists(os.path.join(d, "headlessmc.jar")) else ""
    versions = os.path.join(d, "home", ".minecraft", "versions")
    loader_ok = os.path.isdir(versions) and any(v.startswith(cfg["loader"] + "-loader-") and v.endswith("-" + cfg["minecraft"])
                                                for v in os.listdir(versions))
    return {
        "settings": {k: cfg[k] for k in DEFAULTS},
        "game_dir": d, "headlessmc": hmc_version, "java": shutil.which("java") is not None,
        "minecraft": loader_ok, "instance": os.path.isdir(inst),
        "mods": mods, "mods_missing": [m["file"] for m in pinned if m["file"] not in mods],
        "buddy": ver, "buddy_jars": buddy, "accounts": accounts(cfg),
        "options": os.path.exists(os.path.join(inst, "options.txt")),
        "service": service(), "job": job_state(),
        "docker": {"version": image_version(), "updater": bool(watchtower()[0] and watchtower()[1]), "update": last_image_update(),
                   "release": version_info()},
        # the Minecraft versions the image's mods and jars are for: today one (setup/mods.json)
        "versions": [pinned_all["minecraft"]] if pinned_all.get("minecraft") else [],
    }


GAME_LOG = os.path.join(STATE, "game.log")


def game_log(lines=300):
    """The game's own console, the last lines of game.log (supervise.py writes it), for the panel's Status."""
    try:
        with open(GAME_LOG, "rb") as f:
            f.seek(0, 2)
            f.seek(max(0, f.tell() - 200_000))
            return f.read().decode("utf-8", "replace").splitlines()[-lines:]
    except OSError:
        return []


def image_version():
    try:
        return open(os.path.join(IMAGE, "VERSION"), encoding="utf-8").read().strip()
    except OSError:
        return ""


IMAGE_UPDATE = os.path.join(STATE, "image-update.json")


def last_image_update():
    """What the last Update the image came to: Watchtower's summary (updated, skipped: it could not pull, failed), or an
    error; nothing yet while it runs. When it replaced this container the answer never came: the new version says so."""
    try:
        with open(IMAGE_UPDATE, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def watchtower():
    """Watchtower's address and the token of its API: the token lies in a file of a volume both share (compose.yaml's
    tower-token makes it once, WATCHTOWER_TOKEN_FILE), or is given as WATCHTOWER_TOKEN."""
    token = os.environ.get("WATCHTOWER_TOKEN", "")
    if not token and os.environ.get("WATCHTOWER_TOKEN_FILE"):
        try:
            token = open(os.environ["WATCHTOWER_TOKEN_FILE"], encoding="utf-8").read().strip()
        except OSError:
            token = ""
    return os.environ.get("WATCHTOWER_URL", ""), token


def image_update():
    """Ask Watchtower for the newest image. It pulls it and makes every buddy's container anew when there is one, which
    takes the panel down for a minute: so the question is asked in the background and answered at once. None, or why
    not. A private image is pulled with the machine's own docker login, which Watchtower reads."""
    url, token = watchtower()
    if not url or not token:
        return "no updater: compose.yaml starts Watchtower, and its token in the volume tower"

    asked = time.time()
    write_private(IMAGE_UPDATE, json.dumps({"asked": asked, "version": image_version()}))

    def ask():
        req = urllib.request.Request(url.rstrip("/") + "/v1/update", method="POST", headers={"Authorization": "Bearer " + token})
        out = {"asked": asked, "version": image_version()}
        try:
            out["summary"] = json.loads(urllib.request.urlopen(req, timeout=600).read().decode()).get("summary") or {}
        except Exception as e:          # never reached when it replaces this container: the new version says so
            out["error"] = str(e)[:200]
        out["done"] = time.time()
        write_private(IMAGE_UPDATE, json.dumps(out))
    import threading
    threading.Thread(target=ask, daemon=True).start()
    return None


# ---------- a newer version ----------

# Where the panel looks for a newer version: the newest release of the buddy's repository on GitHub. Asked without a
# login, at most once an hour and in the background, so no page waits for GitHub. BUDDY_UPDATE_CHECK=off never asks.
REPO = os.environ.get("BUDDY_REPO", "MinecraftBuddy/minecraft-buddy")
RELEASE = os.path.join(STATE, "latest-release.json")
RELEASE_EVERY = 3600
_release_asking = []


def version_key(v):
    """0.5.10 after 0.5.9: the numbers of a version, without a +build or -suffix."""
    return tuple(int(p) for p in re.findall(r"\d+", str(v).split("+")[0].split("-")[0]))


def latest_release(refresh=False):
    """What the last look at GitHub found: {"version", "url", "checked"}, or {"error", "checked"}; a new look is started
    in the background when the last one is older than an hour (or when refresh asks for one)."""
    if os.environ.get("BUDDY_UPDATE_CHECK", "").lower() in ("off", "0", "false", "no"):
        return {}
    try:
        with open(RELEASE, encoding="utf-8") as f:
            last = json.load(f)
        if not isinstance(last, dict):
            last = {}
    except (OSError, ValueError):
        last = {}
    if (refresh or time.time() - last.get("checked", 0) > RELEASE_EVERY) and not _release_asking:
        _release_asking.append(True)

        def ask():
            try:
                rel = gh("/repos/%s/releases/latest" % REPO)
                out = {"version": str(rel.get("tag_name", "")).lstrip("v"), "url": rel.get("html_url", ""),
                       "notes": str(rel.get("body") or "")[:500], "published": rel.get("published_at", "")}
            except Exception as e:
                out = {k: last[k] for k in ("version", "url", "notes", "published") if k in last}
                out["error"] = str(e)[:200]
            out["checked"] = time.time()
            try:
                write_private(RELEASE, json.dumps(out))
            finally:
                _release_asking.clear()
        import threading
        threading.Thread(target=ask, daemon=True).start()
    return last


def version_info(refresh=False):
    """The image's version, the newest release, and whether that one is newer: what the panel shows as an update."""
    have, rel = image_version(), latest_release(refresh)
    latest = rel.get("version", "")
    return {"version": have, "latest": latest, "url": rel.get("url", ""), "notes": rel.get("notes", ""),
            "checked": rel.get("checked"), "error": rel.get("error", ""),
            "newer": bool(have and latest and version_key(latest) > version_key(have))}

# ---------- jobs: a process of their own, with a log ----------

def job_state():
    try:
        with open(JOB, encoding="utf-8") as f:
            j = json.load(f)
    except (OSError, ValueError):
        return {}
    if j.get("running"):
        try:
            os.kill(j["pid"], 0)
        except (OSError, KeyError, TypeError):
            j.update(running=False, ok=False, why="the job stopped without finishing")
    try:
        with open(JOBLOG, encoding="utf-8", errors="replace") as f:
            lines = f.read().splitlines()
    except OSError:
        lines = []
    j["log"] = lines[-60:]
    link = next((m.group(1) for l in reversed(lines) for m in [LOGIN_LINK.search(l)] if m), None)
    if j.get("kind") == "login" and j.get("running") and link:
        j["link"] = link
    return j


def write_job(j):
    write_private(JOB, json.dumps(j))


def start_job(kind):
    """Run 'game.py job <kind>' as a process of its own. None, or why not."""
    if kind not in ("install", "login"):
        return "unknown job"
    j = job_state()
    if j.get("running"):
        return "a job is running: " + j.get("kind", "?")
    os.makedirs(STATE, exist_ok=True)
    log = open(JOBLOG, "w", encoding="utf-8")
    p = subprocess.Popen([sys.executable, os.path.abspath(__file__), "job", kind], stdin=subprocess.DEVNULL, stdout=log,
                         stderr=subprocess.STDOUT, start_new_session=True, cwd=HOME)
    log.close()
    write_job({"kind": kind, "pid": p.pid, "started": int(time.time()), "running": True})
    return None


def stop_job():
    j = job_state()
    if j.get("running"):
        try:
            os.killpg(j["pid"], signal.SIGTERM)
        except OSError:
            pass
    return None


def say(line):
    print(line, flush=True)


def run_job(kind):
    j = {"kind": kind, "pid": os.getpid(), "started": int(time.time()), "running": True}
    write_job(j)
    try:
        {"install": do_install, "login": do_login}[kind](settings())
        j.update(ok=True)
        say("Done.")
    except Exception as e:
        j.update(ok=False, why=str(e)[:300])
        say("Stopped: " + str(e))
    j.update(running=False, ended=int(time.time()))
    write_job(j)


# ---------- the work ----------

def do_install(cfg):
    d = game_dir(cfg)
    os.makedirs(d, exist_ok=True)
    if not shutil.which("java"):
        raise RuntimeError("no java here: the image has Temurin 25")
    # 1. HeadlessMC, the version in the settings, checked by the digest GitHub gives its release asset
    have = ""
    try:
        have = open(os.path.join(d, "headlessmc.version"), encoding="utf-8").read().strip()
    except OSError:
        pass
    if have != cfg["headlessmc"] or not os.path.exists(os.path.join(d, "headlessmc.jar")):
        say("HeadlessMC %s…" % cfg["headlessmc"])
        rel = gh("/repos/%s/releases/tags/%s" % (HMC_REPO, cfg["headlessmc"]))
        a = next((a for a in rel.get("assets", []) if a["name"] == "headlessmc.jar"), None)
        if not a:
            raise RuntimeError("HeadlessMC %s has no headlessmc.jar" % cfg["headlessmc"])
        download(a["browser_download_url"], os.path.join(d, "headlessmc.jar"), sha256=(a.get("digest") or "").replace("sha256:", ""))
        with open(os.path.join(d, "headlessmc.version"), "w", encoding="utf-8") as f:
            f.write(cfg["headlessmc"] + "\n")
    else:
        say("HeadlessMC %s is there" % have)
    # 2. Minecraft and its loader (the game itself, its libraries and assets come at the first start)
    say("Minecraft %s with %s…" % (cfg["minecraft"], cfg["loader"]))
    if run_hmc(cfg, "version", "install", cfg["loader"], cfg["minecraft"]) != 0:
        raise RuntimeError("HeadlessMC could not install %s %s" % (cfg["loader"], cfg["minecraft"]))
    # 3. the mods of setup/mods.json, each checked by its sha512; an older file of the same mod goes
    md = mods_dir(cfg)
    os.makedirs(md, exist_ok=True)
    pinned = json.load(open(os.path.join(SETUP, "mods.json"), encoding="utf-8"))
    if pinned.get("minecraft") != cfg["minecraft"]:
        raise RuntimeError("setup/mods.json is for Minecraft %s, not %s" % (pinned.get("minecraft"), cfg["minecraft"]))
    for m in pinned["mods"]:
        dest = os.path.join(md, m["file"])
        for f in os.listdir(md):
            if f != m["file"] and f.startswith(m["prefix"]) and f.endswith(".jar"):
                os.remove(os.path.join(md, f))
                say("  removed the older " + f)
        if os.path.exists(dest) and hashlib.sha512(open(dest, "rb").read()).hexdigest() == m["sha512"]:
            say("  %s is there" % m["file"])
            continue
        say("  %s…" % m["file"])
        download(m["url"], dest, sha512=m["sha512"])
    # 4. the buddy's own jars, the image's (or the mods folder's own: BUDDY_JARS=folder)
    install_buddy(cfg)
    # 5. the game's options, once: his language, no sound, little to draw but ten chunks far (the panel's 3D view), never paused
    opt = os.path.join(instance(cfg), "options.txt")
    if not os.path.exists(opt):
        with open(opt, "w", encoding="utf-8") as f:
            f.write("lang:%s\npauseOnLostFocus:false\nsoundCategory_master:0.0\nrenderDistance:%d\nsimulationDistance:5\n"
                    "maxFps:10\nparticles:2\nonboardAccessibility:false\nskipMultiplayerWarning:true\njoinedFirstServer:true\n"
                    "tutorialStep:none\ntelemetryOptInExtra:false\n" % (cfg["language"], int(cfg.get("render_distance", 10))))
        say("options.txt written (%s)" % cfg["language"])
    # 6. what the mod has to know before its first start: his exchange folder, his name and the server
    acc = accounts(cfg)
    change = {"exchangeDir": expand(cfg, "exchange")}
    if acc:
        change["altName"] = acc[0]
    if cfg["server"]:
        change["servers+"] = host_of(cfg["server"])
    with_game_stopped(lambda: mod_settings(change))
    say("buddy.json: the buddy" + (" " + acc[0] if acc else "") + (", server " + cfg["server"] if cfg["server"] else ""))
    # 7. the panel's pictures and names of the game, from the client jar (Mojang's art, made here, never in git)
    icons(cfg)
    say("Installed. Log the account in, fill in the server, and start the game.")


def image_jars(cfg):
    """The image's own jars of the buddy and the modules of the settings: {name: path}, and what is missing."""
    v = image_version()
    want = ["minecraft-buddy-%s+mc%s.jar" % (v, cfg["minecraft"])] + ["minecraft-buddy-%s-%s+mc%s.jar" % (m, v, cfg["minecraft"]) for m in cfg["modules"]]
    have = {n: os.path.join(IMAGE, "jars", n) for n in want if os.path.exists(os.path.join(IMAGE, "jars", n))}
    return v, have, [n for n in want if n not in have]


def own_jars():
    """BUDDY_JARS=folder (in compose.yaml): the buddy's jars are what lies in the mods folder, put there by hand or by a
    script, and the image's are not copied in: for a machine where new versions are tried before they are released."""
    return os.environ.get("BUDDY_JARS") == "folder"


def install_buddy(cfg):
    if own_jars():
        say("  the buddy's jars: the mods folder's own (BUDDY_JARS=folder)")
        return status()["buddy"] if os.path.isdir(mods_dir(cfg)) else ""
    v, jars, missing = image_jars(cfg)
    if missing:
        raise RuntimeError("the image %s lacks %s" % (v, ", ".join(missing)))
    md = mods_dir(cfg)
    os.makedirs(md, exist_ok=True)
    for name, src in jars.items():
        dest = os.path.join(md, name)
        if os.path.exists(dest) and open(dest, "rb").read() == open(src, "rb").read():
            continue
        shutil.copyfile(src, dest + ".part")
        os.replace(dest + ".part", dest)
        say("  %s" % name)
    for f in os.listdir(md):
        if f.startswith(("minecraft-buddy-", "afkhelper-")) and f.endswith(".jar") and f not in jars:
            os.remove(os.path.join(md, f))
            say("  removed the older " + f)
    retire_mods(md)
    return v
def retire_mods(md):
    """The mods an earlier version installed and the buddy no longer needs ("retired" in setup/mods.json): out of the
    mods folder, so that they stop loading."""
    try:
        retired = tuple(json.load(open(os.path.join(SETUP, "mods.json"), encoding="utf-8")).get("retired", []))
    except (OSError, ValueError):
        return
    for f in os.listdir(md) if retired else []:
        if f.startswith(retired) and f.endswith(".jar"):
            os.remove(os.path.join(md, f))
            say("  removed %s: the buddy no longer needs it" % f)


def do_login(cfg):
    if not os.path.exists(os.path.join(game_dir(cfg), "headlessmc.jar")):
        raise RuntimeError("install the game first")
    before = accounts(cfg)
    say("Asking Microsoft for a link: open it, sign in with the account, and wait here.")
    if run_hmc(cfg, "account", "login", timeout=900) != 0:
        raise RuntimeError("the login did not finish")
    after = accounts(cfg)
    new = [a for a in after if a not in before] or after
    if not new:
        raise RuntimeError("no account after the login")
    name = new[0]
    say("Logged in as " + name)
    # his name for the panel and the bridge; the account counts from the next start of the game
    with_game_stopped(lambda: mod_settings({"altName": name}))


def client_jar(cfg):
    """The game's client jar: HeadlessMC 3 downloads it at the first start and keeps it as the loader version's jar
    (versions/fabric-loader-<v>-26.3/fabric-loader-<v>-26.3.jar, seen with RC8), or as versions/26.3/26.3.jar."""
    versions = os.path.join(game_dir(cfg), "home", ".minecraft", "versions")
    found = [os.path.join(versions, cfg["minecraft"], cfg["minecraft"] + ".jar")]
    if os.path.isdir(versions):
        found += [os.path.join(versions, v, v + ".jar") for v in sorted(os.listdir(versions))
                  if v.startswith(cfg["loader"] + "-loader-") and v.endswith("-" + cfg["minecraft"])]
    return next((j for j in found if os.path.exists(j) and os.path.getsize(j) > 10_000_000), None)


def ensure_icons():
    """The panel's pictures, made once the client jar is there: tried again at most once an hour. Made again when the
    health bar's picture is missing, which is how pictures of before 0.4.165 look (make-icons.py named them in Dutch), or
    a banner's, which make-icons.py draws since 1 Nov 2026 (with the shield and the heads), or an air bubble's (0.4.171)."""
    cfg = settings()
    if all(os.path.exists(os.path.join(PANEL_DATA, "icons", n)) for n in ("_heart_full.png", "white_banner.png", "_air_full.png")):
        return
    mark = os.path.join(STATE, "icons-tried")
    if not client_jar(cfg) or job_state().get("running") or (os.path.exists(mark) and time.time() - os.path.getmtime(mark) < 3600):
        return
    os.makedirs(STATE, exist_ok=True)
    open(mark, "w").close()
    subprocess.Popen([sys.executable, os.path.abspath(__file__), "icons"], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                     stderr=subprocess.DEVNULL, start_new_session=True, cwd=HOME)


def icons(cfg):
    """make-assets.py (names, effect icons) and make-icons.py (item icons, needs Pillow and numpy) from the client jar."""
    jar = client_jar(cfg)
    if not jar:
        say("The panel's pictures wait for the game's first start (it downloads the client jar)")
        return
    for script, out in (("make-assets.py", "assets"), ("make-icons.py", "icons")):
        p = os.path.join(HERE, script)
        if not os.path.exists(p):
            continue
        r = subprocess.run([sys.executable, p, jar, os.path.join(PANEL_DATA, out)], capture_output=True, text=True, timeout=600)
        say(("  %s: made" % script) if r.returncode == 0 else ("  %s did not run: %s" % (script, (r.stderr or r.stdout).strip()[-200:])))


# ---------- the shell ----------

def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("command", choices=["status", "install", "login", "start", "stop", "restart", "job", "icons", "set", "server",
                                        "path"])
    ap.add_argument("kind", nargs="*")
    a = ap.parse_args()
    if a.command == "set":
        body = {}
        for kv in a.kind:
            k, _, v = kv.partition("=")
            body[k] = [m for m in v.split(",") if m] if k == "modules" else (v.lower() in ("1", "true", "yes", "on")) if k == "offline" else v
        err = set_settings(body)
        sys.exit(err) if err else print("ok")
        return
    if a.command == "server":
        if len(a.kind) != 1:
            sys.exit("game.py server ADDRESS")
        was = service().get("active") == "active"
        err = set_settings({"server": a.kind[0]})      # restarts the game when it ran
        if err:
            sys.exit(err)
        if not was:
            code, out = control("start")
            if code:
                sys.exit(out)
        print("ok: " + a.kind[0])
        return
    if a.command == "path":
        cfg = settings()
        print({"mods": mods_dir, "game": game_dir, "instance": instance}[(a.kind or ["game"])[0]](cfg))
        return
    a.kind = a.kind[0] if a.kind else None
    if a.command == "job":
        return run_job(a.kind)
    if a.command == "status":
        s = status()
        s["job"].pop("log", None)
        print(json.dumps(s, indent=2))
        return
    if a.command == "icons":
        return icons(settings())
    if a.command in ("start", "stop", "restart"):
        code, out = control(a.command)
        print(out or "ok")
        sys.exit(code)
    # install and login in this shell, with their lines here
    run_job(a.command)


if __name__ == "__main__":
    main()
