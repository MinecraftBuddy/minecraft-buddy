package adris.altoclef.tasks.construction.compound;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.ClearLiquidTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.CastLava;
import adris.altoclef.tasks.construction.PlaceObsidianBucketTask;
import adris.altoclef.tasks.resources.CollectBucketLiquidTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.BreakRules;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Build a nether portal by casting each piece with water + lava.
 * <p>
 * Currently the most reliable portal building method.
 */
public class ConstructNetherPortalBucketTask extends Task {

    // Order here matters
    private static final Vec3i[] PORTAL_FRAME = new Vec3i[]{
            // Left side
            new Vec3i(0, 0, -1),
            new Vec3i(0, 1, -1),
            new Vec3i(0, 2, -1),
            // Right side
            new Vec3i(0, 0, 2),
            new Vec3i(0, 1, 2),
            new Vec3i(0, 2, 2),
            // Top
            new Vec3i(0, 3, 0),
            new Vec3i(0, 3, 1),
            // Bottom
            new Vec3i(0, -1, 0),
            new Vec3i(0, -1, 1)
    };

    private static final Vec3i[] PORTAL_INTERIOR = new Vec3i[]{
            //Inside
            new Vec3i(0, 0, 0),
            new Vec3i(0, 1, 0),
            new Vec3i(0, 2, 0),
            new Vec3i(0, 0, 1),
            new Vec3i(0, 1, 1),
            new Vec3i(0, 2, 1),
            //Outside 1
            new Vec3i(1, 0, 0),
            new Vec3i(1, 1, 0),
            new Vec3i(1, 2, 0),
            new Vec3i(1, 0, 1),
            new Vec3i(1, 1, 1),
            new Vec3i(1, 2, 1),
            //Outside 2
            new Vec3i(-1, 0, 0),
            new Vec3i(-1, 1, 0),
            new Vec3i(-1, 2, 0),
            new Vec3i(-1, 0, 1),
            new Vec3i(-1, 1, 1),
            new Vec3i(-1, 2, 1)
    };

    // the first six of those are the cells in the portal's plane
    private static final int PORTAL_IN_PLANE = 6;

    // The "portalable" region includes the portal (1 x 6 x 4 structure) and an outer buffer for its construction and water bullshit.
    // The "portal origin relative to region" corresponds to the portal origin with respect to the "portalable" region (see _portalOrigin).
    // This can only really be explained visually, sorry!
    private static final Vec3i PORTALABLE_REGION_SIZE = new Vec3i(4, 6, 6);
    private static final Vec3i PORTAL_ORIGIN_RELATIVE_TO_REGION = new Vec3i(1, 0, 2);
    private final TimerGame _lavaSearchTimer = new TimerGame(5);
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final TimeoutWanderTask wanderTask = new TimeoutWanderTask(5);
    // Stored here to cache lava blacklist
    // the cast's own cells are off the menu, the pour we just made is not a lake
    private final CollectBucketLiquidTask _collectLavaTask = new CollectBucketLiquidTask.CollectLavaBucketTask(1)
            .leaveAlone(cell -> this._cast != null && (this._cast.scoopOff(cell) || this._cast.offBand(cell)));
    // no lake in reach (the search came back empty), for the portal phase's no lava clock
    private boolean _noLake;
    private final TimerGame _refreshTimer = new TimerGame(11);
    private BlockPos _portalOrigin = null;
    // the cast for the frame cell we're on, handed back every tick so it's the one that's actually running
    private PlaceObsidianBucketTask _cast;
    private BlockPos _castOrigin;

    //private BlockPos _currentLavaTarget = null;
    private BlockPos _currentDestroyTarget = null;
    // ticks the interior cell we're on has gone without a ray, see InteriorPick
    private int _noReachTicks;
    private int _reachSwitches;
    // the frame whose outside cells have all been seen air once, see InteriorPick.preemptCount, and which of them we have seen
    private BlockPos _ringClearFor;
    private BlockPos _ringSeenFor;
    private final java.util.Set<BlockPos> _ringSeen = new java.util.HashSet<>();
    private boolean _firstSearch = false;
    private static final int LAKE_Y_BAND = 16;
    private static final float WANDER_LEG = 64;

