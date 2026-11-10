package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance;
import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;

// the buddy's fork (S137, risk in the path search): one word for the kind of place the feet are in, in CliffAvoidance's own
// terms, so the rig can split the time spent and the hp lost by place and weigh the search's place costs against them. the
// first word that fits, in this order:
//   sea       over the lava sea, not on fortress brick (CliffAvoidance.overSea, its SEA_NODE_COST)
//   fort_rim  on fortress brick with a drop within a knock (the bridges, its FORTRESS_NODE_COST)
//   rim       a drop within a knock: lava below or nothing to land on within DEPTH (CliffAvoidance.dropNear, its NODE_COST)
//   lava      lava within a knock and no drop (CliffAvoidance.lavaNear, Tuning nether_lava_edges)
//   fortress  on fortress brick, none of the above
//   open      nothing over the head within OPEN_HEIGHT: seen from afar (ghasts, skeletons, phantoms)
//   cave      a roof within OPEN_HEIGHT
// pure: the game hands in its cells and whether the floor is fortress brick. only logged, nothing acts on it
public final class PlaceWord {

    // a roof this close keeps the sky (and what flies or shoots from far off) out
    public static final int OPEN_HEIGHT = 16;

    private PlaceWord() {}

    // feet at (x, y, z), the floor at y - 1
    public static String of(Cells cells, boolean fortressFloor, int x, int y, int z) {
        if (!fortressFloor && CliffAvoidance.overSea(cells, x, y, z)) {
            return "sea";
        }
        if (CliffAvoidance.dropNear(cells, x, y, z)) {
            return fortressFloor ? "fort_rim" : "rim";
        }
        if (CliffAvoidance.lavaNear(cells, x, y, z)) {
            return "lava";
        }
        if (fortressFloor) {
            return "fortress";
        }
        return roofed(cells, x, y, z) ? "cave" : "open";
    }

    // anything solid from above the head up to OPEN_HEIGHT
    static boolean roofed(Cells cells, int x, int y, int z) {
        for (int d = 2; d <= OPEN_HEIGHT + 1; d++) {
            if (cells.at(x, y + d, z) == Cell.SOLID) {
                return true;
            }
        }
        return false;
    }
}
