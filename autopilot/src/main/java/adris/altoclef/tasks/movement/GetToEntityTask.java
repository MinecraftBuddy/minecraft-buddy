package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.helpers.AnnoyingBlocks;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.util.baritone.GoalFollowEntity;
import adris.altoclef.util.helpers.PortalExit;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.ui.HudText;
import net.minecraft.world.entity.player.Player;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerBlock;

public class GetToEntityTask extends Task implements ITaskRequiresGrounded {
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final MovementProgressChecker _progress = new MovementProgressChecker();
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5);
    private final CalcFailureWatch _calcWatch = new CalcFailureWatch();
    private final ChaseStallWatch _chase = new ChaseStallWatch();
    private final Entity _entity;
    private final double _closeEnoughDistance;
    private Task _unstuckTask = null;
    private int _misses = 0;
    // who hears about a dead search instead of the global ban. null = the stock ban
    private final Runnable _onNoPath;

    public GetToEntityTask(Entity entity, double closeEnoughDistance) {
        this(entity, closeEnoughDistance, null);
    }

    // for a caller that has its own idea of what "no way to it" means (the death recovery keeps its own book, a global ban hides
    // the pile's drops from everything else for two minutes)
    public GetToEntityTask(Entity entity, double closeEnoughDistance, Runnable onNoPath) {
        _entity = entity;
        _closeEnoughDistance = closeEnoughDistance;
        _onNoPath = onNoPath;
    }

    public GetToEntityTask(Entity entity) {
        this(entity, 1);
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

    @Override
    protected void onStart(AltoClef mod) {
        // same handover as CustomBaritoneGoalTask, GoalFollowEntity has an equals for it
        mod.getClientBaritone().getPathingBehavior().handoverCancel();
        _progress.reset();
        stuckCheck.reset();
        _wanderTask.resetWander();
        _calcWatch.disarm();
        _misses = 0;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _progress.reset();
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
        // the checks below all stand down while baritone is pathing (and the shimmy and the wander own the bot), and a swimmer
        // in a flooded pocket replans forever. not going anywhere for 45 s is the same verdict as no path
        if (mod.getPlayer().closerThan(_entity, _closeEnoughDistance)) {
            _chase.reset();
        } else if (!(_entity instanceof Player) && _chase.stalled(WorldHelper.getTicks(), mod.getPlayer().getX(), mod.getPlayer().getY(), mod.getPlayer().getZ())) {
            Debug.logMessage("Not getting anywhere chasing " + _entity.getType().getDescriptionId() + ", dropping it.");
            _chase.reset();
            if (_onNoPath != null) {
                _onNoPath.run();
            } else {
                BanPolicy.chaseStalled(mod.getBans(), _entity.getId(), EntityTracker.describe(_entity));
            }
            // the ban stops us at the reachable check below, a callback has to be taken at its word
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            setDebugState("No way to get to the target");
            return null;
        }
        if (_unstuckTask != null && _unstuckTask.isActive() && !_unstuckTask.isFinished(mod) && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Stop other tasks, we are JUST shimmying
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            _calcWatch.disarm();
            return _unstuckTask;
        }
        if (!_progress.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                _unstuckTask = getFenceUnstuckTask();
                return _unstuckTask;
            }
            stuckCheck.reset();
        }
        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            _progress.reset();
            setDebugState("Failed to get to target, wandering for a bit.");
            return _wanderTask;
        }

        // a search that comes back empty ends the goal process, and the line below used to just start the same search
        // again. every one of those is seconds of standing still, so the second miss bans the mob and the hunter picks
        // another. players are never banned, a follower has to stay on someone who walks back across the river
        if (_calcWatch.failed(mod.getClientBaritone().getPathingBehavior().calcFailures()) && !(_entity instanceof Player)) {
            if (++_misses >= 2) {
                Debug.logMessage("No path to " + _entity.getType().getDescriptionId() + ", not chasing it.");
                if (_onNoPath != null) {
                    _onNoPath.run();
                    // the ban would have stopped us at the reachable check below, a callback has to do it itself
                    setDebugState("No way to get to the target");
                    return null;
                } else {
                    BanPolicy.noPath(mod.getBans(), _entity.getId(), EntityTracker.describe(_entity));
                }
            }
        }
        if (!mod.getEntityTracker().isEntityReachable(_entity)) {
            setDebugState("No way to get to the target");
            return null;
        }

        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            _calcWatch.arm(mod.getClientBaritone().getPathingBehavior().calcFailures());
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalFollowEntity(_entity, _closeEnoughDistance));
        }

        if (mod.getPlayer().closerThan(_entity, _closeEnoughDistance)) {
            _progress.reset();
        }

        if (!_progress.check(mod)) {
            return _wanderTask;
        }

        setDebugState("Going to entity");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        _calcWatch.disarm();
        if (isInterrupting()) {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        } else {
            mod.getClientBaritone().getPathingBehavior().handoverCancel();
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetToEntityTask task) {
            return task._entity.equals(_entity) && Math.abs(task._closeEnoughDistance - _closeEnoughDistance) < 0.1;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Approach entity " + _entity.getType().getDescriptionId();
    }

    @Override
    protected String toHudString() {
        // "Walking to Jacob" but "Walking to the Zombie" and "Walking to the Oak Log"
        String name = HudText.entity(_entity);
        return "Walking to " + (_entity instanceof Player ? name : "the " + name);
    }
}
