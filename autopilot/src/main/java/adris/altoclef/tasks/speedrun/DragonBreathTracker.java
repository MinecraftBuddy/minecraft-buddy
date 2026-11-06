package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalRunAway;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.AreaEffectCloud;

public class DragonBreathTracker {
    // clouds further than this cannot matter this tick and the box to block expansion is the only cost here
    private static final double CLOUD_RANGE = 24;
    private final HashSet<BlockPos> _breathBlocks = new HashSet<>();
    private long _lastTick = -1;

    public void updateBreath(AltoClef mod) {
        // the chains and the dragon phase all ask every tick, the answer cannot change inside one game tick
        long tick = mod.getWorld() == null ? -1 : mod.getWorld().getGameTime();
        if (tick >= 0 && tick == _lastTick) {
            return;
        }
        _lastTick = tick;
        _breathBlocks.clear();
        // every cloud close by, not just the nearest: a perched dragon leaves a whole trail of them and stepping
        // out of the nearest one into the next is not dodging anything
        for (AreaEffectCloud cloud : mod.getEntityTracker().getTrackedEntities(AreaEffectCloud.class)) {
            if (!cloud.closerThan(mod.getPlayer(), CLOUD_RANGE)) {
                continue;
            }
            for (BlockPos bad : WorldHelper.getBlocksTouchingBox(mod, cloud.getBoundingBox())) {
                _breathBlocks.add(bad);
            }
        }
    }

    public boolean isNearDragonBreath(BlockPos pos, int blocks) {
        return anyWithin(_breathBlocks, pos, blocks);
    }

    static boolean anyWithin(Iterable<BlockPos> cells, BlockPos pos, int blocks) {
        for (BlockPos bad : cells) {
            if (bad.closerThan(pos, blocks)) return true;
        }
        return false;
    }

    public boolean isTouchingDragonBreath(BlockPos pos) {
        return _breathBlocks.contains(pos);
    }

    public Task getRunAwayTask() {
        return new RunAwayFromDragonsBreathTask();
    }

    private class RunAwayFromDragonsBreathTask extends CustomBaritoneGoalTask {

        @Override
        protected void onStart(AltoClef mod) {
            super.onStart(mod);
            mod.getBehaviour().push();
            mod.getBehaviour().setBlockPlacePenalties(Double.POSITIVE_INFINITY);
            // do NOT ever wander
            _checker = new MovementProgressChecker((int) Float.POSITIVE_INFINITY);
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            super.onStop(mod, interruptTask);
            mod.getBehaviour().pop();
        }

        @Override
        protected Goal newGoal(AltoClef mod) {
            return new GoalRunAway(10, _breathBlocks.toArray(BlockPos[]::new));
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof RunAwayFromDragonsBreathTask;
        }

        @Override
        protected String toHudString() {
            return "Dodging dragon's breath";
        }

        @Override
        protected String toDebugString() {
            return "ESCAPE Dragons Breath";
        }
    }
}
