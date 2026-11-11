package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalFlee;
import adris.altoclef.util.helpers.SafeStand;
import baritone.api.pathing.goals.Goal;
import java.util.List;

// the buddy's fork (S137 step 3, Tuning risk_flee): the commitment's run as the flee search (GoalFlee). the run itself is the
// committed one (it never finishes on its own, a hard cancel only on the ground, the swim to the bank when afloat); its goal is
// the nearest calm cell instead of 24 blocks from the origin, and it is asked again sooner: a calm cell stops being calm as the
// mobs come, and the meter reads four times a second
public class FleeTask extends CommittedRunTask {

    static final long FLEE_REISSUE_TICKS = 10;

    public FleeTask() {
        super(0, 0, 0, List::of);
    }

    // the flee goal on the game thread's world
    public static GoalFlee goal(AltoClef mod) {
        return new GoalFlee(() -> SafeStand.cells(mod.getWorld()), mod::getWorld);
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
