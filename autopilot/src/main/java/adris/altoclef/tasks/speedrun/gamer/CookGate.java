package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.FoodHelper;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// when does the bot cook the raw meat it is carrying. foodUnits() counts raw meat at its cooked value (so a bag of mutton does not
// send us hunting), which means the kit's food need is "met" by dinner nobody has cooked, and the food chain ate it raw. this is
// the separate "convert what we have" need: pure (facts in, a need or nothing out) so the rules can be tested without a game.
// where it sits in the list is the same trick as FoodGate: lead on the surface or between two jobs, never in front of the ore,
// and at the very end of the plan regardless, which is what keeps the nether free of raw meat while a furnace can cook it
public final class CookGate {
    // meat and fish only. a raw potato is a side dish (1 unit raw, 5 baked) and nobody builds a smoker for it
    public static final Item[] RAW_MEAT = {Items.PORKCHOP, Items.BEEF, Items.CHICKEN, Items.MUTTON, Items.RABBIT, Items.COD, Items.SALMON};
    // this much raw meat is worth a trip of its own. fewer than this only gets cooked as the last thing, and only where a
    // station is already standing (nobody crafts a smoker for one rabbit)
    public static final int MIN_RAW = 3;
    // a smoker is a furnace (8 cobble) and 4 logs (WoodBudget has the logs)
    private static final int SMOKER_COBBLE = 8;
    // smelts a coal burns, the unit the smelt tasks count fuel in
    private static final int COAL_SMELTS = 8;

    public enum Station {
        NONE, SMOKER, FURNACE
    }

    private CookGate() {
    }

    public static int raw(GamerFacts f) {
        return f.count(RAW_MEAT);
    }

    // the biggest pile of one kind. the cook loads one kind at a time (one input slot), so this is what the first load burns,
    // and fuel for the whole bag is not needed up front
    public static int pile(GamerFacts f) {
        int best = 0;
        for (Item meat : RAW_MEAT) {
            best = Math.max(best, f.count(meat));
        }
        return best;
    }

    // ingots the kit still wants smelted, beyond the ones in the bag or already cooking. same sum KitPlanner.iron uses for
    // the iron need. while there is any, the plain furnace is the iron's: the early pick and the big batch both walk back to
    // the furnace they remember, and one with the meat in it is a furnace they would mix it up in
    public static boolean ironOwed(GamerFacts f, OverworldConfig cfg) {
        return KitPlanner.ingotsNeeded(f, cfg) > f.count(Items.IRON_INGOT) + f.pendingOutput(Items.IRON_INGOT);
    }

    // food we left in a station without lighting it (AsyncSmelting.leftBehind). not cooking, so not a stand-by and not pending
    // food, but the input slot is taken until somebody goes back for it
    public static boolean stranded(GamerFacts f) {
        for (RunState.FurnaceJob job : f.furnaceJobs()) {
            if (job.stranded) {
                return true;
            }
        }
        return false;
    }

    // is a furnace of ours busy with iron right now. food jobs never get this far (the cook waits for them), so any job on a
    // furnace is ore, and the furnace has ONE input slot: loading meat on top would swap the ore back out
    static boolean furnaceHasIron(GamerFacts f) {
        for (RunState.FurnaceJob job : f.furnaceJobs()) {
            if ("furnace".equals(job.kind)) {
                return true;
            }
        }
        return false;
    }

    // where the meat goes, cheapest first: a smoker we have (twice as fast), then a furnace that is free (standing or in the bag),
    // then, if allowed to spend wood and stone, a new smoker or furnace. NONE = nothing sensible right now, so no cook need.
    // a furnace busy with iron queues the meat behind it: the smoker is the way round that, and only if one can be had
    // `mayCraft` = enough meat to be worth a trip. without it only a station standing on the ground counts: a furnace in the bag
    // is a place, cook, walk back, pick up for two rabbit, and the phase waits on all of it
    public static Station station(GamerFacts f, OverworldConfig cfg, boolean mayCraft) {
        // the beds only move what the kit owes, station reads the logs held and what a smoker costs
        return station(f, cfg, mayCraft, WoodBudget.of(f, cfg, 0));
    }

    public static Station station(GamerFacts f, OverworldConfig cfg, boolean mayCraft, WoodBudget wood) {
        return station(f, cfg, mayCraft, wood.logs() >= wood.smokerLogs());
    }

