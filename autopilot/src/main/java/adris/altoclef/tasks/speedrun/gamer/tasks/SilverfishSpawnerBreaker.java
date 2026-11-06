package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

// silverfish spawner seen: break it before it keeps feeding us silverfish (old BM2:369-377, plus the seen filter and a
// time cap so a spawner we cannot reach does not hold the run). never aims at infested blocks, those are not ours to find
final class SilverfishSpawnerBreaker {
    private static final double SECONDS_PER_SPAWNER = 60;
    private static final int LOOK_EVERY_TICKS = 10;

    private DestroyBlockTask task;
    private BlockPos pos;
    private double since;
    private boolean gaveUp;

    // the task to run this tick or null. the caller must be tracking Blocks.SPAWNER
    Task next(AltoClef mod, boolean enabled, int tick) {
        if (!enabled || gaveUp || !StrongholdScan.havePickaxe(mod)) {
            return null;
        }
        double now = mod.getWorld().getGameTime() / 20.0;
        if (task != null) {
            if (now - since > SECONDS_PER_SPAWNER) {
                gaveUp = true;
                task = null;
                return null;
            }
            if (!task.isFinished(mod) && mod.getWorld().getBlockState(pos).is(Blocks.SPAWNER)) {
                return task;
            }
            task = null;
        }
        if (tick % LOOK_EVERY_TICKS != 0) {
            return null;
        }
        Optional<BlockPos> found = StrongholdScan.silverfishSpawner(mod);
        if (found.isEmpty()) {
            return null;
        }
        pos = found.get();
        task = new DestroyBlockTask(pos);
        since = now;
        return task;
    }

    boolean active() {
        return task != null;
    }
}
