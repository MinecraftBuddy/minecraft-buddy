package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalCommittedRun;
import adris.altoclef.util.baritone.SnapshotGoal;
import adris.altoclef.util.helpers.CombatCommit;
import adris.altoclef.util.helpers.CombatRules;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.tasks.construction.PlaceStationTask;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.Dimension;
import baritone.api.pathing.goals.GoalBlock;
import baritone.pathing.movement.CliffAvoidance;
import baritone.utils.BlockStateInterface;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

// the run CombatCommit asked for. it never finishes on its own: the commitment knows when 24 blocks and two quiet seconds
// have happened, a task that decides for itself is how a run ends early and the wheel goes back for a tick. stands still once it
// is at its goal (that is when eating is allowed) and walks again when something comes back within reach
public class CommittedRunTask extends CustomBaritoneGoalTask {

    // a bit more than the furthest line the commitment holds a run for (17 against a bow's 15), so a mob sitting right on it does
    // not make the goal flicker. any closer and we'd stand at the goal in bow range waiting for an end that isn't coming
    private static final double CROWD_CLEAR = CombatRules.SHOOTER_RANGE + 2;

    // how long the pathfinder is left alone after a goal was handed to it and it did not stick. a goal nothing can reach (a lava
    // lake in the way, the edge of the loaded world) was asked for again on the very next tick, a full search every tick
    // for as long as the run lasted. the commitment ends such a run by itself (CombatCommit.RUN_STUCK), this just stops the
    // pathfinder thread burning until then
    static final long REISSUE_GAP_TICKS = 40;

    private final double _originX;
    private final double _originZ;
    // how far from the origin the run has to get: RUN_DISTANCE, more for a run that goes on from the last (CombatCommit.RERUN_TICKS)
    private final double _distance;
    private final Supplier<List<Entity>> _crowd;
    private long _lastIssue = Long.MIN_VALUE / 2;
    // the goal in hand is the swim to the bank, not the run (see pickGoal)
    private boolean _toShore;

    public CommittedRunTask(double originX, double originZ, Supplier<List<Entity>> crowd) {
        this(originX, originZ, CombatCommit.RUN_DISTANCE, crowd);
    }

    public CommittedRunTask(double originX, double originZ, double distance, Supplier<List<Entity>> crowd) {
        // no wander: standing at the goal waiting out the quiet seconds is not "failed to make progress"
        super(false);
        _originX = originX;
        _originZ = originZ;
        _distance = distance;
        _crowd = crowd;
    }

    // the run the commitment asks for now is this one
    public boolean sameRun(double originX, double originZ, double distance) {
        return _originX == originX && _originZ == originZ && _distance == distance;
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        // run NOW, not after the user's path finishes its segment
        cancelPath(mod);
        return runGoal(mod);
    }

    protected Goal runGoal(AltoClef mod) {
        // the goal is asked from the pathfinder thread, so it gets thread safe copies of the loaded chunks (remade by refresh)
        return new GoalCommittedRun(_originX, _originZ, _distance, CROWD_CLEAR, _crowd,
                () -> CliffAvoidance.of(new BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true)),
                WorldHelper.getCurrentDimension() == Dimension.NETHER);
    }

    // ...unless we're in the air. a hard cancel mid fall drops the MovementFall doing the bucket or the clutch, and the mlg
    // chain grabs the fall halfway down with worse odds. the soft one still stops the processes, the fall movement finishes
    // (it is not safe to cancel), and the goal goes in once it is (the goal task waits for isSafeToCancel anyway)
    @Override
    protected void cancelPath(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (mayCancelHard(player.onGround(), player.isInWater(), player.onClimbable())) {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        } else {
            mod.getClientBaritone().getPathingBehavior().cancelEverything();
        }
    }

    // in the water with nothing under us (wading on the bottom walks out on its own)
    static boolean afloat(boolean inWater, boolean onGround) {
        return inWater && !onGround;
    }

    // standing on something, swimming or on a ladder: nothing to fall out of
    static boolean mayCancelHard(boolean onGround, boolean inWater, boolean onClimbable) {
        return onGround || inWater || onClimbable;
    }

    @Override
    protected void onStart(AltoClef mod) {
        super.onStart(mod);
        // an interrupt (a fall, a lava escape) restarts this same object and cancels baritone's path: the gap belongs to the
        // path that just got cancelled, so the first ask afterwards always goes through
        _lastIssue = Long.MIN_VALUE / 2;
        // (a swim to the bank cut short: the cached goal is that bank, drop it so the run goal comes back)
        if (_toShore) _cachedGoal = null;
        _toShore = false;
    }

    @Override
    protected boolean mayIssueGoal(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        if (!mayReissue(now, _lastIssue, reissueGap())) return false;
        _lastIssue = now;
        pickGoal(mod);
        return true;
    }

    // the goal is cached for the whole task, so the bank is swapped in here, on the reissue ticks. afloat: 24 blocks of open ocean is
    // the same drowned a bit further off and they swim faster than we do, so the bank is the way out (they follow up the beach,
    // but then it is a mob on land and the usual rules are back). back on our feet the run goal comes back
    private void pickGoal(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (afloat(player.isInWater(), player.onGround())) {
            java.util.Optional<BlockPos> shore = PlaceStationTask.shoreNear(mod);
            if (shore.isPresent()) {
                _cachedGoal = new GoalBlock(shore.get());
                _toShore = true;
            }
        } else if (_toShore) {
            _toShore = false;
            Goal run = runGoal(mod);
            if (run instanceof SnapshotGoal snapshot) snapshot.refresh();
            _cachedGoal = run;
        }
    }

    // the very first issue goes through (nothing was handed over yet), after that one per gap. a clock that went backwards
    // (a new world) counts as long enough ago
    static boolean mayReissue(long now, long lastIssue) {
        return mayReissue(now, lastIssue, REISSUE_GAP_TICKS);
    }

    static boolean mayReissue(long now, long lastIssue, long gap) {
        return now < lastIssue || now - lastIssue >= gap;
    }

    // the ticks between two goals handed to the pathfinder
    protected long reissueGap() {
        return REISSUE_GAP_TICKS;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof CommittedRunTask task && task.sameRun(_originX, _originZ, _distance);
    }

    @Override
    protected String toHudString() {
        return "Running from monsters";
    }

    @Override
    protected String toDebugString() {
        return "Running " + (int) _distance + " blocks from " + Math.round(_originX) + ", " + Math.round(_originZ);
    }
}
