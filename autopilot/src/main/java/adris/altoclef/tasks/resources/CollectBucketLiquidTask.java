package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetCloseToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskClock;
import adris.altoclef.trackers.Bans;
import baritone.api.utils.Dimension;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.BreakRules;
import adris.altoclef.util.helpers.BucketFillRules;
import adris.altoclef.util.helpers.BucketSourceRules;
import adris.altoclef.util.helpers.FluidSources;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.PotionHelper;
import adris.altoclef.util.helpers.PoolMisses;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

public class CollectBucketLiquidTask extends ResourceTask {

    private final TimerGame _tryImmediatePickupTimer = new TimerGame(3);
    private final TimerGame _pickedUpTimer = new TimerGame(0.5);
    private final int _count;

    //private IProgressChecker<Double> _checker = new LinearProgressChecker(5, 0.1);
    private final Item _target;
    // what fills: an empty bucket, or a glass bottle for a water bottle. the same click on a source, and the same slot question
    // (the full one does not stack where the empty ones do, so a fill from a stack of them needs a free slot)
    private final Item _empty;
    private final String _vessel;
    private final Block _toCollect;
    private final String _liquidName;
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();

    private boolean wasWandering = false;

    private static final int SCAN_WAIT_TICKS = 60;
    // a stream is followed once in a while, not every tick of a wander. the cube it starts from is 49^3 cells
    private static final int DIRECT_GAP_TICKS = 50;
    private static final int DIRECT_RANGE = 24;
    private static final int DIRECT_RANGE_Y = 12;
    private static final int DIRECT_MAX_TESTS = 12;
    private static final int DIRECT_FORGET_TICKS = 1200;
    private int _lastDirectTick = -1000;
    private int _directRejectedSince = 0;
    private BlockPos _directHit = null;
    private final HashSet<BlockPos> _directRejected = new HashSet<>();
    private static final int CLIMB_GAP_TICKS = 60;
    private static final double CLIMB_SEARCH_RANGE = 24;
    private static final int CLIMB_MAX_MOVES = 16;
    private static final float WANDER_LEG = 64;
    private static final int NO_SOURCE_TICKS = 40;
    private int _scanWaitStart = -1;
    private int _lastClimbTick = -1000;
    private boolean _loggedWander = false;
    // the last tick the wander fallback ran with nothing at all passing the source filter. the portal phase reads it as "no lava
    // here", the same as a tracker that has never seen any, so the no lava escape can fire instead of a wander with no end
    private int _noSourceAt = -1000;
    // made when a fill has no slot to land in. a finished one is stuck (nothing to throw), and _noRoom remembers that
    // until the bag stops needing room
    private EnsureFreeInventorySlotTask _roomTask = null;
    private boolean _noRoom = false;
    private boolean _loggedNoRoom = false;
    // when the current hold started (-1 = not holding), and when we started looking down for the spare bucket throw
    private int _holdSince = -1;
    private int _aimedSince = -1;
    // when this stretch of MAKE_ROOM began and the last tick it showed up. a throw frees a slot for a tick or two, so a
    // flicker must not restart the clock, only a gap of a whole pickup delay does
    private int _makeRoomSince = -1;
    private int _lastMakeRoom = -1;
    // sources the stream climb has already handed over
    private final HashSet<BlockPos> _climbed = new HashSet<>();
    // sources skipped because the block over them is too slow to break, so the line is said once and the ban handed out once
    private final HashSet<BlockPos> _lidsSkipped = new HashSet<>();

    // time spent on each lid, for the ban that doesn't care what the pathing resets are up to. only ticks while we are near
    // it and not already cracking it, so a long walk or a slow honest break is not billed
    private final HashMap<BlockPos, TaskClock> _lidClocks = new HashMap<>();
    private static final double LID_GIVE_UP_SECONDS = 15;
    private static final double LID_NEAR_SQ = 8 * 8;
    // whether any source we can scoop as it is is known. asked every so often, not every tick: it is a walk over every known
    // source and the answer changes when a scan lands or something gets banned
    private boolean _openOutranks;
    private int _openLookAt = Integer.MIN_VALUE / 2;
    private static final int OPEN_LOOK_TICKS = 20;
    // the "got nowhere near it" misses, per pool (see missedPool). lives as long as this task instance does
    private final PoolMisses _poolMisses = new PoolMisses();

