package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// spawner_wait_cap's and lure_cap's rules (BlazeCapRules): when the wait for a wither skeleton pack at the spawner is over,
// when a lure that draws no blaze into reach is given up, and when the pack line is said again
public class BlazeCapRulesTest {
    private static final long SPAWNER = 11L;
    private static final long OTHER = 12L;

    @Test
    public void secondsAreTicksAndNoneIsOff() {
        assertEquals(0, BlazeCapRules.ticks(0));
        assertEquals(0, BlazeCapRules.ticks(-5));
        assertEquals(1800, BlazeCapRules.ticks(90));
    }

    @Test
    public void theWaitHoldsTillTheCapThenIsOverAtThatSpawner() {
        BlazeCapRules.SpawnerWait w = new BlazeCapRules.SpawnerWait();
        for (long t = 1000; t < 1004; t++) assertEquals(BlazeCapRules.Wait.HOLD, w.tick(SPAWNER, t, 5));
        assertEquals(BlazeCapRules.Wait.CAP, w.tick(SPAWNER, 1004, 5));
        // (said once, then over for good at this spawner, the pack still there or back)
        assertEquals(BlazeCapRules.Wait.PAST, w.tick(SPAWNER, 1005, 5));
        assertEquals(BlazeCapRules.Wait.PAST, w.tick(SPAWNER, 9000, 5));
    }

    @Test
    public void theWaitCountsItsTicksInAllAcrossGaps() {
        // (the count crosses three and back by the second: the walk takes the ticks between, the waits add up)
        BlazeCapRules.SpawnerWait w = new BlazeCapRules.SpawnerWait();
        assertEquals(BlazeCapRules.Wait.HOLD, w.tick(SPAWNER, 1000, 4));
        assertEquals(BlazeCapRules.Wait.HOLD, w.tick(SPAWNER, 1001, 4));
        assertEquals(BlazeCapRules.Wait.HOLD, w.tick(SPAWNER, 1500, 4));
        // (a second ask in the same game tick is the same tick)
        assertEquals(BlazeCapRules.Wait.HOLD, w.tick(SPAWNER, 1500, 4));
        assertEquals(BlazeCapRules.Wait.CAP, w.tick(SPAWNER, 3000, 4));
        assertEquals(4, w.ticks(SPAWNER));
    }

    @Test
    public void anotherSpawnerWaitsAfresh() {
        BlazeCapRules.SpawnerWait w = new BlazeCapRules.SpawnerWait();
        w.tick(SPAWNER, 1000, 2);
        assertEquals(BlazeCapRules.Wait.CAP, w.tick(SPAWNER, 1001, 2));
        assertEquals(BlazeCapRules.Wait.HOLD, w.tick(OTHER, 1002, 2));
        assertEquals(0, w.ticks(SPAWNER));
        assertEquals(1, w.ticks(OTHER));
    }

    @Test
    public void withTheSwitchOffTheWaitHoldsForEver() {
        BlazeCapRules.SpawnerWait w = new BlazeCapRules.SpawnerWait();
        for (long t = 0; t < 20000; t++) assertEquals(BlazeCapRules.Wait.HOLD, w.tick(SPAWNER, t, 0));
    }

    @Test
    public void theWaitsOpeningLineOncePerSpawner() {
        BlazeCapRules.SpawnerWait w = new BlazeCapRules.SpawnerWait();
        assertFalse(w.firstLine(SPAWNER));
        w.tick(SPAWNER, 1000, 100);
        assertTrue(w.firstLine(SPAWNER));
        w.tick(SPAWNER, 1001, 100);
        assertFalse(w.firstLine(SPAWNER));
        w.tick(OTHER, 1002, 100);
        assertTrue(w.firstLine(OTHER));
    }

    @Test
    public void aLureThatDrawsNothingRunsOutAtTheCap() {
        BlazeCapRules.LureWatch w = new BlazeCapRules.LureWatch();
        for (long t = 1000; t < 1009; t++) assertFalse(w.tick(SPAWNER, t, Long.MIN_VALUE, 10));
        assertTrue(w.tick(SPAWNER, 1009, Long.MIN_VALUE, 10));
        assertEquals(10, w.ticks());
    }

    @Test
    public void aBlazeInReachStartsTheClockAgain() {
        BlazeCapRules.LureWatch w = new BlazeCapRules.LureWatch();
        for (long t = 1000; t < 1008; t++) assertFalse(w.tick(SPAWNER, t, Long.MIN_VALUE, 10));
        // (the fight went after one at 1010, the lure had no wheel then; back at 1020 the count starts again)
        assertFalse(w.tick(SPAWNER, 1020, 1010, 10));
        assertEquals(1, w.ticks());
        for (long t = 1021; t < 1029; t++) assertFalse(w.tick(SPAWNER, t, 1010, 10));
        assertTrue(w.tick(SPAWNER, 1029, 1010, 10));
    }

    @Test
    public void aKillBeforeTheLureBeganDoesNotCount() {
        BlazeCapRules.LureWatch w = new BlazeCapRules.LureWatch();
        assertFalse(w.tick(SPAWNER, 1000, 900, 3));
        assertFalse(w.tick(SPAWNER, 1001, 900, 3));
        assertTrue(w.tick(SPAWNER, 1002, 900, 3));
    }

    @Test
    public void onlyTheLuresOwnTicksCountAndAnotherCellIsAfresh() {
        BlazeCapRules.LureWatch w = new BlazeCapRules.LureWatch();
        assertFalse(w.tick(SPAWNER, 1000, Long.MIN_VALUE, 3));
        assertFalse(w.tick(SPAWNER, 1000, Long.MIN_VALUE, 3));
        // (a long time away, hiding or healing, is not the lure's time)
        assertFalse(w.tick(SPAWNER, 5000, Long.MIN_VALUE, 3));
        assertFalse(w.tick(OTHER, 5001, Long.MIN_VALUE, 3));
        assertEquals(1, w.ticks());
        w.reset();
        assertEquals(0, w.ticks());
    }

    @Test
    public void withTheSwitchOffTheLureNeverRunsOut() {
        BlazeCapRules.LureWatch w = new BlazeCapRules.LureWatch();
        for (long t = 0; t < 20000; t++) assertFalse(w.tick(SPAWNER, t, Long.MIN_VALUE, 0));
    }

    @Test
    public void thePackLineOnlyOnAChange() {
        // (the first one; then the same pack from the same spot is quiet, a new size, verdict or spot is said)
        assertTrue(BlazeCapRules.packLine(3, false, Double.POSITIVE_INFINITY, -1, false, 12));
        assertFalse(BlazeCapRules.packLine(3, false, 1.0, 3, false, 12));
        assertTrue(BlazeCapRules.packLine(4, false, 1.0, 3, false, 12));
        assertTrue(BlazeCapRules.packLine(3, true, 1.0, 3, false, 12));
        assertTrue(BlazeCapRules.packLine(3, false, 12.5, 3, false, 12));
    }
}
