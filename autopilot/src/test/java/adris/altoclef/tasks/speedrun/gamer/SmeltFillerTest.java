package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.SmeltFiller.Schedule;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SmeltFillerTest {
    private static final int BEDS = 8;
    private OverworldConfig cfg;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Before
    public void setUp() {
        cfg = new OverworldConfig();
        cfg.netherSpareShields = 0;
        cfg.netherSpareSwords = 0;
    }

    private static List<String> names(List<KitNeed> needs) {
        return needs.stream().map(KitNeed::catalogueName).toList();
    }

    private static KitNeed find(List<KitNeed> needs, String name) {
        return needs.stream().filter(n -> n.catalogueName().equals(name)).findFirst().orElse(null);
    }

    // the starter kit, enough food to leave, and nothing iron yet: where the bot is when the first batch goes in
    private FakeFacts atTheFurnace() {
        FakeFacts f = new FakeFacts();
        f.give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1).give(Items.FURNACE, 1).give(Items.LADDER, 3);
        f.foodUnits = 70;
        return f;
    }

    @Test
    public void theDiamondDigWaitsForTheCraftsTheFurnaceOwes() {
        // the essentials in hand, the armor's iron still cooking: the dig is no ingots but it is not the filler either
        FakeFacts f = atTheFurnace().give(Items.IRON_PICKAXE, 1).give(Items.FLINT_AND_STEEL, 1).give(Items.BUCKET, 2)
                .give(Items.IRON_SWORD, 1).give(Items.SHIELD, 1).give(Items.SHEARS, 1).cooking("iron_ingot", 24, 400);
        Schedule s = SmeltFiller.schedule(f, cfg, BEDS);
        assertNull(s.runnable().toString(), find(s.runnable(), KitPlanner.DIAMOND));
        assertEquals(new KitNeed(KitPlanner.DIAMOND, 3), find(s.blocked(), KitPlanner.DIAMOND));
        // nothing waiting on the furnace (a smoker cooks the meat, say): the dig is runnable again
        cfg.armorPlan = OverworldConfig.ArmorPlan.NONE;
        FakeFacts done = atTheFurnace().give(Items.IRON_PICKAXE, 1).give(Items.FLINT_AND_STEEL, 1).give(Items.BUCKET, 2)
                .give(Items.IRON_SWORD, 1).give(Items.SHIELD, 1).give(Items.SHEARS, 1).cooking("cooked_beef", 8, 400);
        assertEquals(new KitNeed(KitPlanner.DIAMOND, 3), find(SmeltFiller.schedule(done, cfg, BEDS).runnable(), KitPlanner.DIAMOND));
    }

    @Test
    public void withNoJobTheScheduleIsJustThePlan() {
        FakeFacts f = atTheFurnace();
        Schedule s = SmeltFiller.schedule(f, cfg, BEDS);
        assertEquals(KitPlanner.plan(f, cfg, BEDS), s.runnable());
        assertTrue(s.blocked().isEmpty());
        assertEquals(39, find(s.runnable(), "iron_ingot").count());
    }

    @Test
    public void ingotsInTheFurnaceSatisfyTheIronNeed() {
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 40, 400);
        assertEquals(40, f.pendingOutput(Items.IRON_INGOT));
        assertNull(find(KitPlanner.plan(f, cfg, BEDS), "iron_ingot"));
        // but not a smaller batch, that one still needs the rest
        FakeFacts small = atTheFurnace().cooking("iron_ingot", 20, 400);
        assertEquals(39, find(KitPlanner.plan(small, cfg, BEDS), "iron_ingot").count());
    }

    @Test
    public void foodInTheSmokerCountsAsPendingNotAsHeld() {
        FakeFacts f = atTheFurnace();
        f.foodUnits = 10;
        f.cookingFood("cooked_mutton", 10, 6, 50);
        assertEquals(10, f.foodUnits());
        assertEquals(60, f.pendingFoodUnits());
        // 10 + 60 covers the 70 unit need but not an 80 one, and the bag number never lied about it
        assertNull(find(KitPlanner.gather(f, cfg, BEDS), KitNeed.FOOD));
        cfg.minFoodUnits = 80;
        // and a trip that starts right there, or 70 sits in the no-trip gap
        cfg.refillStartFoodUnits = 80;
        assertEquals(new KitNeed(KitNeed.FOOD, cfg.refillStopFoodUnits), find(KitPlanner.gather(f, cfg, BEDS), KitNeed.FOOD));
        assertEquals(10 + 60, KitPlanner.progressOf(f, new KitNeed(KitNeed.FOOD, 80), FoodPlan.ofBeds(f, cfg, BEDS)));
    }

    @Test
    public void ironJobsAreNotFood() {
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 40, 400);
        assertEquals(0, f.pendingFoodUnits());
    }

    @Test
    public void aSecondBatchOfMeatWaitsForTheSmokerToBeEmptied() {
        FakeFacts f = atTheFurnace();
        f.foodUnits = 0;
        f.cookingFood("cooked_porkchop", 3, 8, 15).give(Items.MUTTON, 7);
        KitNeed food = new KitNeed(KitNeed.FOOD, 70);
        assertTrue(SmeltFiller.foodBlocked(f, food));
        // no raw meat left to load, so the food task has nothing to wait for and may hunt
        FakeFacts hunter = atTheFurnace().cookingFood("cooked_porkchop", 3, 8, 15);
        assertFalse(SmeltFiller.foodBlocked(hunter, food));
        // and no job, no block
        assertFalse(SmeltFiller.foodBlocked(atTheFurnace().give(Items.MUTTON, 7), food));
        // only the food need ever waits
        assertFalse(SmeltFiller.foodBlocked(f, new KitNeed("log", 4)));
    }

    @Test
    public void gatherKeepsWorkingOnWoodWhileTheFoodCooks() {
        FakeFacts f = new FakeFacts();
        f.cookingFood("cooked_mutton", 7, 6, 35).give(Items.MUTTON, 3);
        List<KitNeed> runnable = SmeltFiller.gatherRunnable(f, cfg, BEDS);
        assertEquals(KitPlanner.gather(f, cfg, BEDS).stream().filter(n -> !KitNeed.FOOD.equals(n.catalogueName())).toList(),
                runnable);
        assertFalse(runnable.isEmpty());
        // the food need is not in the runnable list while the first batch cooks, even when it is still short
        assertNull(find(runnable, KitNeed.FOOD));
    }

    @Test
    public void heldAndCookingIngotsAddUp() {
        FakeFacts f = atTheFurnace().give(Items.IRON_INGOT, 10).cooking("iron_ingot", 30, 400);
        assertNull(find(KitPlanner.plan(f, cfg, BEDS), "iron_ingot"));
    }

    @Test
    public void craftsThatWantIngotsWeDoNotHoldAreBlocked() {
        Schedule s = SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 40, 400), cfg, BEDS);
        assertEquals(List.of("iron_pickaxe", "iron_sword", "bucket", "flint_and_steel", "shield", "shears", "iron_chestplate",
                "iron_helmet", "iron_leggings", "iron_boots"), names(s.blocked()));
        for (String iron : List.of("iron_pickaxe", "bucket", "iron_boots")) {
            assertNull(iron, find(s.runnable(), iron));
        }
    }

    @Test
    public void heldIngotsPayForCraftsInPlanOrder() {
        // 3 pays the pickaxe, nothing is left for the sword
        Schedule s = SmeltFiller.schedule(atTheFurnace().give(Items.IRON_INGOT, 3).cooking("iron_ingot", 36, 400), cfg, BEDS);
        assertEquals(new KitNeed("iron_pickaxe", 1), find(s.runnable(), "iron_pickaxe"));
        assertNull(find(s.runnable(), "iron_sword"));
        assertEquals(new KitNeed("iron_sword", 1), find(s.blocked(), "iron_sword"));
        assertNull(find(s.blocked(), "iron_pickaxe"));
    }

    @Test
    public void fillerComesInUsefulnessOrder() {
        Schedule s = SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 40, 400), cfg, BEDS);
        // a: what the kit wanted anyway (wool, the food target), then the portal's blocks
        // b: flint for the steel, planks for the shield, sticks and beds
        // c: the stock-up, in the order the config lists it
        assertEquals(List.of("wool", "food", "build_blocks", "flint", "planks", "food", "build_blocks", "log"),
                names(s.runnable()));
        assertEquals(new KitNeed("flint", 1), s.runnable().get(3));
        assertEquals(new KitNeed("planks", 6 + 2 + 12), s.runnable().get(4));
    }

    @Test
    public void ironFreeCraftsStayRunnableWhileTheIronCooks() {
        FakeFacts f = new FakeFacts().cooking("iron_ingot", 40, 400);
        f.give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1).give(Items.FURNACE, 1);
        f.foodUnits = 70;
        Schedule s = SmeltFiller.schedule(f, cfg, BEDS);
        // ladders are sticks, no ingots, so they are not stuck behind the furnace like the pickaxe is
        assertEquals(new KitNeed("ladder", 3), s.runnable().get(0));
        assertNull(find(s.blocked(), "ladder"));
    }

    @Test
    public void stockUpIsCappedByTheConfig() {
        Schedule s = SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 40, 400), cfg, BEDS);
        List<KitNeed> r = s.runnable();
        assertEquals(new KitNeed(KitNeed.FOOD, 100), r.get(1));
        assertEquals(new KitNeed(KitNeed.FOOD, 130), r.get(5));
        assertEquals(new KitNeed("wool", 3 * BEDS), r.get(0));
        assertEquals(new KitNeed(KitNeed.BUILD_BLOCKS, 32), r.get(2));
        assertEquals(new KitNeed(KitNeed.BUILD_BLOCKS, 64), r.get(6));
        assertEquals(new KitNeed("log", 8), r.get(7));
    }

    @Test
    public void heldPlanksCountTowardsTheLogStockUp() {
        // 3 logs and 20 planks is 32 planks of wood, the 8 log target is met without a trip
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 39, 400).give(Items.OAK_LOG, 3).give(Items.OAK_PLANKS, 20);
        assertNull(find(SmeltFiller.schedule(f, cfg, BEDS).runnable(), "log"));
        // 3 logs and 8 planks is 20 of the 32: the old answer asked for 5 more logs, 2 logs' worth of planks make it 3 more
        FakeFacts shy = atTheFurnace().cooking("iron_ingot", 39, 400).give(Items.OAK_LOG, 3).give(Items.OAK_PLANKS, 8);
        assertEquals(new KitNeed("log", 6), find(SmeltFiller.schedule(shy, cfg, BEDS).runnable(), "log"));
        assertEquals(0, WoodBudget.of(new FakeFacts().give(Items.OAK_PLANKS, 32), cfg, 0).stockUpLogs(8, true));
        assertEquals(8, WoodBudget.of(new FakeFacts().give(Items.OAK_PLANKS, 3), cfg, 0).stockUpLogs(8, true));
        assertEquals(8, WoodBudget.of(new FakeFacts(), cfg, 0).stockUpLogs(8, true));
    }

    @Test
    public void noLogStockUpDownTheMine() {
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 39, 400);
        assertNotNull(find(SmeltFiller.schedule(f, cfg, BEDS, true).runnable(), "log"));
        assertNull(find(SmeltFiller.schedule(f, cfg, BEDS, false).runnable(), "log"));
        // the rest of the filler (the plan, the prep) is not touched by depth, only the stock-ups are
        Schedule up = SmeltFiller.schedule(f, cfg, BEDS, true);
        assertEquals(up.runnable().stream().filter(n -> !up.isStockUp(n)).toList(), SmeltFiller.schedule(f, cfg, BEDS, false).runnable());
    }

    @Test
    public void everythingStockedMeansNothingRunnable() {
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 40, 400);
        f.foodUnits = 130;
        f.buildBlocks = 64;
        f.give(Items.WOOL.white(), 33).give(Items.OAK_LOG, 8).give(Items.OAK_PLANKS, 20).give(Items.FLINT, 1);
        Schedule s = SmeltFiller.schedule(f, cfg, BEDS);
        assertTrue(names(s.runnable()).toString(), s.runnable().isEmpty());
        assertFalse(s.blocked().isEmpty());
    }

    @Test
    public void ownedFlintAndSteelAsksForNoFlint() {
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 40, 400).give(Items.FLINT_AND_STEEL, 1);
        assertNull(find(SmeltFiller.schedule(f, cfg, BEDS).runnable(), "flint"));
        FakeFacts has = atTheFurnace().cooking("iron_ingot", 40, 400).give(Items.FLINT, 1);
        assertNull(find(SmeltFiller.schedule(has, cfg, BEDS).runnable(), "flint"));
    }

    @Test
    public void aTypoInTheExtrasIsIgnored() {
        cfg.smeltExtras = List.of(new OverworldConfig.KitItem("fod", 5), new OverworldConfig.KitItem("log", 8));
        List<String> n = names(SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 40, 400), cfg, BEDS).runnable());
        assertEquals("log", n.get(n.size() - 1));
        assertFalse(n.contains("fod"));
    }

    @Test
    public void everyFillerNameIsInTheCatalogue() {
        Schedule s = SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 40, 400), cfg, BEDS);
        for (KitNeed need : s.runnable()) {
            if (!need.isSpecial()) {
                assertTrue(need.catalogueName(), adris.altoclef.TaskCatalogue.taskExists(need.catalogueName()));
            }
        }
    }

    // ---- stock-ups are surface work

    @Test
    public void aFoodTripIsNotTakenForTheFoodStockUp() {
        // the refill asks for 130 and so does the default food stock-up (100 + 30): a due iron job must not cut the trip short
        // as if it were filler
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 40, 400);
        f.foodUnits = 40;
        Schedule s = SmeltFiller.schedule(f, cfg, BEDS, true);
        KitNeed trip = new KitNeed(KitNeed.FOOD, cfg.refillStopFoodUnits);
        assertEquals(cfg.targetFoodUnits + 30, cfg.refillStopFoodUnits);
        assertTrue(s.stockUps().contains(trip));
        assertTrue(s.runnable().contains(trip));
        assertFalse(s.isStockUp(trip));
        // with no trip on, the very same need is the stock-up
        f.foodUnits = 110;
        Schedule fed = SmeltFiller.schedule(f, cfg, BEDS, true);
        assertTrue(fed.isStockUp(trip));
    }

    @Test
    public void downTheMineNoStockUpIsRunnable() {
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 40, 400);
        f.foodUnits = 20;
        Schedule deep = SmeltFiller.schedule(f, cfg, BEDS, false);
        assertTrue(deep.stockUps().isEmpty());
        assertNull(find(deep.runnable(), "log"));
        Schedule up = SmeltFiller.schedule(f, cfg, BEDS, true);
        assertEquals(List.of("food", "build_blocks", "log"), names(up.stockUps()));
        for (KitNeed extra : up.stockUps()) {
            assertTrue(up.runnable().contains(extra));
        }
        // the stock-up food is the +30 over the target, the plan's own 70 unit need is not one of them
        assertEquals(cfg.targetFoodUnits + 30, find(up.stockUps(), KitNeed.FOOD).count());
    }

    // (when a due job is fetched, and the stand-by at a quick smoker, are FurnacePlanTest now)

    @Test
    public void anIronCraftIsBlockedBehindTheCookingIngotsForTheWholeCook() {
        // the ladder run: the pickaxe waits on the ingots for as long as they cook, and FurnacePlan does not take that for a reason to
        // leave the need we are on (it only hears about the interrupts the phase names)
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 40, 100);
        assertFalse(SmeltFiller.schedule(f, cfg, BEDS).blocked().isEmpty());
    }

    @Test
    public void foodBehindItsOwnSmokerIsTheInterruptNotAnIronCraft() {
        // GATHER's interrupt: the food is the head of the plan and the smoker is on the last batch
        FakeFacts f = atTheFurnace().cookingFood("cooked_mutton", 3, 6, 15).give(Items.MUTTON, 7);
        f.foodUnits = 0;
        assertTrue(SmeltFiller.gatherBlocking(f, List.of(new KitNeed(KitNeed.FOOD, 70))));
    }

    @Test
    public void foodBehindItsOwnSmokerBlocksTheGatherPlan() {
        FakeFacts f = new FakeFacts().cookingFood("cooked_mutton", 7, 6, 35).give(Items.MUTTON, 3);
        List<KitNeed> plan = KitPlanner.gather(f, cfg, BEDS);
        // wood leads the gather plan, the food is behind it
        assertFalse(SmeltFiller.gatherBlocking(f, plan));
        assertTrue(SmeltFiller.gatherBlocking(f, List.of(new KitNeed(KitNeed.FOOD, 70))));
        assertFalse(SmeltFiller.gatherBlocking(f, List.of()));
    }

    @Test
    public void farAwayWorkIsNotFilteredByDistanceAnyMore() {
        // wool and food used to wait for a sheep or an animal inside the leash, now the filler is just the filler
        Schedule s = SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 40, 400), cfg, BEDS);
        assertEquals(1, s.runnable().stream().filter(n -> n.catalogueName().equals("wool")).count());
        assertEquals(2, s.runnable().stream().filter(n -> n.catalogueName().equals("food")).count());
        assertEquals(1, s.runnable().stream().filter(n -> n.catalogueName().equals("flint")).count());
    }


    @Test
    public void bedPlanksAreCappedAndShrinkWithBedsHeld() {
        assertEquals(0, SmeltFiller.planksWanted(List.of(), new FakeFacts().give(Items.BED.white(), 8), cfg, BEDS));
        assertEquals(12, SmeltFiller.planksWanted(List.of(), new FakeFacts(), cfg, BEDS));
        assertEquals(3, SmeltFiller.planksWanted(List.of(), new FakeFacts().give(Items.BED.white(), 7), cfg, BEDS));
    }

    // (standing by a smoker is FurnacePlanTest now)
}
