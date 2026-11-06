package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.DetourRules.Inputs;
import adris.altoclef.tasks.speedrun.gamer.DetourRules.Offset;
import adris.altoclef.tasks.speedrun.gamer.DetourRules.Step;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.WalkCost;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.Test;

// when a bit of coal is worth a detour, and when the detour is over. the world half (ResourceDetour) is only compile checked
public class CoalRulesTest {
    private static final OverworldConfig CFG = new OverworldConfig();

    // a world where we are mining in the overworld with a pick and nothing else is going on
    private static Inputs calm() {
        return new Inputs(true, true, 0, Integer.MAX_VALUE, false, false, false, false, false);
    }

    private static Inputs calm(int coal) {
        return new Inputs(true, true, coal, Integer.MAX_VALUE, false, false, false, false, false);
    }

    // answers that can change between ticks, and a count of how often each was asked
    private static final class World {
        boolean start = true;
        boolean keep = true;
        boolean drop;
        boolean strayed;
        final AtomicInteger asked = new AtomicInteger();

        DetourRules.Ore ore() {
            return new DetourRules.Ore(() -> {
                asked.incrementAndGet();
                return start;
            }, () -> keep, () -> drop, () -> strayed);
        }
    }

    private static final class Run {
        final DetourRules rules = new DetourRules();
        final World world = new World();
        final OverworldConfig cfg;
        long now = 1000;

        Run() {
            this(CFG);
        }

        Run(OverworldConfig cfg) {
            this.cfg = cfg;
        }

        Step tick(Inputs in) {
            return rules.tick(now, in, DetourSpec.COAL.limits(cfg), world.ore());
        }

        // this many ticks of the same inputs, the way the phase asks every tick. returns the last answer. a jump with no ticks
        // in it is a gap, and a gap ends a detour (see DetourRules.GAP_TICKS)
        Step run(long ticks, Inputs in) {
            Step last = null;
            for (long i = 0; i < ticks; i++) {
                last = next(in);
            }
            return last;
        }

        // one tick later
        Step next(Inputs in) {
            now++;
            return tick(in);
        }

        void advance(long ticks) {
            now += ticks;
        }

        Run started() {
            assertEquals(Step.START, tick(calm()));
            return this;
        }
    }

    // ---- starting

    @Test
    public void startsWhenOreIsInReachUnderTheCap() {
        Run run = new Run();
        assertEquals(Step.START, run.tick(calm(0)));
        assertTrue(run.rules.running());
        assertEquals(run.now, run.rules.startTick());
    }

    @Test
    public void justUnderTheCapStillStartsAndTheCapItselfDoesNot() {
        assertEquals(Step.START, new Run().tick(calm(CFG.coalSideCap - 1)));
        Run full = new Run();
        assertEquals(Step.IDLE, full.tick(calm(CFG.coalSideCap)));
        assertEquals(Step.IDLE, full.tick(calm(CFG.coalSideCap + 9)));
        assertFalse(full.rules.running());
    }

    @Test
    public void noOreInReachIsNoDetour() {
        Run run = new Run();
        run.world.start = false;
        assertEquals(Step.IDLE, run.tick(calm()));
        assertFalse(run.rules.running());
        // and the next tick it is there
        run.world.start = true;
        assertEquals(Step.START, run.next(calm()));
    }

    @Test
    public void theCapComesFromTheConfig() {
        OverworldConfig cfg = new OverworldConfig();
        cfg.coalSideCap = 5;
        DetourRules rules = new DetourRules();
        World world = new World();
        assertEquals(Step.IDLE, rules.tick(1, calm(5), DetourSpec.COAL.limits(cfg), world.ore()));
        assertEquals(Step.START, rules.tick(2, calm(4), DetourSpec.COAL.limits(cfg), world.ore()));
    }

    // ---- the never rules

