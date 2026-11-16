package adris.altoclef.util.helpers;

// (the buddy's fork, Tuning cave_notch_cover, off: an A/B's B arm turns it on, test grotdek) the cover in a cave where the floor
// will not dig: a cell 1 wide and 2 high dug into the nearest cave wall within REACH, stepped into, its opening shut with a block
// at the head's height (the one at the feet's too when needed). of 132 deaths before the iron tools, 38 were underground in M2.1
// (zombie crowds and skeletons in caves); the dug-in cover refuses a cave's floor (air under it, fluid near, ores, gravel) and
// fell back to "a pillbox (no ground to dig here)" in 6 of 11 underground deaths read, which lost its top or was taken over by a
// fight, and where the ground does not dig a low-hp run was kept and ended cornered in 8 of 11. the rules, no minecraft in them:
// CornerWallTask asks the world (notchKind), SkelCoverTask digs in and shuts
public final class CaveNotch {
    private CaveNotch() {
    }

    public static boolean on() {
        return Tuning.get("cave_notch_cover", 0) > 0;
    }

    // what one cell is to the notch
    public enum Kind {
        // nothing to collide with and dry: room for the body (air, grass, a torch)
        OPEN,
        // water or lava in it
        FLUID,
        // a full block that stays put and comes out with the tools at hand, hardness 3 or less (a pick in the bag for stone)
        DIG,
        // a full block that stays put but does not come out now (an ore, obsidian, stone with no pick)
        WALL,
        // a full block that falls (sand, gravel)
        LOOSE,
        // anything else: a slab, a fence, fire, a cobweb, a block with a block entity
        OTHER
    }

    public interface Grid {
        Kind at(int x, int y, int z);
    }

    // the notch: its cell (the feet's; the head's is over it) and the way out of it (dx, dz: toward the opening's cells, where the
    // buddy steps in from)
    public record Notch(int x, int y, int z, int dx, int dz) {
        public int[] cell() {
            return new int[]{x, y, z};
        }

        // the opening's cell at the feet's height, in the cave
        public int[] opening() {
            return new int[]{x + dx, y, z + dz};
        }
    }

    // the notch's cell at most this far from the buddy's feet (blocks, across), and at most RISE up or down
    public static final double REACH = 3;
    public static final int RISE = 1;

    // the four ways out, in the order they are tried at one cell (a new array each call: no static the run memory would have to
    // know)
    private static int[][] sides() {
        return new int[][]{{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
    }

    // the nearest notch from the feet at fx, fy, fz: the fewest steps to its opening, then the nearest cell; null with none
    public static Notch pick(Grid g, int fx, int fy, int fz) {
        Notch best = null;
        double bestWalk = Double.MAX_VALUE, bestNear = Double.MAX_VALUE;
        int r = (int) Math.floor(REACH);
        int[][] ways = sides();
        for (int dy : new int[]{0, -1, 1}) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > REACH * REACH) continue;
                    int x = fx + dx, y = fy + dy, z = fz + dz;
                    for (int[] side : ways) {
                        if (!fits(g, x, y, z, side[0], side[1])) continue;
                        int ox = x + side[0] - fx, oz = z + side[1] - fz;
                        double walk = ox * ox + oz * oz + dy * dy;
                        double near = dx * dx + dz * dz + dy * dy;
                        if (walk < bestWalk || walk == bestWalk && near < bestNear) {
                            best = new Notch(x, y, z, side[0], side[1]);
                            bestWalk = walk;
                            bestNear = near;
                        }
                    }
                }
            }
        }
        return best;
    }

    // a notch at x, y, z opening toward dx, dz: its two cells dig; a floor under it and under the opening; the opening's two
    // cells open; the other three sides of both cells and the top solid and staying put, and behind each of them (one further
    // out) no fluid and no open cell, so the notch is no thin wall away from lava, water or another cave
    public static boolean fits(Grid g, int x, int y, int z, int dx, int dz) {
        if (g.at(x, y, z) != Kind.DIG || g.at(x, y + 1, z) != Kind.DIG) return false;
        if (!floor(g.at(x, y - 1, z))) return false;
        int ox = x + dx, oz = z + dz;
        if (g.at(ox, y, oz) != Kind.OPEN || g.at(ox, y + 1, oz) != Kind.OPEN || !floor(g.at(ox, y - 1, oz))) return false;
        for (int[] side : sides()) {
            if (side[0] == dx && side[1] == dz) continue;
            for (int h = 0; h <= 1; h++) {
                if (!wall(g.at(x + side[0], y + h, z + side[1]))) return false;
                if (!behind(g.at(x + 2 * side[0], y + h, z + 2 * side[1]))) return false;
            }
        }
        return wall(g.at(x, y + 2, z)) && behind(g.at(x, y + 3, z));
    }

    private static boolean floor(Kind k) {
        return k == Kind.DIG || k == Kind.WALL || k == Kind.LOOSE;
    }

    private static boolean wall(Kind k) {
        return k == Kind.DIG || k == Kind.WALL;
    }

    private static boolean behind(Kind k) {
        return k != Kind.OPEN && k != Kind.FLUID;
    }

    // the feet's cell of the opening shut too, not only the head's: a line to the buddy's eyes is left after the head's block
    // (a skeleton below the notch sees in under it), a mob small enough for a one-high cell is about (a baby zombie, a cave
    // spider), or an arrow came into the notch after its head's block
    public static boolean feetToo(boolean lineLeft, boolean smallMob, boolean arrow) {
        return lineLeft || smallMob || arrow;
    }
}
