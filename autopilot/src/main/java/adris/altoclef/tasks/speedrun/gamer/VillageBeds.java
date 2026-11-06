package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.resources.CollectBedTask;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.end.EndGear;
import adris.altoclef.tasks.speedrun.gamer.tasks.StrongholdScan;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BlockTracker;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.SeenFilter;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

// while we are short of beds, a bed in a village we have SEEN is a bed for free: every house has one, it breaks in a
// blink with bare hands and drops the item, and that is 3 wool + 3 planks + a sheep detour we do not have to make. the
// shortfall is the planner's own wool formula (KitPlanner.bedsShort), the village-ness is BedRules, the budget lives in
// RunState so a relog does not hand out a second helping. same shape as VillageLoot
public final class VillageBeds {
    // what villagers work at, plus the bell. nothing outside a village has most of these, and the tracker only pays for
    // them while we are actually short of beds
    private static final Block[] EVIDENCE = {
            Blocks.BELL, Blocks.COMPOSTER, Blocks.BARREL, Blocks.LECTERN, Blocks.LOOM, Blocks.SMOKER, Blocks.STONECUTTER,
            Blocks.FLETCHING_TABLE, Blocks.CARTOGRAPHY_TABLE, Blocks.GRINDSTONE, Blocks.SMITHING_TABLE, Blocks.BLAST_FURNACE};
    private static final Block[] COPPER = ItemHelper.itemsToBlocks(ItemHelper.COPPER_BLOCKS);
    private static final int CHECK_EVERY_TICKS = 20;

    private boolean tracking;
    private int ticks;
    private int toGo;
    private BlockPos target;
    // the destroy task first, then the pickup of what it dropped
    private Task task;
    private boolean broken;
    private Item dropped;
    private int heldBefore;
    private long startedTick;

    public void onExit(AltoClef mod) {
        track(mod, false);
        // leaving mid bed is fine: it went into the tried set when it started, so it already used up its slot
        task = null;
        target = null;
    }

    // beds still to find, for the HUD
    public int toGo() {
        return toGo;
    }

    // the bed task while there is a bed to take, otherwise null. a bed that already started finishes even if the shortfall
    // is met by then (the item is on the floor, leaving it there would just be silly)
    public Task tick(AltoClef mod, GamerContext ctx) {
        RunState state = ctx.state();
        OverworldConfig cfg = ctx.cfg().overworld;
        long now = ctx.facts().gameTime();
        toGo = KitPlanner.bedsShort(ctx.facts(), EndGear.bedTarget(ctx.cfg().end, EndGear.wantSpawnBed(state, ctx.cfg().end)));
        boolean overworld = ctx.facts().dimension() == Dimension.OVERWORLD;
        boolean justTook = false;
        if (target != null) {
            Task running = running(mod, ctx, cfg, now);
            if (running != null && overworld) {
                return running;
            }
            finish(ctx, state, now);
            justTook = true;
        }
        boolean want = overworld && toGo > 0 && BedRules.withinBudget(state.villageBedTicks, cfg.villageBedSeconds);
        track(mod, want);
        // straight on to the next bed after one is in the bag. waiting for the 20 tick check handed the food a second between
        // every two beds, it walked off for the hay and the next bed dragged it back, bed after bed (13:53)
        if (!want || (++ticks % CHECK_EVERY_TICKS != 0 && !justTook)) {
            return null;
        }
        BlockPos next = findBed(mod, state, cfg);
        if (next == null) {
            return null;
        }
        // one go and it counts, however it ends. also the tried set is the thing a relog must not forget
        state.villageBedsTried.add(new RunState.Pos(next.getX(), next.getY(), next.getZ()));
        target = next;
        dropped = mod.getWorld().getBlockState(next).getBlock().asItem();
        heldBefore = ctx.facts().count(dropped);
        broken = false;
        task = new DestroyBlockTask(next);
        startedTick = now;
        ctx.progress("taking a village bed");
        ctx.save();
        return task;
    }