    // the station the meat would go to if the bag held the smoker's logs: what the wood budget's cook claim is sized for. asking with
    // the logs we hold would flip between a furnace and a smoker as the chop lands, and the claim with it
    static Station stationIfLogs(GamerFacts f, OverworldConfig cfg) {
        return station(f, cfg, true, true);
    }

    private static Station station(GamerFacts f, OverworldConfig cfg, boolean mayCraft, boolean logsForSmoker) {
        boolean ironInFurnace = furnaceHasIron(f);
        // the furnace is for meat only once the iron is done with it (or never needs it): while iron is owed the meat gets a
        // smoker of its own, which cooks twice as fast anyway, and a bag with no way to make one just keeps the meat until
        // the end of the plan
        boolean furnaceFree = !ironInFurnace && !ironOwed(f, cfg);
        // a cook already under way keeps its station, it is busy eating the logs and cobble that picked it
        String running = f.cookStation();
        if ("smoker".equals(running)) {
            return Station.SMOKER;
        }
        if ("furnace".equals(running) && !ironInFurnace) {
            return Station.FURNACE;
        }
        // a smoker of ours within NEAR or in the bag is where raw meat goes, before any furnace (WorkbenchRules.cookInSmoker). one in
        // the bag only counts with enough meat to be worth putting it down, or a station already standing to cook the little there is
        if (WorkbenchRules.cookInSmoker(f.stations().smoker().heldForPlanner(), f.has(Items.SMOKER) && (mayCraft || f.stations().furnace().heldForPlanner()))) {
            return Station.SMOKER;
        }
        if (furnaceFree && (f.stations().furnace().heldForPlanner() || (mayCraft && f.has(Items.FURNACE)))) {
            return Station.FURNACE;
        }
        if (!mayCraft) {
            return Station.NONE;
        }
        // the stone floor is the cobble the kit still owes its tools, a smoker is not allowed to eat the stone pick
        int free = f.count(Items.COBBLESTONE) - KitPlanner.stoneFloor(f, cfg);
        boolean furnaceInBag = f.has(Items.FURNACE);
        if (logsForSmoker && (furnaceInBag || free >= SMOKER_COBBLE)) {
            return Station.SMOKER;
        }
        return furnaceFree && free >= SMOKER_COBBLE ? Station.FURNACE : Station.NONE;
    }

    // raw meat worth a cook sitting in the bag with nothing standing in the way but a station and fuel: the overworld, a cook that
    // has not started, not backed off, no batch pending and no meat left cold in a station. the early outs of need(), as a question
    // of its own so the wood budget can claim logs for a cook that need() is turning down for want of them
    static boolean waiting(GamerFacts f) {
        return f.dimension() == Dimension.OVERWORLD && raw(f) >= MIN_RAW && f.cookStation() == null && !f.cookSuspended()
                && f.pendingFoodUnits() == 0 && !stranded(f);
    }

    // smelts the coal and charcoal in the bag give (the ones a furnace may burn)
    static int coalSmelts(GamerFacts f) {
        return burnable(f, Items.COAL, Items.CHARCOAL) * COAL_SMELTS;
    }

    // logs a new smoker costs (WoodBudget has the rule). package only so the wood golden can see it
    static int smokerLogs(GamerFacts f) {
        return WoodBudget.smokerLogsFor(f);
    }

    // a smoker we still have to make: the logs it eats are not fuel. package only so the wood golden can see it
    static boolean smokerToMake(GamerFacts f, Station station) {
        return station == Station.SMOKER && WoodBudget.smokerOwedFor(f);
    }

    // smelts of fuel the cook could burn: what the smelt task is allowed to burn (f.burnable, altoSupportedFuels), and wood only
    // above what the run keeps for crafting (the same reserve FuelPolicy honours). this is FuelPolicy.usableFuel over the facts, give or take a species
    // nothing burns (WoodBudgetContradictionsTest): the gate said yes to a stack of logs the smoker then refused, and the meat was already in it by the time anyone noticed
    public static int fuelSmelts(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return fuelSmelts(f, cfg, endBeds, 0);
    }

