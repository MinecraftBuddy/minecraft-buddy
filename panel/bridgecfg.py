"""The Telegram bridge's settings from the panel (Settings, Telegram).

The whole Telegram set-up is done from the panel, so that a buddy can be set up from nothing through the web page.

The window is one bot and one chat (the second bot for private messages of the IRC days is gone; the one bot answers
them too): what the buddy sees in the game's chat comes in, and a line typed there the buddy says; nothing depends on
IRC any more. The bridge (bridge/chat2telegram.py) keeps its settings in telegram.json and what it does in bridge-status.json beside
it; it starts itself anew within a few seconds of a change of the settings. The panel runs as the same user, so it reads
and writes both files itself. A token or password never goes back to the page: the page learns only whether one is set,
and a field left empty keeps what is there. The file is written whole, readable by its user alone, with the version
before it beside it (.previous). The form of the IRC days is read with its window kept and written in the new form at
the first save (normalize, as the bridge does); anything else added by hand is kept. "enabled": false switches the
bridge off (the panel's main switch); the paths are
not on the page any more (in a container they are the container's own): what a file has is kept.
"""
import http.client
import json
import os
import re
import threading
import time

TOKEN = re.compile(r"^\d{5,16}:[A-Za-z0-9_-]{30,80}$")
CHAT = re.compile(r"^-?\d{1,20}$")
MCNAME = re.compile(r"^\.?[A-Za-z0-9_]{2,16}$")
WHO = re.compile(r"^[^\x00-\x1f\x7f]{1,64}$")       # a Telegram user id, or a name, as the bridge matched admins before
PATH = re.compile(r"^[/~][^\x00-\x1f\x7f]{0,299}$")
PATH_KEYS = ("exchange", "schematics", "status_site", "since")
# what the bridge can leave out of the chat (its SHOW): players' chat, private messages, joins and leaves, other server
# messages, the buddy's own news
SHOW_KEYS = ("chat", "pm", "joins", "server", "buddy")
# a status older than this is a bridge that is not running: it writes one at least once a minute
DOWN_AFTER = 150


def normalize(cfg):
    """As the bridge reads its settings: the window of the IRC days (irc_bot_token, irc_chat_id) becomes the window, and
    what only IRC, the old relay and the second bot for private messages needed goes."""
    c = dict(cfg)
    if c.get("irc_bot_token"):
        c["token"], c["chat_id"] = c["irc_bot_token"], c.get("irc_chat_id") or c.get("chat_id", "")
    for k in ("irc", "irc_bot_token", "irc_chat_id", "relay_keys", "relay_port", "mode", "pm_token"):
        c.pop(k, None)
    return c


