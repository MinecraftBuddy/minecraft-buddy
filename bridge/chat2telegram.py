#!/usr/bin/env python3
"""The buddy's chat in Telegram: what they see in the game's chat comes into one Telegram chat, a line typed there
without a ! they say in the game, and a line with a ! is a command for them (commands.jsonl; the mod carries it out).

Settings in telegram.json in the bridge's own folder (beside this script; another file with --config <file> or the
environment variable BUDDY_BRIDGE_CONFIG). The panel writes it (Settings, Telegram): the bridge looks at the file
every two seconds and starts itself anew when it changes, so nothing has to be restarted by hand. What it does is in
bridge-status.json beside the settings, which the panel shows. Without settings or a bot token it waits; with a token but no
chat it only notes who writes to the bot, so that the panel can offer that chat to choose.

{"token": "...", "chat_id": "...", "enabled": false, "admins": {"<telegram user id, or name>": "<minecraft name>"},
 "paths": {"exchange": "~/Development/minecraft-afk", "schematics": "...", "status_site": "...", "since": "..."}}
token and chat_id are the window: one bot, one chat. A second bot for private messages (pm_token), and the script that
sent files through it, are gone. "enabled": false is the panel's main switch: the bridge then waits and says nothing.
An admin is best named by the user id (the panel offers the ids of who wrote to the bot). A @username also counts; a
first and last name, which anyone can give themselves, only in a private chat with the bot, where nobody else writes
(in a group anyone could take an admin's name). "paths" is optional; what is left out is the default place.
"""
import http.client, json, os, queue, re, sys, threading, time, urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))


def config_path():
    a = sys.argv[1:]
    if "--config" in a and a.index("--config") + 1 < len(a):
        return a[a.index("--config") + 1]
    return os.environ.get("BUDDY_BRIDGE_CONFIG") or os.path.join(HERE, "telegram.json")


CFG_PATH = os.path.abspath(os.path.expanduser(config_path()))
# what belongs to the bridge lies beside its settings: its status, and who wrote to the bot
CFG_DIR = os.path.dirname(CFG_PATH)
STATUS_PATH = os.path.join(CFG_DIR, "bridge-status.json")
STATUS = {"pid": os.getpid(), "started": int(time.time()), "config": CFG_PATH}
STATUS_LOCK = threading.Lock()


def bridge_status(**kw):
    """What the bridge does, for the panel: written whole (a new file renamed over the old), readable by its user alone."""
    with STATUS_LOCK:
        STATUS.update(kw)
        STATUS["at"] = int(time.time())
        data = json.dumps(STATUS, ensure_ascii=False)
        tmp = STATUS_PATH + ".tmp"
        try:
            fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(data)
            os.replace(tmp, STATUS_PATH)
        except OSError as e:
            print("status:", e, flush=True)


def config_stamp():
    """The settings and the script itself: a change of either (the panel's save, the panel's update) starts it anew. What
    comes into the chat ("show") is read as it changes, without a restart (!show changes it from Telegram)."""
    out = []
    try:
        with open(CFG_PATH, encoding="utf-8") as f:
            c = json.load(f)
        c.pop("show", None) if isinstance(c, dict) else None
        out.append(json.dumps(c, sort_keys=True))
    except (OSError, ValueError):
        out.append(None)
    for p in (os.path.abspath(__file__),):
        try:
            st = os.stat(p)
            out.append((st.st_ino, st.st_mtime_ns, st.st_size))
        except OSError:
            out.append(None)
    return tuple(out)


STAMP = config_stamp()


def restart_on_change():
    """The panel writes the settings whole; within two seconds the bridge starts itself anew to take them. Never
    returns. exec keeps the process, so systemd sees no restart."""
    while True:
        time.sleep(2)
        if time.time() - STATUS.get("at", 0) > 60:
            bridge_status()                # a heartbeat: the panel takes a status older than two minutes for a bridge that is down
        if config_stamp() != STAMP:
            time.sleep(0.5)                # a hand edit may still be writing
            print("the settings or the script changed: starting anew", flush=True)
            bridge_status(state="restarting")
            os.execv(sys.executable, [sys.executable, os.path.abspath(__file__)] + sys.argv[1:])


def normalize(cfg):
    """The settings as the panel writes them: the window is token and chat_id. The form of the IRC days (16 to 31 Oct
    2026: irc_bot_token and irc_chat_id the window) is read with that window kept, so that the chat stays the one it was;
    a second bot for private messages (pm_token) is no longer used."""
    c = dict(cfg)
    if c.get("irc_bot_token"):
        c["token"], c["chat_id"] = c["irc_bot_token"], c.get("irc_chat_id") or c.get("chat_id", "")
    for k in ("irc", "irc_bot_token", "irc_chat_id", "relay_keys", "relay_port", "mode", "pm_token"):
        c.pop(k, None)
    return c


def read_config():
    """The settings, or None and why the bridge cannot start with them."""
    try:
        with open(CFG_PATH, encoding="utf-8") as f:
            cfg = json.load(f)
    except FileNotFoundError:
        return None, "no settings yet: fill in Telegram in the panel"
    except (OSError, ValueError) as e:
        return None, f"the settings cannot be read: {e}"
    if not isinstance(cfg, dict):
        return None, "the settings are no JSON object"
    cfg = normalize(cfg)
    if cfg.get("enabled") is False:
        return None, "off"
    if not cfg.get("token"):
        return None, "no bot token"
    if not isinstance(cfg.get("admins", {}), dict):
        return None, "admins is no list of names"
    return cfg, None


CFG, WHY = read_config()
if CFG is None:
    print("waiting for settings:", WHY, flush=True)
    bridge_status(state="off" if WHY == "off" else "unconfigured", reason=WHY)
    restart_on_change()

PATHS = CFG.get("paths") if isinstance(CFG.get("paths"), dict) else {}
EXCHANGE = os.path.expanduser(PATHS.get("exchange") or "~/Development/minecraft-afk")
# the files of the mod's exchange folder the bridge reads and writes: one table, so a rename in the mod is one edit here
FILES = {"events": "events.jsonl", "commands": "commands.jsonl", "settings": "buddy.json", "yield": "yield.jsonl"}
# the buddy's events, in the protocol's English names (the bridge read a Dutch copy, events-nl.jsonl, until 0.4.165)
EVENTS = os.path.join(EXCHANGE, FILES["events"])
# the window everything is said in
MAIN_TOKEN = CFG["token"]
MAIN_CHAT = str(CFG.get("chat_id") or "")
MAIN_PAD = f"/bot{MAIN_TOKEN}/sendMessage"
OUTBOX = queue.Queue()
COMMANDS = os.path.join(EXCHANGE, FILES["commands"])
# telegram user id or name (small letters) -> Minecraft name; a command "to me" (!tpa without a name) takes that name
ADMINS = {str(k).lower(): v for k, v in (CFG.get("admins") or {}).items()}

def send(text):
    """Puts a message in the queue for the window; the sender thread does the rest (reading must never wait for Telegram)."""
    OUTBOX.put(text)


def sender():
    """One open HTTPS connection to Telegram: a new connection per message cost about 0.3 s more."""
    conn = None
    held = None
    loose = []    # the parts of a bundle Telegram refused, sent again one by one
    while True:
        if loose:
            parts = [loose.pop(0)]
        else:
            parts = [held if held is not None else OUTBOX.get()]
            held = None
            # what is already waiting goes along in the same message: in a busy chat Telegram's limit (about one
            # a second in a private chat, twenty a minute in a group) would otherwise let the queue run away
            while sum(len(d) + 1 for d in parts) < 3800:
                try:
                    more = OUTBOX.get_nowait()
                except queue.Empty:
                    break
                if sum(len(d) + 1 for d in parts) + len(more) > 3800:
                    held = more
                    break
                parts.append(more)
        text = "\n".join(parts)
        payload = urllib.parse.urlencode({
            "chat_id": MAIN_CHAT, "text": text,
            "disable_web_page_preview": "true", "parse_mode": "HTML",
        })
        headers = {"Content-Type": "application/x-www-form-urlencoded"}
        for attempt in range(5):
            try:
                if conn is None:
                    conn = http.client.HTTPSConnection("api.telegram.org", timeout=20)
                conn.request("POST", MAIN_PAD, payload, headers)
                r = conn.getresponse()
                body = r.read().decode(errors="replace")
                if r.status == 200:
                    # the servers' public chat and the buddy's private messages: they stay out of the journal
                    print("→", f"{len(parts)} line(s), {len(text)} characters", flush=True)
                    bridge_status(sent=int(time.time()))
                    break
                if r.status == 429:
                    # Telegram says how long to wait (in a group about twenty messages a minute)
                    try:
                        wait = json.loads(body)["parameters"]["retry_after"] + 1
                    except Exception:
                        wait = 5
                    time.sleep(wait)
                    continue
                print("telegram refused:", r.status, body[:200], flush=True)
                bridge_status(send_error={"at": int(time.time()), "code": r.status, "text": telegram_error(body)})
                if len(parts) > 1:
                    loose = parts + loose      # one bad part must not take the others along
                break                          # a 400 and the like: trying again does not help
            except Exception as e:
                print("telegram error:", e, flush=True)
                try:
                    conn.close()
                except Exception:
                    pass
                conn = None
                time.sleep(2 * (attempt + 1))


