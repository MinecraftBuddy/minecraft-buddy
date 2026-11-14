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

    // (Tuning mine_water_streak, a fix of 14 Nov 2026, on) walk-outs of water under a roof, counted by place. a branch mine for
    // iron at y 16 walked up a cave into an underground pool at y 22, inside its band: the walk out of the water and the staircase
    // down from the dry cell took turns about once a second, the staircase's step leading back into the pool, and after the
    // search found no path a wander took the buddy down and baritone's mine walked back to the pool, laps of 60 to 75 s that
    // went 15 to 25 blocks off, so the place watch started again every lap (test heel, 14 Nov 2026). static, like the spots: a
    // task made anew after an interruption keeps the count
    public static final WalkOuts WALK_OUTS = new WalkOuts(3, 10, 2400);

    public static final class WalkOuts {
        // a walk-out that starts within this many blocks of the last dry cell stood on, or of a water cell of the last walk-out,
        // is one more of the same
        public static final double NEAR = 4;
        // the water cell walked out of counts for the staircase's step this long after the buddy was last in it
        public static final long KEEP = 1200;
        private final Streak streak;
        // the streak's fires, by place: the second within ten blocks in ten game minutes gives the depth up there, in a task
        // made anew too
        private final PlaceWatch.Fires fires = new PlaceWatch.Fires(2, 12000, 10);
        private String dim;
        private boolean has;
        // where the last walk-out began, the last water cell stood in (the one walked out of), and when
        private int sx, sy, sz, wx, wy, wz;
        private long wetAt;

        // count walk-outs in a row within radius blocks (level distance) of the first, each within gap ticks of the one before
        public WalkOuts(int count, double radius, long gap) {
            streak = new Streak(count, radius, gap);
        }

        // a walk-out starting at the water cell (x, y, z) in dim; dry is the last dry cell stood on ({x, y, z}) or null. true
        // when it makes the streak fire. one that starts away from the dry cell and from the last walk-out's water cells is
        // kept as the last walk-out but not counted
        public synchronized boolean start(String dim, int x, int y, int z, int[] dry, long now) {
            boolean same = has && java.util.Objects.equals(dim, this.dim);
            if (!same) {
                streak.reset();
                fires.clear();
            }
            boolean near = dry != null && within(x, y, z, dry[0], dry[1], dry[2])
                    || same && (within(x, y, z, sx, sy, sz) || within(x, y, z, wx, wy, wz));
            this.dim = dim;
            has = true;
            sx = wx = x;
            sy = wy = y;
            sz = wz = z;
            wetAt = now;
            if (!near || !streak.fail(x, z, now)) return false;
            fires.add(streak.x(), streak.z(), now);
            return true;
        }

        // the buddy still in the water of this walk-out, at (x, y, z): the cell walked out of, so far
        public synchronized void wet(String dim, int x, int y, int z, long now) {
            if (!has || !java.util.Objects.equals(dim, this.dim)) return;
            wx = x;
            wy = y;
            wz = z;
            wetAt = now;
        }

        // the water cell last walked out of ({x, y, z}), when the buddy was in it within KEEP ticks of now in dim; else null
        public synchronized int[] cell(String dim, long now) {
            if (!has || !java.util.Objects.equals(dim, this.dim) || now < wetAt || now - wetAt > KEEP) return null;
            return new int[]{wx, wy, wz};
        }

        // the walk-outs in the row so far, and where the row began (where it fired, once it has)
        public synchronized int count() {
            return streak.count();
        }

        public synchronized int x() {
            return streak.x();
        }

        public synchronized int z() {
            return streak.z();
        }

        // the streak's fires near the latest, at its last fire
        public synchronized int fires() {
            return fires.last();
        }

        // the depth given up: its fires start again
        public synchronized void clearFires() {
            fires.clear();
        }

        public synchronized void clear() {
            streak.reset();
            fires.clear();
            has = false;
            dim = null;
        }

        private static boolean within(int x, int y, int z, int ax, int ay, int az) {
            double dx = x - ax, dy = y - ay, dz = z - az;
            return dx * dx + dy * dy + dz * dz <= NEAR * NEAR;
        }
    }
}
