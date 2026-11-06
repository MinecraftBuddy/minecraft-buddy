package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HoldLatchTest {
    @Test
    public void startsOff() {
        assertFalse(new HoldLatch(5).update(false, 0));
    }

    @Test
    public void staysOnForTheHoldAfterTheLastSighting() {
        HoldLatch l = new HoldLatch(5);
        assertTrue(l.update(true, 10));
        assertTrue(l.update(false, 12));
        assertTrue(l.update(false, 14.9));
        assertFalse(l.update(false, 15));
    }

    @Test
    public void aFreshSightingRestartsTheHold() {
        HoldLatch l = new HoldLatch(5);
        l.update(true, 0);
        assertTrue(l.update(true, 4));
        assertTrue(l.update(false, 8));
        assertFalse(l.update(false, 9));
    }

    @Test
    public void resetForgetsIt() {
        HoldLatch l = new HoldLatch(5);
        l.update(true, 0);
        l.reset();
        assertFalse(l.update(false, 1));
    }
}
