package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.AnnoyingBlocks;
import adris.altoclef.Debug;
import adris.altoclef.trace.Trace;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasks.construction.PlaceStationTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.LandMemory;
import adris.altoclef.trackers.MiscBlockTracker;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.PortalExit;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import baritone.process.ExploreProcess;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Call this when the place you're currently at is bad for some reason and you just wanna get away.
 */
public class TimeoutWanderTask extends Task implements ITaskRequiresGrounded {
    // same budget as PlaceStationTask's swim back, swimming is slow
    private static final double TURN_BACK_SECONDS = 90;

    private MovementProgressChecker stuckCheck;
    private final float _distanceToWander;
    private MovementProgressChecker _progressChecker;
    private final boolean _increaseRange;
    private Vec3 _origin;
    //private DistanceProgressChecker _distanceProgressChecker = new DistanceProgressChecker(10, 0.1f);
    private boolean _forceExplore;
    private final adris.altoclef.util.helpers.LandFinder.Steer _toLand = new adris.altoclef.util.helpers.LandFinder.Steer();
    private final adris.altoclef.util.helpers.RimRetreat _offRim = new adris.altoclef.util.helpers.RimRetreat();
    private final adris.altoclef.util.helpers.GhastCover _cover = new adris.altoclef.util.helpers.GhastCover();
    private boolean _reorigin;
    private Task _unstuckTask = null;
    private int _failCounter;
    private double _wanderDistanceExtension;

    // unlike _origin these survive onStart: the parent keeps this same instance across interrupts, and a fresh anchor
    // after every interrupt would make a wander that is going nowhere look like it just got somewhere
    private final WanderGiveUp.Excursion _excursion = new WanderGiveUp.Excursion();
    private boolean _failingLogged;

    private final TimerGame _seaCheck = new TimerGame(1);
    private final TimerGame _turnBackTimer = new TimerGame(TURN_BACK_SECONDS);
    private Task _turnBack;
    // where the rings go around. not _origin: that one is how far a finite wander has come, and landing somewhere after a
    // crossing shouldn't reset that
    private Vec3 _exploreFrom;
    private BlockPos _crossingLogged;

    public TimeoutWanderTask(float distanceToWander, boolean increaseRange) {
        _distanceToWander = distanceToWander;
        _increaseRange = increaseRange;
        _forceExplore = false;
    }

    public TimeoutWanderTask(float distanceToWander) {
        this(distanceToWander, false);
    }

    public TimeoutWanderTask() {
        this(Float.POSITIVE_INFINITY, false);
    }

    public TimeoutWanderTask(boolean forceExplore) {
        this();
        _forceExplore = forceExplore;
    }

    private static BlockPos[] generateSides(BlockPos pos) {
        return new BlockPos[]{
                pos.offset(1, 0, 0),
                pos.offset(-1, 0, 0),
                pos.offset(0, 0, 1),
                pos.offset(0, 0, -1),
                pos.offset(1, 0, -1),
                pos.offset(1, 0, 1),
                pos.offset(-1, 0, -1),
                pos.offset(-1, 0, 1)
        };
    }

    private boolean isAnnoying(AltoClef mod, BlockPos pos) {
        return AnnoyingBlocks.isAnnoying(mod.getWorld().getBlockState(pos).getBlock());
    }

    public void resetWander() {
        _wanderDistanceExtension = 0;
    }

    // This happens all the time in mineshafts and swamps/jungles
    private BlockPos stuckInBlock(AltoClef mod) {
        BlockPos p = mod.getPlayer().blockPosition();
        if (isAnnoying(mod, p)) return p;
        if (isAnnoying(mod, p.above())) return p.above();
        BlockPos[] toCheck = generateSides(p);
        for (BlockPos check : toCheck) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        BlockPos[] toCheckHigh = generateSides(p.above());
        for (BlockPos check : toCheckHigh) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        return null;
    }

    private Task getFenceUnstuckTask() {
        return new SafeRandomShimmyTask();
    }

    // true once the wander has run a good while without ever getting out of its spot. it keeps wandering, this is
    // only for whoever asked for it to read
    public boolean failing() {
        return failing(timeOnTaskSeconds(), _excursion.max());
    }

    // numbers in so a test can ask without a world
    static boolean failing(double secondsOnTask, double maxExcursionBlocks) {
        return WanderGiveUp.decide(secondsOnTask, maxExcursionBlocks) == WanderGiveUp.Stage.FAILING;
    }

    void noteAt(double x, double y, double z) {
        _excursion.note(x, y, z);
    }