    // `logsSpoken` = logs about to go into something else (a smoker), taken off the spare wood first
    static int fuelSmelts(GamerFacts f, OverworldConfig cfg, int endBeds, int logsSpoken) {
        return fuelSmelts(f, WoodBudget.of(f, cfg, endBeds), logsSpoken);
    }

    // (the budget has to be this bag's, it holds the wood half)
    static int fuelSmelts(GamerFacts f, WoodBudget wood, int logsSpoken) {
        return coalSmelts(f) + wood.burnableWoodSmelts(logsSpoken);
    }

    // smelts of wood the cook may count: the wood above the reserve less the logs a smoker still to be made will eat (the smoker got
    // built and then sat there cold). the ONE number, the cook need and the coal detour both read it, so the detour fetches the coal
    // the cook is short of and not one less
    static int woodSmeltsAfterClaims(WoodBudget wood, Station station) {
        return wood.burnableWoodSmelts(wood.logsSpokenFor(station == Station.SMOKER));
    }

    // (for a caller that has not asked for the station: where the meat in the bag would go)
    static int woodSmeltsAfterClaims(GamerFacts f, OverworldConfig cfg, WoodBudget wood) {
        return woodSmeltsAfterClaims(wood, station(f, cfg, raw(f) >= MIN_RAW, wood));
    }

    // the wood half of fuelSmelts: logs and planks above the reserve. the coal detour counts it towards the coal we already have
    public static int woodSmelts(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return woodSmelts(f, cfg, endBeds, 0);
    }

    // (package only so the wood golden can see the spoken form)
    static int woodSmelts(GamerFacts f, OverworldConfig cfg, int endBeds, int logsSpoken) {
        return WoodBudget.of(f, cfg, endBeds).burnableWoodSmelts(logsSpoken);
    }

    // how many of these we hold that a furnace may burn
    private static int burnable(GamerFacts f, Item... items) {
        int total = 0;
        for (Item item : items) {
            if (f.burnable(item)) {
                total += f.count(item);
            }
        }
        return total;
    }

    // what the raw meat in the bag is worth at its cooked value beyond what it gives raw. foodUnits() books the first, the eater
    // (FoodSelector) only ever gets the second out of a porkchop it has to eat raw, so this is the part that only exists once a
    // furnace has been at it
    public static int rawGap(GamerFacts f) {
        int gap = 0;
        for (Item meat : RAW_MEAT) {
            int n = f.count(meat);
            if (n > 0) {
                gap += n * (FoodHelper.plannedNutrition(meat) - FoodHelper.ownNutrition(meat));
            }
        }
        return gap;
    }

    // can the raw meat in the bag actually become dinner. a cook is loading or a smoker is on it already, or the cook need would
    // be asked for right now (a station, fuel for the biggest pile, the overworld, not backed off). when this is false the meat gets
    // eaten raw, so the plan must not count it at the cooked value (ten raw porkchop is 80 planned units and 30 real ones)
    public static boolean cookFeasible(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return f.cookStation() != null || f.pendingFoodUnits() > 0 || need(f, cfg, endBeds) != null;
    }

    // the same with the tick's wood budget, which is what the food plan has when the engine builds it
    public static boolean cookFeasible(GamerFacts f, OverworldConfig cfg, int endBeds, WoodBudget wood) {
        return f.cookStation() != null || f.pendingFoodUnits() > 0 || need(f, cfg, endBeds, wood) != null;
    }

    // the cook need, or null when there is nothing to do about the raw meat right now
    public static KitNeed need(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return need(f, cfg, endBeds, WoodBudget.of(f, cfg, endBeds));
    }

