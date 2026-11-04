# Server profiles and modules

The buddy knows no server out of the box. What differs per server is a profile in `buddy.json`; what only one server
has is a module.

## In the panel

Settings, **Server profiles** edits them. Every field has a line under it that says what it does, and the editor
explains every command and server line the buddy knows. The **Example** at the bottom of the list is the panel's own
and is never saved: it has every field filled in (the lines are what EssentialsX and WorldGuard send in English). Copy
it, or any other profile, to start a new one for a server that is almost the same; give the copy its own address,
since the first profile that fits wins.

A server saved in the panel (Getting started, or Settings, Status) that no profile fits gets one at once, named after
the server and with its address, for you to fill in.

## Profiles

`profiles` in `buddy.json`: one entry per server; the first that fits wins. On a server without a profile the buddy
does only what needs no command or server line.

```json
"profiles": [
  {
    "name": "example-smp",
    "address": "play.example.net",
    "withCommand": "huskhomes",
    "resourcepack": "accept",
    "afterJoin": [],
    "commands": { "tpa": "/tpa {player}", "tpahere": "/tpahere {player}", "tpaccept": "/tpaccept {player}",
                  "afk": "/afk", "sethome": "/sethome", "home": "/home", "homes": "/homes",
                  "msg": "/msg {player} {text}", "eventLeave": "/event leave" },
    "texts": { "tpahere": "^(\\.?[A-Za-z0-9_]{2,16}) asks you to teleport to them" },
    "eventDims": ["events:"],
    "voidOk": ["events:arenas"],
    "sleepFrom": 12542,
    "modules": { "parkour": { "dim": "events:arenas", "finish": [120, 70, -40] } }
  },
  {
    "name": "some-lobby",
    "address": "example.net",
    "withoutCommand": "sethome",
    "afterJoin": ["/server survival"]
  }
]
```

- **Recognising a server**: `address` is part of the address you join with. Only where two servers share one address
  behind a proxy, `withCommand` / `withoutCommand` tell them apart by a command one has and the other not. The profile
  is chosen again at every switch of server.
- **`afterJoin`** (optional, only for a proxy with a lobby): lines typed after joining; repeated after 40 s, then less
  often, while the same profile still holds. The lobby and the server behind it need a profile each.
- **`resourcepack`**: `accept` or `decline`, the answer when the server offers its resource pack. A required pack is
  always accepted.
- **`commands`**: `{player}` is a player, `{text}` a message. A command left out is not there: the buddy walks instead
  of `/tpa`, waits on the spot instead of `/afk`, and so on.
- **`texts`**: the server's chat lines as regular expressions (`{player}` for a name), in the language the server shows
  the buddy; on a server where players choose a language, in the buddy's choice. The names are in
  [Lines the server sends](#lines-the-server-sends). Where `tpahere` is missing, the server's own clickable accept
  button in chat is used. Every chat line with a clickable command is reported (`profile` `clickable`), which helps to
  fill in a profile for a new server.
- **`sleepFrom`**: the game time from which the buddy lies down on a clear night when others sleep. Left out, the
  vanilla moment. Only a server whose sleep plugin counts a sleeper later needs it. The panel shows and takes it as a
  time on a clock, rounded up to the minute (**Sleep from**: the vanilla moment is 18:32 there); in `buddy.json` it is a
  tick of the day: the vanilla 12523, and 12542 for a plugin that starts at 18:33.
- **`modules`**: the server modules on for this server, each with its settings (next section).
- **Memory per profile**: chests, fields, furnaces and the home are kept per profile.

### Commands

| Name | What the buddy uses it for |
|---|---|
| `tpa` | ask the player being followed for a teleport to that player |
| `tpahere` | ask a player to come to the buddy |
| `tpaccept` | accept the request of the player being followed; without `{player}` the player's name is put behind it, since a bare accept takes the newest request, whoever sent it |
| `tpdeny` | refuse a request |
| `afk` | go to the server's AFK place when nothing needs doing |
| `sethome` | set home, at the bed you tap |
| `home`, `homes` | go home; the list of homes (a menu or a chat list) |
| `msg` | a private message (`{player}`, `{text}`). Colour codes and control characters are taken out of every line the buddy sends, and a line stops at 256 characters, so a plugin line passed on as an answer cannot get the buddy kicked |
| `eventLeave` | leave a minigame |

### Lines the server sends

| Name | The line that says |
|---|---|
| `tpa` | a player asks to teleport to the buddy |
| `tpahere` | a player asks the buddy to teleport to that player (accepted from the player being followed) |
| `chat` | a player's public chat line (`{player}` and the text): what Telegram shows as players' chat |
| `relay` | a chat line from outside the game that the server passes on, such as IRC or Discord (the name and the text): Telegram shows it with players' chat, as the server shows it. `{player}` takes only a Minecraft name; `([^>]+)` takes any IRC nick, as in `^\[IRC\] <([^>]+)> (.*)$` |
| `join`, `leave` | the server's own line when a player comes online or goes offline: kept out of Telegram, which takes joins and leaves from the player list |
| `pm`, `pmOut` | a private message to the buddy (`{player}` and the text); the buddy's own as the server shows it. Both count only from the start of a line, so a player cannot type one in public chat |
| `accepted`, `declined`, `expired` | the buddy's teleport request was accepted, refused, or ran out |
| `teleportComing`, `teleported` | a teleport is about to happen; the teleport took place |
| `afkAlready` | already AFK |
| `fightOn`, `fightOff` | in combat, or out of it (a combat plugin) |
| `cancelledMoved`, `cancelledDamage` | a teleport was cancelled because of a move, or a hit |
| `afkInFight`, `afkInEvent` | `/afk` was refused in combat, or in a minigame |
| `noPermission`, `noChestAccess`, `noBuildAccess` | breaking here, opening this chest, or building here is not allowed |
| `nextPage` | the name of a menu's button to the next page |

## Server modules

What one server alone has is a module, a jar of its own next to the core's, so that the buddy can be used without it.
A module does something only on a server whose profile names it under `modules`, and takes what it needs to know of
that server (where a course is, which line opens a game) from there: a module holds no coordinates.

| Module | Jar | What it does | Settings in the profile |
|---|---|---|---|
| Tweakcraft | `minecraft-buddy-tweakcraft-<version>.jar` | `{"cmd":"parkour"}` (`!parkour` in Telegram): joins a Parkour that two players voted open (no prize) and runs to the finish; refuses when none is open or when it is one of the half-hourly draw; `"on": false` stops and leaves the game | `parkour`: `dim`, `finish` `[x, y, z]`; optional `join` (the command) and the expressions `opened`, `voted`, `calledOff` |

A module is a Gradle subproject under `modules/<name>` that implements `BuddyModule`;
[modules/tweakcraft](../modules/tweakcraft/README.md) is one.

← [Back to the README](../README.md)
