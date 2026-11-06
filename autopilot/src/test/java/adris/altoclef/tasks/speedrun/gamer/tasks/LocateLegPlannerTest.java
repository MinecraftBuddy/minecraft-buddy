package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocateLegPlannerTest {
    @Test
    public void legBudgetGrowsWithDistance() {
        assertTrue(LocateLegPlanner.legBudgetSeconds(0) >= 60);
        assertTrue(LocateLegPlanner.legBudgetSeconds(1000) > LocateLegPlanner.legBudgetSeconds(100));
    }

    @Test
    public void aLegThatKeepsGettingCloserNeverStalls() {
        LocateLegPlanner.Leg leg = new LocateLegPlanner.Leg(0, 200);
        double dist = 200;
        for (double t = 1; t <= 50; t++) {
            dist -= 4;
            leg.update(t, dist);
            assertFalse("t " + t, leg.expired(t));
        }
    }

    @Test
    public void aLegThatStandsStillExpiresAfterAMinute() {
        LocateLegPlanner.Leg leg = new LocateLegPlanner.Leg(100, 200);
        leg.update(120, 199);
        leg.update(150, 198.5);
        assertFalse(leg.expired(159));
        assertTrue(leg.expired(160));
        assertTrue(leg.why(160).contains("progress"));
    }

    @Test
    public void tinyJitterIsNotProgress() {
        LocateLegPlanner.Leg leg = new LocateLegPlanner.Leg(0, 100);
        for (int t = 1; t < 60; t++) {
            leg.update(t, 100 - (t % 2) * 1.0);
        }
        assertTrue(leg.expired(60));
    }

    @Test
    public void aLegCannotRunPastItsBudgetEvenWhenCreeping() {
        LocateLegPlanner.Leg leg = new LocateLegPlanner.Leg(0, 10);
        double budget = LocateLegPlanner.legBudgetSeconds(10);
        double dist = 10;
        double t = 0;
        // 4 blocks every 50 s: always "progress", never stalled, so the hard budget is what ends it
        while (t < budget + 1) {
            t += 50;
            dist -= 4;
            leg.update(t, dist);
            if (leg.expired(t)) {
                assertTrue(t >= budget);
                assertEquals("took too long", leg.why(t));
                return;
            }
        }
        throw new AssertionError("never expired");
    }

    @Test
    public void emptyThrowsAreFreeButCappedInARow() {
        LocateLegPlanner.ThrowLedger l = new LocateLegPlanner.ThrowLedger(0, 14, 3);
        l.empty();
        l.empty();
        assertFalse(l.tooManyEmpty());
        assertEquals(0, l.counted());
        l.empty();
        assertTrue(l.tooManyEmpty());
    }

    @Test
    public void aRealThrowResetsTheEmptyStreak() {
        LocateLegPlanner.ThrowLedger l = new LocateLegPlanner.ThrowLedger(0, 14, 3);
        l.empty();
        l.empty();
        l.eyeAppeared();
        l.empty();
        l.empty();
        assertFalse(l.tooManyEmpty());
        assertEquals(1, l.counted());
    }

    @Test
    public void throwCapIsHardAndSurvivesARelog() {
        LocateLegPlanner.ThrowLedger l = new LocateLegPlanner.ThrowLedger(12, 14, 3);
        assertFalse(l.outOfThrows());
        l.eyeAppeared();
        assertFalse(l.outOfThrows());
        l.eyeAppeared();
        assertTrue(l.outOfThrows());
    }

    @Test
    public void relocateStepsAlongTheRingNotInOrOut() {
        double[] t = LocateLegPlanner.relocateTarget(2000, 0, 150);
        assertEquals(2000, t[0], 1e-9);
        assertEquals(150, t[1], 1e-9);
        double[] u = LocateLegPlanner.relocateTarget(0, -2000, 150);
        assertEquals(150, u[0], 1e-9);
        assertEquals(-2000, u[1], 1e-9);
        // 150 blocks away, and the radius from the origin barely changes (tangent, not radial)
        double[] v = LocateLegPlanner.relocateTarget(1500, -1500, 150);
        assertEquals(150, Math.hypot(v[0] - 1500, v[1] + 1500), 1e-9);
        assertTrue(Math.abs(Math.hypot(v[0], v[1]) - Math.hypot(1500, 1500)) < 6);
    }

    @Test
    public void neverThrowWithinTenBlocksOfTheLastThrow() {
        assertFalse(LocateLegPlanner.tooCloseToLastThrow(0, 0, Double.NaN, Double.NaN));
        assertTrue(LocateLegPlanner.tooCloseToLastThrow(105, 100, 100, 100));
        assertTrue(LocateLegPlanner.tooCloseToLastThrow(100, 109.9, 100, 100));
        assertFalse(LocateLegPlanner.tooCloseToLastThrow(100, 110, 100, 100));
    }

    @Test
    public void anUnusableThrowForcesAWalkOfAtLeastTwentyBlocksFromTheOldSpot() {
        // with a guess: toward it, the full step even when the guess is closer than that (a short hop would loop)
        double[] toGuess = LocateLegPlanner.forcedWalkTarget(100, 100, true, 100, 112);
        assertEquals(100, toGuess[0], 1e-9);
        assertEquals(126, toGuess[1], 1e-9);
        // without one: along the ring tangent
        double[] tangent = LocateLegPlanner.forcedWalkTarget(2000, 0, false, 0, 0);
        assertEquals(26, Math.hypot(tangent[0] - 2000, tangent[1]), 1e-9);
        // a guess right on top of us is no direction
        double[] onTop = LocateLegPlanner.forcedWalkTarget(500, 500, true, 500.5, 500);
        assertEquals(26, Math.hypot(onTop[0] - 500, onTop[1] - 500), 1e-9);
        // and arriving within the 6 block tolerance still leaves 20 behind us, past the 10 block throw gap
        assertTrue(LocateLegPlanner.FORCED_WALK_BLOCKS - LocateLegPlanner.ARRIVE_BLOCKS >= 20);
        assertTrue(LocateLegPlanner.FORCED_WALK_BLOCKS - LocateLegPlanner.ARRIVE_BLOCKS > LocateLegPlanner.MIN_THROW_GAP);
    }

    @Test
    public void settleWaitRunsFromWhenTheEyeWentGone() {
        // still flying: not this wait
        assertFalse(LocateLegPlanner.settleWait(true, 0, false));
        // just went, nothing on the floor yet: keep waiting
        assertTrue(LocateLegPlanner.settleWait(false, 0.5, false));
        // gone too long: it shattered
        assertFalse(LocateLegPlanner.settleWait(false, 3.1, false));
        // drop showed up: stop waiting
        assertFalse(LocateLegPlanner.settleWait(false, 0.5, true));
        // unstamped clock stops the wait rather than hanging it
        assertFalse(LocateLegPlanner.settleWait(false, Double.NaN, false));
    }

    @Test
    public void anEyeOwedToUsSurvivesAnInterrupt() {
        // held 6 before the throw, 5 now: the eye is still flying -> collect it
        assertTrue(LocateLegPlanner.collectPending(6, 5, true, false));
        // it came down and lies nearby
        assertTrue(LocateLegPlanner.collectPending(6, 5, false, true));
        // shattered (20%): nothing flying, nothing on the floor -> the debt is void
        assertFalse(LocateLegPlanner.collectPending(6, 5, false, false));
        // got it back
        assertFalse(LocateLegPlanner.collectPending(6, 6, false, true));
        assertFalse(LocateLegPlanner.collectPending(6, 7, true, true));
        // no baseline = no throw in progress
        assertFalse(LocateLegPlanner.collectPending(0, 0, true, true));
    }

    @Test
    public void relocateAtTheOriginStillGoesSomewhere() {
        double[] t = LocateLegPlanner.relocateTarget(0, 0, 150);
        assertEquals(150, Math.hypot(t[0], t[1]), 1e-9);
    }

    // the task feeds its own clock (seconds of time spent running), so a gap in game time between two ticks is invisible
    @Test
    public void legOnTaskSecondsIgnoresTimeSpentAway() {
        LocateLegPlanner.Leg leg = new LocateLegPlanner.Leg(10, 100);
        leg.update(40, 100);
        assertFalse(leg.expired(40));
        assertTrue(leg.expired(10 + LocateLegPlanner.STALL_SECONDS));
    }
}