def escape(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def telegram_error(body):
    try:
        return str(json.loads(body).get("description") or "")[:200]
    except Exception:
        return body[:200]


def whoami(label, token):
    """The bot's own name for the panel, and whether Telegram takes the token at all."""
    try:
        conn = http.client.HTTPSConnection("api.telegram.org", timeout=20)
        conn.request("GET", f"/bot{token}/getMe")
        r = conn.getresponse()
        body = r.read().decode(errors="replace")
        conn.close()
        if r.status == 200:
            me = json.loads(body).get("result") or {}
            bot = {"ok": True, "username": me.get("username", ""), "name": me.get("first_name", ""), "id": str(me.get("id", ""))}
        else:
            bot = {"ok": False, "code": r.status, "text": "Telegram does not know this token" if r.status in (401, 404) else telegram_error(body)}
    except Exception as e:
        bot = {"ok": False, "text": str(e)[:200]}
    with STATUS_LOCK:
        bots = dict(STATUS.get("bots") or {})
    bots[label] = bot
    bridge_status(bots=bots)


# ---------- commands from Telegram ----------
# A line that starts with "!" is a command for the alt. Anything else an admin says is ordinary talk, which
# the buddy says in the game (buddy_say below).
# What goes to the mod is in the protocol's English (switch, what, state, on/off/finish); the mod reads it in its own
# names (Protocol.in).

CONFIG = os.path.join(EXCHANGE, FILES["settings"])
def game_schematics():
    """The builder's schematics in the game's folder, as the panel's game settings have it (~/buddy-game.json)."""
    try:
        g = {"game_dir": "~/game", "loader": "fabric", "minecraft": "26.3", **json.load(open(os.path.expanduser("~/buddy-game.json")))}
    except (OSError, ValueError):
        return "~/game/home/.minecraft/fabric-26.3/schematics"   # the game's default folder
    return os.path.join(g["game_dir"], "home", ".minecraft", g["loader"] + "-" + g["minecraft"], "schematics")


SCHEMATICS = os.path.expanduser(PATHS.get("schematics") or game_schematics())


def alt_name():
    """The buddy's name in the game, as the mod keeps it (altName in buddy.json): nothing of the account stands in here."""
    try:
        return str(json.load(open(CONFIG)).get("altName") or "")
    except (OSError, ValueError):
        return ""

# name -> (how to type it, one line, the full explanation)
DOC = {
    "help": ("!help [command]", "This list, or everything about one command: !help farmraid", ""),
    "status": ("!status", "Where The buddy is, health and food, what the scoreboard says they have, uptimes, and their inventory slot by slot",
               "Shows their position and zone, health, food and how much food they carry, whether AFK mode is on, their money, "
               "tokens, shards, claims, votes, kills and team from the scoreboard, how long "
               "they have been connected to the server, how long the game has been running and how long the VPS has been up. "
               "Below that the inventory with slot numbers; those numbers are what !dump takes."),
    "positions": ("!positions", "Every place The buddy knows: where they are, home, farm spot, raid spot, wood field, shop, zones, placements",
                  "All stored coordinates in one message. They are plain settings, changed with !set (for example !set "
                  "farmAreaX1 120); a tree farm's plots are measured where they stand with the exchange command woodscan."),
    "settings": ("!settings [word]", "All settings at once, or only those with a word in the name (with explanation)",
                 "!settings alone lists every setting with its value, grouped. !settings farm (or raid, wood, build, eat, "
                 "gg, alt, panic ...) lists only the matching ones and says what each one does."),
    "get": ("!get name", "One setting with its explanation", ""),
    "show": ("!show [kind on|off]", "What comes into this chat: players' chat, private messages, joins and leaves, other server messages, the buddy's own news",
             "!show lists the five with whether they are on. !show joins off (or chat, pm, server, buddy; all) switches one. "
             "The server profile tells the kinds apart: its texts chat (a player's line), join and leave (the server's own lines "
             "for it, which then stay out: joins come from the player list), pm and pmOut. The panel has the same switches "
             "(Settings, Telegram)."),
    "set": ("!set name value", "Change a setting: !set buildMaxPerSec 3, !set raidDrink off",
            "Works for on/off, number and text settings; the mod picks the change up within a second, no restart. "
            "Lists (trusted players, food list) cannot be set from here. The shop key, the alt's name and the server "
            "list are locked, and the click rate does not go above 6 per second."),
    "say": ("!say text", "The buddy says it in public chat, as themselves", ""),
    "msg": ("!msg Name text", "The buddy sends a private message", ""),
    "cmd": ("!cmd /command", "The buddy runs any command, e.g. !cmd /spawn",
            "Sent exactly as typed, as if they typed it themselves. Use it for anything that has no command of its own here."),
    "tpa": ("!tpa [Name]", "The buddy asks to teleport to someone; without a name: to you", ""),
    "tpahere": ("!tpahere [Name]", "The buddy asks someone to come to them; without a name: you", ""),
    "tpaccept": ("!tpaccept", "The buddy accepts the teleport request they just got", ""),
    "follow": ("!follow [Name] | !follow stay | !follow go | !follow off | !follow status", "The buddy walks after you, or after that player, and keeps finding a way",
               "The buddy stops two blocks from you and looks at you, sprints to catch up, jumps gaps, climbs ladders, opens doors and "
               "swims, under water too. Out of sight they walk to where they last saw you and wait there, so they follow you through "
               "a portal, and through a teleport (/tpa, your /tpahere, /afk when nobody answers). When you sit on a cushion they sit "
               "on a free one near you. !follow stay makes them wait where they are, !follow go follows again; in the game the player they "
               "follow does that by looking at them and crouching twice quickly. Whom they follow changes only by !follow "
               "(you), !follow Name or !follow off, also over a restart of their game."),
    "goto": ("!goto x y z | !goto off | !goto", "The buddy walks to that block, jumping where they have to", ""),
    "parkour": ("!parkour | !parkour off", "The buddy joins the Parkour that players voted open and runs to the finish",
                "Only a game two players voted open (/eventvote parkour), which has no prize: one of the half-hourly draw pays its "
                "winner, and The buddy is faster than the players. With no Parkour open, or one of the draw, they do nothing and say why. "
                "They join, wait in the waiting room, run from the start to the finish, and follow you again when the game is over. "
                "!parkour off stops the run and takes them out of the game."),
    "assist": ("!assist [on|off] | !assist dig|chop|crops|chests|give on|off", "While following, The buddy digs your tunnel along, chops the trees around you, keeps your fields and uses your chests",
               "Tunnel: once The buddy is sure you dig one (2x2 up to 5x5, three slices), they break in the slice you break and behind it, "
               "never in front, and stop three seconds after your last block. Trees: after two logs of yours they chop other grown "
               "trees near you that they can reach from the ground while you keep chopping, and plant a sapling back. Chests: the ones they see you open; "
               "they put what they carry with the same items, fetch food, tools and better armour, and wear their best armour. "
               "Crops: the fields where they see you sow or renew; they harvest along when you do, and by themselves when much of a field "
               "is ripe (not within ten minutes of the last time), and plant again. Giving: after a job, with no chest of yours within "
               "32 blocks, they throw what they do not keep at your feet; what you do not pick up within eight seconds they pick up "
               "again and try once more, three times, then five minutes later. It all goes for the player they follow. "
               "What they lack they say here, at most once in five minutes."),
    "chests": ("!chests | !chests forget | !chests add x y z", "The chests The buddy knows as yours (of the player they follow), and what they saw in them", ""),
    "fields": ("!fields | !fields forget", "The fields The buddy knows as yours (of the player they follow)", ""),
    "forget": ("!forget | !forget chests|fields", "The buddy forgets what they know of yours on this server: chests and fields",
               "Only the server they are on, and only the player they follow. In the game: look at them with empty hands and crouch "
               "five times; The buddy shakes their head and crouches once; crouch twice within a few seconds to confirm, and they nod."),
    "profile": ("!profile", "Which server profile The buddy uses now: its commands, texts and the hop after joining",
                "Profiles are in buddy.json under \"profiles\"; the first one that fits the server's address and commands wins."),
    "guard": ("!guard on|off | !guard pvp on|off | !guard", "While following, The buddy fights whoever hits you, and the hostile mobs you hit",
              "With their best weapon and critical hits. Players only with !guard pvp on (off after every restart). "
              "In the game: a weapon in hand, look at them and crouch twice for on/off; hold it up to the sky and crouch "
              "twice for fighting players on/off."),
    "home": ("!home [name]", "The buddy goes to their home, or to the home with that name", ""),
    "sethome": ("!sethome [name]", "The buddy sets their home where they stand",
                "The plain !home and a home asked by PM take this one."),
    "mod": ("!mod on|off", "Master switch: off means the mod does nothing at all", ""),
    "afk": ("!afk on|off", "AFK mode: eats when hungry, keeps watch, never chats by itself",
            ""),
    "farmmob": ("!farmmob on|off|status", "Mob farm: hits the mobs in front of The buddy",
                "Aims at the mobs and hits them at a human click rate, moves a little now and then, returns to their spot and "
                "takes breaks. The buddy must stand at the farm. With farmUseArea on they only hit above the stored farm spot "
                "(see !positions). Stops by itself when the collect chests are full or someone is testing them. Turning it on turns the raid farm off."),
    "farmraid": ("!farmraid on|off|finish|status", "Raid farm: drinks the ominous bottle, fights the raid, repeats",
                 "The buddy must stand at the raid farm. Drinks a bottle (raidDrink), waits for the raid, hits the raiders where they "
                 "land and follows the waves on the boss bar. finish = complete the raid that is running, then stop without a new bottle. "
                 "Turning it on turns the mob farm off."),
    "farmwood": ("!farmwood on|off|finish|status", "Tree farm: chops, replants, bonemeals, picks up, stores",
                 "Works the scanned wood field (see !positions) and puts the wood in its chests. Stops when the axe is nearly worn. "
                 "finish = finish the round, store everything, then stop."),
    "nanocrop": ("!nanocrop on|off|status", "Plants and harvests the one crop under their crosshair",
             "For a nano farm where a dispenser bonemeals: puts a seed on bare farmland, breaks only a ripe crop, and bonemeals "
             "from their off hand if they hold any. They never turn their view, so aim them first."),
    "cropfarm": ("!cropfarm on|off|status | !cropfarm crop <item>", "Crop field: walks the rows, harvests with a hoe, replants, sells the harvest",
                 "The buddy picks the home “wheat” in /homes (setting wheatHome), puts on their best armour and stores everything except food "
                 "(2 stacks), their best pickaxe, axe and shovel, the hoe they harvest with, scaffolding (2 stacks) and what they plant in the "
                 "chests there. Then they walk the field row by row, floor by floor: a ripe crop is broken and replanted, bare farmland gets "
                 "a seed, a growing crop is left alone. They never wait for the field. They plant one crop and break whatever stands ripe, "
                 "so a field of another crop turns into that one: !cropfarm crop carrot (or potato, wheat_seeds, beetroot_seeds; setting "
                 "wheatCrop), then hand The buddy a few. With a hoe (setting wheatHoe) "
                 "they break with the hoe in their hand, so its Fortune counts, and plant from their off hand. Of several hoes they take the "
                 "one with the most Fortune; put a better one in a chest at the home and they take it the next time they arrive there "
                 "(!cropfarm off, then on). A hoe with wheatHoeMin durability or less goes back into a chest and they fetch another. "
                 "When they see someone else farming on their floor they take another floor. With a full inventory they walk back, craft "
                 "wheat into hay bales and store everything. It stays on over a kick, a crash or a server restart. The hoe and the other floor need "
                 "AFK Helper 0.3.100 or newer; the field corners are in !positions (settings wheatX1, wheatZ1, wheatX2, wheatZ2). "
                 "!cropfarm status: where they are (floor and row), what they plant, what they harvested and planted in the last hour, today and "
                 "in total, and how many crops per second that is while the mode is on. !crop means the same as !cropfarm."),
    "build": ("!build on|off|status", "Builds the Litematica placement nearest to The buddy",
              "Places every block of the placement with real clicks, after clearing what is in the way. Takes material from the "
              "chests, barrels and shulker boxes within 3 blocks of where they stand when you turn it on. Needs a placement first: "
              "see !place. !build status tells how far they are and what is missing."),
    "schematics": ("!schematics", "The schematic files they have",
                   "Send a .litematic file as an attachment in this group and they save it. Put !place x y z (or !place here) "
                   "in the caption to place it straight away."),
    "place": ("!place name x y z | !place name here", "Put a schematic down: the coordinates are its origin block",
              "Makes a Litematica placement of that file with its origin (the corner Litematica calls the origin) on that block; "
              "here = the block The buddy stands on. Part of the name is enough. Then !build on."),
    "placements": ("!placements", "The placements they have loaded, with their corners", ""),
    "unplace": ("!unplace", "Remove all placements", ""),
    "crouch": ("!crouch on|off", "Stay crouched", ""),
    "jump": ("!jump on|off", "Keep jumping", ""),
    "dump": ("!dump [slots] [ground] [all] | !dump stop", "Empty their inventory into chests nearby, or on the ground",
             "!dump puts everything except tools, armour, the compass and food into the nearest chests, barrels and shulker boxes "
             "(within 24 blocks; The buddy walks to them). !dump ground throws it on the ground instead. !dump all includes tools and food. "
             "!dump 2 10 11 empties only those slots, whatever is in them; the numbers are the ones !status shows. "
             "They report back when they are done. !dump stop breaks it off."),
}
SECTIONS = (
    ("Info", ("help", "status", "positions", "settings", "get", "set", "show")),
    ("Chat", ("say", "msg", "cmd")),
    ("Teleport", ("tpa", "tpahere", "tpaccept", "follow", "guard", "assist", "chests", "fields", "forget", "profile", "goto", "parkour", "home", "sethome")),
    ("Modes", ("mod", "afk", "farmmob", "farmraid", "farmwood", "nanocrop", "cropfarm", "crouch", "jump", "dump")),
    ("Building", ("schematics", "place", "placements", "unplace", "build")),
)
STATUS_NOTE = ("<b>status</b> after a farm command (!cropfarm status, !farmmob status, !farmraid status, !farmwood status, "
               "!sell status, !nanocrop status) tells where that farm is and what it brought in the last hour, today and in total. "
               "The numbers come from yield.jsonl, which the mod fills every five minutes and which survives restarts "
               "(AFK Helper 0.3.62 or newer). !status shows one line for every farm that is running.")
BUDDY_NOTE = ("<b>Typing here without a !</b>\n"
              "{alt} says your line in the game, as themselves: what they see in the game's chat comes in here, their own line too "
              "when the server shows it. Several lines go as several lines, five at most, one a second. A line that starts "
              "with / is a command they type (/msg, /home, …). A line with a ! is a command for them and is never said.")
def me(capital=True):
    """The buddy's name for what the bridge says: altName of buddy.json, the account they play on; nothing of one person in here."""
    return alt_name() or ("The buddy" if capital else "the buddy")


def named(text):
    """A text of DOC with the buddy's own name where it says The buddy."""
    return text.replace("The buddy", me())


def help_text(word):
    word = word.lstrip("!").lower()
    if word in ("wheatfarm", "crop"):
        word = "cropfarm"
    if word:
        if word not in DOC:
            return f"❓ No command <b>!{escape(word)}</b>, see <b>!help</b>"
        how, short, full = DOC[word]
        return f"<b>{escape(how)}</b>\n{escape(named(short))}." + (f"\n\n{escape(named(full))}" if full else "")
    out = ["<b>Commands</b> (admins only) — <b>!help name</b> explains one in full"]
    for title, names in SECTIONS:
        out.append(f"\n<b>{title}</b>")
        out += [f"<b>{escape(DOC[n][0])}</b> — {escape(named(DOC[n][1]))}" for n in names]
    out.append("\n" + STATUS_NOTE)
    out.append("\n" + BUDDY_NOTE.format(alt=escape(me())))
    return "\n".join(out)


COMMAND = re.compile(r"^!\s*([A-Za-z]+)(?:@\w+)?(?:\s+(.*))?$", re.S)
PLAYER = re.compile(r"^[A-Za-z0-9_.]{3,16}$")
HOME = re.compile(r"^[A-Za-z0-9_-]{1,32}$")
MAX_CHAT = 256          # the server refuses anything longer

ON = ("on", "start", "true", "yes", "1")
OFF = ("off", "stop", "false", "no", "0")
# !word -> the switch name the mod knows
MODES = {"mod": "mod", "afk": "afk", "farmmob": "farm", "farmraid": "raid",
         "farmwood": "wood", "nanocrop": "harvest", "cropfarm": "wheat", "build": "build"}
CAN_FINISH = ("raid", "wood")
# fields of the mod's answer (the switch event), in the order we show them
STATE = (("mod", "mod"), ("afk", "afk"), ("farm", "farmmob"), ("raid", "farmraid"), ("wood", "farmwood"),
         ("harvest", "nanocrop"), ("wheat", "cropfarm"), ("build", "build"))
STATE_WORD = {True: "on", False: "off", "on": "on", "off": "off", "finishing": "finishing"}
# why unloading stopped, as the mod reports it, in the words of the window
DUMP_WHY = {"all cleared": "everything stored", "nothing left to drop": "nothing left to throw",
            "no chest within reach": "no chest within reach", "no chest near any more": "no more chests nearby",
            "took too long": "took too long", "asked": "you asked"}


# buddy.json key -> what it does (the comments in the mod's AfkConfig)
ABOUT = {'modEnabled': 'master switch: off = the mod does nothing at all',
 'afkAuto': 'true when AFK mode was switched on by auto-AFK (only then it goes off by itself on movement)',
 'autoAfk': 'switch AFK mode on by itself after a while without input',
 'autoAfkMin': '... after this many minutes without keys/mouse',
 'eatBelow': 'eat at food points <= this (out of 20, so 16 = 8 of the 10 drumsticks)',
 'eatBelowHealth': '... and also at this many health points or fewer (out of 20, so 16 = 8 of the 10 hearts)',
 'lookAround': 'when AFK: look at untrusted players nearby',
 'lookAtMinSec': 'how long you look at a player (from/to)',
 'lookRadius': '... within this many blocks',
 'wander': 'when AFK, walk a short stretch now and then (there and back, only over a safe flat path)',
 'wanderEveryMin': 'on average every this many minutes (random 0.5-1.5x)',
 'wanderMaxBlocks': 'per leg; a route has 2-4 legs',
 'glanceEverySec': 'casual glance on average every this many seconds',
 'steadyFps': "vanilla 'inactivity FPS limit' on Minimized: no 10/30 fps while AFK or in the Esc menu (Sodium hides "
              'this option)',
 'zones': 'fixed places where players get reported',
 'panicOnlyWhenBusy': 'only during AFK mode or farming, not while you play yourself',
 'panicHealth': 'log out below this many health points',
 'panicHome': 'teleport to this home first (through the /homes menu); empty = log out at once',
 'reconnectMaxAttempts': 'alt: this many failed attempts in a row, then quit the game (systemd restarts it with a '
                         'fresh session)',
 'farmWalkEverySec': 'always walk a real stretch now and then (well over a block there and back)',
 'farmBlockMoveSec': 'this long on the same block = take a real step (the server sees you as AFK otherwise)',
 'farmCrouch': 'crouch while approaching and during nudges',
 'farmCrouchMinSec': 'how long you stay down during a nudge',
 'farmApproach': 'walk as close to the mobs as possible (up to a wall/edge)',
 'farmApproachRange': 'only approach mobs within this many blocks',
 'farmUseArea': 'only hit mobs above the landing spot and walk there',
 'farmAreaX1': 'a corner of the mob farm\'s area, with farmAreaZ1, farmAreaX2 and farmAreaZ2',
 'raidDrink': 'drink a new ominous bottle after a raid',
 'raidDrinkPauseSec': 'wait after the victory before the next bottle',
 'farmThroughMenus': 'keep hitting while the chat or the Esc menu is open',
 'farmRecenter': 'return to your starting spot when you have drifted',
 'farmVisitorRadius': 'untrusted player this close: farm stands still as long as they stay (0 = off)',
 'farmVisitorAfterSec': 'and this long afterwards, so you do not start the moment they turn around',
 'farmSideSwitchSec': 'this often move to another side of the landing spot (0 = never)',
 'farmSessionMin': 'on average this often a long break of 2-8 min (0 = never)',
 'clickLog': 'intervals between hits to clicks.csv (for klikanalyse.py)',
 'watchdogAlarm': 'alarm when someone tests you: boat, blocks, water, a push',
 'watchdogPauseMin': 'first signal: stand still this long and then carry on by itself (0 = stop right away)',
 'farmDropsMax': 'this many loose items (not stacks) at the farm = chests full, farm off (0 = off)',
 'farmHomeBack': 'home to go to when you got teleported away while farming ("" = do nothing)',
 'raidJump': 'jump once after drinking',
 'raidLookY': 'where to look when there is nothing to hit, hundredths of a block relative to your feet',
 'raidWaitMaxSec': 'wait this long for a raid after a bottle; then try again',
 'woodY': 'floor height: where you stand during the scan (y of the saplings)',
 'woodBonemeal': 'use bonemeal',
 'woodBonemealThreshold': 'bonemeal round only when at most this percent of the plots has a tree (0 = only on an empty '
                        'field)',
 'woodBonemealMax': 'per sapling; skip and report after that',
 'woodAxeMin': 'stop when the axe has less durability left',
 'woodPickupEverySec': 'pick up at most once per this many seconds, at least 180 (items only vanish after 5 minutes)',
 'woodKeepSaplings': 'per kind to keep in the inventory',
 'woodTakeAlongMax': 'this many logs of another kind in a row simply get chopped along (0 = never)',
 'woodPauseEveryMin': 'on average this often a breather of 20-60 s (0 = never)',
 'woodPauseRadius': 'player within this many blocks: stand still',
 'wheatOn': 'the crop field switch itself (use !cropfarm); it survives a restart',
 'wheatHome': 'the home beside the crop field, with the chests and the crafting table',
 'wheatCrop': 'what the buddy plants on the crop field: wheat_seeds, carrot, potato or beetroot_seeds; seeds of other crops are sold',
 'wheatHoe': 'break the crops with the hoe that has the most Fortune and plant from the off hand; without a usable hoe the buddy breaks by hand',
 'wheatHoeMin': 'a hoe with this much durability or less goes back into a chest, and the buddy takes another there',
 'wheatPauseRadius': 'untrusted player within this many blocks: stand still (0 = never)',
 'wheatWaitAfterPlayerSec': 'and this long after they left',
 'wheatPauseEveryMin': 'on average this often a breather of 20-60 s (0 = never)',
 'buildMaxPerSec': 'blocks per second at most',
 'buildPauseEveryMin': 'on average this often a breather of 20-60 s (0 = never)',
 'buildPauseRadius': 'untrusted player within this many blocks: stand still (0 = never)',
 'buildClearAir': 'also break what stands where the schematic wants air',
 'buildUseScaffold': 'bridges and pillars of cheap blocks to reach and to stand right',
 'buildUseScaffolding': 'towers of scaffolding to reach up high, instead of dirt pillars',
 'buildRemoveScaffold': 'break the scaffold again when the build is done',
 'buildToolMin': 'never break with a tool that has less durability left',
 'buildPickup': 'pick up the drops around the build now and then',
 'altHome': 'alt: the home a plain !home or a home asked by PM takes ("" = plain /home)',
 'pmCommands': 'commands by PM from trusted players (tpa, tpahere, tpaccept, home, status)',
 'altName': 'the buddy\'s account name, written when they log in',
 'altCrouchAlong': 'alt: crouch on/off when a trusted player nearby crouches, or by PM "crouch"',
 'altCrouched': 'alt: last requested crouch and jump state, so it survives a restart',
 'keepEvents': 'archive events.jsonl at startup instead of clearing it (alt: nothing lost after a restart)',
 'servers': 'only act automatically here',
 'allServers': 'testing: active everywhere'}
# never shown
SECRET = {"shopSyncKey"}
# never changed from Telegram: they decide who the alt is and where its files and server are
LOCKED = SECRET | {"shopSyncUrl", "exchangeDir", "altName", "allServers"}
# hits per second: above ~6 the anti-cheat notices
CEILING = {"farmMinCps": 6.0, "farmMaxCps": 6.0}
# places: shown by !positions, left out of the settings list
PLACES = {"wheatX1", "wheatZ1", "wheatX2", "wheatZ2", "farmAreaX1", "farmAreaZ1", "farmAreaX2", "farmAreaZ2", "farmAreaDim",
          "raidX", "raidZ", "raidDim", "woodX1", "woodZ1", "woodX2", "woodZ2", "woodY", "woodDim", "woodPlots", "woodChests",
          "zones"}
GROUPS = (("AFK and looking around", ("afk", "autoAfk", "look", "wander", "glance", "steady")),
          ("Eating", ("eat",)),
          ("Players and alerts", ("player", "home", "alert", "trusted", "tpa", "watchdog")),
          ("Safety and connection", ("panic", "autoReconnect", "reconnect", "servers")),
          ("Mob farm", ("farm", "clickLog")), ("Raid farm", ("raid",)), ("Tree farm", ("wood",)), ("Crop field", ("wheat",)), ("Building", ("build",)),
          ("Alt", ("alt", "pmCommands", "keepEvents")))


def order(line):
    """Appends one line to commands.jsonl; the mod reads it within a second."""
    with open(COMMANDS, "a") as f:
        f.write(json.dumps(line, ensure_ascii=False) + "\n")


def now_ms():
    return int(time.time() * 1000)


def answer_of(since_ms, kinds, wait=3.0, want=None):
    """The mod's own answer to an order: the first event of one of these types written after since_ms.
    Without it a reply would only say what was asked, not what happened."""
    end = time.time() + wait
    while time.time() < end:
        time.sleep(0.4)
        try:
            with open(EVENTS, "r", errors="replace") as f:
                f.seek(max(0, os.path.getsize(EVENTS) - 60000))
                for l in f:
                    if not any(f'"{k}"' in l for k in kinds):
                        continue
                    try:
                        ev = json.loads(l)
                    except Exception:
                        continue
                    if ev.get("type") in kinds and ev.get("t", 0) >= since_ms and (want is None or want(ev)):
                        return ev
        except FileNotFoundError:
            pass
    return None


def later(since_ms, kinds, want, wait, tell):
    """Reports in the group when a slow job is done, without holding up the next command."""
    def run():
        ev = answer_of(since_ms, kinds, wait, want)
        text = tell(ev)
        if text:
            send(text)
    threading.Thread(target=run, daemon=True).start()


def snapshot():
    """The latest of each kind of event the answers are built from, and who is online, in one pass over the log."""
    last = {}
    online = None
    since = None   # name -> [ms, seen coming online]; a name from the list at connecting has the moment of that list
    try:
        with open(EVENTS, "r", errors="replace") as f:
            for l in f:
                try:
                    ev = json.loads(l)
                except Exception:
                    continue
                t = ev.get("type")
                if t in ("status", "location", "inventory", "mod_started", "scoreboard"):
                    last[t] = ev
                elif t == "connection" and ev.get("action") == "connected":
                    last["connected"] = ev
                elif t == "players":
                    online = set(ev.get("names", "").split())
                    since = {n: [ev.get("t"), False] for n in online}
                elif t == "player_join" and online is not None:
                    online.add(ev.get("name"))
                    since[ev.get("name")] = [ev.get("t"), True]
                elif t == "player_leave" and online is not None:
                    online.discard(ev.get("name"))
                    since.pop(ev.get("name"), None)
    except FileNotFoundError:
        pass
    last["online"] = online
    last["online_since"] = since
    return last


def span(seconds):
    seconds = int(seconds)
    d, h, m = seconds // 86400, seconds % 86400 // 3600, seconds % 3600 // 60
    return f"{d}d {h}h" if d else f"{h}h {m}m" if h else f"{m}m" if m else f"{seconds}s"


ITEM = re.compile(r"^(.+?)(?:x(\d+))?(?:\((\d+)\))?$")


def inventory(ev):
    """The inventory event as a table: "2:cooked_beefx23 4:diamond_axe(912)" becomes one row per slot."""
    parts = {"Hotbar": [], "Backpack": [], "Armour and off hand": []}
    for token in ev.get("things", "").split():
        where, _, what = token.partition(":")
        slot = int(where) if where.isdigit() else 40 if where == "hand2" else 36 + int(where[5:]) if where.startswith("armor") else -1
        m = ITEM.match(what)
        name, count, uses = m.group(1).replace("_", " "), m.group(2), m.group(3)
        row = f"{slot:>2}  {name}" + (f" ×{count}" if count else "") + (f"  ({uses} uses left)" if uses else "")
        parts["Hotbar" if slot < 9 else "Backpack" if slot < 36 else "Armour and off hand"].append((slot, row))
    lines = []
    for title, rows in parts.items():
        if rows:
            lines.append(title)
            lines += [row for _, row in sorted(rows)]
    head = f"🎒 <b>Inventory</b> — free: {ev.get('free_hotbar', '?')} of 9 hotbar, {ev.get('free_backpack', '?')} of 27 backpack"
    if not lines:
        return head + "\nEmpty"
    return head + "\n<pre>" + escape("\n".join(lines)) + "</pre>\nEmpty a slot with <b>!dump 2</b>, several with <b>!dump 2 10 11</b>"


YIELD = os.path.join(EXCHANGE, FILES["yield"])
# !word -> (farm in the yield log and in the farmstatus event, title, (counter, what it counts) ...)
FARM_YIELD = {
    "cropfarm": ("wheat", "🌾 Crop field", (("harvested", "harvested"), ("planted", "planted"))),
    "nanocrop": ("crop", "🌱 Nano crop", (("harvested", "harvested"), ("planted", "planted"))),
    "farmmob": ("mob", "⚔ Mob farm", (("killed", "killed"), ("hits", "hits"))),
    "farmraid": ("raid", "🏹 Raid farm", (("killed", "killed"), ("hits", "hits"))),
    "farmwood": ("wood", "🌲 Tree farm", (("logs", "logs chopped"),)),
}
WHEAT_PHASE = {"home": "going to their home", "settle": "arriving at the field", "tidy": "at the chests",
               "goto": "walking to a row", "idle": "waiting for the crop to ripen"}
PAUSE_WHY = {"player nearby": "a player nearby", "breather": "a breather", "watchdog": "the watchdog"}


def yield_rows(farm):
    """The lines of the yield log for one farm."""
    rows = []
    try:
        with open(YIELD) as fh:
            for ln in fh:
                try:
                    o = json.loads(ln)
                except ValueError:
                    continue
                if not isinstance(o, dict):
                    continue
                if o.get("farm") == farm:
                    rows.append(o)
    except FileNotFoundError:
        pass
    return rows


def farm_live(farm=None):
    """The mod's own answer: per running farm (or the one asked for) where it is and what is not in the file yet."""
    since = now_ms()
    order({"cmd": "farmstatus", "farm": farm} if farm else {"cmd": "farmstatus"})
    ev = answer_of(since, ("farmstatus",))
    return None if ev is None else ev.get("farms", [])


def farm_state(farm, live):
    if live is None:
        return "no answer from the mod (offline?)"
    if not live.get("on"):
        if farm == "wheat" and live.get("on_in_s"):
            return f"switched on, carries on in {span(live['on_in_s'])}"
        return "off"
    parts = ["on"]
    if live.get("on_since"):
        parts[0] += f" for {span(time.time() - live['on_since'] / 1000)}"
    if farm == "wheat":
        phase = live.get("phase")
        if (live.get("floors") or 0) > 1:   # floor 1 is the top one
            parts.append(f"floor {live.get('floor')} of {live.get('floors')}")
        if live.get("crop"):   # the item the buddy puts on bare farmland
            parts.append("planting " + str(live["crop"]).replace("_", " "))
        parts.append(f"row {live.get('row')} of {live.get('rows')}" if phase == "lane" else WHEAT_PHASE.get(phase, phase))
        if live.get("pause"):
            parts.append("paused: " + PAUSE_WHY.get(live["pause"], live["pause"]))
        if live.get("rounds"):
            parts.append(f"{live['rounds']} rounds done")
    elif farm == "raid" and live.get("busy"):
        parts.append(f"raid {live.get('round')}, wave {live.get('wave')}")
    elif farm == "wood" and live.get("modules"):
        parts.append(f"{live['modules']} modules done")
    return " · ".join(escape(str(p)) for p in parts)


def midnight_of(now):
    lt = time.localtime(now)
    return time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday, 0, 0, 0, 0, 0, -1))


