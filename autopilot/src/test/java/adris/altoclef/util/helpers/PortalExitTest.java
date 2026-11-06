package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.PortalExit.Cell;
import adris.altoclef.util.helpers.PortalExit.Terrain;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PortalExitTest {
    private static final int Y = 70;

    // flat solid floor at Y-1, air above, a portal column at 0,0
    private static final class World implements Terrain {
        final Map<String, Cell> set = new HashMap<>();

        World() {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    put(x, Y - 1, z, Cell.SOLID);
                    put(x, Y - 2, z, Cell.SOLID);
                }
            }
            put(0, Y, 0, Cell.PORTAL);
            put(0, Y + 1, 0, Cell.PORTAL);
        }

        World put(int x, int y, int z, Cell c) {
            set.put(x + "," + y + "," + z, c);
            return this;
        }

        @Override
        public Cell at(int x, int y, int z) {
            return set.getOrDefault(x + "," + y + "," + z, Cell.AIR);
        }
    }

    // dir: 0 south (+z), 1 west (-x), 2 north (-z), 3 east (+x)
    private static void wall(World w, int dir) {
        int dx = new int[]{0, -1, 0, 1}[dir];
        int dz = new int[]{1, 0, -1, 0}[dir];
        w.put(dx, Y, dz, Cell.SOLID).put(dx, Y + 1, dz, Cell.SOLID);
    }

    @Test
    public void openGroundKeepsTheDirectionWeFace() {
        OptionalInt d = PortalExit.pick(new World(), 0, Y, 0, 180f);
        assertEquals(2, d.getAsInt());
    }

    @Test
    public void facingTheFrameTurnsToTheFreeSide() {
        World w = new World();
        wall(w, 0);
        // yaw 0 faces south, the wall; west and east are equally far, north is behind
        int d = PortalExit.pick(w, 0, Y, 0, 0f).getAsInt();
        assertTrue(d == 1 || d == 3);
    }

    @Test
    public void onlyOneSideFree() {
        World w = new World();
        wall(w, 0);
        wall(w, 1);
        wall(w, 3);
        assertEquals(2, PortalExit.pick(w, 0, Y, 0, 0f).getAsInt());
    }

    @Test
    public void portalNeighbourIsNotAnExit() {
        World w = new World();
        wall(w, 0);
        wall(w, 1);
        wall(w, 3);
        w.put(0, Y, -1, Cell.PORTAL);
        assertFalse(PortalExit.pick(w, 0, Y, 0, 0f).isPresent());
    }

    @Test
    public void lavaFloorOrFireIsNotAnExit() {
        World w = new World();
        wall(w, 1);
        wall(w, 2);
        wall(w, 3);
        w.put(0, Y - 1, 1, Cell.HAZARD);
        assertFalse(PortalExit.pick(w, 0, Y, 0, 0f).isPresent());
        w.put(0, Y - 1, 1, Cell.SOLID).put(0, Y, 1, Cell.HAZARD);
        assertFalse(PortalExit.pick(w, 0, Y, 0, 0f).isPresent());
    }

    @Test
    public void lowCeilingIsNotAnExit() {
        World w = new World();
        wall(w, 1);
        wall(w, 2);
        wall(w, 3);
        w.put(0, Y + 1, 1, Cell.SOLID);
        assertFalse(PortalExit.pick(w, 0, Y, 0, 0f).isPresent());
    }

    @Test
    public void oneBlockDropIsFineTwoIsNot() {
        World w = new World();
        wall(w, 1);
        wall(w, 2);
        wall(w, 3);
        w.put(0, Y - 1, 1, Cell.AIR);
        assertEquals(0, PortalExit.pick(w, 0, Y, 0, 0f).getAsInt());
        w.put(0, Y - 2, 1, Cell.AIR);
        assertFalse(PortalExit.pick(w, 0, Y, 0, 0f).isPresent());
    }

    @Test
    public void yawWrapsAround() {
        // 350 is nearly south (0), not nearly east
        assertEquals(0, PortalExit.pick(new World(), 0, Y, 0, 350f).getAsInt());
        assertEquals(0, PortalExit.pick(new World(), 0, Y, 0, -10f).getAsInt());
    }
}
