# Minecraft Buddy: Tweakcraft

Tweakcraft's module of the buddy: a jar of its own (`minecraft-buddy-tweakcraft-<version>+mc26.3.jar`) next to the
core's. It does nothing on a server whose profile does not name it.

## Parkour

`{"cmd":"parkour"}`, `!parkour` in Telegram: he joins a Parkour that two players voted open with `/eventvote` (such a
game has no prize) and runs from the start to the finish. With no Parkour open, or one of the half-hourly draw (which pays
its winner), he does nothing and says why. `{"cmd":"parkour","on":false}` stops the run and takes him out of the game
with the profile's `eventLeave` command.

In the server's profile in `buddy.json`:

```json
"modules": {
  "parkour": { "dim": "events:arenas", "finish": [1676, 104, 1] }
}
```

`finish` is the block of the finish plate of the server's parkour course: change it with the course. Optional: `join`, the
command that joins an open game (`/event join`), and regular expressions for the server's lines when they ever change:
`opened` (`Event: Parkour\.`), `voted` (`Gekozen door.*voor de lol|Chosen by.*for fun`) and `calledOff`
(`Parkour (gaat niet door|is cancel+ed)`).

Events: `parkour` `joins` (`starts in`, `already in the game`), `refused` (`not on this server`, `no finish set`,
`no parkour open`, `already begun`, `with a prize`), `stop` (`out of the game`).