    private static final List<UnaryOperator<Inputs>> NEVER = List.of(
            in -> new Inputs(false, in.tool(), in.held(), in.need(), in.cookStation(), in.furnaceDue(), in.loadInFlight(), in.foodLeads(), in.resourceHead()),
            in -> new Inputs(in.dimension(), false, in.held(), in.need(), in.cookStation(), in.furnaceDue(), in.loadInFlight(), in.foodLeads(), in.resourceHead()),
            in -> new Inputs(in.dimension(), in.tool(), in.held(), in.need(), true, in.furnaceDue(), in.loadInFlight(), in.foodLeads(), in.resourceHead()),
            in -> new Inputs(in.dimension(), in.tool(), in.held(), in.need(), in.cookStation(), true, in.loadInFlight(), in.foodLeads(), in.resourceHead()),
            in -> new Inputs(in.dimension(), in.tool(), in.held(), in.need(), in.cookStation(), in.furnaceDue(), true, in.foodLeads(), in.resourceHead()),
            in -> new Inputs(in.dimension(), in.tool(), in.held(), in.need(), in.cookStation(), in.furnaceDue(), in.loadInFlight(), true, in.resourceHead()),
            in -> new Inputs(in.dimension(), in.tool(), in.held(), in.need(), in.cookStation(), in.furnaceDue(), in.loadInFlight(), in.foodLeads(), true));
    private static final List<String> NEVER_NAMES = List.of("nether", "no pick", "cook has its station", "furnace due", "load in flight",
            "food leads", "coal is the head");

    @Test
    public void eachNeverRuleKeepsAnIdleBotOnItsTask() {
        for (int i = 0; i < NEVER.size(); i++) {
            Run run = new Run();
            Inputs bad = NEVER.get(i).apply(calm());
            assertTrue(NEVER_NAMES.get(i), DetourRules.blocked(bad));
            assertEquals(NEVER_NAMES.get(i), Step.IDLE, run.tick(bad));
            assertFalse(NEVER_NAMES.get(i), run.rules.running());
            // the ore question is a world scan, a bot that may not go does not pay for it
            assertEquals(NEVER_NAMES.get(i) + " asked the world", 0, run.world.asked.get());
        }
        assertFalse(DetourRules.blocked(calm()));
    }

    @Test
    public void eachNeverRuleEndsADetourThatWasGoing() {
        for (int i = 0; i < NEVER.size(); i++) {
            Run run = new Run().started();
            assertEquals(NEVER_NAMES.get(i), Step.KEEP, run.next(calm()));
            assertEquals(NEVER_NAMES.get(i), Step.BLOCKED, run.next(NEVER.get(i).apply(calm())));
            assertFalse(NEVER_NAMES.get(i), run.rules.running());
        }
    }

    @Test
    public void aNeverRuleThatClearsStillLeavesTheCooldown() {
        Run run = new Run().started();
        assertEquals(Step.BLOCKED, run.next(NEVER.get(2).apply(calm())));
        // the cook is done with its station and the ore is still right there, it is still too soon
        assertEquals(Step.IDLE, run.next(calm()));
        run.advance(DetourRules.COOLDOWN_TICKS);
        assertEquals(Step.START, run.next(calm()));
    }

    @Test
    public void everyNeverRuleIsInTheTable() {
        // a field added to Inputs without a rule here would be an unchecked way to go mining. held, need and mined are the three
        // that are not never rules, CoalNeedTest and GravelDetourTest have those
        assertEquals(Inputs.class.getRecordComponents().length - 3, NEVER.size());
    }

    // ---- hysteresis

    @Test
    public void aDetourThatStartedKeepsGoingWhileTheStartTestFailsAndTheKeepTestHolds() {
        Run run = new Run().started();
        int asked = run.world.asked.get();
        // we stepped back, or the line of sight is gone: the start test would say no, the vein is still around us
        run.world.start = false;
        for (int i = 0; i < 200; i++) {
            assertEquals(Step.KEEP, run.next(calm(3)));
        }
        assertTrue(run.rules.running());
        assertEquals("a detour that is going does not ask the start question", asked, run.world.asked.get());
    }

    @Test
    public void aFlickeringStartTestMakesOneDetourNotForty() {
        Run run = new Run();
        int starts = 0;
        int ends = 0;
        // the start question flickers, the vein is there throughout: one detour, not forty
        for (int i = 0; i < 80; i++) {
            run.world.start = i % 2 == 0;
            Step step = run.next(calm());
            if (step == Step.START) {
                starts++;
            }
            if (step == Step.DONE || step == Step.BLOCKED || step == Step.TIMEOUT) {
                ends++;
            }
        }
        assertEquals(1, starts);
        assertEquals(0, ends);
    }

