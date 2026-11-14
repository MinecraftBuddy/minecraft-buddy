package adris.altoclef.util.helpers;

// (the buddy's fork, Tuning log_far_surface, a fix of 14 Nov 2026, on) logs seen from deep under the ground. the buddy needed
// sticks at y -24, the nearest log was a spruce 50 blocks over the cave, every search to a log timed out and each one struck its
// tree: tree after tree banned for five minutes, the bans ran out together and it began again (test heel, look 88). a log far over
// the feet of a buddy under a roof is no fault of the tree: a timed-out search to it strikes nothing and the buddy goes up to the
// surface first, and three trees given up in a row with no log mined is "out of reach from here" and the same climb. the climb
// is SmeltSurface's: a goal of the sky's height over the column, baritone's way up (the open staircase or pit it came by when that
// is cheapest, else it digs or pillars from the bag), a new height when it gets there still under a roof. pure, the caller passes
// the heights and the clock (game ticks)
public final class LogReach {
    // the log this much over the feet and more is far
    public static final int FAR_ABOVE = 16;
    // under a roof: more than this between the feet and the sky's height over the column (a two deep pit is in the open)
    public static final int ROOF = 2;
    // the climb is over this close to the sky, or within SHALLOW once at the height it walked to
    public static final int SHALLOW = 8;
    // trees given up in a row with no log mined, and it is out of reach from here
    public static final int ROW = 3;
    // the whole climb gets three game minutes, then the trees from down here, the old way, for five
    public static final long CLIMB_CAP = 3600;
    public static final long OFF_AFTER = 6000;

    private LogReach() {
    }

    public static boolean on() {
        return Tuning.get("log_far_surface", 0) > 0;
    }

    // a timed-out search (or a stall) on a log from here is no strike on its tree: the way up first
    public static boolean farFromHere(int depthBelowSky, int logAboveFeet) {
        return depthBelowSky > ROOF && logAboveFeet > FAR_ABOVE;
    }

    public static boolean outOfReach(int treesInARow) {
        return treesInARow >= ROW;
    }

    // the row of trees given up: a log mined since the last one starts it over
    public static int row(int treesBefore, int logsThen, int logsNow) {
        return (logsNow > logsThen ? 0 : treesBefore) + 1;
    }

    public enum Step {ARRIVED, ON, RETARGET, GAVE_UP}

    // where a climb that is under way stands: in the open (or within SHALLOW at the height it walked to) it is over; at that
    // height and still deep, a new height from the sky over here; past its cap, given up
    public static Step step(int feetY, int climbY, int depthBelowSky, long since, long now) {
        if (depthBelowSky <= ROOF) {
            return Step.ARRIVED;
        }
        if (now - since > CLIMB_CAP || now < since) {
            return Step.GAVE_UP;
        }
        if (feetY >= climbY) {
            return depthBelowSky <= SHALLOW ? Step.ARRIVED : Step.RETARGET;
        }
        return Step.ON;
    }
}
