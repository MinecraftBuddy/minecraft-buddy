# The exchange folder

How the panel, the bridge and your own scripts talk to the mod: files in one folder.

## Files and commands

Everything outside the game talks to the mod through files in the exchange folder, in English:

| File | What |
|---|---|
| `buddy.json` | the settings |
| `commands.jsonl` | commands to the mod, a JSON line each (appended) |
| `events.jsonl` | what the mod reports, a JSON line each; emptied at every start of the game |
| `state.json`, `map.json` | the buddy's state and the map, while the panel asks for them |
| `steer.json` | the panel's keys while you steer |
| `follow.json` | whom they follow, and whether they were told to stay, over a restart |
| `chests.json`, `fields.json`, `furnaces.json`, `base.json` | the chests, fields, furnaces and bed they learnt, per server and player |
| `containers.json` | what lay in every container they had open, by block, per server and dimension (the panel's 3D view shows it) |
| `pens.json` | the pen they farm per server and player, to take it up again after a restart |
| `obtain.json`, `obtain-items.json` | the order under way, to take it up again after a restart; what can be ordered (written once a start) |
| `pen.txt` | what they made of the last pen they looked at: its evidence, the best spot per side, its layers from above |
| `build.json` | the builder's material chests, scaffold and progress, and per placement the items it leaves out (`ignore`) |
| `materials.json` | the material list of the placement last asked for (`placement` with `materials`) |
| `routes.json`, `homewalks.json` | their best time per route; where they walk on after a home |
| `yield.jsonl` | what each farm brought in, a line per farm every five minutes, kept for good |
| `menus.jsonl`, `recording.jsonl` | with `menuLog` on: every menu the server opens, and what they did and saw |
| `commandtree.json`, `blocks.txt`, `clicks.csv` | on request: the server's commands, the blocks round a spot, the intervals between hits |
| `events-archive/` | the events of every earlier session of the game, gzipped |

Every name, key and value there is English, and so is every command with its fields: the mod writes its events as the
code names them and takes nothing else (since 0.4.166). A selection of the commands:

| Command | What |
|---|---|
| `{"cmd":"follow","player":"Name"}`, `{"cmd":"follow","on":false}`, `{"cmd":"follow","stay":true}` | follow, stop following, stay |
| `{"cmd":"goto","x":10,"y":64,"z":-20}` | walk to a spot |
| `{"cmd":"chat","text":"/menu"}` | type a line or a command |
| `{"cmd":"help","dig":true}` | a job on or off (`dig`, `chop`, `harvest`, `chests`, `give`) |
| `{"cmd":"chests"}`, `{"cmd":"fields"}` | what they know |
| `{"cmd":"forget","what":"all"}` | forget this server (or `chests`, `fields`, `furnaces`, `home`) |
| `{"cmd":"profile"}` | which profile holds |
| `{"cmd":"state"}` | write `state.json` and `map.json` for 30 s (the panel does this) |
| `{"cmd":"world3d","watch":true}` | for 8 s: the 3D view's positions file every tick and `state.json` four times a second, as while they are played (the panel sends it every four seconds while a Take control window is open); the positions file then also names the blocks broken around them (`breaks`, each `[x, y, z, state]`) |
| `{"cmd":"invclick","slot":3,"button":0,"mode":"pickup"}` | one click in their inventory (0-35 inventory, 36-39 boots to helmet, 40 off hand, -999 outside) |
| `{"cmd":"invclick","slot":12,"button":0,"mode":"pickup","menu":4}` | one click in the open menu with that id (`menu.id` in `state.json`), by the menu's own slot numbers; refused when that menu is closed |
| `{"cmd":"menuclose"}` | shut the open menu |
| `{"cmd":"select","slot":2}` | take that hotbar slot in hand |
| `{"cmd":"map","mode":"cave"}` | the map's layer: `auto`, `surface`, `cave` |
| `{"cmd":"mode","mode":"base"}`, `{"cmd":"mode","mode":"follow","player":"Name"}`, `{"cmd":"mode","mode":"build"}`, `{"cmd":"mode","mode":"afk"}` | tend the base (walks home first), follow, build, AFK (another mode ends it) |
| `{"cmd":"goto","x":1676,"y":104,"z":1}` | walk there, jumps and all (the parkour course by its finish alone); their best time per route is kept in `routes.json` |
| `{"cmd":"setting","name":"baseHarvest","value":false}` | one switch of a mode or of AFK mode (`afk`, `lookAround`, `wander`, `afkCrouch`, `crouchAlong`, `menuLog`, and the numbers `wanderMaxBlocks` and `wanderEveryMin`; see [CLAUDE.md](../CLAUDE.md)) |
| `{"cmd":"switch","what":"wood","on":true}` | a farm on or off: `mobfarm`, `raid`, `wood`, `harvest` (the crop under their crosshair), `wheat` (a whole field) |
| `{"cmd":"placement","action":"place","file":"house.litematic","here":true,"rotate":90,"mirror":"left-right"}` | place a schematic from the schematics folder with its start block (Litematica's origin) where they stand, or at `x` `y` `z`; `rotate` 90, 180 or 270 clockwise, `mirror` `left-right` or `front-back` |
| `{"cmd":"placement","action":"move","name":"house","x":10,"y":64,"z":-20}` | that placement's start block somewhere else (or `"here":true`); without `name` the selected one, or the only one; refused for a placement locked in Litematica |
| `{"cmd":"placement","action":"materials","name":"house"}` | write that placement's material list to `materials.json` |
| `{"cmd":"placement","action":"ignore","name":"house","item":"minecraft:glass_pane","on":true}` | leave that item's blocks out of the build (`"on":false` takes them back); the list anew |
| `{"cmd":"placement","action":"list"}`, `{"cmd":"placement","action":"remove"}`, `{"cmd":"placement","action":"remove","name":"house"}` | report the placements; remove every one, or one |
| `{"cmd":"renderdistance","chunks":12}` | how far the game sees and asks the server for chunks (2 to 32), saved in its options |
| `{"cmd":"woodscan"}` | measure the tree farm they stand in: its plots and chests go into `buddy.json` |
| `{"cmd":"obtain","item":"diamond_block","count":4,"by":"Name"}`, `{"cmd":"obtain","stop":true}`, `{"cmd":"obtain"}` | fetch an item for that player (or keep it); stop; what they are on (an `obtain` event, `status`). `"plan":true` only works the steps out (event `plan`) |
| `{"cmd":"leave"}`, `{"cmd":"join"}` | off the server and staying off, and back |
| `{"cmd":"drop","slot":3}` | throw that stack |
| `{"cmd":"ping"}` | answers `pong` |
| `{"cmd":"homewalk","home":"iron","x":100,"y":64,"z":-200}`, `{"cmd":"homewalk","home":"iron","on":false}`, `{"cmd":"homewalk"}` | after `/home iron` on this server walk on to that spot (a home that may not stand in a claim); forget it; list the walks of this server (`homewalks.json`) |
| `{"cmd":"pen"}`, `{"cmd":"pen","on":false}`, `{"cmd":"pen","on":true}` | how the farm stands; stop it (as the sign does); take up the remembered pen when they stand at it |
| `{"cmd":"pen","probe":true,"x":10,"y":64,"z":-20,"mob":"zombie"}` | only recognise, as if such a mob stood in that block, and write `pen.txt` |
| `{"cmd":"pen","x":10,"y":64,"z":-20}` | a test: start there for the player they follow, as a punch would |
| `{"cmd":"pen","hit":false}`, `{"cmd":"pen","trace":true}`, `{"cmd":"pen","safe":false}` | test switches: no blows, a `trace` event every four ticks, the sweep rule off |

[CLAUDE.md](../CLAUDE.md) has every command, event and setting.

← [Back to the README](../README.md)
