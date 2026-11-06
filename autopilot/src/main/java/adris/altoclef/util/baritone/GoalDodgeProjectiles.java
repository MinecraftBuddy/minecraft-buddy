package adris.altoclef.util.baritone;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.helpers.ProjectileHelper;
import baritone.api.pathing.goals.Goal;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.phys.Vec3;

public class GoalDodgeProjectiles implements Goal, SnapshotGoal {

    private static final Shot[] NO_SHOTS = new Shot[0];

    private final AltoClef _mod;

    private final double _distanceHorizontal;
    private final double _distanceVertical;

    // the pathfinder only ever reads this array. refresh builds a new one and swaps it in, so no lock per node and a
    // search never sees a half built list. a tick stale mid search is fine, baritone replans constantly
    private volatile Shot[] _shots = NO_SHOTS;

    public GoalDodgeProjectiles(AltoClef mod, double distanceHorizontal, double distanceVertical) {
        _mod = mod;
        _distanceHorizontal = distanceHorizontal;
        _distanceVertical = distanceVertical;
    }

    // this used to hold the minecraft lock for the whole projectile loop on every single node. now it takes it once
    // a tick to copy the arrows and the nodes are lock free
    @Override
    public void refresh() {
        List<CachedProjectile> projectiles = _mod.getEntityTracker().getProjectiles();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            if (projectiles.isEmpty()) {
                _shots = NO_SHOTS;
                return;
            }
            Shot[] shots = new Shot[projectiles.size()];
            int count = 0;
            for (CachedProjectile projectile : projectiles) {
                if (projectile == null || projectile.position == null || projectile.velocity == null) continue;
                shots[count++] = new Shot(projectile.position, projectile.velocity, projectile.gravity);
            }
            _shots = count == shots.length ? shots : Arrays.copyOf(shots, count);
        }
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        Shot[] shots = _shots;
        if (shots.length == 0) return true;
        Vec3 p = new Vec3(x, y, z);
        for (Shot shot : shots) {
            if (isHitCloseEnough(closestApproach(shot, p), p)) return false;
        }
        return true;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        Shot[] shots = _shots;
        if (shots.length == 0) return 0;
        Vec3 p = new Vec3(x, y, z);
        // The HIGHER the cost, the better (total distance from arrows)
        double costFactor = 0;
        for (Shot shot : shots) {
            if (isHitCloseEnough(closestApproach(shot, p), p)) {
                costFactor += ProjectileHelper.getFlatDistanceSqr(shot.position.x, shot.position.z, shot.velocity.x, shot.velocity.z, p.x, p.z);
            }
        }
        return -1 * costFactor;
    }

    // used to be cached on the projectile for two seconds, but the cached answer belonged to whatever node asked first
    // and every other node reused it. the math is a few multiplies, so it's just exact per node now
    private static Vec3 closestApproach(Shot shot, Vec3 p) {
        return ProjectileHelper.calculateArrowClosestApproach(shot.position, shot.velocity, shot.gravity, p);
    }

    private boolean isHitCloseEnough(Vec3 hit, Vec3 to) {
        Vec3 delta = to.subtract(hit);
        double horizontalSquared = delta.x * delta.x + delta.z * delta.z;
        double vertical = Math.abs(delta.y);
        return horizontalSquared < _distanceHorizontal * _distanceHorizontal && vertical < _distanceVertical;
    }

    // an arrow frozen mid flight. Vec3 is immutable so sharing them with the pathfinder is safe
    private record Shot(Vec3 position, Vec3 velocity, double gravity) {
    }
}
