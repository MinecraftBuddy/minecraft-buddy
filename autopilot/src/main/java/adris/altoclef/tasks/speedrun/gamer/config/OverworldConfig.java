package adris.altoclef.tasks.speedrun.gamer.config;

import adris.altoclef.tasks.speedrun.gamer.portal.PortalMold;

import java.util.ArrayList;
import java.util.List;

// owner: overworld prep worker (GATHER, IRON, PORTAL). add knobs here, not in GamerConfig
public class OverworldConfig {
    // catalogue names ("iron_pickaxe", "bucket"), counts are "hold this many in total"
    public static class KitItem {
        public String item;
        public int count = 1;

        public KitItem() {
        }

        public KitItem(String item, int count) {
            this.item = item;
            this.count = count;
        }
    }

    public enum ArmorPlan {
        // 24 iron: what keeps a bed explosion survivable (see gamer-design.md 0.6)
        FULL_IRON,
        CHEST_HELMET,
        NONE
    }

    // the wooden axe is for logs (KitPlanner puts it right after the first few) and the two stone picks are one to wear out
    // and one spare. any iron pick in the bag covers both. the stone sword is the weapon (WeaponPick takes a sword over any
    // axe: it sweeps a crowd and the charge comes back faster). the planner sizes the wood for all of this plus the iron kit
    // (bump GamerConfig.VERSION when you change a default kit list, saved files would keep the old one otherwise)
    public List<KitItem> starterKit = new ArrayList<>(List.of(
            new KitItem("wooden_axe", 1), new KitItem("stone_pickaxe", 2), new KitItem("stone_sword", 1),
            new KitItem("furnace", 1)));
    // armor comes from armorPlan, wool from end.beds. same deal: bump GamerConfig.VERSION when this changes
    public List<KitItem> ironKit = new ArrayList<>(List.of(
            new KitItem("iron_pickaxe", 1), new KitItem("iron_sword", 1), new KitItem("bucket", 2),
            new KitItem("flint_and_steel", 1), new KitItem("shield", 1), new KitItem("shears", 1),
            // sticks only, no iron. for ladder clutching on the long falls in the nether (one ladder gets picked back up)
            new KitItem("ladder", 3)));
    public ArmorPlan armorPlan = ArmorPlan.FULL_IRON;
    // stone-or-better pickaxes the bag holds on top of the one we dig with when the portal is entered (PortalPlanner.gate). the
    // nether digs netherrack all day and a stone pick is 3 cobble and 2 sticks, nothing next to running out. a diamond pick in the
    // bag (see diamondPick) needs none of them. 0 = off. a plain
    // number and not a kit list, so an old saved config just gets 3 and GamerConfig.VERSION stays
    public int netherSparePicks = 3;
    // shields in the bag on top of the one in the offhand when we go through. the blaze fight alone can eat most of one. gather
    // pays for them with the rest of the kit (KitPlanner.ironKit). 0 = off. a plain number, so an old saved config keeps its
    // own, and GamerConfig.VERSION moved when the default went from 2 to 3 (GamerConfigs.migrate)
    public int netherSpareShields = 3;
    // iron swords in the bag on top of the one in hand. a long pearl hunt wears one out and nothing in the nether makes a new one.
    // priced like the spare shields (KitPlanner.ironKit). a diamond sword replaces the whole entry, see DiamondSpend. 0 = off. a
    // plain number, so an old saved config just gets 1 and GamerConfig.VERSION stays
    public int netherSpareSwords = 1;
    // diamonds in the bag (mined, or out of a chest) get crafted into upgrades instead of sitting there: a pick, then armor, see
    // DiamondSpend. off = the plan is exactly what it was. a plain flag, not a kit list, so an old saved config just gets true
    public boolean diamondSpend = true;
    // dig for a diamond pick before the portal: the iron one wears out halfway through the nether. it goes after everything the
    // portal needs (a timed out IRON can still skip with the essentials) and gives up after diamondPickMinutes of digging, or when
    // the IRON budget is nearly gone. plain scalars, an old saved config just gets these
    public boolean diamondPick = true;
    public double diamondPickMinutes = 8;
    // raw gold for the piglin helmet, dug in IRON when an ore is already in the loaded chunks. gives up after this many minutes of
    // digging. a plain scalar, an old saved config just gets 3
    public double goldHuntMinutes = 3;
    // IRON's sheep chase moves on after this many minutes with no sheep in view (a death starts it over). only IRON's wool need reads
    // it, END_PREP gets the beds whatever happened here. a plain scalar too
    public double woolHuntMinutes = 6;