    @Test
    public void theKeepReachIsLooserThanTheStartReach() {
        assertTrue(DetourRules.keepBudget(CFG.coalSideBudget) > CFG.coalSideBudget);
        // a cell we may stay for but would not have started on
        List<Offset> start = DetourRules.offsets(CFG.coalSideBudget);
        List<Offset> keep = DetourRules.offsets(DetourRules.keepBudget(CFG.coalSideBudget));
        assertTrue(keep.size() > start.size());
        assertTrue(keep.containsAll(start));
        assertFalse(start.contains(new Offset(15, 0, 0)));
        assertTrue(keep.contains(new Offset(15, 0, 0)));
    }

    // ---- done

    @Test
    public void whenTheClusterIsMinedItGivesTheDropAMomentAndThenIsDone() {
        Run run = new Run().started();
        assertEquals(Step.KEEP, run.next(calm(2)));
        run.world.keep = false;
        // the last ore just broke, its drop is not there yet
        assertEquals(Step.SETTLE, run.next(calm(2)));
        assertEquals(Step.SETTLE, run.next(calm(2)));
        assertTrue(run.rules.running());
        run.advance(DetourRules.DROP_GRACE_TICKS - 3);
        assertEquals(Step.SETTLE, run.next(calm(2)));
        run.advance(DetourRules.DROP_GRACE_TICKS);
        assertEquals(Step.DONE, run.next(calm(2)));
        assertFalse(run.rules.running());
    }

    @Test
    public void aDropThatShowsUpDuringTheGraceKeepsTheDetourUntilItIsPickedUp() {
        Run run = new Run().started();
        run.world.keep = false;
        assertEquals(Step.SETTLE, run.next(calm(2)));
        run.world.drop = true;
        // as long as the coal is on the floor it is not over, however long that takes (the budget is the other way out)
        for (int i = 0; i < 100; i++) {
            assertEquals(Step.KEEP, run.next(calm(2)));
        }
        // in the bag now, and the grace starts over from here
        run.world.drop = false;
        assertEquals(Step.SETTLE, run.next(calm(3)));
        run.advance(DetourRules.DROP_GRACE_TICKS);
        assertEquals(Step.DONE, run.next(calm(3)));
    }

    @Test
    public void aSecondVeinAppearingInTheGraceCancelsIt() {
        Run run = new Run().started();
        run.world.keep = false;
        assertEquals(Step.SETTLE, run.next(calm()));
        run.advance(DetourRules.DROP_GRACE_TICKS - 5);
        // the next piece of the vein loaded in, or the scan caught up
        run.world.keep = true;
        assertEquals(Step.KEEP, run.next(calm()));
        run.world.keep = false;
        assertEquals(Step.SETTLE, run.next(calm()));
        run.advance(DetourRules.DROP_GRACE_TICKS - 5);
        assertEquals("the grace runs from the last ore, not the first gap", Step.SETTLE, run.next(calm()));
    }

    @Test
    public void reachingTheCapMidDetourEndsItWithoutABan() {
        Run run = new Run().started();
        assertEquals(Step.KEEP, run.next(calm(CFG.coalSideCap - 1)));
        assertEquals(Step.DONE, run.next(calm(CFG.coalSideCap)));
        assertFalse(run.rules.running());
    }

    // ---- the budget

    @Test
    public void aDetourThatOutlastsItsBudgetIsTimedOut() {
        Run run = new Run().started();
        long budget = DetourSpec.COAL.limits(CFG).maxTicks();
        assertEquals(30 * 20, budget);
        assertEquals("on the last tick of the budget it is still allowed", Step.KEEP, run.run(budget, calm()));
        assertEquals(Step.TIMEOUT, run.next(calm()));
        assertFalse(run.rules.running());
    }

    @Test
    public void theBudgetComesFromTheConfig() {
        OverworldConfig cfg = new OverworldConfig();
        cfg.coalSideSeconds = 4;
        Run run = new Run(cfg).started();
        assertEquals(Step.KEEP, run.run(80, calm()));
        assertEquals(Step.TIMEOUT, run.next(calm()));
    }

