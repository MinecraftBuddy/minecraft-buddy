package adris.altoclef.util.helpers;

// (Tuning mine_leave_given_up, a fix of 13 Nov 2026, on) where branch mines gave their depth up, the way a branch mine leaves
// such a place, and the count of a way down that keeps failing at one place. pure, no game types, so the rules are
// unit-tested. a kit piece whose mine gave up its depth in a flooded cave failed over to the next piece, whose new mine
// started on the same ledge with a fresh place watch and stalled the same way, for each piece and each of its three tries
// (test heel's flooded cave, 13 Nov 2026)
public final class MineLeave {
    private MineLeave() {
    }

    // a spot given up counts for this long (30 game minutes) and this near (level distance)
    public static final long FRESH = 36000;
    public static final double NEAR = 24;
    // the leave ends this far from the spot (level distance); its goal lies a little past that, so that a goal reached
    // within its radius is past it too
    public static final double FAR = 48;
    public static final int TARGET = 56;
    public static final double TARGET_RADIUS = 6;
    // the spots kept, the oldest dropped first
    public static final int KEPT = 8;
    // a way back over the spot counts as this many more water blocks than a way straight away from it
    public static final double BACK_COST = 16;

    // the spots given up by any branch mine, shared by all of them: the next piece's mine is a new task
    public static final Spots GIVEN_UP = new Spots(KEPT);

    public record Spot(String dim, int x, int z, long at) {
    }

    public static final class Spots {
        private final int kept;
        private final java.util.ArrayDeque<Spot> spots = new java.util.ArrayDeque<>();

        public Spots(int kept) {
            this.kept = kept;
        }

        public synchronized void record(String dim, int x, int z, long now) {
            spots.addLast(new Spot(dim, x, z, now));
            while (spots.size() > kept) spots.removeFirst();
        }

        // the nearest spot in dim within near blocks (level distance) of (x, z), given up within fresh ticks of now and
        // not in skip; null when none. a spot from a later game time than now (another world's clock) does not count
        public synchronized Spot near(String dim, int x, int z, long now, double near, long fresh, java.util.Set<Spot> skip) {
            Spot best = null;
            double bestD = near * near;
            for (Spot s : spots) {
                if (!java.util.Objects.equals(s.dim(), dim) || now < s.at() || now - s.at() > fresh) continue;
                if (skip != null && skip.contains(s)) continue;
                double dx = s.x() - x, dz = s.z() - z, d = dx * dx + dz * dz;
                if (d <= bestD) {
                    best = s;
                    bestD = d;
                }
            }
            return best;
        }

        public synchronized int size() {
            return spots.size();
        }

        public synchronized void clear() {
            spots.clear();
        }
    }

    // the eight ways to leave by, east first and round
    public static final int[][] WAYS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    // the way to leave by next: the fewest water blocks seen that way (water[i] for WAYS[i]), plus BACK_COST for a way back
    // over the spot (away: from the spot to the buddy; none when zero), none within 45 degrees of a way tried before (tried:
    // a bit per way), the first of equals. -1 when none is left
    public static int nextWay(int[] water, double awayX, double awayZ, int tried) {
        double len = Math.hypot(awayX, awayZ);
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < WAYS.length; i++) {
            if (nearTried(i, tried)) continue;
            double cos = 0;
            if (len > 0) cos = (WAYS[i][0] * awayX + WAYS[i][1] * awayZ) / (Math.hypot(WAYS[i][0], WAYS[i][1]) * len);
            double score = water[i] + BACK_COST * (1 - cos) / 2;
            if (score < bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean nearTried(int i, int tried) {
        int n = WAYS.length;
        for (int k = -1; k <= 1; k++) {
            if ((tried & (1 << ((i + k + n) % n))) != 0) return true;
        }
        return false;
    }

    // the goal of a leave by that way: TARGET blocks from the spot, rounded to a column
    public static int[] target(int spotX, int spotZ, int way) {
        double len = Math.hypot(WAYS[way][0], WAYS[way][1]);
        return new int[]{spotX + (int) Math.round(WAYS[way][0] * TARGET / len), spotZ + (int) Math.round(WAYS[way][1] * TARGET / len)};
    }

    // far enough from the spot to end the leave
    public static boolean left(int x, int z, Spot spot) {
        return Math.hypot(x - spot.x(), z - spot.z()) >= FAR;
    }

    // a way down that keeps failing at one place: true at the count-th failure in a row within radius blocks (level
    // distance) of the first of them, each within gap ticks of the one before; a failure within SAME ticks of the last is
    // the same one (the search and its wander can report a stall on ticks close together). the count starts again after
    // it says true, after reset, and at a failure too far or too late to join the row
    public static final class Streak {
        public static final long SAME = 60;
        private final int count;
        private final double radius;
        private final long gap;
        private int n, ax, az;
        private long last;

        public Streak(int count, double radius, long gap) {
            this.count = count;
            this.radius = radius;
            this.gap = gap;
        }

        public boolean fail(int x, int z, long now) {
            if (n > 0 && now >= last && now - last < SAME) return false;
            double dx = x - ax, dz = z - az;
            if (n == 0 || now < last || now - last > gap || dx * dx + dz * dz > radius * radius) {
                n = 0;
                ax = x;
                az = z;
            }
            n++;
            last = now;
            if (n >= count) {
                n = 0;
                return true;
            }
            return false;
        }

        // the first failure of the row (where it fired, once it has)
        public int x() {
            return ax;
        }

        public int z() {
            return az;
        }

        public int count() {
            return n;
        }

        public void reset() {
            n = 0;
        }
    }
}
