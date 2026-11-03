# The web panel

The buddy's page in the browser: watch, steer, use the inventory, and set everything up. It is at the buddy's domain
(the label `caddy` of that buddy's block in `compose.yaml`).

## What is on it

### The header

The bar at the top shows the buddy's name and skin, whether the buddy is online and on which server, and what the
buddy is doing **now**. Its buttons:

- **Leave** takes the buddy off the server, and the buddy stays off, also when the game starts again, until **Join**.
  Join goes back to the last server. After a kick or a lost connection the buddy joins again automatically after about
  half a minute.
- **Settings**; in Settings the same button goes back to the overview.

Under the buttons stands the buddy's version, with an arrow when a newer release is out. A click on it then updates
(see [Installing](install.md#updates)).

On a phone the parts come one at a time, chosen in a bar at the bottom (Map, Inventory, Chat, Settings).

### Priorities

The steps in the order they get the buddy's hands: stay safe, your instructions, fight, fetch an order, save a
player's things, bring things home, tend the base, sleep, help you work, follow. The step in charge is lit, with what
the buddy is doing; the ones below it wait.

### Modes

A mode is how the buddy spends the time, each with its own switches. Click a mode's circle (or its name): the mode
opens greyed, so its switches, an amount or a schematic can be set first, and the button beside its name switches to
it. The circle again closes it. Another mode ends the one that was on.

- **AFK**: the buddy stays put and does nothing else, for example to keep a farm's chunks loaded. Teleport requests
  from trusted players are accepted. Its switches: **Look around** looks at players who come close, **Walk about** takes
  a few steps and back now and then so that a server's AFK check sees movement (how far and how often are set under
  it), **Stay crouched**.
- **Tend the base** walks the buddy home and keeps the base: harvests fields, runs furnaces, stores in chests, fights
  monsters, sleeps at night, shears sheep and herds strays back. Set the base in the game: with the buddy within six
  blocks, tap a bed with an empty hand.
- **Follow a player** follows the name you type or pick. Its switches: mine, chop and farm with you, guard you, pick up
  your drops, farm mobs, dig when blocked, bridge and pillar, crouch along.
- **Build a schematic** builds the placed schematic nearest to the buddy, with what the buddy carries and the chests
  near where the buddy stands. Upload the file in Settings, Schematics. Opened, the mode places new schematics and
  lists the placed ones:
  - **Place a schematic**: the file, its start block (where the buddy stands, or x y z), the turn and the mirror. The
    start block is the block that lands on that spot; the schematic turns and mirrors around it.
  - Each placed schematic shows its size, turn, mirror and start block. A click shows it on the map. To move it, type a
    new start block under it or click **To where the buddy stands**.
  - **Material list**: what the build takes per item, as Litematica counts it: in all, still missing where the area is
    loaded, what the buddy carries, and what the building chests held. Tick **ignore** to leave an item out.
  - **Remove** takes the placement away; the file stays.
- **Obtain items** fetches an item. Search it by name (the list holds what the buddy knows a way to from nothing,
  worked out from the game's own recipes and drops), type how many and who gets it, and click **Fetch**. Under it stands
  the order's progress: how many the buddy has, the current step and for how long, or that the buddy is dug in for the
  night or to heal. **Stop** ends the order. Once the order is over, the line says how it ended. [Obtain
  items](obtain.md) has the detail.

A **?** beside a mode or switch says how it is set in the game (the bed you tap for the base, the sign to follow, the
punch for a mob farm). A switch counts at once and is kept; the switches of a mode that is off wait for it.

### What the buddy is missing

Above the map, for as long as it lasts: what the buddy lacks to help, such as food, a weapon, room in the inventory, a
tool about to break, or the right tool or torches for a job.

### The map

128 by 128 blocks around the buddy, live, north up, coloured as Xaero's minimap draws it. On it: the bed, chests and
fields, players and monsters nearby, and an arrow for the buddy. The corners show the buddy's position and the game's
day and time.

- Drag to look around and zoom out to 512 blocks; **Back to the buddy** or the round button puts the buddy in the middle
  again. A coordinate in Problems or Activity takes the map there.
- What was seen before stays on the map, dimmer. The buddy draws the chunks around, also while the page is shut.
- `Auto` shows the buddy's layer when deep under rock or in the nether; `Surface` and `Cave` choose one.
- Click a spot and **Walk there** sends the buddy.
- **2D / 3D** switches the map. In 3D, drag with the left button to move and with the right to turn and tilt (on a
  touchpad, hold `Shift`, `Option` or `Ctrl` and drag). Zoom with the wheel, two fingers, a pinch, the buttons, a double
  click (in) or a double right click (out). A click on a block offers **Walk there** too, also underground, where the
  map looks through the rock overhead.
- In 3D, the box at the bottom right shows what the mouse is on. For a chest, barrel, shulker box or furnace that is
  also what lay in it the last time the buddy had it open, with when: a server sends a container's items only while it
  is open.

### Take control

**Take control** first shows the view around the buddy (**Around** and the buddy's name). **Play** shows the buddy's
own view, with the keys at the top (**Hide keys** folds them away). Everything shows as it happens: turning, hitting,
eating, breaking blocks, a chest or menu being opened. In Play, a click on the view takes control.

A bar shows how far the view is with loading, here and on the 3D map. The first time in a browser takes longest: the
game's textures come from Mojang then. Back on the page after more than 20 seconds (another tab or app in front, the
computer asleep), the old view turns grey under the bar until it shows what is there now; the rest of the page is
fetched again at once.

While you watch:

- **Around the buddy**: drag to turn around the buddy, scroll to zoom. **Fly**: click the view, then `W A S D`, `Space`
  and `Shift`, at 20 blocks a second (60 with `Ctrl`). The buddy stands still in both.
- **Inventory** shows the buddy's inventory and armour small in the top right corner until you click it again. While
  you play the button is gone, and `E` opens the inventory.
- **Plan** shows an order's plan and timeline at the left until you click it off. The button is there only during an
  order.
- `F5`, `F1` and `F3` work as in the game.

While you play:

- `W A S D` walk, the mouse looks, `Space` jumps, `Shift` sneaks, `R` (or `W` twice) sprints, left click hits or breaks,
  right click uses (held: eating, a bow, a shield), `1`–`9` or the wheel picks a slot, `Q` drops, `F` swaps hands, `E`
  inventory, `T` or `/` chat, `Tab` player list, `F5` camera, `F1` hides the HUD, `F3` shows what is looked at.
- `Esc` stops, and you watch again. **← Overview** and **Done** close the window.
- In the inventory, what is on the cursor goes back when you shut it; a click on the world beside it throws what is on
  the cursor.
- No job moves the buddy while you play. On a touch screen: the buttons, and drag on the view to turn.

One person plays at a time. While someone else plays, a click on the view says who (by address), that person's open
inventory shows over the view, and nothing you click reaches the game, nor do clicks in the overview's inventory.

The view draws what the game draws: blocks, light and night, mobs, players with their skins, armour and what they hold,
signs, beacon beams, the HUD, the line above the hotbar, the scoreboard, and a new chat line for ten seconds while the
chat is shut. The game's screens open over it: a chest or any menu, a sign (`Enter` goes down a line, `Esc` or Done
writes it), a book, the bed, the death screen. The coordinates and the game's day and time stand at the top right,
under the inventory while it shows there.

- **Game**, **Bright** and **Full** set the brightness: the game's own, its brightest, or as with Night Vision so that
  dark caves can be seen into. Full is the default; the choice is kept in the browser and holds for the 3D map too.
- Hovering a block shows its data, and for a container the buddy had open its items too, with names and enchantments.
  A click copies it for `/data merge block` or `/setblock` on a test server.

### Inventory

The buddy's inventory as the game draws it: the buddy with skin and armour (the head follows the mouse), the off hand,
the crafting grid, the 27 slots and the hotbar with the held slot outlined, and under it armour, hearts, food and the
level. Hover a slot for the game's tooltip. Beside it stand the buddy's effects and what the buddy holds on the cursor.

Use it as in the game: a click picks up or puts down, a right click takes half or puts one, `Shift`-click moves across,
a double click gathers a kind, `1`–`9` over a slot swaps with the hotbar and `F` with the off hand, `Q` drops one
(`Ctrl`+`Q` the stack), dragging a held stack over slots shares it out, a click outside the window throws what you
hold, and the wheel over the hotbar picks the held item. Only what you picked up follows your mouse; what the buddy, or
someone else playing, holds on the cursor stays in the box beside it. Every click is one real click in the buddy's
inventory, and the jobs wait meanwhile.

**A menu the buddy has open** (a chest, or a menu a server command opens) takes the inventory's place, as the game's
chest screen: the menu's title, its slots, and the buddy's inventory below, with the server's names and lore in the
tooltips. Clicks work as in the inventory, so a server's button does what it does in the game (it may spend money).
Close, or `Esc` over the inventory, shuts the menu. While following or keeping a base, the buddy shuts a menu nobody
clicked in for a minute.

### Chat, Activity, Problems, Players

- **Chat**: the server's chat with names in colour, a line to type as the buddy (a line or a `/command`), and quick
  buttons (follow, stay, follow again, go home, stop following, ping). While you type a `/command`, the game's
  suggestions, and the server's, stand above the box: `Tab` takes the next, `Shift`+`Tab` the one before, a click that
  one. Nothing is sent before `Enter`.
- **Activity**: everything the buddy reports. During an order also its steps and Soprano's lines: what Soprano starts
  and finishes, what it does now (a line when the kind of task changes, so a staircase down is one line), and when one
  of its chains takes over to keep the buddy alive (once it has held for a second). The order's card shows only the
  step and what Soprano does at this moment.
- **Problems**: what went wrong, the same trouble in one row with how often, when last and where.
- **Players**: who is online and since when, kept across the buddy's reconnects ("at least since" for players who were
  on before the buddy came).

Scroll up for older lines.

### Settings

- **Getting started**: the four steps of a new machine (see [Installing](install.md)).
- **Status**: install the game, log in, set the server, start, stop and update the buddy, and read the game's console.
  A server saved there gets a profile of its own, named after it, when none fits it.
- **Connection**: **Leave** and **Join**, as in the header, and the switch for joining again after a kick or a restart
  of the game or the server. Leave turns that switch off, Join on.
- **Server profiles**: the [server profiles](profiles.md); the editor explains every name, the variables and the
  server modules.
- **Access**: the owner password, trusted addresses and trusted players (below).
- **Telegram**: the [bridge](telegram.md). Off, nothing of Telegram shows anywhere in the panel.
- **Schematics**: upload a `.litematic` (up to 8 MB) into the game's schematics folder, or delete one. Placing is on the
  overview.
- **Behavior**: the buddy's logs (download, empty, and switch the recording of menus and screens on or off), and a
  field for any [exchange command](exchange.md) as JSON, with the ones worth knowing listed.

## Who may open it

- **The owner password** opens everything, from anywhere, for thirty days. Choose it at the first visit, with the setup
  code from the container's log (`docker compose logs <name>`); change it in Settings, Access, where Log out is too. Log
  out ends the session on the panel as well, so a copied cookie opens nothing afterwards; a new password ends every
  session.
- **Trusted addresses** (Settings, Access): one address or network a line, `#` for a note. They may steer the buddy
  without a password. When you are not logged in, the panel refuses a list without your own address.
- **Anyone else** gets the login page and nothing more.

Ten wrong passwords from one address in ten minutes (an IPv6 address counts as its /64), and that address waits; a
hundred from all addresses together, and every password login waits. The panel believes a visitor's address only from
Caddy (see [Installing](install.md#what-runs)).

**Trusted players** (Settings, Access) is one list for the game and Telegram:

- A **Minecraft name**: that player's taps, private-message commands and teleport requests count. Minecraft names can be
  changed only while the buddy is on a server.
- A **Telegram user id**: that person can chat as the buddy and give `!` commands in Telegram; "to me" means that
  person's Minecraft name.
- Either may be empty.

## How it is made

The panel is `panel/` in this repo: `server.py` (Python's standard library only, plain HTTP on a port of the container,
behind Caddy), `owner.py` (the password), `game.py` (the game), `index.html` and its fonts (`fonts/`: IBM Plex Sans and
Mono and Pixelify Sans, under the SIL Open Font License). It comes with the image, so an update of the image updates
the panel, and it talks to the mod only through the exchange folder. The game's pictures and English names are not in
git (they are Mojang's): the panel makes them from the game's client jar once the game has started (`make-icons.py`,
with Pillow and numpy, and `make-assets.py`).

← [Back to the README](../README.md)
