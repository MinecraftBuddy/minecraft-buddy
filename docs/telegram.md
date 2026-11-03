# Telegram

The buddy's chat in a Telegram chat, both ways, and commands with a `!`.

## How it works

One Telegram chat is the buddy's: the lines of the game's chat they see come in there, a line you type there without a
`!` they say in the game (a line with `/` is a command they type), and a line with a `!` is a command for them; `!help` lists
them, among them `!follow`, `!guard`, `!assist`, `!chests`, `!fields`, `!forget`, `!profile`, `!goto`, `!dump`, `!status`,
`!cmd /command` and `!say text`. Only the admins are heard. It is set up in the panel (Settings, Telegram), which has a main switch: off, the bridge says
nothing and Telegram is hidden everywhere in the panel. Make a bot with @BotFather, paste its token, send the bot a message from the chat you want, and pick that chat from who wrote to it;
Trust there adds the sender to Trusted players under Access, by their Telegram id. The bridge is `bridge/chat2telegram.py`;
it runs in the buddy's container and reads their events (`events.jsonl`) itself. One bot does it all, in the one chat you
picked: a message anywhere else is only listed for picking, and a private message while no chat is picked gets, once,
where to pick it. Check the token asks Telegram which bot a token is and sends nothing; Send a test line puts one line in the chat. Telegram lets one program at a time read a bot's messages: give each buddy a bot of its own.

## What comes in

Five kinds, each on or off: in the panel (Settings, Telegram, What comes into the chat) or in the chat itself with
`!show` (lists them), `!show joins off` or `!show all on`. A change counts at once; the bridge does not start anew for it.

| Kind | What |
|---|---|
| `chat` | players' chat |
| `pm` | private messages to and from the buddy |
| `joins` | players coming online and going offline, from the player list |
| `server` | everything else the server says: deaths, broadcasts, plugin lines |
| `buddy` | the buddy's own news: joining or leaving the server, what they lack |

The [server profile](profiles.md) tells the kinds apart, so set its texts for the server: `chat` (how a player's line
looks), `join` and `leave` (the server's own lines for it, which then stay out of the chat: joins come from the player
list), and `pm` and `pmOut`. A line that matches none of them counts as a server message. A line the game itself marks
as a player's is chat without a text; many servers send players' lines as their own messages, and then only the `chat`
text tells them apart. On a server whose lines look like `[Admin] Name » text`, for instance, `chat` is
`^(?:\[[^\]]*\] )*{player} » (.*)$`, and with join lines like `[+] Name`, `join` is `^\[\+\] {player}$` and `leave`
`^\[-\] {player}$`.

← [Back to the README](../README.md)