    // keeps us out of the ocean, null means carry on exploring. each turn back costs the frontier it swam at, so this ends on
    // land or in a crossing and never swims back and forth at the same beach
    private Task landStep(AltoClef mod) {
        LandMemory land = mod.getLandMemory();
        if (!land.active()) {
            return null;
        }
        ExploreProcess explore = mod.getClientBaritone().getExploreProcess();
        LocalPlayer player = mod.getPlayer();
        BlockPos me = player.blockPosition();
        if (_turnBack != null) {
            if (_turnBack.isFinished(mod)) {
                _turnBack = null;
                return null;
            }
            if (_turnBackTimer.elapsed()) {
                // 90 s and still not there, it's closer to go on than to keep at it. only written down here: the swim back
                // stopping on our way out cancels every process, a crossing started now wouldn't see the next tick
                _turnBack = null;
                land.startCrossing(me.getX(), me.getZ());
                return null;
            }
            return _turnBack;
        }
        if (land.crossing()) {
            crossingStep(mod, land, explore, player);
            return null;
        }
        if (!LandFrontier.turnsBack(_distanceToWander) || !_seaCheck.elapsed()) {
            return null;
        }
        _seaCheck.reset();
        Optional<BlockPos> dry = mod.getMiscBlockTracker().getLastDryLand(WorldHelper.getCurrentDimension());
        double distToDry = dry.map(p -> Math.sqrt(me.distSqr(p))).orElse(Double.POSITIVE_INFINITY);
        // the shore scan is thousands of block reads, so it only gets asked once everything else already says open water
        if (!LandFrontier.openWater(player.isInWater(), player.onGround(), distToDry, false) || PlaceStationTask.shoreNear(mod).isPresent()) {
            return null;
        }
        if (dry.isEmpty()) {
            // never stood on anything in this world, nothing to go back to
            land.startCrossing(me.getX(), me.getZ());
            return null;
        }
        List<ChunkPos> banned = LandFrontier.toBan(dry.get().getX(), dry.get().getZ(), me.getX(), me.getZ(), explore.lastFrontier());
        banned.forEach(land::ban);
        Debug.logInternal("wander: open water " + (int) distToDry + " blocks from dry land, heading back to " + dry.get().toShortString()
                + ", " + banned.size() + " frontier chunks off the list");
        explore.onLostControl();
        _turnBack = new GetToBlockTask(dry.get());
        _turnBackTimer.reset();
        return _turnBack;
    }

    private void crossingStep(AltoClef mod, LandMemory land, ExploreProcess explore, LocalPlayer player) {
        BlockPos me = player.blockPosition();
        if (!explore.isCrossing()) {
            // every interrupt cancels every process. a fight out at sea doesn't mean swimming back to the island we gave up on
            startExplore(mod);
            explore.crossFrom(me.getX(), me.getZ());
        }
        BlockPos start = land.crossingStart();
        if (!start.equals(_crossingLogged)) {
            _crossingLogged = start;
            Debug.logInternal("wander: no land frontier, crossing from " + me.toShortString() + " toward "
                    + explore.crossingTarget().getX() + "," + explore.crossingTarget().getZ());
        }
        // the plain rings stay around the target whatever we walk on, so this is what brings a crossing back
        if (_seaCheck.elapsed()) {
            _seaCheck.reset();
            boolean dry = MiscBlockTracker.isDryLand(player.onGround(), player.isInWater(), player.isPassenger());
            if (LandFrontier.reanchor(dry, dry && explore.landFrontierExists(me.getX(), me.getZ()))) {
                Debug.logInternal("wander: land after a crossing, " + (int) Math.hypot(me.getX() - start.getX(), me.getZ() - start.getZ())
                        + " blocks out, exploring from " + me.toShortString());
                // or the ring around the old origin pulls us straight back over the water
                land.endCrossing();
                _exploreFrom = player.position();
                startExplore(mod);
                return;
            }
        }
        BlockPos target = explore.crossingTarget();
        if (LandFrontier.crossingArrived(Math.hypot(me.getX() - target.getX(), me.getZ() - target.getZ()))) {
            // spiralling around one spot in the middle of an ocean finds nothing, keep the heading
            explore.crossFrom(me.getX(), me.getZ());
        }
    }

    private void startExplore(AltoClef mod) {
        LandMemory land = mod.getLandMemory();
        land.sweep();
        mod.getClientBaritone().getExploreProcess().explore((int) _exploreFrom.x(), (int) _exploreFrom.z(), land);
    }

