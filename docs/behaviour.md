# How the buddy behaves

Everything the buddy does in the game: the signs you give, what the buddy does unasked, the jobs you switch on with a
tap, mob farms, finding the way, what comes first, and what the buddy carries.

## Signs you give the buddy in the game

Stand near the buddy and give the sign. No command is needed, and the signs work on any server.

| You | The buddy |
|---|---|
| look at the buddy and crouch twice quickly, with empty hands or a tool (anything but a sword, spear, mace or trident) | stays here (a nod) or follows again (a hop); staying is kept over a restart |
| the same with a sword, spear, mace or trident in hand | protection on (weapon out and a swing) or off (weapon away and a shake of the head) |
| hold a sword, spear, mace or trident up to the sky and crouch twice | fighting players on or off (on by default, kept over a restart) |
| look at the buddy with empty hands and crouch five times or more | asks "forget everything of this server?" with a shake of the head and one crouch; crouch twice within seven seconds to confirm, and the buddy nods |
| **tap a block with a bare hand** (hit it and let go within a second) within six blocks of the buddy | a job on or off ([Jobs you switch on with a tap](#jobs-you-switch-on-with-a-tap)) |
| **punch a monster in the pen of a mob farm with a bare hand** while the buddy follows you within six blocks | nods, goes to the pen's edge and kills what comes in ([Farming mobs](#farming-mobs)) |
| while the buddy farms: crouch twice within six blocks (no need to look) | stops farming: one crouch, a step back out of sight of the pen's mobs, and a wait there (in a base: keeps the base) |
| throw things at the buddy (Q while looking at the buddy) | takes them home to your base |

The answers: **a nod** is yes or on, **one crouch** is off, **a shake of the head** is "that block means nothing to me"
or "that cannot be done", or, after the weapon is put away, protection off.

## What the buddy does without being asked

**Always**, the buddy waits for the follow sign and is your **corpse guard** (the panel's **Pick up your drops**, on by
default, which also covers things you throw): when you die within 32 blocks, the buddy picks up exactly what fell, the
dearest first, and throws it at your feet when you are back. Not:

- from lava, fire or the void;
- when another player killed you;
- while more than two hostile mobs or a creeper stand by the spot, or the buddy is low on health: then the buddy waits
  at a distance while the items last.

**While following you**, the buddy:

- follows on foot, swimming, climbing, jumping gaps, by elytra, on your happy ghast or in your boat;
- sleeps when you sleep, eats, and respawns after a death;
- uses `/tpa` and accepts your `/tpahere`, where the server has them;
- learns your chests from the lids you open;
- wears the best armour at hand, with a **totem** in the off hand whenever there is one, and a **shield** there while
  guarding you without a totem;
- listens for your taps.

**Protection** is on by default; the weapon sign or the panel's **Guard you** switches it. With it on, the buddy fights:

- whatever hits either of you, at once, also during a walk to a spot, while getting loose or with a chest open;
- a monster you hit, unless three or more monsters are around: then that one is left to you;
- **ahead of a blow**: a monster that comes at you within six blocks (walking your way, set on a target, or closer than
  three blocks), a creeper that swells within seven blocks of you, and one that aims a bow at you, the worst first. With
  a monster within twelve blocks of you, the buddy stands beside you on the monster's side, facing it. Not an enderman,
  a piglin or a zombified piglin (a blow turns them on you), not a spider where it is light, nor a mob with a name, on a
  lead, in a boat or a minecart, or in a pit or a farm's cell (such as an iron farm's zombie); not while you play in
  creative or spectator mode, nor while the buddy stays.

A foe out of reach for twenty seconds is left alone for a minute. In a fight:

- **the shield** goes up between blows, while a skeleton or anyone else aims a bow or crossbow at the buddy, and towards
  a creeper that swells within four blocks (the shield blocks its blast from the front);
- **the blows**: a critical hit against one foe; a plain blow on the ground when two or more monsters stand beside the
  target and the sword has Sweeping Edge, so that it hits them all; a sprinting blow at a creeper, which knocks it back,
  then a step away; a step back from a zombie or the like that comes closer than a blow's length, where the ground
  behind is safe;
- **the bow**, with arrows at hand: at a phantom, a ghast, a blaze, a vex, a creeper, a foe two and a half blocks or
  more above the buddy, or one out of reach for four seconds. The buddy stands still, draws fully, aims where the arrow
  will meet the foe, and never shoots with a block or a player in the way;
- **at four hearts or less** the buddy backs off, eats a golden apple (or the best food at hand when not full) and
  fights on. Golden apples are eaten only to heal in a fight, never for hunger.

**Players**: with fighting players on (the sky sign, or the panel's **Guard against players**), the buddy fights a
player who hits either of you, and a player you hit, like a monster. Never you, a player you trust, or one in creative
or spectator mode. Against a player the buddy:

- comes in at a run and strikes the first blow sprinting, which knocks the player back;
- hits from 2.9 blocks and keeps two blocks off, strafing left and right while the next blow charges;
- switches to an axe against a raised shield: an axe blow disables it;
- eats a golden apple on the spot, since a player would catch up during a walk away;
- turns on whoever hits you first, then on the weaker of two who hit the buddy.

**Eating**: when hungry or low on hearts the buddy eats from what is carried. Not while you play the buddy with Take
control; eating starts again as soon as you stop.

**Phantoms**: two game days after the buddy last slept, the buddy lies down in the base's bed at night for a second,
also when nobody else sleeps, which keeps phantoms away. Away from a base, the panel shows that the buddy has not slept.

**At night in your base** the buddy goes to bed, also while following you, until you leave the base or the morning
comes.

**Left in your base** (within 48 blocks of the bed you tapped, while following nobody or told to stay), the buddy keeps
the base: the fields, the furnaces and the chests (everything not kept on hand goes in), each a switch under **Tend the
base** in the panel. Between jobs the buddy waits by the bed and **sleeps at night** in the base's bed, or one beside
it, until the morning. With **Only when others sleep** on (off by default), the buddy goes to bed only while another
player sleeps, as soon as the server allows it, and gets up once nobody else has slept for ten seconds. A sleeper counts
when in view, or anywhere when the server shows the count of sleepers above the hotbar.

The base **grows** by itself: a chest or barrel near the bed that opens becomes one of the base's; one that will not
open is forgotten again. In the base the buddy also does the **chores**:

- **storing**: all of the base's chests count, not only the nearby ones. What the buddy carries goes into a chest that
  holds the same item, else into one where it lay before, and only then into the one with the most room;
- **compacting**: when the chests run full, what goes nine to a block (ingots, raw ore, diamonds, wheat into hay bales,
  ...) is made into blocks at a crafting table of the base. A stack of what a furnace smelts or burns stays, and half a
  stack of coal for torches;
- **seeds** beyond a stack go into a composter, through a hopper on it (or a chest on that hopper) where there is one; a
  full composter is emptied and the bone meal goes into the chests;
- **sheep** with wool are shorn from a cell beside the pen or inside it, never from its gate or doorway, with shears
  from a chest or made of two iron ingots. A gate is not opened while animals stand right behind it, and never left
  open. **Shear sheep** switches it off;
- **strays**: a sheep or cow that got out of its pen is led back with wheat (held only while leading), and the gate is
  shut behind it from inside. **Herd strays** switches it off. With **Use pen gates** off, the buddy climbs over
  carpeted fences instead of opening gates, and does not herd;
- **worn gear**: with **Renew worn gear** on, a tool or armour piece down to a tenth of its durability is swapped for a
  fresh one from a chest, or a new iron one is made from the iron beyond **Leave iron**. Following you away from the
  base, a pickaxe, axe, shovel or sword nearly worn out means a trip home for a new one (by the server's home command,
  or on foot when the base is near) and back. Tools are used up before a new one: of equally good tools the most worn
  goes first, and only a tool with an enchantment (no curse) is kept at three uses left, for a repair;
- **a shield and golden apples**, while guarding you: a shield is made when there is no usable one at hand or in a
  chest, golden apples while the inventory and the chests hold fewer than four together. **Leave iron** and **Leave
  gold** (9 and 64 ingots by default, a block counts nine) are never used for anything made;
- **a bow and arrows**, while guarding you: a bow is made when there is none at hand or in a chest, and arrows while the
  inventory and the chests hold fewer than 32 together;
- **tools**: a kind missing from the inventory and every chest is made at the crafting table, of iron while nine iron
  ingots stay over, else of copper while nine copper ingots stay over, else of stone; never of your diamonds. Sticks and
  planks are made too, from planks or logs; with no wood in the base, a tree of the base outside the garden around your
  chests is chopped and its sapling planted;
- **armour**: a slot with nothing as good as iron, and no such piece in a chest, gets an iron piece made at the crafting
  table, while nine iron ingots stay over;
- **iron blocks** count as nine ingots each; with only blocks left, one is turned into ingots;
- **trampled fields**: farmland of a known field that turned back into dirt is hoed and sown again with what grows
  beside it; a missing hoe is made, of iron while nine ingots stay over. The buddy never lands a jump on farmland, which
  would trample it.

From the chests the buddy takes food, tools, a weapon and armour unasked, but nothing of diamond or netherite: those
stay for you.

## Jobs you switch on with a tap

Tap a block with your bare hand within six blocks of the buddy; the same kind of block again switches the job off. The
jobs are kept over a restart. Digging, chopping and the fields are also the panel's **Mine with you**, **Chop with you**
and **Farm with you**.

| You tap | The buddy |
|---|---|
| farmland, or dirt or sand beside a crop or sugar cane | keeps this field: harvests and replants (a crop itself breaks at the first blow and gives no tap) |
| a log | chops along: the grown trees near you while you chop |
| stone, an ore, dirt, sand | digs along in the tunnel or pit you dig, and puts **torches** where it is dark: about ten blocks apart, on the right-hand wall of a tunnel, from your chests or made of coal and sticks |
| a furnace, blast furnace or smoker | keeps it and the furnaces beside it going: opens it and learns what is in it (to smelt on top, fuel below), then every minute takes out what is done and refills it from your chests |
| a chest or barrel | counts it as one of yours |
| a bed | makes it the base: walks there and sets the server's home there (`/sethome`) |

Where you may not break blocks (someone's claim, spawn) the server sends no tap; in creative a tap breaks the block.

## Farming mobs

**Punch a monster in the pen of your mob farm with your bare hand** while the buddy follows you within six blocks. What
the buddy makes of the spot:

- **a farm**: a closed space of at most 3 by 3 blocks, a place at its edge to hit it from, and one of these: a hopper,
  chest or hopper minecart under it; a stream at its floor that carries the drops out; mobs weakened by a fall; or a
  shaft above it together with a crowd of mobs. A nod, and the buddy goes;
- **no farm**: closed but no farm (a small pool or water hole, a cave nook), nowhere to hit it from, or one of you
  stands in it. A shake of the head, and the buddy's chat says which;
- **open ground**: nothing happens, and your punch is a plain blow, joined at once while the buddy guards you. A mob
  that swims or flies with no floor within four blocks counts as open ground too, unless it drops down a shaft: then the
  buddy waits for it to land.

At a pen the punch is ignored while the buddy stays, is too far, rides something, is in AFK mode or busy with another
job; the panel says why.

**At the pen** the buddy:

- walks crouched to a spot at the pen's edge, never over the pen, and stays crouched there so as not to step off (a blow
  from a mob on the land side can still knock the buddy in);
- hits what comes in at a human pace, and only with the crosshair on it;
- with a sword and someone else (you, a pet, a villager) within its sweep, takes an axe from the hotbar, which does not
  sweep, and the sword again a few seconds after you step away; with no axe in the hotbar, waits until you do;
- walks round to another side about every three minutes (`penSideSwitchSec` in the settings), and to another side for a
  mob out of reach from this one;
- waits and looks into the pen when it is empty.

**Danger:**

- **Low on health** after a blow (five hearts): the buddy goes out of sight of the pen's mobs, eats, and comes back at
  eight hearts or after a minute without damage. A hit there too, or blow after blow, means leaving the farm and
  following you; poison or fire that ticks on is no blow and is waited out there. After backing off three times in ten
  minutes, the buddy leaves the farm and waits out of sight of its mobs, or follows you when there is no such place or
  it cannot be reached; the panel says so.
- **A swelling creeper**, of the farm or any other that comes close, also at night, in a fight with another mob or while
  fetching your things:
  - a creeper of the farm that one blow kills before it goes off gets that blow. One that has just dropped into the pit
    is not struck: the fall shortens its fuse;
  - from any other the buddy gets out of its sight or reach (to another place when the creeper follows) and waits until
    it is safe to go back; a mob that attacks meanwhile is fought once no creeper nearby has swelled for half a second;
  - when a creeper keeps the buddy from the farm for two minutes, or drives the buddy off three times, the farm is left
    once that creeper stops swelling: the buddy waits out of sight of the farm's mobs, or follows you when the creeper
    was not one of the farm's own or there is no such place.
- **A mob with a spear** that reaches the buddy: off to another side, or away until it is gone.
- **Hungry and low with nothing to eat**: the farm is given up; the buddy follows you again and fetches food.

**At night**, when someone sleeps or you lie in bed nearby, the hitting stops (unless `penNightPause` is off in the
settings) and the buddy waits out of sight of the pen's mobs, so that the server can count the buddy as idle and the
night can pass. With a bed at hand the buddy goes to bed, and the farming goes on in the morning.

**Stopping**: crouch twice within six blocks of the buddy, without looking. The answer is one crouch, a step back out of
sight of the pen's mobs and a wait there; in a base, the base is kept. Look at the buddy and crouch twice during that
step back, and the buddy follows you at once. The panel's **Stop farming** works like the sign. The farming also ends,
and the buddy follows you again, on a tap, a mode, a job, AFK mode, stopping the following, or **Farm mobs** switched
off in the panel.

**The buddy gives up unasked**, and says why:

- on a death, a teleport or a trip to another dimension;
- more than 32 blocks from the pen;
- on a fall into the pen, also during the step back: the panel asks you to get the buddy out, and from then on the pen's
  mobs are fought down there;
- when the pen is gone, a spot cannot be reached, or there is no food;
- when AFK mode or another job keeps the buddy from the farm for twenty seconds. A fight, a menu or steering from the
  panel only pauses the farming.

After a restart of the game or the server, the farming goes on once the buddy stands at the pen again; never after a
death.

**Guarding you at a pit**, a farm or not: a mob out of reach more than a block below the buddy's feet, in a closed space
that could not be climbed or swum out of, or in a farm's cell, is left be, because reaching it means falling in; the
panel says so once. A mob is fought as ever:

- in a dip one block deep, or a crater that can be climbed out of in steps;
- in a water hole that can be swum out of, also over mud or soul sand;
- in a pit with a door or gate to ground beyond it, or a room behind a door on the same level (an iron door or a
  trapdoor is no way out, and a cell under a drop shaft is a farm's, door or not);
- once it climbs out of a pit and comes for you;
- on the rim of the pen being farmed, and a phantom over it.

When the buddy stays somewhere and a mob that is left be lands a hit, the buddy takes cover: in the nearest place out of
sight of every such mob nearby (also from the far side of its pit), or, where every place near is in sight, more than 16
blocks from the mob and its pit. There the buddy waits half a minute before going on. Hit there anyway, or unable to get
there, the buddy tries another place, three in all. With nowhere to go the following starts again; in a base the base is
kept unless the buddy is down to three hearts. The panel says so, also when the buddy is shot at in the base again and
again.

**What the buddy kills**: hostile mobs only. No boss, warden, shulker, named mob or pet; an enderman or a piglin only
when it is angry or one blow kills it. The experience the mobs drop goes to the buddy, as in vanilla. By default the
farming goes on when you go offline; `penOwnerAwayMin` in the settings ends it that many minutes after you left.

## How the buddy gets about

The way is planned block by block and walked with the real keys: level ground and slabs, jumps up, drops of up to three
blocks (deeper only into water), ladders and vines, doors and gates, swimming, and jumps over gaps of up to five blocks
where a miss is survivable. Where walking does not get there, the buddy also:

- **digs** through one or two blocks of natural ground (earth, stone, sand, leaves) to go on at the same height, with
  the fastest tool at hand; never a block with something inside it, one beside water or lava, or one with sand or gravel
  above it;
- **builds up** a block at a time, jumping and placing a block underfoot;
- **bridges** a gap, crouched at the edge.

These come into use only where there is no other way. The blocks placed are ones that can be spared (dirt, cobblestone
and the like), and they stay. Within 64 blocks of a base's bed or 32 of a known chest the buddy does not dig, because
those blocks are your build, but still builds up and bridges, so a step two up or a gap in or by the base is no
obstacle. **Dig when blocked** and **Bridge and pillar** in the panel switch them off. The way keeps clear of lava,
fire, magma, cactus, berry bushes, cobwebs, powder snow and monsters where there is a way round.

**When stuck** (twelve seconds without getting anywhere, half a minute of going to and fro on the same few blocks, or
two seconds held in a cobweb, powder snow or a berry bush), the buddy shuffles loose with a step aside, a step back and
jumps, and plans another way. **What fails twice is left alone**: a spot that could not be reached, or a block that
could not be dug, twice within ten minutes, is not tried again for ten minutes, and the buddy says so once.

## How the buddy decides

One thing at a time gets the buddy's hands, in this order:

1. staying alive: air under water, a fire, getting loose, a creeper swelling at the buddy at a farm
2. a menu in use
3. a fight
4. the corpse guard
5. your taps
6. farming mobs
7. carrying things home
8. keeping the base
9. staying
10. sleeping
11. riding
12. working along
13. following

The first that wants the moment takes it, and what was going on carries on as soon as nothing above it wants the hands.
The panel shows which one has them now. Steering or using the inventory in the panel comes before all of it: meanwhile
nothing else moves the buddy or clicks in that inventory.

## What the buddy carries

- the two best of each tool kind and of swords (by speed or damage, enchantments, durability left), and the weapon used
  in fights;
- one spare piece per armour slot besides what is worn;
- up to two stacks each of food, torches and rockets;
- two totems, eight golden apples and four enchanted golden apples;
- the best bow and two stacks of arrows;
- saplings, and a stack of each kind of seed;
- a shield, compass, clock, map, elytra and shears.

When all the food is poor (two points a bite or less, such as beetroot), better food is fetched from a chest that has
some. Everything else goes into your chests, or is thrown at your feet when no chest of yours is near.

← [Back to the README](../README.md)
