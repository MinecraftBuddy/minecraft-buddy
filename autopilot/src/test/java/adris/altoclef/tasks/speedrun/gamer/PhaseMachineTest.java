package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.trace.Trace;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.Test;

// whole scripted runs through the engine's brain with fake handlers and fake facts: no game, no Bootstrap
public class PhaseMachineTest {
    private static class TestHost implements PhaseMachine.Host {
        final RunState state = new RunState();
        final GamerConfig cfg = new GamerConfig();
        final FakeFacts facts = new FakeFacts();
        final List<String> said = new ArrayList<>();
        int saves;
        int resets;
        boolean walkOnPortal;

        @Override
        public void onPhaseReset() {
            resets++;
        }

        @Override
        public GamerConfig cfg() {
            return cfg;
        }

        @Override
        public RunState state() {
            return state;
        }

        @Override
        public GamerFacts facts() {
            return facts;
        }

        @Override
        public void say(String line) {
            said.add(line);
        }

        @Override
        public void save() {
            saves++;
        }

        @Override
        public void walkOnEndPortal(boolean on) {
            walkOnPortal = on;
        }
    }

    private TestHost host;
    private List<FakeHandler> handlers;
    private PhaseMachine machine;

    @Before
    public void setUp() {
        host = new TestHost();
        handlers = FakeHandler.full();
        machine = new PhaseMachine(new ArrayList<PhaseHandler>(handlers), host);
    }

    private FakeHandler h(GamerPhase p) {
        return handlers.stream().filter(x -> x.phase == p).findFirst().orElseThrow();
    }

    private void start() {
        machine.begin(null);
    }

    // one engine tick, `seconds` of game time later, with no progress made
    private void tickAfter(double seconds) {
        host.facts.seconds(seconds);
        machine.tick(null);
    }

    @Test
    public void aStraightRunWalksEveryPhaseAndFinishes() {
        // each phase is done once its own item is in the bag, the way the real predicates look
        FakeHandler[] chain = handlers.toArray(new FakeHandler[0]);
        boolean[] done = new boolean[chain.length];
        for (int i = 0; i < chain.length; i++) {
            int index = i;
            chain[i].doneWhen(f -> done[index]);
        }
        start();
        List<GamerPhase> seen = new ArrayList<>();
        for (int i = 0; i < chain.length; i++) {
            machine.tick(null);
            assertEquals(chain[i].phase, host.state.phase);
            seen.add(host.state.phase);
            assertEquals(1, chain[i].ticks);
            done[i] = true;
            host.facts.seconds(30);
            host.facts.x += 50;
        }
        machine.tick(null);
        assertEquals(GamerPhase.DONE, host.state.phase);
        assertTrue(host.state.finished);
        assertTrue(machine.ended());
        assertEquals(11, seen.size());
        assertTrue(host.said.contains("Beat the game."));
        assertTrue(host.saves > 0);
        // an ended run does nothing
        assertNull(machine.tick(null));
        // every handler was entered once and left once
        for (FakeHandler fh : handlers) {
            assertEquals(fh.phase.name(), List.of("enter1", "exit"), fh.events);
        }
    }

    @Test
    public void everyGateInATickReadsTheSameFoodPlanAndTheNextTickBuildsANewOne() {
        // the plan reads the item tables (raw meat), the only test in here that does
        ItemFixture.boot();
        start();
        host.facts.foodUnits = 40;
        machine.tick(null);
        FoodPlan first = machine.food();
        assertEquals(40, first.held());
        // the bag moving under a handler mid tick does not move the plan: the gates must not disagree inside one tick
        host.facts.foodUnits = 90;
        assertSame(first, machine.food());
        assertEquals(40, machine.food().held());
        // the next tick reads the bag again
        machine.tick(null);
        assertNotSame(first, machine.food());
        assertEquals(90, machine.food().held());
        assertSame(machine.food(), machine.food());
    }

    @Test
    public void theTicksFoodPlanFollowsThePhaseItIsAskedIn() {
        ItemFixture.boot();
        start();
        // three raw porkchop, a smoker standing and coal: the kit's phases cook them (24), the rest eat them raw (9)
        host.facts.give(Items.PORKCHOP, 3).give(Items.COAL, 2);
        host.facts.smokerPlaced = true;
        host.facts.foodUnits = 24;
        host.state.phase = GamerPhase.IRON;
        FoodPlan inIron = machine.food();
        assertEquals(24, inIron.held());
        // a move inside the tick must not keep the old phase's plan
        host.state.phase = GamerPhase.END_PREP;
        assertEquals(9, machine.food().held());
        assertNotSame(inIron, machine.food());
        assertSame(machine.food(), machine.food());
    }

