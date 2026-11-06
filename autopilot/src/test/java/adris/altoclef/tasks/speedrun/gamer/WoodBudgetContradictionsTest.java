package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.phases.GatherPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.IronPhase;
import adris.altoclef.util.helpers.FuelPolicy;
import adris.altoclef.util.helpers.ItemFixture;
import adris.altoclef.util.helpers.ItemHelper;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

// where the wood views disagreed with each other. a test with a "known wrong" line in it still pins what the code answers today and
// is waiting on its behaviour round (play-tested on its own), the fix is meant to flip those assertions. the ones without are
// fixed, and pin the right answer. nothing here is a feature, don't "fix" a test to make the code look right
public class WoodBudgetContradictionsTest {
    private static final int BEDS = 8;
    private final OverworldConfig cfg = new OverworldConfig();
    // the spare shields have their own test (KitPlannerTest), the budgets here are the kit as it was
    {
        cfg.netherSpareShields = 0;
        cfg.netherSpareSwords = 0;
    }

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @After
    public void clearFuel() {
        FuelPolicy.clear();
    }

    // everything the kit makes out of wood is made, the beds are the only wood left owed
    private static FakeFacts madeEverything() {
        return new FakeFacts().give(Items.WOODEN_PICKAXE, 1).give(Items.WOODEN_AXE, 1).give(Items.STONE_PICKAXE, 2)
                .give(Items.STONE_SWORD, 1).give(Items.CRAFTING_TABLE, 1).give(Items.FURNACE, 1).give(Items.SHIELD, 1)
                .give(Items.LADDER, 3).give(Items.IRON_PICKAXE, 1).give(Items.IRON_SWORD, 1).give(Items.BUCKET, 2)
                .give(Items.SHEARS, 1).give(Items.FLINT_AND_STEEL, 1);
    }

    private static FoodPlan calm(FakeFacts f, OverworldConfig cfg, WoodBudget w) {
        EndConfig end = new EndConfig();
        end.beds = BEDS;
        return FoodPlan.of(f, cfg, end, true, false, w);
    }

    private static int logNeed(List<KitNeed> plan) {
        return plan.stream().filter(n -> n.catalogueName().equals("log")).mapToInt(KitNeed::count).findFirst().orElse(-1);
    }