    // nutrition points, a cooked steak is 8. enough for an hour or so, the food chain eats on its own
    public int minFoodUnits = 70;
    public int targetFoodUnits = 100;
    // a food trip only starts under this, then runs to refillStopFoodUnits (FoodPlan.wantsRefill). with no gap one bite at 70 sent the
    // bot off whatever it was doing for a single unit. on the surface a trip starts under minFoodUnits instead (FoodPlan.surfaceTopUp),
    // the hunt is a walk there and a climb from a mine
    public int refillStartFoodUnits = 45;
    // and once a trip starts it runs to this, not to minFoodUnits. a cave leg eats 40-50 units, so a trip that stopped at 70 bought
    // one cave leg and the next surface visit was another hunt. 130 is about a stack of steak, one smoker load
    public int refillStopFoodUnits = 130;
    // the portal gate fills to this before the nether (refillStopFoodUnits stays the stop for every overworld trip). the nether eats
    // about 8 units a minute in the fights and the walk home from 40 is five minutes of overworld, so go down with a lot instead.
    // a full bag still stops at the minimum
    public int netherEntryFoodUnits = 210;
    // the IRON phase leaves the mine for food only below this (or on the surface, up to minFoodUnits).
    // a bot with an apple and six mutton used to climb out for a hunt in the middle of a vein
    public int minHeldFoodUnits = 24;
    // logs the gather chops on top of its wood budget while the food is still to be hunted, so the raw meat has fuel the moment
    // it is in the bag (a smoker's four, its table, and a pile of a dozen chicken's worth of burning). 0 = none and the cook waits for coal. the
    // surplus is "spare" wood to WoodReserve, which is the only reason the furnace may burn it
    public int cookFuelLogs = 14;

    // lava casting gets this long (counted from when the cast starts, after the prep) before the portal phase goes the
    // obsidian way. the whole portal phase has 14 minutes and the prep before the cast eats some of them, 7 leaves the
    // obsidian way a real chunk of the budget (it may need a diamond pickaxe first, that is slow)
    public double castGiveUpMinutes = 7;
    // ...but only once no frame cell has turned obsidian for this long either. a cell takes ~20 s when it works, so 3 minutes
    // with none is a stuck cast, not a slow one. the phase budget still ends it either way
    public double castStallSeconds = 180;
    // ruined portal chests are only looted when we have already seen one this close, never searched for
    public int ruinedPortalLootRadius = 60;
    // a chest that is not emptied after this long gets written off (blocked, buried, whatever)
    public double lootChestSeconds = 60;
    // village blacksmith chests (the iron need only): how far away a seen chest may be, and how close to a grindstone,
    // smithing table or blast furnace it has to sit to count as a blacksmith's
    public int villageLootRadius = 48;
    public int villageChestJobRadius = 6;
    // per run, not per phase: at most this many chests and this many seconds spent in them, then we mine like normal
    public int villageLootMaxChests = 3;
    public double villageLootSeconds = 240;
    // village beds, while we are short of beds: how far away a seen bed may be, and how close to a job block, a bell or
    // another bed it has to sit to count as a village's. a bed is a bed, no wool and no shears needed for it
    public int villageBedRadius = 48;
    public int villageBedEvidenceRadius = 16;
    // per run: this long in total spent on beds, and this long on any one of them before it is written off
    public double villageBedSeconds = 120;
    public double villageBedEachSeconds = 20;