    // sources the caller wants left alone (the cast's own pour), checked next to the tracker's bans
    private Predicate<BlockPos> _leaveAlone = p -> false;

    public CollectBucketLiquidTask leaveAlone(Predicate<BlockPos> leaveAlone) {
        _leaveAlone = leaveAlone;
        return this;
    }

    public CollectBucketLiquidTask(String liquidName, Item filledBucket, int targetCount, Block toCollect) {
        this(liquidName, filledBucket, Items.BUCKET, "Bucket", targetCount, toCollect);
    }

    protected CollectBucketLiquidTask(String liquidName, Item filled, Item empty, String vessel, int targetCount, Block toCollect) {
        super(filled, targetCount);
        _liquidName = liquidName;
        _target = filled;
        _empty = empty;
        _vessel = vessel;
        _count = targetCount;
        _toCollect = toCollect;
    }

    // (bottle_in_water_fix) the fill where the buddy stands waits for an empty one in the bag
    static boolean bottleInWaterFix() {
        return adris.altoclef.util.helpers.Tuning.get("bottle_in_water_fix", 0) > 0;
    }

    // how many full ones the bag holds. a bucket is its item; a water bottle is a potion, and so is every other drink
    protected int filledCount(AltoClef mod) {
        return mod.getItemStorage().getItemCount(_target);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(_toCollect);
        // Track fluids
        mod.getBehaviour().push();
        mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.SOURCE_ONLY);

        // Avoid breaking / placing blocks at our liquid
        mod.getBehaviour().avoidBlockBreaking((pos) -> Minecraft.getInstance().level.getBlockState(pos).getBlock() == _toCollect);
        mod.getBehaviour().avoidBlockPlacing((pos) -> Minecraft.getInstance().level.getBlockState(pos).getBlock() == _toCollect);