    // (a) the cook outranks the beds in the IRON phase. raw meat is waiting, the smoker is still to be made and the logs held are
    // the beds' on the gather's books, which IRON never chops for: the smoker used to starve (the station said smoker, the gate said
    // no fuel, nothing chopped). now the cook's claim is on the logs first, and the plan chops what is short of it
    @Test
    public void theIronCookHasTheLogsItNeedsBeforeTheBeds() {
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 5).give(Items.BEEF, 5).give(Items.COBBLESTONE, 30);
        f.nearSurface = true;
        WoodBudget w = WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON);
        assertEquals(0, w.ironLogsOwed());
        // the beds are still the gather's debt, that view does not move
        assertEquals(2, w.gatherLogsOwed());
        assertEquals(new WoodReserve.Keep(0, 0), w.kitClaim());
        // a smoker's four (the table is held) and four logs to burn five smelts, nothing speaks for the logs but the cook
        assertEquals(4, w.smokerClaim());
        assertEquals(4, w.fuelClaim());
        assertEquals(new WoodReserve.Keep(0, 0), w.keep());
        assertEquals(new WoodReserve.Keep(0, 0), w.bedClaim());
        assertEquals(5, w.spareLogs());
        // five held, eight claimed: the plan chops the three
        assertEquals(3, w.cookChopLogs());
        assertEquals(3, w.ironChopLogs(f));
        assertEquals(8, logNeed(KitPlanner.plan(f, cfg, BEDS, calm(f, cfg, w), w)));
        // the station is the smoker and the gate waits for the fuel
        assertEquals(CookGate.Station.SMOKER, CookGate.station(f, cfg, true, w));
        assertNull(CookGate.need(f, cfg, BEDS, w));
        // with the three chopped it goes
        f.give(Items.OAK_LOG, 3);
        WoodBudget after = WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON);
        assertEquals(0, after.cookChopLogs());
        assertEquals(KitNeed.COOK_SMOKER, CookGate.need(f, cfg, BEDS, after).catalogueName());
        // and the logs the furnaces may not burn are still the kit's, here nothing
        assertEquals(new WoodReserve.Keep(0, 0), after.fuelKeep());
    }

    // the gather goes on keeping what it kept, and the cook only claims in the iron phase
    @Test
    public void theGatherStillKeepsEveryLogForTheBeds() {
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 5).give(Items.BEEF, 5).give(Items.COBBLESTONE, 30);
        f.nearSurface = true;
        WoodBudget w = WoodBudget.of(f, cfg, BEDS);
        assertEquals(new WoodReserve.Keep(5, 0), w.keep());
        assertEquals(0, w.smokerClaim() + w.fuelClaim() + w.cookChopLogs());
        assertEquals(0, w.ironChopLogs(f));
        assertEquals(w, WoodBudget.of(f, cfg, BEDS, GamerPhase.GATHER));
    }

    // the cook is the second claim, the kit the first: when the kit still wants wood the cook is not fed from it, and the chop covers both
    @Test
    public void theKitIsFedBeforeTheCookAndTheChopCoversBoth() {
        // no iron pickaxe: the kit owes its two sticks, a plank each, which one log covers (and then the log is the kit's)
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 1).give(Items.BEEF, 5).give(Items.COBBLESTONE, 30);
        f.items.remove(Items.IRON_PICKAXE);
        f.nearSurface = true;
        WoodBudget w = WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON);
        assertEquals(0, w.ironLogsOwed());
        assertEquals(new WoodReserve.Keep(1, 0), w.kitClaim());
        // the one log we hold is the kit's, so the whole claim is short
        assertEquals(w.smokerClaim() + w.fuelClaim(), w.cookChopLogs());
        assertEquals(w.cookChopLogs(), w.ironChopLogs(f));
        // with none held the kit is short itself, and the chop is its log and the cook's claim, in one trip
        f.items.remove(Items.OAK_LOG);
        w = WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON);
        assertEquals(1, w.ironLogsOwed());
        assertEquals(new WoodReserve.Keep(0, 0), w.kitClaim());
        assertEquals(w.ironLogsOwed() + w.smokerClaim() + w.fuelClaim(), w.ironChopLogs(f));
        // ore in the bag ends the kit's chop, not the cook's: the cook near the surface still chops the lot
        f.give(Items.RAW_IRON, 3);
        assertEquals(w.ironLogsOwed() + w.cookChopLogs(), w.ironChopLogs(f));
        // down in the mine nothing is chopped for the cook, and the kit's chop is over once the ore is in
        f.nearSurface = false;
        WoodBudget deep = WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON);
        assertEquals(0, deep.cookChopLogs());
        assertEquals(0, deep.ironChopLogs(f));
    }

    // the failure the first try at this had: a smoker only from logs spare after the reserve, so the smoker never got built. here the
    // bag is the one that failed (iron owed, raw meat waiting, no smoker, N logs) and the answer is never "station smoker, no fuel,
    // nothing to chop": either the cook goes with what is held, or the plan chops, and after that chop the cook goes
    @Test
    public void theIronCookIsNeverLeftWaitingOnLogsNothingChops() {
        int cases = 0;
        for (boolean kitWantsWood : new boolean[]{false, true}) {
          for (boolean table : new boolean[]{true, false}) {
           for (int coal : new int[]{0, 1, 3}) {
            for (int planks : new int[]{0, 6}) {
             for (boolean ore : new boolean[]{false, true}) {
              for (int beds : new int[]{0, 3, 8}) {
                for (int logs = 0; logs <= 12; logs++) {
                    FakeFacts f = madeEverything().give(Items.BEEF, 5).give(Items.COBBLESTONE, 30);
                    if (kitWantsWood) {
                        f.items.remove(Items.IRON_PICKAXE);
                    }
                    if (!table) {
                        f.items.remove(Items.CRAFTING_TABLE);
                    }
                    if (coal > 0) {
                        f.give(Items.COAL, coal);
                    }
                    if (planks > 0) {
                        f.give(Items.OAK_PLANKS, planks);
                    }
                    if (ore) {
                        f.give(Items.RAW_IRON, 3);
                    }
                    f.nearSurface = true;
                    if (logs > 0) {
                        f.give(Items.OAK_LOG, logs);
                    }
                    cases++;
                    String where = "kitWantsWood=" + kitWantsWood + " table=" + table + " coal=" + coal + " planks=" + planks + " ore=" + ore
                            + " beds=" + beds + " logs=" + logs;
                    boolean cooked = false;
                    // two chops at most: the claim is sized to be met by one, a second would be a chase
                    for (int round = 0; round < 3 && !cooked; round++) {
                        WoodBudget w = WoodBudget.of(f, cfg, beds, GamerPhase.IRON);
                        assertTrue(where + ": iron is owed", CookGate.ironOwed(f, cfg));
                        if (CookGate.need(f, cfg, beds, w) != null) {
                            cooked = true;
                            break;
                        }
                        EndConfig end = new EndConfig();
                        end.beds = beds;
                        List<KitNeed> plan = KitPlanner.plan(f, cfg, beds, FoodPlan.of(f, cfg, end, true, false, w), w);
                        int chop = logNeed(plan);
                        assertTrue(where + " round " + round + ": no cook and no chop in " + plan, chop > f.count(ItemHelper.LOG));
                        f.items.put(Items.OAK_LOG, chop);
                    }
                    assertTrue(where + ": still no cook after the chop", cooked);
                }
              }
             }
            }
           }
          }
        }
        assertEquals(2 * 2 * 3 * 2 * 2 * 3 * 13, cases);
    }

    // a cook that is under way has its meat in the slot (the bag reads no raw) and the claim that sized its fuel is gone with it: the
    // furnace must still be handed the logs it burns, or the smoker is crafted and loaded and then sits cold
    @Test
    public void aCookThatHasStartedStillMayBurnItsLogs() {
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 5).give(Items.COBBLESTONE, 30);
        f.cookStation = "smoker";
        f.smokerPlaced = true;
        f.nearSurface = true;
        WoodBudget w = WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON);
        // the beds are still owed on the gather's books, which would keep all 5
        assertTrue(w.gatherLogsOwed() > 0);
        assertEquals(new WoodReserve.Keep(0, 0), w.fuelKeep());
        assertEquals(0, w.stockClaim());
        WoodReserve.apply(w);
        assertEquals(5, FuelPolicy.usable(List.of(new ItemStack(Items.OAK_LOG, 5)))[0]);
        // nothing is chopped for a cook that is already going
        assertEquals(0, w.cookChopLogs());
        // the gather does not do this
        assertEquals(new WoodReserve.Keep(5, 0), WoodBudget.of(f, cfg, BEDS).fuelKeep());
    }

    // whatever the cook takes, the reserve never drops under what the kit holds (in planks, a log is four), where the two searches
    // split the kit's wood into logs and planks differently
    @Test
    public void theCooksClaimNeverEatsIntoTheKitsWood() {
        int cases = 0;
        for (int logs = 0; logs <= 6; logs++) {
            for (int planks = 0; planks <= 12; planks++) {
                for (int beds : new int[]{0, 1, 3, 8}) {
                    for (boolean pick : new boolean[]{true, false}) {
                      for (String running : new String[]{null, "smoker"}) {
                        FakeFacts f = madeEverything().give(Items.BEEF, 5).give(Items.COBBLESTONE, 30);
                        f.cookStation = running;
                        if (!pick) {
                            f.items.remove(Items.IRON_PICKAXE);
                            f.items.remove(Items.STONE_PICKAXE);
                            f.items.remove(Items.STONE_SWORD);
                        }
                        f.give(Items.OAK_LOG, logs).give(Items.OAK_PLANKS, planks);
                        WoodBudget w = WoodBudget.of(f, cfg, beds, GamerPhase.IRON);
                        WoodReserve.Keep kit = w.kitClaim();
                        WoodReserve.Keep keep = w.keep();
                        assertTrue("logs=" + logs + " planks=" + planks + " beds=" + beds + " pick=" + pick + ": keep " + keep + " kit " + kit,
                                4 * keep.logs() + keep.planks() >= 4 * kit.logs() + kit.planks());
                        cases++;
                      }
                    }
                }
            }
        }
        assertEquals(7 * 13 * 4 * 2 * 2, cases);
    }

    // (b) the coal detour used to count the wood a furnace could burn WITHOUT the logs a smoker still to make would eat, where the
    // cook need took those off, so the detour thought there was more fuel than the cook did and fetched a coal short. both read
    // CookGate.woodSmeltsAfterClaims now
    @Test
    public void theCoalDetourDoesNotSubtractTheSmokersLogs() {
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 12).give(Items.BEEF, 5).give(Items.COBBLESTONE, 30).give(Items.BED.white(), 8);
        WoodBudget w = WoodBudget.of(f, cfg, BEDS);
        assertTrue(w.smokerOwed());
        assertEquals(18, w.burnableWoodSmelts(0));
        assertEquals(12, w.burnableWoodSmelts(w.smokerLogs()));
        StubContext ctx = new StubContext() {
            @Override
            public GamerFacts facts() {
                return f;
            }
        };
        ctx.cfg.overworld = cfg;
        int detour = ResourceDetour.coalNeed(ctx);
        int ingots = KitPlanner.ingotsNeeded(f, cfg);
        int loads = KitPlanner.smeltLoads(f, cfg);
        int withSpoken = DetourSpec.coalNeed(ingots, 0, 0, CookGate.raw(f), w.burnableWoodSmelts(w.smokerLogs()), loads);
        assertEquals(w.burnableWoodSmelts(w.smokerLogs()), CookGate.woodSmeltsAfterClaims(f, cfg, w));
        assertEquals(withSpoken, detour);
        // the cook's own count is the lower one and the detour goes by it: one more coal than before
        assertNotEquals(DetourSpec.coalNeed(ingots, 0, 0, CookGate.raw(f), w.burnableWoodSmelts(0), loads), detour);
        assertEquals(6, detour);
    }

    // (c) the log stock-up asks for logs in the wait for the iron. they are wood to craft with later (SmeltFiller: "it is only there
    // so we have wood to craft with", smeltExtras: "logs to hold"), not fuel, and the wood budget had no use for them (the kit is
    // made), so the reserve called every one spare and the furnace burned what the stock-up chopped, for the next wait to chop it
    // again. the iron budget holds them back now (stockClaim), under two things that outrank it: the cook's claim, and the fuel the
    // smelts still ahead need (an iron batch with no coal must be able to burn the pile, or the claim is a new way to stall)
    @Test
    public void theLogStockUpChopsForLogsTheFurnaceMayNotBurn() {
        // coal for every smelt ahead, so the logs are not fuel
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 3).give(Items.BED.white(), 8).give(Items.COAL, 8);
        WoodBudget gather = WoodBudget.of(f, cfg, BEDS);
        assertEquals(0, gather.gatherLogsOwed());
        // the gather has no stock-up, its view is what it was
        assertEquals(new WoodReserve.Keep(0, 0), gather.keep());
        WoodBudget w = WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON);
        // it wants a pile of 8 and holds 3 of them, all spoken for
        assertEquals(8, w.stockUpLogs(8, true));
        assertEquals(3, w.stockClaim());
        assertEquals(new WoodReserve.Keep(3, 0), w.keep());
        assertEquals(0, w.spareLogs());
        assertEquals(0, w.burnableWoodSmelts(0));
        // planks are wood to it too: 12 of them are three logs of the pile
        f.give(Items.OAK_PLANKS, 12);
        WoodBudget planked = WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON);
        assertEquals(3, planked.stockClaim());
        f.give(Items.OAK_PLANKS, 20);
        assertEquals(0, WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON).stockUpLogs(8, true));
        assertEquals(0, WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON).stockClaim());
        // the cook outranks it: raw meat waiting and the claim is the cook's, the logs are fuel and a smoker again
        FakeFacts meat = madeEverything().give(Items.OAK_LOG, 3).give(Items.BED.white(), 8).give(Items.BEEF, 5).give(Items.COBBLESTONE, 30)
                .give(Items.COAL, 8);
        WoodBudget cooking = WoodBudget.of(meat, cfg, BEDS, GamerPhase.IRON);
        assertEquals(0, cooking.stockClaim());
        assertEquals(new WoodReserve.Keep(0, 0), cooking.keep());
    }

    // and the fuel in hand outranks the stock: no coal, a batch of iron ahead, and the logs stay burnable
    @Test
    public void theLogStockUpNeverLocksUpTheFuelTheSmeltsAheadNeed() {
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 3).give(Items.BED.white(), 8);
        int ahead = Math.max(0, KitPlanner.ingotsNeeded(f, cfg) - f.count(Items.IRON_INGOT));
        assertTrue("iron is still to be smelted", ahead > 4);
        WoodBudget w = WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON);
        assertEquals(0, w.stockClaim());
        assertEquals(new WoodReserve.Keep(0, 0), w.keep());
        assertEquals(4, w.burnableWoodSmelts(0));
        // coal for most of it: the wood left over is the stock's, as far as the smelts do not need it
        f.give(Items.COAL, (ahead - 3) / 8 + 1);
        WoodBudget some = WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON);
        int fuel = CookGate.fuelSmelts(f, some, 0);
        assertTrue("never less fuel than the smelts ahead, if there was that much", fuel >= Math.min(ahead, ((ahead - 3) / 8 + 1) * 8 + 4));
    }

    // (d) GatherPhase.isDone and IronPhase's built their own FoodPlan, at the kit's minimum with the latch from the state, and not
    // the tick's latched ctx.food() (nor the tick's wood). mid trip the two disagreed about the cook's logs, so isDone said the phase
    // was finished while the plan it had just walked still had a chop in it. the engine asks with the context now. the food half
    // stays the kit's minimum on purpose (a trip past it carries on in the next phase, the latch is the run's), the wood half is
    // the tick's: a chop still in the plan keeps the phase going
    @Test
    public void isDoneReadsTheTicksPlanForTheChopsAndStopsAtTheMinimumForTheFood() {
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 3).give(Items.BED.white(), 8).give(Items.COBBLESTONE, 30);
        f.foodUnits = 100;
        StubContext ctx = new StubContext() {
            @Override
            public GamerFacts facts() {
                return f;
            }
        };
        ctx.cfg.end.beds = BEDS;
        ctx.state.phase = GamerPhase.GATHER;
        // a food trip is on (state.foodRefilling), the bag is past the minimum but not at the trip's stop
        ctx.state.foodRefilling = true;
        List<KitNeed> plan = KitPlanner.gather(f, ctx.cfg.overworld, BEDS, ctx.food(), ctx.wood());
        assertEquals(List.of(new KitNeed("log", 14), new KitNeed(KitNeed.FOOD, 130)), plan);
        // 14 logs to chop for the cook is still owed, so GATHER is not done (it used to say yes: its own plan at the minimum had no chop)
        GatherPhase gather = new GatherPhase();
        assertFalse(gather.isDone(ctx));
        // with the logs in the bag only the trip is left, and that one carries on in IRON
        f.give(Items.OAK_LOG, 14);
        assertEquals(List.of(new KitNeed(KitNeed.FOOD, 130)), KitPlanner.gather(f, ctx.cfg.overworld, BEDS, ctx.food(), ctx.wood()));
        assertTrue(gather.isDone(ctx));

        // IRON: the cook's chop is in the tick's plan, and the phase is not done while it is
        FakeFacts raw = new FakeFacts().give(Items.OAK_LOG, 5).give(Items.BEEF, 5).give(Items.COBBLESTONE, 30).give(Items.CRAFTING_TABLE, 1)
                .give(Items.BED.white(), 8);
        raw.foodUnits = 100;
        raw.nearSurface = true;
        StubContext iron = new StubContext() {
            @Override
            public GamerFacts facts() {
                return raw;
            }
        };
        iron.cfg.overworld.ironKit.clear();
        iron.cfg.overworld.starterKit.clear();
        iron.cfg.overworld.armorPlan = OverworldConfig.ArmorPlan.NONE;
        iron.state.phase = GamerPhase.IRON;
        List<KitNeed> ironPlan = KitPlanner.plan(raw, iron.cfg.overworld, iron.cfg.end.beds, iron.food(), iron.wood());
        assertEquals(8, logNeed(ironPlan));
        assertFalse(new IronPhase().isDone(iron));
    }

    // (e, found on the way) the cook gate's count of wood a furnace may burn takes the reserve off the burnable logs only, but
    // FuelPolicy hands the same reserve out by item name over every log, burnable or not. with a species the furnace won't touch
    // in the bag (a crimson stem) the reserve lands on it, the oak stays fully burnable, and the gate undercounts the fuel
    @Test
    public void theReserveLandsOnLogsNothingBurnsSoTheGateUndercountsTheFuel() {
        FakeFacts f = madeEverything().give(Items.CRIMSON_STEM, 10).give(Items.OAK_LOG, 12);
        f.unburnable.add(Items.CRIMSON_STEM);
        WoodBudget w = WoodBudget.of(f, cfg, BEDS);
        assertEquals(new WoodReserve.Keep(7, 0), w.keep());
        WoodReserve.apply(w);
        int[] usable = FuelPolicy.usable(List.of(new ItemStack(Items.CRIMSON_STEM, 10), new ItemStack(Items.OAK_LOG, 12)));
        // the furnace may burn all 12 oak, the 7 kept were taken from the stems (they sort first), and the gate counts what it is handed
        assertEquals(3, usable[0]);
        assertEquals(12, usable[1]);
        assertEquals(18, w.burnableWoodSmelts(0));
        assertEquals(usable[1] * 3 / 2, w.burnableWoodSmelts(0));
        // logs about to be a smoker come off the same pool: 7 kept and 4 spoken for are 11, the ten stems soak up ten, the oak one
        assertEquals(11 * 3 / 2, w.burnableWoodSmelts(4));
    }

    // (f, found by the verifier) outside the overworld the furnaces are told to keep every log, but the smelts the coal detour and the
    // cook gate counted took the planner's (smaller) keep off, so they counted wood as fuel that FuelPolicy would not let burn. they
    // count against the furnace's keep (fuelKeep) now
    @Test
    public void downInTheNetherTheCountedFuelIsOnlyWoodTheFurnaceMayBurn() {
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 12);
        f.dimension = baritone.api.utils.Dimension.NETHER;
        WoodBudget w = WoodBudget.of(f, cfg, BEDS);
        assertEquals(new WoodReserve.Keep(12, 0), w.fuelKeep());
        // the planner's own keep is 7 (5 spare logs by its books), but what is counted as fuel is what the furnace is handed: none
        assertEquals(new WoodReserve.Keep(7, 0), w.keep());
        assertEquals(0, w.burnableWoodSmelts(0));
        WoodReserve.apply(w);
        assertEquals(0, FuelPolicy.usable(List.of(new ItemStack(Items.OAK_LOG, 12)))[0]);
    }
}
