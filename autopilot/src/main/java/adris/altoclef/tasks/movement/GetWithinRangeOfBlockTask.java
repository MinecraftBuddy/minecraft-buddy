package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.core.BlockPos;

public class GetWithinRangeOfBlockTask extends CustomBaritoneGoalTask {

    // GoalNear squares its range in an int, and 46340^2 is the last square that fits. past that the goal quietly turns
    // into something tiny (Integer.MAX_VALUE squares to exactly 1, "within a block")
    static final int MAX_RANGE = 46340;

    private final BlockPos _blockPos;
    private final int _range;

    public GetWithinRangeOfBlockTask(BlockPos blockPos, int range) {
        _blockPos = blockPos;
        _range = clampRange(range);
    }

    static int clampRange(int range) {
        return Math.max(0, Math.min(range, MAX_RANGE));
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalNear(_blockPos, _range);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetWithinRangeOfBlockTask task) {
            return task._blockPos.equals(_blockPos) && task._range == _range;
        }
        return false;
    }

    @Override
    protected String toHudString() {
        return "Walking near " + HudText.pos(_blockPos);
    }

    @Override
    protected String toDebugString() {
        return "Getting within " + _range + " blocks of " + _blockPos.toShortString();
    }
}
