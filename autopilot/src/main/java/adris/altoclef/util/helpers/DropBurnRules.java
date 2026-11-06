package adris.altoclef.util.helpers;

// will the item from breaking this block end up in lava. obsidian at diamond depth is mostly the crust on a lava lake, and
// the "it's fine to break the block over liquid" rule is about us, not the drop: the drop falls straight in and burns.
// plain answers over a grid so it can be tested without a world (same idea as BucketSourceRules)
public final class DropBurnRules {

    private DropBurnRules() {
    }

    public interface Cells {
        // lava, source or flowing. flowing burns items just as well
        boolean lava(int x, int y, int z);

        boolean water(int x, int y, int z);

        // no collision box: the drop falls through it (air, plants, fluids)
        boolean fallThrough(int x, int y, int z);
    }

    // past this many open cells the drop is falling somewhere we can't see the bottom of anyway, call it fine
    public static final int MAX_FALL = 16;

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public static boolean dropWouldBurn(Cells cells, int x, int y, int z, int minY) {
        // lava over or beside the block flows into the hole we just made, right where the drop is sitting
        if (cells.lava(x, y + 1, z)) {
            return true;
        }
        for (int[] side : SIDES) {
            if (cells.lava(x + side[0], y, z + side[1])) {
                return true;
            }
        }
        return fallsIntoLava(cells, x, y, z, minY);
    }

    private static boolean fallsIntoLava(Cells cells, int x, int y, int z, int minY) {
        for (int dy = 1; dy <= MAX_FALL && y - dy >= minY; dy++) {
            int at = y - dy;
            if (cells.lava(x, at, z)) {
                return true;
            }
            // items float on water, and lava under the water isn't our problem
            if (cells.water(x, at, z) || !cells.fallThrough(x, at, z)) {
                return false;
            }
        }
        return false;
    }
}
