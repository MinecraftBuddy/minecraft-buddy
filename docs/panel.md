# The web panel

The buddy's page in the browser: watch them, steer them, use their inventory, and set everything up.

## What is on it

The buddy's domain (the label `caddy` of their block in `compose.yaml`). The bar at the top shows the buddy's name and their own skin, whether they are online and on which server, what they do **now**, a button to take
them off the server or back on, and the way to **Settings** (in the settings the same button goes back to the **Overview**). On a phone the parts come one at a time, chosen in a bar at
the bottom (Map, Inventory, Chat, Settings).

- **Priorities**: the steps in the order they get the buddy's hands (stay safe, your instructions, fight, save a player's
  things, bring things home, tend the base, sleep, help you work, follow). The one that has them now is lit, with what
  the buddy does; the ones below it wait.
- **Mode**: the buddy's ways of spending their time, each with its own switches. A mode is chosen with its circle (or its
  name): that opens it grey, with **Confirm switch** beside its name, so its switches, an amount or a schematic can be set
  first; only the confirm switches, and the circle again closes it. **AFK** keeps them where they are doing nothing
  else, for instance to keep a farm's chunks loaded: **Look around** looks at players who come close, **Walk about**
  takes a few steps and back now and then so that a server's AFK check sees them move (how many blocks and how often are
  set under it), **Stay crouched**. **Tend the base** (harvest fields, run furnaces, store in chests, fight monsters,
  sleep at night, shear sheep, herd strays) walks them home and keeps it. **Follow a player** (mine, chop and farm with
  you, guard you, pick up your drops, farm mobs, dig when blocked, bridge and pillar, crouch along) follows the name you
  type or pick. **Build a schematic** starts the builder on the placed schematic nearest to them; opened, it places an
  uploaded file (**Place a schematic**: the file, its **start block** where they stand or at x y z, the turn and the mirror;
  the start block is the block that lands on that spot, the schematic turns and mirrors around it), and lists each placed
  schematic with its size, turn and mirror and its start block, which a click shows on the map and the fields under it
  move, typed or **To where they stand**. **Material list** opens what it takes per item, as Litematica counts it (in all,
  still missing where it is loaded, what they carry, what the chests they build from held); an item ticked **ignore** is
  left out when they build. **Remove** takes the placement away; the file stays. Switching to another mode ends AFK. A **?** beside a
  mode or switch says how it is set in the game (the bed you tap for their base, the sign to follow, the punch for a mob
  farm). A switch counts at once and is kept in their config; the switches of a mode that is not on wait for it.
- **What they are missing**: what the buddy lacks to help (food, a weapon, room, a tool about to break, the right tool or torches for
  a job), above the map, for as long as it lasts.
