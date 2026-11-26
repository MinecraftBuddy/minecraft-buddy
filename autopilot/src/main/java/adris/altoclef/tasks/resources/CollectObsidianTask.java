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
    // (obsidian_seen_hold) when obsidian was last found, and how long that still counts as found (10 s)
    private long _seenAt = Long.MIN_VALUE;
    static final long SEEN_HOLD = 200;
    // (Tuning cast_hold_end) the cell of the last cast that came out obsidian, when obsidian other than the cast's own was last
    // found, and the cell whose end of the hold was said (a line a cell)
    private BlockPos _castMade;
    private long _otherSeenAt = Long.MIN_VALUE;
    private BlockPos _holdEndSaid;

    private PlaceObsidianBucketTask _placeObsidianTask;
    // (Tuning cast_spot_scan) the spot within CAST_SCAN blocks the cast's walk goes to, and when it was last looked for
    private BlockPos _castSpot;
    private long _castScanAt = Long.MIN_VALUE;
    // when the walk to this spot began, and the spots a walk gave up on (CAST_GIVE_UP with the spot not reached)
    private long _castSince;
    private final java.util.Set<BlockPos> _castBanned = new java.util.HashSet<>();
    private static final long CAST_GIVE_UP = 1200;
    // the spot looked for again this often while the walk to it goes on (game ticks)
    private static final long CAST_RESCAN = 100;
    // how far sideways and up or down a cast's spot is looked for around the feet, and the cast's own half width
    static final int CAST_SCAN = 8, CAST_SCAN_DY = 3, CAST_HALF = 3;
    // (Tuning obsidian_no_sky, off by default, the block onderdak of 25 Nov 2026) M3.2 stays out from under the open sky: no
    // cast at a cell the sky light reaches, nor in the deep dark; natural obsidian only within NO_SKY_REACH sideways, within
    // the lava band of the buddy's own height (LavaHunt.BAND) and with no sky above it; with the feet under the sky and no
    // sheltered cell near, down NO_SKY_DESCEND blocks first instead of the wander. 4 of 16 obsidian deaths of heel-252/253
    // were on the surface (a cast at the feet, a climb to surface obsidian, 2 at night), 1 more in the deep dark
    static final int NO_SKY_REACH = 96, NO_SKY_DESCEND = 16;
    // the height the descent out of the sky goes to, latched so the walk keeps one goal; MIN_VALUE when none
    private int _descendY = Integer.MIN_VALUE;
    // when the cell underfoot was last said refused (obsidian_no_sky)
    private long _skyRefusedAt = Long.MIN_VALUE;

    static boolean noSkyOn() {
        return adris.altoclef.util.helpers.Tuning.get("obsidian_no_sky", 0) > 0;
    }

    // a cell out of the sky's light and not in the deep dark (obsidian_no_sky)
    static boolean sheltered(net.minecraft.world.level.Level level, BlockPos cell) {
        return !level.canSeeSky(cell) && !level.getBiome(cell).is(net.minecraft.world.level.biome.Biomes.DEEP_DARK);
    }

    public CollectObsidianTask(int count) {
        super(Items.OBSIDIAN, count);
        _count = count;
    }

    private static BlockPos getGoodObsidianPosition(AltoClef mod) {
        if (noSkyOn() && !sheltered(mod.getWorld(), mod.getPlayer().blockPosition())) return null;
        BlockPos start = mod.getPlayer().blockPosition().offset(-3, -3, -3);
        BlockPos end = mod.getPlayer().blockPosition().offset(3, 3, 3);
        for (BlockPos pos : WorldHelper.scanRegion(mod, start, end)) {
            if (!WorldHelper.canBreak(mod, pos) || !WorldHelper.canPlace(mod, pos)) {
                return null;
            }
        }
        return mod.getPlayer().blockPosition();
    }

    // (Tuning cast_spot_scan, a fix of 19 Nov 2026, on; look 139) the nearest cell within CAST_SCAN sideways and CAST_SCAN_DY up
    // or down that getGoodObsidianPosition would take if the buddy stood in it: every cell of its cube breakable and
    // placeable, the cell and the one above it open, solid ground under it. the old way tested only the cell underfoot and
    // otherwise wandered blind, a goal 114 to 226 blocks off: 5.5 game minutes stood still in a cave under the sea with 9 of
    // 10 obsidian, while a good cell lay a short walk away. one pass over the box (a prefix sum of the cells that fail), so
    // each candidate costs the same however big its cube; null for none
    static BlockPos nearbyObsidianPosition(AltoClef mod, java.util.Set<BlockPos> banned) {
        BlockPos feet = mod.getPlayer().blockPosition();
        int r = CAST_SCAN + CAST_HALF, h = CAST_SCAN_DY + CAST_HALF;
        int nx = 2 * r + 1, ny = 2 * h + 1;
        int[] sum = new int[(nx + 1) * (ny + 1) * (nx + 1)];
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = 0; x < nx; x++) {
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nx; z++) {
                    at.set(feet.getX() - r + x, feet.getY() - h + y, feet.getZ() - r + z);
                    int bad = WorldHelper.canBreak(mod, at) && WorldHelper.canPlace(mod, at) ? 0 : 1;
                    sum[idx(x + 1, y + 1, z + 1, ny, nx)] = bad + sum[idx(x, y + 1, z + 1, ny, nx)] + sum[idx(x + 1, y, z + 1, ny, nx)]
                            + sum[idx(x + 1, y + 1, z, ny, nx)] - sum[idx(x, y, z + 1, ny, nx)] - sum[idx(x, y + 1, z, ny, nx)]
                            - sum[idx(x + 1, y, z, ny, nx)] + sum[idx(x, y, z, ny, nx)];
                }
            }
        }
        BlockPos best = null;
        double bestD = Double.POSITIVE_INFINITY;
        var level = mod.getWorld();
        boolean noSky = noSkyOn();
        for (int dx = -CAST_SCAN; dx <= CAST_SCAN; dx++) {
            for (int dy = -CAST_SCAN_DY; dy <= CAST_SCAN_DY; dy++) {
                for (int dz = -CAST_SCAN; dz <= CAST_SCAN; dz++) {
                    // the cube of cells [c - CAST_HALF, c + CAST_HALF] in box coordinates, its count of failing cells
                    int x0 = dx + r - CAST_HALF, y0 = dy + h - CAST_HALF, z0 = dz + r - CAST_HALF;
                    int x1 = x0 + 2 * CAST_HALF + 1, y1 = y0 + 2 * CAST_HALF + 1, z1 = z0 + 2 * CAST_HALF + 1;
                    int bad = sum[idx(x1, y1, z1, ny, nx)] - sum[idx(x0, y1, z1, ny, nx)] - sum[idx(x1, y0, z1, ny, nx)]
                            - sum[idx(x1, y1, z0, ny, nx)] + sum[idx(x0, y0, z1, ny, nx)] + sum[idx(x0, y1, z0, ny, nx)]
                            + sum[idx(x1, y0, z0, ny, nx)] - sum[idx(x0, y0, z0, ny, nx)];
                    if (bad != 0) continue;
                    BlockPos c = feet.offset(dx, dy, dz);
                    if (!open(level, c) || !open(level, c.above())
                            || level.getBlockState(c.below()).getCollisionShape(level, c.below()).isEmpty()
                            || mod.getBlockTracker().unreachable(c) || banned.contains(c)
                            || (noSky && !sheltered(level, c))) continue;
                    double d = c.distSqr(feet);
                    if (d < bestD) {
                        bestD = d;
                        best = c;
                    }
                }
            }
        }
        return best;
    }

    // no fluid and nothing to bump into: a cell the buddy can stand in
    private static boolean open(net.minecraft.world.level.Level level, BlockPos p) {
        var st = level.getBlockState(p);
        return st.getFluidState().isEmpty() && st.getCollisionShape(level, p).isEmpty();
    }

    private static int idx(int x, int y, int z, int ny, int nx) {
        return (x * (ny + 1) + y) * (nx + 1) + z;
    }

    // (obsidian_seen_hold, cast_hold_end) whether obsidian not found this tick still counts as found. the hold is for a natural
    // block that comes and goes in the tracker (look 130); once the block of the buddy's own cast is mined and picked up, with
    // no other obsidian found within the hold, there is nothing to hold for: the collect had nothing to mine and wandered for
    // the 10 s, and the next cast began where the wander stopped
    static boolean held(boolean holdOn, boolean castHoldEnd, long tick, long seenAt, long otherSeenAt, boolean ownCastGone) {
        if (!holdOn || !within(tick, seenAt)) return false;
        return !(castHoldEnd && ownCastGone && !within(tick, otherSeenAt));
    }

    private static boolean within(long tick, long at) {
        return at != Long.MIN_VALUE && tick >= at && tick - at <= SEEN_HOLD;
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

        // (Tuning obsidian_no_sky: near, in the own height's band, no sky above)
        boolean noSky = noSkyOn();
        int feetY = mod.getPlayer().blockPosition().getY();
        Predicate<BlockPos> goodObsidian = (blockPos ->
                blockPos.closerToCenterThan(mod.getPlayer().position(), noSky ? NO_SKY_REACH : 800)
                        && WorldHelper.canBreak(mod, blockPos)
                        && !WorldHelper.dropWouldBurn(mod, blockPos)
                        && (!noSky || (Math.abs(blockPos.getY() - feetY) <= adris.altoclef.tasks.speedrun.gamer.portal.LavaHunt.BAND
                        && !mod.getWorld().canSeeSky(blockPos.above())))
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
        // (Tuning obsidian_seen_hold, a fix of 18 Nov 2026, on) obsidian found in the last SEEN_HOLD ticks still counts as found:
        // a natural block 120 off and 122 down came and went from the tracker about once a second, and every change switched
        // between walking to it and a cast with no lava, each cancelling the other's path, for six minutes (look 130)
        // (Tuning cast_hold_end, a fix of 26 Nov 2026, on) but not for the block of the buddy's own cast once it is mined: see held
        boolean castHoldEnd = adris.altoclef.util.helpers.Tuning.get("cast_hold_end", 0) > 0;
        boolean tracked = mod.getBlockTracker().anyFound(goodObsidian, Blocks.OBSIDIAN);
        boolean found = tracked || mod.getEntityTracker().itemDropped(Items.OBSIDIAN);
        long tick = mod.getWorld().getGameTime();
        if (found) {
            _seenAt = tick;
        }
        if (tracked && castHoldEnd) {
            // obsidian other than the cast's own cell, the one in the making or the last one made
            BlockPos making = _placeObsidianTask != null ? _placeObsidianTask.getPos() : null;
            BlockPos made = _castMade;
            if ((made == null && making == null) || mod.getBlockTracker().anyFound(
                    goodObsidian.and(p -> !p.equals(made) && !p.equals(making)), Blocks.OBSIDIAN)) {
                _otherSeenAt = tick;
            }
        }
        if (!found) {
            boolean holdOn = adris.altoclef.util.helpers.Tuning.get("obsidian_seen_hold", 0) > 0;
            boolean ownGone = _castMade != null && !mod.getWorld().getBlockState(_castMade).is(Blocks.OBSIDIAN);
            found = held(holdOn, castHoldEnd, tick, _seenAt, _otherSeenAt, ownGone);
            // a line a cast cell, so a block can count the holds this ended
            if (!found && holdOn && castHoldEnd && ownGone && within(tick, _seenAt) && !_castMade.equals(_holdEndSaid)) {
                _holdEndSaid = _castMade;
                Debug.logInternal("obsidian: the cast's block at " + _castMade.toShortString()
                        + " mined and picked up, no hold: the next cast from here (cast_hold_end)");
            }
        }
        if (/*obsidianNearby || */found) {
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
            // (cast_hold_end) the cast just made obsidian in its cell: that block is the buddy's own, its mining holds nothing after it
            if (_placeObsidianTask != null && mod.getWorld().getBlockState(_placeObsidianTask.getPos()).is(Blocks.OBSIDIAN)) {
                _castMade = _placeObsidianTask.getPos();
            }
            _placeObsidianTask = null;
            // lava crust obsidian is 9 seconds of digging to watch the drop burn. leave it be, the bucket cast below scoops that
            // same lava and makes obsidian that lands on something. obsidian with a floor under it still beats a cast
            return new MineAndCollectTask(new ItemTarget(Items.OBSIDIAN, _count), new Block[]{Blocks.OBSIDIAN}, MiningRequirement.DIAMOND)
                    .onlyWhere(p -> !WorldHelper.dropWouldBurn(mod, p) && (!noSky || goodObsidian.test(p)));
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
            // (Tuning obsidian_no_sky) the cell underfoot refused for the sky or the deep dark: a line at most once a minute, so
            // a block can count the runs where the rule came into play
            if (noSky && !sheltered(mod.getWorld(), mod.getPlayer().blockPosition())) {
                long now = mod.getWorld().getGameTime();
                if (_skyRefusedAt == Long.MIN_VALUE || now < _skyRefusedAt || now - _skyRefusedAt >= 1200) {
                    _skyRefusedAt = now;
                    Debug.logInternal("obsidian: no cast at " + mod.getPlayer().blockPosition().toShortString()
                            + (mod.getWorld().canSeeSky(mod.getPlayer().blockPosition()) ? ", under the open sky" : ", in the deep dark") + " (obsidian_no_sky)");
                }
            }
            BlockPos goodPos = getGoodObsidianPosition(mod);
            if (goodPos != null && !mod.getBlockTracker().unreachable(goodPos)) {
                _castSpot = null;
                // (Tuning cast_band_here, on since 19 Nov 2026) the cast's lava fetch looks within the band around the buddy's own height
                _placeObsidianTask = new PlaceObsidianBucketTask(goodPos).bandHere(adris.altoclef.util.helpers.Tuning.get("cast_band_here", 0) > 0);
            } else {
                // (Tuning cast_spot_scan, a fix of 19 Nov 2026, on; look 139) a good cell near by is walked to first
                if (adris.altoclef.util.helpers.Tuning.get("cast_spot_scan", 0) > 0) {
                    long now = mod.getWorld().getGameTime();
                    if (_castSpot != null && now >= _castSince && now - _castSince > CAST_GIVE_UP) {
                        Debug.logInternal("obsidian: the walk to the cast spot at " + _castSpot.toShortString() + " got nowhere in a minute, another one");
                        _castBanned.add(_castSpot);
                        _castSpot = null;
                    }
                    if (_castSpot == null || now < _castScanAt || now - _castScanAt >= CAST_RESCAN) {
                        BlockPos was = _castSpot;
                        _castSpot = nearbyObsidianPosition(mod, _castBanned);
                        _castScanAt = now;
                        if (_castSpot != null && !_castSpot.equals(was)) {
                            _castSince = now;
                            Debug.logInternal("obsidian: no cast spot underfoot, walking to one at " + _castSpot.toShortString() + " (cast_spot_scan)");
                        }
                    }
                    if (_castSpot != null) {
                        setDebugState("Walking to a spot to place obsidian");
                        return new adris.altoclef.tasks.movement.GetToBlockTask(_castSpot);
                    }
                }
                // (Tuning obsidian_no_sky) under the open sky with no sheltered cell near: down out of it, not a wander on the surface
                BlockPos feet = mod.getPlayer().blockPosition();
                if (noSky && mod.getWorld().canSeeSky(feet)) {
                    if (_descendY == Integer.MIN_VALUE || feet.getY() <= _descendY) {
                        _descendY = Math.max(feet.getY() - NO_SKY_DESCEND, mod.getWorld().getMinY() + 8);
                        Debug.logInternal("obsidian: no cast spot out of the open sky near " + feet.toShortString() + ", going down to y "
                                + _descendY + " first (obsidian_no_sky)");
                    }
                    setDebugState("Going down out of the open sky to place obsidian");
                    return new adris.altoclef.tasks.movement.GetToYTask(_descendY);
                }
                _descendY = Integer.MIN_VALUE;
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
