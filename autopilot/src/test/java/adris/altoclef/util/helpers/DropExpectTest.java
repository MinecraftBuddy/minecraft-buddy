package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DropExpectTest {
    @Test
    public void nothingExpectedNeverHolds() {
        DropExpect e = new DropExpect();
        assertFalse(e.isLive());
        assertFalse(e.hold(100, false));
        assertFalse(e.hold(100, true));
        assertFalse(e.takeNow());
    }

    @Test
    public void aBreakHoldsForExactlyItsBudget() {
        DropExpect e = new DropExpect();
        e.expectBreak(1000);
        for (int t = 1000; t < 1000 + DropExpect.BREAK_TICKS; t++) {
            assertTrue("tick " + t, e.hold(t, false));
        }
        assertFalse(e.hold(1000 + DropExpect.BREAK_TICKS, false));
        assertFalse(e.isLive());
        // timing out is not "go and get it"
        assertFalse(e.takeNow());
    }

    @Test
    public void aKillWaitsLongerThanABreak() {
        DropExpect brk = new DropExpect();
        DropExpect kill = new DropExpect();
        brk.expectBreak(0);
        kill.expectKill(0);
        int t = DropExpect.BREAK_TICKS;
        assertFalse(brk.hold(t, false));
        assertTrue(kill.hold(t, false));
        for (t = 0; t < DropExpect.KILL_TICKS; t++) {
            assertTrue("tick " + t, kill.hold(t, false));
        }
        assertFalse(kill.hold(DropExpect.KILL_TICKS, false));
        assertTrue(DropExpect.KILL_TICKS > DropExpect.BREAK_TICKS);
    }

    @Test
    public void aMatchingDropEndsTheWaitAndSaysGoGetIt() {
        DropExpect e = new DropExpect();
        e.expectBreak(50);
        assertTrue(e.hold(51, false));
        assertTrue(e.hold(52, false));
        // it showed up: the wait is over early and the pickup paths take it from here
        assertFalse(e.hold(53, true));
        assertTrue(e.takeNow());
        assertFalse(e.isLive());
        // and it doesn't hold again by itself
        assertFalse(e.hold(54, false));
    }

    @Test
    public void aDropOnTheVeryFirstTickIsNotWaitedFor() {
        DropExpect e = new DropExpect();
        e.expectKill(10);
        assertFalse(e.hold(10, true));
        assertTrue(e.takeNow());
    }

    @Test
    public void aNewExpectationClearsTheOldVerdict() {
        DropExpect e = new DropExpect();
        e.expectBreak(0);
        e.hold(1, true);
        assertTrue(e.takeNow());
        e.expectBreak(5);
        assertFalse(e.takeNow());
        assertTrue(e.isLive());
        assertTrue(e.hold(6, false));
    }

    @Test
    public void aWaitNobodyTickedStopsLookingLiveOnItsOwn() {
        // a task swapped out mid wait never calls hold() again, and whoever asks from outside must not wait on it forever
        DropExpect e = new DropExpect();
        e.expectKill(100);
        assertTrue(e.isLive(100 + DropExpect.KILL_TICKS - 1));
        assertFalse(e.isLive(100 + DropExpect.KILL_TICKS));
    }

    @Test
    public void clearForgetsEverything() {
        DropExpect e = new DropExpect();
        e.expectKill(0);
        e.clear();
        assertFalse(e.isLive());
        assertFalse(e.hold(1, false));
    }
}
