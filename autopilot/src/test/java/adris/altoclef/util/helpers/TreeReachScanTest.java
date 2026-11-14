package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the two clocks of TreeReachScan, which start at Integer.MIN_VALUE: before 14 Nov 2026 now - Integer.MIN_VALUE overflowed,
// the dirt look never ran and the look cache was never cleared
public class TreeReachScanTest {
    @Test
    public void theDirtLookRunsOnItsFirstAsk() {
        assertTrue(TreeReachScan.dirtLookDue(0, Integer.MIN_VALUE));
        assertTrue(TreeReachScan.dirtLookDue(1, Integer.MIN_VALUE));
        assertTrue(TreeReachScan.dirtLookDue(123_456, Integer.MIN_VALUE));
        assertTrue(TreeReachScan.dirtLookDue(Integer.MAX_VALUE, Integer.MIN_VALUE));
    }

    @Test
    public void theDirtLookRunsOnceEveryFiveSeconds() {
        assertFalse(TreeReachScan.dirtLookDue(1000, 1000));
        assertFalse(TreeReachScan.dirtLookDue(1099, 1000));
        assertTrue(TreeReachScan.dirtLookDue(1100, 1000));
        // the clock went back: look again
        assertTrue(TreeReachScan.dirtLookDue(999, 1000));
    }

    @Test
    public void theLookCacheIsClearedOnItsFirstAsk() {
        assertTrue(TreeReachScan.looksStale(0, Integer.MIN_VALUE));
        assertTrue(TreeReachScan.looksStale(1, Integer.MIN_VALUE));
        assertTrue(TreeReachScan.looksStale(123_456, Integer.MIN_VALUE));
        assertTrue(TreeReachScan.looksStale(Integer.MAX_VALUE, Integer.MIN_VALUE));
    }

    @Test
    public void theLookCacheLastsTwoSeconds() {
        assertFalse(TreeReachScan.looksStale(1000, 1000));
        assertFalse(TreeReachScan.looksStale(1039, 1000));
        assertTrue(TreeReachScan.looksStale(1040, 1000));
        assertTrue(TreeReachScan.looksStale(999, 1000));
    }
}
