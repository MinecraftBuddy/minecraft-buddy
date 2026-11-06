package adris.altoclef.util.helpers;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DropBurnRulesTest {

    private enum Cell { AIR, SOLID, LAVA, FLOWING_LAVA, WATER }

    // everything not set is stone, the boring default for underground
    private static final class Grid implements DropBurnRules.Cells {
        private final Map<String, Cell> cells = new HashMap<>();

        Grid set(int x, int y, int z, Cell cell) {
            cells.put(x + "," + y + "," + z, cell);
            return this;
        }

        Grid column(int x, int z, int fromY, int toY, Cell cell) {
            for (int y = fromY; y <= toY; y++) {
                set(x, y, z, cell);
            }
            return this;
        }

        private Cell at(int x, int y, int z) {
            return cells.getOrDefault(x + "," + y + "," + z, Cell.SOLID);
        }

        @Override
        public boolean lava(int x, int y, int z) {
            Cell c = at(x, y, z);
            return c == Cell.LAVA || c == Cell.FLOWING_LAVA;
        }

        @Override
        public boolean water(int x, int y, int z) {
            return at(x, y, z) == Cell.WATER;
        }

        @Override
        public boolean fallThrough(int x, int y, int z) {
            return at(x, y, z) != Cell.SOLID;
        }
    }

    private static boolean burns(Grid grid) {
        return DropBurnRules.dropWouldBurn(grid, 0, 10, 0, -64);
    }

    @Test
    public void crustOverLavaBurns() {
        assertTrue(burns(new Grid().set(0, 9, 0, Cell.LAVA)));
    }

    @Test
    public void solidFloorIsFine() {
        assertFalse(burns(new Grid()));
    }

    @Test
    public void lavaBesideFlowsIn() {
        assertTrue(burns(new Grid().set(1, 10, 0, Cell.LAVA)));
        assertTrue(burns(new Grid().set(0, 10, -1, Cell.LAVA)));
    }

    @Test
    public void lavaAboveFlowsIn() {
        assertTrue(burns(new Grid().set(0, 11, 0, Cell.LAVA)));
    }

    @Test
    public void diagonalLavaDoesNotCount() {
        assertFalse(burns(new Grid().set(1, 10, 1, Cell.LAVA).set(1, 9, 0, Cell.LAVA)));
    }

    @Test
    public void airGapThenLavaBurns() {
        assertTrue(burns(new Grid().column(0, 0, 6, 9, Cell.AIR).set(0, 5, 0, Cell.LAVA)));
    }

    @Test
    public void airGapThenFloorIsFine() {
        assertFalse(burns(new Grid().column(0, 0, 6, 9, Cell.AIR).set(0, 4, 0, Cell.LAVA)));
    }

    @Test
    public void waterThenLavaIsFine() {
        assertFalse(burns(new Grid().set(0, 9, 0, Cell.WATER).set(0, 8, 0, Cell.LAVA)));
    }

    @Test
    public void flowingLavaCounts() {
        assertTrue(burns(new Grid().set(0, 9, 0, Cell.FLOWING_LAVA)));
        assertTrue(burns(new Grid().set(-1, 10, 0, Cell.FLOWING_LAVA)));
    }

    @Test
    public void walkStopsAtTheCap() {
        int gap = DropBurnRules.MAX_FALL;
        assertTrue(burns(new Grid().column(0, 0, 10 - gap + 1, 9, Cell.AIR).set(0, 10 - gap, 0, Cell.LAVA)));
        assertFalse(burns(new Grid().column(0, 0, 10 - gap, 9, Cell.AIR).set(0, 10 - gap - 1, 0, Cell.LAVA)));
    }

    @Test
    public void walkStopsAtTheWorldBottom() {
        // nothing below min y to look at, the void is not lava
        Grid grid = new Grid().column(0, 0, -64, -63, Cell.AIR).set(0, -65, 0, Cell.LAVA);
        assertFalse(DropBurnRules.dropWouldBurn(grid, 0, -62, 0, -64));
    }
}
