package baritone.behavior.trip;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TripMathTest {

    @Test
    public void deficitCountsWhatWeHold() {
        assertEquals(0, TripMath.deficit(0, 0));
        assertEquals(0, TripMath.deficit(-3, 10));
        // 6 placements + 2 spare
        assertEquals(8, TripMath.deficit(6, 0));
        assertEquals(3, TripMath.deficit(6, 5));
        assertEquals(0, TripMath.deficit(6, 8));
        assertEquals(0, TripMath.deficit(6, 64));
        // 100 + 2 + 10
        assertEquals(112, TripMath.deficit(100, 0));
        assertEquals(8, TripMath.deficit(6, -4));
    }

    @Test
    public void gatherTicks() {
        assertEquals(400, TripMath.gatherTicks(10, false, 40, 2400), 0);
        assertEquals(2800, TripMath.gatherTicks(10, true, 40, 2400), 0);
        assertEquals(2400, TripMath.gatherTicks(0, true, 40, 2400), 0);
        assertEquals(0, TripMath.gatherTicks(-5, false, 40, 2400), 0);
    }

    @Test
    public void bothThresholdsHaveToHold() {
        // 10 minutes walking, 3 minutes the other way plus a minute of digging: 6 min saved, 60%
        assertTrue(TripMath.worthAsking(12000, 3600, 1200, 30, 30));
        // a third faster on a 40 s walk is 13 s, under 30 s
        assertFalse(TripMath.worthAsking(800, 500, 0, 30, 30));
        // 40 s saved on an hour, way under 30%
        assertFalse(TripMath.worthAsking(72000, 71200, 0, 30, 30));
        // exactly on both lines counts
        assertTrue(TripMath.worthAsking(2000, 1400, 0, 30, 30));
        assertFalse(TripMath.worthAsking(2000, 1401, 0, 30, 30));
    }

    @Test
    public void theGatherCountsAgainstTheFasterPath() {
        assertTrue(TripMath.worthAsking(12000, 3000, 0, 30, 30));
        // the same path with ten minutes of digging first isn't faster any more
        assertFalse(TripMath.worthAsking(12000, 3000, 12000, 30, 30));
        // a pickaxe from scratch eats most of a small win
        double gather = TripMath.gatherTicks(4, true, 40, 2400);
        assertFalse(TripMath.worthAsking(6000, 3000, gather, 30, 30));
    }

    @Test
    public void nonsenseIsNeverWorthIt() {
        assertFalse(TripMath.worthAsking(Double.POSITIVE_INFINITY, 100, 0, 30, 30));
        assertFalse(TripMath.worthAsking(1000, Double.NaN, 0, 30, 30));
        assertFalse(TripMath.worthAsking(0, 0, 0, 0, 0));
        assertFalse(TripMath.worthAsking(1000, 1000, 0, 0, 0));
        // zero thresholds still want it to actually be faster
        assertTrue(TripMath.worthAsking(1000, 999, 0, 0, 0));
    }

    @Test
    public void progressMeansGettingSomewhere() {
        assertTrue(TripMath.madeProgress(true, 100, 100, 3.563));
        assertTrue(TripMath.madeProgress(false, 1000, 1000 - 5 * 3.563, 3.563));
        assertFalse(TripMath.madeProgress(false, 1000, 1000 - 4 * 3.563, 3.563));
        // ending further away is no progress at all
        assertFalse(TripMath.madeProgress(false, 100, 200, 3.563));
        assertFalse(TripMath.madeProgress(false, Double.NaN, 0, 3.563));
    }

    @Test
    public void planText() {
        assertEquals("about 8 blocks", new GatherPlan(Trip.Ask.STUCK, 6, 8, false).describe());
        assertEquals("about 1 block", new GatherPlan(Trip.Ask.STUCK, 1, 1, false).describe());
        assertEquals("about 8 blocks and a stone pickaxe", new GatherPlan(Trip.Ask.FASTER, 6, 8, true).describe());
        assertEquals("a stone pickaxe", new GatherPlan(Trip.Ask.FASTER, 0, 0, true).describe());
        assertTrue(new GatherPlan(Trip.Ask.STUCK, 6, 0, false).isEmpty());
        assertFalse(new GatherPlan(Trip.Ask.FASTER, 0, 0, true).isEmpty());
    }
}
