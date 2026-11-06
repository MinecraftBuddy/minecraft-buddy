package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Look;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Spot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// "can a player stand in this column of the nether", pure over a tiny block alphabet so a test can build lava seas out of
// strings. NetherGround is the half that reads the real blocks into this alphabet
public final class StandScan {
    public enum Block {
        // a full top face you can stand on (bedrock counts, it holds you up just fine)
        SOLID,
        // nothing there: no collision, no fluid, no fire
        AIR,
        LAVA,
        // magma, fire, soul fire, campfires: not a place to stand and not a place to stand in
        HARM,
        // everything else (fences, slabs, signs...): in the way, and nothing to stand on either
        OTHER
    }

    public interface Column {
        Block at(int x, int y, int z);
    }

    // floors from y 1 (the bedrock layer under it counts as one too) to just under the roof bedrock, which starts at 123
    static final int LOWEST_FLOOR = 1;
    static final int HIGHEST_FLOOR = 119;

    // the sea level lava is in y 0..31, but nothing here knows that: any lava in the column counts
    static final int LAVA_SCAN_TOP = 122;

    // standing in the open: a floor, two clear cells over it, no lava beside the feet (a ledge you would be pushed off by
    // the first ghast) and at least two of the four floors next to ours (a lone pillar in a lava sea is not a place anyone
    // walks to)
    public static boolean standable(Column w, int x, int y, int z) {
        if (w.at(x, y, z) != Block.SOLID || w.at(x, y + 1, z) != Block.AIR || w.at(x, y + 2, z) != Block.AIR) {
            return false;
        }
        int floors = 0;
        for (int[] d : SIDES) {
            if (w.at(x + d[0], y + 1, z + d[1]) == Block.LAVA) {
                return false;
            }
            if (w.at(x + d[0], y, z + d[1]) == Block.SOLID) {
                floors++;
            }
        }
        return floors >= 2;
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public static boolean standableColumn(Column w, int x, int z) {
        for (int y = LOWEST_FLOOR; y <= HIGHEST_FLOOR; y++) {
            if (standable(w, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasLava(Column w, int x, int z) {
        for (int y = LOWEST_FLOOR; y <= LAVA_SCAN_TOP; y++) {
            if (w.at(x, y, z) == Block.LAVA) {
                return true;
            }
        }
        return false;
    }

    // the waypoint itself: fine if someone can stand in the column, or if there is no lava in it at all (solid netherrack
    // with no pocket is a tunnel, and baritone digs those). a lava sea column is the one that is neither. not fine = look
    // around for the nearest column that has a floor, and if the whole ring is lava, nowhere
    public static Spot look(Column w, int x, int z, int ring) {
        if (standableColumn(w, x, z) || !hasLava(w, x, z)) {
            return new Spot(Look.FINE, x, z);
        }
        for (int[] o : offsets(ring)) {
            if (standableColumn(w, x + o[0], z + o[1])) {
                return new Spot(Look.SHIFTED, x + o[0], z + o[1]);
            }
        }
        return new Spot(Look.NOWHERE, x, z);
    }

    private static List<int[]> offsets(int ring) {
        List<int[]> out = new ArrayList<>();
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if ((dx != 0 || dz != 0) && dx * dx + dz * dz <= ring * ring) {
                    out.add(new int[]{dx, dz});
                }
            }
        }
        // nearest first. it is cheap enough to sort again, this runs once per waypoint
        out.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        return out;
    }

    private StandScan() {
    }
}
