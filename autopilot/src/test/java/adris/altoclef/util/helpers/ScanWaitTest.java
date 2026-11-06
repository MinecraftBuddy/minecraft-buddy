package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScanWaitTest {
    @Test
    public void aScanThatIsNotOwedNeverHolds() {
        ScanWait w = new ScanWait();
        assertFalse(w.waiting(100, false));
    }

    @Test
    public void theWaitEndsTheTickTheScanLands() {
        ScanWait w = new ScanWait();
        assertTrue(w.waiting(100, true));
        assertTrue(w.waiting(105, true));
        assertFalse(w.waiting(106, false));
    }

    @Test
    public void aStuckScanHoldsForASecondAndNoMore() {
        assertEquals(20, ScanWait.MAX_TICKS);
        ScanWait w = new ScanWait();
        assertTrue(w.waiting(100, true));
        assertTrue(w.waiting(100 + ScanWait.MAX_TICKS - 1, true));
        assertFalse(w.waiting(100 + ScanWait.MAX_TICKS, true));
        assertFalse(w.waiting(500, true));
    }

    @Test
    public void theNextNewTypeGetsAFreshWait() {
        ScanWait w = new ScanWait();
        w.waiting(100, true);
        assertFalse(w.waiting(100 + ScanWait.MAX_TICKS, true));
        // the scan landed, then something new got tracked later on
        w.waiting(200, false);
        assertTrue(w.waiting(300, true));
        // and a reset (a new search) starts over too
        w.clear();
        assertTrue(w.waiting(1000, true));
    }
}
