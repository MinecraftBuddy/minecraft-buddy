package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.speedrun.gamer.Respawn;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public class CollectBedTask extends CraftWithMatchingWoolTask {

    public static final Block[] BEDS = ItemHelper.itemsToBlocks(ItemHelper.BED);

    // beds in the world further than this are left alone and one is crafted (the buddy's fork)
    private static final double BED_REACH = 64;

    private final ItemTarget _visualBedTarget;

    public CollectBedTask(Item[] beds, ItemTarget wool, int count) {
        // Top 3 are wool, must be the same.
        super(new ItemTarget(beds, count), colorfulItems -> colorfulItems.wool, colorfulItems -> colorfulItems.bed, createBedRecipe(wool), new boolean[]{true, true, true, false, false, false, false, false, false});
        _visualBedTarget = new ItemTarget(beds, count);
    }

    public CollectBedTask(Item bed, String woolCatalogueName, int count) {
        this(new Item[]{bed}, new ItemTarget(woolCatalogueName, 1), count);
    }

    public CollectBedTask(int count) {
        this(ItemHelper.BED, TaskCatalogue.getItemTarget("wool", 1), count);
    }

    static CraftingRecipe createBedRecipe(ItemTarget wool) {
        ItemTarget w = wool;
        ItemTarget p = TaskCatalogue.getItemTarget("planks", 1);
        return CraftingRecipe.newShapedRecipe(new ItemTarget[]{w, w, w, p, p, p, null, null, null}, 1);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        super.onResourceStart(mod);
        mod.getBlockTracker().trackBlock(BEDS);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        super.onResourceStop(mod, interruptTask);
        mod.getBlockTracker().stopTracking(BEDS);
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // Break beds from the world if possible, that would be pretty fast.
        Block[] copperBlocks = ItemHelper.itemsToBlocks(ItemHelper.COPPER_BLOCKS);
        // the bed we respawn at is not for the taking, however close it is
        // (the buddy's fork: and only a bed within BED_REACH. one 190 blocks off at y -22 had the task go after it and after the
        // wool in turn, every second for the eight game minutes of the kit step, and no bed came, the runner, 2 Nov 2026, attempt 60)
        var me = mod.getPlayer().position();
        Predicate<BlockPos> notSpawn = pos -> !Respawn.isSpawnBed(pos.getX(), pos.getY(), pos.getZ()) && pos.closerToCenterThan(me, BED_REACH);
        Optional<BlockPos> nearestBed = mod.getBlockTracker().getNearestTracking(notSpawn, BEDS);
        if (nearestBed.isPresent() && WorldHelper.canBreak(mod, nearestBed.get())) {
            for (Block CopperBlock : copperBlocks) {
                Block blockBelow = mod.getWorld().getBlockState(nearestBed.get().below()).getBlock();
                if (blockBelow == CopperBlock) {
                    Debug.logMessage("Blacklisting bed in trial chambers.");
                    mod.getBlockTracker().requestBlockUnreachable(nearestBed.get(), 0);
                }
            }
            // Failure + blacklisting is encapsulated within THIS task)
            return new MineAndCollectTask(new ItemTarget(ItemHelper.BED), BEDS, MiningRequirement.HAND).onlyWhere(notSpawn);
        }
        return super.onResourceTick(mod);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectBedTask task) {
            return task._visualBedTarget.equals(_visualBedTarget);
        }
        return false;
    }

    @Override
    protected String toHudString() {
        return "Crafting " + HudText.items(_visualBedTarget);
    }

    @Override
    protected String toDebugStringName() {
        return "Crafting bed: " + _visualBedTarget;
    }
}
