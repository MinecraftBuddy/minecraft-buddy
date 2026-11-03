#!/usr/bin/env python3
"""The container's own process (Dockerfile: tini, then this): the panel, the Telegram bridge and the game.

One image is easy to deploy: after a deploy the web interface comes first, with a wizard that logs in with the account,
then adds servers and server profiles if wanted, and then joins and stays joined.

The panel and the bridge always run, and start again a moment after they stop. The game runs while the panel wants it:
panel/game.py writes game-want.json in the state folder ({"run": bool, "restart": n}) and this writes what it does to
game-runs.json beside it, every few seconds, as systemd did for the user unit. A game that stops by itself starts again
after twenty seconds, as Restart=always did; before every start the image's jars of the buddy go into the mods folder,
so a new image is a new buddy. The game's lines go to game.log in the state folder (the one before as game.log.1), not to the
container's log. A stop of the container stops the game first, the whole process group (HeadlessMC and the game it
started), and waits for it.

Before the owner has a password the setup code is printed in the container's log: docker compose logs <buddy>.

With the argument compose it prints the machine's compose.yaml instead, the one file that runs every buddy of a machine
with Caddy and Watchtower: docker run --rm ghcr.io/minecraftbuddy/minecraft-buddy compose > compose.yaml
"""
import json
import os
import shutil
import signal
import subprocess
import sys
import time

IMAGE = os.environ.get("BUDDY_IMAGE_DIR", "/opt/buddy")
sys.path.insert(0, os.path.join(IMAGE, "panel"))
import game  # noqa: E402
import owner  # noqa: E402

DATA = os.path.expanduser("~")
CONF = os.path.join(DATA, ".config", "buddy-panel")
GAME_LOG = os.path.join(game.STATE, "game.log")
STOPPING = [False]


def say(line):
    print("[buddy] " + line, flush=True)


def panel_argv():
    # BUDDY_LISTEN: 127.0.0.1 where the container shares the machine's network (network_mode: host), all addresses otherwise
    a = [sys.executable, os.path.join(IMAGE, "panel", "server.py"), "--host", os.environ.get("BUDDY_LISTEN", "0.0.0.0"),
         "--port", os.environ.get("BUDDY_PANEL_PORT", "8080"),
         "--allow", os.path.join(CONF, "allow.txt"), "--owner", os.path.join(CONF, "owner.json"),
         "--history", os.path.join(DATA, "panel", "history.jsonl"), "--bridge-config", os.path.join(DATA, "telegram.json")]
    if os.environ.get("BUDDY_PROXY_HOST"):
        a += ["--proxy-host", os.environ["BUDDY_PROXY_HOST"]]
        # the SHA-256 of the secret Caddy sends (compose.yaml: proxy-secret beside the Watchtower token)
        if os.environ.get("BUDDY_PROXY_SECRET_FILE"):
            a += ["--proxy-secret", os.environ["BUDDY_PROXY_SECRET_FILE"]]
    return a


class Child:
    """A program that always runs: started again after a pause that grows while it keeps stopping at once."""

    def __init__(self, name, argv, env=None):
        self.name, self.argv, self.env = name, argv, env
        self.p, self.started, self.pause, self.next = None, 0.0, 2.0, 0.0

    def tick(self):
        if self.p and self.p.poll() is not None:
            say("%s stopped (exit code %s)" % (self.name, self.p.returncode))
            self.pause = 2.0 if time.time() - self.started > 60 else min(self.pause * 2, 60.0)
            self.next, self.p = time.time() + self.pause, None
        if not self.p and not STOPPING[0] and time.time() >= self.next:
            self.started = time.time()
            self.p = subprocess.Popen(self.argv, env=self.env, stdin=subprocess.DEVNULL, cwd=DATA)
            say("%s started (pid %d)" % (self.name, self.p.pid))

    def stop(self):
        if self.p and self.p.poll() is None:
            self.p.terminate()


