# The exchange folder

How the panel, the bridge and your own scripts talk to the mod: files in one folder.

## Files and commands

Everything outside the game talks to the mod through files in the exchange folder:

| File | What |
|---|---|
| `buddy.json` | the settings |
| `commands.jsonl` | commands to the mod, a JSON line each (appended) |
| `events.jsonl` | what the mod reports, a JSON line each; emptied at every start of the game |
| `state.json`, `map.json` | the buddy's state and the map, while the panel asks for them. In the game the state is written when it changes and at least every two seconds (`beat`, in milliseconds), so that its time `t` tells whether the game is alive; off a server it says `"online": false`, two seconds after leaving and at every ask |
| `steer.json` | the panel's keys while you steer |
| `follow.json` | whom to follow and whether to stay, kept over a restart |
| `chests.json`, `fields.json`, `furnaces.json`, `base.json` | the chests, fields, furnaces and bed the buddy knows, per server and player |
| `containers.json` | what lay in every container the buddy had open, by block, per server and dimension (the panel's 3D view shows it) |
| `pens.json` | the pen being farmed per server and player, to take it up again after a restart |
| `pen.txt` | the buddy's reading of the last pen: its evidence, the best spot per side, its layers from above |
| `build.json` | the builder's material chests, scaffold and progress, and per placement the items it leaves out (`ignore`) |
| `materials.json` | the material list of the placement last asked for (`placement` with `materials`) |
| `routes.json`, `homewalks.json` | the best time per route; where to walk on after a home |
| `yield.jsonl` | what each farm brought in, a line per farm every five minutes, kept for good |
| `menus.jsonl`, `recording.jsonl` | with `menuLog` on: every menu the server opens, and what the buddy did and saw |
| `commandtree.json`, `blocks.txt`, `clicks.csv` | on request: the server's commands, the blocks round a spot, the intervals between hits |
| `events-archive/` | the events of every earlier session of the game, gzipped |

Every name, key and value is English. A selection of the commands:

| Command | What |
|---|---|
| `{"cmd":"follow","player":"Name"}`, `{"cmd":"follow","on":false}`, `{"cmd":"follow","stay":true}` | follow, stop following, stay |
| `{"cmd":"goto","x":10,"y":64,"z":-20}` | walk to a spot, jumping where needed (a parkour course too, given only its finish); the best time per route is kept in `routes.json` |
| `{"cmd":"chat","text":"/menu"}` | type a line or a command |
| `{"cmd":"help","dig":true}` | a job on or off (`dig`, `chop`, `harvest`, `chests`, `give`) |
| `{"cmd":"chests"}`, `{"cmd":"fields"}` | what the buddy knows |
| `{"cmd":"forget","what":"all"}` | forget this server (or `chests`, `fields`, `furnaces`, `home`) |
| `{"cmd":"profile"}` | which profile holds |
| `{"cmd":"state"}` | write `state.json` and `map.json` for 30 s (the panel does this) |
| `{"cmd":"world3d","watch":true}` | for 8 s: the 3D view's positions file every tick, with the blocks broken around the buddy (`breaks`, each `[x, y, z, state]`), and `state.json` four times a second; the panel sends it every four seconds while Take control is open |
| `{"cmd":"invclick","slot":3,"button":0,"mode":"pickup"}` | one click in the buddy's inventory (0-35 inventory, 36-39 boots to helmet, 40 off hand, -999 outside) |
| `{"cmd":"invclick","slot":12,"button":0,"mode":"pickup","menu":4}` | one click in the open menu with that id (`menu.id` in `state.json`), by the menu's own slot numbers; refused when that menu is closed |
| `{"cmd":"menuclose"}` | shut the open menu |
| `{"cmd":"select","slot":2}` | take that hotbar slot in hand |
| `{"cmd":"map","mode":"cave"}` | the map's layer: `auto`, `surface`, `cave` |
| `{"cmd":"mode","mode":"base"}`, `{"cmd":"mode","mode":"follow","player":"Name"}`, `{"cmd":"mode","mode":"build"}`, `{"cmd":"mode","mode":"afk"}` | tend the base (walks home first), follow, build, AFK (another mode ends it) |
| `{"cmd":"setting","name":"baseHarvest","value":false}` | one switch of a mode or of AFK mode (`afk`, `lookAround`, `wander`, `afkCrouch`, `crouchAlong`, `menuLog`, and the numbers `wanderMaxBlocks` and `wanderEveryMin`); `!settings` in Telegram lists every setting |
| `{"cmd":"switch","what":"wood","on":true}` | a farm on or off: `mobfarm`, `raid`, `wood`, `harvest` (the crop under the crosshair), `wheat` (a whole field) |
| `{"cmd":"placement","action":"place","file":"house.litematic","here":true,"rotate":90,"mirror":"left-right"}` | place a schematic from the schematics folder with its start block (Litematica's origin) where the buddy stands, or at `x` `y` `z`; `rotate` 90, 180 or 270 clockwise, `mirror` `left-right` or `front-back` |
| `{"cmd":"placement","action":"move","name":"house","x":10,"y":64,"z":-20}` | that placement's start block somewhere else (or `"here":true`); without `name` the selected one, or the only one; refused for a placement locked in Litematica |
| `{"cmd":"placement","action":"materials","name":"house"}` | write that placement's material list to `materials.json` |
| `{"cmd":"placement","action":"ignore","name":"house","item":"minecraft:glass_pane","on":true}` | leave that item's blocks out of the build (`"on":false` takes them back), and write the material list again |
| `{"cmd":"placement","action":"list"}`, `{"cmd":"placement","action":"remove"}`, `{"cmd":"placement","action":"remove","name":"house"}` | report the placements; remove every one, or one |
| `{"cmd":"renderdistance","chunks":12}` | how far the game sees and asks the server for chunks (2 to 32), saved in its options |
| `{"cmd":"woodscan"}` | measure the tree farm the buddy stands in: its plots and chests go into `buddy.json` |
| `{"cmd":"leave"}`, `{"cmd":"join"}` | off the server and staying off, also when the game starts again, until `join`; back to the last server |
| `{"cmd":"drop","slot":3}` | throw that stack |
| `{"cmd":"ping"}` | answers `pong` |
| `{"cmd":"homewalk","home":"iron","x":100,"y":64,"z":-200}`, `{"cmd":"homewalk","home":"iron","on":false}`, `{"cmd":"homewalk"}` | after `/home iron` on this server, walk on to that spot (for a home that may not stand in a claim); forget it; list the walks of this server (`homewalks.json`) |
| `{"cmd":"pen"}`, `{"cmd":"pen","on":false}`, `{"cmd":"pen","on":true}` | how the farm stands; stop it (as the sign does); take up the remembered pen when standing at it |
| `{"cmd":"pen","probe":true,"x":10,"y":64,"z":-20,"mob":"zombie"}` | only recognise, as if such a mob stood in that block, and write `pen.txt` |
| `{"cmd":"pen","x":10,"y":64,"z":-20}` | a test: start there for the player being followed, as a punch would |
| `{"cmd":"pen","hit":false}`, `{"cmd":"pen","trace":true}`, `{"cmd":"pen","safe":false}` | test switches: no blows, a `trace` event every four ticks, the sweep rule off |

The rest are in the mod's source: `AfkHelperClient.java` handles the commands, and every setting is a field of
`AfkConfig.java`.

← [Back to the README](../README.md)
