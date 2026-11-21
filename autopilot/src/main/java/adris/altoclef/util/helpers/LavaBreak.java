package adris.altoclef.util.helpers;

// the buddy's fork, Tuning dig_lava_check (on since 19 Nov 2026, confirmed by test obsidiaan): in the overworld no block is broken
// that has lava beside it, over it or under it, nor one with an open cell beside it that has lava beside or over that cell.
// baritone's own rule (MovementHelper.avoidBreaking) looks at the four sides and the top as its snapshot saw them, never under
// the block, and never one cell further: a pool just scooped is an open cell for a lava tick (1.5 s in the overworld) and lava
// again by the time a pick is through the block beside it. in whole runs of M3.2 the path broke the block beside a pool just
// scooped and the buddy fell into it, and a dug-in cover dug down into lava. AltoClef registers refuse as a break avoider (the
// planner, its cost check while a path runs, and WorldHelper.canBreak all ask it); GetDownColumnTask holds its dig at a block
// it refuses. a task left with no way on ends by its own give-up. a block the buddy's body is in is never refused: sand or
// gravel fallen on the buddy is dug out by WorldSurvivalChain, which asks the avoiders first, and the lava is in reach of the
// buddy whether it is dug or not.
public final class LavaBreak {

    public static final String KEY = "dig_lava_check";

    // the switch as the main thread last read it: the break avoider runs on baritone's path thread, many times a search
    private static volatile boolean on;
    // (Tuning dig_lava_loose, a fix of 21 Nov 2026, on; true until the first read, as the tests see it) sand or gravel under the
    // block followed down to what holds it up
    private static volatile boolean looseOn = true;

    private LavaBreak() {
    }

    // once a client tick, on the main thread
    public static void refresh() {
        on = Tuning.get(KEY, 0) > 0;
        looseOn = Tuning.get("dig_lava_loose", 0) > 0;
    }

    public static boolean on() {
        return on;
    }

    // the world as refuse needs it
    public interface Cells {
        // lava in the cell, a source or flowing
        boolean lava(int x, int y, int z);

        // something to break: a collision shape
        boolean solid(int x, int y, int z);

        // nothing in the cell for lava to stop at: no collision shape and no fluid
        boolean open(int x, int y, int z);

        // a block that falls when nothing holds it up: sand, gravel, concrete powder
        default boolean loose(int x, int y, int z) {
            return false;
        }
    }

    // the loose blocks followed down under a break, at most
    static final int LOOSE_MAX = 8;

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // the same, and never a block the buddy's body is in (body)
    public static boolean refuse(Cells c, int x, int y, int z, boolean body) {
        return !body && refuse(c, x, y, z);
    }

    // whether breaking the block at x y z lets lava into its cell. a cell with nothing to break is never refused
    public static boolean refuse(Cells c, int x, int y, int z) {
        if (!c.solid(x, y, z)) return false;
        if (c.lava(x, y - 1, z) || c.lava(x, y + 1, z)) return true;
        for (int[] d : SIDES) {
            if (c.lava(x + d[0], y, z + d[1])) return true;
        }
        for (int[] d : SIDES) {
            int nx = x + d[0], nz = z + d[1];
            if (!c.open(nx, y, nz)) continue;
            if (c.lava(nx, y + 1, nz)) return true;
            for (int[] e : SIDES) {
                if (c.lava(nx + e[0], y, nz + e[1])) return true;
            }
        }
        // (dig_lava_loose) gravel resting on lava under the block: the break lets it fall into the lava, and the buddy standing
        // on the block above falls after it. a dig straight down in M3.2 broke the deepslate over such gravel at y -55, the gravel
        // went, and the buddy dropped into a pool three deep with no way up (heel-140-mc2 r1)
        if (looseOn) {
            int k = y - 1, n = 0;
            while (n < LOOSE_MAX && c.loose(x, k, z)) {
                k--;
                n++;
            }
            if (n > 0 && (c.lava(x, k, z) || c.open(x, k, z) && c.lava(x, k - 1, z))) return true;
        }
        return false;
    }
}
