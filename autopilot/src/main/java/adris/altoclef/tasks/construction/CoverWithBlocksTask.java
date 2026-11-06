package adris.altoclef.tasks.construction;

import adris.altoclef.AltoSettings;
import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;

public class CoverWithBlocksTask extends Task {
    private static final TimerGame timer = new TimerGame(30);
    private static final Task goToNether = new DefaultGoToDimensionTask(Dimension.NETHER);
    private static final Task goToOverworld = new DefaultGoToDimensionTask(Dimension.OVERWORLD);
    private static Task getBlocks;
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
        // from getting blocks or crossing dimensions
        boolean wasSearching = _searching;
        _searching = false;
        Item[] throwAways = AltoSettings.getThrowawayItems(mod, true);
        Item[] throwAwaysToUse = Arrays.stream(throwAways).filter(item -> !(Block.byItem(item) instanceof LeavesBlock) &&
                !(Block.byItem(item) instanceof FallingBlock) && item instanceof BlockItem).toArray(Item[]::new);
        int throwAwayCount = mod.getItemStorage().getItemCount(throwAwaysToUse);
        if (getBlocks != null && throwAwayCount < 128) {
            setDebugState("Getting blocks to cover nether lava.");
            timer.reset();
            return getBlocks;
        } else {
            getBlocks = null;
        }
        Block[] blocks = ItemHelper.itemsToBlocks(throwAwaysToUse);
        if (!mod.getItemStorage().hasItem(throwAwaysToUse) && mod.getBlockTracker().anyFound(blocks)) {
            timer.reset();
            ItemTarget throwAwaysTarget = new ItemTarget(throwAwaysToUse);
            getBlocks = new MineAndCollectTask(throwAwaysTarget, blocks, MiningRequirement.STONE);
            return getBlocks;
        }
        if (!mod.getItemStorage().hasItem(throwAwaysToUse) && !mod.getBlockTracker().anyFound(blocks)) {
            timer.reset();
            if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD) {
                setDebugState("Trying nether to search for blocks.");
                return goToNether;
            }
            if (WorldHelper.getCurrentDimension() == Dimension.NETHER) {
                setDebugState("Trying overworld to search for blocks.");
                return goToOverworld;
            }
        }
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
            setDebugState("Going to nether.");
            timer.reset();
            return goToNether;
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
        setDebugState("Covering lava with blocks");
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
            if (!WorldHelper.isBlock(mod, lavaPos, Blocks.LAVA) || !WorldHelper.isAir(mod, lavaPos.above()) ||
                    !mod.getWorld().getBlockState(lavaPos).getFluidState().isSource()) {
                lavaPos = lava.get();
                timer.reset();
            }
            Item[] throwAways = AltoSettings.getThrowawayItems(mod, true);
            Item[] throwAwaysToUse = Arrays.stream(throwAways).filter(item -> !(Block.byItem(item) instanceof LeavesBlock) &&
                    !(Block.byItem(item) instanceof FallingBlock) && item instanceof BlockItem).toArray(Item[]::new);
            List<Slot> presentThrowAways = mod.getItemStorage().getSlotsWithItemPlayerInventory(true, throwAwaysToUse);
            if (!presentThrowAways.isEmpty()) {
                for (Slot presentThrowAway : presentThrowAways) {
                    Item throwAwayItem = StorageHelper.getItemStackInSlot(presentThrowAway).getItem();
                    Block throwAwayBlock = Block.byItem(throwAwayItem);
                    return new PlaceBlockTask(lavaPos, throwAwayBlock);
                }
            }
        }
        return null;
    }

    // a command nobody is waiting on forever: the lava search wander can't even leave its spot, so there is nothing left
    // to try. a flag from the search branch and not thisOrChildWanderFailing, which would also end it for a stuck wander
    // down in the block or dimension tasks
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
        return other instanceof CoverWithBlocksTask;
    }

    @Override
    protected String toHudString() {
        return "Covering lava with blocks";
    }

    @Override
    protected String toDebugString() {
        return "Covering nether lava with blocks";
    }
}
