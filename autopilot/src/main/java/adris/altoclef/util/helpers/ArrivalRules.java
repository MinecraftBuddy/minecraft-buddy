package adris.altoclef.util.helpers;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

// what to do in the first seconds after a dimension change: wait for the ground to exist, and back out of a portal that
// dumped us in lava. pure
public final class ArrivalRules {

    // the client has no chunks right after a dimension change, and the planner reads missing chunks as air. three seconds
    // is more than the server ever needs, the cap is only so a chunk that never comes can't freeze us
    public static final int HOLD_CAP_TICKS = 60;
    // how long after arriving a lava dunk still counts as the portal's doing
    public static final int STEP_BACK_TICKS = 200;
    // a step back that started keeps the wheel this long counting from its own start, whatever the start window said
    // (survival teleports after 80 ticks inside, plus a backoff or two on top)
    public static final int STEP_BACK_LATCH_TICKS = 500;
    // a portal that touched us right after a teleport holds its cooldown up while we stand in it, so a stand that goes
    // nowhere this long steps out for a moment and comes back. the transition needs 80 ticks inside, the rest is margin
    public static final int PORTAL_WAIT_TICKS = 90;
    // the cooldown is 10 ticks, so 11+ clears it
    public static final int PORTAL_BACKOFF_TICKS = 15;
    // a step back that never got into a portal block this long after it started is walking into a wall or into lava
    public static final int STEP_BACK_GIVE_UP_TICKS = 60;
    // after this many step backs at one portal the next ones are a loop between dimensions, the lava escape takes over
    public static final int MAX_STEP_BACKS = 2;
    public static final int SAME_PORTAL_RADIUS = 3;
    // a second lava arrival at the portal within this long gets a line in the log
    public static final long REPEAT_MS = 5 * 60 * 1000;
    // the box around the feet that has to be loaded, in blocks
    public static final int LOOK_RADIUS = 2;
    public static final int PORTAL_REACH = 2;

    private ArrivalRules() {
    }

    public static boolean hold(int ticksSinceArrival, boolean boxLoaded) {
        return !boxLoaded && ticksSinceArrival < HOLD_CAP_TICKS;
    }

    // `ticksSinceStart` >= 0 = a step back already started that long ago, it keeps going while we stand in the portal
    // waiting to be sent home (long after the lava is behind us), so only the start has the short window
    public static boolean stepBack(boolean nether, int ticksSinceArrival, boolean inLava, int ticksSinceStart, boolean portalNear) {
        if (!nether || !portalNear) return false;
        return ticksSinceStart >= 0 ? ticksSinceStart <= STEP_BACK_LATCH_TICKS : inLava && ticksSinceArrival <= STEP_BACK_TICKS;
    }

    public static boolean gaveUp(int ticksSinceStart, boolean wasInsidePortal) {
        return !wasInsidePortal && ticksSinceStart >= STEP_BACK_GIVE_UP_TICKS;
    }

    // `count` step backs so far at `last`, is another one at `now` a loop
    public static boolean loopCapped(BlockPos last, int count, BlockPos now) {
        return last != null && count >= MAX_STEP_BACKS && last.distSqr(now) <= SAME_PORTAL_RADIUS * SAME_PORTAL_RADIUS;
    }

    // the closest portal cell within reach of the feet, a little more up than down (the frame sits a block or two over us)
    public static BlockPos nearestPortal(BlockPos at, Predicate<BlockPos> isPortal) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -PORTAL_REACH; dx <= PORTAL_REACH; dx++) {
            for (int dy = -1; dy <= PORTAL_REACH; dy++) {
                for (int dz = -PORTAL_REACH; dz <= PORTAL_REACH; dz++) {
                    BlockPos p = at.offset(dx, dy, dz);
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < bestDist && isPortal.test(p)) {
                        best = p;
                        bestDist = d;
                    }
                }
            }
        }
        return best;
    }
}
