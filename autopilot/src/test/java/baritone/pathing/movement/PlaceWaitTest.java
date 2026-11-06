package baritone.pathing.movement;

import static baritone.pathing.movement.PlaceWait.Verdict.CLEAR;
import static baritone.pathing.movement.PlaceWait.Verdict.GIVE_UP;
import static baritone.pathing.movement.PlaceWait.Verdict.WAIT;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlaceWaitTest {

    @Test
    public void aClearCellIsClear() {
        MovementState s = new MovementState();
        assertEquals(CLEAR, s.placeBlocked(false));
    }

    @Test
    public void aMobInTheCellIsWaitedOutForTwentyTicks() {
        MovementState s = new MovementState();
        for (int i = 1; i <= PlaceWait.MOVEMENT_PATIENCE; i++) {
            assertEquals("tick " + i, WAIT, s.placeBlocked(true));
        }
        assertEquals(GIVE_UP, s.placeBlocked(true));
        assertEquals(GIVE_UP, s.placeBlocked(true));
    }

    // it wandered off for a tick, so the clock starts over. a mob dancing in and out should not add up to giving up
    @Test
    public void aClearTickStartsTheCountOver() {
        MovementState s = new MovementState();
        for (int i = 0; i < PlaceWait.MOVEMENT_PATIENCE; i++) s.placeBlocked(true);
        assertEquals(CLEAR, s.placeBlocked(false));
        assertEquals(WAIT, s.placeBlocked(true));
    }

    @Test
    public void theTaskGivesUpOnASpotSoonerThanAMovement() {
        int ticks = 0;
        for (int i = 0; i < PlaceWait.TASK_PATIENCE; i++) {
            ticks = PlaceWait.next(true, ticks);
            assertEquals(WAIT, PlaceWait.judge(ticks, PlaceWait.TASK_PATIENCE));
        }
        ticks = PlaceWait.next(true, ticks);
        assertEquals(GIVE_UP, PlaceWait.judge(ticks, PlaceWait.TASK_PATIENCE));
        // the same count is still fine for a movement
        assertEquals(WAIT, PlaceWait.judge(ticks, PlaceWait.MOVEMENT_PATIENCE));
    }

    @Test
    public void nextOnlyCountsConsecutiveTicks() {
        assertEquals(1, PlaceWait.next(true, 0));
        assertEquals(6, PlaceWait.next(true, 5));
        assertEquals(0, PlaceWait.next(false, 5));
    }
}