    private boolean checkFailing(AltoClef mod) {
        Vec3 pos = mod.getPlayer().position();
        noteAt(pos.x(), pos.y(), pos.z());
        boolean failing = failing();
        if (failing && !_failingLogged) {
            _failingLogged = true;
            Debug.logWarning(String.format("wander failing: %.0f s on task, never more than %.1f blocks from where it started",
                    timeOnTaskSeconds(), _excursion.max()));
            // rate limited by its reason prefix, so a pile of stuck wanders is one dump
            Trace.dump("monitor:wander");
        }
        return failing;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getClientBaritone().getPathingBehavior().cancelWhenSafe();
        _origin = mod.getPlayer().position();
        _exploreFrom = _origin;
        // a swim back from before an interrupt may have nothing to do with where the interrupt left us
        _turnBack = null;
        // built here and not in the field initializers because the checkers read the game clock, and a wander has to be
        // constructible without a game so a test can hang one in a task tree
        _progressChecker = new MovementProgressChecker();
        stuckCheck = new MovementProgressChecker();
        _failCounter = 0;
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            moveTo.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ContainerInput.PICKUP));
            if (ItemHelper.canDropStack(mod, cursorStack)) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            garbage.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ContainerInput.PICKUP));
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
        } else {
            StorageHelper.closeScreen();
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        boolean failing = checkFailing(mod);
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _progressChecker.reset();
        }
        if (WorldHelper.isInNetherPortal(mod)) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                setDebugState("Getting out from nether portal");
                PortalExit.step(mod);
                return null;
            } else {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            }
        } else {
            if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            }
        }
        Task back = landStep(mod);
        if (back != null) {
            setDebugState("Swimming back to dry land.");
            return back;
        }
        // shimmy holds sneak+forward+click, in a meadow of tall grass it never runs out of grass to be stuck in. a
        // failing wander drops it and goes back to exploring
        if (!failing && _unstuckTask != null && _unstuckTask.isActive() && !_unstuckTask.isFinished(mod) && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Stop other tasks, we are JUST shimmying
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return _unstuckTask;
        }
        if (!_progressChecker.check(mod) || !stuckCheck.check(mod)) {
            Optional<Entity> mob = mod.getEntityTracker().getClosestEntity(Mob.class);
            if (mob.isPresent() && mob.get().position().closerThan(mod.getPlayer().position(), 1)) {
                setDebugState("Killing annoying entity.");
                Predicate<Entity> valid = entity -> entity == mob.get();
                return new KillEntitiesTask(valid, mob.get().getClass());
            }
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null && !failing) {
                _failCounter++;
                _unstuckTask = getFenceUnstuckTask();
                return _unstuckTask;
            }
            stuckCheck.reset();
        }
        // the buddy's fork: out on open water for half a game minute it heads for land, where what it is looking for is.
        // a fresh world dropped it in the sea and the explore swam it about for three game days (the runner, 2 Nov 2026)
        // in the nether with no path in a ghast's sight: into cover first (GhastCover)
        Task cover = _cover.next(mod);
        if (cover != null) {
            setDebugState(_cover.what());
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return cover;
        }
        // and in the nether, standing on a rim over the lava sea with no path, back to firm ground first (RimRetreat)
        Task offRim = _offRim.next(mod);
        if (offRim != null) {
            setDebugState(_offRim.what());
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return offRim;
        }
        Task land = _toLand.next(mod);
        if (land == null) land = _toLand.inland(mod);
        if (land != null) {
            setDebugState(_toLand.what());
            mod.getClientBaritone().getExploreProcess().onLostControl();
            _reorigin = true;
            return land;
        }
        setDebugState("Exploring.");
        // after a walk inland the explore starts from here, or it heads back out over the sea it came from
        if (_reorigin) {
            _reorigin = false;
            _origin = mod.getPlayer().position();
            mod.getClientBaritone().getExploreProcess().onLostControl();
        }
        if (!mod.getClientBaritone().getExploreProcess().isActive()) {
            startExplore(mod);
        }
        if (!_progressChecker.check(mod)) {
            _progressChecker.reset();
            if (!_forceExplore) {
                _failCounter++;
                Debug.logMessage("Failed exploring.");
            }
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritone().getPathingBehavior().cancelWhenSafe();
        if (isFinished(mod)) {
            if (_increaseRange) {
                _wanderDistanceExtension += _distanceToWander;
                Debug.logMessage("Increased wander range");
            }
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // Why the heck did I add this in?
        //if (_origin == null) return true;

        if (Float.isInfinite(_distanceToWander)) return false;

        // If we fail 10 times or more, we may as well try the previous task again.
        if (_failCounter > 10) {
            return true;
        }

        if (mod.getPlayer() != null && mod.getPlayer().position() != null && (mod.getPlayer().onGround() ||
                mod.getPlayer().isInWater())) {
            double sqDist = mod.getPlayer().position().distanceToSqr(_origin);
            double toWander = _distanceToWander + _wanderDistanceExtension;
            return sqDist > toWander * toWander;
        } else {
            return false;
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof TimeoutWanderTask task) {
            if (Float.isInfinite(task._distanceToWander) || Float.isInfinite(_distanceToWander)) {
                return Float.isInfinite(task._distanceToWander) == Float.isInfinite(_distanceToWander);
            }
            return Math.abs(task._distanceToWander - _distanceToWander) < 0.5f;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Wander for " + (_distanceToWander + _wanderDistanceExtension) + " blocks";
    }

    @Override
    protected String toHudString() {
        return "Exploring";
    }
}
