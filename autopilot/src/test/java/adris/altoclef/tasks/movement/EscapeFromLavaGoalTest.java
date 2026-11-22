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

    // (lava_goal_head) lava at head height, in the cell or beside it, makes a dry cell unsafe; off, only the feet's layer counts
    @Test
    public void lavaAtHeadHeightCountsWhenOn() {
        assertFalse(EscapeFromLavaTask.isSafeCell(false, false, true, false, true, true));
        assertFalse(EscapeFromLavaTask.isSafeCell(false, false, false, true, true, true));
        assertTrue(EscapeFromLavaTask.isSafeCell(false, false, false, false, true, true));
        assertTrue(EscapeFromLavaTask.isSafeCell(false, false, true, true, true, false));
    }

    @Test
    public void theFeetsLayerAndTheFloorStillCountWithTheHead() {
        assertFalse(EscapeFromLavaTask.isSafeCell(true, false, false, false, true, true));
        assertFalse(EscapeFromLavaTask.isSafeCell(false, true, false, false, true, true));
        assertFalse(EscapeFromLavaTask.isSafeCell(false, false, false, false, false, true));
    }
}
