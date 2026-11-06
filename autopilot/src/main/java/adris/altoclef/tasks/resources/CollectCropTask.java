package adris.altoclef.tasks.resources;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.CropRules;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class CollectCropTask extends ResourceTask {

    private final ItemTarget _cropToCollect;
    private final Item[] _cropSeed;
    private final Predicate<BlockPos> _canBreak;
    private final Block[] _cropBlock;

    // 200 ticks on one cell and it is somebody else's problem
    private final ReplantBook<BlockPos> _holes = new ReplantBook<>(200,
            dud -> Debug.logInternal("replant: gave up on " + dud.toShortString() + ", it never kept a seed"));
    // crops we have started on. a hole only counts once the cell is really air, the swing starting is not the crop leaving
    private final Set<BlockPos> _started = new HashSet<>();
    private CropPhase _phase = CropPhase.BREAK;
    // same task every tick while we stay on a cell, the click needs a few ticks of looking before it lands
    private InteractWithBlockTask _replantTask;
    private BlockPos _replantAt;

    private final Task _collectSeedTask;

    // To prevent infinite chunk-unload-reload loop bug
    private final HashSet<BlockPos> _wasFullyGrown = new HashSet<>();

    public CollectCropTask(ItemTarget cropToCollect, Block[] cropBlock, Item[] cropSeed, Predicate<BlockPos> canBreak) {
        super(cropToCollect);
        _cropToCollect = cropToCollect;
        _cropSeed = cropSeed;
        _canBreak = canBreak;
        _cropBlock = cropBlock;
        _collectSeedTask = new PickupDroppedItemTask(new ItemTarget(cropSeed, 1), true);
    }

    public CollectCropTask(ItemTarget cropToCollect, Block[] cropBlock, Item... cropSeed) {
        this(cropToCollect, cropBlock, cropSeed, canBreak -> true);
    }

    public CollectCropTask(ItemTarget cropToCollect, Block cropBlock, Item... cropSeed) {
        this(cropToCollect, new Block[]{cropBlock}, cropSeed);
    }

    public CollectCropTask(Item cropItem, int count, Block cropBlock, Item... cropSeed) {
        this(new ItemTarget(cropItem, count), cropBlock, cropSeed);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(_cropBlock);
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        sweepStarted(mod);
        _holes.refresh(WorldHelper.getTicks(), pos -> isEmptyCrop(mod, pos));
        boolean haveSeed = mod.getItemStorage().hasItem(_cropSeed);
        boolean replantOn = Baritone.settings().replantCrops.value;

        // Collect seeds if we need to.
        if (_holes.any() && replantOn && !haveSeed) {
            if (_collectSeedTask.isActive() && !_collectSeedTask.isFinished(mod)) {
                setDebugState("Picking up dropped seeds");
                return _collectSeedTask;
            }
            if (mod.getEntityTracker().itemDropped(_cropSeed)) {
                Optional<ItemEntity> closest = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().position(), _cropSeed);
                if (closest.isPresent() && closest.get().closerThan(mod.getPlayer(), 7)) {
                    // Trigger the collection of seeds.
                    return _collectSeedTask;
                }
            }
        }

        // one phase at a time: replant until the holes in reach are full, break until the next one opens. the old code asked
        // "is there a hole and a seed" fresh every tick, and a click changes both answers at once (the predicted plant eats
        // the seed and fills the cell), so every click flipped us to breaking and a server revert flipped us back. the old
        // replant also went through DoToClosestBlockTask, whose validity check asked the block tracker about an air cell
        // over farmland (never valid), so its pursuit was thrown away and rebuilt every tick
        if (!haveSeed) {
            _holes.release();
        }
        Vec3 at = mod.getPlayer().position();
        ToDoubleFunction<BlockPos> dist = pos -> pos.distToCenterSqr(at);
        _phase = CropPhase.next(_phase, replantOn, haveSeed, _holes.near(dist, REPLANT_RANGE_SQ), _holes.committed());
        if (_phase == CropPhase.REPLANT) {
            BlockPos cell = _holes.pick(WorldHelper.getTicks(), dist, REPLANT_RANGE_SQ);
            if (cell != null) {
                setDebugState("Replanting...");
                if (!cell.equals(_replantAt)) {
                    _replantAt = cell;
                    _replantTask = new InteractWithBlockTask(new ItemTarget(_cropSeed, 1), Direction.UP, cell.below(), true);
                }
                return _replantTask;
            }
            _phase = CropPhase.BREAK;
        }
        _replantAt = null;
        _replantTask = null;

        // grown ones only, whatever the crop and whether or not we replant. a young one yields the one item we planted,
        // and the carrot branch of this used to take them (only wheat was asked), so a field of seedlings kept us busy
        Predicate<BlockPos> validCrop = blockPos -> _canBreak.test(blockPos) && isMature(mod, blockPos);

        // Dimension
        if (isInWrongDimension(mod) && !mod.getBlockTracker().anyFound(validCrop, _cropBlock)) {
            return getToCorrectDimensionTask(mod);
        }

        // Break crop blocks.
        setDebugState("Breaking crops.");
        return new DoToClosestBlockTask(
                blockPos -> {
                    _started.add(blockPos);
                    return new DestroyBlockTask(blockPos);
                },
                validCrop,
                _cropBlock
        ).expectDrops();
    }

    // crops we swung at become holes when they are gone, and are forgotten when something else took the spot
    private void sweepStarted(AltoClef mod) {
        _started.removeIf(pos -> {
            if (isEmptyCrop(mod, pos)) {
                _holes.add(pos);
                return true;
            }
            return !Arrays.asList(_cropBlock).contains(mod.getWorld().getBlockState(pos).getBlock());
        });
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(_cropBlock);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // Don't stop while we're replanting crops.
        if (replantPending(mod)) {
            return false;
        }
        return super.isFinished(mod);
    }

    // only the holes we are standing in: replanting is a favour to the field, not worth a walk back across the farm
    private static final double REPLANT_RANGE_SQ = 10 * 10;

    // a hole we already set off for counts at any distance, or walking away mid-replant would read as done
    private boolean replantPending(AltoClef mod) {
        if (!Baritone.settings().replantCrops.value) {
            return false;
        }
        sweepStarted(mod);
        _holes.refresh(WorldHelper.getTicks(), pos -> isEmptyCrop(mod, pos));
        // the last seed in a predicted plant: it looks planted and seedless until the server answers, don't finish on that
        if (_holes.settling()) {
            return true;
        }
        if (!mod.getItemStorage().hasItem(_cropSeed)) {
            return false;
        }
        Vec3 at = mod.getPlayer().position();
        return _holes.committed() || _holes.near(pos -> pos.distToCenterSqr(at), REPLANT_RANGE_SQ) > 0;
    }

    // for CollectFoodTask: is there still a field to tend, or is this just harvesting
    public boolean hasReplantWork() {
        return Baritone.settings().replantCrops.value && (_holes.any() || _holes.settling());
    }

    // air over farmland. a trampled spot (dirt now) can't take a seed, and trying every few seconds forever was the stall
    private boolean isEmptyCrop(AltoClef mod, BlockPos pos) {
        return WorldHelper.isAir(mod, pos) && mod.getWorld().getBlockState(pos.below()).is(Blocks.FARMLAND);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectCropTask task) {
            return Arrays.equals(task._cropSeed, _cropSeed) && Arrays.equals(task._cropBlock, _cropBlock) && task._cropToCollect.equals(_cropToCollect);
        }
        return false;
    }

    @Override
    protected String toHudString() {
        return "Harvesting " + HudText.some(_cropToCollect);
    }

    @Override
    protected String toDebugStringName() {
        return "Collecting crops: " + _cropToCollect;
    }


    private boolean isMature(AltoClef mod, BlockPos blockPos) {
        // Chunk needs to be loaded for wheat maturity to be checked.
        if (!mod.getChunkTracker().isChunkLoaded(blockPos) || !WorldHelper.canReach(mod, blockPos)) {
            return _wasFullyGrown.contains(blockPos);
        }
        // Prune if we're not mature/fully grown wheat.
        BlockState s = mod.getWorld().getBlockState(blockPos);
        if (s.getBlock() instanceof CropBlock) {
            boolean mature = CropRules.ripe(s);
            if (_wasFullyGrown.contains(blockPos)) {
                if (!mature) _wasFullyGrown.remove(blockPos);
            } else {
                if (mature) _wasFullyGrown.add(blockPos);
            }
            return mature;
        }
        // Not a crop block.
        return false;
    }
}
