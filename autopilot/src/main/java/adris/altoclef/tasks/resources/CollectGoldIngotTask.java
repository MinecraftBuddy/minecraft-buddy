package adris.altoclef.tasks.resources;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
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
            Task back = furnaceBack(mod);
            if (back != null) {
                return back;
            }
            SmeltTarget all = new SmeltTarget(new ItemTarget(Items.GOLD_INGOT, _count), new ItemTarget(Items.RAW_GOLD, _count));
            SmeltTarget batch = batch(mod);
            if (batch != null) all = batch;
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

    // (the buddy's fork, Tuning gold_batch, on by default, 12 Nov 2026: M3.3's smoke. the smelt took the whole count as its
    // target, so it mined raw gold till all of it was in and smelted nothing before: two runs died after about 100 game
    // minutes with 23 and 32 raw gold and not one ingot. with it on, once the bag holds GOLD_BATCH raw gold short of the
    // count, those are smelted first, held till their ingots are out, then the mining goes on; the last of it as before)
    static final int GOLD_BATCH = 16;
    private int _batchTo;

    private SmeltTarget batch(AltoClef mod) {
        if (adris.altoclef.util.helpers.Tuning.get("gold_batch", 0) <= 0) return null;
        int ingots = mod.getItemStorage().getItemCount(Items.GOLD_INGOT);
        int raw = mod.getItemStorage().getItemCountInventoryOnly(Items.RAW_GOLD);
        if (_batchTo > 0 && ingots >= _batchTo) {
            Debug.logInternal("gold: a batch smelted, " + ingots + " of " + _count + " ingots");
            _batchTo = 0;
            noteFurnaceBack(mod);
        }
        int need = _count - ingots;
        if (_batchTo == 0 && need > raw && raw >= GOLD_BATCH) {
            _batchTo = ingots + raw;
            _furnacesBefore = furnacesNear(mod);
            Debug.logInternal("gold: " + raw + " raw gold smelted now, " + ingots + " ingots of " + _count + " (gold_batch)");
        }
        return _batchTo > 0 ? new SmeltTarget(new ItemTarget(Items.GOLD_INGOT, _batchTo), new ItemTarget(Items.RAW_GOLD, _batchTo)) : null;
    }

    // (Tuning gold_furnace_back, a fix of 13 Nov 2026, on: after a batch the furnace it smelted in goes back in the bag, as the
    // branch mine does with its table: the one within FURNACE_NEAR that was not there when the batch began is broken and picked
    // up, for at most a game half minute. the furnaces of the gold stayed where they were put down, the bag held none and its
    // deepslate makes none here (DoStuffInContainerTask.spareCobble), so each batch walked back to a furnace up to 128 blocks off,
    // at the surface: about 50 of the 123 gold minutes of the goudslijm smoke 2's run 3)
    static final int FURNACE_NEAR = 8;
    private java.util.Set<BlockPos> _furnacesBefore;
    private BlockPos _ourFurnace;
    private int _furnacesHad;
    private long _furnaceUntil;

    private static java.util.Set<BlockPos> furnacesNear(AltoClef mod) {
        java.util.Set<BlockPos> out = new java.util.HashSet<>();
        BlockPos feet = mod.getPlayer().blockPosition();
        int r = FURNACE_NEAR;
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -r, -r), feet.offset(r, r, r))) {
            if (mod.getWorld().getBlockState(pos).is(Blocks.FURNACE)) out.add(pos.immutable());
        }
        return out;
    }

    private void noteFurnaceBack(AltoClef mod) {
        java.util.Set<BlockPos> before = _furnacesBefore;
        _furnacesBefore = null;
        if (before == null || adris.altoclef.util.helpers.Tuning.get("gold_furnace_back", 0) <= 0) return;
        for (BlockPos pos : furnacesNear(mod)) {
            if (before.contains(pos)) continue;
            _ourFurnace = pos;
            _furnacesHad = mod.getItemStorage().getItemCount(Items.FURNACE);
            _furnaceUntil = mod.getWorld().getGameTime() + 600;
            Debug.logInternal("gold: the batch's furnace at " + pos.toShortString() + " back in the bag");
            return;
        }
    }

    private Task furnaceBack(AltoClef mod) {
        if (_ourFurnace == null) return null;
        if (mod.getWorld().getGameTime() > _furnaceUntil || mod.getItemStorage().getItemCount(Items.FURNACE) > _furnacesHad) {
            _ourFurnace = null;
            return null;
        }
        if (mod.getWorld().getBlockState(_ourFurnace).is(Blocks.FURNACE)) {
            setDebugState("The batch's furnace, back in the bag");
            return new adris.altoclef.tasks.construction.DestroyBlockTask(_ourFurnace);
        }
        setDebugState("The batch's furnace it broke, picked up");
        return new adris.altoclef.tasks.movement.PickupDroppedItemTask(Items.FURNACE, _furnacesHad + 1);
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