class Bridge:
    def __init__(self, path):
        self.path = os.path.abspath(os.path.expanduser(path))
        self.status_path = os.path.join(os.path.dirname(self.path), "bridge-status.json")
        self.lock = threading.Lock()

    def load(self):
        """The settings as they are; {} when there is no file. A file that is no JSON object raises ValueError."""
        try:
            with open(self.path, encoding="utf-8") as f:
                cfg = json.load(f)
        except FileNotFoundError:
            return {}
        if not isinstance(cfg, dict):
            raise ValueError("no JSON object")
        return cfg

    def status(self):
        try:
            with open(self.status_path, encoding="utf-8") as f:
                st = json.load(f)
        except (OSError, ValueError):
            return {"state": "never", "text": "the bridge has not run with these settings yet"}
        st["age"] = max(0, int(time.time() - st.get("at", 0)))
        if st["age"] > DOWN_AFTER and st.get("state") != "unconfigured":
            st["down"] = True
        st.pop("config", None)
        return st

    def view(self):
        """What the page may know: every value but the secrets, of which only whether they are set."""
        try:
            cfg = self.load()
            broken = None
        except (OSError, ValueError) as e:
            cfg, broken = {}, "telegram.json cannot be read (" + str(e)[:120] + "): saving here would overwrite it"
        cfg = normalize(cfg)
        admins = cfg.get("admins") if isinstance(cfg.get("admins"), dict) else {}
        paths = cfg.get("paths") if isinstance(cfg.get("paths"), dict) else {}
        return {
            "file": os.path.exists(self.path), "broken": broken,
            "enabled": cfg.get("enabled") is not False,
            "token": bool(cfg.get("token")), "chat_id": str(cfg.get("chat_id") or ""),
            "admins": [{"who": str(k), "name": str(v)} for k, v in admins.items()],
            "paths": {k: str(paths[k]) for k in PATH_KEYS if isinstance(paths.get(k), str)},
            # what comes into the chat: on unless set off (the bridge's SHOW; !show there changes it too)
            "show": {k: (cfg.get("show") or {}).get(k, True) is not False for k in SHOW_KEYS} if isinstance(cfg.get("show", {}), dict) else {k: True for k in SHOW_KEYS},
            "status": self.status(),
        }

    def save(self, body):
        """Merge what the page sends into the settings. Returns None, or what is wrong."""
        with self.lock:
            try:
                cfg = self.load()
            except (OSError, ValueError) as e:
                return "telegram.json cannot be read (" + str(e)[:120] + "); put it right by hand first"
            new = normalize(json.loads(json.dumps(cfg)))
            err = self.merge(new, body)
            if err:
                return err
            if new == cfg:
                return None
            data = json.dumps(new, ensure_ascii=False, indent=2) + "\n"
            os.makedirs(os.path.dirname(self.path), exist_ok=True)
            if os.path.exists(self.path):
                self.write(self.path + ".previous", open(self.path, "rb").read())
            self.write(self.path, data.encode("utf-8"))
            return None

    @staticmethod
    def write(path, data):
        tmp = path + ".tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)

    @staticmethod
    def merge(cfg, b):
        if not isinstance(b, dict):
            return "no settings"

        def secret(holder, key, value, rule, what):
            # an empty field keeps what is there; "clear" in the body takes it away
            if value is None or value == "":
                return None
            if not isinstance(value, str) or not rule(value.strip()):
                return what + " is not valid"
            holder[key] = value.strip()
            return None

        def chat(key, value):
            if value is None:
                return None
            value = str(value).strip()
            if not value:
                cfg.pop(key, None)
                return None
            if not CHAT.match(value):
                return "a chat is a number, like -1001234567890 for a group"
            cfg[key] = value
            return None

        for e in (secret(cfg, "token", b.get("token"), TOKEN.match, "The bot token"),
                  chat("chat_id", b.get("chat_id"))):
            if e:
                return e

        if "enabled" in b:
            if not isinstance(b["enabled"], bool):
                return "enabled is on or off"
            if b["enabled"]:
                cfg.pop("enabled", None)
            else:
                cfg["enabled"] = False

        if "show" in b:
            sh = b["show"]
            if not isinstance(sh, dict) or any(k not in SHOW_KEYS or not isinstance(v, bool) for k, v in sh.items()):
                return "show: on or off for " + ", ".join(SHOW_KEYS)
            cfg["show"] = dict(cfg.get("show") if isinstance(cfg.get("show"), dict) else {}, **sh)

        if "admins" in b:
            rows = b["admins"]
            if not isinstance(rows, list) or len(rows) > 20:
                return "at most twenty Telegram users"
            admins = {}
            for r in rows:
                who = str((r or {}).get("who", "")).strip() if isinstance(r, dict) else ""
                name = str((r or {}).get("name", "")).strip() if isinstance(r, dict) else ""
                if not who and not name:
                    continue
                if not WHO.match(who):
                    return "an admin needs a Telegram user id (or name)"
                # the Minecraft name may be empty: such an admin chats and gives commands, and "to me" means nobody
                if name and not MCNAME.match(name):
                    return "the Minecraft name of " + who + " is not a player's name"
                if who.lower() in (k.lower() for k in admins):
                    return who + " is in the list twice"
                admins[who] = name
            cfg["admins"] = admins

        if "paths" in b:
            p = b["paths"]
            if not isinstance(p, dict):
                return "paths: no settings"
            paths = cfg.get("paths") if isinstance(cfg.get("paths"), dict) else {}
            for k in PATH_KEYS:
                if k in p:
                    v = str(p.get(k) or "").strip()
                    if not v:
                        paths.pop(k, None)
                    elif not PATH.match(v):
                        return "the path for " + k + " starts with / or ~"
                    else:
                        paths[k] = v
            if paths:
                cfg["paths"] = paths
            else:
                cfg.pop("paths", None)

        for c in b.get("clear") or []:
            if c == "token":
                cfg.pop(c, None)
        return None

    def test(self, send):
        """Ask Telegram who the bot is (Check the token), and with send say a line in the chat (Send a test line). Never
        getUpdates: the bridge reads the bot's messages, and Telegram lets one program at a time do that."""
        try:
            cfg = self.load()
        except (OSError, ValueError) as e:
            return {"error": "telegram.json cannot be read: " + str(e)[:120]}
        cfg = normalize(cfg)
        main_token, chat = cfg.get("token"), str(cfg.get("chat_id") or "")
        out = {"bots": {}}
        if main_token:
            out["bots"]["main"] = call(main_token, "getMe")
        if send:
            if not main_token or not chat:
                out["sent"] = {"ok": False, "text": "no bot or no chat to send it to"}
            else:
                out["sent"] = call(main_token, "sendMessage", {"chat_id": chat, "text": "✅ Test from the panel: the game chat shows up here."})
        return out


def call(token, method, params=None):
    """One call to the Bot API. What comes back names the bot or says what Telegram objects to, never the token."""
    try:
        conn = http.client.HTTPSConnection("api.telegram.org", timeout=15)
        body = json.dumps(params or {})
        conn.request("POST", "/bot" + token + "/" + method, body, {"Content-Type": "application/json"})
        r = conn.getresponse()
        raw = r.read().decode(errors="replace")
        conn.close()
        try:
            j = json.loads(raw)
        except ValueError:
            j = {}
        if r.status == 200 and j.get("ok"):
            res = j.get("result") or {}
            if method == "getMe":
                return {"ok": True, "username": res.get("username", ""), "name": res.get("first_name", "")}
            return {"ok": True}
        if r.status in (401, 404):
            return {"ok": False, "text": "Telegram does not know this token"}
        return {"ok": False, "text": str(j.get("description") or r.status)[:200]}
    except Exception as e:
        return {"ok": False, "text": str(e)[:200]}
