package adris.altoclef.tasks.movement;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// what counts as out of the lava for the escape goal
public class EscapeFromLavaGoalTest {

    @Test
    public void dryCellWithAFloorIsSafe() {
        assertTrue(EscapeFromLavaTask.isSafeCell(false, false, true));
    }

    @Test
    public void airOverTheLakeIsNotSafe() {
        assertFalse(EscapeFromLavaTask.isSafeCell(false, false, false));
    }

    @Test
    public void lavaOrLavaBesideIsNotSafe() {
        assertFalse(EscapeFromLavaTask.isSafeCell(true, false, true));
        assertFalse(EscapeFromLavaTask.isSafeCell(false, true, true));
    }
}