- **Map**, live: 128 by 128 blocks around the buddy, north up, coloured as Xaero's minimap draws it (grass, leaves and water
  in the biome's colours, shaded by height, deeper water darker). On it their bed, chests and fields, the players and
  monsters near them, an arrow for the buddy and the way they face, the buddy's position in the top corner, and the
  game's day and time (as F3 counts the day) in the bottom corner, in 2D and 3D alike. Zoom in, drag to look
  around, the round button puts them back in the middle. `Auto` shows the layer they are on once they are deep under rock or
  in the nether (`Surface` and `Cave` choose). Around it, dimmer, what they saw before: always, also with the page shut, they draw the chunks around them into
  `maptiles/` in the exchange folder, again each time they come by. Zoom out to 512 blocks; **Back to** takes the map to
  them again, and a coordinate in Problems or Activity takes it there. Click a spot and **Walk there** sends them.
  **2D / 3D** switches the map: the 3D map is dragged with the left button, turned and tilted with the right (on a
  touchpad, hold `Shift`, `Option` or `Ctrl` and drag), zoomed with a double click (in) and a double right click (out)
  on the spot, and by the wheel, two fingers, a pinch and the buttons; the round button puts them in the middle again,
  north up. A click on a block offers **Walk there** too, also underground, where the map looks through the rock above
  them; a click beside that menu shuts it.
  **Take control** opens watching them through their eyes (`Play`), with the keys explained at the top (**Hide keys**
  folds them away): you see them turn, hit, eat and break blocks (cracks, and the bits flying), and the chest or menu
  they open, as they do it, whether they work by themselves or someone else plays them. `F5`, `F1` and `F3` work while
  watching too. **Around them** and **Fly** look about while they stand still. One person plays them at a time: while
  another window plays them, a click on the view says "Someone else (at their address) is controlling them at the
  moment", their inventory shows over the view while that person has it open, and nothing you click there reaches the
  game (nor the clicks in the overview's inventory). Click the view to take over, `W A S D` walk, the mouse turns them, `Space` jumps, `Shift`
  sneaks, `R` or `W` twice quickly sprints, the left button hits or breaks, the right button uses (held: eating, a bow, a shield), `1` to
  `9` or the wheel pick a hotbar slot, `E` the inventory (shut it and what is on the cursor goes back; a click on the
  world beside it throws what is on the cursor), `T` or `/` the chat, `Tab` who is online, `F5` the view behind and in front of them, `F1` hides the HUD, `F3` what they look at, `Esc`
  hands them back and you watch again; **← Overview** at the top left and **Done** at the top right end it. Their coordinates and the game's
  day and time stand at the top left. **Game**, **Bright** and **Full** set how bright the 3D view is: the game's own
  brightness, its brightest, or as with Night Vision so that dark caves can be seen into (Full by default; the choice is
  kept in the browser and holds for the 3D map too). Blocks, light and night, mobs, players with their skins and armour, what each holds and a sign's text
  are drawn as the game draws them, beacon beams too, and what the buddy holds is in their hands, a blow swinging it and food brought to their
  mouth. The air bubbles stand above the food under water, as in the game. The hover over a block shows its data, and for a
  container they had open its items too (`Items`, with names and enchantments), which a click copies for `/data merge block`
  or `/setblock` on a test server. The line above the hotbar stands in its colours, the scoreboard at the right as the game draws it (`F1` hides it too), and a new chat line shows over the view for ten seconds while
  the chat is shut, as in the game. The game's screens open over the view: a chest or any menu, a sign (`Enter` goes down a line, `Esc` or Done
  writes it), a book, the bed, the death screen. The buddy's coordinates stand at the top left. The view's frames a second
  stand in its status line; the buddy's game draws
  forty frames a second while you play them (their own setting otherwise). On a touch screen: the buttons, and drag on the
  field to turn. While you play them no job moves them.
- **Inventory**, as the game draws it: armour beside the buddy (their own skin and the armour they wear, their head following the
  mouse), the off hand, the crafting grid, the 27 slots and the
  hotbar with the slot they hold outlined, and under it the game's HUD: armour, hearts, food (from the right), their level
  and its bar. Hover a slot for the game's tooltip (the name in its rarity's colour, a player's own name in italics,
  enchantments in gray, curses in red, durability, the item's id). The buddy's effects and what they hold on the cursor stand
  beside it. **You use it as in the game**: a click picks up or puts down, a right-click takes half or puts one,
  `Shift`-click moves across, a double click gathers a kind, `1`–`9` over a slot swaps with the hotbar, `F` with the
  off hand, `Q` drops one (`Ctrl`+`Q` the stack), dragging a held stack over slots shares it out, and a click outside
  the window throws what you hold. Only what you picked up yourself follows your mouse, and only that is thrown by a
  click outside the window: what they, or someone else playing them, hold on the cursor stays in the box beside it. The
  wheel over the hotbar picks what they hold. Every click is one real click in their
  inventory; meanwhile their jobs wait.
- **The chat box**: a line goes out as the buddy's chat, a `/command` as their command. While a `/command` is typed, what
  may come next stands above the box (the game's suggestions, and the server's where it fills them in); `Tab` takes the
  next, `Shift`+`Tab` the one before, a click that one. Nothing is sent before `Enter`.
