package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.WaitCap.Verdict;
import org.junit.Test;

// an angry enderman out of reach is a wait with no end: the plan only waits for it. the cap walks at it once, then writes it off
public class WaitCapTest {

    private static Verdict waitFor(WaitCap cap, int ticks) {
        Verdict last = Verdict.NONE;
        for (int i = 0; i < ticks; i++) {
            Verdict v = cap.tick(true);
            if (v != Verdict.NONE) return v;
            last = v;
        }
        return last;
    }

    @Test
    public void anOutOfReachWaitIsCappedByAWalkThenABan() {
        WaitCap cap = new WaitCap();
        assertEquals(Verdict.NONE, waitFor(cap, ApproachRules.WAIT_TICKS - 1));
        assertFalse(cap.approaching());
        assertEquals(Verdict.APPROACH, cap.tick(true));
        assertTrue(cap.approaching());
        // the walk gets its own WAIT_TICKS, the plan's wait does not count in them
        for (int i = 0; i < ApproachRules.WAIT_TICKS; i++) assertEquals(Verdict.NONE, cap.tick(true));
        assertFalse(cap.approaching());
        assertEquals(Verdict.NONE, waitFor(cap, ApproachRules.WAIT_TICKS - 1));
        assertEquals(Verdict.BAN, cap.tick(true));
    }

    @Test
    public void aTickThatIsNotWaitingStartsTheCountOver() {
        WaitCap cap = new WaitCap();
        waitFor(cap, ApproachRules.WAIT_TICKS - 1);
        assertEquals(Verdict.NONE, cap.tick(false));
        assertEquals(Verdict.NONE, waitFor(cap, ApproachRules.WAIT_TICKS - 1));
        assertEquals(Verdict.APPROACH, cap.tick(true));
    }

    @Test
    public void aLongQuietSpellForgetsTheFirstCap() {
        WaitCap cap = new WaitCap();
        assertEquals(Verdict.APPROACH, waitFor(cap, ApproachRules.WAIT_TICKS));
        for (int i = 0; i < ApproachRules.WAIT_TICKS; i++) cap.tick(true);
        for (int i = 0; i < ApproachRules.WAIT_TICKS; i++) cap.tick(false);
        assertEquals(Verdict.APPROACH, waitFor(cap, ApproachRules.WAIT_TICKS));
    }

    @Test
    public void resetForgetsEverything() {
        WaitCap cap = new WaitCap();
        waitFor(cap, ApproachRules.WAIT_TICKS);
        cap.reset();
        assertFalse(cap.approaching());
        assertEquals(Verdict.APPROACH, waitFor(cap, ApproachRules.WAIT_TICKS));
    }

    @Test
    public void rightOnTopOfItWithNoShotCountsAsStuck() {
        // aim stalled, too close: the plan only waits, so the cap has to be able to see it
        assertTrue(WaitCap.counts(false, true, false, true, false));
        // a clear shot waiting on a swing is the stand cap's, and a mob at a sane distance is the approach's
        assertFalse(WaitCap.counts(false, true, true, true, false));
        assertFalse(WaitCap.counts(false, true, false, false, false));
    }

    @Test
    public void theOldWaitsStillCount() {
        assertTrue(WaitCap.counts(true, false, true, false, false));
        assertFalse(WaitCap.counts(true, true, true, false, false));
        assertTrue(WaitCap.counts(false, true, true, false, true));
        assertFalse(WaitCap.counts(false, false, true, false, true));
    }
}
