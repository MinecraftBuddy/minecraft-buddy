package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.core.BlockPos;

// "stack up right here": a goal block straight above us and baritone's pillar movement does the rest. GetToYTask would
// also go up but it is free to wander off sideways to find a hill, and the whole point is the column we stand in
final class PillarHereTask extends CustomBaritoneGoalTask {
    private final BlockPos top;

    PillarHereTask(BlockPos top) {
        // no wandering when stuck, the golem fight has its own clock and a stuck pillar is a give up
        super(false);
        this.top = top;
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalBlock(top);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PillarHereTask task && task.top.equals(top);
    }

    @Override
    protected String toDebugString() {
        return "Pillaring up to " + top.toShortString();
    }
}
