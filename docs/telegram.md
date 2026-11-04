# Telegram

The buddy's chat in a Telegram chat, both ways, and commands with a `!`.

## How it works

One Telegram chat is the buddy's. The buddy's game chat shows up there, and in that chat:

- a plain line: the buddy says it in the game chat (up to five lines, one a second);
- a line starting with `/`: the buddy types it as a command, such as `/msg` or `/home`;
- a line starting with `!`: a command for the buddy, never said in the game. `!help` lists them, among them
  `!follow`, `!guard`, `!assist`, `!chests`, `!fields`, `!forget`, `!profile`, `!goto`, `!dump`, `!status`,
  `!cmd /command` and `!say text`.

Only the admins are heard: the Telegram user ids in Trusted players (Settings, Access).

## Setting it up

In the panel, Settings, Telegram:

1. Make a bot with @BotFather and paste its token. The panel can check a token: it asks Telegram which bot it is and
   sends nothing.
2. Send the bot a message from the chat you want, and pick that chat from the list of who wrote to the bot. Trust there
   adds the sender to Trusted players, by Telegram user id.
3. Send a test line to see that it arrives.

The section has a main switch: off, the bridge says nothing and Telegram is hidden everywhere in the panel. The bridge
runs in the buddy's container and answers only in the chat you picked. A message anywhere else is only listed for
picking, and a private message while no chat is picked gets, once, a note on where to pick it.

Every buddy needs a separate bot: Telegram lets one program at a time read a bot's messages.

## What comes in

Seven kinds, each on or off, in Settings, Telegram or with `!show` in the chat (`!show` lists them, `!show joins off`,
`!show all on`). A change counts at once.

| Kind | What |
|---|---|
| `chat` | players' chat |
| `pm` | private messages to and from the buddy |
| `joins` | players coming online and going offline, from the player list |
| `deaths` | players dying, as the server says it; for the buddy's own death, where it happened |
| `advancements` | players making an advancement |
| `server` | everything else the server says: broadcasts, plugin lines |
| `buddy` | the buddy's own news: joining or leaving the server, a kick or a lost connection with the server's reason (once, not at every try to join again), what is lacking |

The [server profile](profiles.md) tells the kinds apart, so set its texts for the server: `chat` (how a player's line
looks), `relay` (chat from outside the game the server passes on, such as IRC or Discord: it comes in with players'
chat, as the server shows it), `join` and `leave` (the server's own lines for it, which then stay out of the chat: joins
come from the player list), `death` and `advancement` (a plugin's own lines for them), and `pm` and `pmOut`. The game's own
death messages and advancements need no text: the mod knows them on any server and in any language. A line the game itself marks as a player's is chat even without a
`chat` text; any other line that matches none of them is a server message. Many servers send players' lines as server messages, and then only
the `chat` text tells them apart. On a server whose lines look like `[Admin] Name » text`, for instance, `chat` is
`^(?:\[[^\]]*\] )*{player} » (.*)$`, and with join lines like `[+] Name`, `join` is `^\[\+\] {player}$` and `leave`
`^\[-\] {player}$`.

← [Back to the README](../README.md)