def tally(farm, live, now):
    """What a farm did since a moment and per second that it was on: the yield log plus what the mod has not written
    yet. Returns the lines of the log, the pending counters and the two sums."""
    pending = (live or {}).get("not_in_file_yet") or {}
    rows = yield_rows(farm)
    def total(key, start):
        return sum(r.get(key, 0) for r in rows if r["t"] / 1000 >= start) + pending.get(key, 0)
    def per_second(key, start):
        """Per second that the farm was on: the lines of the log carry their own seconds, what is not in the file
        yet counts from the last line (or from when the farm went on)."""
        part = [r for r in rows if r["t"] / 1000 >= start]
        n = sum(r.get(key, 0) for r in part)
        secs = sum(r.get("active_s", r.get("sec", 0)) for r in part)
        if pending.get(key) and (live or {}).get("on"):
            since = max(rows[-1]["t"] / 1000 if rows else 0, (live.get("on_since") or 0) / 1000, start)
            extra = min(300, now - since)
            if extra >= 30:
                n += pending[key]
                secs += extra
        return n / secs if secs >= 30 else None
    return rows, pending, total, per_second


def farm_report(word, short=False, live=None, asked=True):
    farm, title, counters = FARM_YIELD[word]
    if asked:
        answer = farm_live(farm)
        live = None if answer is None else next((f for f in answer if f.get("farm") == farm), {"on": False})
    now = time.time()
    midnight = midnight_of(now)
    rows, pending, total, per_second = tally(farm, live, now)
    # a harvest is counted per second as well: that is what the field is judged by
    speed = counters[0][0] == "harvested"
    if short:
        key, what = counters[0]
        rate = per_second(key, now - 3600) if speed else None
        return (f"{title}: {farm_state(farm, live)} · {total(key, now - 3600):,} {what} in the last hour"
                + (f" ({rate:.2f} crops/s)" if rate is not None else ""))
    lines = [f"<b>{title}</b>: {farm_state(farm, live)}"]
    for key, what in counters:
        lines.append(f"{what.capitalize()}: last hour <b>{total(key, now - 3600):,}</b> · today {total(key, midnight):,} · in total {total(key, 0):,}")
    if speed:
        key = counters[0][0]
        parts = []
        for label, start in (("now", now - 420), ("last hour", now - 3600), ("today", midnight), ("in total", 0)):
            rate = per_second(key, start)
            if rate is not None:
                parts.append(f"{label} <b>{rate:.2f}</b>" if not parts else f"{label} {rate:.2f}")
        if parts:
            lines.append("Crops per second (while on): " + " · ".join(parts))
    if not rows and not pending:
        lines.append("Nothing in the yield log yet (it is written every five minutes, from AFK Helper 0.3.62 on)")
    return "\n".join(lines)


