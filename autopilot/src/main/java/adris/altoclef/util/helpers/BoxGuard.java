package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

// the buddy's fork, Tuning box_no_wander (off by default, steering 88 of 6 Nov 2026): a walk that makes no progress while
// the feet are in the blaze camp's box never falls back on the goal task's wander. Of ten brew runs that died before the
// second rod that day, six ended outside the box the same way: a walk inside it ("Back into the corner", to a fire, to a
// rod) found no path one cell off, the wander took the buddy over the wall, and the way back in was never found. The plain
// rod drill did the same at the same boxes. BlazeLureTask marks its corner on every tick; the guard holds while that mark
// is fresh and the feet are within reach of it (the corner cell, the front cell, the look out)
public final class BoxGuard {
    // horizontal reach from the corner cell's middle, and how far up or down the feet may be (a raised box's steps)
    public static final double REACH = 2.0;
    public static final double UP = 2.0;
    // ticks since the lure last ran: its fire walks and rod pickups run as other tasks in between
    public static final long FRESH = 600;
    // the step by input to a goal one cell off: this many ticks at most, then the path is asked again
    public static final int STEP_TICKS = 20;

    private static volatile BlockPos corner;
    private static volatile long tick = Long.MIN_VALUE;

    private BoxGuard() {
    }

    public static void mark(BlockPos c, long now) {
        corner = c;
        tick = now;
    }

    // (box_home turns it on too: the box as home, BoxHomeRules)
    public static boolean on() {
        return Tuning.get("box_no_wander", 0) > 0 || BoxHomeRules.on();
    }

    // the box's corner while the switch is on, the lure ran lately and the feet are within its reach; else null
    public static BlockPos holds(AltoClef mod) {
        if (!on() || mod.getPlayer() == null || mod.getWorld() == null) return null;
        BlockPos c = corner;
        if (c == null) return null;
        long now = mod.getWorld().getGameTime();
        if (now < tick || now - tick > FRESH) return null;
        return near(mod.getPlayer().position(), c) ? c : null;
    }

    static boolean near(Vec3 at, BlockPos c) {
        double dx = at.x - (c.getX() + 0.5), dz = at.z - (c.getZ() + 0.5);
        return dx * dx + dz * dz <= REACH * REACH && Math.abs(at.y - c.getY()) <= UP;
    }

    // a goal the step by input may take: on the feet's level, at most one and a half blocks off (the next cell, a diagonal one)
    public static boolean stepTo(Vec3 at, BlockPos goal) {
        if (goal.getY() != (int) Math.floor(at.y + 1e-6)) return false;
        double dx = at.x - (goal.getX() + 0.5), dz = at.z - (goal.getZ() + 0.5);
        return dx * dx + dz * dz <= 1.5 * 1.5;
    }
}
