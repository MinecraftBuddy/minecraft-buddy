package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import adris.altoclef.tasksystem.Task;
import baritone.Baritone;
import baritone.altoclef.SettingsOverrides;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.core.BlockPos;

// the way down off a night pillar: baritone to the cell the pillar began in, with falls capped at 3 so the only way it has to
// lose height is to mine the pillar under its own feet one block at a time (MovementDownward, which wants a solid block two
// down: a column of our own blocks is exactly that). without the cap a water bucket in the bag makes a 12 block drop onto
// the ground the cheapest route, and the whole point of the pillar was not to be hurt
final class NightDescendTask extends CustomBaritoneGoalTask {
    private static final int FALL_CAP = 3;

    private final BlockPos ground;
    private Integer userNoWater;
    private Integer userBucket;

    NightDescendTask(BlockPos ground) {
        // wandering off a pillar is the last thing we want, the trip has its own clock
        super(false);
        this.ground = ground;
    }

    @Override
    protected void onStart(AltoClef mod) {
        super.onStart(mod);
        Settings s = Baritone.settings();
        if (userNoWater == null && !SettingsOverrides.isHeld(s.maxFallHeightNoWater)) {
            userNoWater = s.maxFallHeightNoWater.value;
        }
        if (userBucket == null && !SettingsOverrides.isHeld(s.maxFallHeightBucket)) {
            userBucket = s.maxFallHeightBucket.value;
        }
        SettingsOverrides.put(s.maxFallHeightNoWater, Math.min(s.maxFallHeightNoWater.value, FALL_CAP));
        SettingsOverrides.put(s.maxFallHeightBucket, Math.min(s.maxFallHeightBucket.value, FALL_CAP));
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        super.onStop(mod, interruptTask);
        Settings s = Baritone.settings();
        // putting the user's own value back is how the registry lets go of a setting
        if (userNoWater != null) {
            SettingsOverrides.put(s.maxFallHeightNoWater, userNoWater);
        }
        if (userBucket != null) {
            SettingsOverrides.put(s.maxFallHeightBucket, userBucket);
        }
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalBlock(ground);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof NightDescendTask task && task.ground.equals(ground);
    }

    @Override
    protected String toDebugString() {
        return "Climbing down to " + ground.toShortString();
    }
}
