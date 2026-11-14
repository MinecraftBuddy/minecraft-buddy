package adris.altoclef.tasks.misc;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// (Tuning bag_keep_stations, table_back_on_cut) when a station a craft or smelt put down goes back in the bag
public class StationsBackTest {

    @Test
    public void aDoneOneIsTakenFromNearAndSoon() {
        assertTrue(StationsBack.due(1000, 1000 + StationsBack.KEEP_TICKS, StationsBack.NEAR));
        assertFalse(StationsBack.due(1000, 1001 + StationsBack.KEEP_TICKS, 1));
        assertFalse(StationsBack.due(1000, 1100, StationsBack.NEAR + 1));
    }

    @Test
    public void aCutCraftsTableWaitsForTheCraftThenComesBack() {
        long cut = 1000;
        // a fight holds the craft (paused, still active): the table waits for it
        assertFalse(StationsBack.cutDue(cut, cut + 200, 5, true, false));
        // the craft is over: taken back, from further and later than a done one's
        assertTrue(StationsBack.cutDue(cut, cut + 200, 5, false, false));
        assertTrue(StationsBack.cutDue(cut, cut + StationsBack.CUT_KEEP_TICKS, StationsBack.CUT_NEAR, false, false));
        assertTrue(StationsBack.cutDue(cut, cut + 2 * StationsBack.KEEP_TICKS, StationsBack.NEAR + 10, false, false));
        // too far, too late, or a table in the bag already
        assertFalse(StationsBack.cutDue(cut, cut + 200, StationsBack.CUT_NEAR + 1, false, false));
        assertFalse(StationsBack.cutDue(cut, cut + StationsBack.CUT_KEEP_TICKS + 1, 5, false, false));
        assertFalse(StationsBack.cutDue(cut, cut + 200, 5, false, true));
        // kept for later while in time, whatever the distance
        assertTrue(StationsBack.cutKept(cut, cut + StationsBack.CUT_KEEP_TICKS));
        assertFalse(StationsBack.cutKept(cut, cut + StationsBack.CUT_KEEP_TICKS + 1));
        assertFalse(StationsBack.cutKept(cut, cut - 1));
    }
}