def login_refused(path):
    """What the end of the game's log says of HeadlessMC's login at Minecraft's services: "401", "429" or ""."""
    try:
        with open(path, "rb") as f:
            f.seek(0, 2)
            f.seek(max(0, f.tell() - 4000))
            tail = f.read().decode("utf-8", "replace")
    except OSError:
        return ""
    if "/launcher/login" not in tail:
        return ""
    if "401" in tail or "UNAUTHORIZED" in tail:
        return "401"
    return "429" if "429" in tail else ""


def expire_game_tokens(cfg):
    """After the container is made anew (an update of the image), Minecraft's services refuse HeadlessMC's cached login
    with 401 at every start, while the Microsoft and Xbox tokens it was made from are still good. Marking the last two
    as expired makes HeadlessMC fetch them anew from those, without a new Microsoft login. True when it changed one."""
    path = os.path.join(game.game_dir(cfg), "data", "headlessmc", ".auth", "default", ".accounts.json")
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return False
    changed = False
    for account in (data.get("accounts") or {}).values():
        for key in ("javaXstsToken", "minecraftToken"):
            token = account.get(key) if isinstance(account, dict) else None
            if isinstance(token, dict) and token.get("expireTimeMs") != 0:
                token["expireTimeMs"] = 0
                changed = True
    if changed:
        shutil.copy2(path, path + ".before-refresh")
        os.chmod(path + ".before-refresh", 0o600)
        game.write_private(path, json.dumps(data))
    return changed


class Game:
    """The game while it is wanted, in a process group of its own, as the user unit was."""

    def __init__(self):
        self.p, self.log, self.state, self.why = None, None, "inactive", ""
        self.since, self.started, self.restarts, self.next = "", 0, 0, 0.0
        self.tokens_at = 0.0
        self.seen_restart = game.want().get("restart", 0)

    def write(self):
        game.write_private(game.RUNS, json.dumps({"state": self.state, "why": self.why, "since": self.since, "started": self.started,
                                                  "restarts": self.restarts, "pid": self.p.pid if self.p else None, "at": time.time()}))

    def start(self):
        cfg = game.settings()
        if not os.path.exists(os.path.join(game.game_dir(cfg), "headlessmc.jar")):
            self.state, self.why = "failed", "not installed"
            return
        try:
            game.install_buddy(cfg)
        except Exception as e:
            self.state, self.why = "failed", str(e)[:200]
            say("Minecraft Buddy's jars not installed: " + self.why)
            return
        os.makedirs(game.STATE, exist_ok=True)
        if os.path.exists(GAME_LOG):
            os.replace(GAME_LOG, GAME_LOG + ".1")
        self.log = open(GAME_LOG, "w", encoding="utf-8")
        self.p = subprocess.Popen(["bash", os.path.join(IMAGE, "setup", "buddy-run.sh")], stdin=subprocess.DEVNULL, stdout=self.log,
                                  stderr=subprocess.STDOUT, cwd=DATA, start_new_session=True)
        self.started = time.time()
        self.since = time.strftime("%a %Y-%m-%d %H:%M:%S %Z")
        self.state, self.why = "activating", ""
        say("the game started (pid %d); its output goes to %s" % (self.p.pid, GAME_LOG))

    def stop(self, wait=60):
        if not self.p:
            return
        try:
            os.killpg(self.p.pid, signal.SIGTERM)
        except OSError:
            pass
        end = time.time() + wait
        while time.time() < end and self.p.poll() is None:
            time.sleep(0.5)
        if self.p.poll() is None:
            try:
                os.killpg(self.p.pid, signal.SIGKILL)
            except OSError:
                pass
            self.p.wait()
        say("the game stopped")
        self.done()

    def done(self):
        if self.log:
            self.log.close()
        self.p, self.log = None, None

    def login_wait(self):
        """Seconds until the next start. A refused login (401) gets its cached tokens renewed once an hour at most; a
        start that is told to slow down (429) waits five minutes, where every twenty seconds would keep it refused."""
        refused = login_refused(GAME_LOG)
        if refused == "401" and time.time() - self.tokens_at > 3600:
            self.tokens_at = time.time()
            if expire_game_tokens(game.settings()):
                say("Minecraft's services refused the saved login (401): the next start renews it")
        elif refused == "429":
            say("Minecraft's services ask to slow down (429): next start in five minutes")
            return 300
        return 20

    def tick(self):
        w = game.want()
        if self.p and self.p.poll() is not None:
            code = self.p.returncode
            self.done()
            say("the game stopped by itself (exit code %s)" % code)
            if w.get("run") and not STOPPING[0]:
                self.state, self.why, self.next = "activating", "auto-restart", time.time() + self.login_wait()
                self.restarts += 1
            else:
                self.state, self.why = "inactive" if code == 0 else "failed", ""
        if self.p and self.state == "activating" and time.time() - self.started > 5:
            self.state = "active"
        if w.get("restart", 0) != self.seen_restart:
            self.seen_restart = w.get("restart", 0)
            if self.p:
                self.stop()
            self.next = 0.0
        if STOPPING[0]:
            return
        if w.get("run") and not self.p and time.time() >= self.next:
            self.start()
            if not self.p:
                self.next = time.time() + 20
        elif not w.get("run") and self.p:
            self.stop()
            self.state, self.why = "inactive", ""
        elif not w.get("run") and not self.p and self.state == "activating":
            self.state, self.why = "inactive", ""


