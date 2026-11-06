package adris.altoclef.util.helpers;

// which liquid sources are worth walking to with a bucket. nearest-wins used to pick a source with a block on top of it
// and no way to get at that block, over an open pool two steps further. this is the "can we get at it" half, in plain
// is-it-solid / is-it-open answers so it can be tested without a world
public final class BucketSourceRules {

    private BucketSourceRules() {
    }

    // OPEN: a bucket ray can reach it as it is. LID: only after the block over it is dug out, and that block can be reached
    // from the side. NONE: not a candidate
    public enum Kind { OPEN, LID, NONE }

    public interface Cells {
        // a full block, the kind that sits on a source and hides it (and the kind the lid digging is about)
        boolean solid(int x, int y, int z);

        // nothing in the way of a ray or a body: air, fluid, plants
        boolean open(int x, int y, int z);

        // open and dry, somewhere a body can stand or swing from. a lava or water cell is open to a ray, not to us
        default boolean free(int x, int y, int z) {
            return open(x, y, z);
        }
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public static Kind classify(Cells cells, int x, int y, int z) {
        if (!cells.solid(x, y + 1, z)) {
            return openToTheOutside(cells, x, y, z) ? Kind.OPEN : Kind.NONE;
        }
        // a lid. digging it from the side needs a side cell of the lid itself to stand in or swing from. a cell that only
        // touches the lid across a corner is how a source ends up "visible" and unreachable: the path search never has
        // anywhere to take us, so those don't count
        for (int[] side : SIDES) {
            if (cells.free(x + side[0], y + 1, z + side[1])) {
                return Kind.LID;
            }
        }
        return Kind.NONE;
    }

    // some face of the source has air, fluid or a plant on it, so there's a spot to scoop it from. (glass over a pool is not
    // solid for the lid logic but it isn't open either)
    private static boolean openToTheOutside(Cells cells, int x, int y, int z) {
        if (cells.open(x, y + 1, z)) {
            return true;
        }
        for (int[] side : SIDES) {
            if (cells.open(x + side[0], y, z + side[1])) {
                return true;
            }
        }
        return false;
    }

    // an open source outranks the lids when it is this close to us (straight line) ...
    public static final double OPEN_NEAR_BLOCKS = 32;
    // ... or no further than this past the nearest lid source we could dig. past both, a pool across the map does not shut
    // out a lid three blocks from our feet
    public static final double OPEN_EXTRA_BLOCKS = 16;

    // distances from the bot to the nearest open source and to the nearest lid source worth digging, NaN for "none known"
    public static boolean openOutranksLids(double nearestOpen, double nearestLid) {
        if (Double.isNaN(nearestOpen)) {
            return false;
        }
        if (Double.isNaN(nearestLid)) {
            return true;
        }
        return nearestOpen <= OPEN_NEAR_BLOCKS || nearestOpen - nearestLid <= OPEN_EXTRA_BLOCKS;
    }

    // a lid source only counts while an open one does not outrank it: digging for lava is the slow way, and a pool we can
    // just scoop from the edge beats a lidded source unless it is a long way off for no good reason
    public static boolean worthGoingTo(Kind kind, boolean openOutranksLids) {
        return kind == Kind.OPEN || kind == Kind.LID && !openOutranksLids;
    }
}
