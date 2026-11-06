package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PearlHuntWalkTest {
    @Test
    public void steadyProgressNeverStallsBeforeTheCap() {
        double best = Double.MAX_VALUE;
        double stall = 0;
        double dist = 600;
        // 3 blocks every second for 190 s: the old flat clock would have quit at 150
        for (int t = 1; t <= 190; t++) {
            dist -= 3;
            if (PearlHuntTask.walkProgressed(dist, best)) {
                best = dist;
                stall = 0;
            }
            stall++;
            assertFalse(PearlHuntTask.walkOver(stall, t));
        }
    }

    @Test
    public void halfBlockCreepsDoNotCount() {
        assertTrue(PearlHuntTask.walkProgressed(100, 105));
        assertFalse(PearlHuntTask.walkProgressed(104.5, 105));
        assertTrue(PearlHuntTask.walkProgressed(103, 105));
    }

    @Test
    public void fortySecondsWithoutProgressEndsTheWalk() {
        assertFalse(PearlHuntTask.walkOver(40, 100));
        assertTrue(PearlHuntTask.walkOver(40.05, 100));
    }

    @Test
    public void theHardCapEndsAWalkThatKeepsCreeping() {
        assertFalse(PearlHuntTask.walkOver(0, 480));
        assertTrue(PearlHuntTask.walkOver(0, 480.05));
    }

    @Test
    public void driftingOutReopensOnlyAfterArriving() {
        assertTrue(PearlHuntTask.driftedOut(true, false, 121));
        assertFalse(PearlHuntTask.driftedOut(true, false, 119));
        assertFalse(PearlHuntTask.driftedOut(false, false, 500));
        assertFalse(PearlHuntTask.driftedOut(true, true, 500));
    }

    @Test
    public void thereMeansTwentyFourFlatAndEightUp() {
        assertTrue(PearlHuntTask.atForest(24, 0, 0));
        assertTrue(PearlHuntTask.atForest(0, -8, 0));
        assertFalse(PearlHuntTask.atForest(24.5, 0, 0));
        assertFalse(PearlHuntTask.atForest(0, 8.5, 0));
    }

    @Test
    public void onlyALongGapCountsAsAway() {
        assertFalse(PearlHuntTask.awayLong(Long.MIN_VALUE, 5000));
        assertFalse(PearlHuntTask.awayLong(1000, 1200));
        assertFalse(PearlHuntTask.awayLong(1000, 1600));
        assertTrue(PearlHuntTask.awayLong(1000, 1601));
    }
}
