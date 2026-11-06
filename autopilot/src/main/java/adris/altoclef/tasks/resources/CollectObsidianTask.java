package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.PlaceObsidianBucketTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class CollectObsidianTask extends ResourceTask {

    private final TimerGame _placeWaterTimeout = new TimerGame(6);
    private final MovementProgressChecker _lavaTimeout = new MovementProgressChecker();
    private final int _count;
    private Task _forceCompleteTask = null;

    private PlaceObsidianBucketTask _placeObsidianTask;

    public CollectObsidianTask(int count) {
        super(Items.OBSIDIAN, count);
        _count = count;
    }

    private static BlockPos getGoodObsidianPosition(AltoClef mod) {
        BlockPos start = mod.getPlayer().blockPosition().offset(-3, -3, -3);
        BlockPos end = mod.getPlayer().blockPosition().offset(3, 3, 3);
        for (BlockPos pos : WorldHelper.scanRegion(mod, start, end)) {
            if (!WorldHelper.canBreak(mod, pos) || !WorldHelper.canPlace(mod, pos)) {
                return null;
            }
        }
        return mod.getPlayer().blockPosition();
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();

        mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.SOURCE_ONLY);

        mod.getBlockTracker().trackBlock(Blocks.OBSIDIAN);
        //mod.getBlockTracker().trackBlock(Blocks.WATER);
        //mod.getBlockTracker().trackBlock(Blocks.LAVA);
    }

    @Override
    protected adris.altoclef.tasksystem.Task onResourceTick(AltoClef mod) {

        // Get a diamond pickaxe FIRST
        if (!StorageHelper.miningRequirementMet(mod, MiningRequirement.DIAMOND)) {
            setDebugState("Getting diamond pickaxe first");
            return new SatisfyMiningRequirementTask(MiningRequirement.DIAMOND);
        }

        if (_forceCompleteTask != null && _forceCompleteTask.isActive() && !_forceCompleteTask.isFinished(mod)) {
            return _forceCompleteTask;
        }

        Predicate<BlockPos> goodObsidian = (blockPos ->
                blockPos.closerToCenterThan(mod.getPlayer().position(), 800)
                        && WorldHelper.canBreak(mod, blockPos)
                        && !WorldHelper.dropWouldBurn(mod, blockPos)
        );

        /*
        // Check for nearby obsidian
        // WHY do we do this?
        //      - because our jank 'portal' task protects our obsidian.
        boolean obsidianNearby = false;
        BlockPos start = mod.getPlayer().getBlockPos().add(-3, -3, -3);
        BlockPos end = mod.getPlayer().getBlockPos().add(3, 3, 3);
        for (BlockPos pos : WorldUtil.scanRegion(mod, start, end)) {
            if (mod.getBlockTracker().blockIsValid(pos, Blocks.OBSIDIAN) && !badObsidian.test(pos)) {
                obsidianNearby = true;
                break;
            }
        }
         */
        if (/*obsidianNearby || */mod.getBlockTracker().anyFound(goodObsidian, Blocks.OBSIDIAN) || mod.getEntityTracker().itemDropped(Items.OBSIDIAN)) {
            /*
            // Clear nearby water
            BlockPos nearestObby = mod.getBlockTracker().getNearestTracking(mod.getPlayer().getPos(), Blocks.OBSIDIAN);
            if (nearestObby != null) {
                BlockPos nearestWater = mod.getBlockTracker().getNearestTracking(WorldWorldHelper.toVec3d(nearestObby), blockPos -> !WorldUtil.isSourceBlock(mod, blockPos, true), Blocks.WATER);

                if (nearestWater != null && nearestWater.getSquaredDistance(nearestObby) < 5 * 5) {
                    _forceCompleteTask = new ClearLiquidTask(nearestWater);
                    setDebugState("Clearing water nearby obsidian");
                    return _forceCompleteTask;
                }
            }
             */

            setDebugState("Mining/Collecting obsidian");
            _placeObsidianTask = null;
            // lava crust obsidian is 9 seconds of digging to watch the drop burn. leave it be, the bucket cast below scoops that
            // same lava and makes obsidian that lands on something. obsidian with a floor under it still beats a cast
            return new MineAndCollectTask(new ItemTarget(Items.OBSIDIAN, _count), new Block[]{Blocks.OBSIDIAN}, MiningRequirement.DIAMOND)
                    .onlyWhere(p -> !WorldHelper.dropWouldBurn(mod, p));
        }

        if (WorldHelper.getCurrentDimension() == Dimension.NETHER) {
            final double AVERAGE_GOLD_PER_OBSIDIAN = 11.475;
            int gold_buffer = (int) (AVERAGE_GOLD_PER_OBSIDIAN * _count);
            setDebugState("We can't place water, so we're trading for obsidian");
            return new TradeWithPiglinsTask(gold_buffer, Items.OBSIDIAN, _count);
        }

        // the cast bans its own cell when it's ruined or its lava got loose, and then sits there waiting for someone to
        // move it. that someone is us
        if (_placeObsidianTask != null && mod.getBlockTracker().unreachable(_placeObsidianTask.getPos())) {
            _placeObsidianTask = null;
        }
        if (_placeObsidianTask == null) {
            BlockPos goodPos = getGoodObsidianPosition(mod);
            if (goodPos != null && !mod.getBlockTracker().unreachable(goodPos)) {
                _placeObsidianTask = new PlaceObsidianBucketTask(goodPos);
            } else {
                setDebugState("Walking until we find a spot to place obsidian");
                return new TimeoutWanderTask();
            }
        }
        // lmfao
        setDebugState("Placing Obsidian");
        return _placeObsidianTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, adris.altoclef.tasksystem.Task interruptTask) {
        //mod.getBlockTracker().stopTracking(Blocks.LAVA);
        //mod.getBlockTracker().stopTracking(Blocks.WATER);
        mod.getBlockTracker().stopTracking(Blocks.OBSIDIAN);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectObsidianTask task) {
            return task._count == _count;
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " blocks of obsidian";
    }
}
