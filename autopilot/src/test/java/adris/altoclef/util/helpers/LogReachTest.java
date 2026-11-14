package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// (Tuning log_far_surface) logs seen from deep under the ground: no strike on a tree from there, up to the surface first (look
// 88: tree after tree banned from a cave 50 blocks under them)
public class LogReachTest {

    @Test
    public void aLogFarOverABuddyUnderARoofIsNoStrike() {
        // 50 under the surface, the log 50 over the feet
        assertTrue(LogReach.farFromHere(50, 50));
        // the log 16 over the feet or less: a tower or a climb by the trunk, the old way
        assertFalse(LogReach.farFromHere(50, 16));
        assertTrue(LogReach.farFromHere(50, 17));
        // in the open (a two deep pit counts as open): the old way
        assertFalse(LogReach.farFromHere(2, 40));
        assertTrue(LogReach.farFromHere(3, 40));
    }

    @Test
    public void threeTreesInARowWithNoLogMinedAreOutOfReach() {
        int row = LogReach.row(0, 0, 0);
        assertEquals(1, row);
        row = LogReach.row(row, 0, 0);
        assertFalse(LogReach.outOfReach(row));
        row = LogReach.row(row, 0, 0);
        assertTrue(LogReach.outOfReach(row));
        // a log mined since the last one starts the row over
        assertEquals(1, LogReach.row(2, 3, 4));
    }

    @Test
    public void theClimbEndsInTheOpenOrGivesUpAfterItsCap() {
        // in the open: over
        assertEquals(LogReach.Step.ARRIVED, LogReach.step(60, 70, 2, 0, 100));
        // on the way up
        assertEquals(LogReach.Step.ON, LogReach.step(40, 70, 30, 0, 100));
        // at the height it walked to, in a cave under a hill: a new height from the sky over here, or over when shallow
        assertEquals(LogReach.Step.RETARGET, LogReach.step(70, 70, 20, 0, 100));
        assertEquals(LogReach.Step.ARRIVED, LogReach.step(70, 70, 8, 0, 100));
        // past three game minutes, given up; a clock that went back (another world) too
        assertEquals(LogReach.Step.GAVE_UP, LogReach.step(40, 70, 30, 0, LogReach.CLIMB_CAP + 1));
        assertEquals(LogReach.Step.ON, LogReach.step(40, 70, 30, 0, LogReach.CLIMB_CAP));
        assertEquals(LogReach.Step.GAVE_UP, LogReach.step(40, 70, 30, 500, 100));
    }
}
