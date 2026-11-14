package adris.altoclef.takealong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.junit.Test;

// (take_along_falling_ban) a live look at the test heel: a gravel column at a pond's edge, each detour for flint out of time,
// its blocks banned one by one, the gravel that fell in from above began the next detour at the same place
public class FallBansTest {
    @Test
    public void aGivenUpBoxIsBannedByColumnAtAnyHeight() {
        FallBans b = new FallBans();
        assertEquals(12, b.add("OVERWORLD", 10, -5, 12, -2, 1000));
        // gravel that fell in from above, at any height, and the box's own ends
        assertTrue(b.banned("OVERWORLD", 10, -5, 500));
        assertTrue(b.banned("OVERWORLD", 12, -2, 999));
        // just outside, another dimension, or after the ban's time
        assertFalse(b.banned("OVERWORLD", 13, -2, 500));
        assertFalse(b.banned("OVERWORLD", 11, -6, 500));
        assertFalse(b.banned("NETHER", 11, -3, 500));
        assertFalse(b.banned("OVERWORLD", 11, -3, 1000));
        assertEquals(0, b.size());
        // the ends in either order
        assertEquals(4, b.add("OVERWORLD", 3, 3, 2, 2, 50));
        assertTrue(b.banned("OVERWORLD", 2, 3, 10));
    }

    @Test
    public void outOfTimeLedTooFarOrCappedWithNothing() {
        assertTrue(FallBans.areaBan(true, true, "timeout", 0));
        assertTrue(FallBans.areaBan(true, true, "strayed", 1));
        assertTrue(FallBans.areaBan(true, true, "capped", 0));
        // capped with a flint got, done, blocked: no area
        assertFalse(FallBans.areaBan(true, true, "capped", 1));
        assertFalse(FallBans.areaBan(true, true, "done", 0));
        assertFalse(FallBans.areaBan(true, true, "blocked", 0));
        // not a falling kind, or the key off
        assertFalse(FallBans.areaBan(true, false, "timeout", 0));
        assertFalse(FallBans.areaBan(false, true, "timeout", 0));
    }

    private static FallBans.Water waterAt(int[]... cells) {
        Set<String> wet = new java.util.HashSet<>();
        for (int[] c : cells) {
            wet.add(c[0] + "," + c[1] + "," + c[2]);
        }
        return (x, y, z) -> wet.contains(x + "," + y + "," + z);
    }

    @Test
    public void gravelWhoseFlintWouldLandInWater() {
        // dry all round
        assertFalse(FallBans.dropWet(waterAt(), 0, 64, 0));
        // a side, above, under
        assertTrue(FallBans.dropWet(waterAt(new int[]{1, 64, 0}), 0, 64, 0));
        assertTrue(FallBans.dropWet(waterAt(new int[]{0, 65, 0}), 0, 64, 0));
        assertTrue(FallBans.dropWet(waterAt(new int[]{0, 63, 0}), 0, 64, 0));
        // beside the cell under it: the pond's edge the drop rolls into
        assertTrue(FallBans.dropWet(waterAt(new int[]{0, 63, -1}), 0, 64, 0));
        // a corner or two down is no matter
        assertFalse(FallBans.dropWet(waterAt(new int[]{1, 63, 1}), 0, 64, 0));
        assertFalse(FallBans.dropWet(waterAt(new int[]{0, 62, 1}), 0, 64, 0));
    }
}
