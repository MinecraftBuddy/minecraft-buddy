package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.KitItem;
import adris.altoclef.util.helpers.FuelPolicy;
import adris.altoclef.util.helpers.ItemHelper;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// the ONE answer to "how much wood does the bot still need, how much is spare, how much may a furnace burn". pure facts in, numbers
// out, built once a tick (GamerContext.wood) and read by the kit planner, the cook gate, the filler, the coal detour and the
// furnace reserve. before this every one of them rebuilt its own view of the same bag and they drifted apart.
// no food in here on purpose: the food plan itself asks the cook gate about fuel, so the budget has to exist before it. the two
// answers that do depend on the food plan take it as an argument (cookFuelLogs, gatherChopLogs)
public record WoodBudget(
        // the phase it was built for
        GamerPhase phase,
        // what the bag holds, every species of a group added up
        int logs, int planks, int sticks,
        // logs still to fetch for everything wooden the kit crafts, a log of slack included: the gather's form (axe, beds and all)
        int gatherLogsOwed,
        // the same once the plan is past the gather: no lost axe, no beds, one pick, no slack. a spare log there is a trip
        int ironLogsOwed,
        // planks the end fight's beds still cost, 3 a bed we do not hold. already inside gatherLogsOwed, the filler stocks it too
        int bedPlanksOwed,
        // the first small trip of the gather, just a table and the axe, so the axe is in hand for the real batch
        int firstBatchLogs,
        // wood spoken for, the rest is spare. the GATHER's view of what is owed (gatherLogsOwed) in every phase but IRON; there it is
        // that view less what the cook takes off the beds' share (see the claims below)
        WoodReserve.Keep keep,
        // what the furnaces are told to leave alone (FuelPolicy): keep in the overworld, all of it everywhere else
        WoodReserve.Keep fuelKeep,
        // logs a new smoker costs: its four, and one more for a table when there is none to craft it on
        int smokerLogs,
        // no smoker held, standing or cooking: one still has to be made, so the logs it eats are not fuel
        boolean smokerOwed,
        // logs and planks a furnace is allowed to touch at all (altoSupportedFuels), before the reserve comes off
        int burnableLogs, int burnablePlanks,
        // the most logs cookFuelLogs asks for: cfg.cookFuelLogs while no raw meat is in the bag, else 0. the rest is the food plan's say
        int cookFuelCap,
        // ---- the claims on the logs we hold, IRON only (0 in the other phases, which keep the gather's view). in priority order: the
        // kit, then the cook, then the end beds with whatever is left. when logs are scarce the cook eats logs that were banked for
        // beds and the END phase chops again, on purpose: fed now beats beds later
        // the wood the kit's remaining crafts hold, the iron form: no lost axe, no beds, no slack. the logs it holds are never the cook's
        WoodReserve.Keep kitClaim,
        // logs a smoker still to be made will eat, while raw meat is waiting and nothing can cook it yet (0 once a smoker exists)
        int smokerClaim,
        // logs of fuel the waiting raw meat needs, only the part coal and the planks above the reserve do not cover
        int fuelClaim,
        // what the reserve keeps for the end beds (and the gather's slack) on top of the kit's claim, once the cook has had its share
        WoodReserve.Keep bedClaim,
        // logs the iron phase chops for the cook: its claim less the logs held beyond the kit. 0 down in a mine, no climb out for it
        int cookChopLogs,
        // logs the log stock-up wants held to craft with (smeltExtras "log", planks count as a quarter log each), held back from the
        // furnaces once the cook has no claim. the same wood as the beds' share, not a second pile
        int stockClaim,
        // every species of log and plank in the bag, and whether a furnace may burn it: what burnableWoodSmelts hands the reserve to,
        // the way FuelPolicy hands it out (by name over every species, burnable or not)
        List<Species> species) {

    // a held stack of one species of wood
    public record Species(Item item, int count, boolean burnable) {
    }

    // planks and sticks per kind of tool: only the wooden tier pays the planks, every tier pays the sticks
    private static final Map<String, int[]> TOOL_WOOD = Map.of(
            "pickaxe", new int[]{3, 2}, "axe", new int[]{3, 2}, "sword", new int[]{2, 1},
            "shovel", new int[]{1, 2}, "hoe", new int[]{2, 2});
    private static final int PLANKS_PER_LOG = 4;
    // 4 sticks come out of 2 planks, so sticks are bought in fours
    private static final int STICKS_PER_CRAFT = 4;
    // a smoker is a furnace (8 cobble) and 4 logs
    private static final int SMOKER_LOGS = 4;

    // no phase = the gather's view, which is what every test and every phase but IRON has always had
    public static WoodBudget of(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return of(f, cfg, endBeds, GamerPhase.GATHER);
    }

    public static WoodBudget of(GamerFacts f, OverworldConfig cfg, int endBeds, GamerPhase phase) {
        int logs = f.count(ItemHelper.LOG);
        int planks = f.count(ItemHelper.PLANKS);
        int gather = owed(f, cfg, endBeds, true);
        WoodReserve.Keep gatherKeep = keepOf(f, cfg, endBeds, logs, planks, gather);
        int iron = owed(f, cfg, endBeds, false);
        int burnableLogs = burnable(f, ItemHelper.LOG);
        int burnablePlanks = burnable(f, ItemHelper.PLANKS);
        List<Species> species = speciesOf(f);
        Claims claims = phase == GamerPhase.IRON
                ? claimsOf(f, cfg, endBeds, logs, planks, burnableLogs, burnablePlanks, gatherKeep, iron, species)
                : new Claims(gatherKeep, NOTHING, 0, 0, NOTHING, 0, 0);
        WoodReserve.Keep keep = claims.keep();
        // the run lets wood burn (GamerTask.withWoodFuel) for the overworld cook. nothing down in the nether budgets it, so a clear
        // reserve would let one smelt eat every log in the bag: keep all of it, coal still burns
        WoodReserve.Keep fuel = f.dimension() == Dimension.OVERWORLD ? keep : new WoodReserve.Keep(logs, planks);
        return new WoodBudget(phase, logs, planks, f.count(Items.STICK), gather, iron, bedPlanksOwed(f, endBeds), firstBatchLogs(f, cfg),
                keep, fuel, smokerLogsFor(f), smokerOwedFor(f), burnableLogs, burnablePlanks,
                cfg.cookFuelLogs <= 0 || CookGate.raw(f) > 0 ? 0 : cfg.cookFuelLogs,
                claims.kit(), claims.smoker(), claims.fuel(), claims.bed(), claims.chop(), claims.stock(), species);
    }

    private static List<Species> speciesOf(GamerFacts f) {
        List<Species> out = new ArrayList<>();
        for (Item[] group : new Item[][]{ItemHelper.LOG, ItemHelper.PLANKS}) {
            for (Item item : group) {
                int n = f.count(item);
                if (n > 0) {
                    out.add(new Species(item, n, f.burnable(item)));
                }
            }
        }
        return out;
    }

    private static final WoodReserve.Keep NOTHING = new WoodReserve.Keep(0, 0);

    private record Claims(WoodReserve.Keep keep, WoodReserve.Keep kit, int smoker, int fuel, WoodReserve.Keep bed, int chop, int stock) {
    }

    // the logs smeltExtras says to hold ("log"), 0 when the config has no such entry
    private static int stockTarget(OverworldConfig cfg) {
        int total = 0;
        if (cfg.smeltExtras != null) {
            for (KitItem extra : cfg.smeltExtras) {
                if ("log".equals(extra.item) && extra.count > 0) {
                    total += extra.count;
                }
            }
        }
        return total;
    }

    // IRON's claims on what we hold. the kit comes first and holds what its crafts need (iron form). the cook comes second: with raw
    // meat waiting and no way to cook it yet it holds the smoker's logs (if one still has to be made) and the fuel logs the meat
    // needs. the beds get what is left of their usual share. the reserve is the gather's keep less whatever the cook takes: it
    // eats the spare logs first (nothing speaks for them), then the beds' share down to the kit's claim. no claim, no change: IRON
    // goes on running on the gather's keep, slack and all
    private static Claims claimsOf(GamerFacts f, OverworldConfig cfg, int endBeds, int logs, int planks, int burnableLogs,
                                   int burnablePlanks, WoodReserve.Keep gatherKeep, int ironOwedNow, List<Species> species) {
        WoodReserve.Keep kit = kitKeepOf(f, cfg, endBeds, logs, planks, ironOwedNow);
        // the planks the kit holds are never fuel, even where its search split logs and planks differently from the gather's
        int keptPlanks = Math.max(gatherKeep.planks(), kit.planks());
        int smoker = 0;
        int fuel = 0;
        if (CookGate.waiting(f)) {
            CookGate.Station station = CookGate.stationIfLogs(f, cfg);
            if (station != CookGate.Station.NONE) {
                smoker = station == CookGate.Station.SMOKER && smokerOwedFor(f) ? smokerLogsFor(f) : 0;
                fuel = fuelLogsFor(f, Math.max(0, burnablePlanks - keptPlanks));
            }
        }
        int claim = smoker + fuel;
        // a cook that is under way has its meat in the slot and the bag reads no raw, so nothing sizes its fuel any more and the
        // claim would drop the tick it starts (and the smoker be loaded with logs the furnace is then refused): until it is over it
        // may burn whatever the kit does not hold. no chop for it, the claim above is what sizes that
        boolean committed = f.cookStation() != null;
        int reserveClaim = committed ? logs : claim;
        int fromBeds = Math.min(Math.max(0, reserveClaim - (logs - gatherKeep.logs())), Math.max(0, gatherKeep.logs() - kit.logs()));
        WoodReserve.Keep keep = new WoodReserve.Keep(gatherKeep.logs() - fromBeds, reserveClaim > 0 ? keptPlanks : gatherKeep.planks());
        WoodReserve.Keep bed = new WoodReserve.Keep(Math.max(0, keep.logs() - kit.logs()), Math.max(0, keep.planks() - kit.planks()));
        // short of the claim by the oak logs it takes before the furnace would be handed the claim's worth: the reserve is dealt out
        // by name (FuelPolicy.usable), a species the furnace won't burn can soak it up or leave it to the oak, so ask the same rule
        int chop = 0;
        // (while the gather still owes wood the reserve grows with every log chopped, so a species that sorts after the oak and
        // burns nothing can take a few trips to settle. stems in an overworld bag are not a thing, so that is left alone. and no
        // chopping at all if oak itself is not fuel, it would never open the gate)
        if (f.nearSurface() && claim > 0 && f.burnable(Items.OAK_LOG)) {
            while (chop <= claim + keep.logs() && usableLogs(species, keep.logs(), chop) < claim) {
                chop++;
            }
        }
        // the log stock-up (SmeltFiller) chops towards a pile to craft with, planks counting as wood. the reserve keeps what it
        // chopped, or the furnace burns the pile and the next wait chops it again. the cook outranks it, so while the cook has a
        // claim the stock is not protected, and so does the fuel the smelts still ahead need: the stock only holds back logs that
        // coal and spare planks leave unneeded, or an iron batch with no coal would find its fuel locked up in a pile of
        // nice-to-have. it overlaps the beds' share, it is the same wood and not a second pile
        int smeltsAhead = Math.max(0, KitPlanner.ingotsNeeded(f, cfg) - f.count(Items.IRON_INGOT)) + CookGate.raw(f);
        int fuelInHand = CookGate.coalSmelts(f) + Math.max(0, burnablePlanks - keep.planks()) * 3 / 2;
        int logsForFuel = smeltsAhead > fuelInHand ? (2 * (smeltsAhead - fuelInHand) + 2) / 3 : 0;
        int stock = claim > 0 || committed ? 0 : Math.min(Math.max(0, Math.min(logs, burnableLogs) - logsForFuel),
                Math.max(0, stockTarget(cfg) - planks / PLANKS_PER_LOG));
        if (stock > keep.logs()) {
            keep = new WoodReserve.Keep(stock, keep.planks());
        }
        return new Claims(keep, kit, smoker, fuel, bed, chop, stock);
    }

    // logs a furnace would be handed with `reserve` logs kept back and `extraOak` more oak chopped (the same rule the furnace gets)
    private static int usableLogs(List<Species> species, int reserve, int extraOak) {
        List<Species> all = new ArrayList<>(species);
        if (extraOak > 0) {
            all.add(new Species(Items.OAK_LOG, extraOak, true));
        }
        Item[] items = new Item[all.size()];
        int[] counts = new int[items.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = all.get(i).item();
            counts[i] = all.get(i).count();
        }
        int[] usable = FuelPolicy.usable(items, counts, reserve, 0);
        int total = 0;
        for (int i = 0; i < items.length; i++) {
            if (all.get(i).burnable() && isLog(items[i])) {
                total += usable[i];
            }
        }
        return total;
    }

    private static boolean isLog(Item item) {
        for (Item log : ItemHelper.LOG) {
            if (log == item) {
                return true;
            }
        }
        return false;
    }

    // logs of fuel for the biggest pile of raw meat: the smelts left after coal and `sparePlanks` (a log and a plank are both 1.5 smelts)
    private static int fuelLogsFor(GamerFacts f, int sparePlanks) {
        int smelts = CookGate.pile(f) - CookGate.coalSmelts(f);
        if (smelts <= 0) {
            return 0;
        }
        return Math.max(0, (2 * smelts + 2) / 3 - sparePlanks);
    }

    // ---- what we hold beyond the reserve

    public int spareLogs() {
        return logs - keep.logs();
    }

    public int sparePlanks() {
        return planks - keep.planks();
    }

    // ---- what the planners chop

    // logs on top of the wood budget so the meat has something to burn when it comes in. the food has to be short, a trip due or on
    // (keyed on the same refill as the food need, logs for a hunt that is not coming are just more trees), and the spare logs we
    // already hold count, so the request settles at cfg.cookFuelLogs of surplus instead of chasing itself. past the food the answer
    // is 0 whatever the bag says, or burning them would send the gather back to the trees
    public int cookFuelLogs(FoodPlan food) {
        return cookFuelLogs(food, gatherLogsOwed);
    }

    // `logsShort` = how far the log need is from met. short of the budget every log is spoken for, so there is no spare to look for
    public int cookFuelLogs(FoodPlan food, int logsShort) {
        if (cookFuelCap <= 0 || !food.wantsRefill()) {
            return 0;
        }
        int spare = logsShort > 0 ? 0 : Math.max(0, spareLogs());
        return Math.max(0, cookFuelCap - spare);
    }

    // what the iron phase chops: the whole owed amount before the first ore is in the bag, nothing after. asked of the live facts,
    // not frozen into the budget: the early load latch (EarlyIronPick.track) can start in the middle of a tick, after the budget.
    // the cook's logs go on top whenever we are near the surface, ore in the bag or not (that is the one term the first ore does not
    // end), and the kit's own short rides along then: the logs are one pool, a chop for the cook alone would be eaten by the kit first
    public int ironChopLogs(GamerFacts f) {
        if (cookChopLogs > 0) {
            return ironLogsOwed + cookChopLogs;
        }
        return KitPlanner.ironStarted(f) ? 0 : ironLogsOwed;
    }

    // what the gather asks the trees for: the whole budget and the cook's logs on top
    public int gatherChopLogs(FoodPlan food) {
        return gatherLogsOwed + cookFuelLogs(food);
    }

    // the log stock-up counts planks as wood too (4 to a log), it is only there so we have wood to craft with. 0 = no trip.
    // a stock-up is not worth climbing out of the mine for, so down there it never happens (the iron phase asks for wood
    // itself while the surface is still close)
    public int stockUpLogs(int wantedLogs, boolean nearSurface) {
        return stockUp(logs, planks, wantedLogs, nearSurface);
    }

    // (the same question for a caller that only has the counts)
    static int stockUp(int logs, int planks, int wantedLogs, boolean nearSurface) {
        if (!nearSurface) {
            return 0;
        }
        int missing = 4 * wantedLogs - planks - 4 * logs;
        if (missing <= 0) {
            return 0;
        }
        // held logs already count, the need is a total like every other log need
        return wantedLogs - planks / 4;
    }

    // ---- what the furnace may burn

    // logs the smoker we still have to make will eat, 0 when there is no new smoker coming (CookGate's station decides that)
    public int logsSpokenFor(boolean smokerStation) {
        return smokerStation && smokerOwed ? smokerLogs : 0;
    }

    // smelts of fuel in the wood above the reserve: logs and planks the furnace may burn, `spokenLogs` taken off the spare logs first
    // (logs about to go into something else). a log and a plank are both 300 ticks of fire, 1.5 items (ItemHelper.getFuelAmount)
    // the reserve is handed out the way FuelPolicy hands it to the furnace (FuelPolicy.usable, by name over every species whether it
    // burns or not), so a stack the furnace won't touch can soak up the reserve and leave the oak burnable, and the count is the
    // furnace's and not a subtraction of its own
    public int burnableWoodSmelts(int spokenLogs) {
        Item[] items = new Item[species.size()];
        int[] counts = new int[items.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = species.get(i).item();
            counts[i] = species.get(i).count();
        }
        // fuelKeep, not keep: it is the reserve the furnace is actually told (every log outside the overworld), so the count is
        // never wood the furnace will refuse
        int[] usable = FuelPolicy.usable(items, counts, fuelKeep.logs() + spokenLogs, fuelKeep.planks());
        int burnable = 0;
        for (int i = 0; i < items.length; i++) {
            if (species.get(i).burnable()) {
                burnable += usable[i];
            }
        }
        return burnable * 3 / 2;
    }

    // ---- the arithmetic, everything above is built from it

    // logs still to fetch for everything wooden the overworld will craft, one log of slack included, 0 when we hold enough. summed
    // over every kit item still missing (starter and iron kits, ladders, the axe, a table or two, beds) with the planks, sticks and
    // logs we hold taken off. gathering is false once the plan is past the gather: a lost axe is not worth a log, and neither are the beds
    static int owed(GamerFacts f, OverworldConfig cfg, int endBeds, boolean gathering) {
        int[] wood = new int[2];
        List<KitItem> all = new ArrayList<>(cfg.starterKit);
        all.addAll(KitPlanner.ironKit(f, cfg));
        // a diamond pick or sword has the same sticks as the iron one it stands in for, so the kit item steps aside and the
        // diamond one is paid below (a pick we upgrade on top of an iron one we hold is the only case that adds sticks)
        List<String> diamond = DiamondSpend.choose(f, cfg);
        for (KitItem k : all) {
            if (!gathering && KitPlanner.isAxe(k.item)) {
                continue;
            }
            if (DiamondSpend.replaces(diamond, k.item)) {
                continue;
            }
            int count = !gathering && k.item.endsWith("_pickaxe") ? Math.min(k.count, 1) : k.count;
            int m = KitPlanner.missing(f, k.item, count);
            addWood(wood, k.item, m);
            // a stone pick is made with a wooden one, unless something in the bag already mines stone
            boolean needsWooden = k.item.equals("stone_pickaxe") && m > 0 && KitPlanner.have(f, "stone_pickaxe") == 0;
            if (needsWooden && f.count(Items.WOODEN_PICKAXE) == 0) {
                addWood(wood, "wooden_pickaxe", 1);
            }
        }
        for (String name : diamond) {
            addWood(wood, name, 1);
        }
        if (wood[0] + wood[1] > 0) {
            wood[0] += KitPlanner.tablePlanks(f);
        }
        // a bed is wool and 3 planks, a held bed is both already. a village handing us beds only shrinks the leftovers.
        // only the gather asks: the iron phase is done with wool in hand, the planks for the beds are not its business
        if (gathering) {
            wood[0] += bedPlanksOwed(f, endBeds);
        }
        // the margin is the gather's up-front chop. in the iron phase exactly enough planks is enough, a spare log is a trip
        return logsShort(f, wood[0], wood[1], gathering);
    }

    static int bedPlanksOwed(GamerFacts f, int endBeds) {
        // IRON's sheep clock running out changes nothing here, END_PREP still gets every bed and most of them want planks
        return 3 * Math.max(0, KitPlanner.bedGoal(f, endBeds) - f.count(ItemHelper.BED));
    }

    // the first trip to the trees: just the table and the axe, so the axe is in hand for the real batch
    static int firstBatchLogs(GamerFacts f, OverworldConfig cfg) {
        int[] wood = new int[2];
        for (KitItem k : cfg.starterKit) {
            if (KitPlanner.isAxe(k.item)) {
                addWood(wood, k.item, KitPlanner.missing(f, k.item, k.count));
            }
        }
        if (wood[0] + wood[1] == 0) {
            return 0;
        }
        if (!KitPlanner.tableHeld(f)) {
            wood[0] += KitPlanner.TABLE_PLANKS;
        }
        return logsShort(f, wood[0], wood[1], false);
    }

    private static void addWood(int[] wood, String item, int count) {
        if (count <= 0) {
            return;
        }
        switch (item) {
            case "shield" -> wood[0] += 6 * count;
            case "bed" -> wood[0] += 3 * count;
            // 7 sticks make 3 ladders
            case "ladder" -> wood[1] += 7 * ((count + 2) / 3);
            default -> {
                int us = item.indexOf('_');
                int[] tool = us < 0 ? null : TOOL_WOOD.get(item.substring(us + 1));
                if (tool != null) {
                    wood[0] += item.startsWith("wooden_") ? tool[0] * count : 0;
                    wood[1] += tool[1] * count;
                }
            }
        }
    }

    // planks and sticks to logs: sticks are bought in fours (two planks), logs in fours (planks), what we hold comes off
    private static int logsShort(GamerFacts f, int planks, int sticks, boolean margin) {
        int stickShort = Math.max(0, sticks - f.count(Items.STICK));
        int total = planks + 2 * ((stickShort + STICKS_PER_CRAFT - 1) / STICKS_PER_CRAFT);
        if (total <= 0) {
            return 0;
        }
        // the slack is part of what we hold ourselves to, not a bonus when short, or the need would flicker at the edge
        if (margin) {
            total += PLANKS_PER_LOG;
        }
        int missingPlanks = total - f.count(ItemHelper.PLANKS) - PLANKS_PER_LOG * f.count(ItemHelper.LOG);
        return missingPlanks <= 0 ? 0 : (missingPlanks + PLANKS_PER_LOG - 1) / PLANKS_PER_LOG;
    }

    // wood spoken for. a log is surplus while the owed amount still says 0 with it gone, so the margin the budget builds in stays ours
    private static WoodReserve.Keep keepOf(GamerFacts f, OverworldConfig cfg, int endBeds, int logs, int planks, int owedNow) {
        if (owedNow > 0) {
            // still short of the budget: every piece of wood is spoken for
            return new WoodReserve.Keep(logs, planks);
        }
        // owed only falls as we hold more, so the biggest k with "still 0 after losing k" is a binary search
        int spareLogs = WoodReserve.most(logs, k -> owed(WoodReserve.without(f, ItemHelper.LOG, k), cfg, endBeds, true) == 0);
        GamerFacts afterLogs = WoodReserve.without(f, ItemHelper.LOG, spareLogs);
        int sparePlanks = WoodReserve.most(planks, k -> owed(WoodReserve.without(afterLogs, ItemHelper.PLANKS, k), cfg, endBeds, true) == 0);
        return new WoodReserve.Keep(logs - spareLogs, planks - sparePlanks);
    }

    // the same search in the iron form: the wood the kit's remaining crafts hold, no slack. short of it, all of it is the kit's
    private static WoodReserve.Keep kitKeepOf(GamerFacts f, OverworldConfig cfg, int endBeds, int logs, int planks, int ironOwedNow) {
        if (ironOwedNow > 0) {
            return new WoodReserve.Keep(logs, planks);
        }
        int spareLogs = WoodReserve.most(logs, k -> owed(WoodReserve.without(f, ItemHelper.LOG, k), cfg, endBeds, false) == 0);
        GamerFacts afterLogs = WoodReserve.without(f, ItemHelper.LOG, spareLogs);
        int sparePlanks = WoodReserve.most(planks, k -> owed(WoodReserve.without(afterLogs, ItemHelper.PLANKS, k), cfg, endBeds, false) == 0);
        return new WoodReserve.Keep(logs - spareLogs, planks - sparePlanks);
    }

    // logs a new smoker costs: its four, and one more for a table when there is none to craft it on (the table's planks used to
    // take the fourth log, and the catalogue went chopping a tree from the bottom of the mine)
    static int smokerLogsFor(GamerFacts f) {
        return SMOKER_LOGS + (f.has(Items.CRAFTING_TABLE) || f.stations().table().heldForPlanner() ? 0 : 1);
    }

    // a smoker we still have to make: nothing held, standing or cooking for us
    static boolean smokerOwedFor(GamerFacts f) {
        return !f.has(Items.SMOKER) && !f.stations().smoker().heldForPlanner() && f.cookStation() == null;
    }

    // how many of these we hold that a furnace may burn (f.burnable, altoSupportedFuels)
    private static int burnable(GamerFacts f, Item[] items) {
        int total = 0;
        for (Item item : items) {
            if (f.burnable(item)) {
                total += f.count(item);
            }
        }
        return total;
    }
}
