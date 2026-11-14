package adris.altoclef.tasks.misc;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// (Tuning bag_keep_stations, table_back_on_cut, cut_parent_hold, stations_back_cap) when a station a craft or smelt put down
// goes back in the bag
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

    // (cut_parent_hold) look 96: the furnace craft cut by its smelt's fuel trip, the smelt still running
    @Test
    public void aCutTableWaitsWhileTheCraftsParentRuns() {
        long cut = 1000;
        // the smelt went for fuel and the furnace is not made: the table stays
        assertTrue(StationsBack.parentHolds(cut, cut + 1, true, false));
        assertTrue(StationsBack.parentHolds(cut, cut + StationsBack.PARENT_HOLD_TICKS - 1, true, false));
        // the furnace made (in the bag), the smelt over, or three game minutes gone: the table may go back
        assertFalse(StationsBack.parentHolds(cut, cut + 200, true, true));
        assertFalse(StationsBack.parentHolds(cut, cut + 200, false, false));
        assertFalse(StationsBack.parentHolds(cut, cut + StationsBack.PARENT_HOLD_TICKS, true, false));
        // a clock before the cut holds nothing
        assertFalse(StationsBack.parentHolds(cut, cut - 1, true, false));
        // the hold ends with time left in the cut's keep to take the table back
        assertTrue(StationsBack.PARENT_HOLD_TICKS < StationsBack.CUT_KEEP_TICKS);
    }

    // (stations_back_cap) the same table taken back and put down again, every few seconds for 25 minutes in look 96
    @Test
    public void twoTakeBacksAtOneSpotInAMinuteThenTheTableStays() {
        List<StationsBack.Take> takes = new ArrayList<>();
        String ow = "OVERWORLD";
        long t = 5000;
        assertFalse(StationsBack.capped(takes, 10, 64, 10, ow, t));
        takes.add(new StationsBack.Take(10, 64, 10, ow, t));
        assertFalse(StationsBack.capped(takes, 10, 64, 10, ow, t + 100));
        // put down a block over the second time: the same spot
        takes.add(new StationsBack.Take(11, 64, 10, ow, t + 100));
        assertEquals(2, StationsBack.takesAt(takes, 10, 64, 11, ow, t + 200));
        assertTrue(StationsBack.capped(takes, 10, 64, 11, ow, t + 200));
        // another spot, another dimension, or the minute gone: not capped
        assertFalse(StationsBack.capped(takes, 20, 64, 10, ow, t + 200));
        assertFalse(StationsBack.capped(takes, 10, 64, 10, "NETHER", t + 200));
        assertEquals(1, StationsBack.takesAt(takes, 10, 64, 10, ow, t + StationsBack.CAP_WINDOW_TICKS + 1));
        assertFalse(StationsBack.capped(takes, 10, 64, 10, ow, t + StationsBack.CAP_WINDOW_TICKS + 1));
        // the spot is 4 blocks across
        assertTrue(StationsBack.sameSpot(takes.get(0), 14, 64, 10, ow));
        assertFalse(StationsBack.sameSpot(takes.get(0), 15, 64, 10, ow));
    }

    // (root_done_table) a task done without a stop: the craft nearest the leaf with a table of its own is the one reported
    @Test
    public void aDoneTaskReportsTheTableOfItsDeepestCraft() {
        assertEquals("craft2", StationsBack.deepestOwnTable(List.of("root", "craft1", "craft2", "click"), x -> x.startsWith("craft")));
        assertNull(StationsBack.deepestOwnTable(List.of("root", "click"), x -> x.startsWith("craft")));
    }
}
