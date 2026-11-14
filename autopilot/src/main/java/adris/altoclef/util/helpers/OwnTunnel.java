package adris.altoclef.util.helpers;

// the tunnel of its own of a branch mine (BranchMineTask, mine_own_pit) kept over a mine made anew, and baritone's mine
// stopping for no path counted by place. pure, no game types, so the rules are unit-tested.
// (Tuning mine_own_keep, a fix of 14 Nov 2026, on) the tunnel and the band exits that lead to it live in the task, and a craft
// or a pickup between two ticks of the mine (stick_reserve's stone pickaxe, the table picked back up) stopped the whole tree
// and made the mine anew: it started baritone's legit mine again at once, which shuttled 88 blocks to and fro through an old
// tunnel of its own, every search "couldn't get more than 2.0 blocks", for 18 game minutes, and the same again after the
// next fire (the iron leggings at 6 of 7 raw iron, box 2 t8). so the spot, the height and the heading are kept here, per
// dimension, and a mine made anew for the same height (the ore's), within the tunnel's reach of the spot and at the tunnel's
// height, goes on with it
// (Tuning mine_no_path_tunnel, a fix of 14 Nov 2026, on) baritone's mine that stops for no path ("Unable to find any path ...
// canceling mine") is started again by the branch mine with a new branch point at the feet, so a mine whose way away from it
// is shut stops and starts for good, and none of the other checks fires (it moves, it starts once every 25 s). the second
// such stop within NO_PATH_WINDOW and NO_PATH_REACH of the one before switches the mine to its own tunnel, a quarter turned
// from the way between the two stops; a second switch within NO_PATH_REACH of an earlier one in FRESH gives the depth up
public final class OwnTunnel {
    private OwnTunnel() {
    }

    // a tunnel kept counts this long after its last step (30 game minutes, as a spot given up)
    public static final long FRESH = MineLeave.FRESH;
    // a mine made anew takes the tunnel on with its height within this many blocks of the tunnel's
    public static final int Y_NEAR = 3;
    // two stops for no path within this many ticks (10 game minutes) and blocks (level distance) of each other
    public static final long NO_PATH_WINDOW = 12000;
    public static final double NO_PATH_REACH = 100;

    // shared by every branch mine: the next one is a new task
    public static final State KEPT = new State();

    // the tunnel: the height its mine was for (the ore's), where it was begun (x, z), its height and heading, and when it last
    // stepped
    public record Kept(String dim, int target, int x, int y, int z, int dx, int dz, long at) {
    }

    public static final class State {
        private String dim;
        private Kept tunnel;
        // (mine_own_keep) the band exits of every mine of the dimension, as one mine's were (2 within ten blocks in ten
        // game minutes)
        private final PlaceWatch.Fires bandExits = new PlaceWatch.Fires(2, 12000, 10);
        // (mine_no_path_tunnel) the last stop for no path, and the last switch to a tunnel of its own for it
        private boolean hasStop, hasSwitch;
        private int stopX, stopZ, switchX, switchZ;
        private long stopAt, switchAt;

        // a call from another dimension forgets the rest
        private void dim(String d) {
            if (!java.util.Objects.equals(d, dim)) {
                clear();
                dim = d;
            }
        }

        public synchronized PlaceWatch.Fires bandExits(String d) {
            dim(d);
            return bandExits;
        }

        // the tunnel of a mine for the height target, begun at (x, z) at height y, heading (dx, dz), as of now; a step
        // refreshes it
        public synchronized void keep(String d, int target, int x, int y, int z, int dx, int dz, long now) {
            dim(d);
            tunnel = new Kept(d, target, x, y, z, dx, dz, now);
        }

        // the tunnel for a mine for the height target, mining at height y, standing at (x, z): one of a mine for the same
        // target within reach blocks (level distance) of its spot, its height within Y_NEAR, stepped within FRESH; null when
        // none. a later game time than now (another world's clock) does not count
        public synchronized Kept near(String d, int target, int x, int y, int z, long now, double reach) {
            Kept k = tunnel;
            if (k == null || !java.util.Objects.equals(k.dim(), d) || now < k.at() || now - k.at() > FRESH) return null;
            if (k.target() != target || Math.abs(k.y() - y) > Y_NEAR) return null;
            double dx = k.x() - x, dz = k.z() - z;
            return dx * dx + dz * dz <= reach * reach ? k : null;
        }

        public synchronized void drop() {
            tunnel = null;
        }

        // a stop of baritone's mine for no path at (x, z): the stop before it ({x, z}) when that one lies within
        // NO_PATH_WINDOW and NO_PATH_REACH, which makes the pair (and the count starts again), else null
        public synchronized int[] noPathStop(String d, int x, int z, long now) {
            dim(d);
            boolean pair = hasStop && now >= stopAt && now - stopAt <= NO_PATH_WINDOW
                    && Math.hypot(x - stopX, z - stopZ) <= NO_PATH_REACH;
            int[] before = pair ? new int[]{stopX, stopZ} : null;
            hasStop = !pair;
            stopX = x;
            stopZ = z;
            stopAt = now;
            return before;
        }

        // whether a switch to a tunnel of its own for no path was made within NO_PATH_REACH of (x, z) in FRESH
        public synchronized boolean switchedNear(String d, int x, int z, long now) {
            dim(d);
            return hasSwitch && now >= switchAt && now - switchAt <= FRESH && Math.hypot(x - switchX, z - switchZ) <= NO_PATH_REACH;
        }

        public synchronized void switched(String d, int x, int z, long now) {
            dim(d);
            hasSwitch = true;
            switchX = x;
            switchZ = z;
            switchAt = now;
        }

        // the depth given up: the switch is spent
        public synchronized void switchSpent() {
            hasSwitch = false;
        }

        // what is kept, for the run's measure: the tunnel, the last stop and the last switch
        public synchronized int size() {
            return (tunnel != null ? 1 : 0) + (hasStop ? 1 : 0) + (hasSwitch ? 1 : 0);
        }

        public synchronized void clear() {
            tunnel = null;
            bandExits.clear();
            hasStop = false;
            hasSwitch = false;
            dim = null;
        }
    }

    // the heading of the tunnel of its own after two stops for no path, at (ax, az) and then (bx, bz): a quarter turned
    // (as the branch mine turns: dx = -dz, dz = dx) from the way between them along its longer axis; with the two less than
    // two blocks apart, a quarter turned from the heading (dx, dz) the mine has
    public static int[] heading(int ax, int az, int bx, int bz, int dx, int dz) {
        int ox, oz;
        int ddx = bx - ax, ddz = bz - az;
        if (Math.max(Math.abs(ddx), Math.abs(ddz)) < 2) {
            ox = dx;
            oz = dz;
            if (ox == 0 && oz == 0) ox = 1;
        } else if (Math.abs(ddx) >= Math.abs(ddz)) {
            ox = Integer.signum(ddx);
            oz = 0;
        } else {
            ox = 0;
            oz = Integer.signum(ddz);
        }
        return new int[]{-oz, ox};
    }
}
