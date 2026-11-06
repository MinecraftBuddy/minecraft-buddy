package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalBucketTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalObsidianTask;
import adris.altoclef.tasksystem.Task;
import baritone.altoclef.SettingsOverrides;
import baritone.api.utils.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class EnterNetherPortalTask extends Task {
    private static final double FRAME_STUCK_SECONDS = 3;
    // a drop of 4 is 1 damage, and it is the drop off a standard frame top (3 tall interior + the obsidian above it)
    private static final int FRAME_DROP_FALL_HEIGHT = 5;
    // the tallest interior the game lights is 21, so a walk down further than that is walking down something else
    private static final int MAX_PORTAL_HEIGHT = 21;
    // what the constructors without a goodPortal use. public so PortalPhase can ask usablePortal the same question its enter task does
    public static final Predicate<BlockPos> ANY_PORTAL = blockPos -> true;

    private final Task _getPortalTask;
    private final Dimension _targetDimension;

    private final TimerGame _portalTimeout = new TimerGame(10);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5);

    private final Predicate<BlockPos> _goodPortal;

    private boolean _leftPortal;
    // the bottom cell we are walking to when the tracker only knows cells higher up. kept between ticks so the two bottom
    // cells don't take turns being nearest and restart the path every tick
    private BlockPos _feetTarget;

    // how long we have been standing up on the frame. see onTopOfFrame
    private final TimerGame _onFrameTimer = new TimerGame(FRAME_STUCK_SECONDS);
    // what the user had for maxFallHeightNoWater while we hold it higher, null when we are not holding it
    private Integer _userFallHeight;

    public EnterNetherPortalTask(Task getPortalTask, Dimension targetDimension, Predicate<BlockPos> goodPortal) {
        if (targetDimension == Dimension.END)
            throw new IllegalArgumentException("Can't build a nether portal to the end.");
        _getPortalTask = getPortalTask;
        _targetDimension = targetDimension;
        _goodPortal = goodPortal;
    }

    public EnterNetherPortalTask(Dimension targetDimension, Predicate<BlockPos> goodPortal) {
        this(null, targetDimension, goodPortal);
    }

    public EnterNetherPortalTask(Task getPortalTask, Dimension targetDimension) {
        this(getPortalTask, targetDimension, ANY_PORTAL);
    }

    public EnterNetherPortalTask(Dimension targetDimension) {
        this(null, targetDimension);
    }

    // a portal interior is at least three cells tall and feet only ever fit in the bottom one. aiming a GoalBlock at a
    // higher cell is impossible, so baritone settled for the closest node it could reach, and the top of the frame
    // can be that node. the bot then stood on the obsidian and looked at the portal like it owed it money
    public static boolean isFeetCell(BlockPos cell, Predicate<BlockPos> isPortal) {
        return !isPortal.test(cell.below());
    }

    // the bottom of the portal column a cell is in, a lone cell is its own bottom. the builders used to tell the tracker
    // about the middle cell only, and the tracker's own scan takes its time to learn the rest
    public static BlockPos feetCell(BlockPos cell, Predicate<BlockPos> isPortal) {
        BlockPos at = cell;
        for (int i = 0; i < MAX_PORTAL_HEIGHT && isPortal.test(at.below()); i++) {
            at = at.below();
        }
        return at;
    }

    public static Predicate<BlockPos> portalAt(AltoClef mod) {
        return blockPos -> mod.getWorld().getBlockState(blockPos).getBlock() == Blocks.NETHER_PORTAL;
    }

    // a tracked cell we'd walk to as it is
    public static Predicate<BlockPos> standablePortal(AltoClef mod, Predicate<BlockPos> goodPortal) {
        Predicate<BlockPos> portalAt = portalAt(mod);
        return blockPos -> {
            if (portalAt.test(blockPos)) {
                return isFeetCell(blockPos, portalAt);
            }
            // REQUIRE that there be solid ground beneath us, not more portal.
            if (!mod.getChunkTracker().isChunkLoaded(blockPos)) {
                // Eh just assume it's good for now
                return true;
            }
            BlockPos below = blockPos.below();
            boolean canStand = WorldHelper.isSolid(mod, below) && !mod.getBlockTracker().blockIsValid(below, Blocks.NETHER_PORTAL);
            return canStand && goodPortal.test(blockPos);
        };
    }

    // the cell this task would walk to, nearest to `from`: a tracked cell that is standable as it is, or the bottom of the
    // live portal column a tracked cell sits in. empty means this task would go build a portal. PortalPhase asks this too,
    // so "a portal exists" there is never a portal we can't get our feet into here. no bans on this one: a portal whose
    // bottom we gave up on still means "hand out the enter task", and that's the one that skips it and builds a new one.
    // the callers handing out a builder instead got a builder that's already done and stood there till the watchdog
    public static Optional<BlockPos> usablePortal(AltoClef mod, Predicate<BlockPos> goodPortal, Vec3 from) {
        return usablePortal(mod, goodPortal, from, p -> false);
    }

    private static Optional<BlockPos> usablePortal(AltoClef mod, Predicate<BlockPos> goodPortal, Vec3 from, Predicate<BlockPos> banned) {
        if (!mod.getBlockTracker().isTracking(Blocks.NETHER_PORTAL)) {
            return Optional.empty();
        }
        return pickUsable(mod.getBlockTracker().getKnownLocations(Blocks.NETHER_PORTAL), portalAt(mod),
                standablePortal(mod, goodPortal), banned, from);
    }

    // usablePortal without the game. a bottom cell GetToBlockTask gave up on is banned, and skipping it here is what
    // lets this task go build a portal instead of walking at the same wall forever (the tracker's getNearest skips them too)
    static Optional<BlockPos> pickUsable(List<BlockPos> cells, Predicate<BlockPos> portalAt, Predicate<BlockPos> standable,
                                         Predicate<BlockPos> banned, Vec3 from) {
        BlockPos best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (BlockPos cell : cells) {
            BlockPos feet = cell;
            if (!standable.test(cell)) {
                // only a live portal has a bottom worth walking down to, a stale entry is just stale
                if (!portalAt.test(cell)) {
                    continue;
                }
                feet = feetCell(cell, portalAt);
                if (!standable.test(feet)) {
                    continue;
                }
            }
            if (banned.test(feet)) {
                continue;
            }
            double d = feet.distToCenterSqr(from);
            if (d < bestDist) {
                bestDist = d;
                best = feet;
            }
        }
        return Optional.ofNullable(best);
    }

    // feet up on the frame: standing on the obsidian above a 3 tall interior, and close enough to the portal that this is the frame and not
    // a hill. the frame top is 4 above the ground next to it and baritone refuses a drop that big, so a bot that got up
    // there can be unable to path back down (the pick is fine, the goal is simply unreachable from up here)
    public static boolean onTopOfFrame(Vec3 feet, BlockPos feetCell) {
        double dx = feet.x - (feetCell.getX() + 0.5);
        double dz = feet.z - (feetCell.getZ() + 0.5);
        return feet.y >= feetCell.getY() + 4 && dx * dx + dz * dz <= 3.5 * 3.5;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.NETHER_PORTAL);
        _leftPortal = false;
        _feetTarget = null;
        _onFrameTimer.reset();
        _portalTimeout.reset();
        _wanderTask.resetWander();
    }

    @Override
    protected Task onTick(AltoClef mod) {

        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            setDebugState("Exiting portal for a bit.");
            _portalTimeout.reset();
            _leftPortal = true;
            return _wanderTask;
        }

        if (mod.getWorld().getBlockState(mod.getPlayer().blockPosition()).getBlock() == Blocks.NETHER_PORTAL) {

            if (_portalTimeout.elapsed() && !_leftPortal) {
                return _wanderTask;
            }
            setDebugState("Waiting inside portal", "Standing in the portal");
            releaseFallHeight(mod);
            mod.getClientBaritone().getExploreProcess().onLostControl();
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getMineProcess().onLostControl();
            mod.getClientBaritone().getFarmProcess().onLostControl();
            mod.getClientBaritone().getGetToBlockProcess();
            mod.getClientBaritone().getBuilderProcess();
            mod.getClientBaritone().getFollowProcess();
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.MOVE_BACK);
            mod.getInputControls().release(Input.MOVE_FORWARD);
            return null;
        } else {
            _portalTimeout.reset();
        }

        Predicate<BlockPos> portalAt = portalAt(mod);
        // a banned cell stays in the tracker's list, and DoToClosestBlockTask's pick skips it, so without the ban check a
        // walled off bottom cell had us wandering after nothing instead of falling through to the build
        Predicate<BlockPos> standableAsIs = standablePortal(mod, _goodPortal);
        Predicate<BlockPos> standablePortal = p -> standableAsIs.test(p) && !mod.getBlockTracker().unreachable(p);
        Vec3 feet = mod.getPlayer().position();
        boolean trackedStandable = mod.getBlockTracker().anyFound(standablePortal, Blocks.NETHER_PORTAL);

        // the tracker knows portal but only cells up in the air: walk to the bottom of that column instead of building
        // a second portal next to the first one. same pick usablePortal makes, that's the one PortalPhase trusts
        BlockPos feetTarget = null;
        if (!trackedStandable) {
            if (_feetTarget != null && portalAt.test(_feetTarget) && standablePortal.test(_feetTarget)) {
                feetTarget = _feetTarget;
            } else {
                feetTarget = usablePortal(mod, _goodPortal, feet, mod.getBlockTracker()::unreachable).orElse(null);
            }
        }
        _feetTarget = feetTarget;

        Optional<BlockPos> frameCell = trackedStandable
                ? mod.getBlockTracker().getNearestTracking(feet, standablePortal, Blocks.NETHER_PORTAL)
                : Optional.ofNullable(feetTarget);
        watchFrame(mod, frameCell);

        if (trackedStandable) {
            setDebugState("Going to found portal");
            return new DoToClosestBlockTask(blockPos -> new GetToBlockTask(blockPos, false), standablePortal, Blocks.NETHER_PORTAL);
        }
        if (feetTarget != null) {
            setDebugState("Going to the bottom of a known portal");
            return new GetToBlockTask(feetTarget, false);
        }
        setDebugState("Making new nether portal.");
        if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD) {
            return new ConstructNetherPortalBucketTask();
        }
        return _getPortalTask != null ? _getPortalTask : new ConstructNetherPortalObsidianTask();
    }

    // the bot can end up on the frame top and baritone will not drop 4 blocks on its own. after a few seconds up there the
    // limit goes up to 5 for as long as we are up there (1 damage), so the path back down to the bottom cell exists
    private void watchFrame(AltoClef mod, Optional<BlockPos> cell) {
        Vec3 feet = mod.getPlayer().position();
        if (!mod.getPlayer().onGround()) {
            // mid drop is no time to take the limit away from the path that is doing the dropping
            return;
        }
        if (cell.isEmpty() || !onTopOfFrame(feet, cell.get())) {
            _onFrameTimer.reset();
            releaseFallHeight(mod);
            return;
        }
        var fall = mod.getClientBaritoneSettings().maxFallHeightNoWater;
        if (_onFrameTimer.elapsed() && _userFallHeight == null && fall.value < FRAME_DROP_FALL_HEIGHT) {
            _userFallHeight = fall.value;
            SettingsOverrides.put(fall, FRAME_DROP_FALL_HEIGHT);
            // the debug state would be overwritten by the pick further down this same tick
            Debug.logMessage("Stuck on the portal frame, allowing a bigger drop.");
        }
    }

    // only hands it back if the hold is still ours, same idea as PortalPhase does with async smelting
    private void releaseFallHeight(AltoClef mod) {
        if (_userFallHeight == null) {
            return;
        }
        var fall = mod.getClientBaritoneSettings().maxFallHeightNoWater;
        if (SettingsOverrides.isHeld(fall) && fall.value == FRAME_DROP_FALL_HEIGHT) {
            SettingsOverrides.put(fall, _userFallHeight);
        }
        _userFallHeight = null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        releaseFallHeight(mod);
        mod.getBlockTracker().stopTracking(Blocks.NETHER_PORTAL);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return WorldHelper.getCurrentDimension() == _targetDimension;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof EnterNetherPortalTask task) {
            return (Objects.equals(task._getPortalTask, _getPortalTask) && Objects.equals(task._targetDimension, _targetDimension));
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Entering nether portal";
    }

    @Override
    protected String toHudString() {
        return "Going through a Nether portal";
    }
}
