package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import net.minecraft.core.BlockPos;

/**
 * Approaching a block can be done easily with baritone
 * but we have an issue: baritone requires that a goal be available.
 * <p>
 * For instance, say we want to approach the center of a lava pool.
 * That's not possible, so say we expect the bot to get as close as it can.
 * We have to specify the "radius", and this radius MUST be outside of the pool,
 * else baritone will get stuck and won't even try getting close.
 */
public class GetCloseToBlockTask extends Task {

    // the radius we ask baritone for until we are right next to the target. starting from the real distance and tightening
    // a block at a time sounds nicer but repaths on every single step (the goal is met a block later, every block), and
    // one path to the tightest radius is the whole point. Integer.MAX_VALUE here squares to 1 in an int, same goal
    static final int START_RANGE = 1;

    private final BlockPos _toApproach;
    private int _currentRange;

    public GetCloseToBlockTask(BlockPos toApproach) {
        _toApproach = toApproach;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _currentRange = START_RANGE;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // Always bump the range down if we've met it.
        // We have a strictly decreasing range, which means we will eventualy get
        // as close as we can.
        double distSq = mod.getPlayer().blockPosition().distSqr(_toApproach);
        if (within(_currentRange, distSq)) {
            _currentRange = shrunkRange(distSq);
        }
        return new GetWithinRangeOfBlockTask(_toApproach, _currentRange);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    // long math, an int range squared is what went wrong in the first place
    static boolean within(int range, double distSq) {
        long r = range;
        return distSq <= r * r;
    }

    // one block tighter than where we stand, never below the exact block and never past what GoalNear can square
    static int shrunkRange(double distSq) {
        return GetWithinRangeOfBlockTask.clampRange((int) Math.sqrt(distSq) - 1);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetCloseToBlockTask task) {
            return task._toApproach.equals(_toApproach);
        }
        return false;
    }

    @Override
    protected String toHudString() {
        return "Walking to " + HudText.pos(_toApproach);
    }

    @Override
    protected String toDebugString() {
        return "Approaching " + _toApproach.toShortString();
    }


}