# the labels are the server's own; only these two read better in English
BOARD_LABEL = {"Geld": "Money", "/vote": "Votes"}


def board_of(s):
    """The scoreboard as the mod last wrote it (AFK Helper 0.3.87 on): label -> value. A server may put an icon from
    its resource pack in front of some labels, a private-use character; 0.3.87 still left those in."""
    board = (s.get("scoreboard") or {}).get("lines") or {}
    return {re.sub("[\ue000-\uf8ff]", "", k).strip(): v for k, v in board.items()}


def board_line(s):
    """What the scoreboard says they have: money, tokens, shards, claims, votes, kills, team."""
    board = board_of(s)
    return "💰 " + " · ".join(f"{escape(BOARD_LABEL.get(k, k))} <b>{escape(str(v))}</b>" for k, v in board.items()) if board else None


def status():
    s = snapshot()
    st, loc = s.get("status"), s.get("location")
    if not st:
        return "No status in the log yet"
    now = time.time()
    age = int(now - st["t"] / 1000)
    lines = []
    if age > 30:
        lines.append(f"⚠ Last sign of life {span(age)} ago: {escape(me())} is offline or the game hangs")
    if loc:
        lines.append(f"📍 <b>{loc['x']} {loc['y']} {loc['z']}</b> · zone {escape(str(loc.get('zone')))} · {escape(str(loc.get('dim', '')).replace('minecraft:', ''))}")
    lines.append(f"❤ {round(st['hp'])}/20   🍗 {st['food']}/20   AFK mode {'on' if st.get('afk') else 'off'}")
    if board_line(s):
        lines.append(board_line(s))
    lines.append(f"🍞 Food: {st.get('food_hotbar', '?')} in hotbar, {st.get('food_inv', '?')} in backpack · holding {escape(str(st.get('hand', '?')).replace('_', ' '))}")
    up = []
    if s.get("connected") and age <= 30:
        up.append(f"connected {span(now - s['connected']['t'] / 1000)}")
    if s.get("mod_started"):
        up.append(f"{escape(me())}'s game running {span(now - s['mod_started']['t'] / 1000)} (AFK Helper {escape(str(s['mod_started'].get('version')))})")
    try:
        up.append(f"VPS up {span(float(open('/proc/uptime').read().split()[0]))}")
    except Exception:
        pass
    lines.append("⏱ " + " · ".join(up))
    if age <= 30:
        running = farm_live()
        for word, (farm, _, _) in FARM_YIELD.items():
            live = next((f for f in running or [] if f.get("farm") == farm), None)
            if live:
                lines.append(farm_report(word, short=True, live=live, asked=False))
    if s.get("online") is not None:
        lines.append(f"👥 {len(s['online'])} players online")
    if s.get("inventory"):
        lines.append("")
        lines.append(inventory(s["inventory"]))
    return "\n".join(lines)


def dim(d):
    return str(d or "?").replace("minecraft:", "")


# the numbers of !status for a read-only page, only where the settings name a file (paths.status_site); its names are
# English since 0.4.165
SITE = PATHS.get("status_site", "")


