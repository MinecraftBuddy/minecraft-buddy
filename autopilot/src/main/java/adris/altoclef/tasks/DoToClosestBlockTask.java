package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.DropWatch;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.ui.HudText;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Finds the closest reachable block and runs a task on that block.
 */
public class DoToClosestBlockTask extends AbstractDoToClosestObjectTask<BlockPos> {

    private final Block[] _targetBlocks;

    private final Supplier<Vec3> _getOriginPos;
    private final Function<Vec3, Optional<BlockPos>> _getClosest;

    private final Function<BlockPos, Task> _getTargetTask;

    private final Predicate<BlockPos> _isValid;

    // set by expectDrops: wait for the drop of a block we broke before picking the next one
    // we break from arm length, and a drop that fell further than this from the block is somebody else's problem
    private static final double BREAK_REACH_SQ = 6.5 * 6.5;
    private static final double DROP_RADIUS = 5;
    private boolean _expectDrops;

    public DoToClosestBlockTask(Supplier<Vec3> getOriginSupplier, Function<BlockPos, Task> getTargetTask, Function<Vec3, Optional<BlockPos>> getClosestBlock, Predicate<BlockPos> isValid, Block... blocks) {
        _getOriginPos = getOriginSupplier;
        _getTargetTask = getTargetTask;
        _getClosest = getClosestBlock;
        _isValid = isValid;
        _targetBlocks = blocks;
    }

    public DoToClosestBlockTask(Function<BlockPos, Task> getTargetTask, Function<Vec3, Optional<BlockPos>> getClosestBlock, Predicate<BlockPos> isValid, Block... blocks) {
        this(null, getTargetTask, getClosestBlock, isValid, blocks);
    }

    public DoToClosestBlockTask(Function<BlockPos, Task> getTargetTask, Predicate<BlockPos> isValid, Block... blocks) {
        this(null, getTargetTask, null, isValid, blocks);
    }

    public DoToClosestBlockTask(Function<BlockPos, Task> getTargetTask, Block... blocks) {
        this(getTargetTask, null, blockPos -> true, blocks);
    }

    // after one of the blocks goes (we broke it, it is air and in reach) stand still until what it dropped shows up, so the
    // next block isn't picked with the item still on its way. the drop is then up to whoever is above us, see isFinished
    public DoToClosestBlockTask expectDrops() {
        _expectDrops = true;
        return this;
    }

    @Override
    protected void onPursuitGone(AltoClef mod, BlockPos gone) {
        if (!_expectDrops || !mod.getWorld().getBlockState(gone).isAir()) {
            return;
        }
        Vec3 spot = Vec3.atCenterOf(gone);
        if (mod.getPlayer().getEyePosition().distanceToSqr(spot) <= BREAK_REACH_SQ) {
            expectDrop(false, spot);
        }
    }

    @Override
    protected boolean dropSeen(AltoClef mod, Vec3 spot) {
        return DropWatch.anyNear(mod, spot, DROP_RADIUS);
    }

    // the drop we waited for is there. finished is how this tells the plan above to go and get it (the pickup is not ours
    // to do, and picking the next block instead is the thing that left it lying there)
    @Override
    public boolean isFinished(AltoClef mod) {
        return _expectDrops && dropReady();
    }

    @Override
    protected Vec3 getPos(AltoClef mod, BlockPos obj) {
        return WorldHelper.toVec3d(obj);
    }

    @Override
    protected Optional<BlockPos> getClosestTo(AltoClef mod, Vec3 pos) {
        if (_getClosest != null) {
            return _getClosest.apply(pos);
        }
        return mod.getBlockTracker().getNearestTracking(pos, _isValid, _targetBlocks);
    }

    @Override
    protected Vec3 getOriginPos(AltoClef mod) {
        if (_getOriginPos != null) {
            return _getOriginPos.get();
        }
        return mod.getPlayer().position();
    }

    @Override
    protected boolean stillLooking(AltoClef mod) {
        return mod.getBlockTracker().scanPending(_targetBlocks);
    }

    @Override
    protected Task getGoalTask(BlockPos obj) {
        return _getTargetTask.apply(obj);
    }

    @Override
    protected boolean isValid(AltoClef mod, BlockPos obj) {
        // Assume we're valid since we're in the same chunk.
        if (!mod.getChunkTracker().isChunkLoaded(obj)) return true;
        // Our valid predicate
        if (_isValid != null && !_isValid.test(obj)) return false;
        // Correct block
        return mod.getBlockTracker().blockIsValid(obj, _targetBlocks);
    }

    @Override
    protected void onStart(AltoClef mod) {
        forgetDropExpect();
        mod.getBlockTracker().trackBlock(_targetBlocks);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(_targetBlocks);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DoToClosestBlockTask task) {
            return Arrays.equals(task._targetBlocks, _targetBlocks);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Doing something to closest block...";
    }

    @Override
    protected String toHudString() {
        return "Finding the nearest " + HudText.blocks(_targetBlocks);
    }

    @Override
    protected boolean isHudPlumbing() {
        return true;
    }
}