    @Test
    public void aDetourWhoseOreIsGoneIsNeverTimedOutEvenWithTheClockUp() {
        Run run = new Run().started();
        assertEquals(Step.KEEP, run.run(DetourSpec.COAL.limits(CFG).maxTicks(), calm(4)));
        // the last ore broke right at the end of the budget: that is a finished detour, not one to ban
        run.world.keep = false;
        assertEquals(Step.SETTLE, run.next(calm(4)));
        assertEquals(Step.DONE, run.run(DetourRules.DROP_GRACE_TICKS, calm(4)));
    }

    @Test
    public void aTimedOutDetourCoolsDownBeforeTheNextOne() {
        Run run = new Run().started();
        run.run(DetourSpec.COAL.limits(CFG).maxTicks(), calm());
        assertEquals(Step.TIMEOUT, run.next(calm()));
        // the caller bans the cluster; whatever is left standing, the rules still wait their turn
        assertEquals(Step.IDLE, run.next(calm()));
        assertEquals(Step.IDLE, run.run(DetourRules.COOLDOWN_TICKS - 2, calm()));
        assertEquals(Step.START, run.next(calm()));
    }

    // ---- the leash

    @Test
    public void aDetourLedFarFromWhereItBeganWithOreStillStandingIsStrayed() {
        Run run = new Run().started();
        assertEquals(Step.KEEP, run.next(calm()));
        // the mining task gave up on a block and is walking to a vein across the map
        run.world.strayed = true;
        assertEquals(Step.STRAYED, run.next(calm()));
        assertFalse(run.rules.running());
        // and it cools down like any other end
        run.world.strayed = false;
        assertEquals(Step.IDLE, run.next(calm()));
        assertEquals(Step.START, run.run(DetourRules.COOLDOWN_TICKS - 1, calm()));
    }

    @Test
    public void strayingWithTheClusterGoneIsJustDone() {
        Run run = new Run().started();
        run.world.keep = false;
        run.world.strayed = true;
        // the pick up walk took us a bit far, but there is nothing left to ban
        assertEquals(Step.SETTLE, run.next(calm(3)));
        assertEquals(Step.DONE, run.run(DetourRules.DROP_GRACE_TICKS, calm(3)));
    }

    @Test
    public void strayingBeatsTheClockAndTheNeverRulesBeatStraying() {
        Run run = new Run().started();
        run.world.strayed = true;
        assertEquals(Step.BLOCKED, run.next(NEVER.get(3).apply(calm())));
        Run late = new Run().started();
        late.run(DetourSpec.COAL.limits(CFG).maxTicks(), calm());
        late.world.strayed = true;
        assertEquals(Step.STRAYED, late.next(calm()));
    }

    @Test
    public void aDropOnTheFloorWithNoOreLeftIsNotAReasonToCallItStrayed() {
        Run run = new Run().started();
        run.world.keep = false;
        run.world.drop = true;
        run.world.strayed = true;
        // the last coal rolled away from us, fetching it is the job, there is nothing to ban
        assertEquals(Step.KEEP, run.next(calm(3)));
    }

    @Test
    public void theLeashIsAsTheCrowFliesSoStandingUnderAKeepEdgeOreIsFine() {
        double budget = CFG.coalSideBudget;
        // an ore 2 across and 4 down is 18 by WalkCost, the very edge of the keep reach, and we stand 6 down to mine it from below
        assertTrue(WalkCost.within(2, -4, 0, DetourRules.keepBudget(budget)));
        assertFalse(DetourRules.strayed(2, -6, 0, budget));
        // the same ore at the flat edge, and a pickup walk a few blocks past it
        assertFalse(DetourRules.strayed(18 + 3, 0, 0, budget));
        assertFalse(DetourRules.strayed(0, 6, 18, budget));
        // 24 exactly is still inside, and anything past it is a walk to another vein
        assertFalse(DetourRules.strayed(24, 0, 0, budget));
        assertTrue(DetourRules.strayed(25, 0, 0, budget));
        assertFalse(DetourRules.strayed(20, 13, 0, budget));
        assertTrue(DetourRules.strayed(20, 14, 0, budget));
        assertTrue(DetourRules.strayed(-18, 0, 18, budget));
        assertFalse(DetourRules.strayed(0, 0, 0, budget));
    }

    @Test
    public void theLeashIsWiderThanTheKeepReachWhichIsWiderThanTheStartReach() {
        double start = CFG.coalSideBudget;
        assertTrue(DetourRules.leash(start) > DetourRules.keepBudget(start));
        assertTrue(DetourRules.keepBudget(start) > start);
        assertEquals(24.0, DetourRules.leash(start), 0);
    }

