package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.container.SmeltSplit;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.ArmorPlan;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.KitItem;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.ToolWear;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// pure: what is still missing from the overworld kit, in the order we want it. never asks for what is already held
// (inventory, worn armor, offhand, and better tiers count: a diamond pickaxe is an iron pickaxe, a water bucket is a bucket)
public final class KitPlanner {
    // wooden first, so a stone axe is a wooden axe too and nobody re-makes the axe because the first one got upgraded
    private static final List<String> TIERS = List.of("wooden", "stone", "iron", "diamond", "netherite");
    private static final List<String> TOOLS = List.of("pickaxe", "sword", "axe", "shovel", "hoe");
    private static final List<String> ARMOR = List.of("helmet", "chestplate", "leggings", "boots");
    // stations get placed and left behind, so once the starter phase is over we stop asking for them
    private static final Set<String> PLACEABLE = Set.of("furnace", "crafting_table");
    // chestplate first: biggest chunk of armor points per trip
    private static final List<String> FULL_IRON = List.of("iron_chestplate", "iron_helmet", "iron_leggings", "iron_boots");
    private static final List<String> CHEST_HELMET = List.of("iron_chestplate", "iron_helmet");
    private static final Map<String, Integer> INGOTS = Map.ofEntries(
            Map.entry("iron_pickaxe", 3), Map.entry("iron_sword", 2), Map.entry("iron_axe", 3),
            Map.entry("iron_shovel", 1), Map.entry("iron_hoe", 2), Map.entry("bucket", 3),
            Map.entry("shield", 1), Map.entry("shears", 2), Map.entry("flint_and_steel", 1),
            Map.entry("iron_helmet", 5), Map.entry("iron_chestplate", 8), Map.entry("iron_leggings", 7),
            Map.entry("iron_boots", 4));

    private KitPlanner() {
    }

    static final int TABLE_PLANKS = 4;

    // wood -> axe -> the rest of the wood -> table -> stone tools -> furnace, then enough food to survive the next phase.
    // the engine hands in the tick's FoodPlan, the short form (package only, so the phases cannot reach for it) builds its own
    static List<KitNeed> gather(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return gather(f, cfg, endBeds, FoodPlan.ofBeds(f, cfg, endBeds));
    }

    // (no ctx at hand, so the wood budget is built here. the engine passes the tick's, see GamerContext.wood)
    public static List<KitNeed> gather(GamerFacts f, OverworldConfig cfg, int endBeds, FoodPlan food) {
        return gather(f, cfg, endBeds, food, WoodBudget.of(f, cfg, endBeds));
    }

    public static List<KitNeed> gather(GamerFacts f, OverworldConfig cfg, int endBeds, FoodPlan food, WoodBudget wood) {
        List<KitNeed> out = new ArrayList<>(starter(f, cfg, endBeds, true, new ArrayList<>(), food, wood));
        addRefill(out, food);
        // food sits last here, so a hungry bag walks past every pig on its way to the next log. one loaded close by leads, up to
        // the floor and no further: the rest of the refill stays where it was. not before the axe though, punching eight cows
        // to death with bare hands while the first tree waits is a bad look and a slow one
        boolean axeOwed = out.stream().anyMatch(n -> isAxe(n.catalogueName()));
        if (food.held() < food.floor() && f.preyNear() && !axeOwed) {
            out.add(0, new KitNeed(KitNeed.FOOD, food.floor()));
        }
        // no cook here: GATHER used to wait out the smoker with nothing else to do, and the meat cooks just as well while the
        // iron phase starts (it leads the plan on the surface, see CookGate.leads, and the output is collected on the way)
        return out;
    }

    // everything the overworld still owes us before the portal, in order: starter tools, food, ONE combined iron ingot
    // need (so the bot mines and smelts once instead of once per item, bar the early batch), the crafts, armor on, wool,
    // food top up.
    // food can show up twice (min early, target at the end), the first unsatisfied one is the one that matters.
    // the engine passes the tick's FoodPlan, the short form (package only) builds its own
    static List<KitNeed> plan(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return plan(f, cfg, endBeds, FoodPlan.ofBeds(f, cfg, endBeds));
    }

    public static List<KitNeed> plan(GamerFacts f, OverworldConfig cfg, int endBeds, FoodPlan food) {
        return plan(f, cfg, endBeds, food, WoodBudget.of(f, cfg, endBeds));
    }

