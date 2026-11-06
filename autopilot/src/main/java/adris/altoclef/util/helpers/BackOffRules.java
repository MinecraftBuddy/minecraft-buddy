package adris.altoclef.util.helpers;

// the kill task's "too close, step back" run-away, as answers. no world in here, the controller reads the cells and the
// task feeds the positions. the back-off exists so a swing lands at the edge of reach and a hop has room; in a shaft or a
// 2 high tunnel there is no edge to find, and a path that cannot go anywhere only owns the keys the swing needed
public final class BackOffRules {

    // a back-off that has not moved us this far in this many ticks is not going anywhere
    public static final int STUCK_TICKS = 10;
    public static final double STUCK_BLOCKS = 0.5;
    // and the next one does not start for this long, or the stuck one would come right back the tick after it was dropped
    public static final int MUTE_TICKS = 60;

    private BackOffRules() {
    }

    // sideRoom: some horizontal neighbour of our feet can be stood in (open at feet and head height). headroom: the same cell
    // check the crit hop vetoes on, nothing solid two above the feet. both, or the plan never starts a run-away
    public static boolean roomToBackOff(boolean sideRoom, boolean headroom) {
        return sideRoom && headroom;
    }

    // one per live back-off goal. asked once a tick while the goal is ours, says when it should be dropped
    public static final class Watch {
        private long windowStart = Long.MIN_VALUE;
        private double startX;
        private double startZ;

        public boolean stuck(long now, double x, double z) {
            if (windowStart == Long.MIN_VALUE || now < windowStart) {
                windowStart = now;
                startX = x;
                startZ = z;
                return false;
            }
            if (now - windowStart < STUCK_TICKS) {
                return false;
            }
            boolean moved = Math.hypot(x - startX, z - startZ) >= STUCK_BLOCKS;
            windowStart = now;
            startX = x;
            startZ = z;
            return !moved;
        }

        public void clear() {
            windowStart = Long.MIN_VALUE;
        }
    }
}
