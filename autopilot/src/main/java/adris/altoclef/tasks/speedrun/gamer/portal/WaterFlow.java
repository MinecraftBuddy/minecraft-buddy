package adris.altoclef.tasks.speedrun.gamer.portal;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

// pure: where does placed water end up. a slimmed copy of FlowingFluid (downhill first, shortest-slope-to-a-hole
// sideways, 7 blocks of reach) plus the one lava rule we care about: a lava SOURCE with water on any side except
// directly below it turns to obsidian. runs to a fixpoint instead of ticking, so it says where water can EVER get,
// not when. the mold geometry is tested against this instead of against vibes
public final class WaterFlow {
    public enum Kind { AIR, SOLID, LAVA, WATER }

    public interface World {
        Kind at(int x, int y, int z);
    }

    // everything the run touched. keys are packed with key(), unpack with x()/y()/z()
    public static final class Result {
        public final Map<Long, Integer> water = new HashMap<>();
        public final Set<Long> obsidian = new HashSet<>();
        public boolean hitBudget;

        public boolean hasWater(int x, int y, int z) {
            return water.containsKey(key(x, y, z));
        }

        public boolean isObsidian(int x, int y, int z) {
            return obsidian.contains(key(x, y, z));
        }
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    // water looks four cells ahead for a drop. lava would look two but this class does not do lava
    private static final int SLOPE = 4;
    // a pool with nothing under it falls forever, bail instead
    private static final int BUDGET = 50000;

    private final World base;
    private final int maxGen;
    private final Result r = new Result();
    // strongest water first so a cell is settled the first time it comes off the queue
    private final PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> Long.compare(b[3], a[3]));

    private WaterFlow(World base, int maxGen) {
        this.base = base;
        this.maxGen = maxGen;
    }

    // world coordinates: x and z get 26 bits each (the border is +-30M), y gets 12. every field is biased up and masked, so a
    // negative one cannot smear over its neighbours
    private static final int XZ_BIAS = 1 << 25;
    private static final long XZ_MASK = (1L << 26) - 1;
    private static final int Y_BIAS = 2048;
    private static final long Y_MASK = (1L << 12) - 1;

    public static long key(int x, int y, int z) {
        return (((long) x + XZ_BIAS) & XZ_MASK) << 38 | (((long) z + XZ_BIAS) & XZ_MASK) << 12 | ((long) y + Y_BIAS) & Y_MASK;
    }

    public static int x(long k) {
        return (int) ((k >>> 38) & XZ_MASK) - XZ_BIAS;
    }

    public static int y(long k) {
        return (int) (k & Y_MASK) - Y_BIAS;
    }

    public static int z(long k) {
        return (int) ((k >>> 12) & XZ_MASK) - XZ_BIAS;
    }

    // sources: cells that hold a water source from the start (the bucket spots)
    public static Result run(World base, int[][] sources) {
        return run(base, sources, Integer.MAX_VALUE);
    }

    // maxGen: how many flow steps the water gets before somebody scoops it back up. every cell it moves into is one
    // step (a fall counts too), so 2 is "it has had time to wet its neighbours and not much more". 5 ticks a step in game
    public static Result run(World base, int[][] sources, int maxGen) {
        WaterFlow f = new WaterFlow(base, maxGen);
        for (int[] s : sources) {
            f.put(s[0], s[1], s[2], 8, false, 0);
        }
        f.drain();
        return f.r;
    }

    private Kind kind(int x, int y, int z) {
        long k = key(x, y, z);
        if (r.obsidian.contains(k)) {
            return Kind.SOLID;
        }
        if (r.water.containsKey(k)) {
            return Kind.WATER;
        }
        return base.at(x, y, z);
    }

    private void drain() {
        int steps = 0;
        while (!queue.isEmpty()) {
            if (++steps > BUDGET) {
                r.hitBudget = true;
                return;
            }
            long[] q = queue.poll();
            int x = (int) q[0];
            int y = (int) q[1];
            int z = (int) q[2];
            int amount = r.water.get(key(x, y, z));
            if (amount != q[3]) {
                // a stronger entry for this cell got there first
                continue;
            }
            int gen = (int) q[5];
            if (gen >= maxGen) {
                continue;
            }
            if (hole(x, y, z)) {
                // water over water or over air just keeps falling, it never spreads sideways from here
                if (open(x, y - 1, z, 8)) {
                    put(x, y - 1, z, 8, true, gen + 1);
                }
                continue;
            }
            int next = q[4] != 0 ? 7 : amount - 1;
            if (next > 0) {
                spread(x, y, z, next, gen + 1);
            }
        }
    }

    private void spread(int x, int y, int z, int next, int gen) {
        int best = 1000;
        boolean[] go = new boolean[4];
        for (int i = 0; i < 4; i++) {
            if (!open(x + SIDES[i][0], y, z + SIDES[i][1], next)) {
                continue;
            }
            int k = hole(x + SIDES[i][0], y, z + SIDES[i][1]) ? 0 : slope(x + SIDES[i][0], y, z + SIDES[i][1], 1, i ^ 1);
            if (k < best) {
                best = k;
                Arrays.fill(go, false);
            }
            if (k <= best) {
                go[i] = true;
            }
        }
        for (int i = 0; i < 4; i++) {
            if (go[i]) {
                put(x + SIDES[i][0], y, z + SIDES[i][1], next, false, gen);
            }
        }
    }

    private int slope(int x, int y, int z, int dist, int from) {
        int best = 1000;
        for (int i = 0; i < 4; i++) {
            if (i == from) {
                continue;
            }
            int nx = x + SIDES[i][0];
            int nz = z + SIDES[i][1];
            if (!open(nx, y, nz, 1)) {
                continue;
            }
            if (hole(nx, y, nz)) {
                return dist;
            }
            if (dist < SLOPE) {
                best = Math.min(best, slope(nx, y, nz, dist + 1, i ^ 1));
            }
        }
        return best;
    }

    // water can drop out of this cell: whatever is below takes it (air, or more water)
    private boolean hole(int x, int y, int z) {
        Kind below = kind(x, y - 1, z);
        return below == Kind.AIR || below == Kind.WATER;
    }

    // can water of this strength move into the cell. flowing water only gets replaced by stronger water
    private boolean open(int x, int y, int z, int amount) {
        Kind k = kind(x, y, z);
        if (k == Kind.AIR) {
            return true;
        }
        return k == Kind.WATER && amount > r.water.get(key(x, y, z));
    }

    private void put(int x, int y, int z, int amount, boolean falling, int gen) {
        long k = key(x, y, z);
        Integer have = r.water.get(k);
        if (have != null && have >= amount) {
            return;
        }
        r.water.put(k, amount);
        // lava sources next to the new water. not the one above: water below lava does nothing
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};
        for (int[] a : around) {
            if (kind(x + a[0], y + a[1], z + a[2]) == Kind.LAVA) {
                r.obsidian.add(key(x + a[0], y + a[1], z + a[2]));
            }
        }
        queue.add(new long[]{x, y, z, amount, falling ? 1 : 0, gen});
    }
}