    @Test
    public void aBudgetOverrunRetriesThenStartsOverThenGetsStuck() {
        start();
        // gather budget is 8 minutes. stall off so only the budget speaks
        h(GamerPhase.GATHER).stall = 0;
        tickAfter(8 * 60 + 1);
        assertEquals(GamerPhase.GATHER, host.state.phase);
        assertEquals(2, host.state.attemptsOf(GamerPhase.GATHER));
        assertEquals(List.of("enter1", "timeout1", "exit", "enter2"), h(GamerPhase.GATHER).events);
        assertFalse(machine.ended());
        // the retry has its own fresh budget
        tickAfter(8 * 60 - 1);
        assertFalse(machine.ended());
        // out of retries on the clock: back in at attempt 1 with a fresh budget, nobody gave up
        tickAfter(2);
        assertStartedOver(GamerPhase.GATHER, "took longer than 8 minutes");
        assertEquals(List.of("enter1", "timeout1", "exit", "enter2", "timeout2", "exit", "enter1"), h(GamerPhase.GATHER).events);
        // the same road again, and this time it really is the end
        tickAfter(8 * 60 + 1);
        assertEquals(2, host.state.attemptsOf(GamerPhase.GATHER));
        assertFalse(machine.ended());
        tickAfter(8 * 60 + 1);
        assertTrue(machine.ended());
        assertTrue(host.state.stuck);
        assertTrue(host.state.stuckReason, host.state.stuckReason.contains("8 minutes"));
        // the phase stays where it was, so #gamer resumes there
        assertEquals(GamerPhase.GATHER, host.state.phase);
        assertTrue(host.said.get(host.said.size() - 1).contains("Gave up at"));
        assertTrue(host.said.get(host.said.size() - 1).contains("State saved, type #gamer to pick it back up."));
        assertTrue(host.said.get(host.said.size() - 1).contains("getting started"));
        assertNull(machine.tick(null));
    }

    // what a restart has to leave behind, whichever clock ran out
    private void assertStartedOver(GamerPhase phase, String reason) {
        assertFalse(machine.ended());
        assertFalse(host.state.stuck);
        assertTrue(host.state.restartedThisPhase);
        assertEquals(phase, host.state.phase);
        assertEquals(1, host.state.attemptsOf(phase));
        assertEquals(0, machine.secondsInPhase(), 1e-9);
        assertEquals(0, machine.watchdog().secondsSinceProgress(machine.now()), 1e-9);
        assertEquals(host.facts.gameTime, host.state.phaseEnteredGameTime);
        String line = host.said.get(host.said.size() - 1);
        assertTrue(line, line.contains("starting this part over"));
        assertTrue(line, line.contains(reason));
        // the harness reads these three as the end of a game (or a resume)
        assertFalse(line, line.contains("Gave up at"));
        assertFalse(line, line.contains("Resuming"));
        assertFalse(line, line.contains("spawned over a lava lake"));
    }

    @Test
    public void aStalledPhaseRetriesThenStartsOverThenGetsStuck() {
        start();
        tickAfter(121);
        assertEquals(2, host.state.attemptsOf(GamerPhase.GATHER));
        tickAfter(121);
        assertStartedOver(GamerPhase.GATHER, "no progress for 121 seconds");
        tickAfter(121);
        assertFalse(machine.ended());
        tickAfter(121);
        assertTrue(host.state.stuck);
        assertTrue(host.state.stuckReason, host.state.stuckReason.contains("no progress"));
        assertTrue(host.said.get(host.said.size() - 1).contains("Gave up at"));
    }

    @Test
    public void aHandlerThatSaysStuckOnTheClockStartsOverToo() {
        // the portal and dragon shape: no retry left in the handler's own opinion, from the first timeout
        h(GamerPhase.GATHER).onTimeout = Timeout.STUCK;
        start();
        tickAfter(121);
        assertStartedOver(GamerPhase.GATHER, "no progress");
        tickAfter(121);
        assertTrue(host.state.stuck);
    }

    @Test
    public void aDeathCapOnTheClockStopsWithNoRestart() {
        h(GamerPhase.DRAGON).onTimeout = Timeout.STUCK_NO_RESTART;
        host.state.phase = GamerPhase.DRAGON;
        start();
        tickAfter(121);
        assertTrue(host.state.stuck);
        assertFalse(host.state.restartedThisPhase);
        assertTrue(host.said.stream().noneMatch(s -> s.contains("starting this part over")));
    }

    @Test
    public void aFailWithNoRetriesLeftStopsWithNoRestart() {
        start();
        machine.fail("first");
        machine.tick(null);
        machine.fail("the dragon killed us");
        machine.tick(null);
        assertTrue(host.state.stuck);
        assertFalse(host.state.restartedThisPhase);
        assertEquals("the dragon killed us", host.state.stuckReason);
        assertEquals(1, host.said.stream().filter(s -> s.startsWith("Gave up at")).count());
        assertTrue(host.said.stream().noneMatch(s -> s.contains("starting this part over")));
    }

