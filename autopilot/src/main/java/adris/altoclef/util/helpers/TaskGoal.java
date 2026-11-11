package adris.altoclef.util.helpers;

import adris.altoclef.util.baritone.GoalDodgeProjectiles;
import adris.altoclef.util.baritone.GoalFlee;
import adris.altoclef.util.baritone.GoalRunAwayFromCrowd;
import adris.altoclef.util.baritone.GoalRunAwayFromEntities;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalInverted;
import baritone.api.pathing.goals.GoalRunAway;

// the buddy's fork (S137, a place goal by a safer route): where the task's own walk is headed. no task declares it: it is the
// goal the pathfinder has while the task has the wheel, read once a tick (DangerShadow), and kept while another chain has
// the wheel (a fight, a run, a meal) and while the task stands at it with no walk on (the work there). a goal that leads
// away from something (a run of the task's own, a dodge) is no place to get to. game thread only
public final class TaskGoal {

    // the task had the wheel at most this many ticks ago: it has it (the meter's chain asks before the task's tick)
    static final long NOW = 2;

    private static Goal goal;
    private static long seenAt;
    private static long wheelAt = Long.MIN_VALUE / 2;
    private static Object world;

    private TaskGoal() {}

    // once a tick: whether the task has the wheel, and the pathfinder's goal
    public static void note(long now, Object inWorld, boolean taskHasWheel, Goal pathGoal) {
        if (inWorld != world) {
            clear();
            world = inWorld;
        }
        if (!taskHasWheel) return;
        wheelAt = now;
        // (no goal: the task stands or works, the place it walked to stays)
        if (pathGoal == null) return;
        if (!place(pathGoal)) {
            goal = null;
            return;
        }
        goal = pathGoal;
        seenAt = now;
    }

    public static void clear() {
        goal = null;
        world = null;
        wheelAt = Long.MIN_VALUE / 2;
    }

    // the task has the wheel
    public static boolean hasWheel(long now, Object inWorld) {
        return world == inWorld && now >= wheelAt && now - wheelAt <= NOW;
    }

    // the goal of the task's walk, while that walk is on
    public static Goal walking(long now, Object inWorld) {
        return within(now, inWorld, NOW);
    }

    // the goal the task's walk had at most `ticks` ago
    public static Goal within(long now, Object inWorld, long ticks) {
        return goal != null && world == inWorld && now >= seenAt && now - seenAt <= ticks ? goal : null;
    }

    // a goal to get to, not one that leads away from something
    public static boolean place(Goal goal) {
        return goal != null && !(goal instanceof GoalFlee) && !(goal instanceof GoalRunAwayFromEntities)
                && !(goal instanceof GoalRunAwayFromCrowd) && !(goal instanceof GoalDodgeProjectiles)
                && !(goal instanceof GoalRunAway) && !(goal instanceof GoalInverted);
    }
}
