package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PursuitProgressTest {
    private static final Object LOG = "log at 1 64 1";
    private static final Object OTHER = "log at 3 64 3";

    // a pursuit and the game clock, one tick per call like the task
    private static final class Run {
        final PursuitProgress p = new PursuitProgress();
        long now = 1000;

        void tick(Object on, double dist) {
            p.tick(on, dist, false, now++);
        }

        void broke(Object on, double dist) {
            p.tick(on, dist, true, now++);
        }

        void standStill(Object on, double dist, int ticks) {
            for (int i = 0; i < ticks; i++) {
                tick(on, dist);
            }
        }
    }

    @Test
    public void nothingPursuedIsNeverStalled() {
        assertFalse(new PursuitProgress().stalled());
    }

    @Test
    public void sevenSecondsWithoutGettingCloserIsStalled() {
        assertEquals(7 * 20, PursuitProgress.STALL_TICKS);
        Run r = new Run();
        // the first tick is where the clock starts, not a tick of getting nowhere
        r.standStill(LOG, 10, PursuitProgress.STALL_TICKS);
        assertFalse(r.p.stalled());
        r.tick(LOG, 10);
        assertTrue(r.p.stalled());
    }

    @Test
    public void halfABlockCloserStartsTheClockOverAndLessDoesNot() {
        Run r = new Run();
        r.tick(LOG, 10);
        r.standStill(LOG, 9.6, PursuitProgress.STALL_TICKS - 1);
        // creeping up a few centimetres at a time is not a walk that works
        r.tick(LOG, 9.6);
        assertTrue(r.p.stalled());
        r.tick(LOG, 9.4);
        assertFalse(r.p.stalled());
    }

    @Test
    public void walkingAwayAndBackDoesNotCountAsProgress() {
        Run r = new Run();
        r.tick(LOG, 10);
        r.standStill(LOG, 14, 60);
        // back where we were is still not closer than ever
        r.standStill(LOG, 10, PursuitProgress.STALL_TICKS - 60);
        assertTrue(r.p.stalled());
    }

    @Test
    public void aBlockBrokenOnTheWayIsProgress() {
        Run r = new Run();
        r.tick(LOG, 10);
        r.standStill(LOG, 10, PursuitProgress.STALL_TICKS - 1);
        r.broke(LOG, 10);
        assertFalse(r.p.stalled());
        r.standStill(LOG, 10, PursuitProgress.STALL_TICKS - 1);
        assertFalse(r.p.stalled());
    }

    @Test
    public void inReachIsWorkingOnItNotGettingNowhere() {
        Run r = new Run();
        r.tick(LOG, PursuitProgress.IN_REACH);
        r.standStill(LOG, PursuitProgress.IN_REACH, PursuitProgress.STALL_TICKS * 3);
        assertFalse(r.p.stalled());
        // a step out of reach and the clock runs again
        r.standStill(LOG, PursuitProgress.IN_REACH + 0.1, PursuitProgress.STALL_TICKS);
        assertTrue(r.p.stalled());
    }

    @Test
    public void timeSpentSomewhereElseIsNotTimeSpentGettingNowhere() {
        Run r = new Run();
        r.tick(LOG, 10);
        r.standStill(LOG, 10, PursuitProgress.STALL_TICKS - 10);
        // a fight took the wheel for 30 s and dragged us 15 blocks further off
        r.now += 600;
        r.tick(LOG, 25);
        assertFalse(r.p.stalled());
        // the distance starts over from there: walking back toward the old best is progress
        r.standStill(LOG, 25, PursuitProgress.STALL_TICKS - 10);
        r.tick(LOG, 24);
        r.standStill(LOG, 24, PursuitProgress.STALL_TICKS - 1);
        assertFalse(r.p.stalled());
        // a gap of a few ticks is not a resume
        r.now += PursuitProgress.RESUME_GAP - 1;
        r.tick(LOG, 24);
        assertTrue(r.p.stalled());
    }

    @Test
    public void aNewTargetGetsAFreshClock() {
        Run r = new Run();
        r.tick(LOG, 10);
        r.standStill(LOG, 10, PursuitProgress.STALL_TICKS);
        assertTrue(r.p.stalled());
        r.tick(OTHER, 30);
        assertFalse(r.p.stalled());
        r.p.clear();
        assertFalse(r.p.stalled());
    }

    @Test
    public void theRePickRule() {
        // stalled and the pick says something else is nearest: that one gets a go
        assertTrue(PursuitProgress.repick(true, true, LOG, OTHER));
        // still getting somewhere: the 2x rule decides, not this
        assertFalse(PursuitProgress.repick(true, false, LOG, OTHER));
        // the stuck one is still the nearest: nothing to switch to
        assertFalse(PursuitProgress.repick(true, true, LOG, LOG));
        assertFalse(PursuitProgress.repick(true, true, LOG, null));
        // tasks that opt out (mobs) never switch on a stall
        assertFalse(PursuitProgress.repick(false, true, LOG, OTHER));
    }
}
