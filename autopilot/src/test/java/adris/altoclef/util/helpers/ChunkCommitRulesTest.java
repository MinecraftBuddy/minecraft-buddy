package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.ChunkCommitRules.Clock;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChunkCommitRulesTest {

    @Test
    public void theTargetIsKeptWhileItIsStillToVisit() {
        assertTrue(ChunkCommitRules.keep(true, true, false, false));
    }

    // it loads (searched from here), leaves the list, or was given up: pick anew. and with no target there is nothing to keep
    @Test
    public void aNewPickOnlyWhenTheTargetIsDoneWith() {
        assertFalse(ChunkCommitRules.keep(true, true, false, true));
        assertFalse(ChunkCommitRules.keep(true, false, false, false));
        assertFalse(ChunkCommitRules.keep(true, true, true, false));
        assertFalse(ChunkCommitRules.keep(false, false, false, false));
    }

    @Test
    public void eightBlocksCloserThanTheMarkIsProgress() {
        assertEquals(Clock.PROGRESS, ChunkCommitRules.clock(142, 150, 1));
        assertEquals(Clock.PROGRESS, ChunkCommitRules.clock(100, 150, 899));
        assertEquals(Clock.WAIT, ChunkCommitRules.clock(142.1, 150, 1));
        // farther off counts as no progress, not as a reason to drop it sooner
        assertEquals(Clock.WAIT, ChunkCommitRules.clock(170, 150, 899));
    }

    @Test
    public void nineHundredTicksWithoutProgressGiveTheTargetUp() {
        assertEquals(Clock.WAIT, ChunkCommitRules.clock(148, 150, ChunkCommitRules.GIVE_UP_TICKS - 1));
        assertEquals(Clock.GIVE_UP, ChunkCommitRules.clock(148, 150, ChunkCommitRules.GIVE_UP_TICKS));
        assertEquals(Clock.GIVE_UP, ChunkCommitRules.clock(170, 150, ChunkCommitRules.GIVE_UP_TICKS + 40));
        // progress on the last tick still counts
        assertEquals(Clock.PROGRESS, ChunkCommitRules.clock(141, 150, ChunkCommitRules.GIVE_UP_TICKS));
    }

    // the stall the rule is for: pacing within 5 blocks some 150 off, the target given up after 45 s
    @Test
    public void pacingOnTheSpotIsGivenUpAfterFortyFiveSeconds() {
        assertEquals(900, run(150, tick -> 150 + 5 * Math.sin(tick / 20.0), 5000));
    }

    // walking at a slow 2 blocks a second, 150 blocks off: progress every 4 s, never given up
    @Test
    public void aSlowWalkTowardsTheTargetIsNeverGivenUp() {
        assertEquals(-1, run(150, tick -> Math.max(0, 150 - tick / 10.0), 1500));
    }

    // a detour of 40 s away from the target and back still makes it within the clock
    @Test
    public void aDetourShorterThanTheClockIsKept() {
        assertEquals(-1, run(150, tick -> tick < 400 ? 150 + tick / 10.0 : 190 - (tick - 400) / 5.0, 1100));
    }

    @Test
    public void distanceIsToTheChunksCentreInXZ() {
        assertEquals(0, ChunkCommitRules.distanceXZ(8, 8, 0, 0), 1e-9);
        assertEquals(0, ChunkCommitRules.distanceXZ(-8, -8, -1, -1), 1e-9);
        assertEquals(5, ChunkCommitRules.distanceXZ(14200 + 3, 1080 + 4, 887, 67), 1e-9);
        assertEquals(16, ChunkCommitRules.distanceXZ(14200, 1080, 888, 67), 1e-9);
    }

    // the clock as the search keeps it: the mark at the commit's distance, one tick counted per search tick. the tick the
    // target is given up, or -1
    private static long run(double start, java.util.function.LongToDoubleFunction distance, long ticks) {
        double best = start;
        long since = 0;
        for (long tick = 1; tick <= ticks; tick++) {
            double d = distance.applyAsDouble(tick);
            since++;
            switch (ChunkCommitRules.clock(d, best, since)) {
                case PROGRESS -> {
                    best = d;
                    since = 0;
                }
                case GIVE_UP -> {
                    return tick;
                }
                case WAIT -> {
                }
            }
        }
        return -1;
    }

    // chunk_search_keep: a restart in the same dimension with something gathered keeps it; off, another dimension or
    // nothing gathered starts empty

    @Test
    public void aRestartKeepsTheSearchOnlyWithTheSwitchInTheSameDimension() {
        assertTrue(ChunkCommitRules.keepOnRestart(true, true, true, false));
        assertTrue(ChunkCommitRules.keepOnRestart(true, true, false, true));
        assertFalse(ChunkCommitRules.keepOnRestart(false, true, true, true));
        assertFalse(ChunkCommitRules.keepOnRestart(true, false, true, true));
        assertFalse(ChunkCommitRules.keepOnRestart(true, true, false, false));
    }
}