    public static KitNeed need(GamerFacts f, OverworldConfig cfg, int endBeds, WoodBudget wood) {
        int raw = raw(f);
        // a cook that is mid load has the meat in the station's slot, not the bag: with the last kind in there raw reads 0, and
        // dropping the need then swaps the task out from under a half loaded smoker
        boolean loading = f.cookStation() != null;
        // a smoker cooking for us already is the one input slot taken, the next kind of meat waits for it to be collected
        if ((raw == 0 && !loading) || f.dimension() != Dimension.OVERWORLD || f.cookSuspended() || f.pendingFoodUnits() > 0) {
            return null;
        }
        // meat sitting cold in a station of ours is the one input slot taken: the pickup trip empties it first (or lights it)
        if (!loading && stranded(f)) {
            return null;
        }
        Station station = station(f, cfg, raw >= MIN_RAW, wood);
        if (station == Station.NONE) {
            return null;
        }
        // the first load is the biggest pile, the others cook after it has been collected. a cook that is already loading skips
        // this: the coal it put in the smoker left the bag, so the count dips under the pile the moment the fuel goes in and
        // the cook walked out of its own plan with the meat in the slot (23:18:36). the smelt task fetches what it is short of
        if (!loading && coalSmelts(f) + woodSmeltsAfterClaims(wood, station) < pile(f)) {
            return null;
        }
        return new KitNeed(station == Station.SMOKER ? KitNeed.COOK_SMOKER : KitNeed.COOK_FURNACE, MIN_RAW);
    }

    // a furnace or smoker we just emptied, with meat in the bag: load it before it goes back in the bag (FurnaceWatch). no
    // station question, we are standing at one
    public static boolean reusable(GamerFacts f, OverworldConfig cfg, int endBeds, boolean smoker) {
        return reusable(f, cfg, endBeds, smoker, WoodBudget.of(f, cfg, endBeds));
    }

    public static boolean reusable(GamerFacts f, OverworldConfig cfg, int endBeds, boolean smoker, WoodBudget wood) {
        // the plain furnace we are standing at is the iron's while ore is owed, same rule as station(): the big batch walks back
        // to the furnace it remembers and would swap the meat out
        if (!smoker && ironOwed(f, cfg)) {
            return false;
        }
        return raw(f) >= MIN_RAW && !f.cookSuspended() && f.pendingFoodUnits() == 0 && !stranded(f) && fuelSmelts(f, wood, 0) >= pile(f);
    }

    public static int index(List<KitNeed> needs) {
        for (int i = 0; i < needs.size(); i++) {
            if (KitNeed.isCookName(needs.get(i).catalogueName())) {
                return i;
            }
        }
        return -1;
    }

    // does the cook go in front of the rest. the plan always ends with it (that is the "mandatory before the nether" part), this is
    // the early start: enough meat to be worth it, and we are on the surface or the next job is not ore. `latched` is a start
    // that already happened, it carries on or the bot would flip every time it walks over the heightmap line
    public static boolean leads(List<KitNeed> needs, int at, int raw, boolean surfaced, boolean latched) {
        return leads(needs, at, raw, surfaced, latched, false);
    }

    // `loadBusy` = a furnace load that is not the cook's is in flight right now (WorkbenchRules.loadInFlight, the caller leaves it
    // false once the cook is the one running): the cook does not start in the middle of it, latched or not. it took over with
    // three raw iron on the way into the new furnace (23:18:31, the latch was set four seconds earlier while the furnace was
    // still being crafted, the head latch only handed over later) and the bot walked off with the ore on the cursor. the cook
    // simply waits a few ticks for the load to be recorded
    public static boolean leads(List<KitNeed> needs, int at, int raw, boolean surfaced, boolean latched, boolean loadBusy) {
        return leads(needs, at, raw, surfaced, latched, loadBusy, false);
    }

    // `committed` = the cook task is running and has its station (f.cookStation()). it leads whatever the bag says: the moment the
    // meat goes into the smoker the bag reads 0 raw, and "fewer than MIN_RAW" used to hand the head to the iron in the middle of
    // the load (13:11:01 and again at 13:11:14, both exactly when the first click landed). the cook has its own bounded give up
    public static boolean leads(List<KitNeed> needs, int at, int raw, boolean surfaced, boolean latched, boolean loadBusy,
                                boolean committed) {
        if (committed) {
            return true;
        }
        if (raw < MIN_RAW || loadBusy) {
            return false;
        }
        return latched || surfaced || !FoodGate.headIsOre(needs, at);
    }

    // the cook moved to the front, behind a food need that is leading (hunting first, cooking what it brought back after)
    public static List<KitNeed> lead(List<KitNeed> needs, int at) {
        List<KitNeed> out = new ArrayList<>(needs);
        KitNeed cook = out.remove(at);
        int to = !out.isEmpty() && KitNeed.FOOD.equals(out.get(0).catalogueName()) ? 1 : 0;
        out.add(to, cook);
        return out;
    }
}
