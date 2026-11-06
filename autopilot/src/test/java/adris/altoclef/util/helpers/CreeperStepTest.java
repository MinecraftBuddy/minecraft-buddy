package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CreeperStep.Event;
import adris.altoclef.util.helpers.CreeperStep.Seen;
import java.util.List;
import org.junit.Test;

// the creeper preempt: within 4 is a step, lit or not, and the hold always ends
public class CreeperStepTest {

    private static List<Seen> lit(int id, double distance) {
        return List.of(new Seen(id, true, distance, false));
    }

    // a creeper whose fuse is already going (lit or deflating)
    private static List<Seen> fused(int id, boolean lit, double distance, boolean fusing) {
        return List.of(new Seen(id, lit, distance, false, fusing));
    }

    private static List<Seen> fused(int id, double distance) {
        return fused(id, true, distance, true);
    }

    @Test
    public void litWithinFourSteps() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.START, step.step(0, lit(7, 3.5), false));
        assertTrue(step.active());
        assertEquals(7, step.creeperId());
        assertTrue(step.shouldLog());
        assertEquals(Event.START, new CreeperStep().step(0, lit(7, CreeperStep.TRIGGER), false));
    }

    @Test
    public void aSecondOneLitCloseTakesTheHoldFromAnUnlitOne() {
        // attempt 179: held an unlit one at 3.5 while another lit at 2.4, and no shield went up
        CreeperStep step = new CreeperStep();
        assertEquals(Event.START, step.step(0, List.of(new Seen(7, false, 3.5, false)), true));
        assertEquals(7, step.creeperId());
        Event e = step.step(1, List.of(new Seen(7, false, 3.5, false), new Seen(8, true, 2.4, false)), true);
        assertEquals(Event.CORNERED, e);
        assertEquals(8, step.creeperId());
        assertTrue(step.cornered());
    }

    @Test
    public void aFurtherLitOneLeavesALitHoldAlone() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.START, step.step(0, lit(7, 3.5), false));
        assertEquals(Event.NONE, step.step(1, List.of(new Seen(7, true, 3.5, false), new Seen(8, true, 5, false)), false));
        assertEquals(7, step.creeperId());
    }

    @Test
    public void litAtFiveIsNothing() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.NONE, step.step(0, lit(7, 5), false));
        assertFalse(step.active());
    }

    @Test
    public void anUnlitOneInsideFourSteps() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.START, step.step(0, List.of(new Seen(7, false, 3, false)), true));
        assertTrue(step.active());
        assertEquals(7, step.creeperId());
    }

    @Test
    public void anUnlitOneAtSixIsNothing() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.NONE, step.step(0, List.of(new Seen(7, false, 6, false)), true));
        assertFalse(step.active());
    }

    // lit first, it is the one with a clock on it
    @Test
    public void aLitOneBeatsACloserUnlitOne() {
        CreeperStep step = new CreeperStep();
        step.step(0, List.of(new Seen(1, false, 1, false), new Seen(2, true, 3.5, false)), false);
        assertEquals(2, step.creeperId());
    }

    // one rule to let go: unlit and past HOLD_RADIUS
    @Test
    public void anUnlitOneIsHeldUntilItIsPastFive() {
        CreeperStep step = new CreeperStep();
        step.step(0, List.of(new Seen(7, false, 3, false)), false);
        assertEquals(Event.NONE, step.step(5, List.of(new Seen(7, false, CreeperStep.HOLD_RADIUS, false, false)), false));
        assertTrue(step.active());
        assertEquals(Event.END_UNLIT, step.step(6, List.of(new Seen(7, false, CreeperStep.HOLD_RADIUS + 0.1, false, false)), false));
        assertFalse(step.active());
    }

    // the user's path walks back into one we let go of: the second step is the run's job, not another lap
    @Test
    public void anUnlitOneWeWalkBackIntoIsARun() {
        CreeperStep step = new CreeperStep();
        step.step(0, List.of(new Seen(7, false, 3, false)), false, Mode.NONE, true);
        assertFalse(step.escalate());
        assertEquals(Event.END_UNLIT, step.step(20, List.of(new Seen(7, false, 5.5, false, false)), false, Mode.NONE, true));
        assertEquals(Event.START, step.step(100, List.of(new Seen(7, false, 3.5, false)), false, Mode.NONE, true));
        assertTrue(step.escalate());
        // and not within the window of the end any more than REPEAT_WINDOW
        CreeperStep late = new CreeperStep();
        late.step(0, List.of(new Seen(7, false, 3, false)), false, Mode.NONE, true);
        late.step(20, List.of(new Seen(7, false, 5.5, false, false)), false, Mode.NONE, true);
        assertEquals(Event.START, late.step(21 + CreeperStep.REPEAT_WINDOW, List.of(new Seen(7, false, 3.5, false)), false, Mode.NONE, true));
        assertFalse(late.escalate());
    }

    // a run is already taking us away, an unlit one beside it is not worth stopping for
    @Test
    public void aRunIgnoresAnUnlitOne() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.NONE, step.step(0, List.of(new Seen(7, false, 3, false)), false, Mode.RUN, true));
    }

    @Test
    public void litAtFiveWithThePathWalkingIntoItSteps() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.START, step.step(0, List.of(new Seen(7, true, 5, true)), false));
    }

    @Test
    public void picksTheClosestLitOne() {
        CreeperStep step = new CreeperStep();
        step.step(0, List.of(new Seen(1, true, 3.9, false), new Seen(2, true, 2, false), new Seen(3, false, 1, false)), false);
        assertEquals(2, step.creeperId());
    }

    @Test
    public void holdsWhileLitEvenPastFour() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        for (int t = 1; t < CreeperStep.HOLD_CAP; t++) {
            assertEquals(Event.NONE, step.step(t, lit(7, 6), false));
            assertTrue(step.active());
        }
    }

    @Test
    public void explodedOrGoneEndsTheHold() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        assertEquals(Event.END_GONE, step.step(20, List.of(), false));
        assertFalse(step.active());
    }

    @Test
    public void unswellingEndsTheHold() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        assertEquals(Event.END_UNLIT, step.step(10, List.of(new Seen(7, false, 6, false)), false));
        assertFalse(step.active());
    }

    // a creeper that lost its line to us goes unlit with the swell still up, and relights the moment we walk back in
    @Test
    public void aDeflatingFuseCloseByKeepsTheHold() {
        CreeperStep step = new CreeperStep();
        step.step(0, fused(7, 3), false);
        for (int t = 1; t < CreeperStep.HOLD_CAP; t++) {
            assertEquals("tick " + t, Event.NONE, step.step(t, fused(7, false, CreeperStep.HOLD_RADIUS, true), false));
            assertTrue(step.active());
        }
    }

    @Test
    public void aDrainedFuseCloseByStillHoldsUntilWeAreOut() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        assertEquals(Event.NONE, step.step(10, List.of(new Seen(7, false, 3, false, false)), false));
        assertEquals(Event.END_UNLIT, step.step(11, List.of(new Seen(7, false, 5.5, false, false)), false));
        assertFalse(step.active());
    }

    @Test
    public void aDeflatingFuseFarAwayEndsTheHold() {
        CreeperStep step = new CreeperStep();
        step.step(0, fused(7, 3), false);
        assertEquals(Event.END_UNLIT, step.step(10, fused(7, false, CreeperStep.HOLD_RADIUS + 0.1, true), false));
    }

    @Test
    public void aFuseThatNeverDrainsStillHitsTheCap() {
        CreeperStep step = new CreeperStep();
        step.step(0, fused(7, 3), false);
        // lit for a while, then deflating and never draining: the cap ends the hold
        assertEquals(Event.NONE, step.step(10, fused(7, false, 2, true), false));
        assertEquals(Event.END_CAP, step.step(CreeperStep.HOLD_CAP, fused(7, false, 2, true), false));
        assertFalse(step.active());
    }

    @Test
    public void aHoldThatOnlyRanLongBecauseItRelitIsNotBlacklisted() {
        CreeperStep step = new CreeperStep();
        step.step(0, fused(7, 3), false);
        for (int t = 1; t < 20; t++) step.step(t, fused(7, 3), false);
        for (int t = 20; t < 35; t++) step.step(t, fused(7, false, 3, true), false);
        for (int t = 35; t < CreeperStep.HOLD_CAP; t++) step.step(t, fused(7, 3), false);
        // the cap lands, but it has only been lit again for fifteen ticks
        assertEquals(Event.END_CAP, step.step(CreeperStep.HOLD_CAP, fused(7, 3), false));
        // so its next relight beside us is stepped from again
        assertEquals(Event.START, step.step(CreeperStep.HOLD_CAP + 1, fused(7, 3), false));
    }

    @Test
    public void behindTheShieldALitCreeperIsHeldPastTheCap() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.CORNERED, step.step(0, lit(7, 3), true));
        for (int t = 1; t <= CreeperStep.HOLD_CAP + 20; t++) assertEquals(Event.NONE, step.step(t, lit(7, 3), true));
        assertTrue(step.active());
        // it blew or walked off: the hold ends
        assertEquals(Event.END_GONE, step.step(CreeperStep.HOLD_CAP + 21, List.of(), true));
    }

    @Test
    public void behindTheShieldACreeperThatNeverBlowsStillHitsTheLongerCap() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.CORNERED, step.step(0, lit(7, 3), true));
        assertEquals(Event.NONE, step.step(CreeperStep.HOLD_CAP_SHIELD - 1, lit(7, 3), true));
        assertEquals(Event.END_CAP, step.step(CreeperStep.HOLD_CAP_SHIELD, lit(7, 3), true));
    }

    @Test
    public void aCreeperThatSatLitThroughTheWholeCapIsNotHeldAgain() {
        CreeperStep step = new CreeperStep();
        step.step(0, fused(7, 3), false);
        assertEquals(Event.END_CAP, step.step(CreeperStep.HOLD_CAP, fused(7, 3), false));
        assertEquals(Event.NONE, step.step(CreeperStep.HOLD_CAP + 1, fused(7, 3), false));
    }

    @Test
    public void twoAndAHalfSecondsIsTheCapAndTheSameOneIsNotHeldAgain() {
        CreeperStep step = new CreeperStep();
        step.step(0, fused(7, 3), false);
        assertEquals(Event.NONE, step.step(CreeperStep.HOLD_CAP - 1, fused(7, 3), false));
        assertEquals(Event.END_CAP, step.step(CreeperStep.HOLD_CAP, fused(7, 3), false));
        assertEquals(Event.NONE, step.step(CreeperStep.HOLD_CAP + 1, fused(7, 3), false));
        assertFalse(step.active());
        assertEquals(50, CreeperStep.HOLD_CAP);
    }

    // ---- coming back

    @Test
    public void aCreeperThatRelitAfterTheStepIsARun() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.START, step.step(0, lit(7, 3), false, Mode.NONE, true));
        assertFalse(step.escalate());
        assertEquals(Event.END_UNLIT, step.step(30, List.of(new Seen(7, false, 7.5, false, false)), false, Mode.NONE, true));
        // it walked back in and lit again
        assertEquals(Event.START, step.step(120, lit(7, 2.8), false, Mode.NONE, true));
        assertTrue(step.escalate());
        // the hand-off is that one tick
        assertEquals(Event.NONE, step.step(121, lit(7, 3.5), false, Mode.NONE, true));
        assertFalse(step.escalate());
    }

    @Test
    public void aDefusedOneThatCameBackIsARunToo() {
        CreeperStep step = new CreeperStep();
        step.step(0, fused(7, 3), false, Mode.NONE, true);
        assertEquals(Event.END_GONE, step.step(20, List.of(), false, Mode.NONE, true));
        assertEquals(Event.START, step.step(200, fused(7, 3), false, Mode.NONE, true));
        assertTrue(step.escalate());
    }

    @Test
    public void withTheCommitmentOffARepeatIsJustTheStepAgain() {
        // nobody to hand a run to, so it is the plain step again
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false, Mode.NONE, false);
        step.step(30, List.of(new Seen(7, false, 7.5, false, false)), false, Mode.NONE, false);
        assertEquals(Event.START, step.step(120, lit(7, 2.8), false, Mode.NONE, false));
        assertFalse(step.escalate());
    }

    @Test
    public void midFightARepeatIsTheRunsToo() {
        // the fight walks us back to it every time, so the second go is the run's there as well
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false, Mode.NONE, true);
        step.step(30, List.of(new Seen(7, false, 7.5, false, false)), false, Mode.FIGHT, true);
        assertEquals(Event.START, step.step(120, lit(7, 2.8), false, Mode.FIGHT, true));
        assertTrue(step.escalate());
    }

    @Test
    public void boxedInMidFightARepeatStaysTheStep() {
        // a run from a box goes nowhere
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false, Mode.NONE, true);
        step.step(30, List.of(new Seen(7, false, 7.5, false, false)), false, Mode.FIGHT, true);
        assertEquals(Event.CORNERED, step.step(120, lit(7, 2.8), true, Mode.FIGHT, true, true));
        assertFalse(step.escalate());
    }

    @Test
    public void aCommitmentStillSteps() {
        assertEquals(Event.START, new CreeperStep().step(0, lit(7, 2.5), false, Mode.FIGHT, true));
        CreeperStep step = new CreeperStep();
        step.step(0, List.of(new Seen(7, true, 2.5, true)), false, Mode.RUN, true);
        assertTrue(step.active());
    }

    // a run that starts mid step holds the step to the stricter let-go rule
    @Test
    public void aStepThatMeetsARunFollowsTheRunsRules() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false, Mode.NONE, true);
        assertEquals(Event.NONE, step.step(10, lit(7, 6.5), false, Mode.NONE, true));
        assertEquals(Event.NONE, step.step(11, lit(7, 6.5), false, Mode.RUN, true));
        assertTrue(step.active());
        // and the run's rules from here: a deflating fuse is held through until it is drained and we are past 7
        assertEquals(Event.NONE, step.step(12, List.of(new Seen(7, false, 6.5, false, true)), false, Mode.RUN, true));
        assertEquals(Event.NONE, step.step(13, List.of(new Seen(7, false, CreeperStep.SAFE + 1, false, true)), false, Mode.RUN, true));
        assertEquals(Event.NONE, step.step(14, List.of(new Seen(7, false, CreeperStep.SWELL_STOP - 0.5, false, false)), false, Mode.RUN, true));
        // 7.6 is where a -x walk to the corner of an 8 goal ends up
        assertEquals(Event.END_UNLIT, step.step(15, List.of(new Seen(7, false, 7.6, false, false)), false, Mode.RUN, true));
    }

    // the flip: unlit at 6 with the swell still up used to end the step, the run walked back past it and it relit
    @Test
    public void aStepInsideARunDoesNotLetGoOfADeflatingFuse() {
        CreeperStep step = new CreeperStep();
        step.step(0, List.of(new Seen(7, true, 2.5, true)), false, Mode.RUN, true);
        assertEquals(Event.NONE, step.step(5, List.of(new Seen(7, false, 6, false, true)), false, Mode.RUN, true));
        assertTrue(step.active());
    }

    // lit at 3 from a standstill. the creeper stands still while it swells (its goals stop), we sprint straight away with
    // a standing start's speed, and the 30 tick fuse has to find us past 5
    @Test
    public void theHandSprintIsPastTheBlastBeforeTheFuseIsOut() {
        double dist = 3;
        double v = 0;
        long t = 0;
        while (CreeperStep.rawGoing(t, dist, dist)) {
            // ground sprint: 0.28 a tick at the top, friction 0.546
            v = v * 0.546 + 0.28 * (1 - 0.546);
            dist += v;
            t++;
        }
        assertTrue("past 5 within the fuse, was " + dist + " after " + t, dist > 5 && t <= 30);
        // the old 10 ticks left us in the blast
        double ten = 3;
        double vv = 0;
        for (int i = 0; i < 10; i++) {
            vv = vv * 0.546 + 0.28 * (1 - 0.546);
            ten += vv;
        }
        assertTrue(ten < 5.5);
    }

    @Test
    public void longAfterTheWindowItIsANewCreeper() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false, Mode.NONE, true);
        step.step(30, List.of(new Seen(7, false, 7.5, false, false)), false, Mode.NONE, true);
        assertEquals(Event.START, step.step(31 + CreeperStep.REPEAT_WINDOW, lit(7, 3), false, Mode.NONE, true));
        assertFalse(step.escalate());
    }

    @Test
    public void anotherCreeperIsNotARepeat() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false, Mode.NONE, true);
        step.step(30, List.of(), false, Mode.NONE, true);
        assertEquals(Event.START, step.step(40, lit(8, 3), false, Mode.NONE, true));
        assertFalse(step.escalate());
    }

    // ---- the run

    @Test
    public void aRunOnlyStepsForItsOwnPathWalkingIntoOne() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.NONE, step.step(0, lit(7, 5), false, Mode.RUN, true));
        assertFalse(step.active());
        assertEquals(Event.START, step.step(1, List.of(new Seen(7, true, 5, true)), false, Mode.RUN, true));
    }

    @Test
    public void aRunStepsFromOneHissingBesideUs() {
        // lit inside TRIGGER but off the path ahead: beside or behind us, the run alone walks through the blast
        assertEquals(Event.START, new CreeperStep().step(0, lit(7, 2.5), false, Mode.RUN, true));
    }

    @Test
    public void noRunNoChange() {
        assertEquals(Event.START, new CreeperStep().step(0, lit(7, 2.5), false, Mode.NONE, true));
    }

    @Test
    public void aClockGoingWayBackwardsEndsIt() {
        CreeperStep step = new CreeperStep();
        step.step(5000, lit(7, 3), false);
        assertEquals(Event.END_CAP, step.step(5, lit(7, 3), false));
    }

    @Test
    public void aClockAFewTicksBackIsLagAndKeepsIt() {
        CreeperStep step = new CreeperStep();
        step.step(100, lit(7, 3), false);
        assertEquals(Event.NONE, step.step(98, lit(7, 3), false));
        assertEquals(7, step.creeperId());
    }

    @Test
    public void aRelitCreeperStepsAgainButLogsOnce() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        step.step(5, List.of(new Seen(7, false, 6, false)), false);
        assertEquals(Event.START, step.step(40, lit(7, 2.5), false));
        assertFalse(step.shouldLog());
    }

    @Test
    public void boxedInWithAShieldPutsItUp() {
        CreeperStep step = new CreeperStep();
        // (stepped from without a shield, one in hand on the way: it goes up the next tick, a lit one within TRIGGER is the
        // shield's whatever the walk would have made of it)
        step.step(0, lit(7, 3.8), false);
        for (int t = 1; t < CreeperStep.CORNER_TICKS; t++) assertEquals(Event.NONE, step.step(t, lit(7, 3.9), false));
        assertEquals(Event.CORNERED, step.step(CreeperStep.CORNER_TICKS, lit(7, 3.9), true));
        assertTrue(step.cornered());
        assertTrue(step.active());
    }

    private static Event stepNoHeading(CreeperStep step, long now, List<Seen> seen, boolean shield, boolean noHeading) {
        return step.step(now, seen, shield, Mode.NONE, false, noHeading);
    }

    @Test
    public void noWayOutIsCorneredOnTheFirstTick() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.CORNERED, stepNoHeading(step, 0, lit(7, 3.5), true, true));
        assertTrue(step.cornered());
        assertTrue(step.active());
        assertTrue(step.shouldLog());
    }

    @Test
    public void noWayOutBeatsTheWalk() {
        // a shield goes up for a lit one within TRIGGER, with a heading or without (the buddy's fork); without a shield a
        // heading is the ordinary step
        CreeperStep walk = new CreeperStep();
        assertEquals(Event.CORNERED, stepNoHeading(walk, 0, lit(7, 3.8), true, false));
        CreeperStep boxed = new CreeperStep();
        assertEquals(Event.CORNERED, stepNoHeading(boxed, 0, lit(7, 3.8), true, true));
        CreeperStep bare = new CreeperStep();
        assertEquals(Event.START, stepNoHeading(bare, 0, lit(7, 3.8), false, false));
        assertFalse(bare.cornered());
    }

    @Test
    public void noWayOutWithoutAShieldIsTheOrdinaryStep() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.START, stepNoHeading(step, 0, lit(7, 3.5), false, true));
        assertFalse(step.cornered());
    }

    @Test
    public void noWayOutPastTheTriggerWaits() {
        // lit but only the path is walking into it, past the blast's reach: nothing is blowing up on us yet. inside SHIELD_REACH
        // a shield goes up (the buddy's fork)
        CreeperStep step = new CreeperStep();
        assertEquals(Event.START, stepNoHeading(step, 0, List.of(new Seen(7, true, 6.5, true)), true, true));
        assertFalse(step.cornered());
        assertEquals(Event.CORNERED, stepNoHeading(new CreeperStep(), 0, List.of(new Seen(7, true, 5, true)), true, true));
    }

    @Test
    public void noWayOutFoundMidStepCornersAtOnce() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3.8), false);
        assertEquals(Event.CORNERED, stepNoHeading(step, 1, lit(7, 3.8), true, true));
        assertTrue(step.cornered());
    }

    @Test
    public void anUnlitOneInAShaftIsNotCornered() {
        // it un-swelled all the way, nothing to put a shield up for. the held step goes on being held, not cornered
        CreeperStep step = new CreeperStep();
        stepNoHeading(step, 0, lit(7, 3), false, false);
        assertEquals(Event.NONE, stepNoHeading(step, 1, List.of(new Seen(7, false, 3, false, false)), true, true));
        assertFalse(step.cornered());
    }

    @Test
    public void aDeflatingOneWithChargeCloseIsCornered() {
        // still charged and within SHIELD_CLOSE: it lights again with that charge, the shield goes up (the buddy's fork)
        CreeperStep step = new CreeperStep();
        stepNoHeading(step, 0, lit(7, 3), false, false);
        assertEquals(Event.CORNERED, stepNoHeading(step, 1, List.of(new Seen(7, false, 3, false, true)), true, true));
    }

    @Test
    public void noShieldMeansKeepTrying() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        assertEquals(Event.NONE, step.step(CreeperStep.CORNER_TICKS + 5, lit(7, 3), false));
        assertFalse(step.cornered());
    }

    @Test
    public void walkingAwayIsNotCornered() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3.6), false);
        assertEquals(Event.NONE, step.step(CreeperStep.CORNER_TICKS, lit(7, 6.5), true));
        assertEquals(Event.NONE, step.step(CreeperStep.CORNER_TICKS + 1, lit(7, 6.4), true));
        assertFalse(step.cornered());
    }

    @Test
    public void pathNodesWithinThreeOfTheCreeperCount() {
        assertTrue(CreeperStep.pathPassesNear(10.5, 64, 10.5, List.of(new int[]{0, 64, 0}, new int[]{8, 64, 10})));
        assertFalse(CreeperStep.pathPassesNear(10.5, 64, 10.5, List.of(new int[]{0, 64, 0}, new int[]{6, 64, 10})));
        assertFalse(CreeperStep.pathPassesNear(10.5, 64, 10.5, List.of()));
    }

    @Test
    public void resetForgetsEverything() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        step.reset();
        assertFalse(step.active());
        assertEquals(Event.START, step.step(1, lit(7, 3), false));
        assertTrue(step.shouldLog());
    }

    @Test
    public void theLogLineSaysWhatHappened() {
        assertEquals("combat: [overworld] lit creeper at 3 blocks, stepping out of the blast",
                CombatLog.creeperStep(baritone.api.utils.Dimension.OVERWORLD, 3.2));
    }

    @Test
    public void theRawSprintAndPathLinesSayWhatHappened() {
        baritone.api.utils.Dimension d = baritone.api.utils.Dimension.NETHER;
        assertEquals("combat: [nether] creeper step: sprinting straight away", CombatLog.creeperSprint(d, 0));
        assertEquals("combat: [nether] creeper step: sprinting 45 degrees off straight away", CombatLog.creeperSprint(d, -45));
        assertEquals("combat: [nether] creeper step: no safe heading, waiting on the path", CombatLog.creeperNoHeading(d));
        assertEquals("combat: [nether] creeper step: path asked for", CombatLog.creeperPath(d, false));
        assertEquals("combat: [nether] creeper step: no path running, asking again", CombatLog.creeperPath(d, true));
    }

    @Test
    public void theStepGoesEightBlocksAndCornersStillCountPositionOnly() {
        assertEquals(8, CreeperStep.SAFE, 0);
        // the clock-and-distance test does not care how the ground was gained, so sprinting out of a corner is not cornered
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3.6), false);
        assertEquals(Event.NONE, step.step(CreeperStep.CORNER_TICKS, lit(7, 6.4), true));
        assertFalse(step.cornered());
    }

    // the sprint is not handed to a path while a lit one is inside the blast, however long it has been going
    @Test
    public void theHandSprintHoldsWhileALitOneIsInsideSix() {
        assertTrue(CreeperStep.rawGoing(CreeperStep.RAW_MAX_TICKS + 20, 7, 5.9));
        assertTrue(CreeperStep.rawGoing(0, 2, 2));
        // past six it is the old rule: 30 ticks and short of SAFE
        assertTrue(CreeperStep.rawGoing(10, 6.5, 6.5));
        assertFalse(CreeperStep.rawGoing(CreeperStep.RAW_MAX_TICKS, 6.5, 6.5));
        // an unlit one gets no hold
        assertFalse(CreeperStep.rawGoing(CreeperStep.RAW_MAX_TICKS, 3, Double.POSITIVE_INFINITY));
    }

    private static Seen unlit(double distance, boolean inView) {
        return new Seen(7, false, distance, false, false, inView);
    }

    @Test
    public void anUnlitOneBehindAWallIsNothing() {
        assertEquals(Event.NONE, new CreeperStep().step(0, List.of(unlit(3, CreeperStep.inView(0, false))), true));
    }

    @Test
    public void anUnlitOneFourBlocksBelowIsNothing() {
        assertEquals(Event.NONE, new CreeperStep().step(0, List.of(unlit(3, CreeperStep.inView(-4, true))), true));
    }

    @Test
    public void anUnlitOneInPlainViewSteps() {
        assertEquals(Event.START, new CreeperStep().step(0, List.of(unlit(3, CreeperStep.inView(1, true))), true));
    }

    @Test
    public void aLitOneStepsWithoutSight() {
        assertEquals(Event.START, new CreeperStep().step(0, List.of(new Seen(7, true, 3.8, false, true, false)), false));
    }

    // the one we hold in a run coming back lit within SHIELD_CLOSE: the shield, without one the step goes on
    @Test
    public void aHeldOneLitAgainCloseGetsTheShield() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.START, step.step(0, lit(7, 3.8), false, Mode.RUN, true));
        assertEquals(Event.NONE, step.step(5, fused(7, false, 5.2, true), true, Mode.RUN, true));
        assertEquals(Event.CORNERED, step.step(10, lit(7, 3.3), true, Mode.RUN, true));
        assertTrue(step.cornered());
        CreeperStep bare = new CreeperStep();
        bare.step(0, lit(7, 3.8), false, Mode.RUN, true);
        assertEquals(Event.NONE, bare.step(10, lit(7, 3.3), false, Mode.RUN, true));
    }

    // one deflating with charge left, close, in a run: the shield too, it lights again with that charge
    @Test
    public void aChargedOneCloseInARunGetsTheShield() {
        assertEquals(Event.CORNERED, new CreeperStep().step(0, fused(7, false, 2, true), true, Mode.RUN, true));
        assertEquals(Event.NONE, new CreeperStep().step(0, fused(7, false, 2, false), true, Mode.RUN, true));
    }

    // a lit one within SHIELD_CLOSE (all of TRIGGER): the shield, if there is one
    @Test
    public void aLitOneThreeBlocksOffGetsTheShield() {
        assertEquals(Event.CORNERED, new CreeperStep().step(0, lit(7, 3), true));
        assertEquals(Event.START, new CreeperStep().step(0, lit(7, 3), false));
        assertEquals(Event.CORNERED, new CreeperStep().step(0, lit(7, 3.8), true));
        assertEquals(Event.START, new CreeperStep().step(0, lit(7, 3.8), false));
    }
}
