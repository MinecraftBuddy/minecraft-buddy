package adris.altoclef.tasks.movement;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RunAwayFromHostilesTaskTest {
    @Test
    public void keepsTheLivePathOnANetherRim() {
        assertFalse(RunAwayFromHostilesTask.forceCancels(true, true));
    }

    @Test
    public void cancelsEverywhereElse() {
        assertTrue(RunAwayFromHostilesTask.forceCancels(true, false));
        assertTrue(RunAwayFromHostilesTask.forceCancels(false, true));
        assertTrue(RunAwayFromHostilesTask.forceCancels(false, false));
    }
}
