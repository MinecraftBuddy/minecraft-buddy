package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BackOffRulesTest {

    @Test
    public void aBackOffNeedsASideToStepToAndRoomOverheadBoth() {
        assertEquals(true, BackOffRules.roomToBackOff(true, true));
        // a shaft: nothing to the side
        assertEquals(false, BackOffRules.roomToBackOff(false, true));
        // a low tunnel: sides open and a ceiling two above the feet
        assertEquals(false, BackOffRules.roomToBackOff(true, false));
        assertEquals(false, BackOffRules.roomToBackOff(false, false));
    }

    @Test
    public void aBackOffThatDoesNotMoveUsIsStuckAfterTheWindow() {
        BackOffRules.Watch watch = new BackOffRules.Watch();
        for (int tick = 0; tick < BackOffRules.STUCK_TICKS; tick++) {
            assertEquals("tick " + tick, false, watch.stuck(tick, 10, 10));
        }
        assertEquals(true, watch.stuck(BackOffRules.STUCK_TICKS, 10.2, 10));
    }

    @Test
    public void aBackOffThatIsWalkingNeverTripsIt() {
        BackOffRules.Watch watch = new BackOffRules.Watch();
        // a walking pace is 0.2 a tick, well over half a block in the window
        for (int tick = 0; tick < 100; tick++) {
            assertEquals("tick " + tick, false, watch.stuck(tick, 10 + tick * 0.2, 10));
        }
    }

    @Test
    public void aBackOffThatStopsPartWayIsCaughtOnTheNextWindow() {
        BackOffRules.Watch watch = new BackOffRules.Watch();
        boolean stuck = false;
        for (int tick = 0; tick < 60 && !stuck; tick++) {
            double x = tick < 15 ? tick * 0.2 : 3;
            stuck = watch.stuck(tick, x, 0);
        }
        assertEquals(true, stuck);
    }

    @Test
    public void clearingTheWatchStartsANewWindow() {
        BackOffRules.Watch watch = new BackOffRules.Watch();
        watch.stuck(0, 0, 0);
        watch.clear();
        assertEquals(false, watch.stuck(BackOffRules.STUCK_TICKS, 0, 0));
    }
}