    // where the frame is going, null while we're still looking for a spot. PortalPhase reads this with castCellsDone to
    // tell a slow cast from a stuck one
    public BlockPos portalOrigin() {
        return _portalOrigin;
    }

    // nothing to fetch lava from: no lake in reach, or the fetch finds no source it can use. PortalPhase counts that as no lava
    public boolean lavaDrought() {
        return _noLake || _collectLavaTask.noSource();
    }

    public int castCellsDone(AltoClef mod) {
        return castCellsDone(_portalOrigin, cell -> mod.getWorld().getBlockState(cell).getBlock() == Blocks.OBSIDIAN);
    }

    // how many of the ten frame cells are obsidian already
    static int castCellsDone(BlockPos origin, Predicate<BlockPos> isObsidian) {
        if (origin == null) {
            return 0;
        }
        int done = 0;
        for (Vec3i frame : PORTAL_FRAME) {
            if (isObsidian.test(origin.offset(frame))) {
                done++;
            }
        }
        return done;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.LAVA);

        _currentDestroyTarget = null;
        _ringClearFor = null;
        _ringSeenFor = null;
        _reachSwitches = 0;
        _noReachTicks = 0;

        mod.getBehaviour().push();

        // Avoid breaking portal frame if we're obsidian.
        // Also avoid placing on the lava + water
        // Also avoid breaking the cast frame
        mod.getBehaviour().avoidBlockBreaking(block -> {
            if (_portalOrigin != null) {
                // Don't break frame
                for (Vec3i framePosRelative : PORTAL_FRAME) {
                    BlockPos framePos = _portalOrigin.offset(framePosRelative);
                    if (block.equals(framePos)) {
                        return mod.getWorld().getBlockState(framePos).getBlock() == Blocks.OBSIDIAN;
                    }
                }
            }
            return false;
        });

        // Protect some used items
        mod.getBehaviour().addProtectedItems(Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.FLINT_AND_STEEL, Items.FIRE_CHARGE);