def series(rows, now, step=900, hours=24):
    """The yield log per quarter of an hour over the last 24 hours, for the graphs on the website: per quarter
    [start in ms, harvested, seconds the farm was on, earned]. Whole quarters only: the running one is left out, a
    sale every six minutes makes a part of a quarter look far better or worse than it is. A line of the log counts
    for the quarter its middle falls in."""
    end = int(now // step) * step
    start = end - hours * 3600
    out = [[(start + i * step) * 1000, 0, 0, 0] for i in range(hours * 3600 // step)]
    for r in rows:
        i = int((r["t"] / 1000 - r.get("sec", 0) / 2 - start) // step)
        if 0 <= i < len(out):
            out[i][1] += r.get("harvested", 0)
            out[i][2] += r.get("active_s", r.get("sec", 0))
            out[i][3] += r.get("earned", 0)
    return out


SINCE = os.path.expanduser(PATHS.get("since") or "~/online_since.json")


def online_since(seen, now):
    """Who is online and since when, for the list on the website: [name, ms, seen coming online]. The log only has
    the moment of players who came online while the buddy was watching; who was already there when they connected gets
    the moment of that list, as 'not later than'. Every reconnect of the buddy (restart, kick, hub) starts such a list
    again, and a mod release restarts them: so what was known up to a quarter of an hour ago is kept in a file, and a player
    they find still there keeps the earlier moment."""
    now_ms = int(now * 1000)
    try:
        with open(SINCE) as f:
            known = {n: k for n, k in json.load(f).items() if now_ms - k[2] < 900_000}
    except Exception:
        known = {}
    out = []
    for name, (t, exact) in seen.items():
        was = known.get(name)
        if not exact and was and was[0] < t:
            t, exact = was[0], was[1]
        known[name] = [t, exact, now_ms]
        out.append([name, t, exact])
    try:
        with open(SINCE + ".tmp", "w") as f:
            json.dump(known, f)
        os.replace(SINCE + ".tmp", SINCE)
    except OSError as e:
        print("online_since:", e, flush=True)
    return out


def site_status():
    """Everything !status and !<farm> status show, as numbers and the mod's own words: the website words them
    itself. With the buddy's coordinates and the hotbar slot they hold: both are on the page, although it has no login."""
    s = snapshot()
    st, loc = s.get("status"), s.get("location")
    now = time.time()
    out = {"t": now_ms()}
    age = None
    if st:
        age = int(now - st["t"] / 1000)
        out.update(age_s=age, hp=st.get("hp"), food=st.get("food"), afk=bool(st.get("afk")), hand=st.get("hand", ""),
                   food_hotbar=st.get("food_hotbar"), food_inv=st.get("food_inv"))
        if st.get("hand_slot") is not None:   # from AFK Helper 0.3.94 on
            out["hand_slot"] = st["hand_slot"]
    if loc:
        out.update(zone=loc.get("zone"), dim=dim(loc.get("dim")), x=loc.get("x"), y=loc.get("y"), z=loc.get("z"))
    online = age is not None and age <= 30
    if s.get("connected") and online:
        out["connected_s"] = int(now - s["connected"]["t"] / 1000)
    if s.get("mod_started"):
        out.update(game_s=int(now - s["mod_started"]["t"] / 1000), version=s["mod_started"].get("version"))
    try:
        out["vps_s"] = int(float(open("/proc/uptime").read().split()[0]))
    except Exception:
        pass
    if s.get("scoreboard"):
        out.update(board=board_of(s), board_t=s["scoreboard"]["t"])
    if s.get("online") is not None:
        out["players"] = len(s["online"])
        out["online"] = online_since(s.get("online_since") or {}, now)
    if s.get("inventory"):
        inv = s["inventory"]
        out["inventory"] = {k: inv.get(k) for k in ("free_hotbar", "free_backpack", "things", "t")}
    running = farm_live() if online else None
    midnight = midnight_of(now)
    farms = []
    for farm, _, _ in FARM_YIELD.values():
        live = next((f for f in running or [] if f.get("farm") == farm), None)
        rows, pending, total, per_second = tally(farm, live, now)
        if not rows and not live:
            continue
        keys = []
        for r in rows + [pending]:
            keys += [k for k in r if k not in ("t", "farm", "sec", "active_s") and k not in keys]
        entry = {"farm": farm, "live": live, "known": running is not None,
                 "counters": {k: {"hour": total(k, now - 3600), "today": total(k, midnight), "total": total(k, 0)} for k in keys}}
        if "harvested" in keys:
            entry["per_s"] = {name: per_second("harvested", start)
                              for name, start in (("now", now - 420), ("hour", now - 3600), ("today", midnight), ("total", 0))}
        if "harvested" in keys or "earned" in keys:
            entry["series"] = series(rows, now)
        farms.append(entry)
    out["farms"] = farms
    return out


def site_writer():
    """Once a minute: that is how often the page refreshes itself, and each round asks the mod for its farm status."""
    if not SITE:
        return
    while True:
        try:
            os.makedirs(os.path.dirname(SITE), exist_ok=True)
            tmp = SITE + ".tmp"
            with open(tmp, "w") as f:
                json.dump(site_status(), f, ensure_ascii=False)
            os.replace(tmp, SITE)
        except Exception as e:
            print("site:", e, flush=True)
        time.sleep(60)


def positions():
    cfg = json.load(open(CONFIG))
    s = snapshot()
    loc = s.get("location")
    out = ["<b>Places</b>"]
    if loc:
        out.append(f"📍 Now: <b>{loc['x']} {loc['y']} {loc['z']}</b> ({escape(str(loc.get('zone')))}, {dim(loc.get('dim'))})")
    out.append(f"⚔ Mob farm spot: x {cfg.get('farmAreaX1')}..{cfg.get('farmAreaX2')}, z {cfg.get('farmAreaZ1')}..{cfg.get('farmAreaZ2')} ({dim(cfg.get('farmAreaDim'))})"
               f" — {'used' if cfg.get('farmUseArea') else 'not used (farmUseArea off)'}")
    out.append(f"🚩 Raid spot: x {cfg.get('raidX')}, z {cfg.get('raidZ')} ({dim(cfg.get('raidDim'))})")
    if cfg.get("woodPlots"):
        out.append(f"🌲 Wood field: x {cfg.get('woodX1')}..{cfg.get('woodX2')}, z {cfg.get('woodZ1')}..{cfg.get('woodZ2')}, floor y {cfg.get('woodY')} ({dim(cfg.get('woodDim'))})"
                   f" — {len(cfg['woodPlots'])} plots")
        chests = cfg.get("woodChests") or []
        if chests:
            out.append("   chests: " + ", ".join(f"{c['x']} {c['y']} {c['z']}" for c in chests))
    else:
        out.append("🌲 Wood field: not scanned")
    if cfg.get("wheatX1") != cfg.get("wheatX2"):
        out.append(f"🌾 Crop field: {cfg.get('wheatX1')},{cfg.get('wheatZ1')} to {cfg.get('wheatX2')},{cfg.get('wheatZ2')}, home “{escape(str(cfg.get('wheatHome')))}”"
                   + (f", plants {escape(str(cfg['wheatCrop']).replace('_', ' '))}" if cfg.get("wheatCrop") else ""))
    else:
        out.append("🌾 Crop field: not set (!set wheatX1 …, wheatZ1, wheatX2, wheatZ2)")
    shop = cfg.get("shop")
    if shop:
        out.append(f"🛒 Shop “{escape(str(shop.get('name')))}”: {shop.get('x')} {shop.get('y')} {shop.get('z')}, radius {shop.get('radius')} ({dim(shop.get('dim'))})")
    for z in cfg.get("zones") or []:
        out.append(f"🔔 Zone “{escape(str(z.get('name')))}”: {z.get('x')} {z.get('y')} {z.get('z')}, radius {z.get('radius')} ({dim(z.get('dim'))})")
    since = now_ms()
    order({"cmd": "placement", "action": "list"})
    ev = answer_of(since, ("placement",))
    if ev is None:
        out.append("🧱 Placements: no answer from the mod")
    elif ev.get("action") == "failed":
        out.append(f"🧱 Placements: {escape(str(ev.get('reason')))}")
    else:
        out.append("🧱 Placements: " + (escape(ev.get("placements") or "") or "none"))
    return "\n".join(out)


def switch(what, state, label):
    """Sends a switch (state on, off or finish) and reports what the mod says all switches are afterwards."""
    since = now_ms()
    order({"cmd": "switch", "what": what, "state": state})
    ev = answer_of(since, ("switch", "error"))
    if ev is None:
        return f"🔧 {label}: sent, but no answer from the mod (offline?)"
    if ev["type"] == "error":
        return f"❌ {label}: the mod refused ({escape(str(ev.get('msg')))})"
    states = " · ".join(f"{name} <b>{STATE_WORD.get(ev.get(key), ev.get(key))}</b>" for key, name in STATE if key in ev)
    now = STATE_WORD.get(ev.get(what), ev.get(what))
    asked = state if state in ("on", "off") else "finishing"
    note = "" if now == asked or (asked == "finishing" and now in ("on", "finishing")) else f"\n⚠ It is <b>{now}</b>, not {asked}: the mod did not go along (wrong place, nothing to do, or no placement)"
    return f"🔧 {label}\n{states}{note}"


def set_crop(raw):
    """What the buddy plants on the crop field. Through the mod, not the file: it knows what grows on farmland, and with a
    name it does not know they would break every ripe crop and plant nothing back."""
    item = raw.strip().lower().replace("minecraft:", "")
    if not re.fullmatch(r"[a-z_]{2,40}", item):
        return "❌ Like this: <b>!cropfarm crop carrot</b> (or potato, wheat_seeds, beetroot_seeds)"
    since = now_ms()
    order({"cmd": "set", "key": "wheatCrop", "value": item})
    ev = answer_of(since, ("config_set", "error"))
    if ev is None:
        return "🌾 Crop: sent, but no answer from the mod (offline?)"
    if ev["type"] == "error":
        if "unknown crop" in str(ev.get("msg")):
            return f"❌ <b>{escape(item)}</b> is not a crop that grows on farmland. Try carrot, potato, wheat_seeds or beetroot_seeds"
        return "❌ The mod refused: choosing the crop needs AFK Helper 0.3.97 or newer"
    return (f"🌾 {escape(me())} plants <b>{escape(item.replace('_', ' '))}</b> from now on. They break whatever stands ripe and put only this back, "
            "so hand them a few: they do not sell those while the field still needs them. Seeds of other crops are sold.")


def setting_key(name):
    """The buddy.json key for a name typed in Telegram, in any case."""
    cfg = json.load(open(CONFIG))
    low = name.lower()
    for k in cfg:
        if k.lower() == low:
            return cfg, k
    return cfg, None


def value_text(value):
    if isinstance(value, bool):
        return "on" if value else "off"
    if isinstance(value, list):
        if all(isinstance(x, str) for x in value):
            return ", ".join(value) if value else "(empty)"
        return f"{len(value)} entries"
    if isinstance(value, dict):
        return "(see !positions)"
    return "(empty)" if value == "" else str(value)


def shown(key, value, about=False):
    line = f"{key} = <b>{escape(value_text(value))}</b>"
    if about and ABOUT.get(key):
        line += f"\n   <i>{escape(ABOUT[key])}</i>"
    return line


def is_scalar(v):
    return isinstance(v, (bool, int, float, str))


def group_of(key):
    best = None
    for title, starts in GROUPS:
        for s in starts:
            if key.startswith(s) and (best is None or len(s) > best[1]):
                best = (title, len(s))
    return best[0] if best else "General"


def settings(word):
    cfg = json.load(open(CONFIG))
    keys = [k for k in cfg if k not in SECRET and k not in PLACES and k != "exchangeDir"]
    if word:
        low = word.lower()
        rows = [shown(k, cfg[k], True) for k in keys if low in k.lower()]
        if not rows:
            return f"No setting with “{escape(word)}” in its name; <b>!settings</b> alone lists them all"
        return "\n".join(rows) + "\n\nChange one with <b>!set name value</b>"
    groups = {}
    for k in keys:
        groups.setdefault(group_of(k), []).append(shown(k, cfg[k]))
    out = ["<b>All settings</b> — <b>!settings word</b> explains a group, <b>!set name value</b> changes one, <b>!positions</b> has the places"]
    for title in ["General"] + [g[0] for g in GROUPS]:
        if title in groups:
            out.append(f"\n<b>{title}</b>")
            out += groups[title]
    return "\n".join(out)


def get_setting(name):
    cfg, key = setting_key(name)
    if key is None or key in SECRET:
        return f"❌ No setting called “{escape(name)}”; try <b>!settings word</b>"
    return shown(key, cfg[key], True)


def set_setting(name, raw):
    cfg, key = setting_key(name)
    if key is None or key in SECRET:
        return f"❌ No setting called “{escape(name)}”; try <b>!settings word</b>"
    label = key
    if key in LOCKED:
        return f"❌ {label} cannot be changed from Telegram"
    if key == "wheatCrop":
        return set_crop(raw)
    old = cfg[key]
    low = raw.lower()
    try:
        if isinstance(old, bool):
            if low not in ON + OFF:
                return f"❌ {label} is on or off"
            new = low in ON
        elif isinstance(old, int):
            new = int(raw)
        elif isinstance(old, float):
            new = float(raw.replace(",", "."))
        elif isinstance(old, str):
            new = raw
        else:
            return f"❌ {label} is a list; it cannot be set from here"
    except ValueError:
        return f"❌ {label} takes a number (now {old})"
    if not isinstance(new, (bool, str)):
        if new < 0 and key not in PLACES:
            return "❌ Not below zero"
        if key in CEILING and new > CEILING[key]:
            return f"❌ {label} goes up to {CEILING[key]:g}"
    # the mod reloads the file when its time stamp changes; replace it in one step so it never reads half a file
    cfg[key] = new
    tmp = CONFIG + ".tmp"
    with open(tmp, "w") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)
    os.replace(tmp, CONFIG)
    # the mod writes this file too: check that our value is still there after it has picked it up
    time.sleep(2)
    if json.load(open(CONFIG)).get(key) != new:
        return f"⚠ {label}: the mod wrote the file at the same moment, value not kept. Try again."
    return "⚙ " + shown(key, new, True) + f"\n   was {escape(value_text(old))}"


def schematic_files():
    try:
        return sorted(f for f in os.listdir(SCHEMATICS) if f.lower().endswith(".litematic"))
    except FileNotFoundError:
        return []


def schematics():
    files = schematic_files()
    if not files:
        return "No schematics yet. Send a .litematic file as an attachment in this group."
    rows = [f"• {escape(f[:-10])} ({max(1, os.path.getsize(os.path.join(SCHEMATICS, f)) // 1024)} kB)" for f in files]
    return "<b>Schematics</b>\n" + "\n".join(rows) + "\n\nPlace one with <b>!place name x y z</b> or <b>!place name here</b>"


def find_schematic(name):
    """The file for a name typed in Telegram: exact, without the extension, or the one file that contains it."""
    files = schematic_files()
    low = name.lower()
    for f in files:
        if f.lower() in (low, low + ".litematic"):
            return f, None
    hits = [f for f in files if low in f.lower()]
    if len(hits) == 1:
        return hits[0], None
    if not hits:
        return None, f"❌ No schematic called “{escape(name)}”; see <b>!schematics</b>"
    return None, "❌ Several match: " + ", ".join(escape(h[:-10]) for h in hits)


def placement_answer(ev):
    if ev is None:
        return "❌ No answer from the mod (offline?)"
    a = ev.get("action")
    if a == "failed":
        return f"❌ Placement failed: {escape(str(ev.get('reason')))}"
    if a == "set":
        return (f"🧱 Placed <b>{escape(str(ev.get('name')))}</b> with its origin at <b>{escape(str(ev.get('origin')))}</b>\n"
                f"It spans {escape(str(ev.get('from')))} to {escape(str(ev.get('until')))}\nStart with <b>!build on</b>")
    if a == "gone":
        return f"🧱 Removed {ev.get('count')} placement(s)"
    rows = [escape(r) for r in (ev.get("placements") or "").split(" | ") if r]
    return "<b>Placements</b>\n" + ("\n".join("• " + r for r in rows) if rows else "None. Make one with <b>!place name x y z</b>")


def place(rest, file=None):
    """!place name x y z, or !place name here; with file given (an attachment) rest is only the spot."""
    words = rest.split()
    if words and words[-1].lower() == "here":
        spot, name = {"here": True}, " ".join(words[:-1])
    elif len(words) >= 3 and all(re.fullmatch(r"-?\d+", w) for w in words[-3:]):
        spot, name = {"x": int(words[-3]), "y": int(words[-2]), "z": int(words[-1])}, " ".join(words[:-3])
    else:
        return "❌ Like this: <b>!place name x y z</b> or <b>!place name here</b>"
    if file is None:
        if not name:
            return "❌ Which schematic? <b>!place name x y z</b>; see <b>!schematics</b>"
        file, wrong = find_schematic(name)
        if wrong:
            return wrong
    since = now_ms()
    order({"cmd": "placement", "action": "place", "file": file, **spot})
    return placement_answer(answer_of(since, ("placement",), 8))


def placement(action):
    """action list or remove."""
    since = now_ms()
    order({"cmd": "placement", "action": action})
    return placement_answer(answer_of(since, ("placement",)))


def attachment(doc, caption, token=MAIN_TOKEN):
    """A .litematic sent in the chat: saved in Litematica's folder, and placed at once if the caption says where."""
    name = os.path.basename(doc.get("file_name") or "")
    if not name.lower().endswith(".litematic"):
        return None if not name.lower().endswith((".schem", ".schematic", ".nbt")) else "❌ Only .litematic files; save it as a Litematica schematic first"
    if doc.get("file_size", 0) > 20_000_000:
        return "❌ Too big: Telegram gives a bot files up to 20 MB"
    clean = re.sub(r"[^A-Za-z0-9 ._-]", "_", name[:-10]).strip(" .")[:60] or "schematic"
    conn = http.client.HTTPSConnection("api.telegram.org", timeout=60)
    try:
        conn.request("GET", f"/bot{token}/getFile?" + urllib.parse.urlencode({"file_id": doc["file_id"]}))
        info = json.loads(conn.getresponse().read().decode())
        conn.request("GET", f"/file/bot{token}/{info['result']['file_path']}")
        r = conn.getresponse()
        data = r.read()
        if r.status != 200:
            return f"❌ Could not fetch the file from Telegram ({r.status})"
    finally:
        conn.close()
    os.makedirs(SCHEMATICS, exist_ok=True)
    path = os.path.join(SCHEMATICS, clean + ".litematic")
    replaced = os.path.exists(path)
    with open(path + ".tmp", "wb") as f:
        f.write(data)
    os.replace(path + ".tmp", path)
    said = f"💾 {'Replaced' if replaced else 'Saved'} schematic <b>{escape(clean)}</b> ({max(1, len(data) // 1024)} kB)"
    m = COMMAND.match((caption or "").strip())
    if m and m.group(1).lower() == "place":
        return [said, place(" ".join((m.group(2) or "").split()), clean + ".litematic")]
    return said + f"\nPlace it with <b>!place {escape(clean)} x y z</b> or <b>!place {escape(clean)} here</b>"


def dump(rest):
    words = rest.lower().split()
    if words == ["stop"]:
        since = now_ms()
        order({"cmd": "unload", "stop": True})
        ev = answer_of(since, ("unload", "error"))
        if ev is None:
            return f"📦 Nothing to stop: {escape(me())} was not emptying"
        if ev["type"] == "error":
            return f"❌ The mod refused ({escape(str(ev.get('msg')))})"
        return "🛑 Stopped"
    slots = sorted({int(w) for w in words if w.isdigit()})
    others = [w for w in words if not w.isdigit()]
    if any(w not in ("ground", "all") for w in others) or any(s > 40 for s in slots):
        return "❌ Like this: <b>!dump</b>, <b>!dump ground</b>, <b>!dump all</b>, <b>!dump 2 10 11</b> (slots 0 to 40, see !status) or <b>!dump stop</b>"
    where, everything = ("ground" if "ground" in others else "chest"), "all" in others
    line = {"cmd": "unload", "where": where, "all": everything}
    if slots:
        line["slots"] = slots
    since = now_ms()
    order(line)
    ev = answer_of(since, ("unload", "error"))
    if ev is None:
        return "📦 Sent, but no answer from the mod (offline?)"
    if ev["type"] == "error":
        return f"❌ The mod refused ({escape(str(ev.get('msg')))})"

    def done(ev):
        if ev is None:
            return "📦 Still emptying after 5 minutes; see !status"
        why = DUMP_WHY.get(ev.get("reason"), ev.get("reason"))
        return f"📦 Done: {ev.get('dumped', 0)} stacks into chests, {ev.get('thrown', 0)} thrown on the ground ({escape(str(why))})"
    later(since, ("unload",), lambda e: e.get("action") == "done", 310, done)
    what = ("slot " + ", ".join(map(str, slots))) if slots else ("everything, tools and food too" if everything else "everything except tools, armour and food")
    return f"📦 Emptying {what} {'onto the ground' if where == 'ground' else 'into chests nearby'}; I report when it is done"


def name_of(who, private=False):
    """A Telegram user id or name to their Minecraft name; None for who is not among the admins, who may do nothing.
    The user id and the @username are each one account's own; the first and last name anyone can give themselves, so
    they count only in a private chat, where nobody but that one person writes."""
    names = (" ".join(x for x in (who.get("first_name"), who.get("last_name")) if x), who.get("first_name")) if private else ()
    for candidate in (str(who.get("id") or ""), who.get("username"), *names):
        if candidate and candidate.lower() in ADMINS:
            return ADMINS[candidate.lower()] or ""   # an admin without a Minecraft name is "" (not None)
    return None


def command(t, mc):
    """One !command. Always answers, also on a typo, so you can see that nothing was done."""
    m = COMMAND.match(t)
    if not m:
        return "❓ Unknown command, see <b>!help</b>"
    what = m.group(1).lower()
    if what in ("wheatfarm", "crop"):   # wheatfarm was its name until 4 Oct 2026; crop is what the mod's own command is called
        what = "cropfarm"
    # everything on one line: a line break in a chat message gets the player kicked
    rest = " ".join((m.group(2) or "").split())
    usage = f"❌ Like this: <b>{escape(DOC[what][0])}</b>" if what in DOC else ""

    def chat(text, sign="⌨"):
        if len(text) > MAX_CHAT:
            return f"❌ Too long ({len(text)} characters, the server takes {MAX_CHAT})"
        since = now_ms()
        order({"cmd": "chat", "text": text})
        ev = answer_of(since, ("chat_sent", "chat_refused"))
        if ev is None:
            return f"{sign} {escape(text)}\n⚠ No answer from the mod (offline?)"
        if ev["type"] == "chat_refused":
            return f"❌ Not sent, {escape(me())} is not on a server where they may chat: {escape(text)}"
        return f"{sign} {escape(me())}: {escape(text)}"

    if what in ("help", "commands"):
        return help_text(rest)
    if what == "show":
        return show_command(rest)
    if what == "status":
        return status()
    if what in ("positions", "places"):
        return positions()
    if what == "say":
        if not rest:
            return usage
        if rest.startswith("/"):
            return "❌ A command goes with <b>!cmd</b>, not with !say"
        return chat(rest, "💬")
    if what == "cmd":
        if not rest:
            return usage
        return chat(rest if rest.startswith("/") else "/" + rest)
    if what == "msg":
        name, _, text = rest.partition(" ")
        if not PLAYER.match(name) or not text.strip():
            return usage
        return chat(f"/msg {name} {text.strip()}", "✉")
    if what in ("tpa", "tpahere"):
        name = rest or mc
        if not PLAYER.match(name):
            return usage
        return chat(f"/{what} {name}")
    if what == "tpaccept":
        return chat("/tpaccept")
    if what == "follow":
        low = rest.lower()
        if low in OFF:
            line = {"cmd": "follow", "on": False}
        elif low in ("stay", "wait"):
            line = {"cmd": "follow", "stay": True}
        elif low in ("go", "come"):
            line = {"cmd": "follow", "stay": False}
        elif low == "status":
            line = {"cmd": "follow"}
        elif not rest or PLAYER.match(rest):
            line = {"cmd": "follow", "player": rest or mc}
        else:
            return usage
        since = now_ms()
        order(line)
        ev = answer_of(since, ("follow", "error"))
        if ev is None:
            return "🚶 Sent, but no answer from the mod (offline?)"
        if ev["type"] == "error":
            return f"❌ The mod refused: {escape(str(ev.get('msg')))}"
        who = escape(str(ev.get("player") or ""))
        if ev.get("action") == "stays":
            return f"🧍 {escape(me())} stays here (following <b>{who}</b> again with !follow go)"
        if ev.get("action") == "follows again":
            return f"🚶 Following <b>{who}</b> again"
        if not ev.get("on"):
            return "🚶 Not following anyone" + (f" any more ({who})" if who else "")
        where = f"{ev.get('distance')} m away" if ev.get("visible") else f"not in sight: {escape(me())} waits until you show up"
        return f"🚶 Following <b>{who}</b>, {where}"
    if what in ("home", "sethome"):
        if rest and not HOME.match(rest):
            return usage
        return chat(f"/{what} {rest}".strip())
    if what == "assist":
        words = rest.lower().split()
        parts = {"dig": "dig", "chop": "chop", "crops": "harvest", "chests": "chests", "give": "give"}
        if not words:
            line = {"cmd": "help"}
        elif words[0] in ON + OFF and len(words) == 1:
            line = {"cmd": "help", "on": words[0] in ON}
        elif words[0] in parts and len(words) == 2 and words[1] in ON + OFF:
            line = {"cmd": "help", parts[words[0]]: words[1] in ON}
        else:
            return usage
        since = now_ms()
        order(line)
        ev = answer_of(since, ("helper", "error"), want=lambda e: e.get("type") == "error" or e.get("action") in ("status", "set"))
        if ev is None:
            return "🧰 Sent, but no answer from the mod (offline?)"
        if ev["type"] == "error":
            return f"❌ The mod refused: {escape(str(ev.get('msg')))}"
        def onoff(v):
            return "on" if v else "off"
        out = (f"🧰 Assist <b>{onoff(ev.get('on'))}</b>: tunnels {onoff(ev.get('dig'))}, trees {onoff(ev.get('chop'))}, "
               f"crops {onoff(ev.get('harvest'))} ({ev.get('fields_known', 0)} fields), "
               f"chests {onoff(ev.get('chests'))} ({ev.get('chests_known', 0)} known), "
               f"giving {onoff(ev.get('give', True))}")
        if ev.get("for"):
            out += f"\n👤 For <b>{escape(str(ev['for']))}</b>"
        if ev.get("harvesting"):
            out += f"\n🌾 Harvesting the field at {escape(str(ev['harvesting']))}"
        if ev.get("tunnel"):
            out += f"\n⛏ Digging along: {escape(str(ev['tunnel']))}"
        if ev.get("tree"):
            out += f"\n🌳 Chopping the tree at {escape(str(ev['tree']))}"
        if ev.get("gives"):
            out += f"\n🎁 Giving: {escape(str(ev['gives']))}"
        if ev.get("lacks"):
            out += f"\n🧺 Lacks: {escape(str(ev['lacks']))}"
        return out
    if what == "fields":
        words = rest.split()
        if not words:
            line = {"cmd": "fields"}
        elif words[0].lower() == "forget" and len(words) == 1:
            line = {"cmd": "fields", "forget": True}
        else:
            return usage
        since = now_ms()
        order(line)
        ev = answer_of(since, ("fields", "error"))
        if ev is None:
            return "🌾 Sent, but no answer from the mod (offline?)"
        if ev["type"] == "error":
            return f"❌ The mod refused: {escape(str(ev.get('msg')))}"
        if not ev.get("for"):
            return f"🌾 {escape(me())} follows nobody, so they work on nobody's fields"
        if not ev.get("count"):
            return f"🌾 {escape(me())} knows no fields of <b>{escape(str(ev['for']))}</b> yet: sow or harvest nearby and they learn them"
        rows = "\n".join("• " + escape(r) for r in str(ev.get("list", "")).split("; ") if r)
        return f"🌾 {ev['count']} field(s) of <b>{escape(str(ev['for']))}</b>:\n{rows}"
    if what == "forget":
        word = rest.lower().strip() or "all"
        if word not in ("all", "chests", "fields"):
            return f"❌ Like this: <b>{escape(DOC['forget'][0])}</b>"
        since = now_ms()
        order({"cmd": "forget", "what": word})
        ev = answer_of(since, ("forget", "error"))
        if ev is None:
            return "🧹 Sent, but no answer from the mod (offline?)"
        if ev["type"] == "error":
            return f"❌ The mod refused: {escape(str(ev.get('msg')))}"
        return (f"🧹 Forgotten for <b>{escape(str(ev.get('for')))}</b> on {escape(str(ev.get('profile')))}: "
                f"{ev.get('chests', 0)} chest(s), {ev.get('fields', 0)} field(s)")
    if what == "profile":
        since = now_ms()
        order({"cmd": "profile"})
        ev = answer_of(since, ("profile", "error"))
        if ev is None:
            return "🗺 Sent, but no answer from the mod (offline?)"
        if ev["type"] == "error":
            return f"❌ The mod refused: {escape(str(ev.get('msg')))}"
        name = ev.get("name") or "(none: no profile fits this server)"
        return (f"🗺 Profile <b>{escape(str(name))}</b> on {escape(str(ev.get('server')))}: {ev.get('commands', 0)} commands, "
                f"{ev.get('texts', 0)} texts, {ev.get('afterJoin', 0)} line(s) after joining, resource pack {escape(str(ev.get('resourcepack')))}")
    if what == "chests":
        words = rest.split()
        if not words:
            line = {"cmd": "chests"}
        elif words[0].lower() == "forget" and len(words) == 1:
            line = {"cmd": "chests", "forget": True}
        elif words[0].lower() == "add" and len(words) == 4:
            try:
                line = {"cmd": "chests", "learn": [int(float(v)) for v in words[1:]]}
            except ValueError:
                return usage
        else:
            return usage
        since = now_ms()
        order(line)
        ev = answer_of(since, ("chests", "error"))
        if ev is None:
            return "📦 Sent, but no answer from the mod (offline?)"
        if ev["type"] == "error":
            return f"❌ The mod refused: {escape(str(ev.get('msg')))}"
        if not ev.get("count"):
            return f"📦 {escape(me())} knows none of your chests yet: open one near them and they learn it"
        rows = "\n".join("• " + escape(r) for r in str(ev.get("list", "")).split("; ") if r)
        return f"📦 {ev['count']} chest(s) of yours:\n{rows}"
    if what == "guard":
        words = rest.lower().split()
        if not words:
            line = {"cmd": "guard"}
        elif words[0] in ON + OFF:
            line = {"cmd": "guard", "on": words[0] in ON}
        elif words[0] == "pvp" and len(words) == 2 and words[1] in ON + OFF:
            line = {"cmd": "guard", "pvp": words[1] in ON}
        else:
            return usage
        since = now_ms()
        order(line)
        ev = answer_of(since, ("guard", "error"))
        if ev is None:
            return "🛡 Sent, but no answer from the mod (offline?)"
        if ev["type"] == "error":
            return f"❌ The mod refused: {escape(str(ev.get('msg')))}"
        state = f"🛡 Protection <b>{'on' if ev.get('on') else 'off'}</b>, against players <b>{'on' if ev.get('pvp') else 'off'}</b>"
        return state + (f"\n⚔ Fighting {escape(str(ev.get('fights')))}" if ev.get("fights") else "")
    if what == "parkour":
        low = rest.lower()
        if rest and low not in OFF:
            return usage
        since = now_ms()
        order({"cmd": "parkour", "on": False} if rest else {"cmd": "parkour"})
        ev = answer_of(since, ("parkour", "error"))
        if ev is None:
            return "🏃 Sent, but no answer from the mod (offline?)"
        if ev["type"] == "error":
            if "parkour" in str(ev.get("msg")):
                return "🏃 Parkour is a module: the jar minecraft-buddy-tweakcraft is not in the buddy's mods folder"
            return f"❌ The mod refused: {escape(str(ev.get('msg')))}"
        act = ev.get("action")
        if act == "stop":
            return "🏃 Stopped" + (", and out of the game" if ev.get("out of the game") else "")
        if act == "refused":
            why = {"no parkour open": "no Parkour is open",
                   "not on this server": "the Tweakcraft module is not on for this server (its profile in buddy.json)",
                   "no finish set": "the server profile gives no finish for the course",
                   "already begun": "the Parkour that opened has begun already, or is over",
                   "with a prize": "this Parkour is one of the draw, with a prize; the buddy only plays a game players voted open"
                   }.get(ev.get("reason"), ev.get("reason"))
            return f"🏃 Not joining: {escape(str(why))}"
        def finished(e):
            if e is None:
                return None
            if e.get("type") == "chat":
                return "🏁 " + escape(str(e.get("text")))
            return f"🏃 The Parkour run ended: {escape(str(e.get('reason')))}"
        alt = alt_name()
        later(since, ("chat", "goto"), lambda e: (e.get("type") == "chat" and alt and alt in str(e.get("text"))
                                                   and ("over de finish" in str(e.get("text")) or "crosses the finish" in str(e.get("text"))))
              or (e.get("type") == "goto" and e.get("action") == "stop"), 600, finished)
        if ev.get("already in the game"):
            return f"🏃 {escape(me())} is in the game already and runs to the finish"
        return f"🏃 Joined Parkour: it starts in about {ev.get('starts in', 0)} s if enough players join, then {escape(me())} runs to the finish"
    if what == "goto":
        low = rest.lower()
        if low in OFF:
            line = {"cmd": "goto", "on": False}
        elif not rest:
            line = {"cmd": "goto"}
        else:
            parts = rest.split()
            try:
                x, y, z = (int(float(v)) for v in parts)
            except ValueError:
                return usage
            line = {"cmd": "goto", "x": x, "y": y, "z": z}
        since = now_ms()
        order(line)
        ev = answer_of(since, ("goto", "error"))
        if ev is None:
            return "🧭 Sent, but no answer from the mod (offline?)"
        if ev["type"] == "error":
            return f"❌ The mod refused: {escape(str(ev.get('msg')))}"
        if ev.get("action") == "stop":
            return "🧭 Stopped"
        if ev.get("action") == "start":
            return f"🧭 On the way: {ev.get('points')} spot(s), first {ev.get('x')} {ev.get('y')} {ev.get('z')}"
        return f"🧭 {ev.get('points', 0)} spot(s) to go" + (f", next {ev.get('x')} {ev.get('y')} {ev.get('z')}" if ev.get('points') else "")
    if what == "build" and rest.lower() == "status":
        since = now_ms()
        order({"cmd": "buildstatus"})
        ev = answer_of(since, ("buildstatus",))
        return "🧱 " + escape(ev["text"]) if ev else "❌ No answer from the mod (offline?)"
    if what in FARM_YIELD and rest.lower() == "status":
        return farm_report(what)
    if what == "cropfarm" and rest.lower().split(" ")[0] in ("crop", "plant"):
        item = rest.partition(" ")[2].strip()
        return set_crop(item) if item else usage
    if what in MODES:
        mode, low = MODES[what], rest.lower()
        if low in ("finish", "finishing") and mode in CAN_FINISH:
            return switch(mode, "finish", f"{what} finish")
        if low not in ON + OFF:
            return usage + f"\n{escape(DOC[what][1])}. More: <b>!help {what}</b>"
        return switch(mode, "on" if low in ON else "off", f"{what} {'on' if low in ON else 'off'}")
    if what in ("crouch", "jump"):
        low = rest.lower()
        if low not in ON + OFF:
            return usage
        since = now_ms()
        order({"cmd": "set", "key": "altJumping" if what == "jump" else "altCrouched", "value": low in ON})
        ok = answer_of(since, ("config_set",)) is not None
        return f"{'🦘 Jumping' if what == 'jump' else '🧎 Crouching'} {'on' if low in ON else 'off'}" + ("" if ok else "\n⚠ No answer from the mod (offline?)")
    if what == "dump":
        return dump(rest)
    if what == "schematics":
        return schematics()
    if what == "place":
        return place(rest)
    if what == "placements":
        return placement("list")
    if what == "unplace":
        return placement("remove")
    try:
        if what == "settings":
            return settings(rest)
        if what == "get":
            return get_setting(rest) if rest else usage
        if what == "set":
            name, _, value = rest.partition(" ")
            if not name or not value.strip():
                return usage
            return set_setting(name, value.strip())
    except Exception as e:
        return f"❌ Could not read or write the settings: {escape(str(e))}"
    return f"❓ Unknown command <b>!{escape(what)}</b>, see <b>!help</b>"


def pieces(text, limit=3800):
    """Telegram takes 4096 characters per message; split on line ends."""
    out, cur = [], ""
    for l in text.split("\n"):
        if cur and len(cur) + len(l) + 1 > limit:
            out.append(cur)
            cur = l
        else:
            cur = cur + "\n" + l if cur else l
    return out + [cur] if cur else out


def buddy_online():
    """Is the buddy in the game? Their status comes every five seconds while they are."""
    st = snapshot().get("status")
    return bool(st) and time.time() - st["t"] / 1000 < 30


BUDDY_OUT = queue.Queue()        # (time asked, line) for the buddy to say, one a second
MAX_LINES = 5                    # the most lines one Telegram message may make the buddy say


def buddy_writer():
    while True:
        at, line = BUDDY_OUT.get()
        if time.time() - at > 60:
            continue                 # a line that waited a minute is dropped, not said late
        order({"cmd": "chat", "text": line})
        time.sleep(1)


def buddy_say(text):
    """Ordinary talk in the window: the buddy says it in the game, line by line. Their own line comes back from the game."""
    lines = [" ".join(l.split()) for l in text.splitlines()]
    lines = [l for l in lines if l]
    if not lines or lines == ["/start"]:
        return None                  # Telegram's own /start when the chat with the bot is opened
    if len(lines) > MAX_LINES:
        return f"❌ More than {MAX_LINES} lines; nothing said"
    long = next((l for l in lines if len(l) > MAX_CHAT), None)
    if long:
        return f"❌ Too long for the game by {len(long) - MAX_CHAT} characters; nothing said"
    if not buddy_online():
        return f"⚠ {escape(me())} is not in the game right now: nothing said"
    for l in lines:
        BUDDY_OUT.put((time.time(), l))
    return None


def buddy_line(ev):
    """What of the buddy's events comes into the window: every line of the game's chat (the bar above the hotbar is no chat),
    whether they are connected, and what they lack to help."""
    t = ev.get("type")
    if t == "chat":
        text = str(ev.get("text") or "").strip()
        if not text:
            return None
        # the mod says what kind of line it is (0.4.168 on): a private message, a player's line, a join or leave the player
        # list already tells, or anything else; a mod of before that only knew the game's signed lines
        kind = ev.get("is") or ("chat" if ev.get("kind") == "chat" else "server")
        if kind in ("join", "leave"):
            return None
        if not show({"pm": "pm", "chat": "chat"}.get(kind, "server")):
            return None
        who, said = ev.get("player"), ev.get("said")
        if who and said is not None and kind == "chat":
            return f"<b>{escape(str(who))}</b> » {escape(str(said))}"
        return escape(text)
    if t in ("player_join", "player_leave"):
        name = str(ev.get("name") or "")
        # a plugin's figures in the player list have colour codes for names: no players
        if not show("joins") or not PLAYER.match(name):
            return None
        return f"➕ {escape(name)} came online" if t == "player_join" else f"➖ {escape(name)} went offline"
    if t in ("connection", "helper") and not show("buddy"):
        return None
    if t == "connection":
        a, srv = ev.get("action"), ev.get("server") or "the server"
        if a == "connected":
            return f"🟢 {escape(me())} is in the game on {escape(str(srv))}"
        if a == "logged out":       # the mod leaves the server itself (low health, a new session)
            why = ev.get("reason") or ""
            return f"🔴 {escape(me())} left {escape(str(srv))}" + (f": {escape(str(why))}" if why else "")
        return None
    if t == "helper" and ev.get("action") == "lacks" and ev.get("text"):
        return f"🧰 {escape(str(ev['text']))}"
    return None


# what comes into the chat, each on or off: "show" in the settings, set in the panel (Settings, Telegram) or with !show here
SHOW = (("chat", "players' chat", "the lines of players: what the game signs as a player's, or what the server profile's text chat matches"),
        ("pm", "private messages", "to and from the buddy: the server profile's texts pm and pmOut"),
        ("joins", "joins and leaves", "players coming online and going off, from the player list; the server's own lines for it "
                                      "stay out when the profile's texts join and leave match them"),
        ("server", "other server messages", "everything else the server says: deaths, broadcasts, plugin lines"),
        ("buddy", "the buddy's own news", "joining or leaving the server, and what they lack to help"))
_show = [None, {}]


def show(kind):
    """Whether this kind comes into the chat: read from the settings whenever they change; on unless set off."""
    try:
        m = os.stat(CFG_PATH).st_mtime_ns
    except OSError:
        m = None
    if m != _show[0]:
        try:
            with open(CFG_PATH, encoding="utf-8") as f:
                v = json.load(f).get("show")
            _show[1] = v if isinstance(v, dict) else {}
        except (OSError, ValueError, AttributeError):
            _show[1] = {}
        _show[0] = m
    return _show[1].get(kind, True) is not False


def set_show(changes):
    """!show: the settings file with "show" changed, written whole as the panel writes it (mode 600, a file renamed over
    the old), which the bridge reads without starting anew."""
    with open(CFG_PATH, encoding="utf-8") as f:
        cfg = json.load(f)
    sh = cfg.get("show") if isinstance(cfg.get("show"), dict) else {}
    sh.update(changes)
    cfg["show"] = sh
    tmp = CFG_PATH + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(json.dumps(cfg, ensure_ascii=False, indent=2) + "\n")
    os.replace(tmp, CFG_PATH)


def show_command(rest):
    """!show, !show joins off, !show all on."""
    words = rest.lower().split()
    names = [k for k, _, _ in SHOW]
    if len(words) == 2 and (words[0] in names or words[0] == "all") and words[1] in ON + OFF:
        on = words[1] in ON
        set_show({k: on for k in (names if words[0] == "all" else [words[0]])})
    elif words:
        return "❌ Like this: <b>!show</b>, <b>!show joins off</b> or <b>!show all on</b> (" + ", ".join(names) + ")"
    out = ["<b>What comes in here</b> (<b>!show name on</b> or <b>off</b>)"]
    out += [f"{'✅' if show(k) else '⬜'} <b>{k}</b>: {label}, {escape(why)}" for k, label, why in SHOW]
    out.append("\nThe server profile (panel: Settings, Server profiles) tells the kinds apart: its texts chat, join and leave, "
               "pm and pmOut. A line that matches none of them counts as a server message.")
    return "\n".join(out)


def handle(text, mc):
    """The answer in the window: a !command, or ordinary talk, which the buddy says in the game."""
    t = text.strip()
    if t.startswith("!"):
        return command(t, mc)
    return buddy_say(t)


SEEN, SEEN_LOCK = [], threading.Lock()
HINTED = set()        # who was told that the bot has no chat yet: once per sender while the bridge runs


def seen(m):
    """Who wrote in which chat to the bot: the last twenty, newest first, in bridge-status.json. The panel offers
    a chat from it to take as the window and a sender to make an admin, by the ids, which are what Telegram checks."""
    chat, who = m.get("chat") or {}, m.get("from") or {}
    name = lambda d: " ".join(x for x in (d.get("first_name"), d.get("last_name")) if x)
    e = {"bot": "main", "chat_id": str(chat.get("id", "")), "type": chat.get("type", ""),
         "chat": chat.get("title") or name(chat) or chat.get("username") or "",
         "user_id": str(who.get("id", "")), "user": name(who), "username": who.get("username") or "", "at": int(time.time())}
    with SEEN_LOCK:
        SEEN[:] = [x for x in SEEN if (x["bot"], x["chat_id"], x["user_id"]) != (e["bot"], e["chat_id"], e["user_id"])]
        SEEN.insert(0, e)
        del SEEN[20:]
        copy = list(SEEN)
    bridge_status(seen=copy)


def no_chat_hint(m):
    """A private message while the bot has no chat yet: says once to its sender where to choose this chat."""
    who = str((m.get("from") or {}).get("id") or "")
    if MAIN_CHAT or who in HINTED:
        return
    HINTED.add(who)
    send_to(m["chat"]["id"], "Seen. This bot has no chat yet: choose this one in the buddy's panel, Settings, Telegram.")


def send_to(chat_id, text):
    """One message to a chat that is not the window, at once (the window's own go through the queue)."""
    try:
        conn = http.client.HTTPSConnection("api.telegram.org", timeout=20)
        conn.request("POST", MAIN_PAD, json.dumps({"chat_id": chat_id, "text": text}), {"Content-Type": "application/json"})
        conn.getresponse().read()
        conn.close()
    except Exception as e:
        print("telegram reply:", e, flush=True)


def listener(token):
    """Fetches messages (long polling) and carries out what the admins ask in the window. A message elsewhere is only
    noted for the panel (seen), and a private one while there is no chat yet gets where to choose it."""
    offset = None
    conn = None
    while True:
        try:
            if conn is None:
                conn = http.client.HTTPSConnection("api.telegram.org", timeout=60)
            query = {"timeout": 25, "allowed_updates": json.dumps(["message"])}
            if offset:
                query["offset"] = offset
            conn.request("GET", f"/bot{token}/getUpdates?" + urllib.parse.urlencode(query))
            r = conn.getresponse()
            body = r.read().decode(errors="replace")
            if r.status != 200:
                why = ("another program reads this bot's messages too" if r.status == 409 else telegram_error(body))
                bridge_status(listen_error={"bot": "main", "at": int(time.time()), "code": r.status, "text": why})
                print("telegram listen:", r.status, why, flush=True)
                time.sleep(30)
                continue
            if STATUS.get("listen_error"):
                bridge_status(listen_error=None)
            data = json.loads(body)
            for u in data.get("result", []):
                offset = u["update_id"] + 1
                m = u.get("message") or {}
                chat = m.get("chat", {})
                seen(m)
                if not (MAIN_CHAT and str(chat.get("id")) == MAIN_CHAT):
                    if chat.get("type") == "private":
                        no_chat_hint(m)
                    continue
                mc = name_of(m.get("from") or {}, chat.get("type") == "private")
                if mc is None:
                    continue                    # not an admin: ignored without a word
                text = (m.get("text") or "").strip()
                try:
                    if m.get("document"):
                        answer = attachment(m["document"], m.get("caption"), token)
                        text = "[file] " + str(m["document"].get("file_name"))
                    elif text:
                        answer = handle(text, mc)
                    else:
                        continue
                except Exception as e:
                    answer = f"❌ That went wrong here: {escape(str(e))}"
                if answer:
                    print("←", mc, ":", text[:100], flush=True)
                    for part in (answer if isinstance(answer, list) else [answer]):
                        for piece in pieces(part):
                            send(piece)
        except Exception as e:
            print("telegram listen:", e, flush=True)
            try:
                conn.close()
            except Exception:
                pass
            conn = None
            time.sleep(5)


def tail(path):
    """New lines of a file; starts again when the mod replaces the file (a restart)."""
    f = None
    inode = None
    while True:
        try:
            st = os.stat(path)
            if f is None or st.st_ino != inode:
                if f:
                    f.close()
                f = open(path, "r", errors="replace")
                inode = st.st_ino
                f.seek(0, os.SEEK_END)      # only new lines
            # at a restart the mod empties events.jsonl without replacing it: our place would then lie past the end
            # and nothing would ever be read again. Back to the start.
            elif st.st_size < f.tell():
                f.seek(0)
        except FileNotFoundError:
            time.sleep(2)
            continue
        line = f.readline()
        if not line:
            time.sleep(0.2)
            yield None          # lets main empty the queue meanwhile
            continue
        yield line


def main():
    threading.Thread(target=restart_on_change, daemon=True).start()
    bridge_status(state="starting", chat=MAIN_CHAT)
    threading.Thread(target=whoami, args=("main", MAIN_TOKEN), daemon=True).start()
    if not MAIN_CHAT:
        # a bot but no window yet: only note who writes to it, so that the panel can offer that chat
        bridge_status(state="no chat", reason="choose the chat in the panel: send the bot a message first")
        print("no chat yet: noting who writes to the bot", flush=True)
        threading.Thread(target=listener, args=(MAIN_TOKEN,), daemon=True).start()
        while True:
            time.sleep(3600)
    threading.Thread(target=sender, daemon=True).start()
    threading.Thread(target=listener, args=(MAIN_TOKEN,), daemon=True).start()
    threading.Thread(target=site_writer, daemon=True).start()
    bridge_status(state="running")
    threading.Thread(target=buddy_writer, daemon=True).start()
    send(f"🟢 Bridge started: {escape(me())}'s chat in the game comes in here, and a line without ! they say; "
         "<b>!help</b> for their commands")
    # every line apart, as in the game; the sender bundles what waits when Telegram asks to slow down
    for l in tail(EVENTS):
        if l is None:
            continue
        try:
            r = buddy_line(json.loads(l))
        except Exception:
            r = None
        if r:
            send(r)


if __name__ == "__main__":
    sys.exit(main())
