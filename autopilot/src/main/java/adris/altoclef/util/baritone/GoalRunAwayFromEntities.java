package adris.altoclef.util.baritone;

import adris.altoclef.AltoClef;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import java.util.Optional;
import net.minecraft.world.entity.Entity;

public abstract class GoalRunAwayFromEntities implements Goal, SnapshotGoal {

    private final AltoClef _mod;
    private final double _distance;
    private final boolean _xzOnly;

    // Higher: We will move more directly away from each entity
    // Too high: We will refuse to take alternative, faster paths and will dig straight away.
    // Lower: We will in general move far away from an entity, allowing the ocassional closer traversal.
    // Too low: We will just run straight into the entity to go past it.
    private final double _penaltyFactor;

    // what the pathfinder sees. refresh swaps the whole record in one write, so a search never sees half an update.
    // it can be a tick stale mid search, which is fine, baritone replans all the time anyway. same goal object the
    // whole time, so nothing sees a "new" goal and decides to replan
    private volatile Target _target = null;

    public GoalRunAwayFromEntities(AltoClef mod, double distance, boolean xzOnly, double penaltyFactor) {
        _mod = mod;
        _distance = distance;
        _xzOnly = xzOnly;
        _penaltyFactor = penaltyFactor;
    }

    // asking the tracker per node was 2 lock grabs and a pile of list scans each, at 10^5 nodes a second, fighting
    // the main thread for the same lock. now it's once a tick and the nodes just do arithmetic
    @Override
    public void refresh() {
        Optional<Entity> entity = getEntities(_mod);
        if (entity.isPresent() && entity.get().isAlive()) {
            Entity e = entity.get();
            _target = new Target(e.getX(), e.getY(), e.getZ(), e.getBlockX(), e.getBlockY(), e.getBlockZ());
        } else {
            _target = null;
        }
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        Target t = _target;
        // nothing to run from (or it died) means we're already as safe as we're getting
        if (t == null) return true;
        double dx = t.x - x;
        double dz = t.z - z;
        double sqDistance = dx * dx + dz * dz;
        if (!_xzOnly) {
            double dy = t.y - y;
            sqDistance += dy * dy;
        }
        return !(sqDistance < _distance * _distance);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        Target t = _target;
        if (t == null) return 0;
        // The lower the cost, the better.
        double cost = getCostOfEntity(t, x, y, z);
        double costSum;
        if (cost != 0) {
            // We want the CLOSER entities to have a bigger weight than the further ones.
            costSum = 1 / cost;
        } else {
            // Bad >:(
            costSum = 1000;
        }
        return costSum * _penaltyFactor;
    }

    // called on the main thread from refresh, so it's allowed to talk to the tracker
    protected abstract Optional<Entity> getEntities(AltoClef mod);

    // Virtual
    protected double getCostOfEntity(Target entity, int x, int y, int z) {
        double heuristic = 0;
        if (!_xzOnly) {
            heuristic += GoalYLevel.calculate(entity.by, y);
        }
        heuristic += GoalXZ.calculate(entity.bx - x, entity.bz - z);
        return heuristic; //entity.squaredDistanceTo(x, y, z);
    }

    // an entity frozen in time: exact position and block position
    protected record Target(double x, double y, double z, int bx, int by, int bz) {
    }
}
