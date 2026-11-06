package adris.altoclef.tasks.movement;

import org.junit.Test;

import static org.junit.Assert.*;

public class ChaseStallWatchTest {

    // the task ticks every 20 ticks here, true if it ever called it stalled
    private static boolean tick(ChaseStallWatch w, long from, long to, double x, double y, double z) {
        boolean stalled = false;
        for (long t = from; t <= to; t += 20) {
            stalled |= w.stalled(t, x, y, z);
        }
        return stalled;
    }

    @Test
    public void standingInOnePlaceForTheWindowIsStalled() {
        ChaseStallWatch w = new ChaseStallWatch();
        assertFalse(tick(w, 1000, 1000 + ChaseStallWatch.WINDOW_TICKS - 20, 0, 60, 0));
        assertTrue(w.stalled(1000 + ChaseStallWatch.WINDOW_TICKS, 1, 60, 1));
    }

    @Test
    public void wigglingInsideTheCircleStillCounts() {
        ChaseStallWatch w = new ChaseStallWatch();
        w.stalled(0, 0, 60, 0);
        for (int i = 1; i * 20 < ChaseStallWatch.WINDOW_TICKS; i++) {
            assertFalse(w.stalled(i * 20, (i % 2 == 0) ? 1.5 : -1.5, 60, 0));
        }
        assertTrue(w.stalled(ChaseStallWatch.WINDOW_TICKS, 1, 60, 1));
    }

    @Test
    public void goingSomewhereStartsTheClockOver() {
        ChaseStallWatch w = new ChaseStallWatch();
        assertFalse(tick(w, 0, 800, 0, 60, 0));
        // walks off at 820, the window counts from there
        assertFalse(tick(w, 820, 820 + ChaseStallWatch.WINDOW_TICKS - 20, 10, 60, 0));
        assertTrue(w.stalled(820 + ChaseStallWatch.WINDOW_TICKS, 10, 60, 0));
    }

    @Test
    public void climbingOutOfAPitCountsAsMoving() {
        ChaseStallWatch w = new ChaseStallWatch();
        tick(w, 0, 800, 0, 60, 0);
        assertFalse(w.stalled(820, 0, 64, 0));
        assertFalse(w.stalled(ChaseStallWatch.WINDOW_TICKS, 0, 64, 0));
    }

    @Test
    public void resetForgetsTheSpot() {
        ChaseStallWatch w = new ChaseStallWatch();
        tick(w, 0, 800, 0, 60, 0);
        w.reset();
        assertFalse(w.stalled(ChaseStallWatch.WINDOW_TICKS, 0, 60, 0));
    }

    @Test
    public void aShortInterruptionKeepsTheCount() {
        ChaseStallWatch w = new ChaseStallWatch();
        tick(w, 0, 400, 0, 60, 0);
        // away for a meal, 500 ticks without a call
        assertTrue(w.stalled(900, 0, 60, 0));
    }

    @Test
    public void aLongAbsenceStartsOver() {
        ChaseStallWatch w = new ChaseStallWatch();
        tick(w, 0, 400, 0, 60, 0);
        assertFalse(w.stalled(400 + ChaseStallWatch.GAP_TICKS + 1, 0, 60, 0));
    }

    @Test
    public void aReconnectDoesNotFakeAStall() {
        ChaseStallWatch w = new ChaseStallWatch();
        w.stalled(400000, 0, 60, 0);
        // the tick counter starts over, far behind the stamp: count from here instead
        assertFalse(w.stalled(50, 0, 60, 0));
        assertTrue(tick(w, 70, 50 + ChaseStallWatch.WINDOW_TICKS, 0, 60, 0));
    }
}