        _progressChecker.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_portalOrigin != null) {
            if (mod.getWorld().getBlockState(_portalOrigin.above()).getBlock() == Blocks.NETHER_PORTAL) {
                setDebugState("Done constructing nether portal.");
                // every lit cell, same as the obsidian variant: the enter task wants a bottom cell and the tracker's own
                // scan won't hear about this portal for a while
                for (Vec3i inside : PORTAL_INTERIOR) {
                    BlockPos cell = _portalOrigin.offset(inside);
                    if (mod.getWorld().getBlockState(cell).getBlock() == Blocks.NETHER_PORTAL) {
                        mod.getBlockTracker().addBlock(Blocks.NETHER_PORTAL, cell);
                    }
                }
                return null;
            }
        }
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _progressChecker.reset();
        }
        if (wanderTask.isActive() && !wanderTask.isFinished(mod)) {
            setDebugState("Trying again.");
            _progressChecker.reset();
            return wanderTask;
        }

        if (!_progressChecker.check(mod)) {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            // a checker that failed stays failed until somebody resets it, and with nothing moving nobody does. without this
            // the cancel above runs every tick and kills every search the child starts (it never gets to finish or fail)
            _progressChecker.reset();
            if (_portalOrigin != null && _currentDestroyTarget != null) {
                mod.getBlockTracker().requestBlockUnreachable(_portalOrigin);
                mod.getBlockTracker().requestBlockUnreachable(_currentDestroyTarget);
                if (mod.getBlockTracker().unreachable(_portalOrigin) && mod.getBlockTracker().unreachable(_currentDestroyTarget)) {
                    _portalOrigin = null;
                    _currentDestroyTarget = null;
                }
                return wanderTask;
            }
        }
        if (_refreshTimer.elapsed()) {
            Debug.logMessage("Duct tape: Refreshing inventory again just in case");
            _refreshTimer.reset();
            mod.getSlotHandler().refreshInventory();
        }

        //If too far, reset.
        if (_portalOrigin != null && !_portalOrigin.closerToCenterThan(mod.getPlayer().position(), 2000)) {
            _portalOrigin = null;
            _currentDestroyTarget = null;
        }

        if (_currentDestroyTarget != null) {
            // same test the picker below uses, or a fluid/fire/plant in the interior gets picked and dropped every tick
            BlockState targetState = mod.getWorld().getBlockState(_currentDestroyTarget);
            if (targetState.isAir() || (_portalOrigin != null && ringCellDone(mod, _currentDestroyTarget))) {
                _currentDestroyTarget = null;
            } else if (!isLiquid(targetState) && WorldHelper.tooSlowToBreak(mod, _currentDestroyTarget)) {
                moveFrame(mod, _currentDestroyTarget, "portal interior");
                return null;
            } else if (_portalOrigin != null && switchedInteriorCell(mod)) {
                // a cell we can actually hit beats one we've been staring past. next tick takes it from the top
                return null;
            } else {
                return clearInteriorCell(targetState, _currentDestroyTarget);
            }
        }
        // Get flint & steel if we don't have one
        if (!mod.getItemStorage().hasItem(Items.FLINT_AND_STEEL) && !mod.getItemStorage().hasItem(Items.FIRE_CHARGE)) {
            setDebugState("Getting flint & steel");
            _progressChecker.reset();
            return TaskCatalogue.getItemTask(Items.FLINT_AND_STEEL, 1);
        }
        // Get bucket if we don't have one.
        int bucketCount = mod.getItemStorage().getItemCount(Items.BUCKET, Items.LAVA_BUCKET, Items.WATER_BUCKET);
        if (bucketCount < 2) {
            setDebugState("Getting buckets");
            _progressChecker.reset();
            // If we have lava/water, get the inverse. Otherwise we dropped a bucket, just get a bucket.
            if (mod.getItemStorage().hasItem(Items.LAVA_BUCKET)) {
                return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
            } else if (mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                return TaskCatalogue.getItemTask(Items.LAVA_BUCKET, 1);
            }
            if (mod.getEntityTracker().itemDropped(Items.WATER_BUCKET, Items.LAVA_BUCKET)) {
                return new PickupDroppedItemTask(new ItemTarget(new Item[]{Items.WATER_BUCKET, Items.LAVA_BUCKET}, 1), true);
            }
            return TaskCatalogue.getItemTask(Items.BUCKET, 2);
        }

        boolean needsToLookForPortal = _portalOrigin == null;
        if (needsToLookForPortal) {
            _progressChecker.reset();
            // Get water before searching, just for convenience.
            if (!mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                setDebugState("Getting water");
                _progressChecker.reset();
                return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
            }

            boolean foundSpot = false;

            if (_firstSearch || _lavaSearchTimer.elapsed()) {
                _firstSearch = false;
                _lavaSearchTimer.reset();
                Debug.logMessage("(Searching for lava lake with portalable spot nearby...)");
                List<BlockPos> lakes = findLavaLakes(mod, mod.getPlayer().blockPosition());
                _noLake = lakes.isEmpty();
                if (!lakes.isEmpty()) {
                    // nearest lake first, but a lake whose every site is wet, too slow to clear or banned by a ruined cast
                    // used to mean sitting out the whole give up timer next to it. the next lake over is right there
                    for (BlockPos lavaPos : lakes) {
                        BlockPos foundPortalRegion = getPortalableRegion(mod, lavaPos, mod.getPlayer().blockPosition(), new Vec3i(-1, 0, 0), PORTALABLE_REGION_SIZE, 20);
                        if (foundPortalRegion != null) {
                            _portalOrigin = foundPortalRegion.offset(PORTAL_ORIGIN_RELATIVE_TO_REGION);
                            foundSpot = true;
                            break;
                        }
                    }
                    if (!foundSpot) {
                        Debug.logWarning("Failed to find portalable region nearby. Consider increasing the search timeout range");
                    }
                } else {
                    Debug.logMessage("(lava lake not found)");
                }
            }

            if (!foundSpot) {
                setDebugState("(timeout: Looking for lava lake)");
                // same leg at a time as the fetch: a finished one is dropped so the next starts from where we stand
                if (subTask() instanceof TimeoutWanderTask leg && leg.isFinished(mod)) dropChild(mod);
                return new TimeoutWanderTask(WANDER_LEG);
            }
        }

        // We have a portal, now build it.
        for (Vec3i framePosRelative : PORTAL_FRAME) {
            BlockPos framePos = _portalOrigin.offset(framePosRelative);
            Block frameBlock = mod.getWorld().getBlockState(framePos).getBlock();
            if (frameBlock == Blocks.OBSIDIAN) {
                // Already satisfied, clear water above if need be.
                BlockPos waterCheck = framePos.above();
                if (mod.getWorld().getBlockState(waterCheck).getBlock() == Blocks.WATER && WorldHelper.isSourceBlock(mod, waterCheck, true)) {
                    setDebugState("Clearing water from cast");
                    return new ClearLiquidTask(waterCheck);
                }
                continue;
            }

            // a banned frame cell (a ruined cast, or our lava loose round it) moves the frame before anything else. going
            // for lava first would fetch a bucket for a spot we're about to leave
            if (mod.getBlockTracker().unreachable(framePos)) {
                _portalOrigin = null;
                _cast = null;
                return null;
            }

            // We need to place obsidian here.
            // the cells before this one may still have their cast water on top, that is our water bucket. the same task
            // comes back each tick so we can ask it about its lava, a fresh one each tick never ticks
            // the origin can move under us in a few places, and a new one can share a frame cell with the old one
            if (_cast == null || !_cast.getPos().equals(framePos) || !_portalOrigin.equals(_castOrigin)) {
                _cast = new PlaceObsidianBucketTask(framePos, frameCells(), strayCells(_portalOrigin), aroundCells(_portalOrigin));
                _castOrigin = _portalOrigin;
            }

            // Get lava early so placing it is faster. not while the cast has lava of its own out: the pour that just went
            // has no bucket to show for it yet, and a spill is the cast's to scoop before anyone walks off
            if (!mod.getItemStorage().hasItem(Items.LAVA_BUCKET) && frameBlock != Blocks.LAVA && !_cast.ownsTheLava(mod)) {
                setDebugState("Collecting lava");
                _progressChecker.reset();
                _cast.startBandClock(mod);
                return _collectLavaTask;
            }
            return _cast;
        }

        // Now, clear the inside.
        BlockPos p = InteriorPick.pick(mod, _portalOrigin, PORTAL_INTERIOR, interiorCount(mod), cell -> mod.getWorld().getBlockState(cell).isAir(), null, 0, _reachSwitches);
        if (p != null) {
            if (!isLiquid(mod.getWorld().getBlockState(p)) && WorldHelper.tooSlowToBreak(mod, p)) {
                moveFrame(mod, p, "portal interior");
                return null;
            }
            setDebugState("Clearing inside of portal");
            _currentDestroyTarget = p;
            _noReachTicks = 0;
            return null;
        }

        setDebugState("Flinting and Steeling");
        // Flint and steel it baby
        return new InteractWithBlockTask(new ItemTarget(new Item[]{Items.FLINT_AND_STEEL, Items.FIRE_CHARGE}, 1), Direction.UP, _portalOrigin.below(), true);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.LAVA);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ConstructNetherPortalBucketTask;
    }

    @Override
    protected String toHudString() {
        return "Building a Nether portal";
    }

    @Override
    protected String toDebugString() {
        return "Construct Nether Portal";
    }

    // how many of PORTAL_INTERIOR may stop the flint right now: the six in the portal's plane come first in the list, the
    // twelve outside ones only until they have all been air once
    private int interiorCount(AltoClef mod) {
        if (_portalOrigin == null) {
            return PORTAL_INTERIOR.length;
        }
        if (!_portalOrigin.equals(_ringSeenFor)) {
            _ringSeen.clear();
            _ringSeenFor = _portalOrigin;
            _ringClearFor = null;
        }
        if (_ringClearFor == null) {
            List<BlockPos> cells = new ArrayList<>(PORTAL_INTERIOR.length);
            for (Vec3i offs : PORTAL_INTERIOR) {
                cells.add(_portalOrigin.offset(offs));
            }
            _ringClearFor = InteriorPick.ringSeenClear(cells, PORTAL_IN_PLANE, _ringSeen, cell -> mod.getWorld().getBlockState(cell).isAir()) ? _portalOrigin : null;
        }
        return InteriorPick.preemptCount(PORTAL_INTERIOR.length, PORTAL_IN_PLANE, _ringClearFor != null);
    }

    // a ring cell that refilled after the whole ring was clear once: not ours to chase any more
    private boolean ringCellDone(AltoClef mod, BlockPos cell) {
        if (interiorCount(mod) >= PORTAL_INTERIOR.length) {
            return false;
        }
        for (int i = PORTAL_IN_PLANE; i < PORTAL_INTERIOR.length; i++) {
            if (cell.equals(_portalOrigin.offset(PORTAL_INTERIOR[i]))) {
                return true;
            }
        }
        return false;
    }

    // count the cell we're on going without a ray and move to a better one when InteriorPick says so
    private boolean switchedInteriorCell(AltoClef mod) {
        _noReachTicks = InteriorPick.reachNow(mod, _currentDestroyTarget) ? 0 : _noReachTicks + 1;
        BlockPos next = InteriorPick.pick(mod, _portalOrigin, PORTAL_INTERIOR, interiorCount(mod), cell -> mod.getWorld().getBlockState(cell).isAir(), _currentDestroyTarget, _noReachTicks, _reachSwitches);
        if (next == null || next.equals(_currentDestroyTarget)) {
            return false;
        }
        if (!mod.getBlockTracker().unreachable(_currentDestroyTarget)) {
            _reachSwitches++;
        }
        _currentDestroyTarget = next;
        _noReachTicks = 0;
        return true;
    }

    // fluids come out with a bucket, everything else gets broken. the obsidian variant asks too
    static boolean isLiquid(BlockState state) {
        return state.getBlock() instanceof LiquidBlock;
    }

    static Task clearInteriorCell(BlockState state, BlockPos pos) {
        return isLiquid(state) ? new ClearLiquidTask(pos) : new DestroyBlockTask(pos);
    }

    // a cell the build actually has to clear: the frame cells (the cast breaks what is in them), the air over each (lava and
    // water cells get two cleared above), and the interior. the buffer round the box and the mould blocks are never broken
    static boolean clearedCell(BlockPos origin, BlockPos cell) {
        for (Vec3i frame : PORTAL_FRAME) {
            BlockPos f = origin.offset(frame);
            if (cell.equals(f) || cell.equals(f.above()) || cell.equals(f.above(2))) {
                return true;
            }
        }
        for (Vec3i inside : PORTAL_INTERIOR) {
            if (cell.equals(origin.offset(inside))) {
                return true;
            }
        }
        return false;
    }

    // a block in a cell the build clears that takes too long to break, and is not one of our own finished frame cells
    // (those are obsidian by design, the frame loop skips them)
    static boolean strayBlock(BlockPos origin, BlockPos cell, boolean tooSlow, boolean obsidian) {
        if (!tooSlow || !clearedCell(origin, cell)) {
            return false;
        }
        if (obsidian) {
            for (Vec3i frame : PORTAL_FRAME) {
                if (cell.equals(origin.offset(frame))) {
                    return false;
                }
            }
        }
        return true;
    }

    // the inside of the box and the buffer the casts stand in: every frame cell's ledge and the head room over it. never
    // frame cells, those end up obsidian by design. the origin search only picks a spot with no lava anywhere near these,
    // so lava in one later is ours, and obsidian in one is a cast that went wrong
    static List<BlockPos> aroundCells(BlockPos origin) {
        LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
        for (Vec3i inside : PORTAL_INTERIOR) {
            out.add(origin.offset(inside));
        }
        for (Vec3i frame : PORTAL_FRAME) {
            BlockPos ledge = origin.offset(frame).offset(-1, 1, 0);
            out.add(ledge);
            out.add(ledge.above());
        }
        for (Vec3i frame : PORTAL_FRAME) {
            out.remove(origin.offset(frame));
        }
        return new ArrayList<>(out);
    }

    // where none of our lava belongs: the frame (the cast takes its own cell back out) plus everything round it
    static List<BlockPos> strayCells(BlockPos origin) {
        LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
        for (Vec3i frame : PORTAL_FRAME) {
            out.add(origin.offset(frame));
        }
        out.addAll(aroundCells(origin));
        return new ArrayList<>(out);
    }

    static boolean anyBanned(BlockPos origin, Predicate<BlockPos> banned) {
        for (Vec3i frame : PORTAL_FRAME) {
            if (banned.test(origin.offset(frame))) {
                return true;
            }
        }
        return false;
    }

    // the frame moves: nothing here is worth another look
    private void moveFrame(AltoClef mod, BlockPos stray, String role) {
        Debug.logMessage("cast ruined: " + BuiltInRegistries.BLOCK.getKey(mod.getWorld().getBlockState(stray).getBlock()).getPath()
                + " at " + stray.toShortString() + " where the " + role + " cell goes, moving the frame");
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        _portalOrigin = null;
        _currentDestroyTarget = null;
    }

    private List<BlockPos> frameCells() {
        List<BlockPos> out = new ArrayList<>();
        for (Vec3i framePosRelative : PORTAL_FRAME) {
            out.add(_portalOrigin.offset(framePosRelative));
        }
        return out;
    }

    // every lake big enough, one spot each, nearest first. walking the sources nearest first means the first source of a
    // lake we flood from is that lake's closest one, so the list comes out sorted for free
    private List<BlockPos> findLavaLakes(AltoClef mod, BlockPos playerPos) {
        HashSet<BlockPos> alreadyExplored = new HashSet<>();
        List<BlockPos> lakes = new ArrayList<>();
        List<BlockPos> lavas = new ArrayList<>(mod.getBlockTracker().getKnownLocations(Blocks.LAVA));
        lavas.sort(Comparator.comparingDouble(playerPos::distSqr));
        for (BlockPos pos : lavas) {
            if (alreadyExplored.contains(pos)) continue;
            // a lake 60 blocks down is a lake we would dig to, not one we cast at
            if (!inYBand(pos.getY(), playerPos.getY())) continue;
            int depth = getNumberOfBlocksAdjacent(alreadyExplored, pos);
            // every lone source gets flooded now, only the real lakes are worth a chat line
            if (depth >= LAKE_MIN_SOURCES) {
                Debug.logMessage("Found with depth " + depth);
                lakes.add(pos);
            }
        }
        return lakes;
    }

    // the flood the cast wants before it calls a pool a lake (LPortalTask walks to one the pool could not build on)
    public static final int LAKE_MIN_SOURCES = 12;

    static boolean inYBand(int lakeY, int playerY) {
        return Math.abs(lakeY - playerY) <= LAKE_Y_BAND;
    }

    // Used to flood-scan for blocks of lava.
    private int getNumberOfBlocksAdjacent(HashSet<BlockPos> alreadyExplored, BlockPos origin) {
        assert Minecraft.getInstance().level != null;
        return lakeSize(origin, ConstructNetherPortalBucketTask::isFullLavaSource, alreadyExplored);
    }

    public static boolean isFullLavaSource(BlockPos pos) {
        BlockState s = Minecraft.getInstance().level.getBlockState(pos);
        return s.getBlock() == Blocks.LAVA && s.getFluidState().isSource() && s.getFluidState().getAmount() == 8;
    }

    // full sources touching start, 6 ways. every cell looked at goes in explored, source or not, so findLavaLakes skips
    // the rest of a lake it already counted. this used to recurse one frame per source and a deep lava lake is a lot of
    // sources, so now it's a stack we own instead of the jvm's
    public static int lakeSize(BlockPos start, Predicate<BlockPos> isFullSource, Set<BlockPos> explored) {
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        todo.push(start);
        int count = 0;
        while (!todo.isEmpty()) {
            BlockPos pos = todo.pop();
            // add() says no when it was already there, same as the old "explored? return 0"
            if (!explored.add(pos) || !isFullSource.test(pos)) continue;
            count++;
            for (BlockPos n : new BlockPos[]{pos.north(), pos.south(), pos.east(), pos.west(), pos.above(), pos.below()}) {
                // seen ones would just pop and die, don't let them pile up
                if (!explored.contains(n)) todo.push(n);
            }
        }
        return count;
    }

    // Get a region that a portal can fit into
    private BlockPos getPortalableRegion(AltoClef mod, BlockPos lava, BlockPos playerPos, Vec3i sizeOffset, Vec3i sizeAllocation, int timeoutRange) {
        Vec3i[] directions = new Vec3i[]{new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0), new Vec3i(0, 0, 1), new Vec3i(0, 0, -1)};

        double minDistanceToPlayer = Double.POSITIVE_INFINITY;
        BlockPos bestPos = null;
        List<ItemStack> bag = WorldHelper.bagOf(mod);

        for (Vec3i direction : directions) {

            // Inch along
            for (int offs = 1; offs < timeoutRange; ++offs) {

                Vec3i offset = new Vec3i(direction.getX() * offs, direction.getY() * offs, direction.getZ() * offs);

                boolean found = true;
                boolean solidFound = false;
                BlockPos origin = lava.offset(offset).offset(sizeOffset).offset(PORTAL_ORIGIN_RELATIVE_TO_REGION);
                // the cast gives a spot up on sight for obsidian or a slow block on a ledge it stands on, so don't pick one
                HashSet<BlockPos> ledges = new HashSet<>(aroundCells(origin));
                // check for collision with lava in box
                // We have an extra buffer to make sure we never break a block NEXT to lava.
                moveAlongLine:
                for (int dx = -1; dx < sizeAllocation.getX() + 1; ++dx) {
                    for (int dz = -1; dz < sizeAllocation.getZ() + 1; ++dz) {
                        for (int dy = -1; dy < sizeAllocation.getY(); ++dy) {
                            BlockPos toCheck = lava.offset(offset).offset(sizeOffset).offset(dx, dy, dz);
                            assert Minecraft.getInstance().level != null;
                            BlockState state = Minecraft.getInstance().level.getBlockState(toCheck);
                            if (state.getBlock() == Blocks.LAVA || state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.BEDROCK) {
                                found = false;
                                break moveAlongLine;
                            }
                            // a block we could never get out of the way in time (obsidian from some old lava and water) ruins
                            // the spot: the cast has to clear it, and the frame would just move again once it found out
                            boolean tooSlow = BreakRules.tooSlow(bag, state);
                            if (strayBlock(origin, toCheck, tooSlow, state.is(Blocks.OBSIDIAN))
                                    || ledges.contains(toCheck) && (tooSlow || state.is(Blocks.OBSIDIAN))) {
                                found = false;
                                break moveAlongLine;
                            }
                            // Also check for at least 1 solid block for us to place on...
                            if (dy <= 1 && !solidFound && WorldHelper.isSolid(mod, toCheck)) {
                                solidFound = true;
                            }
                        }
                    }
                }
                // Check for solid ground at least somewhere
                if (!solidFound) {
                    break;
                }

                // a frame cell we were already told is a lost cause (the cast bans it when something in it is too slow to break)
                if (found && anyBanned(origin, mod.getBlockTracker()::unreachable)) {
                    found = false;
                }

                if (found) {
                    BlockPos foundBoxCorner = lava.offset(offset).offset(sizeOffset);
                    double sqDistance = foundBoxCorner.distSqr(playerPos);
                    if (sqDistance < minDistanceToPlayer) {
                        minDistanceToPlayer = sqDistance;
                        bestPos = foundBoxCorner;
                    }
                    break;
                }
            }

        }

        return bestPos;
    }
}