        _progressChecker.reset();
        _directRejected.clear();
        _directHit = null;
        // a restart gets a fresh look at whether anything can be thrown, the bag may have changed while we were away
        _roomTask = null;
        _noRoom = false;
        _loggedNoRoom = false;
        _holdSince = -1;
        _aimedSince = -1;
        _makeRoomSince = -1;
        _lastMakeRoom = -1;
    }


    @Override
    protected Task onTick(AltoClef mod) {
        Task result = super.onTick(mod);
        // Reset our "first time" timeout/wander flag.
        if (!thisOrChildAreTimedOut()) {
            wasWandering = false;
        }
        return result;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _progressChecker.reset();
        }
        // before either fill below (the one we're standing in and the one at the shore), not between a click and its wait
        if (_pickedUpTimer.elapsed()) {
            BucketFillRules.Fill fill = fillPlan(mod);
            if (fill == BucketFillRules.Fill.MAKE_ROOM) {
                setDebugState("Making room for the full bucket");
                return roomTask();
            }
            if (fill != BucketFillRules.Fill.HOLD) {
                _holdSince = -1;
            } else if (!holdOrDropSpare(mod)) {
                // not filling is the answer. nothing tells the caller, it just sees no lava bucket arrive; the portal phase's
                // budget is what ends a stalled cast, and a plain `get lava_bucket` idles until somebody frees a slot (or
                // until the spare buckets below are gone and the one left fills in place)
                return null;
            }
        }
        // If we're standing inside a liquid, go pick it up.
        // (the buddy's fork, Tuning bottle_in_water_fix, a fix of 14 Nov 2026, on) only with an empty one in the bag to fill. with
        // none, the click did nothing and the branch's null every 3 s cut the fetch of the empties below: in a pond the bottles'
        // table walk began anew 102 times in 6 minutes and never got to a table or to its own fallback
        if (_tryImmediatePickupTimer.elapsed() && (_empty != Items.BUCKET || !mod.getItemStorage().hasItem(Items.WATER_BUCKET))
                && (!bottleInWaterFix() || mod.getItemStorage().hasItem(_empty))) {
            Block standingInside = mod.getWorld().getBlockState(mod.getPlayer().blockPosition()).getBlock();
            if (standingInside == _toCollect) {
                setDebugState("Trying to collect (we are in it)");
                mod.getInputControls().forceLook(0, 90);
                //mod.getClientBaritone().getLookBehavior().updateTarget(new Rotation(0, 90), true);
                //Debug.logMessage("Looking at " + _toCollect + ", picking up right away.");
                _tryImmediatePickupTimer.reset();
                if (mod.getSlotHandler().forceEquipItem(_empty)) {
                    mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                    mod.getExtraBaritoneSettings().setInteractionPaused(true);
                    _pickedUpTimer.reset();
                    _progressChecker.reset();
                }
                return null;
            }
        }

        if (!_pickedUpTimer.elapsed()) {
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            _progressChecker.reset();
            // Wait for force pickup
            return null;
        }

        // Get buckets if we need em
        int bucketsNeeded = _count - mod.getItemStorage().getItemCount(_empty) - filledCount(mod);
        if (bucketsNeeded > 0) {
            setDebugState("Getting " + _vessel.toLowerCase() + "...");
            // targets are totals. "1 bucket" with an empty one already in hand is met, and nothing else ever got us one
            return TaskCatalogue.getItemTask(_empty, mod.getItemStorage().getItemCount(_empty) + bucketsNeeded);
        }

        // what is a source at all, banned or not. the ranking below decides which of those are worth going to
        Predicate<BlockPos> isSource = blockPos -> {
            if (mod.getBlockTracker().unreachable(blockPos)) return false;
            if (_leaveAlone.test(blockPos)) return false;
            if (!WorldHelper.canReach(mod, blockPos)) return false;
            if (!WorldHelper.canReach(mod, blockPos.above())) return false; // We may try reaching the block above.
            assert Minecraft.getInstance().level != null;
            // We break the block above. If it's bedrock, ignore.
            if (mod.getWorld().getBlockState(blockPos.above()).getBlock() == Blocks.BEDROCK) {
                return false;
            }
            // obsidian over the source (or anything else that takes minutes) is a source we are not getting at. skipped
            // now, not after the progress checker has watched us swing at it
            if (WorldHelper.isSolid(mod, blockPos.above()) && WorldHelper.tooSlowToBreak(mod, blockPos.above())) {
                skipLid(mod, blockPos);
                return false;
            }
            return WorldHelper.isSourceBlock(mod, blockPos, false);
        };
        BucketSourceRules.Cells cells = new BucketSourceRules.Cells() {
            @Override
            public boolean solid(int x, int y, int z) {
                return WorldHelper.isSolid(mod, new BlockPos(x, y, z));
            }

            @Override
            public boolean open(int x, int y, int z) {
                BlockPos at = new BlockPos(x, y, z);
                return mod.getWorld().getBlockState(at).getCollisionShape(mod.getWorld(), at).isEmpty();
            }

            @Override
            public boolean free(int x, int y, int z) {
                // somewhere to stand or swing from: open, and not a pool of something
                BlockPos at = new BlockPos(x, y, z);
                return open(x, y, z) && mod.getWorld().getBlockState(at).getFluidState().isEmpty();
            }
        };
        // open sources first, a lid only when there is no open one to be had, and never one that can't be got at (see
        // BucketSourceRules)
        Runnable lookForOpen = () -> {
            _openLookAt = WorldHelper.getTicks();
            Optional<BlockPos> open = mod.getBlockTracker().getNearestTracking(
                    p -> isSource.test(p) && BucketSourceRules.classify(cells, p.getX(), p.getY(), p.getZ()) == BucketSourceRules.Kind.OPEN,
                    _toCollect);
            Optional<BlockPos> lid = mod.getBlockTracker().getNearestTracking(
                    p -> isSource.test(p) && BucketSourceRules.classify(cells, p.getX(), p.getY(), p.getZ()) == BucketSourceRules.Kind.LID,
                    _toCollect);
            _openOutranks = BucketSourceRules.openOutranksLids(away(mod, open), away(mod, lid));
        };
        if (WorldHelper.getTicks() - _openLookAt >= OPEN_LOOK_TICKS) {
            lookForOpen.run();
        }
        Predicate<BlockPos> isSourceLiquid = blockPos -> isSource.test(blockPos)
                && BucketSourceRules.worthGoingTo(BucketSourceRules.classify(cells, blockPos.getX(), blockPos.getY(), blockPos.getZ()), _openOutranks);

        // Find nearest water and right click it
        if (mod.getBlockTracker().isTracking(_toCollect)) {
            Optional<BlockPos> nearestSource = mod.getBlockTracker().getNearestTracking(isSourceLiquid, _toCollect);
            if (nearestSource.isEmpty() && _openOutranks) {
                // the open one we were counting on just got banned or went away. don't wander off for a second on a stale yes
                lookForOpen.run();
                nearestSource = mod.getBlockTracker().getNearestTracking(isSourceLiquid, _toCollect);
            }
            if (nearestSource.isPresent()) {
                _loggedWander = false;
                _noSourceAt = -1000;
                Block nearestSourceBlock = mod.getWorld().getBlockState(nearestSource.get()).getBlock();
                // We want to MINIMIZE this distance to liquid.
                setDebugState("Trying to collect...");
                //Debug.logMessage("TEST: " + RayTraceUtils.fluidHandling);
                return new DoToClosestBlockTask(blockPos -> {
                    // Clear above if lava because we can't enter.
                    // but NOT if we're standing right above.
                    if (WorldHelper.isSolid(mod, blockPos.above())) {
                        boolean slow = !_progressChecker.check(mod);
                        if (slow || lidTimedOut(mod, blockPos)) {
                            mod.getClientBaritone().getPathingBehavior().cancelEverything();
                            mod.getClientBaritone().getPathingBehavior().forceCancel();
                            mod.getClientBaritone().getExploreProcess().onLostControl();
                            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                            Debug.logMessage("Failed to break, blacklisting.");
                            mod.getBlockTracker().requestBlockUnreachable(blockPos);
                            // was a set of this task's own that never emptied, now a ban with a clock
                            BanPolicy.bucketLidStuck(mod.getBans(), WorldHelper.getCurrentDimension(), blockPos.getX(), blockPos.getY(), blockPos.getZ());
                            // a failed checker stays failed, so without this the next source got banned on its first tick
                            _progressChecker.reset();
                            // and the ban runs out some day, the next go at this lid gets a clock of its own
                            _lidClocks.remove(blockPos);
                            _openLookAt = Integer.MIN_VALUE / 2;
                        }
                        return new DestroyBlockTask(blockPos.above());
                    }
                    // We can reach the block.
                    if (LookHelper.getReach(blockPos).isPresent() &&
                            mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
                        return InteractWithBlockTask.scoop(new ItemTarget(_empty, 1), blockPos, _toCollect != Blocks.LAVA);
                    }
                    // Get close enough.
                    // up because if we go below we'll try to move next to the liquid (for lava, not a good move)
                    if (this.thisOrChildAreTimedOut() && !wasWandering) {
                        mod.getBlockTracker().requestBlockUnreachable(blockPos.above());
                        missedPool(mod, blockPos);
                        wasWandering = true;
                    }
                    return new GetCloseToBlockTask(blockPos.above());
                }, isSourceLiquid, nearestSourceBlock);
            }
        }

        // Dimension
        if (_toCollect == Blocks.WATER && WorldHelper.getCurrentDimension() == Dimension.NETHER) {
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }

        // a scan for this liquid that hasn't landed yet says nothing about whether there is any. same cap and idea as
        // AbstractDoToClosestObjectTask's wait
        int now = WorldHelper.getTicks();
        if (mod.getBlockTracker().scanPending(_toCollect)) {
            if (_scanWaitStart < 0) _scanWaitStart = now;
            if (now - _scanWaitStart < SCAN_WAIT_TICKS) {
                setDebugState("Waiting for the first scan before wandering");
                return null;
            }
        } else {
            _scanWaitStart = -1;
        }

        // the scan only reports sources. a waterfall in view with its source out of the scan's sight is still a
        // way to the source, so follow it up and hand what it finds to the tracker
        if (tryClimbToSource(mod, now)) {
            setDebugState("Followed a stream up to its source");
            return null;
        }

        // the tracker keeps the 25 nearest and a crowd of useless ones can fill that, so look at the world itself
        if (tryDirectSearch(mod, now, isSourceLiquid)) {
            setDebugState("Found a source the tracker had crowded out");
            return null;
        }

        // Oof, no liquid found.
        List<BlockPos> tracked = mod.getBlockTracker().getKnownLocations(_toCollect);
        long passing = tracked.stream().filter(isSourceLiquid).count();
        if (passing == 0) _noSourceAt = now;
        if (!_loggedWander) {
            _loggedWander = true;
            Debug.logMessage("No " + _liquidName + " to collect: tracker knows " + tracked.size() + " positions, " + passing + " passed, wandering");
        }
        setDebugState("Searching for liquid by wandering around aimlessly");

        // a leg at a time: each one ends 64 blocks out and the next starts with a fresh look at what the tracker knows, instead of
        // one wander that never comes back to ask
        if (subTask() instanceof TimeoutWanderTask leg && leg.isFinished(mod)) dropChild(mod);
        return new TimeoutWanderTask(WANDER_LEG);
    }

    public boolean noSource() {
        return WorldHelper.getTicks() - _noSourceAt <= NO_SOURCE_TICKS;
    }

    // true once we have spent LID_GIVE_UP_SECONDS working on the lid over this source and it is still there
    private boolean lidTimedOut(AltoClef mod, BlockPos source) {
        BlockPos lid = source.above();
        TaskClock clock = _lidClocks.computeIfAbsent(source.immutable(), k -> new TaskClock());
        boolean cracking = mod.getControllerExtras().isBreakingBlock() && lid.equals(mod.getControllerExtras().getBreakingBlockPos());
        // a live path this close is the walk (or the dig) to it, that is progress and DestroyBlockTask has its own clocks for
        // standing around
        boolean walking = mod.getClientBaritone().getPathingBehavior().isPathing();
        if (!cracking && !walking && mod.getPlayer().distanceToSqr(lid.getX() + 0.5, lid.getY() + 0.5, lid.getZ() + 0.5) <= LID_NEAR_SQ) {
            clock.tick();
        }
        if (!clock.over(LID_GIVE_UP_SECONDS)) {
            return false;
        }
        Debug.logInternal("bucket: " + LID_GIVE_UP_SECONDS + " s on the block over the " + _liquidName + " at " + source.toShortString() + " and it is still there, giving up on it");
        return true;
    }

    // the strike above wants five on one column and the next pick is the column next door, so a pool we can't path into
    // was never banned and we bobbed around on top of it for good. counted per pool instead: the second miss anywhere in
    // one pool and all of its sources are out (PoolMisses)
    private void missedPool(AltoClef mod, BlockPos source) {
        Fluid kind = _toCollect == Blocks.LAVA ? Fluids.LAVA : Fluids.WATER;
        // fluid type, not block, so flowing cells and waterlogged blocks hold the pool together like they do in the world
        Set<BlockPos> pool = FluidSources.flood(p -> mod.getWorld().getBlockState(p).getFluidState().getType().isSame(kind), source, FluidSources.FLOOD_CAP);
        Dimension dim = WorldHelper.getCurrentDimension();
        // our own liquid blocks only: a waterlogged slab or some kelp in the pool is a source too, and the ban book is everybody's
        List<BlockPos> sources = _poolMisses.miss(dim, source, pool, p -> {
            var state = mod.getWorld().getBlockState(p);
            return state.getBlock() == _toCollect && state.getFluidState().isSource();
        });
        if (sources.isEmpty()) {
            return;
        }
        // the source cells themselves: isSource asks unreachable(source) first, and the tracker skips banned cells
        List<Bans.Key> keys = new ArrayList<>(sources.size());
        for (BlockPos cell : sources) {
            keys.add(Bans.Key.block(dim, cell.getX(), cell.getY(), cell.getZ()));
        }
        BanPolicy.bucketPoolGaveUp(mod.getBans(), keys, source.getX(), source.getY(), source.getZ());
        // the open-vs-lid answer may have been this pool
        _openLookAt = Integer.MIN_VALUE / 2;
    }

    // straight line from us to a source, NaN when there isn't one
    private static double away(AltoClef mod, Optional<BlockPos> source) {
        return source.map(p -> Math.sqrt(mod.getPlayer().distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5))).orElse(Double.NaN);
    }

    // the same ban the "couldn't break the block over this source" path hands out, just without the wait
    private void skipLid(AltoClef mod, BlockPos source) {
        if (!_lidsSkipped.add(source.immutable())) {
            return;
        }
        BlockPos lid = source.above();
        Debug.logMessage("not breaking " + BuiltInRegistries.BLOCK.getKey(mod.getWorld().getBlockState(lid).getBlock()).getPath()
                + " over the " + _liquidName + " at " + source.toShortString() + ", " + BreakRules.describe(WorldHelper.bagOf(mod),mod.getWorld().getBlockState(lid)));
        mod.getBlockTracker().requestBlockUnreachable(source, 0, "the block over it takes too long to break");
        BanPolicy.bucketLidStuck(mod.getBans(), WorldHelper.getCurrentDimension(), source.getX(), source.getY(), source.getZ());
    }

    // what the next fill should do about the slot the full bucket needs (see BucketFillRules)
    private BucketFillRules.Fill fillPlan(AltoClef mod) {
        NonNullList<ItemStack> slots = mod.getPlayer().getInventory().getNonEquipmentItems();
        int[] bucketStacks = new int[slots.size()];
        int empty = 0;
        for (int i = 0; i < bucketStacks.length; i++) {
            ItemStack stack = slots.get(i);
            if (stack.isEmpty()) {
                empty++;
            } else if (stack.is(_empty)) {
                bucketStacks[i] = stack.getCount();
            }
        }
        // a stack on the cursor is the room task halfway through throwing it, and the slot it came out of is its home until it
        // lands somewhere. counting that slot as free said GO, the wander cut the room task off, the stack went back, MAKE_ROOM,
        // twice a second for minutes
        if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            empty--;
        }
        boolean freeSlot = empty > 0;
        if (!BucketFillRules.needsFreeSlot(bucketStacks, freeSlot)) {
            _roomTask = null;
            _noRoom = false;
            return BucketFillRules.Fill.GO;
        }
        // nothing to throw and nowhere to put it. latched, because handing the room task back next tick restarts it (stuck
        // flag reset) and the fill path would get cancelled and restarted every other tick
        if (_roomTask != null && _roomTask.isFinished(mod)) {
            _noRoom = true;
        }
        BucketFillRules.Fill fill = BucketFillRules.plan(bucketStacks, freeSlot, _noRoom, _toCollect == Blocks.LAVA);
        if (fill == BucketFillRules.Fill.MAKE_ROOM) {
            // the room task can spin for minutes if what it throws keeps coming back, so give it a cap
            int now = WorldHelper.getTicks();
            if (_lastMakeRoom < 0 || BucketFillRules.roomStretchBroke(_lastMakeRoom, now)) {
                _makeRoomSince = now;
            }
            _lastMakeRoom = now;
            if (BucketFillRules.roomTimedOut(_makeRoomSince, now)) {
                _noRoom = true;
                fill = BucketFillRules.plan(bucketStacks, freeSlot, true, _toCollect == Blocks.LAVA);
            }
        }
        if (_noRoom && !_loggedNoRoom) {
            _loggedNoRoom = true;
            if (fill == BucketFillRules.Fill.HOLD) {
                // in chat too: an idle bot with nothing on screen reads as frozen
                Debug.logInternal("bucket: not filling " + _liquidName + ", no free slot");
                Debug.logMessage("bag is full and nothing can go, can't fill " + _liquidName);
            } else {
                Debug.logInternal("bucket: filling " + _liquidName + " anyway, no free slot");
            }
        }
        return fill;
    }

    // true when the fill may go ahead anyway (a lone bucket in hand fills in place, no new slot). false keeps holding, with
    // the state set. after the wait it throws every spare bucket of the stack in hand at once, keeping one. they land at
    // our feet with a 2 s pickup delay and are left to the pickup rules; the lone bucket is filled and we are off to the
    // lake long before then, and if they do find us first they merge back and the hold starts over from scratch
    private boolean holdOrDropSpare(AltoClef mod) {
        int now = WorldHelper.getTicks();
        if (_holdSince < 0) {
            _holdSince = now;
        }
        int held = 0;
        if (now - _holdSince >= BucketFillRules.HOLD_DROP_TICKS && mod.getSlotHandler().forceEquipItem(_empty)) {
            ItemStack hand = mod.getPlayer().getMainHandItem();
            held = hand.is(_empty) ? hand.getCount() : 0;
        }
        BucketFillRules.Hold step = BucketFillRules.holdStep(held, now - _holdSince);
        if (step == BucketFillRules.Hold.FILL_IN_PLACE) {
            return true;
        }
        if (step == BucketFillRules.Hold.DROP_SPARES) {
            setDebugState("Dropping spare buckets to make room", "Dropping spare buckets");
            // straight down, so they land at our feet and not in the lake we are standing next to. the look has to reach
            // the server before the drop does (drop sends its packet right now, the rotation goes out with the tick), so
            // look now and throw two ticks later. the same wait lets the hotbar slot change from the equip reach the server,
            // drop does not sync the selected slot itself, so do not cut it to 1
            mod.getInputControls().forceLook(mod.getPlayer().getYRot(), 90);
            if (_aimedSince < 0) {
                _aimedSince = now;
            }
            if (now - _aimedSince >= 2) {
                _aimedSince = -1;
                for (int i = BucketFillRules.spares(held); i > 0; i--) {
                    mod.getController().dropItem(mod.getPlayer(), false);
                }
                Debug.logInternal("bucket: held " + BucketFillRules.HOLD_DROP_TICKS / 20 + " s with no free slot, dropped "
                        + BucketFillRules.spares(held) + " spare buckets, filling the last one in place");
            }
        } else {
            _aimedSince = -1;
            setDebugState("Waiting for a free slot to fill " + _liquidName, "Waiting for a free slot to fill " + _liquidName);
        }
        return false;
    }

    private Task roomTask() {
        if (_roomTask == null) {
            _roomTask = new EnsureFreeInventorySlotTask();
        }
        return _roomTask;
    }

    // true if it found a source and told the tracker, so the next tick's query can pick it up
    private boolean tryClimbToSource(AltoClef mod, int now) {
        if (now - _lastClimbTick < CLIMB_GAP_TICKS) return false;
        _lastClimbTick = now;
        Optional<BlockPos> stream = mod.getBlockTracker().getNearestWithinRange(mod.getPlayer().position(), CLIMB_SEARCH_RANGE, _toCollect);
        if (stream.isEmpty()) return false;
        Optional<BlockPos> source = FluidSources.climbToSource(
                pos -> mod.getWorld().getBlockState(pos).getFluidState(), stream.get(), CLIMB_MAX_MOVES);
        // already tried it and the predicate didn't like it, climbing again would just find it again
        if (source.isEmpty() || !_climbed.add(source.get())) return false;
        mod.getBlockTracker().addBlock(_toCollect, source.get());
        return true;
    }

    // true if it found a source the full predicate likes and told the tracker. the scan is a cheap fluid-state read per
    // cell, the predicate (canReach and friends) only runs on the nearest few survivors
    private boolean tryDirectSearch(AltoClef mod, int now, Predicate<BlockPos> isSourceLiquid) {
        // the tracker's purge can drop a far cell on its next rescan, so put the last find back while it still holds
        if (_directHit != null) {
            if (isSourceLiquid.test(_directHit)) {
                mod.getBlockTracker().addBlock(_toCollect, _directHit);
                return true;
            }
            _directHit = null;
        }
        if (now - _lastDirectTick < DIRECT_GAP_TICKS) return false;
        _lastDirectTick = now;
        // what failed stays failed for a bit, but bans and lids change, so not for the whole cast
        if (now - _directRejectedSince > DIRECT_FORGET_TICKS) {
            _directRejected.clear();
            _directRejectedSince = now;
        }
        // the tracker's 25 are the nearest exposed ones and they just failed, don't spend the tests on them twice
        Set<BlockPos> known = new HashSet<>(mod.getBlockTracker().getKnownLocations(_toCollect));
        Fluid kind = _toCollect == Blocks.LAVA ? Fluids.LAVA : Fluids.WATER;
        BlockPos me = mod.getPlayer().blockPosition();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                me.offset(-DIRECT_RANGE, -DIRECT_RANGE_Y, -DIRECT_RANGE), me.offset(DIRECT_RANGE, DIRECT_RANGE_Y, DIRECT_RANGE))) {
            FluidState self = mod.getWorld().getFluidState(pos);
            if (self.getType() != kind) continue;
            if (!FluidSources.exposedSource(self, mod.getWorld().getFluidState(pos.above()))) continue;
            if (_directRejected.contains(pos) || known.contains(pos)) continue;
            found.add(pos.immutable());
        }
        found.sort(Comparator.comparingDouble(p -> p.distSqr(me)));
        Optional<BlockPos> hit = FluidSources.firstPassing(found, isSourceLiquid, DIRECT_MAX_TESTS);
        // the ones that failed stay failed for a while, or the nearest few useless cells eat the test budget every time
        int tested = hit.isPresent() ? found.indexOf(hit.get()) : Math.min(found.size(), DIRECT_MAX_TESTS);
        _directRejected.addAll(found.subList(0, tested));
        if (_directRejected.size() > 400) _directRejected.clear();
        if (hit.isEmpty()) return false;
        _directHit = hit.get();
        mod.getBlockTracker().addBlock(_toCollect, hit.get());
        return true;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(_toCollect);
        mod.getBehaviour().pop();
        //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        mod.getExtraBaritoneSettings().setInteractionPaused(false);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectBucketLiquidTask task) {
            if (task._count != _count) return false;
            return task._toCollect == _toCollect && task._empty == _empty;
        }
        return false;
    }

    @Override
    protected String toHudString() {
        return "Filling " + HudText.count(_count, _vessel) + " with " + _liquidName;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " " + _liquidName + " " + _vessel.toLowerCase() + "s";
    }

    public static class CollectWaterBucketTask extends CollectBucketLiquidTask {
        public CollectWaterBucketTask(int targetCount) {
            super("water", Items.WATER_BUCKET, targetCount, Blocks.WATER);
        }
    }

    public static class CollectLavaBucketTask extends CollectBucketLiquidTask {
        public CollectLavaBucketTask(int targetCount) {
            super("lava", Items.LAVA_BUCKET, targetCount, Blocks.LAVA);
        }
    }

    // a glass bottle on a water source, for the brewing stand (the nether has no water to fill one at). a water bottle is a
    // potion, so it is counted and finished by what is in the bottle, never by the item alone
    public static class CollectWaterBottleTask extends CollectBucketLiquidTask {
        private final int _bottles;

        public CollectWaterBottleTask(int targetCount) {
            super("water", Items.POTION, Items.GLASS_BOTTLE, "Bottle", targetCount, Blocks.WATER);
            _bottles = targetCount;
        }

        @Override
        protected int filledCount(AltoClef mod) {
            return PotionHelper.count(mod, Potions.WATER);
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return filledCount(mod) >= _bottles;
        }
    }

}