    public static List<KitNeed> plan(GamerFacts f, OverworldConfig cfg, int endBeds, FoodPlan food, WoodBudget wood) {
        // a worn pick we still carry gets replaced after the iron, not in the middle of it (see starter)
        List<KitNeed> late = new ArrayList<>();
        // under the floor food leads whatever the starter is missing (a bag with no pick has it and its wood chain up front), but
        // only up to the floor: enough to stop starving, then the tools (hunting a whole refill with bare hands is a lot of fist
        // fights), then the rest of the refill where it always sat. at or over the floor the starter goes first and the refill
        // behind it, like it always did. a bag that already has its pick and weapon and only owes logs keeps the one piece refill
        boolean hungry = food.held() < food.floor();
        List<KitNeed> out = new ArrayList<>();
        List<KitNeed> starter = starter(f, cfg, endBeds, false, late, food, wood);
        boolean split = hungry && starter.stream().anyMatch(n -> isTool(n.catalogueName()));
        if (split) {
            out.add(new KitNeed(KitNeed.FOOD, food.floor()));
        } else if (hungry) {
            // nothing to arm, so the refill is the whole job and goes up front in one piece
            addRefill(out, food);
        }
        out.addAll(starter);
        if (!hungry || split) {
            addRefill(out, food);
        }
        out.addAll(iron(f, cfg, endBeds));
        out.addAll(late);
        addFood(out, food, food.target());
        addCook(out, f, cfg, endBeds, wood);
        // a dry spell (nothing to hunt for minutes): the next kit step goes first, unless an animal is right here (FoodDry)
        return FoodDry.defers(f.foodDryLatched(), f.preyNear()) ? FoodDry.defer(out) : out;
    }

    // gathering: all the wood comes first, so nothing climbs out of a cave for one more log later. a small batch (table
    // and axe), the axe, the rest of the budget with the axe in hand, then the stone tools.
    // not gathering (IRON on): stations are not asked for again, a lost axe stays lost and the spare pick is not worth a
    // trip. wood is only asked for before the first ore, while the surface is still right here (bar the cook's logs, near the surface).
    // `late` is where the IRON phase parks a pick replacement: a stone pick that is merely worn (85%, see wornOut) is still
    // a pick, and swapping it mid-mine once cost a 34 s log trip. only a bag with no pick at all keeps it up front
    private static List<KitNeed> starter(GamerFacts f, OverworldConfig cfg, int endBeds, boolean gathering, List<KitNeed> late,
                                         FoodPlan food, WoodBudget wood) {
        List<KitNeed> out = new ArrayList<>();
        List<KitItem> axes = new ArrayList<>();
        List<KitItem> rest = new ArrayList<>();
        for (KitItem k : cfg.starterKit) {
            if (isAxe(k.item)) {
                axes.add(k);
            } else if (k.item.equals("furnace") && f.stations().furnace().heldForPlanner()) {
                // standing on the ground (cooking the meat in it, say) and coming back to the bag: not a second one to craft
                continue;
            } else if (gathering || !PLACEABLE.contains(k.item)) {
                rest.add(k);
            }
        }
        if (gathering) {
            if (axes.stream().anyMatch(a -> missing(f, a.item, a.count) > 0)) {
                addLogs(out, f, wood.firstBatchLogs());
            }
            for (KitItem a : axes) {
                addItem(out, f, a.item, a.count);
            }
            addLogs(out, f, wood.gatherChopLogs(food));
            // every cobble the stone kit eats in one trip, before the first stone craft, so the bot doesn't go back down
            // for the furnace's cobble after crafting the axe
            addCobble(out, f, stoneNeed(f, cfg));
        } else {
            // (the first ore ends the kit's chop, not the cook's: the budget asks, see ironChopLogs)
            addLogs(out, f, wood.ironChopLogs(f));
        }
        for (KitItem k : rest) {
            // the spare pick is for the gather. once underground one fresh pick is the plan and the next is a craft
            boolean pick = !gathering && k.item.endsWith("_pickaxe");
            addItem(pick && holdsAnyPick(f) ? late : out, f, k.item, pick ? Math.min(k.count, 1) : k.count);
        }
        return out;
    }

    private static boolean isTool(String name) {
        return name.endsWith("_pickaxe") || name.endsWith("_axe") || name.endsWith("_sword");
    }

    // stone-or-better picks we still need to make to walk into the nether with the one we dig with plus netherSparePicks that are
    // not worn out. have() counts the better tiers and leaves worn ones out (they are 'spent'), so an iron pick plus two stone
    // ones is three. 0 when the bag has them, or holds a diamond pick
    public static int sparePicksShort(GamerFacts f, OverworldConfig cfg) {
        // a diamond pick digs netherrack for the whole nether and then some, the stone spares were for an iron pick running out
        if (cfg.netherSparePicks <= 0 || f.count(Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE) > 0) {
            return 0;
        }
        return Math.max(0, 1 + cfg.netherSparePicks - have(f, "stone_pickaxe"));
    }