def main():
    os.makedirs(CONF, mode=0o700, exist_ok=True)
    os.makedirs(os.path.join(DATA, "panel"), exist_ok=True)
    os.makedirs(game.STATE, exist_ok=True)
    o = owner.Owner(os.path.join(CONF, "owner.json"))
    code = o.setup_code()
    if code:
        bar = "=" * 64
        say(bar)
        say("No password yet. Open the panel%s and enter this setup code with a new password:" % (" (" + os.environ["BUDDY_URL"] + ")" if os.environ.get("BUDDY_URL") else ""))
        say("")
        say("    " + code)
        say("")
        say(bar)
    say("Minecraft Buddy %s" % (game.image_version() or "?") + (", at " + os.environ["BUDDY_URL"] if os.environ.get("BUDDY_URL") else ""))
    if os.environ.get("BUDDY_PROXY_HOST") and not os.environ.get("BUDDY_PROXY_SECRET_FILE"):
        say("Caddy sends no secret (BUDDY_PROXY_SECRET_FILE), so any program on this machine can pose as an allowed address. "
            "Copy the proxy-secret lines of the image's compose.yaml (docker run --rm IMAGE compose) into yours.")
    env = dict(os.environ, BUDDY_BRIDGE_CONFIG=os.path.join(DATA, "telegram.json"))
    kids = [Child("panel", panel_argv()), Child("bridge", [sys.executable, os.path.join(IMAGE, "bridge", "chat2telegram.py")], env)]
    g = Game()

    def stop(signum, frame):
        STOPPING[0] = True
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    last = 0.0
    while not STOPPING[0]:
        for k in kids:
            k.tick()
        g.tick()
        if time.time() - last >= 2:
            g.write()
            last = time.time()
        time.sleep(0.5)
    say("stopping")
    g.stop()
    g.state = "inactive"
    g.write()
    for k in kids:
        k.stop()
    for k in kids:
        if k.p:
            try:
                k.p.wait(15)
            except subprocess.TimeoutExpired:
                k.p.kill()


def compose():
    """The machine's compose.yaml, to start from: docker run --rm ghcr.io/minecraftbuddy/minecraft-buddy compose > compose.yaml"""
    sys.stdout.write(open(os.path.join(IMAGE, "docker", "compose.yaml"), encoding="utf-8").read())


if __name__ == "__main__":
    if sys.argv[1:] == ["compose"]:
        compose()
    else:
        main()
