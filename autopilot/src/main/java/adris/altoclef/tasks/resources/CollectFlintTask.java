package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockNearbyTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class CollectFlintTask extends ResourceTask {
    private static final float CLOSE_ENOUGH_FLINT = 10;
    private static final float STAY_SLACK = 6;
    private static final long HOLD_TICKS = 100;

    private final int _count;
    private boolean _mineClose;
    private long _pickedAt = Long.MIN_VALUE / 2;

    public CollectFlintTask(int targetCount) {
        super(Items.FLINT, targetCount);
        _count = targetCount;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.GRAVEL);
        _mineClose = false;
        _pickedAt = Long.MIN_VALUE / 2;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {

        // We might just want to mine the closest gravel.
        Predicate<BlockPos> valid = validGravel -> WorldHelper.fallingBlockSafeToBreak(validGravel) && WorldHelper.canBreak(mod, validGravel);
        Optional<BlockPos> closest = mod.getBlockTracker().getNearestTracking(mod.getPlayer().position(), valid, Blocks.GRAVEL);
        // this and the mine task used to swap a few times a second (the gravel check flickers), and each swap threw away the
        // crack, so nothing ever broke. a wider leash to stay than to come in, a minimum hold per pick, and the same filter
        // for the pick so both go for the same block
        long now = mod.getWorld().getGameTime();
        double reach = _mineClose ? CLOSE_ENOUGH_FLINT + STAY_SLACK : CLOSE_ENOUGH_FLINT;
        boolean close = closest.isPresent() && closest.get().closerToCenterThan(mod.getPlayer().position(), reach);
        if (close != _mineClose && now - _pickedAt >= HOLD_TICKS) {
            _mineClose = close;
            _pickedAt = now;
        }
        // held even through a tick where the check blinks out, that blink is exactly the flicker
        if (_mineClose) {
            return new DoToClosestBlockTask(DestroyBlockTask::new, valid, Blocks.GRAVEL);
        }

        // If we have gravel, place it.
        if (mod.getItemStorage().hasItem(Items.GRAVEL)) {
            // Place it
            return new PlaceBlockNearbyTask(Blocks.GRAVEL);
        }

        // We don't have gravel and we need to search for flint. Grab some!
        return TaskCatalogue.getItemTask(Items.GRAVEL, 1);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.GRAVEL);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectFlintTask task) {
            return task._count == _count;
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " flint";
    }


}
