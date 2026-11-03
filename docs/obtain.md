# Obtain items

A mode in which the buddy fetches an item and a count outside the base: whatever it takes, from the base's chests when
it is there, made or gathered otherwise, with every step in between (crafting, smelting, a better pickaxe first). The
design aims at the worst case: a bare start in a hardcore world, with nothing to take from, up to an elytra, without
dying on the way. Orders for everyday things (iron blocks, diamonds, obsidian, torches) take the same road, shorter.

## Ordering

- **The panel**: the mode **Obtain items**. Search an item by its name (the list shows its picture and is what the
  buddy knows a way to from nothing, worked out from the game's own recipes and drops), type how many and who gets it,
  and **Fetch**. Under it the progress: how many are gathered so far, the current step and for how long, or that the
  buddy is dug in for the night or to heal. **Stop** ends it; once it is over the panel says how it ended.
- **Watching an order** (**Take control**): at the left the plan (**Plan** shows and hides it): the order, its step, and
  Soprano's tasks from the outer to the deepest (a golden helmet, its gold, the raw gold, the mine down to it). When
  something else takes over (monsters, food, lava, a fall, the buddy's own fighting), it shows on top as **Interrupted**
  and the plan under it waits. Under the plan a timeline of the order, newest on top: every step and task, every hit
  with the hearts before and after, every interruption and the return to the plan, a death. Hits show only here, not in
  the Activity tab.
- **Telegram**: `!obtain diamond_block 4` fetches four diamond blocks for you, `!obtain iron block 9` works too (the
  name in words), `for Name` at the end brings them to another player. `!obtain` alone shows the current order,
  `!obtain stop` ends it. A line comes when the order is done or given up.
- **The exchange file**: `{"cmd":"obtain","item":"diamond_block","count":4,"by":"Steve"}` fetches for Steve (without
  `by` the buddy keeps the items), `{"cmd":"obtain","stop":true}` stops, `{"cmd":"obtain"}` asks for the current
  order (an `obtain` event, `status`), and `"plan":true` only works the steps out (an `obtain` event, `plan`, with the
  steps).

A count runs from 1 to 2304 (a full inventory). Only the panel's trusted players and the bridge's admins can order.

While an order runs, following is off. When the items are there the buddy brings them to whoever ordered them,
following that player and throwing the stacks at their feet the way a dump does (watched, picked up and thrown again,
three tries). With nobody named, or a player who is not online, the buddy keeps them and stays put, watching.

An order is kept in `obtain.json` in the exchange folder and taken up again after a restart. `obtain-items.json` holds
the list of what can be ordered, written once at every start.

Events of type `obtain`: `start`, `resumed`, `step` (what is being done now), `step done`, `failed` (a step that did
not come off, and why), `gives up` (with the reason), `stop`, `done`, `status`, `plan`. Soprano's own lines come as
events of type `alto` and go to the panel's Activity tab.

## What the mode may use, and what it will not do

- From the base: an order may take everything its steps need from the base's known chests, diamond tools too. The base's
  own chores keep their own rules.
- **Safety before the item**: rather give an order up, with its reason in the panel and on Telegram, than take a chance
  of dying. An order with no safe way is given up.
- **A server's rules**: a dig or a placement the server refuses (a claim) is read from chat and not tried there again
  for a while. The mode sends no server commands of its own; anything a server does by command is its profile's.
- **No x-ray**: an ore counts only where the buddy can see it (it touches air or a fluid). On a server with an anti-xray
  plugin every hidden block can be a fake ore, and chasing those is how a bot that reads every block gets stuck. On a
  server without one, reading hidden blocks would be x-ray, against the rules of many servers.

## How it works

With [Soprano](https://github.com/AverWasTaken/soprano) (Baritone with AltoClef) in the mods folder, the work is split:

| who | what |
|---|---|
| Soprano, its `#get` and catalogue | an order for an item it knows, whole: planning, walking, mining, crafting, smelting, food |
| Soprano's chains, on for the whole order | fighting (mob defense), eating, out of lava, fire and water, a bucket under a fall |
| Baritone | walking and mining for the buddy's own steps (paths, digging through, building up, bridging, falls into water) |
| the buddy | the order itself (panel, Telegram, exchange file), its status, a stop and the delivery; the kit before a danger; the night; an item Soprano does not know (the elytra), planned by the buddy, with every step of it that Soprano knows handed to Soprano; a server's rules; the base's chests |

While a task of Soprano's runs, the buddy's own layer stands down: fight, flight, creeper shield and meals, the kit, the
night's hole, out of the water, digging out, throwing junk away, the brink. Without Soprano the buddy's own planner,
steps and walker do it all.

The buddy's own parts, all in `net.milenco.buddy.client`:

- **Knowledge** (`VanillaData`, `Recipes`, `Drops`, `Nature`): the recipes (crafting, smelting, the stonecutter,
  smithing), what every block and mob drops and what a block takes to break, read at the start from the vanilla data in
  the client jar (`data/minecraft/recipe`, `loot_table`, `tags`). No data of Mojang's in git, and right by itself for
  another version. `Nature` says where a block or a mob is found (dimension, kind of place, best height) with a guess of
  its cost, and a small list of its own covers what has no recipe or drop (an elytra: an End ship's item frame).
- **The planner** (`ObtainPlan`): pure, without the game, and tested. From what the buddy carries and the base's chests
  it works out the cheapest way to the item (craft, smelt, mine, kill, a chest), with the tool, station and fuel each
  step needs before it. It is asked again after every step, so a step that brought more or less than planned changes
  the rest. A way that fails is left alone for a while and the planner takes another.
- **The steps** (`ObtainSteps`), each with a start, a tick and an outcome (done, or failed with a reason): take from a
  chest, craft in the 2x2 grid or at a table (placed when there is none near, picked up afterwards), smelt (a furnace
  placed or found, fuel, waiting, taking out), break blocks of a kind (seen, or searched for), kill mobs, pick up drops,
  fill a bucket, cast obsidian in a mould, build and light a nether portal, find the stronghold by eyes of ender.
- **Searching** (`BlockScan`, `Stronghold`): the blocks the client has in its loaded chunks, an ore only where it
  touches air or a fluid; mining at the depth an ore is common, by a staircase, never straight down; outward to new
  ground otherwise, in a widening spiral, remembering the places already searched. The stronghold is where the lines
  of two throws of an eye of ender cross.
- **The kit** (`ObtainKit`): what the buddy carries before a step of a kind (below).
- **Staying alive** (`ObtainSafe`): what Follow's chain does already runs first (air, fire, creepers, a fight, getting
  loose, doors, the hunger guard), and on top of it the rules below.
- **Where it runs**: a step in Follow's chain (`Arbiter` name `obtain`), after the survival steps and before the
  following, with a job in `Jobs.Job` so that mod off, the panic and a farm switched on stop it too.

## The way to an elytra

There is one route for every order: `#get elytra` and `#get dragon_egg` are the far ends of the same tree that gets a
stack of torches. What the buddy has comes first (the bag, then the base's chests), the world after that.

1. Wood, a crafting table, wooden and stone tools, food, coal and torches, a shelter for the night.
2. Iron from caves or a mine, a furnace, iron tools and armour, a bucket, a shield, flint and steel, a bed.
3. Diamonds (deep, away from lava), a diamond pickaxe, obsidian (mined, or cast with water on lava).
4. The nether: a portal, gold against piglins, ghasts and lava, a fortress, blaze rods, ender pearls from endermen or
   bartering, back.
5. Eyes of ender, two throws apart to place the stronghold, digging down, the portal room.
6. The dragon: the crystals shot, hits when it lands, its breath avoided, never at the edge.
7. The gateway, the outer islands bridged, an End city with a ship, shulkers avoided (levitation), the elytra taken,
   back through the exit portal and home.

## The kit

A danger is only met with the gear for it. Each gate holds for its own kind of step and every kind below it, and what a
gate lacks is fetched by safer steps first, all of it planned together.

- **Underground**: a crafting table, wood (or sticks), a sword (a stone one as soon as there is stone in the bag) and a
  spare pickaxe.
- **The deep** (diamonds): armour as the iron comes (a shield from the first ingot, then the chestplate, the leggings,
  the helmet and the boots, each as soon as there is iron for it; copper in an empty slot meanwhile), a good sword, a
  water bucket, torches, blocks, food, three stone pickaxes with a table and sticks so that the rock wears out the cheap
  tools, and a bed when there is a sheep near or wool in the bag.
- **The nether**: full armour with a golden helmet worn (the piglins leave the wearer be) and a spare one in the bag, a
  diamond sword when the diamonds are in the bag, 48 blocks, flint and steel, and food to 96 points. Torches and the
  water bucket are the overworld's. With Soprano also three water bottles and a redstone dust, each a piece of its own
  and filled in the overworld, the redstone tried once an order: in the fortress Soprano's blaze hunt picks up a nether wart and a magma cream when they are close by and,
  with two rods for the stand and its fuel, brews three potions of fire resistance (eight minutes each). The potion is
  drunk when fire is about, and it stops all fire damage: burning, the blazes' fireballs and lava.
- **The End**: a bow with arrows on top.

A piece of the kit may take ten game minutes and three more for each ingot; a piece that did not come is not asked again
for ten game minutes, while the others go on. The golden helmet for the nether is never given up: the order waits for
its gold. A stock of eight or more (food, torches, blocks) counts as enough at three quarters. A tool or piece of armour
at its last tenth counts as gone, so a new one comes while the old still works; a shield at its last uses gets a spare
in the bag, before the food run when the iron and the wood for it are in the bag already. With Soprano the portal into
the nether is built from the bag's obsidian when it holds ten and flint and steel, where the buddy stands; otherwise it
is cast with a bucket of lava and one of water. A frame begun and left (a food run broke in) is finished where it
stands; under the ground the buddy climbs to the surface first. With the bag full, Soprano makes room by
throwing what no task uses; the brew's things, the portal's, the golden helmet, the shield, the water bucket, blaze
rods, ender pearls and golden apples are never among them during an order.

## Staying alive

- **Never without the survival layer**: after an order out in the wild the buddy stays in Follow's chain and fights
  whatever comes. After an order given up in the night the hole still stays shut until the morning.
- **The night in the ground**, without iron armour: from 11500, before the dusk, to 1000 (a thunderstorm counts as
  night). Three blocks down under the open sky and shut, or underground the open sides shut right there; out in the
  morning once nothing waits at the hole's mouth. Deep under the ground the night changes nothing and the work goes on.
  A bed in the bag skips the night where the server lets one sleeper do that.
- **Sleep only outside**: Soprano's bed at dusk goes down only outside in the overworld. Deep under the ground the buddy
  does not sleep (phantoms come only under the open sky); back outside at night, the bed goes down at once.
- **Hurt**: under half health with no fight on and food to heal by, a hole until healed; with nothing to eat, food first.
- **Fight or flight by numbers**: the time the best weapon in the bag takes to kill what comes against the time they
  take to bring the buddy to two hearts, armour counted. A fight that would be lost is run from in the open, never down
  a tunnel and never where there is no way away; a flight that still takes hits from a monster ends in a fight.
- **Shooters**: from skeletons and the like, a hole three down right there, or walls built of the blocks in the bag, the
  shooters' side first, kept while one is about. A drowned with a trident counts as a shooter. Under the ground monsters
  on their way are walled out at once. Hit at arm's length inside the walls, the buddy fights.
- **Places to avoid**: where a patrol of pillagers was seen (twenty minutes) or the buddy was shot (ten), no search
  leg, no ore and no animal within 48 blocks.
- **No meal with a monster in pursuit** within ten blocks or a shooter in sight, unless only a bite can save the buddy.
- **Edges**: no bridge over more than three blocks of nothing, and at a brink a crouched walk, so there is no walking
  off; getting loose never jumps beside a drop.
- **Water and lava**: in water underground, a walk to a dry cell; at night out of the water at once; a boat for more
  than 32 blocks of water ahead. A roof with water or lava over or beside it stays shut, and a block with lava beside it
  is never broken.
- **Up by a stair of the buddy's own**: a step only the surface has, taken deep under the ground, starts with a stair
  dug up to the sky, not a walk through the caves.
- **Tools renewed in time**: a tool or armour piece in its last tenth counts as gone, so a new one is made while the old
  still works; its last uses are spent only when nothing else breaks the block.
- **In the nether**: little food to eat means a trip home to the overworld for more, begun once nothing in sight is on
  the buddy and the fire is out (or the food is all but gone); away from the rim of a bridge or a ledge; never there
  without something golden on.

## Settings

| setting | default | what |
|---|---|---|
| `obtainSopranoFirst` | on | an item Soprano's catalogue knows is got by Soprano whole; the buddy's own steps only where it has none |
| `obtainAltoSurvival` | on | Soprano's chains fight, eat and get out of lava and water during an order |
| `obtainBaritone` | on | the buddy's own steps walk and mine with Baritone when it is in the mods folder |
| `practiceFastTicks` | off | for a practice server run at a raised `/tick rate`: the client ticks as fast as the server |

On an order the buddy eats any food, raw meat too, not only the foods of `eatItems`.

## Practising on a server of your own

The mode is practised on a local server, where a death costs nothing. What such a server needs to know:

- The client takes the longer of its own 50 ms and the server's tick (`Minecraft.getTickTargetMillis`), so at a raised
  `/tick rate` the world runs faster than the player in it. `practiceFastTicks` lets the client take the server's tick;
  the client's frame limit caps it too (it runs at most ten ticks a frame). Five times the speed keeps both in step.
- What runs on the wall clock takes longer in game time at a raised rate: Baritone's path search (the buddy cuts its
  budget by the rate), and Paper's block-break clock, which makes every dig take as many times longer and needs a server
  plugin to count server ticks instead.
- Under Paper the difficulty is kept twice, in `level.dat` and per dimension in `data/paper/level_overrides.dat`,
  which wins.
- Paper's anti-xray (engine mode 2) on the practice server shows whether the search holds up on a real server.

## Notes for developers

- The client jar carries `data/minecraft` (recipes, loot tables, tags): no recipe has to come from the server, which
  since 1.21.2 sends a client only the recipes it has unlocked.
- On 26.3 the client has no `MOTION_BLOCKING_NO_LEAVES` heightmap: it answers the bottom of the world. The ground is
  found from `MOTION_BLOCKING` downward past leaves, logs and plants (`ObtainSteps.surface`).
- A client is never told what a monster targets (`getTarget` is the server's); it is told whether a monster is
  aggressive (a melee monster's raised arms, a shooter's drawn bow, a drowned's raised trident). A skeleton shows it
  only while it draws its bow.
- `canSeeSky` is false in the top cell of water (the water takes a level of the light): deep in the sea a player seems
  to be under the ground. Look for the first cell over the head that holds no water.
- The client's clock steps back a tick or two when the server's time comes in: a time window needs some slack at its
  edge.
- The game refuses a block in a cell the player's own body is in, also half: a placer steps to the middle of its cell
  first, and a station's spot never touches the player's box.
- Crouched at the edge of a bridge a player leans over the gap, and the block position is the gap: count from the block
  stood on (`getOnPos`).
- A one-wide staircase needs the cell over the head in every column it goes down into, or the way back up is a jump
  into a ceiling.
- A thunderstorm is night for the monsters.
- Baritone's legit mine never goes down to the ore's height by itself (its step down is commented out): the buddy's own
  staircase takes it there first.
- macOS' file system does not tell `Pickup` from `PickUp`: never two class names that differ in case only, or one
  `.class` file overwrites the other.

## Status and limits

- From nothing to iron, diamonds and obsidian works, on normal difficulty so far (hard and hardcore come after it); the
  nether (blaze rods, ender pearls) is being worked on. The stronghold, the End, the dragon and an End city are in the
  plan and partly in the code, not yet proven in the game.
- Not made yet: a way to fight wither skeletons that does not trade blows, healing during a fight, and a risk slider
  that lets an order choose a faster and riskier way where there is one (until then always the safe way).
- A crowd of blazes is still mostly fought blow by blow; a bow does not lead a moving target.
- Near a patch of sculk a branch mine can walk to and fro between the mine and its furnace for a while.

## Building

The mode compiles against Soprano's jar (a fork of it, with what a server with an anti-xray plugin needs, more kinds of
food, copper tools and a night's sleep for any task) and does not ship it. The jar is not in git until that fork is
published with its source: put `soprano-unoptimized-fabric-1.1.6.jar` in `libs/` by hand (see `build.gradle`).
