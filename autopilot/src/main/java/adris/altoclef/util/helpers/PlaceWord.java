package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance;
import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

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
// pure: the game hands in its cells and whether the floor is fortress brick. the log names it; the path search prices it
// under Tuning risk_table 1 (RiskCost)
public final class PlaceWord {

    // a roof this close keeps the sky (and what flies or shoots from far off) out
    public static final int OPEN_HEIGHT = 16;

    // the words in the order above, by index (RiskCost's table is an array over them)
    public static final String[] WORDS = {"sea", "fort_rim", "rim", "lava", "fortress", "open", "cave"};
    public static final int SEA = 0, FORT_RIM = 1, RIM = 2, LAVA = 3, FORTRESS = 4, OPEN = 5, CAVE = 6;

    private PlaceWord() {}

    // feet at (x, y, z), the floor at y - 1
    public static String of(Cells cells, boolean fortressFloor, int x, int y, int z) {
        return WORDS[index(cells, fortressFloor, x, y, z)];
    }

    public static int index(Cells cells, boolean fortressFloor, int x, int y, int z) {
        if (!fortressFloor && CliffAvoidance.overSea(cells, x, y, z)) {
            return SEA;
        }
        if (CliffAvoidance.dropNear(cells, x, y, z)) {
            return fortressFloor ? FORT_RIM : RIM;
        }
        if (CliffAvoidance.lavaNear(cells, x, y, z)) {
            return LAVA;
        }
        if (fortressFloor) {
            return FORTRESS;
        }
        return roofed(cells, x, y, z) ? CAVE : OPEN;
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

    // index with every answer kept, for one path search: a node asks about the 24 columns round it, and its neighbours ask
    // about nearly the same ones, so each column is scanned once. per search, as CliffAvoidance.Memo is: a column bridged
    // or mined since the last search has a different answer now. the same word as index, cell for cell
    public static final class Memo {
        private final Cells cells;
        private final Long2ByteOpenHashMap words = new Long2ByteOpenHashMap(4096);
        private final Long2ByteOpenHashMap unsafe = new Long2ByteOpenHashMap(4096);
        private final Long2ByteOpenHashMap lavaUnder = new Long2ByteOpenHashMap(4096);

        public Memo(Cells cells) {
            this.cells = cells;
            words.defaultReturnValue((byte) -1);
            unsafe.defaultReturnValue((byte) -1);
            lavaUnder.defaultReturnValue((byte) -1);
        }

        // (the floor under a node is the same block all search long, so the node's key is enough)
        public int index(boolean fortressFloor, int x, int y, int z) {
            long key = key(x, y, z);
            byte known = words.get(key);
            if (known >= 0) {
                return known;
            }
            int word = word(fortressFloor, x, y, z);
            words.put(key, (byte) word);
            return word;
        }

        private int word(boolean fortressFloor, int x, int y, int z) {
            if (!fortressFloor && CliffAvoidance.overSea(cells, x, y, z)) {
                return SEA;
            }
            if (around(unsafe, x, y, z)) {
                return fortressFloor ? FORT_RIM : RIM;
            }
            if (around(lavaUnder, x, y, z)) {
                return LAVA;
            }
            if (fortressFloor) {
                return FORTRESS;
            }
            return roofed(cells, x, y, z) ? CAVE : OPEN;
        }

        // CliffAvoidance.dropNear (the unsafe map) or lavaNear (the lavaUnder map): any other column within a knock, our own
        // left out as they leave it out
        private boolean around(Long2ByteOpenHashMap map, int x, int y, int z) {
            for (int dx = -CliffAvoidance.REACH; dx <= CliffAvoidance.REACH; dx++) {
                for (int dz = -CliffAvoidance.REACH; dz <= CliffAvoidance.REACH; dz++) {
                    if ((dx != 0 || dz != 0) && column(map, x + dx, y, z + dz)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean column(Long2ByteOpenHashMap map, int x, int y, int z) {
            long key = key(x, y, z);
            byte known = map.get(key);
            if (known >= 0) {
                return known == 1;
            }
            boolean yes = map == unsafe ? CliffAvoidance.columnUnsafe(cells, x, y, z) : CliffAvoidance.lavaUnder(cells, x, y, z);
            map.put(key, (byte) (yes ? 1 : 0));
            return yes;
        }

        // BlockPos.asLong's packing (26 bits x, 26 bits z, 12 bits y), written out so this class needs no game
        static long key(int x, int y, int z) {
            return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
        }
    }
}
