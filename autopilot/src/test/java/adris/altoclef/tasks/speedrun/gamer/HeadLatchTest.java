package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HeadLatchTest {
    private static final KitNeed IRON = new KitNeed("iron_ingot", 39);
    private static final KitNeed PICK = new KitNeed("stone_pickaxe", 2);
    private static final KitNeed FOOD = new KitNeed(KitNeed.FOOD, 70);

    private FakeFacts f;
    private OverworldConfig cfg;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Before
    public void setUp() {
        f = new FakeFacts().give(Items.STONE_PICKAXE, 1);
        cfg = new OverworldConfig();
        cfg.netherSpareShields = 0;
        cfg.netherSpareSwords = 0;
    }

    // the running need is in the plan (or only just left it), the clock KitRunner keeps is not running
    private static KitNeed pick(KitNeed running, long since, long now, List<KitNeed> needs, GamerFacts f) {
        return HeadLatch.pick(running, since, HeadLatch.NEVER, now, needs, f);
    }

    @Test
    public void theRunningNeedStaysHeadInsideTheDwell() {
        // the planner flipped the order under us, 2 s into the task
        assertEquals(IRON, pick(IRON, 0, 40, List.of(FOOD, IRON), f));
        assertEquals(IRON, pick(IRON, 0, HeadLatch.DWELL_TICKS - 1, List.of(PICK, IRON), f));
    }

    @Test
    public void thePlannerTakesOverWhenTheDwellIsUp() {
        assertEquals(FOOD, pick(IRON, 0, HeadLatch.DWELL_TICKS, List.of(FOOD, IRON), f));
        // a task that has run for minutes is never held, this is only for the first few seconds
        assertEquals(PICK, pick(IRON, 100, 6000, List.of(PICK, IRON), f));
    }

    @Test
    public void aNeedThatIsNoLongerOwedIsLetGo() {
        // satisfied (gone from the list, and the bag holds the count)
        assertEquals(FOOD, pick(IRON, 0, 10, List.of(FOOD), f.give(Items.IRON_INGOT, 39)));
        // or asked for differently, that is a different job
        assertEquals(FOOD, pick(IRON, 0, 10, List.of(FOOD, new KitNeed("iron_ingot", 36)), f));
    }

    private static final KitNeed LOG = new KitNeed("log", 29);
    private static final KitNeed COBBLE = new KitNeed("cobblestone", 19);

    @Test
    public void aRunningNeedGoneForOneTickKeepsTheHead() {
        // the log need dropped out for a tick with 3 of 29 logs in the bag: still the job
        FakeFacts bag = new FakeFacts().give(Items.OAK_LOG, 3);
        assertEquals(LOG, HeadLatch.pick(LOG, 0, 40, 40, List.of(COBBLE), bag));
        assertEquals(LOG, HeadLatch.pick(LOG, 0, 40, 41, List.of(COBBLE), bag));
        // long past its own dwell too: a task that has run for minutes still is not dropped on one tick
        assertEquals(LOG, HeadLatch.pick(LOG, 0, 6000, 6001, List.of(COBBLE), bag));
        // nobody started the clock yet: that is the first tick of it
        assertEquals(LOG, HeadLatch.pick(LOG, 0, HeadLatch.NEVER, 6001, List.of(COBBLE), bag));
    }

    @Test
    public void aRunningNeedGoneForTheDwellHandsOver() {
        FakeFacts bag = new FakeFacts().give(Items.OAK_LOG, 3);
        assertEquals(LOG, HeadLatch.pick(LOG, 0, 40, 40 + HeadLatch.DWELL_TICKS - 1, List.of(COBBLE), bag));
        assertEquals(COBBLE, HeadLatch.pick(LOG, 0, 40, 40 + HeadLatch.DWELL_TICKS, List.of(COBBLE), bag));
        // a clock that went way backwards does not hold for ever
        assertEquals(COBBLE, HeadLatch.pick(LOG, 0, 5000, 40, List.of(COBBLE), bag));
        // a tick or two back is lag, the hold stays
        assertEquals(LOG, HeadLatch.pick(LOG, 0, 500, 498, List.of(COBBLE), bag));
    }

    @Test
    public void aRunningNeedWeHoldHandsOverAtOnce() {
        FakeFacts bag = new FakeFacts().give(Items.OAK_LOG, 29);
        assertEquals(COBBLE, HeadLatch.pick(LOG, 0, 40, 40, List.of(COBBLE), bag));
        // any kind of log counts, same as the planner
        FakeFacts mixed = new FakeFacts().give(Items.OAK_LOG, 20).give(Items.BIRCH_LOG, 9);
        assertEquals(COBBLE, HeadLatch.pick(LOG, 0, HeadLatch.NEVER, 40, List.of(COBBLE), mixed));
        // cobble the same way
        assertEquals(LOG, HeadLatch.pick(COBBLE, 0, 40, 41, List.of(LOG), new FakeFacts().give(Items.COBBLESTONE, 19)));
        assertEquals(COBBLE, HeadLatch.pick(COBBLE, 0, 40, 41, List.of(LOG), new FakeFacts().give(Items.COBBLESTONE, 18)));
    }

    @Test
    public void onlyThePlainGathersAreHeldWhileGone() {
        // cook, armor and blocks have no item count to check, gone is done. food is a bag count and is held like the gathers
        assertEquals(FOOD, HeadLatch.pick(FOOD, 0, 40, 41, List.of(LOG), new FakeFacts()));
        assertEquals(LOG, HeadLatch.pick(FOOD, 0, 40, 40 + HeadLatch.DWELL_TICKS + 1, List.of(LOG), new FakeFacts()));
        assertEquals(LOG, HeadLatch.pick(new KitNeed(KitNeed.BUILD_BLOCKS, 32), 0, 40, 41, List.of(LOG), new FakeFacts()));
        // iron drops out once the batch in the furnace covers it, and the bag can't see that: holding it would go mining for more
        FakeFacts cooking = new FakeFacts().give(Items.STONE_PICKAXE, 1).cooking("iron_ingot", 39, 60);
        assertEquals(FOOD, HeadLatch.pick(IRON, 0, 40, 41, List.of(FOOD), cooking));
        // a replaced worn pick: the need counted the spent one, the bag does not
        FakeFacts worn = new FakeFacts().give(Items.STONE_PICKAXE, 1);
        worn.spent.put(Items.STONE_PICKAXE, 1);
        assertEquals(LOG, HeadLatch.pick(new KitNeed("stone_pickaxe", 2), 0, 40, 41, List.of(LOG), worn));
    }

    @Test
    public void aResizedNeedIsStillADifferentJob() {
        // the gone rule is for gone, a different count of the same thing hands over like it always did
        FakeFacts bag = new FakeFacts().give(Items.OAK_LOG, 3);
        assertEquals(COBBLE, HeadLatch.pick(LOG, 0, HeadLatch.NEVER, 10, List.of(COBBLE, new KitNeed("log", 30)), bag));
        assertEquals(new KitNeed("log", 30), HeadLatch.pick(LOG, 0, HeadLatch.NEVER, 10, List.of(new KitNeed("log", 30), COBBLE), bag));
    }

    @Test
    public void aMissingPickStillCutsInWhileTheRunningNeedIsGone() {
        assertEquals(PICK, HeadLatch.pick(LOG, 0, 40, 41, List.of(PICK), new FakeFacts().give(Items.OAK_LOG, 3)));
    }

    @Test
    public void theSameHeadOrNoRunningNeedIsJustTheHead() {
        assertEquals(IRON, pick(IRON, 0, 10, List.of(IRON, FOOD), f));
        assertEquals(FOOD, pick(null, 0, 10, List.of(FOOD, IRON), f));
        assertEquals(FOOD, pick(null, 0, 10, List.of(FOOD), f));
    }

    @Test
    public void aClockThatWentBackwardsDoesNotHoldForEver() {
        assertEquals(FOOD, pick(IRON, 5000, 10, List.of(FOOD, IRON), f));
    }

    @Test
    public void aClockAFewTicksBackIsLagAndKeepsTheDwell() {
        assertEquals(IRON, pick(IRON, 5000, 4998, List.of(FOOD, IRON), f));
    }

    @Test
    public void aMissingPickaxeCutsInAtOnceButAWornOneDoesNot() {
        FakeFacts none = new FakeFacts();
        assertEquals(PICK, pick(IRON, 0, 10, List.of(PICK, IRON), none));
        // worn out is still in the bag (MinecraftFacts keeps it in spent, not count)
        FakeFacts worn = new FakeFacts();
        worn.spent.put(Items.STONE_PICKAXE, 1);
        assertEquals(IRON, pick(IRON, 0, 10, List.of(PICK, IRON), worn));
        // a pick of any tier is a pick
        assertEquals(IRON, pick(IRON, 0, 10, List.of(PICK, IRON), new FakeFacts().give(Items.DIAMOND_PICKAXE, 1)));
        // and only a pickaxe need is that urgent, a missing sword waits like anything else
        assertEquals(IRON, pick(IRON, 0, 10, List.of(new KitNeed("stone_sword", 1), IRON), none));
    }

    // ---- what the planner puts first in IRON ----

    private static List<String> names(List<KitNeed> needs) {
        return needs.stream().map(KitNeed::catalogueName).toList();
    }

    // down the mine with one raw ore in the bag, everything of the starter kit but the pick, food in hand
    private FakeFacts mine() {
        FakeFacts m = new FakeFacts().give(Items.STONE_SWORD, 1).give(Items.FURNACE, 1).give(Items.LADDER, 3).give(Items.RAW_IRON, 1);
        m.foodUnits = 70;
        return m;
    }

    @Test
    public void aWornStonePickDoesNotPreemptTheIron() {
        FakeFacts m = mine();
        m.spent.put(Items.STONE_PICKAXE, 1);
        List<KitNeed> plan = KitPlanner.plan(m, cfg, 8);
        assertEquals("iron_ingot", plan.get(0).catalogueName());
        // still planned, one past the one we hold, but behind all the iron work
        assertEquals(new KitNeed("stone_pickaxe", 2), plan.stream().filter(n -> n.catalogueName().equals("stone_pickaxe")).findFirst().orElseThrow());
        assertTrue(names(plan).indexOf("stone_pickaxe") > names(plan).indexOf("iron_boots"));
    }

    @Test
    public void noPickAtAllIsTheOneThingThatComesFirst() {
        List<KitNeed> plan = KitPlanner.plan(mine(), cfg, 8);
        assertEquals(new KitNeed("stone_pickaxe", 1), plan.get(0));
    }

    @Test
    public void anIronPickMakesTheWornStoneOneMoot() {
        FakeFacts m = mine().give(Items.IRON_PICKAXE, 1);
        m.spent.put(Items.STONE_PICKAXE, 1);
        assertFalse(names(KitPlanner.plan(m, cfg, 8)).contains("stone_pickaxe"));
    }

    @Test
    public void theGatherKeepsTheEightyFivePercentRule() {
        // the spare pick is asked for right away in the gather, wherever in the kit order it falls
        FakeFacts g = new FakeFacts().give(Items.OAK_LOG, 15);
        g.spent.put(Items.STONE_PICKAXE, 1);
        assertEquals(new KitNeed("stone_pickaxe", 3), KitPlanner.gather(g, cfg, 8).stream()
                .filter(n -> n.catalogueName().equals("stone_pickaxe")).findFirst().orElseThrow());
    }

    @Test
    public void aReplacementIsNeverAReasonToGoChoppingMidIron() {
        // planks and cobble in the bag, a worn pick: no log trip in the plan, the craft uses what we hold
        FakeFacts m = mine().give(Items.OAK_PLANKS, 4).give(Items.COBBLESTONE, 20);
        m.spent.put(Items.STONE_PICKAXE, 1);
        assertFalse(names(KitPlanner.plan(m, cfg, 8)).contains("log"));
    }

    // the flip: three raw iron going into a furnace one at a time, the planner's early need dropped out of the list
    // mid move and the latch let go of it (it is not owed any more), so the head was the 39 need
    @Test
    public void theEarlyBatchStaysTheHeadWhileItsOreIsMidMove() {
        // the default kit owes all four early items, the first stage is the chestplate 8 and the shield 1
        KitNeed early = new KitNeed("iron_ingot", 9);
        FakeFacts moving = new FakeFacts().give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1).give(Items.FURNACE, 1)
                .give(Items.LADDER, 3).give(Items.RAW_IRON, 1);
        moving.foodUnits = 70;
        moving.earlyLoad = true;
        List<KitNeed> plan = KitPlanner.plan(moving, cfg, 8);
        assertEquals(early, plan.get(0));
        assertEquals(early, pick(early, 0, 20, plan, moving));
        // without the flight the plan has no early need and the latch can only hand over, which is what used to happen
        FakeFacts plain = new FakeFacts().give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1).give(Items.FURNACE, 1)
                .give(Items.LADDER, 3).give(Items.RAW_IRON, 1);
        plain.foodUnits = 70;
        List<KitNeed> old = KitPlanner.plan(plain, cfg, 8);
        assertEquals(new KitNeed("iron_ingot", 39), pick(early, 0, 20, old, plain));
    }

    @Test
    public void armorFirstOnlyMovesArmorOn() {
        KitNeed armor = new KitNeed(KitNeed.EQUIP_ARMOR, 1);
        KitNeed shield = new KitNeed("shield", 1);
        assertEquals(List.of(armor, IRON, shield), HeadLatch.armorFirst(List.of(IRON, shield, armor)));
        assertEquals(List.of(armor, IRON), HeadLatch.armorFirst(List.of(armor, IRON)));
        assertEquals(List.of(IRON, shield), HeadLatch.armorFirst(List.of(IRON, shield)));
    }

    @Test
    public void armorInTheBagWaitsForARunningCraftThenGoesOn() {
        KitNeed armor = new KitNeed(KitNeed.EQUIP_ARMOR, 1);
        KitNeed shield = new KitNeed("shield", 1);
        List<KitNeed> plan = List.of(armor, shield, IRON);
        // past the plain dwell the craft is still ours, it is half way through its grid
        assertEquals(shield, pick(shield, 0, HeadLatch.DWELL_TICKS + 50, plan, f));
        // a craft that has taken this long is stuck: armor on
        assertEquals(armor, pick(shield, 0, HeadLatch.ARMOR_WAIT_TICKS, plan, f));
        // the craft done (out of the plan) is armor on the same tick
        assertEquals(armor, pick(shield, 0, 20, List.of(armor, IRON), f));
        // anything but armor at the head is the plain dwell for a craft
        assertEquals(IRON, pick(shield, 0, HeadLatch.DWELL_TICKS, List.of(IRON, shield), f));
    }

    @Test
    public void theMiningNeedOnlyGetsThePlainDwellAgainstArmor() {
        KitNeed armor = new KitNeed(KitNeed.EQUIP_ARMOR, 1);
        List<KitNeed> plan = List.of(armor, IRON);
        assertEquals(IRON, pick(IRON, 0, 20, plan, f));
        assertEquals(armor, pick(IRON, 0, HeadLatch.DWELL_TICKS, plan, f));
        // the early load in flight finishes first, same as a craft
        KitNeed early = new KitNeed("iron_ingot", 14);
        FakeFacts loading = new FakeFacts().give(Items.STONE_PICKAXE, 1);
        loading.earlyLoad = true;
        assertEquals(early, pick(early, 0, HeadLatch.DWELL_TICKS + 50, List.of(armor, early, IRON), loading));
    }

    // 13:11:01 and 13:11:14: the cook started, the meat went into the smoker, and five seconds after the start the dwell ran out
    // with the iron at the head. a cook that has its station is not on the clock
    @Test
    public void aCookWithItsStationKeepsTheHeadPastTheDwell() {
        KitNeed cook = new KitNeed(KitNeed.COOK_SMOKER, CookGate.MIN_RAW);
        List<KitNeed> plan = List.of(IRON, cook);
        FakeFacts running = new FakeFacts().give(Items.STONE_PICKAXE, 1);
        running.cookStation = "smoker";
        assertEquals(cook, pick(cook, 0, 6000, plan, running));
        // not running (or never committed) is the plain dwell
        running.cookStation = null;
        assertEquals(IRON, pick(cook, 0, 6000, plan, running));
        // and a cook the plan dropped is let go whatever the pin says
        running.cookStation = "smoker";
        assertEquals(IRON, pick(cook, 0, 6000, List.of(IRON), running));
    }

    @Test
    public void aNeedThatJustLeftTheHeadDoesNotTakeItBackInsideTheDwellOrAfterIt() {
        HeadLatch.Flips flips = new HeadLatch.Flips();
        flips.left(KitNeed.FOOD, 1000);
        // iron has been running 10 s, food wants back in 15 s after it left: past the dwell, still held
        assertEquals(IRON, HeadLatch.pick(IRON, 1000, HeadLatch.NEVER, 1300, List.of(FOOD, IRON), f, flips));
        // 30 s on the same job and it is a plain change of plan
        assertEquals(FOOD, HeadLatch.pick(IRON, 1000, HeadLatch.NEVER, 1000 + HeadLatch.FLIP_TICKS, List.of(FOOD, IRON), f, flips));
    }

    @Test
    public void aNeedThatLeftLongAgoGetsTheOrdinaryDwell() {
        HeadLatch.Flips flips = new HeadLatch.Flips();
        flips.left(KitNeed.FOOD, 0);
        long now = HeadLatch.FLIP_TICKS + 500;
        assertEquals(FOOD, HeadLatch.pick(IRON, now - 300, HeadLatch.NEVER, now, List.of(FOOD, IRON), f, flips));
    }

    @Test
    public void aResizedHeadOfTheSameJobIsNotAFlip() {
        HeadLatch.Flips flips = new HeadLatch.Flips();
        flips.left(KitNeed.FOOD, 1000);
        KitNeed more = new KitNeed(KitNeed.FOOD, 90);
        assertEquals(more, HeadLatch.pick(FOOD, 1000, HeadLatch.NEVER, 1300, List.of(more), f, flips));
        // same name in the plan behind another head: the resize hands over to the head, it is not read as that head's return
        assertEquals(IRON, HeadLatch.pick(FOOD, 1000, HeadLatch.NEVER, 1300, List.of(IRON, more), f, flips));
    }

    @Test
    public void foodGoneFromThePlanIsHeldForTheDwellWhileTheBagIsShort() {
        assertTrue(HeadLatch.keepWhileGone(FOOD, HeadLatch.NEVER, 500, f));
        // enough in the bag (or cooking for us) and the planner dropping it is believed straight away
        f.foodUnits = 80;
        assertFalse(HeadLatch.keepWhileGone(FOOD, HeadLatch.NEVER, 500, f));
        assertEquals(IRON, pick(FOOD, 0, 41, List.of(IRON), f));
    }
}
