package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.SmeltFiller.Schedule;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuildBlocksTest {
    private static class StubTask extends Task {
        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected Task onTick(AltoClef mod) {
            return null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
        }

        @Override
        protected boolean isEqual(Task other) {
            return false;
        }

        @Override
        protected String toDebugString() {
            return "stub";
        }
    }

    private static final KitNeed BASE = new KitNeed(KitNeed.BUILD_BLOCKS, 32);
    // the portal gate fills to the entry stack, the smelt bases to portalBuildBlocks
    private static final KitNeed GATE = new KitNeed(KitNeed.BUILD_BLOCKS, 56);

    private StubContext ctx;
    private OverworldConfig cfg;
    private final List<String> said = new ArrayList<>();

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Before
    public void setUp() {
        ctx = new StubContext();
        cfg = ctx.cfg.overworld;
        cfg.netherSparePicks = 0;
        said.clear();
    }

    private BuildBlocks band() {
        return BuildBlocks.of(ctx.facts, ctx.state, said::add);
    }

    private List<KitNeed> gate() {
        ctx.facts.give(Items.BUCKET, 1).give(Items.WATER_BUCKET, 1).give(Items.FLINT_AND_STEEL, 1)
                .give(Items.CRAFTING_TABLE, 1);
        ctx.facts.foodUnits = cfg.netherEntryFoodUnits;
        return PortalPlanner.gate(ctx.facts, cfg, 10, FoodPlan.ofBeds(ctx.facts, cfg, 10), band());
    }

    private Schedule schedule() {
        return SmeltFiller.schedule(ctx.facts, cfg, 8, true, FoodPlan.ofBeds(ctx.facts, cfg, 8), WoodBudget.of(ctx.facts, cfg, 8), band());
    }

    @Test
    public void bandBelowLowAsksForTheTarget() {
        assertEquals(32, BuildBlocks.need(0, 8, 32));
        assertEquals(32, BuildBlocks.need(7, 8, 32));
    }

    @Test
    public void bandBetweenLowAndTargetAsksForNothing() {
        assertEquals(0, BuildBlocks.need(8, 8, 32));
        assertEquals(0, BuildBlocks.need(20, 8, 32));
        assertEquals(0, BuildBlocks.need(31, 8, 32));
    }

    @Test
    public void bandAtOrAboveTheTargetAsksForNothing() {
        assertEquals(0, BuildBlocks.need(32, 8, 32));
        assertEquals(0, BuildBlocks.need(64, 8, 32));
    }

    @Test
    public void aRefillUnderWayRunsToItsTargetAndLetsGoThere() {
        BuildBlocks.started(ctx.state, 32, false);
        // past low, short of the target: still owed
        ctx.facts.buildBlocks = 20;
        assertEquals(32, band().want(20, 8, 32));
        // a site with another target is not the one that started it
        assertEquals(0, band().want(20, 8, 64));
        ctx.facts.buildBlocks = 32;
        assertEquals(0, band().want(32, 8, 32));
        assertEquals("the latch is gone, not just unused", 0, ctx.state.buildRefill);
        // and a bag that dips back to 20 later is a band question again, not a refill
        ctx.facts.buildBlocks = 20;
        assertEquals(0, band().want(20, 8, 32));
    }

    @Test
    public void theFourthFreshRefillInOneEntryIsRefused() {
        for (int i = 1; i <= BuildBlocks.MAX_REFILLS; i++) {
            ctx.facts.buildBlocks = 2;
            assertEquals("refill " + i, 32, band().want(2, 8, 32));
            BuildBlocks.started(ctx.state, 32, false);
            ctx.facts.buildBlocks = 32;
            band();
        }
        ctx.facts.buildBlocks = 2;
        assertEquals(0, band().want(2, 8, 32));
        assertEquals(0, band().want(2, 8, 32));
        assertEquals("one line, however many times it is asked", 1, said.size());
        assertTrue(said.get(0), said.get(0).contains("3 times"));
    }

    @Test
    public void aRefillStillRunningWhenTheCapIsUsedUpFinishes() {
        ctx.state.buildRefills = BuildBlocks.MAX_REFILLS;
        ctx.state.buildRefill = 32;
        ctx.facts.buildBlocks = 12;
        assertEquals(32, band().want(12, 8, 32));
        assertTrue(said.isEmpty());
    }

    @Test
    public void aNewEntryStartsTheCountOver() {
        ctx.state.buildRefills = BuildBlocks.MAX_REFILLS;
        ctx.state.buildCapSaid = true;
        ctx.state.buildRefill = 32;
        BuildBlocks.newEntry(ctx.state);
        ctx.facts.buildBlocks = 2;
        assertEquals(32, band().want(2, 8, 32));
        assertEquals(0, ctx.state.buildRefills);
        assertEquals(0, ctx.state.buildRefill);
        assertFalse(ctx.state.buildCapSaid);
    }

    @Test
    public void portalGateIgnoresAHalfBagAndFinishesWhatItStarted() {
        ctx.facts.buildBlocks = cfg.netherEntryBlocksLow + 2;
        assertTrue("a bag over the low mark is a half bag, the old gate went mining", gate().isEmpty());
        ctx.facts.buildBlocks = cfg.netherEntryBlocksLow - 1;
        assertEquals(List.of(GATE), gate());
        // the runner picks it up, and from then on the gate keeps asking until the bag is full
        BuildBlocks.started(ctx.state, 56, false);
        ctx.facts.buildBlocks = 25;
        assertEquals(List.of(GATE), gate());
        ctx.facts.buildBlocks = 56;
        assertTrue(gate().isEmpty());
    }

    @Test
    public void ironBaseNeedStartsUnderEightAndNotAtTwenty() {
        ctx.facts.cooking("iron_ingot", 40, 400);
        ctx.facts.give(Items.STONE_PICKAXE, 1).give(Items.STONE_AXE, 1).give(Items.FURNACE, 1);
        ctx.facts.buildBlocks = 20;
        assertFalse("no base need at 20", schedule().runnable().contains(BASE));
        ctx.facts.buildBlocks = 7;
        assertTrue(schedule().runnable().contains(BASE));
    }

    @Test
    public void aFullBagGetsNoBuildNeedsAtAll() {
        ctx.facts.cooking("iron_ingot", 40, 400);
        ctx.facts.buildBlocks = 32;
        assertTrue(schedule().runnable().stream().noneMatch(n -> n.catalogueName().equals(KitNeed.BUILD_BLOCKS)));
    }

    // the loop from the play log, end to end through the planner and the runner: collect, spend nearly all of it, collect again
    @Test
    public void spendingTheBagNeverSendsTheGateRoundMoreThanThreeTimes() {
        KitRunner runner = new KitRunner((need, equip, food) -> new StubTask());
        int refills = 0;
        ctx.facts.buildBlocks = 0;
        for (int round = 0; round < 10; round++) {
            boolean on = false;
            // collect: 4 blocks a tick until the gate lets go
            for (int tick = 0; tick < 100; tick++) {
                List<KitNeed> gate = gate();
                if (gate.isEmpty()) {
                    break;
                }
                assertEquals(List.of(GATE), gate);
                if (!on) {
                    refills++;
                    on = true;
                }
                runner.run(ctx, gate);
                ctx.facts.buildBlocks += 4;
            }
            assertTrue("a full bag, or the cap", ctx.facts.buildBlocks >= 56 || refills >= BuildBlocks.MAX_REFILLS);
            // a walk that eats the bag down to 3
            ctx.facts.buildBlocks = 3;
        }
        assertEquals(BuildBlocks.MAX_REFILLS, refills);
        assertEquals(1, said.size());
    }

    @Test
    public void theStockUpGetsOneTripAndNeverEatsTheBasesThree() {
        ctx.facts.buildBlocks = 12;
        assertEquals(64, band().wantExtra(12, 16, 64));
        BuildBlocks.started(ctx.state, 64, true);
        ctx.facts.buildBlocks = 64;
        band();
        ctx.facts.buildBlocks = 12;
        assertEquals("one trip per entry", 0, band().wantExtra(12, 16, 64));
        assertTrue("and no line about it", said.isEmpty());
        // the base still has all three
        ctx.facts.buildBlocks = 2;
        assertEquals(32, band().want(2, 8, 32));
        assertEquals(0, ctx.state.buildRefills);
    }

    @Test
    public void aLeftOverStockUpLatchDoesNotMakeTheNextBaseTripFree() {
        BuildBlocks.started(ctx.state, 64, true);
        // went deep, the stock-up left the plan with the latch still up. a fresh base trip is still a trip
        BuildBlocks.started(ctx.state, 32, false);
        assertEquals(1, ctx.state.buildRefills);
        assertEquals(1, ctx.state.buildExtraRefills);
        // the same one over several ticks is not
        BuildBlocks.started(ctx.state, 32, false);
        assertEquals(1, ctx.state.buildRefills);
    }

    @Test
    public void thePoolMarkCoversTheSlabAndBothPads() {
        // 8 slab cells and the two top row pads are what the mold places, 4 on top
        assertEquals(8 + 2 + 12, cfg.portalBuildBlocksLow);
    }

    // the loop itself: the iron phase while a furnace cooks, the schedule and the runner, bag eaten down after every fill
    @Test
    public void ironScheduleRefillsAtMostThreeTimesAndTheStockUpOnce() {
        ctx.facts.cooking("iron_ingot", 40, 400);
        ctx.facts.give(Items.STONE_PICKAXE, 1).give(Items.STONE_AXE, 1).give(Items.FURNACE, 1);
        KitRunner runner = new KitRunner((need, equip, food) -> new StubTask());
        ctx.facts.buildBlocks = 0;
        int peak = 0;
        // bags the walk leaves behind: under the base mark, then in the stock-up band, then under the base mark again
        int[] leftovers = {3, 12, 3, 12, 3, 12, 3, 12};
        for (int round = 0; round < leftovers.length; round++) {
            for (int tick = 0; tick < 100; tick++) {
                List<KitNeed> builds = schedule().runnable().stream().filter(n -> n.catalogueName().equals(KitNeed.BUILD_BLOCKS)).toList();
                if (builds.isEmpty()) {
                    break;
                }
                runner.run(ctx, builds);
                ctx.facts.buildBlocks += 4;
                peak = Math.max(peak, ctx.facts.buildBlocks);
            }
            ctx.facts.buildBlocks = leftovers[round];
        }
        assertEquals(BuildBlocks.MAX_REFILLS, ctx.state.buildRefills);
        assertEquals(BuildBlocks.MAX_EXTRA_REFILLS, ctx.state.buildExtraRefills);
        assertTrue("the stock-up filled to 64 once: " + peak, peak >= 64);
        assertEquals("one line, from the base", 1, said.size());
    }

    @Test
    public void aFullFillFromNothingEndsAtTheBaseAndNoTripFollowsAPillar() {
        ctx.facts.cooking("iron_ingot", 40, 400);
        ctx.facts.buildBlocks = 0;
        KitRunner runner = new KitRunner((need, equip, food) -> new StubTask());
        for (int tick = 0; tick < 100; tick++) {
            List<KitNeed> builds = schedule().runnable().stream().filter(n -> n.catalogueName().equals(KitNeed.BUILD_BLOCKS)).toList();
            if (builds.isEmpty()) {
                break;
            }
            runner.run(ctx, builds);
            ctx.facts.buildBlocks += 4;
        }
        assertEquals(32, ctx.facts.buildBlocks);
        // one pillar later
        ctx.facts.buildBlocks = 31;
        assertTrue(schedule().runnable().stream().noneMatch(n -> n.catalogueName().equals(KitNeed.BUILD_BLOCKS)));
        assertEquals(1, ctx.state.buildRefills);
    }
}
