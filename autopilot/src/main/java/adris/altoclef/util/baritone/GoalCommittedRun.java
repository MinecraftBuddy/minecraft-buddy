package adris.altoclef.util.baritone;

import adris.altoclef.util.helpers.CrowdRepulsion;
import adris.altoclef.util.helpers.LavaShore;
import baritone.pathing.movement.CliffAvoidance;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;

// the goal of a run the combat commitment owns: far from where it started AND clear of whoever is following. the plain
// crowd goal is done the moment everyone is a dozen blocks back, which is how a run turns into a jog. this one asks for
// the 24 as well, and CombatCommit still decides when the run is really over (it also wants two quiet seconds)
public class GoalCommittedRun extends GoalRunAwayFromCrowd {

    private final double _originX;
    private final double _originZ;
    private final double _farDistance;
    private final Supplier<List<Entity>> _crowd;

    // null maker = no world to ask, nothing is rejected (the tests, mostly). the cells are a chunk copy for the pathfinder
    // thread, so refresh swaps in a fresh one now and then or a long run would judge new ground by an old snapshot
    private static final int CELLS_EVERY = 20;
    private final Supplier<Cells> _cellsMaker;
    private volatile Cells _cells;
    // the nether: a run that ends a knock from a drop hands the next fireball a fall into the sea (see CliffAvoidance)
    private final boolean _cliffs;
    private int _refreshes;

    public GoalCommittedRun(double originX, double originZ, double farDistance, double crowdDistance, Supplier<List<Entity>> crowd) {
        this(originX, originZ, farDistance, crowdDistance, crowd, null);
    }

    public GoalCommittedRun(double originX, double originZ, double farDistance, double crowdDistance, Supplier<List<Entity>> crowd,
            Supplier<Cells> cellsMaker) {
        this(originX, originZ, farDistance, crowdDistance, crowd, cellsMaker, false);
    }

    public GoalCommittedRun(double originX, double originZ, double farDistance, double crowdDistance, Supplier<List<Entity>> crowd,
            Supplier<Cells> cellsMaker, boolean cliffs) {
        super(crowdDistance);
        _cliffs = cliffs;
        _originX = originX;
        _originZ = originZ;
        _farDistance = farDistance;
        _crowd = crowd;
        _cellsMaker = cellsMaker;
        _cells = cellsMaker == null ? null : cellsMaker.get();
    }

    @Override
    public void refresh() {
        super.refresh();
        if (_cellsMaker != null && ++_refreshes % CELLS_EVERY == 0) _cells = _cellsMaker.get();
    }

    // a run that ends with lava at our feet or in the eight cells around them just hands the next knock a swim, so those
    // cells are never the end (feet level and floor level, both dimensions)
    @Override
    public boolean isInGoal(int x, int y, int z) {
        return CrowdRepulsion.farFromOrigin(_originX, _originZ, _farDistance, x + 0.5, z + 0.5) && super.isInGoal(x, y, z)
                && !lavaAround(x, y, z) && !onTheRim(x, y, z);
    }

    private boolean onTheRim(int x, int y, int z) {
        Cells cells = _cells;
        return _cliffs && cells != null && CliffAvoidance.dropNear(cells, x, y, z);
    }

    private boolean lavaAround(int x, int y, int z) {
        Cells cells = _cells;
        return cells != null && LavaShore.lavaNear(cells, x + 0.5, y, z + 0.5, 1);
    }

    // both shortfalls are in ticks and both are zero exactly where isInGoal says yes, so the search is pulled the same way
    // by "too close to the crowd" and "too close to where we started"
    @Override
    public double heuristic(int x, int y, int z) {
        return super.heuristic(x, y, z) + CrowdRepulsion.originHeuristic(_originX, _originZ, _farDistance, x + 0.5, z + 0.5);
    }

    @Override
    protected List<Entity> getCrowd() {
        return _crowd.get();
    }
}
