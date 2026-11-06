package adris.altoclef.tasks.entity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LavaHoldClockTest {
    @Test
    public void holdsAddUpInsideTheWindow() {
        LavaHoldClock c = new LavaHoldClock();
        for (int t = 0; t < 400; t++) {
            c.note(t * 2);
        }
        assertEquals(400, c.ticks(798));
    }

    @Test
    public void oldHoldsAgeOut() {
        LavaHoldClock c = new LavaHoldClock();
        c.note(0);
        c.note(10);
        assertEquals(0, c.ticks(LavaHoldClock.WINDOW_TICKS * 3));
        c.note(LavaHoldClock.WINDOW_TICKS * 3);
        assertEquals(1, c.ticks(LavaHoldClock.WINDOW_TICKS * 3 + 1));
    }

    @Test
    public void twoBurstsEitherSideOfAWindowEdgeStillAddUp() {
        LavaHoldClock c = new LavaHoldClock();
        c.note(0);
        for (int t = 1000; t < 1200; t++) {
            c.note(t);
        }
        for (int t = 1201; t <= 1400; t++) {
            c.note(t);
        }
        assertEquals(400, c.ticks(1400));
    }

    @Test
    public void clearAndRespawnDropTheCount() {
        LavaHoldClock c = new LavaHoldClock();
        c.note(5000);
        c.note(5001);
        assertEquals(0, c.ticks(3));
        c.clear();
        assertEquals(0, c.ticks(5002));
    }
}
