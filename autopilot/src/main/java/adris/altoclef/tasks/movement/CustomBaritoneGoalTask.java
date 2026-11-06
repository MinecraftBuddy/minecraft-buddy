package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.util.baritone.SnapshotGoal;
import adris.altoclef.util.helpers.AnnoyingBlocks;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.PortalExit;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerBlock;

/**
 * Turns a baritone goal into a task.
 */
public abstract class CustomBaritoneGoalTask extends Task implements ITaskRequiresGrounded {
    private final Task _wanderTask = new TimeoutWanderTask(5, true);
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final boolean _wander;
    protected MovementProgressChecker _checker = new MovementProgressChecker();
    protected Goal _cachedGoal = null;
    private Task _unstuckTask = null;

    // This happens all the time in mineshafts and swamps/jungles

    public CustomBaritoneGoalTask(boolean wander) {
        _wander = wander;
    }

    public CustomBaritoneGoalTask() {
        this(true);
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

    // (the buddy's fork: an annoying block counts only when the body touches it: its collision shape, or for one without (vines,
    // cobwebs) its cell, meets the player's box. beside a fence is not stuck in it: on a fortress platform every walk past the
    // rim's fence counted as stuck, and the shimmy that followed broke the fence and walked off into the lava, or stood in the
    // blazes' fire, five deaths in the blaze drills of 4 Nov 2026)
    private boolean isAnnoying(AltoClef mod, BlockPos pos) {
        var level = mod.getWorld();
        var state = level.getBlockState(pos);
        if (!AnnoyingBlocks.isAnnoying(state.getBlock())) return false;
        net.minecraft.world.phys.AABB body = mod.getPlayer().getBoundingBox().inflate(0.05);
        var shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) return body.intersects(new net.minecraft.world.phys.AABB(pos));
        for (net.minecraft.world.phys.AABB part : shape.toAabbs()) {
            if (body.intersects(part.move(pos))) return true;
        }
        return false;
    }

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

    // with our feet in a cell that has a collision shape (a fence, a wall), the nearest of the eight beside it with room for the
    // body, a floor and no fire or lava; null when the feet's cell is open or none is
    private static BlockPos openBeside(AltoClef mod) {
        var level = mod.getWorld();
        BlockPos feet = mod.getPlayer().blockPosition();
        if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) return null;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos c = feet.offset(dx, 0, dz);
                var at = level.getBlockState(c);
                var head = level.getBlockState(c.above());
                var floor = level.getBlockState(c.below());
                if (!at.getCollisionShape(level, c).isEmpty() || !head.getCollisionShape(level, c.above()).isEmpty()) continue;
                if (at.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock || !at.getFluidState().isEmpty()) continue;
                if (floor.getCollisionShape(level, c.below()).isEmpty() || !floor.getFluidState().isEmpty()) continue;
                double d = mod.getPlayer().position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(c));
                if (d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
        }
        return best;
    }

    private Task getFenceUnstuckTask() {
        return new SafeRandomShimmyTask();
    }

    @Override
    protected void onStart(AltoClef mod) {
        cancelPath(mod);
        _checker.reset();
        stuckCheck.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // goals that snapshot entities get their once-a-tick refresh here, on the main thread
        if (_cachedGoal instanceof SnapshotGoal snapshot) {
            snapshot.refresh();
        }
        // (the buddy's fork: our feet in a cell that blocks, the edge of a fortress rim's fence: the pathfinder starts in that cell
        // and finds no way out of it, so first a step to the middle of the nearest open cell beside it. the blaze fight's run
        // stood so in the fire 59 ticks and more, twice in one drill block, corner19 runs 1 and 3, 4 Nov 2026)
        if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
            BlockPos open = openBeside(mod);
            if (open != null) {
                setDebugState("Out of the fence's cell first");
                adris.altoclef.util.helpers.LookHelper.lookAt(mod, new net.minecraft.world.phys.Vec3(open.getX() + 0.5, mod.getPlayer().getEyeY(), open.getZ() + 0.5));
                mod.getInputControls().tryPress(Input.MOVE_FORWARD);
                return null;
            }
        }
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _checker.reset();
        }
        if (WorldHelper.isInNetherPortal(mod)) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                setDebugState("Getting out from nether portal");
                PortalExit.step(mod);
                return null;
            }
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.MOVE_BACK);
            mod.getInputControls().release(Input.MOVE_FORWARD);
        }
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.MOVE_BACK);
            mod.getInputControls().release(Input.MOVE_FORWARD);
        }
        if (_unstuckTask != null && _unstuckTask.isActive() && !_unstuckTask.isFinished(mod) && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Stop other tasks, we are JUST shimmying
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return _unstuckTask;
        }
        if (!_checker.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                _unstuckTask = getFenceUnstuckTask();
                return _unstuckTask;
            }
            stuckCheck.reset();
        }
        if (_cachedGoal == null) {
            _cachedGoal = makeGoal(mod);
        }

        if (_wander) {
            if (isFinished(mod)) {
                // Don't wander if we've reached our goal.
                _checker.reset();
            } else {
                if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
                    setDebugState("Wandering...");
                    _checker.reset();
                    return _wanderTask;
                }
                if (!_checker.check(mod)) {
                    Debug.logMessage("Failed to make progress on goal, wandering.");
                    onWander(mod);
                    return _wanderTask;
                }
            }
        }
        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()
                && mod.getClientBaritone().getPathingBehavior().isSafeToCancel() && mayIssueGoal(mod)) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(_cachedGoal);
        }
        setDebugState("Completing goal.");
        return null;
    }

    // asked on the ticks the goal is about to be handed to baritone again (it is not active: it was reached, or the search
    // came back empty and baritone dropped it). yes is the default, a task whose goal can be out of reach says no for a bit
    protected boolean mayIssueGoal(AltoClef mod) {
        return true;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (_cachedGoal == null) {
            _cachedGoal = makeGoal(mod);
        }
        return _cachedGoal != null && _cachedGoal.isInGoal(mod.getPlayer().blockPosition());
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        cancelPath(mod);
    }

    // how the path gets dropped when this task starts and stops. another chain taking over (a fight, a fall, food) is a real
    // stop and the path is not ours to keep. anything else is a handover: if the next task walks to the same goal,
    // PathingBehavior keeps the path (keepPathOnSameGoal). CommittedRunTask overrides this to stay soft in the air
    protected void cancelPath(AltoClef mod) {
        if (isInterrupting()) {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        } else {
            mod.getClientBaritone().getPathingBehavior().handoverCancel();
        }
    }

    // fresh goals have never seen the world, so give them their first look right away
    private Goal makeGoal(AltoClef mod) {
        Goal goal = newGoal(mod);
        if (goal instanceof SnapshotGoal snapshot) {
            snapshot.refresh();
        }
        return goal;
    }

    protected abstract Goal newGoal(AltoClef mod);

    protected void onWander(AltoClef mod) {
    }
}