    // sleeping through the night (altoSleepAtNight, NightSleep). numbers only, so an old saved config just gets these. each night
    // gets this many tries and this many seconds in all, after that the bot works the night like it always did
    public int nightAttempts = 3;
    public double nightSeconds = 150;
    // no more than this many blocks of pillar to get a bed out of the monsters' reach, and this much air between the highest monster
    // and the bottom of the box the game looks in (the box is 5 down, the extra is for the ones that walk up a slope)
    public int nightMaxPillar = 16;
    public double nightClearMargin = 1;
    // after a try that went nowhere (no blocks for the pillar, a roof, water) look again no sooner than this
    public double nightCooldownSeconds = 20;
    // in the bed without the night passing (other players are not asleep) for this long, we get up and call it a failed try
    public double nightSleepSeconds = 30;
    // waking up at night only counts as a hit that woke us after this many ticks of still reading night. the client learns the
    // morning from a time packet that goes out every 20 ticks
    public int nightWakeGraceTicks = 30;
    // the evening before (NightPrep): a bed in the bag by the time the window opens. both leads are day ticks before the earliest the
    // game lets anyone sleep (12010, full rain). a craft is seconds, so its lead is two minutes; a wool trip is a walk and a chase
    // after three sheep, so it starts four and has 150 s, which leaves it a minute and a half of slack for the craft. each part gets
    // its seconds once an evening and then stays quiet. a sheep this close counts as known, which is about all the game shows us:
    // at 40 a bot down a mine never knew of one and did the same walk after dark. the wool clock is what ends a bad trip, not this
    public int nightCraftLeadTicks = 2400;
    public int nightWoolLeadTicks = 4800;
    public double nightWoolSeconds = 150;
    public double nightCraftSeconds = 45;
    public int nightWoolRadius = 128;
    // a bed standing this close counts as one we can use (the old bed task's number)
    public int nightBedRadius = 40;

    // coal on the way (see DetourRules, DetourSpec.COAL): while GATHER and IRON mine, coal ore this close is worth a short detour,
    // because a smelt that runs short of fuel sends us out for coal anyway. the reach is WalkCost, blocks across plus 4 per block
    // of height, so about 12 flat or 3 up or down. it stops at what the plan still burns (DetourSpec.coalNeed, never past 16) or
    // coalSideCap in the bag, whichever is lower, and a detour gets coalSideSeconds before the vein is banned for a while
    // (BanPolicy.COAL)
    public double coalSideBudget = 12;
    public int coalSideCap = 24;
    public double coalSideSeconds = 30;
    // gravel on the way (DetourSpec.GRAVEL): same reach and clock, for flint until the flint and steel is made. gravelSideBlocks
    // is the most a detour digs (flint is a 10% roll, 10 blocks is ~65% to see one), then it ends with or without flint
    public double gravelSideBudget = 12;
    public int gravelSideBlocks = 10;
    public double gravelSideSeconds = 30;
    // diamonds on the way (DetourSpec.DIAMOND): a diamond ore this close, in sight and with no lava touching it, is worth a short
    // detour when we hold an iron pick or better, whatever the plan is up to. nothing asks for a count of them, so it just
    // holds up to diamondSideCap and stops. diamondSide = false turns it off (a need of 0 never starts anything). a deepslate
    // diamond takes about a second with an iron pick, so the 30 s clock covers a vein
    public boolean diamondSide = true;
    public double diamondSideBudget = 12;
    public int diamondSideCap = 24;
    public double diamondSideSeconds = 30;
    // gold on the way (DetourSpec.GOLD): piglins only leave us alone in the nether with a gold piece on, and nothing else ever
    // digs for the gold. same reach and clock as the diamonds, with an iron pick, while the piece is short. goldSide = false
    // turns it off
    public boolean goldSide = true;
    public double goldSideBudget = 12;
    public double goldSideSeconds = 30;

