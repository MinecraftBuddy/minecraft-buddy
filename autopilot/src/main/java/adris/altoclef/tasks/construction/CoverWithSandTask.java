package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class CoverWithSandTask extends Task {
    private static final TimerGame timer = new TimerGame(30);
    private static final Task getSand = TaskCatalogue.getItemTask(Items.SAND, 128);
    private static final Task goToNether = new DefaultGoToDimensionTask(Dimension.NETHER);
    private static final Task goToOverworld = new DefaultGoToDimensionTask(Dimension.OVERWORLD);
    private BlockPos lavaPos;
    private boolean _searching;
    private boolean _noLava;

    @Override
    protected void onStart(AltoClef mod) {
        _searching = false;
        _noLava = false;
        timer.reset();
        mod.getBlockTracker().trackBlock(Blocks.LAVA);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // only the lava search wander counts, and only if we were already searching last tick: the wander under us is
        // kept across branches (all infinite wanders are equal), so the tick the branch flips it can still be the one
        // from getting sand or crossing dimensions
        boolean wasSearching = _searching;
        _searching = false;
        if (getSand != null && getSand.isActive() && !getSand.isFinished(mod)) {
            setDebugState("Getting sands to cover nether lava.");
            timer.reset();
            return getSand;
        }
        if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD &&
                mod.getItemStorage().getItemCount(Items.SAND) < 64) {
            timer.reset();
            return getSand;
        }
        if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD &&
                mod.getItemStorage().getItemCount(Items.SAND) > 64) {
            setDebugState("Going to nether.");
            timer.reset();
            return goToNether;
        }
        if (WorldHelper.getCurrentDimension() == Dimension.NETHER &&
                !mod.getItemStorage().hasItem(Items.SAND)) {
            setDebugState("Going to overworld to get sand.");
            timer.reset();
            return goToOverworld;
        }
        if (coverLavaWithSand(mod) == null) {
            setDebugState("Searching valid lava.");
            timer.reset();
            _searching = true;
            if (wasSearching && thisOrChildWanderFailing()) {
                _noLava = true;
            }
            return new TimeoutWanderTask();
        }
        setDebugState("Covering lava with sand");
        return coverLavaWithSand(mod);
    }

    private Task coverLavaWithSand(AltoClef mod) {
        Predicate<BlockPos> validLava = blockPos ->
                mod.getWorld().getBlockState(blockPos).getFluidState().isSource() &&
                        WorldHelper.isAir(mod, blockPos.above()) &&
                        (!WorldHelper.isBlock(mod, blockPos.north(), Blocks.LAVA) ||
                                !WorldHelper.isBlock(mod, blockPos.south(), Blocks.LAVA) ||
                                !WorldHelper.isBlock(mod, blockPos.east(), Blocks.LAVA) ||
                                !WorldHelper.isBlock(mod, blockPos.west(), Blocks.LAVA) ||
                                !WorldHelper.isBlock(mod, blockPos.north().above(), Blocks.LAVA) ||
                                !WorldHelper.isBlock(mod, blockPos.south().above(), Blocks.LAVA) ||
                                !WorldHelper.isBlock(mod, blockPos.east().above(), Blocks.LAVA) ||
                                !WorldHelper.isBlock(mod, blockPos.west().above(), Blocks.LAVA));
        Optional<BlockPos> lava = mod.getBlockTracker().getNearestTracking(validLava, Blocks.LAVA);
        if (lava.isPresent()) {
            if (lavaPos == null) {
                lavaPos = lava.get();
                timer.reset();
            }
            if (timer.elapsed()) {
                lavaPos = lava.get();
                timer.reset();
            }
            if (!WorldHelper.isBlock(mod, lavaPos, Blocks.LAVA) || (!WorldHelper.isAir(mod, lavaPos.above()) &&
                    !WorldHelper.isFallingBlock(lavaPos.above())) ||
                    !mod.getWorld().getBlockState(lavaPos).getFluidState().isSource()) {
                lavaPos = lava.get();
                timer.reset();
            }
            return new PlaceBlockTask(lavaPos.above(), Blocks.SAND);
        }
        return null;
    }

    // a command nobody is waiting on forever: the lava search wander can't even leave its spot, so there is nothing left
    // to try. a flag from the search branch and not thisOrChildWanderFailing, which would also end it for a stuck wander
    // down in the sand or dimension tasks
    @Override
    public boolean isFinished(AltoClef mod) {
        return _noLava;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.LAVA);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof CoverWithSandTask;
    }

    @Override
    protected String toHudString() {
        return "Covering lava with sand";
    }

    @Override
    protected String toDebugString() {
        return "Covering nether lava with sand";
    }
}
