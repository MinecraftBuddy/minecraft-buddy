package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.trackers.Bans;
import adris.altoclef.trackers.BlockTracker;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// things the block tracker offers that are traps wearing a crafting table: trial chamber furniture (sits on copper),
// pillager outpost wool and logs, witch hut tables. the tracked blocks get banned (Bans, which the tracker honours) so the
// leaf tasks never walk to them. only looks at block types somebody already tracks (no extra scanning) and only every couple
// of seconds. one of these for the run, GamerTask ticks it in the overworld only: a phase that doesn't run it is a phase that
// walks straight into the furniture
public final class DangerFilter {
    private static final int EVERY_TICKS = 40;
    private static final double PILLAGER_RADIUS = 40;
    private static final double WITCH_RADIUS = 15;

    // a minute of standing around: patrols walk, outposts do not
    private final PillagerWatch pillagerWatch = new PillagerWatch(1200);
    private int ticks;
    private Block[] copperBlocks;
    private Block[] beds;
    private Block[] logs;
    private Block[] wools;

    public void tick(AltoClef mod, RunState state) {
        if (++ticks % EVERY_TICKS != 0) {
            return;
        }
        BlockTracker tracker = mod.getBlockTracker();
        if (beds == null) {
            copperBlocks = ItemHelper.itemsToBlocks(ItemHelper.COPPER_BLOCKS);
            beds = ItemHelper.itemsToBlocks(ItemHelper.BED);
            logs = ItemHelper.itemsToBlocks(ItemHelper.LOG);
            wools = ItemHelper.itemsToBlocks(ItemHelper.WOOL);
        }
        Bans bans = mod.getBans();
        Dimension dim = WorldHelper.getCurrentDimension();
        blacklistOnCopper(mod, tracker, bans, dim, state, Blocks.CRAFTING_TABLE);
        blacklistOnCopper(mod, tracker, bans, dim, state, Blocks.CHEST);
        for (Block bed : beds) {
            blacklistOnCopper(mod, tracker, bans, dim, state, bed);
        }
        blacklistWitchOrPillageTables(mod, tracker, bans, dim, state);
        blacklistNearOutposts(mod, tracker, bans, dim);
    }

    // trial chambers are built on copper, a table or bed or chest on top of it is not ours to take
    private void blacklistOnCopper(AltoClef mod, BlockTracker tracker, Bans bans, Dimension dim, RunState state, Block block) {
        if (!tracker.isTracking(block)) {
            return;
        }
        for (BlockPos pos : tracker.getKnownLocations(block)) {
            // our own table on a copper floor is still ours (there is no placed list for chests or beds)
            if (block == Blocks.CRAFTING_TABLE && ownStation(state == null ? null : state.placedTables, pos)) {
                continue;
            }
            Block below = mod.getWorld().getBlockState(pos.below()).getBlock();
            if (isCopper(below)) {
                BanPolicy.trap(bans, dim, pos.getX(), pos.getY(), pos.getZ(), "trial chamber furniture");
            }
        }
    }

    private void blacklistWitchOrPillageTables(AltoClef mod, BlockTracker tracker, Bans bans, Dimension dim, RunState state) {
        if (!tracker.isTracking(Blocks.CRAFTING_TABLE)) {
            return;
        }
        List<Vec3> witches = positions(mod.getEntityTracker().getTrackedEntities(Witch.class));
        for (BlockPos pos : tracker.getKnownLocations(Blocks.CRAFTING_TABLE)) {
            // a table we put down ourselves is ours wherever the witch happens to be standing
            if (ownStation(state == null ? null : state.placedTables, pos)) {
                continue;
            }
            // outposts put white wool two above their tables
            boolean outpost = mod.getWorld().getBlockState(pos.above(2)).getBlock() == Blocks.WOOL.white();
            if (outpost || nearAny(pos, witches, WITCH_RADIUS)) {
                BanPolicy.trap(bans, dim, pos.getX(), pos.getY(), pos.getZ(), outpost ? "outpost table" : "witch hut table");
            }
        }
    }