    // casting with no lava in sight for this long, while we already hold a diamond pickaxe, flips to obsidian early. counted
    // from the last frame cell that turned obsidian, or from the cast start when none has. without the pickaxe we keep
    // wandering for a lake until castGiveUpMinutes
    public double noLavaSeconds = 150;
    // throwaway blocks to carry through the portal (pillaring, bridging, plugging lava)
    public int portalBuildBlocks = 32;
    // what the portal gate fills to. just enough to get through and get out of trouble, the real stack gets dug on the other side
    // (NetherRestock.ARRIVAL_TARGET), netherrack comes off way faster than dirt does up here. not 64: KitRunner tells a stock-up from
    // this one by the count, and the stock-up lands on 64. portalBuildBlocks stays the small number everything else shares
    public int netherEntryBlocks = 56;
    // the portal gate only tops up under this: the cast, one escape's budget and a spare. a bag over it is not worth a trip to the
    // stone. portalBuildBlocksLow stays the mark for everything else
    public int netherEntryBlocksLow = 40;
    // the refill only starts under these marks (BuildBlocks), then runs to the target. a pillar or two, or a bridge, takes a
    // bag from 32 to 20 and that is not a reason to go mining. while a furnace cooks the base is 8: enough for a few pillars.
    // at the portal it is what the lava pool still places (the slab and the two top row pads) plus a few for the way over. the
    // stack we bridge lava with is the nether's own arrival dig. walking back into the phase with more than that does not start
    // another round
    public int ironBuildBlocksLow = 8;
    public int portalBuildBlocksLow = PortalMold.SLAB.length + (int) PortalMold.CASTS.stream().filter(c -> c.pad != null).count() + 12;
    // the stock-up on top of a full base (smeltExtras build_blocks) only starts under this, and gets one trip per phase entry
    public int stockUpBuildBlocksLow = 16;
    // (old saved files may still carry tableRecoverRadius, tablePickupSeconds, tableUseCooldownSeconds and
    // tableRecoverCooldownSeconds, gson skips them: every station number lives in WorkbenchRules now)

    // smelting in the background (see SmeltFiller): the iron goes in the furnace and the bot does other things while it cooks,
    // anywhere. a furnace in a chunk that does not tick just pauses, so the bot remembers where it is and walks back when the
    // output is due. (old saved files may still carry furnaceLeashBlocks, furnaceMaxPullbacks, furnaceWaitSeconds and
    // furnaceStaleSeconds, gson skips them: how long to wait, collect or give up is FurnacePlan's clock now)
    // planks to stock for beds while waiting (3 a bed, the shield and sticks come on top of this)
    public int smeltBedPlanks = 12;
    // stock-up for when everything else is done and the iron is still cooking. one entry each: "food" = units over
    // targetFoodUnits, "wool_beds" = extra beds worth of wool, "build_blocks" = over portalBuildBlocks, "log" = logs to hold.
    // a kit list too: bump GamerConfig.VERSION when the default changes
    public List<KitItem> smeltExtras = new ArrayList<>(List.of(
            new KitItem("food", 30), new KitItem("build_blocks", 32), new KitItem("log", 8)));

    // iron golem hunt (the iron need only): 100 hp, 3 to 5 ingots, and it cannot hit a player whose feet are above its
    // head, so we stack a pillar next to it and poke it from there. see GolemRules for the vanilla numbers
    public int golemHuntRadius = 32;
    // pillar blocks we must carry (the golem hunt eats 3 to 5), and the health we want before picking this fight
    public int golemMinBlocks = 5;
    public double golemMinHealth = 14;
    // feet this far above the golem's head. golem is 2.7 tall and we stand on whole blocks so 0.3 means "3 blocks up"
    public double golemSafeMargin = 0.3;
    // never stack more than this (a golem down a hole is not worth a tower)
    public int golemMaxPillar = 5;
    // reach is checked from the eye with the vanilla 3.0, anything the golem walks off to for longer than this is a lost cause
    public double golemOutOfReachSeconds = 5;
    // the fight itself, the walk up to it, and the pillar, each with its own clock
    public double golemFightSeconds = 45;
    public double golemApproachSeconds = 40;
    public double golemPillarSeconds = 20;
    // below this we stop swinging and just sit on the pillar until it is safe to leave
    public double golemAbortHealth = 8;
    // once we have hit it, we stay up this long after the last hit (anger lasts 20 to 39 s) before walking down
    public double golemCalmSeconds = 45;
    // other monsters this close and the hunt does not start (or ends)
    public int golemHostileRadius = 16;
    // fights per run, a golem that got away twice is not going to be easier the third time
    public int golemMaxAttempts = 2;
}