    // any pickaxe at all in the bag, worn out ones included: it still breaks blocks, it just is not one we count on
    public static boolean holdsAnyPick(GamerFacts f) {
        for (String tier : TIERS) {
            for (Item pick : exact(tier + "_pickaxe")) {
                if (f.count(pick) + f.spent(pick) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    // the axe for the logs, not the weapon: the swords are in the kit and they are crafts like any other. only the wooden axe
    // gets fetched before the stone pick
    static boolean isAxe(String name) {
        return name.equals("wooden_axe");
    }

    // ore in the bag, raw or cooking: the cave trip has begun and wood is no reason to leave it.
    // an early load in flight counts too, mid-move the bag is empty and we'd go chop a tree with the furnace open
    static boolean ironStarted(GamerFacts f) {
        return f.count(Items.RAW_IRON, Items.IRON_INGOT) + f.pendingOutput(Items.IRON_INGOT) > 0 || f.earlyLoadInFlight();
    }

    // logs on top of the wood budget so the meat has something to burn when it comes in. no meat in the bag and food still
    // short (it is the last thing in the gather, the logs are chopped well before the first animal), and the spare logs we
    // already hold count, so the request settles at cfg.cookFuelLogs of surplus instead of chasing itself. past the food the
    // answer is 0 whatever the bag says, or burning them would send the gather back to the trees
    static int cookFuelLogs(GamerFacts f, OverworldConfig cfg, int endBeds, int budgetLogsShort) {
        return cookFuelLogs(f, cfg, endBeds, budgetLogsShort, FoodPlan.ofBeds(f, cfg, endBeds));
    }

    // (with no raw meat in the bag held is the plain bag plus pending, the raw correction is 0 by then). keyed on the same refill
    // as the food need, logs for a hunt that is not coming are just more trees
    static int cookFuelLogs(GamerFacts f, OverworldConfig cfg, int endBeds, int budgetLogsShort, FoodPlan food) {
        return WoodBudget.of(f, cfg, endBeds).cookFuelLogs(food, budgetLogsShort);
    }

    private static void addLogs(List<KitNeed> out, GamerFacts f, int logs) {
        if (logs > 0) {
            out.add(new KitNeed("log", held(f, "log") + logs));
        }
    }

    private static void addCobble(List<KitNeed> out, GamerFacts f, int cobble) {
        if (cobble > 0) {
            out.add(new KitNeed(COBBLE, held(f, COBBLE) + cobble));
        }
    }

    // the catalogue name of the stone gather. a gathering name (KitNeed), so it never holds the table
    public static final String COBBLE = "cobblestone";
    // cobble a stray placement can eat before the crafts, so one misplaced block is not a second trip
    private static final int STONE_SLACK = 2;
    // cobble per item: the tool shapes (pickaxe and axe 3, sword and hoe 2, shovel 1) and the furnace's ring of 8.
    // the kit makes the sword (2 cobble) and not a stone axe, so a fresh run mines 18. the split smelt's extra furnaces are not
    // in here: the mine brings back far more spare cobble than 16, and SmeltSplit.loads uses what is spare when it decides
    private static final Map<String, Integer> COBBLE_COST = Map.of(
            "stone_pickaxe", 3, "stone_axe", 3, "stone_sword", 2, "stone_hoe", 2, "stone_shovel", 1, "furnace", 8);

    // cobble still to mine for every stone item the kit is missing, slack included, held cobble taken off. 0 when we hold
    // enough. only plain cobblestone counts as held: the catalogue recipes (and so CraftInTableTask) put Items.COBBLESTONE
    // in the slots and nothing else, so cobbled deepslate in the bag would be a second trip wearing a disguise.
    // the slack is part of what we hold ourselves to (same as woodNeed), the crafts eat held and wanted in step so the
    // answer stays 0 through them. the gather only: once ore is in the bag a worn pick is a just in time craft
    public static int stoneNeed(GamerFacts f, OverworldConfig cfg) {
        // a furnace of ours standing close by is skipped as a need above, so it is no 8 cobble here either (stoneFloor knew)
        int wanted = stoneWanted(f, cfg, f.stations().furnace().heldForPlanner());
        if (wanted <= 0) {
            return 0;
        }
        return Math.max(0, wanted + STONE_SLACK - held(f, COBBLE));
    }

    // how many loads the iron still to smelt goes in (the coal rounds per load, DetourSpec.coalNeed). before the batch is decided
    // it is the split the owed iron would get, a coal or two too many if the cobble ends up short. a blast furnace takes it all
    public static int smeltLoads(GamerFacts f, OverworldConfig cfg) {
        if (f.count(Items.BLAST_FURNACE) > 0) {
            return 1;
        }
        SmeltSplit.Batch batch = f.smeltBatch();
        if (batch != null && batch.splitting()) {
            return Math.max(1, batch.loadsLeft());
        }
        return SmeltSplit.wanted(ironOwed(f, cfg));
    }

    // ingots still to smelt: the kit's, less the bag and what is cooking. the same sum CollectIronIngotTask splits
    static int ironOwed(GamerFacts f, OverworldConfig cfg) {
        return ingotsNeeded(f, cfg) - f.count(Items.IRON_INGOT) - f.pendingOutput(Items.IRON_INGOT);
    }

    // the cobble the stone items still missing from the kit will eat, held cobble NOT taken off and no slack. this is what
    // the bag has to keep hold of for the crafts, as opposed to stoneNeed, which is what is left to go and mine. the
    // movements get it as a floor (BotBehaviour.setReserveFloor): a task only reserves the recipe it is on right now (6 for
    // two picks), the axe and the furnace were up for grabs as scaffolding and the gather came right back for them.
    // a furnace of ours standing close by is a furnace we own (8 cobble the kit is not about to spend), same as a placed
    // table is held for the planks. plain cobblestone only, the build blocks the iron phase places on purpose are not in here
    public static int stoneFloor(GamerFacts f, OverworldConfig cfg) {
        return stoneWanted(f, cfg, f.stations().furnace().heldForPlanner());
    }

    // the cobble the stone tools still eat, the furnace left out: what "the bag can make a furnace" must not count
    // (StationHook.cobbleOwed). the furnace's own 8 are not owed to anything else, that is what they would be spent on
    public static int toolCobble(GamerFacts f, OverworldConfig cfg) {
        return stoneWanted(f, cfg, true);
    }

    private static int stoneWanted(GamerFacts f, OverworldConfig cfg, boolean furnaceStanding) {
        List<KitItem> all = new ArrayList<>(cfg.starterKit);
        all.addAll(ironKit(f, cfg));
        int wanted = 0;
        for (KitItem k : all) {
            Integer cost = COBBLE_COST.get(k.item);
            if (cost == null) {
                continue;
            }
            int missing = missing(f, k.item, k.count);
            if (furnaceStanding && k.item.equals("furnace")) {
                missing = Math.max(0, missing - 1);
            }
            wanted += cost * missing;
        }
        return wanted;
    }

    // logs still to fetch for everything wooden the overworld will craft, one log of slack included, 0 when we hold
    // enough (the arithmetic is WoodBudget's, this is the short form for a caller with no budget)
    public static int woodNeed(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return WoodBudget.owed(f, cfg, endBeds, true);
    }

    // gathering is false once the plan is past the gather: a lost axe is not worth a log, and neither are the beds
    // (package only so the wood golden can ask the iron form too)
    static int woodNeed(GamerFacts f, OverworldConfig cfg, int endBeds, boolean gathering) {
        return WoodBudget.owed(f, cfg, endBeds, gathering);
    }

    // planks for a table (or two): with no tool made yet the first one is still ahead of us, and the second is the
    // spare for the iron crafts if the first is too far to walk back to
    static int tablePlanks(GamerFacts f) {
        if (tableHeld(f)) {
            return 0;
        }
        boolean madeTools = f.count(ItemHelper.WOODEN_TOOLS) + f.count(ItemHelper.STONE_TOOLS) + f.count(ItemHelper.IRON_TOOLS) > 0;
        return TABLE_PLANKS * (madeTools ? 1 : 2);
    }

    // a table standing next to us is coming back to the bag, so it is as good as in it. without this the plan asked for
    // table planks the second the table went down and walked off to chop with the axe still uncrafted
    static boolean tableHeld(GamerFacts f) {
        return f.has(Items.CRAFTING_TABLE) || f.stations().table().heldForPlanner();
    }

    // the first trip to the trees: just the table and the axe, so the axe is in hand for the real batch
    // (package only so the wood golden can see it)
    static int firstBatchLogs(GamerFacts f, OverworldConfig cfg) {
        return WoodBudget.firstBatchLogs(f, cfg);
    }

    private static List<KitNeed> iron(GamerFacts f, OverworldConfig cfg, int endBeds) {
        List<KitNeed> crafts = new ArrayList<>();
        int ingots = 0;
        for (KitItem k : ironItems(f, cfg)) {
            int missing = missing(f, k.item, k.count);
            if (missing == 0) {
                continue;
            }
            ingots += missing * INGOTS.getOrDefault(k.item, 0);
            // the first shield (or sword) is the early one, the spares only show up once it exists (no ingots go to them ahead of the pick)
            // shields go one per need, spares too: a batch of three wants 18 planks in the grid, and a bag of oak and dark oak that
            // won't stack is a cursor juggle that never ends
            int craft = k.item.equals("shield") || (k.item.equals("iron_sword") && have(f, k.item) == 0) ? 1 : missing;
            crafts.add(new KitNeed(k.item, held(f, k.item) + craft));
        }
        // the diamonds go first: they need no furnace and no ore, the bag already has them, and each one is an iron item that
        // ironItems no longer owes (so nothing gets smelted for a pick or a chestplate we are about to outclass)
        // a tool with no sticks or wood to make them from would fetch its own wood in the middle of the mine, same trip the late
        // list keeps a worn pick's replacement out of, so it waits behind the iron crafts instead. armor has no sticks
        List<KitNeed> out = new ArrayList<>();
        List<KitNeed> stickless = new ArrayList<>();
        boolean sticks = DiamondSpend.sticksInHand(f);
        for (String name : DiamondSpend.choose(f, cfg)) {
            KitNeed need = new KitNeed(name, held(f, name) + 1);
            (isTool(name) && !sticks ? stickless : out).add(need);
        }
        // the ingots for the early items are in the bag: make them before mining a single ore more (EarlyIronPick)
        List<String> early = EarlyIronPick.front(f, cfg);
        if (!early.isEmpty()) {
            for (String name : early) {
                moveToFront(crafts, out, name);
            }
        } else if (EarlyIronPick.due(f, cfg, ingots)) {
            // enough raw iron for the next early stage (chestplate and shield, then pick and shears, whichever are owed): smelt
            // just those, the big batch is what comes after (and is in the list as well)
            out.add(EarlyIronPick.need(f, cfg));
        }
        // ingots cooking in a furnace we loaded count as held, or the bot would go mining a second batch while the first one cooks
        if (ingots > 0 && f.count(Items.IRON_INGOT) + f.pendingOutput(Items.IRON_INGOT) < ingots) {
            out.add(new KitNeed("iron_ingot", ingots));
        }
        out.addAll(crafts);
        out.addAll(stickless);
        // the plan keeps armor on back here so the plan head stays the real work for everyone reading it (the climb, the benches,
        // the furnace rules). the runner is what puts it on first (HeadLatch.armorFirst)
        int worn = toEquip(f, cfg).size();
        if (worn > 0) {
            out.add(new KitNeed(KitNeed.EQUIP_ARMOR, worn));
        }
        addGold(out, f, cfg);
        addDiamondPick(out, f, cfg);
        addWool(out, f, endBeds);
        return out;
    }

    // raw gold for the piglin helmet, only while an ore is already known (GoldHunt). behind the essentials like the diamond dig: the
    // ore needs the iron pick, and a dig before the buckets are made would go before everything the portal needs
    private static void addGold(List<KitNeed> out, GamerFacts f, OverworldConfig cfg) {
        KitNeed need = GoldHunt.need(f);
        if (need != null && essentialsMet(f, cfg)) {
            out.add(need);
        }
    }

    // the name of the dig for the diamond pick (DiamondHunt clocks it while it is the head)
    public static final String DIAMOND = "diamond";

    // the iron pick breaks halfway through the nether, so the plan digs for a diamond one. behind everything the portal needs (a timed
    // out IRON still skips on the essentials) and in front of the wool, so the dig ends with a walk up to the sheep and not with the
    // portal starting at the bottom of the world. (while iron cooks the wool is filler and may well be done first, SmeltFiller holds
    // the dig back until the crafts are out of the furnace, and then the portal does start down there.) diamondSpend on: three diamonds and DiamondSpend crafts the pick (its need is first in
    // iron() the moment we hold them). off: the three, then the pick itself
    private static void addDiamondPick(List<KitNeed> out, GamerFacts f, OverworldConfig cfg) {
        if (!wantsDiamondPick(f, cfg)) {
            return;
        }
        if (f.count(Items.DIAMOND) < DiamondSpend.MIN) {
            out.add(new KitNeed(DIAMOND, DiamondSpend.MIN));
        }
        if (!cfg.diamondSpend) {
            out.add(new KitNeed("diamond_pickaxe", held(f, "diamond_pickaxe") + 1));
        }
    }

    // switched on, not given up on this run, no diamond (or netherite) pick in the bag, and the essentials in hand. that last one
    // is what really puts it behind them: while iron cooks SmeltFiller runs whatever needs no ingots, and a dig there would
    // otherwise go before the buckets are made (and with no iron pick, before there is anything that can mine a diamond)
    public static boolean wantsDiamondPick(GamerFacts f, OverworldConfig cfg) {
        return cfg.diamondPick && !f.diamondHuntGaveUp() && have(f, "diamond_pickaxe") == 0 && essentialsMet(f, cfg);
    }

    // the craft keeps its count, it only moves up. the shared iron_ingot need was sized with it, so nothing is counted twice
    private static void moveToFront(List<KitNeed> crafts, List<KitNeed> out, String item) {
        crafts.stream().filter(n -> n.catalogueName().equals(item)).findFirst().ifPresent(n -> {
            out.add(n);
            crafts.remove(n);
        });
    }

    // the iron kit as the planner reads it: the shield entry grows by netherSpareShields and the sword entry by netherSpareSwords,
    // the spares ride in the bag. everything that sums the kit (ingots, planks, the craft list) goes through this, so none of
    // them can disagree about the count
    static List<KitItem> ironKit(GamerFacts f, OverworldConfig cfg) {
        List<KitItem> out = new ArrayList<>();
        for (KitItem k : cfg.ironKit) {
            if ("shield".equals(k.item) && cfg.netherSpareShields > 0) {
                out.add(new KitItem(k.item, k.count + cfg.netherSpareShields));
            } else if ("iron_sword".equals(k.item) && cfg.netherSpareSwords > 0 && !betterSword(f)) {
                out.add(new KitItem(k.item, k.count + cfg.netherSpareSwords));
            } else {
                out.add(k);
            }
        }
        return out;
    }

    // a diamond or netherite sword in the bag counts as the iron one (have), and the spare is not owed on top of it: the diamond
    // craft takes the whole entry while it runs, and the entry must not come back one bigger the moment it is done
    private static boolean betterSword(GamerFacts f) {
        return have(f, "iron_sword") > f.count(exact("iron_sword"));
    }

    private static List<KitItem> ironItems(GamerFacts f, OverworldConfig cfg) {
        // whatever DiamondSpend picked is a diamond item from this tick on and stops being an iron one. nothing already smelted or
        // crafted goes anywhere, the diamond piece just wins the slot
        List<String> diamond = DiamondSpend.choose(f, cfg);
        List<KitItem> all = new ArrayList<>();
        for (KitItem k : ironKit(f, cfg)) {
            if (!DiamondSpend.replaces(diamond, k.item)) {
                all.add(k);
            }
        }
        // one piece may be gold instead (PiglinGold), which is exactly the iron it no longer costs
        for (String armor : PiglinGold.swap(f, armorNames(cfg))) {
            if (!DiamondSpend.replaces(diamond, armor)) {
                all.add(new KitItem(armor, 1));
            }
        }
        return all;
    }

    private static void addItem(List<KitNeed> out, GamerFacts f, String name, int count) {
        int missing = missing(f, name, count);
        if (missing > 0) {
            out.add(new KitNeed(name, held(f, name) + missing));
        }
    }

    // how many of this we still have to make to hold `count` of it. a better pickaxe than the one asked for covers the
    // whole quantity: two stone picks are a spare for the gather, and once there is an iron one in the bag a stone pick
    // is a waste of cobble and a trip to a table
    static int missing(GamerFacts f, String name, int count) {
        if (outclassed(f, name)) {
            return 0;
        }
        return Math.max(0, count - have(f, name));
    }

    private static boolean outclassed(GamerFacts f, String name) {
        if (!name.endsWith("_pickaxe")) {
            return false;
        }
        return f.count(counted(name)) > f.count(exact(name));
    }

    // a wooden, stone or iron pickaxe this far gone (ToolWear's line) is as good as gone: we would rather craft the next one while
    // we still have a table and the materials than have it break in a cave. a pick past it does not count as owned, MinecraftFacts
    // keeps those out of count()
    public static boolean wornOut(Item item, int damage, int maxDamage) {
        boolean ours = item == Items.WOODEN_PICKAXE || item == Items.STONE_PICKAXE || item == Items.IRON_PICKAXE;
        return ours && ToolWear.wornOut(damage, maxDamage);
    }

    // the raw meat in the bag gets cooked as the last thing, see CookGate for when it jumps the queue
    private static void addCook(List<KitNeed> out, GamerFacts f, OverworldConfig cfg, int endBeds, WoodBudget wood) {
        KitNeed cook = CookGate.need(f, cfg, endBeds, wood);
        if (cook != null) {
            out.add(cook);
        }
    }

    // the food need at this line, if we hold less (FoodPlan has the one count every food rule goes by)
    private static void addFood(List<KitNeed> out, FoodPlan food, int units) {
        if (food.shortOf(units)) {
            out.add(new KitNeed(KitNeed.FOOD, units));
        }
    }

    // the food need only goes in when a trip is due or under way (FoodPlan.wantsRefill), 69 is not a reason to drop the pick.
    // it asks for the trip's stop, so the hunt keeps going past the minimum instead of coming back for more in 20 minutes
    private static void addRefill(List<KitNeed> out, FoodPlan food) {
        if (food.wantsRefill()) {
            out.add(new KitNeed(KitNeed.FOOD, food.refillStop()));
        }
    }

    // 3 wool a bed, and a bed we already carry is 3 wool we do not need to find. a bed wants three of ONE colour, so
    // 5 red and 4 white is one bed's worth, not nine wool
    private static void addWool(List<KitNeed> out, GamerFacts f, int endBeds) {
        if (f.woolHuntGaveUp()) {
            return;
        }
        int missing = woolShortfall(f, bedGoal(f, endBeds));
        if (missing > 0) {
            // the wool task counts every colour, so ask for what we hold plus the shortfall or it stops too early
            out.add(new KitNeed("wool", f.count(ItemHelper.WOOL) + missing));
        }
    }

    // the beds the materials are for: the fight's, and one more while the spawn bed still has to go down. same number as
    // EndGear.bedTarget, the planner just gets it from the facts
    public static int bedGoal(GamerFacts f, int endBeds) {
        return endBeds + (f.spawnBedOwed() ? 1 : 0);
    }

    // wool still missing for this many beds, a held bed counting as its three wool. one formula for the wool need and for
    // VillageBeds, so a village bed shrinks exactly the need it is standing in for
    public static int woolShortfall(GamerFacts f, int beds) {
        return Math.max(0, 3 * beds - (usableWool(f) + 3 * f.count(ItemHelper.BED)));
    }

    // the same shortfall in whole beds, which is what a village hands out (rounded up, a bed is not two thirds)
    public static int bedsShort(GamerFacts f, int beds) {
        return (woolShortfall(f, beds) + 2) / 3;
    }

    // wool that turns into beds: whole sets of three per colour
    public static int usableWool(GamerFacts f) {
        int usable = 0;
        for (Item wool : ItemHelper.WOOL) {
            usable += f.count(wool) / 3 * 3;
        }
        return usable;
    }

    // what is left of GATHER once the food is not counted: both stone tools. enough to carry on and let IRON do the food.
    // the weapon is whatever swings: the kit makes a sword, but an axe out of a chest swings too (a better tier
    // of either counts, have() sees to that)
    public static boolean stoneToolsMet(GamerFacts f) {
        return have(f, "stone_pickaxe") >= 1 && (have(f, "stone_axe") >= 1 || have(f, "stone_sword") >= 1);
    }

    // armor pieces we carry that are a better tier than what is on that slot (nothing on it counts as the worst), best tier we hold
    // per slot, for EquipArmorTask. options run worst to best, so a diamond piece in the bag goes over worn iron and the iron it
    // swapped out (still in the bag, as a lower tier) is never a reason to swap back
    public static List<Item> toEquip(GamerFacts f, OverworldConfig cfg) {
        List<Item> out = new ArrayList<>();
        for (String name : PiglinGold.swap(f, armorNames(cfg))) {
            Item[] options = counted(name);
            int worn = -1;
            int best = -1;
            for (int i = 0; i < options.length; i++) {
                if (f.armorEquipped(options[i])) {
                    worn = i;
                }
                if (f.count(options[i]) > 0) {
                    best = i;
                }
            }
            if (best > worn) {
                out.add(options[best]);
            }
        }
        // a shield in the bag goes to the offhand with the armor: nothing else puts it there until an arrow or a creeper is
        // already on us, and by then the swap is a click war with the fight (armorEquipped reads the offhand too)
        if (f.count(Items.SHIELD) > 0 && !f.armorEquipped(Items.SHIELD)) {
            out.add(Items.SHIELD);
        }
        PiglinGold.addToEquip(f, out);
        return out;
    }

    public static List<String> armorNames(OverworldConfig cfg) {
        ArmorPlan plan = cfg.armorPlan == null ? ArmorPlan.NONE : cfg.armorPlan;
        return switch (plan) {
            case FULL_IRON -> FULL_IRON;
            case CHEST_HELMET -> CHEST_HELMET;
            case NONE -> List.of();
        };
    }

    // ingots one of this item costs, 0 for everything that is not made of iron
    public static int ingotCost(String name) {
        return INGOTS.getOrDefault(name, 0);
    }

    // the iron kit items (armor included) still missing, the ones a DiamondSpend pick stands in for left out. the same list
    // ingotsNeeded sums, so the early batch never smelts for something the big need does not count
    static Set<String> ironItemsOwed(GamerFacts f, OverworldConfig cfg) {
        Set<String> out = new HashSet<>();
        for (KitItem k : ironItems(f, cfg)) {
            if (missing(f, k.item, k.count) > 0) {
                out.add(k.item);
            }
        }
        return out;
    }

    // ingots the missing iron items still need in total (what the combined iron need is sized by)
    public static int ingotsNeeded(GamerFacts f, OverworldConfig cfg) {
        int ingots = 0;
        for (KitItem k : ironItems(f, cfg)) {
            ingots += missing(f, k.item, k.count) * INGOTS.getOrDefault(k.item, 0);
        }
        return ingots;
    }

    // enough to walk into the portal with: pickaxe, light, and the two buckets. what a timed out iron phase may be
    // skipped with (wool and armor are nice, these are not)
    public static boolean essentialsMet(GamerFacts f, OverworldConfig cfg) {
        return have(f, "iron_pickaxe") >= 1
                && f.count(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE) >= 1
                && have(f, "bucket") >= 2;
    }

    // what we own of this name, better tiers and filled buckets included
    public static int have(GamerFacts f, String name) {
        return f.count(counted(name));
    }

    // what we own of exactly the thing the catalogue would collect for this name. worn out picks are in here even though
    // they are not in count(): the catalogue sees them in the bag, so its target has to start above them
    public static int held(GamerFacts f, String name) {
        Item[] exact = exact(name);
        int total = f.count(exact);
        for (Item item : exact) {
            total += f.spent(item);
        }
        return total;
    }

    // for the "did that need get closer" check. food goes by the plain count, not held: held jumps by the raw meat's cooked value
    // whenever a smoker is placed or picked up, and that is no progress
    public static int progressOf(GamerFacts f, KitNeed need, FoodPlan food) {
        return switch (need.catalogueName()) {
            case KitNeed.FOOD -> food.counted();
            case KitNeed.BUILD_BLOCKS -> f.buildBlocks();
            case KitNeed.EQUIP_ARMOR -> -need.count();
            // less raw meat in the bag is closer (meat going into a smoker leaves it)
            case KitNeed.COOK_SMOKER, KitNeed.COOK_FURNACE -> -CookGate.raw(f);
            default -> have(f, need.catalogueName());
        };
    }

    // plan() runs a few times a tick and these are registry lookups, so the answers are kept (callers never write to them)
    private static final Map<String, Item[]> EXACT = new ConcurrentHashMap<>();
    private static final Map<String, Item[]> COUNTED = new ConcurrentHashMap<>();

    static Item[] exact(String name) {
        return EXACT.computeIfAbsent(name, n -> switch (n) {
            case "wool" -> ItemHelper.WOOL;
            case "bed" -> ItemHelper.BED;
            case "log" -> ItemHelper.LOG;
            case "planks" -> ItemHelper.PLANKS;
            default -> lookup(n);
        });
    }

    static Item[] counted(String name) {
        return COUNTED.computeIfAbsent(name, n -> {
            List<Item> out = new ArrayList<>(Arrays.asList(exact(n)));
            switch (n) {
                case "bucket" -> out.addAll(List.of(Items.WATER_BUCKET, Items.LAVA_BUCKET));
                case "furnace" -> out.add(Items.BLAST_FURNACE);
                default -> addBetterTiers(out, n);
            }
            return out.toArray(new Item[0]);
        });
    }

    // stone_pickaxe is also iron/diamond/netherite, iron_helmet is also diamond/netherite (stone armor does not exist)
    private static void addBetterTiers(List<Item> out, String name) {
        int us = name.indexOf('_');
        if (us < 0) {
            return;
        }
        int tier = TIERS.indexOf(name.substring(0, us));
        String kind = name.substring(us + 1);
        boolean valid = TOOLS.contains(kind) || (ARMOR.contains(kind) && tier >= 1);
        if (tier < 0 || !valid) {
            return;
        }
        for (int i = tier + 1; i < TIERS.size(); i++) {
            out.addAll(Arrays.asList(lookup(TIERS.get(i) + "_" + kind)));
        }
    }

    private static Item[] lookup(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(id))
                .map(item -> new Item[]{item}).orElse(new Item[0]);
    }
}