    // ---- cooldown, being preempted, a gap

    @Test
    public void afterADoneDetourTheNextOneWaitsForTheCooldown() {
        Run run = new Run().started();
        run.world.keep = false;
        run.next(calm(5));
        run.advance(DetourRules.DROP_GRACE_TICKS);
        assertEquals(Step.DONE, run.next(calm(5)));
        run.world.keep = true;
        for (int i = 0; i < DetourRules.COOLDOWN_TICKS - 1; i++) {
            assertEquals(Step.IDLE, run.next(calm(5)));
        }
        assertEquals(Step.START, run.next(calm(5)));
    }

    @Test
    public void aHigherJobTakingTheTickEndsTheDetourAndStartsTheCooldown() {
        Run run = new Run().started();
        run.rules.preempted(run.now + 1);
        assertFalse(run.rules.running());
        run.now += 2;
        assertEquals(Step.IDLE, run.tick(calm()));
        run.advance(DetourRules.COOLDOWN_TICKS);
        assertEquals(Step.START, run.tick(calm()));
    }

    @Test
    public void beingPreemptedWhileIdleChangesNothing() {
        Run run = new Run();
        run.rules.preempted(run.now);
        assertEquals("no detour, so no cooldown to serve", Step.START, run.tick(calm()));
    }

    @Test
    public void aDetourNobodyAskedAboutForAWhileWasNotOursToKeep() {
        Run run = new Run().started();
        run.advance(DetourRules.GAP_TICKS + 1);
        // a chain (a mob fight, a long meal) had the wheel for a couple of seconds: its time is not billed to us, the detour is just over
        assertEquals(Step.BLOCKED, run.tick(calm()));
        assertFalse(run.rules.running());
        // and a gap within the limit is nothing
        Run steady = new Run().started();
        steady.advance(DetourRules.GAP_TICKS);
        assertEquals(Step.KEEP, steady.tick(calm()));
    }

    @Test
    public void resetForgetsTheDetourAndTheCooldown() {
        Run run = new Run().started();
        run.rules.preempted(run.now);
        run.rules.reset();
        assertEquals("a new phase starts with a clean slate", Step.START, run.next(calm()));
        run.rules.reset();
        assertFalse(run.rules.running());
    }

    // ---- the chat line

    @Test
    public void theFirstDetourSaysIt() {
        assertTrue(new DetourRules().announce(5000, 10, 64, 10));
    }

    @Test
    public void aRestartOnTheSameClusterStaysQuiet() {
        DetourRules rules = new DetourRules();
        assertTrue(rules.announce(5000, 10, 64, 10));
        // a bed took the tick, we are back a few seconds later a block or two over
        assertFalse(rules.announce(5000 + 200, 10, 64, 10));
        assertFalse(rules.announce(5000 + 400, 12, 63, 11));
    }

    @Test
    public void aStartMoreThanEightBlocksFromTheLastOneSaysItAgain() {
        DetourRules rules = new DetourRules();
        assertTrue(rules.announce(5000, 0, 64, 0));
        // 8 exactly is still the same cluster, 9 is another
        assertFalse(rules.announce(5100, 8, 64, 0));
        assertTrue(rules.announce(5200, 9, 64, 0));
        // height counts as distance too, a vein two floors down is not the one we were on
        DetourRules tall = new DetourRules();
        assertTrue(tall.announce(5000, 0, 64, 0));
        assertFalse(tall.announce(5100, 0, 56, 0));
        assertTrue(tall.announce(5200, 0, 55, 0));
    }

    @Test
    public void aStartMoreThanAMinuteAfterTheLastOneSaysItAgainEvenOnTheSpot() {
        DetourRules rules = new DetourRules();
        assertTrue(rules.announce(5000, 0, 64, 0));
        assertFalse(rules.announce(5000 + DetourRules.ANNOUNCE_TICKS, 0, 64, 0));
        assertTrue(rules.announce(5000 + DetourRules.ANNOUNCE_TICKS + 1, 0, 64, 0));
        assertEquals(60 * 20, DetourRules.ANNOUNCE_TICKS);
    }

