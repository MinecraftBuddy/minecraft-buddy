package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CaveNotch.Kind;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

// (cave_notch_cover) the cell a notch is dug into: the nearest cave wall within 3 of the feet, two cells that dig, a floor under
// it and under its opening, solid walls and top that stay put, and no fluid or open cell behind them
public class CaveNotchTest {

    // a cave: solid stone that digs everywhere, an open room x -10..2, y 64..66, z -3..3 on a stone floor (y 63); the wall that
    // faces the room is at x 3
    private static final class Cave implements CaveNotch.Grid {
        final Map<Long, Kind> set = new HashMap<>();
        int roomMaxX = 2;

        @Override
        public Kind at(int x, int y, int z) {
            Kind k = set.get(key(x, y, z));
            if (k != null) return k;
            boolean room = x >= -10 && x <= roomMaxX && y >= 64 && y <= 66 && z >= -3 && z <= 3;
            return room ? Kind.OPEN : Kind.DIG;
        }

        Cave put(int x, int y, int z, Kind k) {
            set.put(key(x, y, z), k);
            return this;
        }

        private static long key(int x, int y, int z) {
            return ((long) x * 4096 + y) * 4096 + z;
        }
    }

    @Test
    public void theNearestWallIsDugIntoFacingTheRoom() {
        // the feet at x 1, a block from the wall at x 3: the notch at x 3, its opening at x 2
        CaveNotch.Notch n = CaveNotch.pick(new Cave(), 1, 64, 0);
        assertNotNull(n);
        assertEquals(3, n.x());
        assertEquals(64, n.y());
        assertEquals(0, n.z());
        assertEquals(-1, n.dx());
        assertEquals(0, n.dz());
        // standing right at the wall: the opening is the feet's own cell
        n = CaveNotch.pick(new Cave(), 2, 64, 1);
        assertEquals(2, n.opening()[0]);
        assertEquals(1, n.opening()[2]);
    }

    @Test
    public void noWallWithinThreeIsNoNotch() {
        // the middle of the room: the walls 4 off and more (x 3 from x -2, z 4 from z 0)
        assertNull(CaveNotch.pick(new Cave(), -2, 64, 0));
    }

    @Test
    public void fluidOrAnOpenCellBehindAWallIsNoNotch() {
        Cave c = new Cave();
        assertTrue(CaveNotch.fits(c, 3, 64, 0, -1, 0));
        // lava behind the side wall at the head's height
        c.put(3, 65, 2, Kind.FLUID);
        assertFalse(CaveNotch.fits(c, 3, 64, 0, -1, 0));
        // another cave behind the back wall: a thin wall
        c = new Cave().put(5, 64, 0, Kind.OPEN);
        assertFalse(CaveNotch.fits(c, 3, 64, 0, -1, 0));
        // water over the top
        c = new Cave().put(3, 67, 0, Kind.FLUID);
        assertFalse(CaveNotch.fits(c, 3, 64, 0, -1, 0));
        // the room's air reaches the cell's other side: no wall there
        c = new Cave().put(3, 64, 1, Kind.OPEN);
        assertFalse(CaveNotch.fits(c, 3, 64, 0, -1, 0));
    }

    @Test
    public void cellsThatWillNotDigOrFallAreNoNotch() {
        // an ore (or stone with no pick) in the head's cell: a wall, not a cell to dig
        assertFalse(CaveNotch.fits(new Cave().put(3, 65, 0, Kind.WALL), 3, 64, 0, -1, 0));
        // gravel over the head would fall in: the top must stay put
        assertFalse(CaveNotch.fits(new Cave().put(3, 66, 0, Kind.LOOSE), 3, 64, 0, -1, 0));
        // an ore beside it is a wall all the same
        assertTrue(CaveNotch.fits(new Cave().put(3, 64, 1, Kind.WALL), 3, 64, 0, -1, 0));
        // no floor under the opening (a drop): no step in
        assertFalse(CaveNotch.fits(new Cave().put(2, 63, 0, Kind.OPEN), 3, 64, 0, -1, 0));
        // no floor under the notch
        assertFalse(CaveNotch.fits(new Cave().put(3, 63, 0, Kind.OPEN), 3, 64, 0, -1, 0));
    }

    @Test
    public void aRefusedCellLeavesTheNextNearest() {
        // lava behind the back wall of the cell straight ahead: the notch moves a cell along the wall, the fewest steps after it
        Cave c = new Cave().put(5, 65, 0, Kind.FLUID);
        assertFalse(CaveNotch.fits(c, 3, 64, 0, -1, 0));
        CaveNotch.Notch n = CaveNotch.pick(c, 1, 64, 0);
        assertNotNull(n);
        assertEquals(3, n.x());
        assertEquals(64, n.y());
        assertEquals(1, Math.abs(n.z()));
        assertTrue(CaveNotch.fits(c, n.x(), n.y(), n.z(), n.dx(), n.dz()));
        // lava only beside a wall, not behind it, is no danger: nothing the dig opens touches it
        assertTrue(CaveNotch.fits(new Cave().put(4, 65, 1, Kind.FLUID), 3, 64, 0, -1, 0));
    }

    @Test
    public void theFeetsCellOnlyWhenNeeded() {
        assertFalse(CaveNotch.feetToo(false, false, false));
        assertTrue(CaveNotch.feetToo(true, false, false));
        assertTrue(CaveNotch.feetToo(false, true, false));
        assertTrue(CaveNotch.feetToo(false, false, true));
    }

    @Test
    public void offByDefault() {
        assertEquals(0.0, Tuning.DEFAULTS.get("cave_notch_cover"), 0);
        assertEquals(1.0, Tuning.DEFAULTS.get("brew_box_bag_check"), 0);
        assertEquals(1.0, Tuning.DEFAULTS.get("nether_keep_rack"), 0);
    }
}
