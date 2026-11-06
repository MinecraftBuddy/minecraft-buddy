package adris.altoclef.tasks.resources;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.SmeltInBlastFurnaceTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.*;
import baritone.api.utils.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class CollectGoldIngotTask extends ResourceTask {

    private final int _count;
    private final SmeltRouter _router = new SmeltRouter();

    public CollectGoldIngotTask(int count) {
        super(Items.GOLD_INGOT, count);
        _count = count;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();
        // the router needs to see blast furnaces. iron tracks these too, gold never did and only found them by luck
        mod.getBlockTracker().trackBlock(Blocks.BLAST_FURNACE);
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // gold we carry as blocks or nuggets is ingots with extra steps, cheaper than any furnace or pickaxe
        Task unpacked = unpack(mod);
        if (unpacked != null) {
            return unpacked;
        }
        if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD) {
            SmeltTarget all = new SmeltTarget(new ItemTarget(Items.GOLD_INGOT, _count), new ItemTarget(Items.RAW_GOLD, _count));
            // same deal as iron: a blast furnace that is already standing close beats a plain furnace
            Task nearby = _router.tryNearbyBlast(mod, all);
            if (nearby != null) {
                return nearby;
            }
            if (Baritone.settings().altoUseBlastFurnace.value) {
                if (mod.getItemStorage().hasItem(Items.BLAST_FURNACE) ||
                        mod.getBlockTracker().anyFound(Blocks.BLAST_FURNACE) ||
                        mod.getEntityTracker().itemDropped(Items.BLAST_FURNACE)) {
                    return new SmeltInBlastFurnaceTask(all);
                }
                if (_count < 5) {
                    return _router.furnace(all);
                }
                mod.getBehaviour().addProtectedItems(Items.COBBLESTONE, Items.STONE, Items.SMOOTH_STONE);
                Optional<BlockPos> furnacePos = mod.getBlockTracker().getNearestTracking(Blocks.FURNACE);
                furnacePos.ifPresent(blockPos -> mod.getBehaviour().avoidBlockBreaking(blockPos));
                if (mod.getItemStorage().getItemCount(Items.IRON_INGOT) >= 5) {
                    return TaskCatalogue.getItemTask(Items.BLAST_FURNACE, 1);
                }
                return _router.furnace(new SmeltTarget(new ItemTarget(Items.IRON_INGOT, 5), new ItemTarget(Items.RAW_IRON, 5)));
            }
            return _router.furnace(all);
        } else if (WorldHelper.getCurrentDimension() == Dimension.NETHER) {
            // If we have enough nuggets, craft them.
            int nuggs = mod.getItemStorage().getItemCount(Items.GOLD_NUGGET);
            int nuggs_needed = _count * 9 - mod.getItemStorage().getItemCount(Items.GOLD_INGOT) * 9;
            if (nuggs >= nuggs_needed) {
                ItemTarget n = new ItemTarget(Items.GOLD_NUGGET);
                CraftingRecipe recipe = CraftingRecipe.newShapedRecipe("gold_ingot", new ItemTarget[]{
                        n, n, n, n, n, n, n, n, n
                }, 1);
                return new CraftInTableTask(new RecipeTarget(Items.GOLD_INGOT, _count, recipe));
            }
            // Mine nuggets
            return new MineAndCollectTask(new ItemTarget(Items.GOLD_NUGGET, _count * 9), new Block[]{Blocks.NETHER_GOLD_ORE}, MiningRequirement.WOOD);
        } else {
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }
    }

    // blocks first (a 2x2 craft, no table), then whole sets of nine nuggets. each one only crafts what we are short of
    private Task unpack(AltoClef mod) {
        int ingots = mod.getItemStorage().getItemCount(Items.GOLD_INGOT);
        if (ingots >= _count) {
            return null;
        }
        int blocks = mod.getItemStorage().getItemCount(Items.GOLD_BLOCK);
        if (blocks > 0) {
            CraftingRecipe recipe = CraftingRecipe.newShapedRecipe("gold_ingots_from_block",
                    new ItemTarget[]{new ItemTarget(Items.GOLD_BLOCK, 1), null, null, null}, 9);
            return new CraftInInventoryTask(new RecipeTarget(Items.GOLD_INGOT, Math.min(_count, ingots + 9 * blocks), recipe));
        }
        int sets = mod.getItemStorage().getItemCount(Items.GOLD_NUGGET) / 9;
        if (sets > 0) {
            ItemTarget n = new ItemTarget(Items.GOLD_NUGGET);
            CraftingRecipe recipe = CraftingRecipe.newShapedRecipe("gold_ingot", new ItemTarget[]{n, n, n, n, n, n, n, n, n}, 1);
            return new CraftInTableTask(new RecipeTarget(Items.GOLD_INGOT, Math.min(_count, ingots + sets), recipe));
        }
        return null;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        mod.getBlockTracker().stopTracking(Blocks.BLAST_FURNACE);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectGoldIngotTask && ((CollectGoldIngotTask) other)._count == _count;
    }

    @Override
    protected String toDebugStringName() {
        return "Collecting " + _count + " gold.";
    }
}
