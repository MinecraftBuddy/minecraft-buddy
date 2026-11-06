package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.EscapeRoute.Cell;
import adris.altoclef.tasks.speedrun.gamer.EscapeRoute.Plan;
import adris.altoclef.tasks.speedrun.gamer.EscapeRoute.Route;
import adris.altoclef.tasks.speedrun.gamer.EscapeRoute.Terrain;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EscapeRouteTest {
    // feet at y 70, the world is air unless a test says otherwise
    private static final int Y = 70;

    private static final class World implements Terrain {
        final Map<String, Cell> set = new HashMap<>();
        IntPredicate unloadedX = x -> false;

        World put(int x, int y, int z, Cell c) {
            set.put(x + "," + y + "," + z, c);
            return this;
        }

        // natural ground from x0 on, every z, a few blocks deep
        World floorFrom(int x0, int top) {
            for (int x = x0; x <= x0 + 120; x++) {
                for (int z = -100; z <= 100; z++) {
                    for (int y = top; y >= top - 4; y--) {
                        put(x, y, z, Cell.NATURAL);
                    }
                }
            }
            return this;
        }

        World slab() {
            for (int x = 0; x <= 1; x++) {
                for (int z = 0; z <= 2; z++) {
                    put(x, Y - 1, z, Cell.ARTIFICIAL);
                }
            }
            return this;
        }

        @Override
        public Cell at(int x, int y, int z) {
            if (unloadedX.test(x)) {
                return Cell.UNLOADED;
            }
            return set.getOrDefault(x + "," + y + "," + z, Cell.AIR);
        }
    }

    // a two high rock face at x 3 with ground under it
    private static World wall(World w) {
        return w.put(3, Y - 1, 0, Cell.NATURAL).put(3, Y, 0, Cell.NATURAL).put(3, Y + 1, 0, Cell.NATURAL);
    }

    private static int turns(Route r) {
        int turns = 0;
        int lastDx = 0;
        int lastDz = 0;
        for (int i = 1; i < r.cells().size(); i++) {
            int dx = Integer.signum(r.cells().get(i)[0] - r.cells().get(i - 1)[0]);
            int dz = Integer.signum(r.cells().get(i)[2] - r.cells().get(i - 1)[2]);
            if (i > 1 && (dx != lastDx || dz != lastDz)) {
                turns++;
            }
            lastDx = dx;
            lastDz = dz;
        }
        return turns;
    }

    private static void assertCell(int x, int y, int z, int[] got) {
        assertEquals(x, got[0]);
        assertEquals(y, got[1]);
        assertEquals(z, got[2]);
    }

    @Test
    public void anOrdinaryArrivalIsNeverIsolated() {
        // natural floor under the portal and around it: nothing to place, nothing to plan
        World w = new World().floorFrom(-50, Y - 1);
        Plan p = EscapeRoute.plan(w, 0, Y, 0, 200);
        assertFalse(p.isolated());
        assertEquals(0, p.route().placements());
        // and a thin bag does not change that
        assertFalse(EscapeRoute.plan(w, 0, Y, 0, 0).isolated());
    }

    @Test
    public void aFloorTenCellsEastIsAStraightTenPlacements() {
        World w = new World().slab().floorFrom(12, Y - 1);
        Route r = EscapeRoute.find(w, 0, Y, 0, 40);
        assertNotNull(r);
        assertFalse(r.wall());
        // x 1 is still the slab, 2..11 are bridged, 12 is the floor
        assertEquals(10, r.placements());
        assertEquals(13, r.cells().size());
        assertEquals(0, turns(r));
        assertCell(12, Y, 0, r.end());
        Plan p = EscapeRoute.plan(w, 0, Y, 0, 60);
        assertTrue(p.isolated());
        assertEquals(10, p.route().placements());
    }

    @Test
    public void aFloorBeatsANearerWallWhenBothFit() {
        World w = new World().slab().floorFrom(12, Y - 1);
        wall(w);
        Route r = EscapeRoute.find(w, 0, Y, 0, 40);
        assertFalse("the wall is one placement away, the floor is still the better end", r.wall());
        assertEquals(10, r.placements());
    }

    @Test
    public void aWallIsTheFallbackWhenNoFloorFits() {
        World w = new World().slab().floorFrom(12, Y - 1);
        wall(w);
        Route r = EscapeRoute.find(w, 0, Y, 0, 5);
        assertNotNull(r);
        assertTrue(r.wall());
        assertEquals(1, r.placements());
        assertCell(3, Y, 0, r.end());
    }

    @Test
    public void aWallWithLavaBesideItIsRejected() {
        World w = wall(new World().slab());
        w.put(4, Y, 0, Cell.LAVA);
        assertNull(EscapeRoute.find(w, 0, Y, 0, 40));
        // the same wall without the lava is fine
        assertTrue(EscapeRoute.find(wall(new World().slab()), 0, Y, 0, 40).wall());
    }

    @Test
    public void lavaOverTheHeadRoomRejectsAWall() {
        // digging the second block out would let the lava above it down
        World w = wall(new World().slab());
        w.put(3, Y + 2, 0, Cell.LAVA);
        assertNull(EscapeRoute.find(w, 0, Y, 0, 40));
    }

    @Test
    public void aWallWithNothingUnderItIsNotGround() {
        World w = new World().slab();
        w.put(3, Y, 0, Cell.NATURAL);
        assertNull(EscapeRoute.find(w, 0, Y, 0, 40));
    }

    @Test
    public void unloadedCellsBlockTheRoute() {
        World w = new World().slab().floorFrom(12, Y - 1);
        w.unloadedX = x -> x == 6;
        assertNull(EscapeRoute.find(w, 0, Y, 0, 60));
    }

    @Test
    public void overBudgetReturnsNone() {
        World w = new World().slab().floorFrom(12, Y - 1);
        assertNull(EscapeRoute.find(w, 0, Y, 0, 9));
        // 13 throwaways = 5 spendable after the spare
        Plan p = EscapeRoute.plan(w, 0, Y, 0, 13);
        assertTrue(p.isolated());
        assertNull(p.route());
    }

    @Test
    public void aStepDownOfThreeIsAFloor() {
        // natural block 3 below the feet: the cell before it is walked off the edge of, so only x 2 is placed
        World w = new World().slab();
        for (int x = 3; x < 100; x++) {
            for (int z = -5; z <= 5; z++) {
                w.put(x, Y - 3, z, Cell.NATURAL);
            }
        }
        Route r = EscapeRoute.find(w, 0, Y, 0, 40);
        assertNotNull(r);
        assertFalse(r.wall());
        assertEquals(1, r.placements());
        assertCell(3, Y - 2, 0, r.end());
    }

    @Test
    public void aStepDownOfFiveIsNotAFloor() {
        World w = new World().slab();
        for (int x = 3; x < 100; x++) {
            for (int z = -5; z <= 5; z++) {
                w.put(x, Y - 5, z, Cell.NATURAL);
            }
        }
        assertNull(EscapeRoute.find(w, 0, Y, 0, 40));
    }

    @Test
    public void aDropThroughLavaIsNotAStepDown() {
        World w = new World().slab();
        for (int x = 3; x < 100; x++) {
            w.put(x, Y - 2, 0, Cell.LAVA).put(x, Y - 3, 0, Cell.NATURAL);
        }
        assertNull(EscapeRoute.find(w, 0, Y, 0, 4));
    }

    @Test
    public void tiesTakeTheStraighterRoute() {
        // one floor block off to the side: every monotone path costs the same, the one turn path is the tidy one
        World w = new World();
        w.put(4, Y - 1, 2, Cell.NATURAL);
        Route r = EscapeRoute.find(w, 0, Y, 0, 20);
        assertNotNull(r);
        assertEquals(5, r.placements());
        assertEquals(1, turns(r));
    }

    // ---- the lava lake test

    private static World lavaBelow(int depth) {
        World w = new World();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                w.put(x, Y - depth, z, Cell.LAVA);
            }
        }
        return w;
    }

    @Test
    public void lakeWhenEveryColumnIsLava() {
        assertTrue(EscapeRoute.overLavaLake(lavaBelow(5).slab(), 0, Y, 0));
    }

    @Test
    public void lakeWithAPillarUnderTheFeet() {
        World w = lavaBelow(5).slab();
        for (int y = Y - 2; y >= Y - 5; y--) {
            w.put(0, y, 0, Cell.NATURAL);
        }
        assertTrue(EscapeRoute.overLavaLake(w, 0, Y, 0));
    }

    @Test
    public void notLakeWithNaturalFloorBelow() {
        World w = lavaBelow(5).slab();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                w.put(x, Y - 3, z, Cell.NATURAL);
            }
        }
        assertFalse(EscapeRoute.overLavaLake(w, 0, Y, 0));
    }

    @Test
    public void notLakeWhenUnloadedBelow() {
        World w = lavaBelow(5).slab();
        w.unloadedX = x -> true;
        assertFalse(EscapeRoute.overLavaLake(w, 0, Y, 0));
    }

    @Test
    public void notLakeOverNothing() {
        assertFalse(EscapeRoute.overLavaLake(new World().slab(), 0, Y, 0));
    }

    @Test
    public void deepLakeStillCounts() {
        World w = new World();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                w.put(x, Y - 100, z, Cell.LAVA);
            }
        }
        assertTrue(EscapeRoute.overLavaLake(w, 0, Y, 0));
    }

    @Test
    public void cheapWallBesideTheSlabIsNotALakeEnd() {
        World w = lavaBelow(5).slab();
        w.put(-1, Y, 0, Cell.NATURAL).put(-1, Y - 1, 0, Cell.NATURAL);
        assertFalse(EscapeRoute.lavaLakeNoWay(w, 0, Y, 0));
    }

    @Test
    public void farFloorOverLavaWithCheapWallIsNotALakeEnd() {
        World w = lavaBelow(5).slab().floorFrom(12, Y - 1);
        w.put(-1, Y, 0, Cell.NATURAL).put(-1, Y - 1, 0, Cell.NATURAL);
        assertFalse(EscapeRoute.lavaLakeNoWay(w, 0, Y, 0));
    }

    @Test
    public void openLavaWithNoWallIsALakeEnd() {
        assertTrue(EscapeRoute.lavaLakeNoWay(lavaBelow(5).slab(), 0, Y, 0));
    }
}