- **A menu they have open** (a chest, or a server's menu such as `/shards` typed in the chat box) takes the inventory's
  place, as the game's chest screen: the menu's title in its colours, its slots, and their own inventory below. Tooltips
  show the server's names and lore with their colours; the glass between a menu's buttons has none. Clicks work as in
  the inventory, so a click on a server's button does what it does in the game (it may spend money or shards). Close or
  `Esc` over the inventory shuts the menu; while they follow or keep a base they shut one nobody clicked in for a minute.
- **Players**, a fourth tab beside them: who is online and since when (from the Telegram bridge's `online_since.json`,
  which keeps it across the buddy's reconnects; "at least since" for who was on before the buddy came).
- **Chat, Activity, Problems**: the server's chat with names in colour, and a line to type as the buddy (a line or a
  `/command`) with quick buttons (follow, stay, follow again, go home, stop following, ping); everything they report;
  and what went wrong, the same trouble in one row with how often, when last and where. Scroll up for older lines,
  from the panel's own history. The figures of a plugin, whose names are colour codes, do not show as joins and leaves.
- **Settings**: **Status** installs, logs in, sets the server and starts, stops and updates the buddy (see [Installing](install.md)),
  and shows the game's own console; a server saved there gets a profile of its own, named after it, when none fits it;
  **Telegram** is the bridge (below), with a main switch: off, nothing of Telegram shows anywhere in the panel;
  **Connection** takes them off the server and back, and whether they stay off when their game starts anew;
  **Server profiles** edits the profiles above (How it works explains every name, the variables and the server modules);
  **Access** has the password, the trusted IPs and the trusted players; **Schematics** uploads a
  `.litematic` (up to 8 MB) into their game's schematics folder and deletes one (placing is on the overview); **Behavior** has their **logs** (download each, empty the ones the panel
  may, and switch recording menus and screens on or off) and a field for any exchange command as JSON, with How it works
  listing the ones worth knowing.
- **In the 3D view**, the box at the bottom right shows what the mouse is on; for a chest, barrel, shulker box or furnace
  that is also what lay in it the last time they had it open, with when. A server sends a container's items only while it
  is open, so one they never opened says so.

## Who may open it

- **The owner password** opens everything, from anywhere, for thirty days: it is chosen at the first visit with the
  setup code from the container's log (`docker compose logs <their name>`), and changed in Settings, Access (where Log out is
  too). Every login is a session the panel keeps (`owner-sessions.json`): Log out ends it there as well, so a copied cookie
  opens nothing afterwards, and a new password ends every session. Caddy in front gives the page its HTTPS, and the panel trusts the visitor's address only from Caddy, with the secret
  Caddy sends (`X-Buddy-Proxy`, see [install](install.md)).
- **Trusted addresses** (Settings, Access): one address or network a line, `#` for a note, that may steer the buddy without a
  password (your own address has to stay in while you are not logged in).
- **Anyone else gets the login page and nothing more**: every other request is refused by the panel itself.

**Trusted players** (Settings, Access) is one list for the game and Telegram. A Minecraft name counts in the game: that
player's taps and private-message commands count, the buddy accepts their teleport requests and does not stop for them (the
mod's `trusted` in `buddy.json`, which changes only while the buddy is on a server). A Telegram user id makes them an admin of
the bridge (`admins` in `telegram.json`); with a name beside it "to me" in a command means that player. Either may be
empty. An admin named by a @username counts too; one named by a first and last name only in a private chat with the bot,
since anyone can give themselves that name in a group.

The panel takes at most 200 connections, and ten wrong passwords from one address (an IPv6 /64) in ten minutes, a hundred
from all addresses together. No other site may show it in a frame.

## How it is made

The panel is `panel/` in this repo: `server.py` (Python's standard library only, plain HTTP on a port of the container,
behind Caddy), `owner.py` (the password), `game.py` (the game), `index.html` and its fonts (`fonts/`: IBM Plex Sans and
Mono and Pixelify Sans, all under the SIL Open Font License, served by the panel itself). It comes with the image, and
an update of the image is an update of the panel. It talks to the mod only through the exchange folder. The game's pictures and English names are
not in git (they are Mojang's): the panel makes them itself from the game's client jar once the game has started
(`make-icons.py`, with Pillow and numpy, and `make-assets.py`).

← [Back to the README](../README.md)