    // only around an outpost (pillagers that stand still), a patrol passing through is not worth losing a forest over
    private void blacklistNearOutposts(AltoClef mod, BlockTracker tracker, Bans bans, Dimension dim) {
        Map<Integer, double[]> pillagers = new HashMap<>();
        for (Pillager p : mod.getEntityTracker().getTrackedEntities(Pillager.class)) {
            // a dead pillager is not holding an outpost
            if (p.isAlive()) {
                pillagers.put(p.getId(), new double[]{p.getX(), p.getZ()});
            }
        }
        pillagerWatch.update(ticks, pillagers);
        liftExpiredBans(bans, pillagerWatch);
        if (pillagerWatch.outposts() == 0) {
            return;
        }
        List<BlockPos> near = new ArrayList<>();
        for (Block block : logs) {
            if (tracker.isTracking(block)) {
                near.addAll(tracker.getKnownLocations(block));
            }
        }
        for (Block block : wools) {
            if (tracker.isTracking(block)) {
                near.addAll(tracker.getKnownLocations(block));
            }
        }
        banNearOutposts(bans, pillagerWatch, dim, near);
    }

    // a ban of its own reason, so the lift below takes only these (a block that was unreachable anyway keeps that ban). one
    // log line per outpost per pass, not one per log. pure past the watch
    static int banNearOutposts(Bans bans, PillagerWatch watch, Dimension dim, List<BlockPos> candidates) {
        Map<Long, List<Bans.Key>> byOutpost = new HashMap<>();
        Map<Long, double[]> spots = new HashMap<>();
        for (BlockPos pos : candidates) {
            double[] at = watch.outpostNear(pos.getX(), pos.getZ(), PILLAGER_RADIUS);
            if (at == null) {
                continue;
            }
            long id = ((long) Math.floor(at[0]) << 32) | (Math.round(Math.floor(at[1])) & 0xFFFFFFFFL);
            spots.putIfAbsent(id, at);
            byOutpost.computeIfAbsent(id, k -> new ArrayList<>()).add(Bans.Key.block(dim, pos.getX(), pos.getY(), pos.getZ()));
        }
        int added = 0;
        for (Map.Entry<Long, List<Bans.Key>> e : byOutpost.entrySet()) {
            double[] at = spots.get(e.getKey());
            added += BanPolicy.outpost(bans, e.getValue(), at[0], at[1]);
        }
        return added;
    }

    // an outpost nobody has seen a live pillager at for a few minutes is gone (cleared, or we left and it despawned them),
    // its logs and wool go back on the menu unless another outpost still covers them. pure past the watch, so a test can
    // run it. the watch's clock only runs in the overworld (GamerTask), or a nether trip would quietly expire every outpost
    static int liftExpiredBans(Bans bans, PillagerWatch watch) {
        int lifted = 0;
        for (double[] gone : watch.drainExpired()) {
            lifted += BanPolicy.liftOutpost(bans, gone[0], gone[1], PILLAGER_RADIUS,
                    key -> watch.nearOutpost(key.x(), key.z(), PILLAGER_RADIUS));
        }
        return lifted;
    }

    // pure so a test can poke it: is this block one the run itself placed
    public static boolean ownStation(List<RunState.Pos> placed, BlockPos pos) {
        return placed != null && placed.contains(new RunState.Pos(pos.getX(), pos.getY(), pos.getZ()));
    }

    private boolean isCopper(Block block) {
        for (Block copper : copperBlocks) {
            if (copper == block) {
                return true;
            }
        }
        return false;
    }

    private static List<Vec3> positions(List<? extends Entity> entities) {
        List<Vec3> out = new ArrayList<>();
        for (Entity e : entities) {
            out.add(e.position());
        }
        return out;
    }

    public static boolean nearAny(BlockPos pos, List<Vec3> points, double radius) {
        for (Vec3 p : points) {
            if (pos.closerToCenterThan(p, radius)) {
                return true;
            }
        }
        return false;
    }
}
