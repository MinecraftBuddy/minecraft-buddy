package adris.altoclef.tasksystem;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaskForceTimeoutTest {
    @Test
    public void forceHoldsForTenSecondsOfTicks() {
        assertFalse(Task.forceTimedOut(50, 250));
        assertTrue(Task.forceTimedOut(50, 251));
    }

    @Test
    public void aFrozenTickClockNeverTimesOut() {
        // paused: the tick count stops, so no amount of wall time moves this
        assertFalse(Task.forceTimedOut(50, 50));
    }
}
