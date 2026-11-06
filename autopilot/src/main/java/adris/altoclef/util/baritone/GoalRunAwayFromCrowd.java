package adris.altoclef.util.baritone;

import adris.altoclef.util.helpers.CrowdRepulsion;
import adris.altoclef.util.helpers.CrowdRepulsion.Crowd;
import baritone.api.pathing.goals.Goal;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;

// run from everything at once. GoalRunAwayFromEntities only ever looked at one mob (the closest of the first hostile's
// class), which is fine for a lone zombie and is how you run straight into the other seven
public abstract class GoalRunAwayFromCrowd implements Goal, SnapshotGoal {

    private final double _distance;

    // same deal as the single entity goal: one volatile swap per tick, the search never sees half an update
    private volatile Crowd _crowd = CrowdRepulsion.EMPTY;

    public GoalRunAwayFromCrowd(double distance) {
        _distance = distance;
    }

    // main thread, once a tick. dead ones are dropped here so the search does not run from corpses
    @Override
    public void refresh() {
        List<Entity> entities = getCrowd();
        int n = 0;
        double[] x = new double[entities.size()], y = new double[entities.size()], z = new double[entities.size()];
        double[] w = new double[entities.size()];
        for (Entity e : entities) {
            if (!e.isAlive()) continue;
            x[n] = e.getX();
            y[n] = e.getY();
            z[n] = e.getZ();
            w[n] = e instanceof Creeper ? CrowdRepulsion.CREEPER_WEIGHT : 1;
            n++;
        }
        _crowd = n == 0 ? CrowdRepulsion.EMPTY : new Crowd(Arrays.copyOf(x, n), Arrays.copyOf(y, n),
                Arrays.copyOf(z, n), Arrays.copyOf(w, n));
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return CrowdRepulsion.inGoal(_crowd, _distance, false, x, y, z);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return CrowdRepulsion.heuristic(_crowd, _distance, false, x, y, z);
    }

    // who we are running from, asked on the main thread from refresh
    protected abstract List<Entity> getCrowd();
}