    @Test
    public void aThrowingOnTimeoutOnTheClockStopsWithNoRestart() {
        FakeHandler gather = new FakeHandler(GamerPhase.GATHER) {
            @Override
            public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
                throw new IllegalArgumentException("bad handler");
            }
        };
        List<PhaseHandler> hs = new ArrayList<>(handlers);
        hs.set(0, gather);
        machine = new PhaseMachine(hs, host);
        machine.begin(null);
        host.facts.seconds(121);
        machine.tick(null);
        assertTrue(host.state.stuck);
        assertFalse(host.state.restartedThisPhase);
        assertTrue(host.said.stream().noneMatch(s -> s.contains("starting this part over")));
    }

    @Test
    public void aNewPhaseGetsItsOwnRestart() {
        start();
        tickAfter(121);
        tickAfter(121);
        assertTrue(host.state.restartedThisPhase);
        h(GamerPhase.GATHER).alwaysDone();
        machine.tick(null);
        assertEquals(GamerPhase.IRON, host.state.phase);
        assertFalse(host.state.restartedThisPhase);
        tickAfter(121);
        tickAfter(121);
        assertStartedOver(GamerPhase.IRON, "no progress");
    }

    @Test
    public void aRelogDoesNotBuyAnotherRestart() {
        host.facts.gameTime = 20 * 10000;
        host.state.phase = GamerPhase.IRON;
        host.state.phaseEnteredGameTime = host.facts.gameTime - 60 * 20;
        host.state.phaseAttempts.put(GamerPhase.IRON.name(), 2);
        host.state.restartedThisPhase = true;
        machine.begin(null, true);
        assertTrue(host.state.restartedThisPhase);
        tickAfter(121);
        assertTrue(host.state.stuck);
    }

    @Test
    public void aRestartKeepsTheDeathsAndTheRegressCounts() {
        start();
        host.state.deathsThisPhase = 5;
        host.state.regressCounts.put("LOCATE>NETHER", 2);
        tickAfter(121);
        tickAfter(121);
        assertStartedOver(GamerPhase.GATHER, "no progress");
        assertEquals(5, host.state.deathsThisPhase);
        assertEquals(Integer.valueOf(2), host.state.regressCounts.get("LOCATE>NETHER"));
    }

    @Test
    public void aRestartDumpsTheTraceAndSaves() {
        start();
        tickAfter(121);
        int saves = host.saves;
        List<String> dumped = new ArrayList<>();
        Trace.setSink(dumped::add);
        try {
            tickAfter(121);
        } finally {
            Trace.setSink(null);
        }
        assertTrue(dumped.get(0), dumped.get(0).startsWith("TRACE DUMP (restart: no progress for 121 seconds) build "));
        assertTrue(dumped.get(dumped.size() - 1).startsWith("END TRACE DUMP"));
        assertTrue(host.saves > saves);
    }

    @Test
    public void aRestartDropsTheOldChildBeforeTheEnter() {
        List<String> order = new ArrayList<>();
        TestHost h2 = new TestHost() {
            @Override
            public void onPhaseReset() {
                order.add("reset");
            }
        };
        List<FakeHandler> hs = FakeHandler.full();
        hs.forEach(fh -> fh.shared = order);
        PhaseMachine m = new PhaseMachine(new ArrayList<PhaseHandler>(hs), h2);
        m.begin(null);
        h2.facts.seconds(121);
        m.tick(null);
        order.clear();
        h2.facts.seconds(121);
        m.tick(null);
        assertEquals(List.of("GATHER:timeout2", "GATHER:exit", "reset", "GATHER:enter1"), order);
    }

    @Test
    public void aHandlerCanChooseToSkipItsPhase() {
        host.state.phase = GamerPhase.IRON;
        h(GamerPhase.IRON).onTimeout = Timeout.SKIP;
        h(GamerPhase.IRON).stall = 0;
        start();
        tickAfter(22 * 60 + 1);
        assertEquals(GamerPhase.PORTAL, host.state.phase);
        assertEquals(1, machine.attempt());
        assertEquals(List.of("enter1", "timeout1", "exit"), h(GamerPhase.IRON).events);
        assertEquals(List.of("enter1"), h(GamerPhase.PORTAL).events);
        assertFalse(machine.ended());
    }

    @Test
    public void skippingTheDragonIsStuckInstead() {
        host.state.phase = GamerPhase.DRAGON;
        h(GamerPhase.DRAGON).onTimeout = Timeout.SKIP;
        h(GamerPhase.DRAGON).stall = 0;
        start();
        tickAfter(25 * 60 + 1);
        assertTrue(host.state.stuck);
        assertFalse(host.state.finished);
    }

    @Test
    public void aStalledPhaseTimesOutButProgressKeepsItAlive() {
        start();
        // 100 s of nothing, then the inventory changes: the stall timer starts over
        tickAfter(100);
        host.facts.give(Items.COBBLESTONE, 1);
        tickAfter(100);
        tickAfter(100);
        assertEquals(List.of("enter1"), h(GamerPhase.GATHER).events);
        // 125 s of nothing since the change
        tickAfter(25);
        assertTrue(h(GamerPhase.GATHER).events.contains("timeout1"));
    }

    @Test
    public void movingAboutSixBlocksCountsAsProgressButShufflingDoesNot() {
        start();
        tickAfter(100);
        host.facts.x += 2;
        tickAfter(100);
        host.facts.x += 2;
        tickAfter(100);
        // two blocks twice never beat six, so the stall fired somewhere in there
        assertTrue(h(GamerPhase.GATHER).events.contains("timeout1"));

        TestHost host2 = new TestHost();
        // long budget, this half is about the stall timer only
        host2.cfg.budgets.gather = 10000;
        List<FakeHandler> hs = FakeHandler.full();
        PhaseMachine m2 = new PhaseMachine(new ArrayList<PhaseHandler>(hs), host2);
        m2.begin(null);
        for (int i = 0; i < 10; i++) {
            host2.facts.seconds(100);
            host2.facts.x += 7;
            m2.tick(null);
        }
        assertEquals(List.of("enter1"), hs.get(0).events);
    }

    @Test
    public void walkingSixOutAndSixBackOnlyCountsTheWayOut() {
        start();
        tickAfter(100);
        host.facts.x += 7;
        tickAfter(100);
        host.facts.x -= 7;
        tickAfter(100);
        tickAfter(30);
        // the way out re-armed the timer, the way back did not: 130 s since the way out is over the 120 s stall limit
        assertTrue(h(GamerPhase.GATHER).events.contains("timeout1"));
    }

    @Test
    public void comingBackAroundAThreePointLoopDoesNotRearmTheStallTimer() {
        host.cfg.budgets.gather = 10000;
        start();
        tickAfter(100);
        host.facts.x += 7;
        tickAfter(100);
        host.facts.z += 7;
        tickAfter(100);
        // back to the first point, then it sits there past the stall limit
        host.facts.x -= 7;
        host.facts.z -= 7;
        tickAfter(100);
        tickAfter(100);
        assertTrue(h(GamerPhase.GATHER).events.contains("timeout1"));
    }

    @Test
    public void aRealMoveAwayAfterALoopStillCountsAsProgress() {
        host.cfg.budgets.gather = 10000;
        start();
        host.facts.x += 7;
        tickAfter(100);
        host.facts.x -= 7;
        tickAfter(100);
        // the way back did not count, this fresh spot does
        host.facts.x -= 7;
        tickAfter(100);
        host.facts.x -= 7;
        tickAfter(100);
        assertEquals(List.of("enter1"), h(GamerPhase.GATHER).events);
    }

    @Test
    public void changingDimensionCountsAsProgress() {
        start();
        tickAfter(100);
        host.facts.dimension = Dimension.NETHER;
        tickAfter(100);
        tickAfter(100);
        assertEquals(List.of("enter1"), h(GamerPhase.GATHER).events);
    }

    @Test
    public void aHandlerCanReportProgressAndFailure() {
        start();
        tickAfter(100);
        machine.progress("found a tree");
        tickAfter(100);
        assertEquals(List.of("enter1"), h(GamerPhase.GATHER).events);
        machine.fail("no trees anywhere");
        machine.tick(null);
        assertEquals(List.of("enter1", "timeout1", "exit", "enter2"), h(GamerPhase.GATHER).events);
        assertEquals(2, host.state.attemptsOf(GamerPhase.GATHER));
    }

    @Test
    public void aResumedRunWithEverythingSatisfiedSkipsAhead() {
        for (GamerPhase p : new GamerPhase[]{GamerPhase.GATHER, GamerPhase.IRON, GamerPhase.PORTAL, GamerPhase.NETHER}) {
            h(p).alwaysDone();
        }
        start();
        machine.tick(null);
        assertEquals(GamerPhase.EYES, host.state.phase);
        // the skipped phases were never entered
        assertEquals(List.of("enter1", "exit"), h(GamerPhase.GATHER).events);
        assertEquals(List.of("enter1"), h(GamerPhase.EYES).events);
        assertTrue(h(GamerPhase.IRON).events.isEmpty());
        assertEquals(1, h(GamerPhase.EYES).ticks);
    }

    @Test
    public void regressesLimitedToOncePerSixtySecondsAndCountsNetherRevisits() {
        host.state.phase = GamerPhase.LOCATE;
        h(GamerPhase.LOCATE).regress = Optional.of(GamerPhase.NETHER);
        h(GamerPhase.LOCATE).stall = 0;
        h(GamerPhase.NETHER).stall = 0;
        h(GamerPhase.EYES).stall = 0;
        h(GamerPhase.RETURN).stall = 0;
        start();
        machine.tick(null);
        assertEquals(GamerPhase.NETHER, host.state.phase);
        assertEquals(1, host.state.netherRevisits);
        // nether is satisfied again and the run flows back to LOCATE
        h(GamerPhase.NETHER).alwaysDone();
        h(GamerPhase.EYES).alwaysDone();
        h(GamerPhase.RETURN).alwaysDone();
        machine.tick(null);
        assertEquals(GamerPhase.LOCATE, host.state.phase);
        // LOCATE wants to regress again straight away: not within 60 s of the first time
        host.facts.seconds(10);
        machine.tick(null);
        assertEquals(GamerPhase.LOCATE, host.state.phase);
        assertEquals(1, host.state.netherRevisits);
        host.facts.seconds(60);
        machine.tick(null);
        assertEquals(GamerPhase.NETHER, host.state.phase);
        assertEquals(2, host.state.netherRevisits);
    }

    @Test
    public void theRodsGiveUpSurvivesARetryAndClearsOnAFreshNetherEntry() {
        host.state.phase = GamerPhase.NETHER;
        host.state.netherRodsGaveUp = true;
        FakeHandler nether = h(GamerPhase.NETHER);
        nether.tickThrows = new IllegalStateException("boom");
        start();
        machine.tick(null);
        nether.tickThrows = null;
        machine.tick(null);
        // the retry entered the handler again and kept the flag
        assertEquals(List.of("enter1", "timeout1", "exit", "enter2"), nether.events);
        assertTrue(host.state.netherRodsGaveUp);
        // coming back to the nether from a later phase is a fresh entry
        host.state.phase = GamerPhase.LOCATE;
        h(GamerPhase.LOCATE).regress = Optional.of(GamerPhase.NETHER);
        h(GamerPhase.LOCATE).stall = 0;
        machine.begin(null);
        host.facts.seconds(100);
        machine.tick(null);
        assertEquals(GamerPhase.NETHER, host.state.phase);
        assertFalse(host.state.netherRodsGaveUp);
    }

    @Test
    public void aThrowingHandlerIsAFailedAttemptNeverARethrow() {
        FakeHandler gather = h(GamerPhase.GATHER);
        gather.tickThrows = new IllegalStateException("boom");
        start();
        // first tick: the handler throws, nothing escapes
        machine.tick(null);
        // second tick: the failure is handled as a timeout, attempt 1 -> retry
        gather.tickThrows = null;
        machine.tick(null);
        assertEquals(List.of("enter1", "timeout1", "exit", "enter2"), gather.events);
        // and when it keeps throwing the run ends stuck instead of looping
        gather.tickThrows = new IllegalStateException("boom");
        for (int i = 0; i < 6 && !machine.ended(); i++) {
            machine.tick(null);
        }
        assertTrue(host.state.stuck);
        assertTrue(host.state.stuckReason, host.state.stuckReason.contains("error in the gather phase"));
    }

    @Test
    public void aThrowingOnTimeoutIsStuck() {
        FakeHandler gather = new FakeHandler(GamerPhase.GATHER) {
            @Override
            public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
                throw new IllegalArgumentException("bad handler");
            }
        };
        List<PhaseHandler> hs = new ArrayList<>(handlers);
        hs.set(0, gather);
        machine = new PhaseMachine(hs, host);
        machine.begin(null);
        machine.fail("whatever");
        machine.tick(null);
        assertTrue(host.state.stuck);
    }

    @Test
    public void startingAlreadyStuckOrFinishedEntersNothing() {
        host.state.finished = true;
        host.state.phase = GamerPhase.DONE;
        machine = new PhaseMachine(new ArrayList<PhaseHandler>(handlers), host);
        machine.begin(null);
        assertTrue(machine.ended());
        assertNull(machine.tick(null));
        for (FakeHandler fh : handlers) {
            assertTrue(fh.events.isEmpty());
        }
    }

    @Test
    public void theContextTheHandlersSeeIsTheRealThing() {
        start();
        host.facts.seconds(42);
        assertEquals(1, machine.attempt());
        assertEquals(42, machine.secondsInPhase(), 1e-6);
        machine.walkOnEndPortal(true);
        assertTrue(host.walkOnPortal);
        machine.log("hello");
        assertEquals(List.of("hello"), host.said);
        int before = host.saves;
        machine.save();
        assertEquals(before + 1, host.saves);
        assertEquals(host.cfg, machine.cfg());
        assertEquals(host.state, machine.state());
    }

    @Test
    public void aDeathRecoveryDoesNotEatTheBudget() {
        start();
        h(GamerPhase.GATHER).stall = 0;
        long enteredBefore = host.state.phaseEnteredGameTime;
        host.facts.seconds(7 * 60);
        // five minutes of walking back for our stuff, the machine's tick never runs
        for (int i = 0; i < 300; i++) {
            host.facts.seconds(1);
            machine.holdClocks();
        }
        host.facts.seconds(1);
        machine.tick(null);
        // gather has 8 minutes, 7 of them are ours. without the hold this is a retry
        assertEquals(1, machine.attempt());
        assertEquals(enteredBefore + 300 * 20, host.state.phaseEnteredGameTime);
        assertEquals(7 * 60 + 1, machine.secondsInPhase(), 1e-6);
        // and the budget still bites once the real time is up
        tickAfter(60 + 1);
        assertEquals(2, host.state.attemptsOf(GamerPhase.GATHER));
    }

    @Test
    public void aHandlerThatHoldsTheBudgetKeepsItButNotTheStallTimer() {
        start();
        FakeHandler g = h(GamerPhase.GATHER);
        g.holdsBudget = true;
        // 20 minutes of one second ticks walking, gather has 8: the budget never bites
        for (int i = 0; i < 1200; i++) {
            host.facts.seconds(1);
            machine.progress();
            machine.tick(null);
        }
        assertEquals(1, machine.attempt());
        // it stops asking and the clock runs again from where it was parked
        g.holdsBudget = false;
        tickAfter(1);
        assertEquals(1, machine.attempt());
        // the stall timer was never held
        g.stall = 120;
        tickAfter(121);
        assertEquals(2, host.state.attemptsOf(GamerPhase.GATHER));
    }

    @Test
    public void aBudgetHoldAndARecoveryHoldDoNotCreditTheSameStretchTwice() {
        start();
        h(GamerPhase.GATHER).holdsBudget = true;
        h(GamerPhase.GATHER).stall = 0;
        host.facts.seconds(1);
        machine.tick(null);
        long entered = host.state.phaseEnteredGameTime;
        for (int i = 0; i < 10; i++) {
            host.facts.seconds(1);
            machine.holdClocks();
        }
        host.facts.seconds(1);
        machine.tick(null);
        // 10 held seconds given back once, the tick after the recovery adds nothing for the same stretch
        assertEquals(entered + 10 * 20, host.state.phaseEnteredGameTime);
    }

    @Test
    public void aRecoveryThatLostTheWheelStopsGivingTimeBack() {
        start();
        long enteredBefore = host.state.phaseEnteredGameTime;
        for (int i = 0; i < 10; i++) {
            host.facts.seconds(1);
            machine.holdClocks();
        }
        machine.releaseHold();
        // mob defense had the wheel for 30 s, the recovery was not running
        host.facts.seconds(30);
        machine.tick(null);
        assertEquals(enteredBefore + 9 * 20, host.state.phaseEnteredGameTime);
        // and a fresh start never inherits an old hold
        machine.holdClocks();
        machine.begin(null, false);
        host.facts.seconds(5);
        machine.tick(null);
        assertEquals(host.facts.gameTime - 5 * 20, host.state.phaseEnteredGameTime);
    }

    @Test
    public void aDeathRecoveryDoesNotTripTheStallTimerEither() {
        start();
        host.facts.seconds(60);
        for (int i = 0; i < 300; i++) {
            host.facts.seconds(1);
            machine.holdClocks();
        }
        host.facts.seconds(1);
        machine.tick(null);
        assertEquals(1, machine.attempt());
        // the 60 s before the death still count: 61 more is the 121 that is a stall
        tickAfter(61);
        assertEquals(2, host.state.attemptsOf(GamerPhase.GATHER));
    }

    @Test
    public void excusedInterruptTimeIsNotProgress() {
        host.state.phase = GamerPhase.IRON;
        start();
        // mob defense takes the wheel for 4 of every 5 seconds, over and over, and nothing gets done in between
        for (int i = 0; i < 100; i++) {
            host.facts.seconds(5);
            machine.excuseStall(4);
            machine.tick(null);
        }
        // that is 100 s of our own idling, under the 120 s stall. the old reset on every return never got anywhere near it
        assertEquals(1, machine.attempt());
        for (int i = 0; i < 30; i++) {
            host.facts.seconds(5);
            machine.excuseStall(4);
            machine.tick(null);
        }
        assertEquals(2, host.state.attemptsOf(GamerPhase.IRON));
    }

    @Test
    public void aRelogKeepsTheClockOfTheAttemptItResumes() {
        host.facts.gameTime = 20 * 10000;
        host.state.phase = GamerPhase.NETHER;
        host.state.phaseEnteredGameTime = host.facts.gameTime - 35 * 60 * 20;
        h(GamerPhase.NETHER).stall = 0;
        machine.begin(null, true);
        assertEquals(35 * 60, machine.secondsInPhase(), 1e-6);
        assertEquals(10000 * 20 - 35 * 60 * 20, host.state.phaseEnteredGameTime);
        // nether has 40 minutes: five more is over, a fresh clock would have had 40 more
        tickAfter(5 * 60 + 1);
        assertEquals(List.of("enter1", "timeout1", "exit", "enter2"), h(GamerPhase.NETHER).events);
    }

    @Test
    public void aRelogStillRestartsTheStallTimer() {
        host.facts.gameTime = 20 * 10000;
        host.state.phase = GamerPhase.IRON;
        host.state.phaseEnteredGameTime = host.facts.gameTime - 10 * 60 * 20;
        machine.begin(null, true);
        // iron has 22 minutes and the stall is 120 s from the moment we are back, not from the last save
        assertEquals(0, machine.watchdog().secondsSinceProgress(machine.now()), 1e-9);
        tickAfter(100);
        assertEquals(1, machine.attempt());
    }

    @Test
    public void aStartThatIsNotAResumeGetsFreshClocks() {
        host.facts.gameTime = 20 * 10000;
        host.state.phase = GamerPhase.NETHER;
        host.state.phaseEnteredGameTime = host.facts.gameTime - 35 * 60 * 20;
        machine.begin(null, false);
        assertEquals(0, machine.secondsInPhase(), 1e-6);
        assertEquals(host.facts.gameTime, host.state.phaseEnteredGameTime);
    }

    @Test
    public void aResumeFromAnImpossiblyOldStartGetsFreshClocks() {
        // the server ran on for days while we were gone, 40 minutes of nether budget say nothing about the attempt
        host.facts.gameTime = 20 * 1_000_000;
        host.state.phase = GamerPhase.NETHER;
        host.state.phaseEnteredGameTime = 20 * 5000;
        machine.begin(null, true);
        assertEquals(0, machine.secondsInPhase(), 1e-6);
        // a start that was never set is the same story
        TestHost other = new TestHost();
        other.facts.gameTime = 20 * 3000;
        other.state.phase = GamerPhase.IRON;
        PhaseMachine m = new PhaseMachine(new ArrayList<PhaseHandler>(FakeHandler.full()), other);
        m.begin(null, true);
        assertEquals(0, m.secondsInPhase(), 1e-6);
        assertEquals(other.facts.gameTime, other.state.phaseEnteredGameTime);
    }

    @Test
    public void aSavedStartSlightlyAheadOfTheWorldClockIsClamped() {
        host.facts.gameTime = 20 * 1000;
        host.state.phase = GamerPhase.IRON;
        host.state.phaseEnteredGameTime = host.facts.gameTime + 100;
        machine.begin(null, true);
        assertEquals(0, machine.secondsInPhase(), 1e-6);
        assertEquals(host.facts.gameTime, host.state.phaseEnteredGameTime);
    }

    @Test
    public void exitCurrentIsWhatAStopCalls() {
        start();
        machine.exitCurrent(null);
        assertEquals(List.of("enter1", "exit"), h(GamerPhase.GATHER).events);
    }

    @Test
    public void aStuckRunDoesNotExitItsHandlerAgainOnTheRealStop() {
        host.state.phase = GamerPhase.IRON;
        start();
        machine.stuck("out of ideas");
        assertTrue(host.state.stuck);
        assertEquals(List.of("enter1", "exit"), h(GamerPhase.IRON).events);
        // the chain then stops the task for real, which asks the machine to exit the current phase again
        machine.exitCurrent(null);
        machine.exitCurrent(null);
        assertEquals(List.of("enter1", "exit"), h(GamerPhase.IRON).events);
    }

    @Test
    public void aStuckRunDumpsTheTrace() {
        List<String> dumped = new ArrayList<>();
        Trace.setSink(dumped::add);
        try {
            host.state.phase = GamerPhase.IRON;
            start();
            machine.stuck("out of ideas");
        } finally {
            Trace.setSink(null);
        }
        assertTrue(dumped.get(0), dumped.get(0).startsWith("TRACE DUMP (stuck: out of ideas) build "));
        assertTrue(dumped.get(dumped.size() - 1).startsWith("END TRACE DUMP"));
    }

    @Test
    public void aStuckRunOnlyDumpsOnce() {
        host.state.phase = GamerPhase.IRON;
        start();
        machine.stuck("first");
        List<String> dumped = new ArrayList<>();
        Trace.setSink(dumped::add);
        try {
            // already over, a second stuck is ignored and must not dump again
            machine.stuck("second");
        } finally {
            Trace.setSink(null);
        }
        assertTrue(dumped.isEmpty());
    }

    @Test
    public void aFinishedRunDoesNotExitTwiceEither() {
        host.state.phase = GamerPhase.DRAGON;
        start();
        machine.finish(false);
        machine.exitCurrent(null);
        assertEquals(List.of("enter1", "exit"), h(GamerPhase.DRAGON).events);
    }

    @Test
    public void aRetryStillPairsExitAndEnter() {
        start();
        machine.fail("first");
        machine.tick(null);
        machine.fail("second");
        machine.tick(null);
        assertTrue(host.state.stuck);
        machine.exitCurrent(null);
        assertEquals(List.of("enter1", "timeout1", "exit", "enter2", "timeout2", "exit"), h(GamerPhase.GATHER).events);
    }

    @Test
    public void theSameRegressThreeTimesIsAPingPongNotARecovery() {
        host.state.phase = GamerPhase.LOCATE;
        FakeHandler locate = h(GamerPhase.LOCATE);
        locate.regress = Optional.of(GamerPhase.NETHER);
        for (GamerPhase p : new GamerPhase[]{GamerPhase.LOCATE, GamerPhase.NETHER, GamerPhase.EYES, GamerPhase.RETURN}) {
            h(p).stall = 0;
        }
        start();
        // NETHER, EYES and RETURN are satisfied at once, so every regress flows straight back to LOCATE
        h(GamerPhase.NETHER).alwaysDone();
        h(GamerPhase.EYES).alwaysDone();
        h(GamerPhase.RETURN).alwaysDone();
        for (int round = 1; round <= PhaseMachine.MAX_REGRESS_PER_PAIR; round++) {
            host.facts.seconds(61);
            machine.tick(null);
            assertEquals("regress " + round, GamerPhase.NETHER, host.state.phase);
            assertEquals(Integer.valueOf(round), host.state.regressCounts.get("LOCATE>NETHER"));
            machine.tick(null);
            assertEquals(GamerPhase.LOCATE, host.state.phase);
        }
        host.facts.seconds(61);
        machine.tick(null);
        assertTrue(host.state.stuck);
        assertTrue(host.state.stuckReason, host.state.stuckReason.contains("phase ping pong"));
        // the phase stays where it was, #gamer resumes there
        assertEquals(GamerPhase.LOCATE, host.state.phase);
    }

    @Test
    public void differentRegressPairsCountSeparately() {
        host.state.regressCounts.put("LOCATE>NETHER", PhaseMachine.MAX_REGRESS_PER_PAIR);
        host.state.phase = GamerPhase.OPEN;
        h(GamerPhase.OPEN).regress = Optional.of(GamerPhase.NETHER);
        h(GamerPhase.OPEN).stall = 0;
        start();
        machine.tick(null);
        assertEquals(GamerPhase.NETHER, host.state.phase);
        assertFalse(host.state.stuck);
    }

    @Test
    public void anExceptionFromTheChildTaskIsAFailedAttemptToo() {
        start();
        machine.failFromChild(new IllegalStateException("child blew up"));
        machine.tick(null);
        assertEquals(List.of("enter1", "timeout1", "exit", "enter2"), h(GamerPhase.GATHER).events);
        machine.failFromChild(new IllegalStateException("again"));
        machine.tick(null);
        assertTrue(host.state.stuck);
        assertTrue(host.state.stuckReason, host.state.stuckReason.contains("error in the gather phase"));
    }

    // a child that recursed itself to death takes the same road as one that threw
    @Test
    public void aStackOverflowFromTheChildTaskIsAFailedAttemptToo() {
        start();
        machine.failFromChild(new StackOverflowError());
        machine.tick(null);
        assertEquals(List.of("enter1", "timeout1", "exit", "enter2"), h(GamerPhase.GATHER).events);
        machine.failFromChild(new StackOverflowError());
        machine.tick(null);
        assertTrue(host.state.stuck);
        assertTrue(host.state.stuckReason, host.state.stuckReason.contains("(StackOverflowError)"));
    }

    // nobody to blame it on: the very same error goes back up, not wrapped in something else
    @Test
    public void anErrorWithNoPhaseGoesBackUpAsIs() {
        machine = new PhaseMachine(new ArrayList<PhaseHandler>(), host);
        StackOverflowError boom = new StackOverflowError();
        try {
            machine.failFromChild(boom);
        } catch (StackOverflowError e) {
            assertSame(boom, e);
            return;
        }
        throw new AssertionError("should have rethrown");
    }

    @Test
    public void theClockNeverGoesBackwards() {
        start();
        host.facts.seconds(100);
        double before = machine.now();
        // a new level can report game time 0 for a few ticks after a dimension change
        host.facts.gameTime = 0;
        assertEquals(before, machine.now(), 1e-9);
        assertEquals(before, machine.secondsInPhase(), 1e-9);
        host.facts.gameTime = 20 * 150;
        assertEquals(150, machine.now(), 1e-9);
    }

    @Test
    public void aStuckDragonRunTellsYouHowToStartTheGearCheckAgain() {
        host.state.phase = GamerPhase.DRAGON;
        start();
        machine.stuck("died 3 times");
        assertTrue(host.said.get(host.said.size() - 1), host.said.get(host.said.size() - 1).contains("#gamer phase end_prep"));
        TestHost other = new TestHost();
        other.state.phase = GamerPhase.IRON;
        PhaseMachine m = new PhaseMachine(new ArrayList<PhaseHandler>(FakeHandler.full()), other);
        m.begin(null);
        m.stuck("x");
        assertFalse(other.said.get(other.said.size() - 1).contains("end_prep"));
    }

    @Test
    public void aRetryAndEveryPhaseMoveDropTheOldChildBeforeTheNextEnter() {
        // the host's reset runs between the old handler's exit and the next one's enter
        List<String> order = new ArrayList<>();
        TestHost h2 = new TestHost() {
            @Override
            public void onPhaseReset() {
                order.add("reset");
            }
        };
        List<FakeHandler> hs = FakeHandler.full();
        hs.forEach(fh -> fh.shared = order);
        PhaseMachine m = new PhaseMachine(new ArrayList<PhaseHandler>(hs), h2);
        m.begin(null);
        // retry
        m.fail("stuck in a loop");
        m.tick(null);
        assertEquals(List.of("GATHER:enter1", "GATHER:timeout1", "GATHER:exit", "reset", "GATHER:enter2"), order);
        // advance
        order.clear();
        hs.get(0).alwaysDone();
        m.tick(null);
        assertEquals(List.of("GATHER:exit", "reset", "IRON:enter1"), order);
    }

    @Test
    public void startingAndStoppingDoNotCountAsAPhaseReset() {
        start();
        machine.exitCurrent(null);
        assertEquals(0, host.resets);
        machine.finish(false);
        assertEquals(0, host.resets);
    }

    @Test
    public void aFinishedStateOnlyFinishesOnce() {
        host.state.phase = GamerPhase.DRAGON;
        start();
        machine.finish(true);
        machine.finish(true);
        assertEquals(1, host.said.stream().filter(s -> s.startsWith("Beat the game")).count());
        assertEquals(List.of("enter1", "exit"), h(GamerPhase.DRAGON).events);
    }
}
