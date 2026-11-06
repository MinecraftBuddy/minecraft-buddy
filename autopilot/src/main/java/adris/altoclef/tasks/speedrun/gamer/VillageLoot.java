package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.container.LootContainerTask;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.StrongholdScan;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BlockTracker;
import adris.altoclef.util.helpers.SeenFilter;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

// while iron is the thing we are short on, a village blacksmith chest we have SEEN is the cheapest iron there is. the
// chest is recognised by the job block next to it (VillageChests), opened once, and the budget lives in RunState so a
// relog does not hand out a second helping. same shape as RuinedPortalLoot
public final class VillageLoot {
    private static final List<Item> LOOT = List.of(
            Items.IRON_INGOT, Items.IRON_PICKAXE, Items.IRON_SWORD, Items.IRON_AXE, Items.IRON_SHOVEL,
            Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
            Items.OBSIDIAN, Items.DIAMOND, Items.GOLD_INGOT, Items.GOLD_NUGGET, Items.GOLD_BLOCK, Items.RAW_GOLD,
            Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS,
            Items.BREAD, Items.APPLE, Items.COAL);
    private static final int CHECK_EVERY_TICKS = 20;

    private boolean tracking;
    private int ticks;
    private BlockPos target;
    private LootContainerTask task;
    private long startedTick;

    public void onEnter(AltoClef mod) {
        if (!tracking) {
            mod.getBlockTracker().trackBlock(Blocks.CHEST, Blocks.GRINDSTONE, Blocks.SMITHING_TABLE, Blocks.BLAST_FURNACE);
            tracking = true;
        }
    }

    public void onExit(AltoClef mod) {
        if (tracking) {
            mod.getBlockTracker().stopTracking(Blocks.CHEST, Blocks.GRINDSTONE, Blocks.SMITHING_TABLE, Blocks.BLAST_FURNACE);
            tracking = false;
        }
        // leaving mid chest is fine: it went into the tried set when it started, so it already used up its slot
        if (target != null) {
            task = null;
            target = null;
        }
    }

    // the loot task while there is a chest to visit, otherwise null. a visit that already started finishes even if the
    // iron need is met by then (the first ingot out of the chest does that, the rest of the chest is still worth it)
    public Task tick(AltoClef mod, GamerContext ctx, KitNeed current) {
        RunState state = ctx.state();
        OverworldConfig cfg = ctx.cfg().overworld;
        long now = ctx.facts().gameTime();
        if (target != null) {
            double elapsed = (now - startedTick) / 20.0;
            boolean settled = task.isFinished(mod);
            if (!settled && elapsed <= cfg.lootChestSeconds) {
                return task;
            }
            ctx.log(LootVisit.line("village", target.getX(), target.getY(), target.getZ(),
                    LootVisit.why(settled, !settled, !task.taken().isEmpty()), task.taken()));
            state.villageLootTicks += now - startedTick;
            target = null;
            task = null;
            ctx.save();
        }
        boolean wantIron = current != null && "iron_ingot".equals(current.catalogueName());
        if (!wantIron || ctx.facts().dimension() != Dimension.OVERWORLD || ++ticks % CHECK_EVERY_TICKS != 0
                || !VillageChests.withinBudget(state.villageChestsTried.size(), state.villageLootTicks,
                cfg.villageLootMaxChests, cfg.villageLootSeconds)) {
            return null;
        }
        BlockPos next = findChest(mod, state, cfg);
        if (next == null) {
            return null;
        }
        // one visit and it counts, however it ends. also the tried set is the thing a relog must not forget
        state.villageChestsTried.add(new RunState.Pos(next.getX(), next.getY(), next.getZ()));
        target = next;
        task = new LootContainerTask(target, LOOT);
        startedTick = now;
        ctx.progress("looting a village chest");
        ctx.save();
        return task;
    }

    private BlockPos findChest(AltoClef mod, RunState state, OverworldConfig cfg) {
        BlockTracker tracker = mod.getBlockTracker();
        if (!tracker.isTracking(Blocks.CHEST)) {
            return null;
        }
        BlockPos player = mod.getPlayer().blockPosition();
        List<VillageChests.Job> jobs = new ArrayList<>();
        addJobs(mod, jobs, Blocks.GRINDSTONE, VillageChests.GRINDSTONE);
        addJobs(mod, jobs, Blocks.SMITHING_TABLE, VillageChests.SMITHING_TABLE);
        addJobs(mod, jobs, Blocks.BLAST_FURNACE, VillageChests.BLAST_FURNACE);
        if (jobs.isEmpty()) {
            return null;
        }
        List<RunState.Pos> chests = new ArrayList<>();
        for (BlockPos pos : StrongholdScan.nearest(tracker.getKnownLocations(Blocks.CHEST), player, StrongholdScan.MAX_SCAN)) {
            chests.add(new RunState.Pos(pos.getX(), pos.getY(), pos.getZ()));
        }
        var eye = mod.getPlayer().position();
        RunState.Pos best = VillageChests.pick(chests, jobs, state.placedJobBlocks, state.villageChestsTried, p -> usable(mod, tracker, p),
                eye.x, eye.y, eye.z, cfg.villageLootRadius, cfg.villageChestJobRadius);
        return best == null ? null : new BlockPos(best.x, best.y, best.z);
    }

    private static void addJobs(AltoClef mod, List<VillageChests.Job> jobs, Block block, int rank) {
        for (BlockPos pos : mod.getBlockTracker().getKnownLocations(block)) {
            // the tracker remembers unloaded chunks, so check the block is still what it says
            if (mod.getWorld().getBlockState(pos).is(block)) {
                jobs.add(new VillageChests.Job(new RunState.Pos(pos.getX(), pos.getY(), pos.getZ()), rank));
            }
        }
    }

    // the questions that touch the world, asked last and only for chests that already look like a blacksmith's
    private static boolean usable(AltoClef mod, BlockTracker tracker, RunState.Pos p) {
        BlockPos pos = new BlockPos(p.x, p.y, p.z);
        return mod.getWorld().getBlockState(pos).is(Blocks.CHEST)
                && mod.getWorld().getBlockState(pos.above()).getBlock() != Blocks.WATER
                && !tracker.unreachable(pos)
                && WorldHelper.isUnopenedChest(mod, pos)
                && SeenFilter.isSeen(mod, pos);
    }
}