    // the task to keep running, null when this bed is done (taken, or out of time)
    private Task running(AltoClef mod, GamerContext ctx, OverworldConfig cfg, long now) {
        if (!broken && task.isFinished(mod)) {
            // the block is gone, now the item it dropped
            broken = true;
            task = new PickupDroppedItemTask(dropped, heldBefore + 1);
        }
        boolean taken = broken && ctx.facts().count(dropped) > heldBefore;
        boolean late = (now - startedTick) / 20.0 > cfg.villageBedEachSeconds;
        return taken || late ? null : task;
    }

    private void finish(GamerContext ctx, RunState state, long now) {
        state.villageBedTicks += now - startedTick;
        target = null;
        task = null;
        ctx.save();
    }

    private void track(AltoClef mod, boolean on) {
        if (on == tracking) {
            return;
        }
        BlockTracker tracker = mod.getBlockTracker();
        if (on) {
            tracker.trackBlock(CollectBedTask.BEDS);
            tracker.trackBlock(EVIDENCE);
        } else {
            tracker.stopTracking(CollectBedTask.BEDS);
            tracker.stopTracking(EVIDENCE);
        }
        tracking = on;
    }

    private BlockPos findBed(AltoClef mod, RunState state, OverworldConfig cfg) {
        BlockTracker tracker = mod.getBlockTracker();
        BlockPos player = mod.getPlayer().blockPosition();
        List<RunState.Pos> beds = new ArrayList<>();
        // the tracker remembers unloaded chunks, so check the block is still a bed
        for (BlockPos pos : StrongholdScan.nearest(tracker.getKnownLocations(CollectBedTask.BEDS), player, StrongholdScan.MAX_SCAN)) {
            if (mod.getWorld().getBlockState(pos).getBlock() instanceof BedBlock) {
                beds.add(new RunState.Pos(pos.getX(), pos.getY(), pos.getZ()));
            }
        }
        if (beds.isEmpty()) {
            return null;
        }
        List<RunState.Pos> evidence = new ArrayList<>();
        for (Block block : EVIDENCE) {
            for (BlockPos pos : tracker.getKnownLocations(block)) {
                if (mod.getWorld().getBlockState(pos).is(block)) {
                    evidence.add(new RunState.Pos(pos.getX(), pos.getY(), pos.getZ()));
                }
            }
        }
        // beds placed by us never fire the place hook (they are not full blocks), but the only one we place is the spawn
        // bed and we write down where
        // (the night bed is ours too, while it stands. a village bed is somebody else's and the tried set is a one shot)
        List<RunState.Pos> own = BedRules.ownBeds(state.spawnBed, state.nightBed, Respawn.bed(state));
        var eye = mod.getPlayer().position();
        RunState.Pos best = BedRules.pick(beds, evidence, own, state.placedJobBlocks, state.villageBedsTried,
                p -> usable(mod, tracker, p), eye.x, eye.y, eye.z, cfg.villageBedRadius, cfg.villageBedEvidenceRadius);
        return best == null ? null : new BlockPos(best.x, best.y, best.z);
    }

    // the questions that touch the world, asked last and only for beds that already look like a village's
    private static boolean usable(AltoClef mod, BlockTracker tracker, RunState.Pos p) {
        BlockPos pos = new BlockPos(p.x, p.y, p.z);
        return !tracker.unreachable(pos)
                // DangerFilter blacklists these too, but only every couple of seconds, and trial chambers are on copper
                && !onCopper(mod, pos)
                && WorldHelper.canBreak(mod, pos)
                && SeenFilter.isSeen(mod, pos);
    }

    private static boolean onCopper(AltoClef mod, BlockPos pos) {
        Block below = mod.getWorld().getBlockState(pos.below()).getBlock();
        for (Block copper : COPPER) {
            if (copper == below) {
                return true;
            }
        }
        return false;
    }
}