    @Test
    public void aQuietStartDoesNotMoveEitherReference() {
        DetourRules rules = new DetourRules();
        assertTrue(rules.announce(5000, 0, 64, 0));
        // creeping along a long vein in 5 block hops: the second hop is 10 from where we SAID it, so it says it again
        assertFalse(rules.announce(5100, 5, 64, 0));
        assertTrue(rules.announce(5200, 10, 64, 0));
        // and the clock runs from the last time it was said, not from the quiet starts in between
        DetourRules clock = new DetourRules();
        assertTrue(clock.announce(0, 0, 64, 0));
        assertFalse(clock.announce(1000, 0, 64, 0));
        assertTrue(clock.announce(1300, 0, 64, 0));
    }

    @Test
    public void aClockThatWentBackwardsSaysItAndAResetForgets() {
        DetourRules rules = new DetourRules();
        assertTrue(rules.announce(9000, 0, 64, 0));
        // a relog or a rewound world: do not trust a reference from the future
        assertTrue(rules.announce(100, 0, 64, 0));
        assertFalse(rules.announce(150, 0, 64, 0));
        rules.reset();
        assertTrue(rules.announce(160, 0, 64, 0));
    }

    @Test
    public void startingADetourDoesNotAnnounceByItself() {
        // the rules only answer the question, ResourceDetour asks it at START: a started detour leaves the memory alone
        Run run = new Run();
        assertEquals(Step.START, run.tick(calm()));
        assertTrue(run.rules.announce(run.now, 0, 64, 0));
    }

    // ---- the reach table

    @Test
    public void theReachIsTheWalkCostBudgetAndNothingBeyond() {
        List<Offset> table = DetourRules.offsets(12);
        assertTrue(table.contains(new Offset(12, 0, 0)));
        assertTrue(table.contains(new Offset(0, 0, -12)));
        assertFalse(table.contains(new Offset(13, 0, 0)));
        // three up is 12 by the 4 per block of height, four is not
        assertTrue(table.contains(new Offset(0, 3, 0)));
        assertTrue(table.contains(new Offset(0, -3, 0)));
        assertFalse(table.contains(new Offset(0, 4, 0)));
        assertTrue(table.contains(new Offset(4, 2, 0)));
        assertFalse(table.contains(new Offset(9, 2, 0)));
        // diagonals count by their length: 8, 8 is 11.3 away
        assertTrue(table.contains(new Offset(8, 0, 8)));
        assertFalse(table.contains(new Offset(9, 0, 9)));
        for (Offset o : table) {
            assertTrue(o + " is out of reach", WalkCost.within(o.dx(), o.dy(), o.dz(), 12));
        }
    }

    @Test
    public void theNearestWalkComesFirstSoTheScanCanStopAtTheFirstHit() {
        List<Offset> table = DetourRules.offsets(12);
        double last = -1;
        for (Offset o : table) {
            double cost = WalkCost.estimate(o.dx(), o.dy(), o.dz());
            assertTrue(o + " came after something farther", cost >= last);
            last = cost;
        }
        assertEquals(new Offset(0, 0, 0), table.get(0));
        // a block beside us beats a block at our feet's depth
        assertTrue(table.indexOf(new Offset(2, 0, 0)) < table.indexOf(new Offset(0, 1, 0)));
        // and the order is the same every time you ask
        assertEquals(table, DetourRules.offsets(12));
    }

    @Test
    public void absurdBudgetsStayBounded() {
        assertEquals(List.of(new Offset(0, 0, 0)), DetourRules.offsets(0));
        assertEquals(List.of(new Offset(0, 0, 0)), DetourRules.offsets(-5));
        List<Offset> huge = DetourRules.offsets(5000);
        assertTrue(huge.size() < 200_000);
        assertTrue(huge.contains(new Offset((int) DetourRules.MAX_BUDGET, 0, 0)));
        assertFalse(huge.contains(new Offset((int) DetourRules.MAX_BUDGET + 1, 0, 0)));
    }

    // ---- the config

    @Test
    public void theDefaultsAreWhatWasAgreed() {
        OverworldConfig o = new OverworldConfig();
        assertEquals(12.0, o.coalSideBudget, 0);
        assertEquals(24, o.coalSideCap);
        assertEquals(30.0, o.coalSideSeconds, 0);
        assertEquals(5 * 20, DetourRules.COOLDOWN_TICKS);
    }
}
