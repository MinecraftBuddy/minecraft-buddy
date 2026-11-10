package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlaceWordTest {
    // feet at y = 0. by default solid below y = 0 and open from y = 0 up; a column can be set to anything, cell by cell
    private static final class World implements Cells {
        final Map<Long, Cell> cells = new HashMap<>();

        static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | ((long) z & 0xFFFFF);
        }

        World set(int x, int y, int z, Cell c) {
            cells.put(key(x, y, z), c);
            return this;
        }

        // the column under (x, z) open from y = -1 down to depth, then lava there
        World lavaPit(int x, int z, int depth) {
            for (int y = -1; y > -depth; y--) set(x, y, z, Cell.OPEN);
            return set(x, -depth, z, Cell.LAVA);
        }

        // the column under (x, z) open from y = -1 down to depth, then solid
        World pit(int x, int z, int depth) {
            for (int y = -1; y >= -depth; y--) set(x, y, z, Cell.OPEN);
            return this;
        }

        World roof(int x, int z, int y) {
            return set(x, y, z, Cell.SOLID);
        }

        @Override
        public Cell at(int x, int y, int z) {
            Cell c = cells.get(key(x, y, z));
            if (c != null) return c;
            return y < 0 ? Cell.SOLID : Cell.OPEN;
        }
    }

    @Test
    public void flatGroundUnderTheSkyIsOpen() {
        assertEquals("open", PlaceWord.of(new World(), false, 0, 0, 0));
    }

    @Test
    public void aRoofWithinReachIsACave() {
        assertEquals("cave", PlaceWord.of(new World().roof(0, 0, 4), false, 0, 0, 0));
        // a roof far up is still the open sky
        assertEquals("open", PlaceWord.of(new World().roof(0, 0, PlaceWord.OPEN_HEIGHT + 2), false, 0, 0, 0));
    }

    @Test
    public void aBridgeOverTheLavaSeaIsSea() {
        World w = new World();
        // our own floor at y = -1, and under it nothing but air down to the lava 30 down
        for (int y = -2; y > -30; y--) w.set(0, y, 0, Cell.OPEN);
        w.set(0, -30, 0, Cell.LAVA);
        assertEquals("sea", PlaceWord.of(w, false, 0, 0, 0));
        // fortress brick over the same sea is a fortress bridge, priced as one
        w.pit(1, 0, 20);
        assertEquals("fort_rim", PlaceWord.of(w, true, 0, 0, 0));
    }

    @Test
    public void aDeepDropWithinAKnockIsARim() {
        assertEquals("rim", PlaceWord.of(new World().pit(2, 0, 12), false, 0, 0, 0));
        // three blocks off is beyond a knock
        assertEquals("open", PlaceWord.of(new World().pit(3, 0, 12), false, 0, 0, 0));
        // a short step down is no rim
        assertEquals("open", PlaceWord.of(new World().pit(1, 0, 3), false, 0, 0, 0));
    }

    @Test
    public void lavaBesideWithNoDropIsLava() {
        // a lava pool at the floor's level beside us: lava where a knock would land, not a fall
        assertEquals("lava", PlaceWord.of(new World().set(1, -1, 0, Cell.LAVA).set(1, 0, 0, Cell.LAVA), false, 0, 0, 0));
        // lava two down in a pit beside us is a drop into lava: a rim
        assertEquals("rim", PlaceWord.of(new World().lavaPit(1, 0, 2), false, 0, 0, 0));
    }

    @Test
    public void fortressBrickWithNothingNearIsFortress() {
        assertEquals("fortress", PlaceWord.of(new World(), true, 0, 0, 0));
    }
}
