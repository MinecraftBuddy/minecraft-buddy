package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalFlee;
import adris.altoclef.util.baritone.GoalRunAwayFromCrowd;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.LavaShore;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import baritone.pathing.movement.CliffAvoidance;
import baritone.api.pathing.goals.Goal;
import baritone.utils.BlockStateInterface;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.skeleton.Skeleton;

public class RunAwayFromHostilesTask extends CustomBaritoneGoalTask {

    // the hostiles that count as "the crowd" when nobody hands us one. past this they are not who we are running from
    private static final double CROWD_RADIUS = 20;
    private static final int CROWD_MAX = 12;

    private final double _distanceToRun;
    private final boolean _includeSkeletons;
    // whoever is kiting knows the crowd better than a scan does (it already threw out the ones walking past)
    private final Supplier<List<Entity>> _crowd;

    public RunAwayFromHostilesTask(double distance, boolean includeSkeletons) {
        this(distance, includeSkeletons, null);
    }

    public RunAwayFromHostilesTask(double distance) {
        this(distance, false, null);
    }

    public RunAwayFromHostilesTask(double distance, boolean includeSkeletons, Supplier<List<Entity>> crowd) {
        _distanceToRun = distance;
        _includeSkeletons = includeSkeletons;
        _crowd = crowd;
    }

    // would this run be over before it started: everyone it would run from is already far enough (or nobody is there).
    // asked before installing one, because a run that is born finished holds the wheel for nothing. a probe goal on
    // purpose, newGoal cancels the path we are on and a question should not do that
    public boolean alreadySafe(AltoClef mod) {
        // (risk_flee: safe is where the flee search would end, a calm cell)
        if (GoalFlee.on()) {
            GoalFlee flee = FleeTask.goal(mod);
            flee.refresh();
            return flee.isInGoal(mod.getPlayer().blockPosition());
        }
        GoalRunAwayFromHostiles probe = new GoalRunAwayFromHostiles(mod, _distanceToRun);
        probe.refresh();
        return probe.isInGoal(mod.getPlayer().blockPosition());
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        // We want to run away NOW
        boolean nether = WorldHelper.getCurrentDimension() == Dimension.NETHER;
        if (forceCancels(nether, nether && onNetherEdge(mod))) {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        }
        // (the buddy's fork, S137 step 3, Tuning risk_flee: the run is the flee search, to the nearest calm cell by the field of
        // the mobs that want us, whoever the caller counted as the crowd; done there)
        if (GoalFlee.on()) return FleeTask.goal(mod);
        return new GoalRunAwayFromHostiles(mod, _distanceToRun);
    }

    // a cancel drops every key for the replan ticks, and that is exactly when a knock travels furthest. on a nether rim the
    // path we are on keeps walking until the new one is ready
    static boolean forceCancels(boolean nether, boolean edgeNear) {
        return !(nether && edgeNear);
    }

    private static boolean onNetherEdge(AltoClef mod) {
        var player = mod.getPlayer();
        return mod.getClientBaritone().bsi != null
                && LavaShore.edgeNear(CliffAvoidance.of(mod.getClientBaritone().bsi), player.getX(), player.getY(), player.getZ());
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof RunAwayFromHostilesTask task) {
            return Math.abs(task._distanceToRun - _distanceToRun) < 1 && (task._crowd == null) == (_crowd == null);
        }
        return false;
    }

    @Override
    protected String toHudString() {
        return "Running from monsters";
    }

    @Override
    protected String toDebugString() {
        return "Run away from monsters";
    }

    private class GoalRunAwayFromHostiles extends GoalRunAwayFromCrowd {

        private final AltoClef _mod;
        // the nether: a chunk copy for the pathfinder thread, so a cell a knock from a drop is never where the run ends. a run
        // from blazes went to the dead end of a fortress bridge and a fireball put us thirty blocks down in the sea (practice
        // run 119). the bridge's middle lane is still a goal, and a bridge with no such lane is no worse than before
        private final CliffAvoidance.Cells _cells;

        public GoalRunAwayFromHostiles(AltoClef mod, double distance) {
            super(distance);
            _mod = mod;
            _cells = WorldHelper.getCurrentDimension() == Dimension.NETHER
                    ? CliffAvoidance.of(new BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true)) : null;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return super.isInGoal(x, y, z) && (_cells == null || !CliffAvoidance.dropNear(_cells, x, y, z));
        }

        @Override
        protected List<Entity> getCrowd() {
            if (_crowd != null) return _crowd.get();
            List<Entity> hostiles = _mod.getEntityTracker().getHostiles();
            List<Entity> crowd = new ArrayList<>();
            try {
                for (Entity hostile : hostiles) {
                    if (!_includeSkeletons && hostile instanceof Skeleton) continue;
                    if (hostile.distanceTo(_mod.getPlayer()) > CROWD_RADIUS) continue;
                    if (!EntityHelper.isAngryAtPlayer(_mod, hostile)) continue;
                    crowd.add(hostile);
                    if (crowd.size() >= CROWD_MAX) break;
                }
            } catch (java.util.ConcurrentModificationException ignored) {
                // the tracker rebuilds on another thread sometimes, we get the crowd again next tick
            }
            if (!crowd.isEmpty()) return crowd;
            // nothing angry close by, which used to be run from "the closest of the first hostile's kind" anyway
            Optional<Entity> lone = Optional.empty();
            if (!hostiles.isEmpty()) {
                for (Entity hostile : hostiles) {
                    Optional<Entity> closest = _mod.getEntityTracker().getClosestEntity(hostile.getClass());
                    if (closest.isPresent()) {
                        if (_includeSkeletons || !(closest.get() instanceof Skeleton)) lone = closest;
                        break;
                    }
                }
            }
            return lone.map(List::of).orElseGet(List::of);
        }
    }
}
