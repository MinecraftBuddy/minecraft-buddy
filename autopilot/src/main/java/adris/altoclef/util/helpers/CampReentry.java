package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.core.BlockPos;

// the buddy's fork, Tuning camp_reentry (off by default): the world's half of CampReentryRules. the rods task marks the
// camp it holds every tick (mark); every walk of a goal task reports itself (walking), and one whose goal is the camp's
// cell or its door is counted by the watch and never wanders; the rods task asks shutOut and replaces the camp. with the
// switch off nothing is marked and every call here is a no
public final class CampReentry {
    // ticks since the rods task last marked the camp: it does not run while a pickup or another chain has the wheel
    static final long FRESH = 40;
    // one no-wander line in this long at most (ticks)
    static final long SAY_TICKS = 100;

    private static final CampReentryRules.Watch watch = new CampReentryRules.Watch();
    private static volatile BlockPos camp;
    private static volatile BlockPos door;
    private static volatile long markedAt = Long.MIN_VALUE / 2;
    private static BlockPos left;
    private static long leftAt = Long.MIN_VALUE / 2;
    private static long saidAt = Long.MIN_VALUE / 2;

    private CampReentry() {
    }

    public static boolean on() {
        return Tuning.get("camp_reentry", 0) > 0;
    }

    // the camp the rods task holds now (null: none, or a box still going up), the standing box's door (null: none), and
    // whether the feet are in it
    public static void mark(BlockPos c, BlockPos d, boolean in, long now) {
        camp = c;
        door = d;
        markedAt = now;
        watch.camp(c, in);
    }

    // why the buddy is shut out of the marked camp, once per walk back; null while it is not
    public static String shutOut(long now) {
        return watch.shutOut(now);
    }

    // a camp left for being shut out of: no wander near it for a while
    public static void left(BlockPos c, long now) {
        left = c;
        leftAt = now;
    }

    // the goal task's walk this tick, with its goal: true when it is a walk back into the marked camp (its cell or door, the
    // feet out of it, the mark fresh), which the watch counts; then it never wanders
    public static boolean walking(AltoClef mod, Goal goal) {
        BlockPos c = camp;
        if (c == null || !(goal instanceof GoalBlock g) || mod.getPlayer() == null || mod.getWorld() == null) return false;
        if (!on()) return false;
        long now = mod.getWorld().getGameTime();
        if (now < markedAt || now - markedAt > FRESH || watch.in() || !c.equals(watch.camp())) return false;
        BlockPos to = new BlockPos(g.x, g.y, g.z);
        if (!to.equals(c) && !to.equals(door)) return false;
        boolean near = mod.getPlayer().position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(c)) <= CampReentryRules.NEAR;
        watch.walk(now, mod.getClientBaritone().getPathingBehavior().calcFailures(), near);
        return near;
    }

    // no wander now for any walk: a camp left for being shut out of within NO_WANDER_TICKS, the feet within NEAR of it
    public static boolean noWander(AltoClef mod) {
        BlockPos c = left;
        if (c == null || mod.getPlayer() == null || mod.getWorld() == null || !on()) return false;
        long now = mod.getWorld().getGameTime();
        if (now < leftAt || now - leftAt > CampReentryRules.NO_WANDER_TICKS) return false;
        return mod.getPlayer().position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(c)) <= CampReentryRules.NEAR;
    }

    // the line for a wander not taken, at most once in SAY_TICKS
    public static void sayNoWander(AltoClef mod, Goal goal) {
        long now = mod.getWorld().getGameTime();
        if (now >= saidAt && now - saidAt < SAY_TICKS) return;
        saidAt = now;
        String to = goal instanceof GoalBlock g ? new BlockPos(g.x, g.y, g.z).toShortString() : String.valueOf(goal);
        Debug.logInternal("camp: no progress on the way to " + to + ", the path asked again instead of the wander");
    }
}
