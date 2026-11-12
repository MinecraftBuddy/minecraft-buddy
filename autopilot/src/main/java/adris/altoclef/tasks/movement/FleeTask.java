package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalFlee;
import adris.altoclef.util.helpers.PlaceWord;
import adris.altoclef.util.helpers.SafeStand;
import baritone.api.pathing.goals.Goal;
import baritone.pathing.movement.CliffAvoidance;
import java.util.List;
import net.minecraft.core.BlockPos;

// the buddy's fork (S137 step 3, Tuning risk_flee): the commitment's run as the flee search (GoalFlee). the run itself is the
// committed one (it never finishes on its own, a hard cancel only on the ground, the swim to the bank when afloat); its goal is
// the nearest calm cell instead of 24 blocks from the origin, and it is asked again sooner: a calm cell stops being calm as the
// mobs come, and the meter reads four times a second
public class FleeTask extends CommittedRunTask {

    static final long FLEE_REISSUE_TICKS = 10;

    // (S137, the repair after smoke7; Tuning risk_flee_keep, on under risk_flee) the flee search found its path: this run walks
    // it, and nothing takes the wheel back for the place the run left (the blaze fight's corner by its door, the ghast
    // shelter's cover). smoke7 r1: a path of 61 blocks found twice, each time taken back within the second by the walk to the
    // pillbox's door, the buddy standing 2 blocks from it in the blazes' fire. found: this task saw it; the last tick any flee
    // walked a found path, for the chains that would take the wheel
    private boolean _found;
    private static volatile long walkedAt = Long.MIN_VALUE / 2;
    // a found path counts this long after the flee was last ticked with it: a chain that won one tick hands it straight back
    static final long WALKED_TICKS = 10;

    public FleeTask() {
        super(0, 0, 0, List::of);
    }

    public static boolean keepOn() {
        return adris.altoclef.util.helpers.Tuning.get("risk_flee", 0) >= 1 && adris.altoclef.util.helpers.Tuning.get("risk_flee_keep", 1) > 0;
    }

    // this run's flee search has found its path and walks it
    public boolean found() {
        return _found;
    }

    // a flee walked a found path within WALKED_TICKS (a clock that went back counts as long ago)
    public static boolean walking(long now) {
        return keepOn() && now >= walkedAt && now - walkedAt <= WALKED_TICKS;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        var current = mod.getClientBaritone().getPathingBehavior().getCurrent();
        if (current != null && current.getPath().getGoal() instanceof GoalFlee) {
            _found = true;
            walkedAt = mod.getWorld().getGameTime();
        }
        return super.onTick(mod);
    }

    // the flee goal on the game thread's world: its cells, and the place word of a cell with the floor as it is (a rim is no
    // goal, and a fortress floor over the lava sea is no sea)
    public static GoalFlee goal(AltoClef mod) {
        return new GoalFlee(() -> SafeStand.cells(mod.getWorld()), mod::getWorld, cells -> {
            BlockPos.MutableBlockPos floor = new BlockPos.MutableBlockPos();
            return (x, y, z) -> PlaceWord.index(cells, CliffAvoidance.onFortressBricks(mod.getWorld().getBlockState(floor.set(x, y - 1, z))), x, y, z);
        });
    }

    @Override
    protected Goal runGoal(AltoClef mod) {
        return goal(mod);
    }

    @Override
    protected long reissueGap() {
        return FLEE_REISSUE_TICKS;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof FleeTask;
    }

    @Override
    protected String toDebugString() {
        return "Fleeing to calm ground";
    }
}
