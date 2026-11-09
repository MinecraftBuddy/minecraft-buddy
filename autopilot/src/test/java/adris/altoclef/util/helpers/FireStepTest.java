package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.FireStep.Cell;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

// the forced fight's step out of the fire: the nearest cell within three blocks with clear feet and head on a floor that does
// not burn, one beside no fire before one beside fire; none, no step
public class FireStepTest {
    // a flat floor at y 63 (feet at 64), open above, with the cells set by hand on top
    private static final class Floor implements FireStep.Cells {
        final Map<String, Cell> set = new HashMap<>();

        Floor put(int x, int y, int z, Cell c) {
            set.put(x + "," + y + "," + z, c);
            return this;
        }

        @Override
        public Cell at(int x, int y, int z) {
            Cell c = set.get(x + "," + y + "," + z);
            if (c != null) return c;
            return y <= 63 ? Cell.SOLID : Cell.OPEN;
        }
    }

    @Test
    public void aBurningCellStepsToTheNearestClearOne() {
        Floor f = new Floor().put(0, 64, 0, Cell.HOT);
        FireStep.Spot s = FireStep.find(f, 0, 64, 0);
        assertNotNull(s);
        assertEquals(64, s.y());
        // the cells straight beside are beside the fire, a corner one is not: the corner
        assertFalse(s.byFire());
        assertEquals(Math.sqrt(2), s.blocks(), 1e-9);
    }

    @Test
    public void aCellBesideNoFireWinsOverANearerOneBesideFire() {
        // fire in the feet cell and the four straight beside it: every cell at one block or less is in or beside fire
        Floor f = new Floor();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 1) f.put(dx, 64, dz, Cell.HOT);
            }
        }
        FireStep.Spot s = FireStep.find(f, 0, 64, 0);
        assertNotNull(s);
        assertFalse(s.byFire());
        assertTrue(s.blocks() > 1);
    }

    @Test
    public void neverOntoLavaNorIntoAWall() {
        Floor f = new Floor().put(0, 64, 0, Cell.HOT);
        // lava under every cell but one, walls in the head of that one but the farthest
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 3 && dz == 0) continue;
                f.put(dx, 63, dz, Cell.HOT);
            }
        }
        FireStep.Spot s = FireStep.find(f, 0, 64, 0);
        assertNotNull(s);
        assertEquals(3, s.x());
        assertEquals(0, s.z());
        f.put(3, 65, 0, Cell.SOLID);
        assertNull(FireStep.find(f, 0, 64, 0));
    }

    @Test
    public void notFurtherThanThreeBlocks() {
        Floor f = new Floor();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                f.put(dx, 63, dz, Cell.HOT);
            }
        }
        // the only good floor four blocks off
        assertNull(FireStep.find(f, 0, 64, 0));
    }

    @Test
    public void aStepUpOrDownIsAllowed() {
        Floor f = new Floor();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                f.put(dx, 63, dz, Cell.HOT);
            }
        }
        // a block to stand on one up, two off
        f.put(2, 64, 0, Cell.SOLID);
        FireStep.Spot s = FireStep.find(f, 0, 64, 0);
        assertNotNull(s);
        assertEquals(65, s.y());
        assertEquals(2, s.x());
    }
}
