package adris.altoclef.util.baritone;

import adris.altoclef.util.helpers.CrowdRepulsion;
import adris.altoclef.util.helpers.LavaShore;
import adris.altoclef.util.helpers.RiskField;
import adris.altoclef.util.helpers.Tuning;
import baritone.pathing.movement.CliffAvoidance;
import baritone.pathing.movement.CliffAvoidance.Cells;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;

// the goal of a run the combat commitment owns: far from where it started AND clear of whoever is following. the plain
// crowd goal is done the moment everyone is a dozen blocks back, which is how a run turns into a jog. this one asks for
// the 24 as well, and CombatCommit still decides when the run is really over (it also wants two quiet seconds)
// (Tuning run_break_line, off) under skeleton fire the search also prices a cell in a shooter's line, see Line
public class GoalCommittedRun extends GoalRunAwayFromCrowd implements SearchSnapshot {

    // (run_break_line) in the cave situation drill 9 of 18 deaths came in this run, 7 with arrows in the back. the price is a
    // heuristic term, a flat allowance per shooter in line (a node's h is set once, A* pops by g+h), so the run takes a way
    // out of the line if that way is at most run_break_line_cost ticks longer. isInGoal asks no line: it would flip with each
    // step a walking skeleton takes and the run would stop and go
    public static boolean lineOn() {
        return Tuning.get("run_break_line", 0) >= 1;
    }

    // ticks one shooter in line adds to a cell's estimate: about five blocks of walking
    public static final double LINE_COST = 20;
    // a skeleton's follow range reaches up to about 17.8: past 18 from its eye a cell is out of its reach
    static final double LINE_RANGE = 18;
    // the nearest shooters priced at most: the first search is time standing in the line, and each shooter is a ray a cell
    static final int LINE_SHOOTERS = 4;
    // sight lines one search may cast (RiskCost.MOB_RAYS' number); past it a cell within a shooter's range counts as in line
    static final int LINE_RAYS = 20000;
    // the buddy's eye over the feet, standing
    static final double EYE = 1.62;

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

    // (run_break_line) the skeleton kinds under fire, nearest first, asked on the main thread from refresh; null = none asked.
    // _fire: their eyes as the last refresh saw them, null with the key off or none about. a search takes _fire as it is at its
    // start (searchStarts), so every node of it is priced against one set of shooters and one chunk copy
    private final Supplier<? extends List<? extends Entity>> _shooters;
    private volatile Shooters _fire;
    private final ThreadLocal<Line> _line = new ThreadLocal<>();
    // a search ever bound a line: with the key off the heuristic never asks the thread local
    private volatile boolean _lineBound;

    public GoalCommittedRun(double originX, double originZ, double farDistance, double crowdDistance, Supplier<List<Entity>> crowd) {
        this(originX, originZ, farDistance, crowdDistance, crowd, null);
    }

    public GoalCommittedRun(double originX, double originZ, double farDistance, double crowdDistance, Supplier<List<Entity>> crowd,
            Supplier<Cells> cellsMaker) {
        this(originX, originZ, farDistance, crowdDistance, crowd, cellsMaker, false);
    }

    public GoalCommittedRun(double originX, double originZ, double farDistance, double crowdDistance, Supplier<List<Entity>> crowd,
            Supplier<Cells> cellsMaker, boolean cliffs) {
        this(originX, originZ, farDistance, crowdDistance, crowd, cellsMaker, cliffs, null);
    }

    public GoalCommittedRun(double originX, double originZ, double farDistance, double crowdDistance, Supplier<List<Entity>> crowd,
            Supplier<Cells> cellsMaker, boolean cliffs, Supplier<? extends List<? extends Entity>> shooters) {
        super(crowdDistance);
        _cliffs = cliffs;
        _originX = originX;
        _originZ = originZ;
        _farDistance = farDistance;
        _crowd = crowd;
        _cellsMaker = cellsMaker;
        _cells = cellsMaker == null ? null : cellsMaker.get();
        _shooters = shooters;
    }

    @Override
    public void refresh() {
        super.refresh();
        if (_cellsMaker != null && ++_refreshes % CELLS_EVERY == 0) _cells = _cellsMaker.get();
        // (run_break_line) the shooters are not even asked with the key off
        _fire = _shooters != null && lineOn() ? eyes(_shooters.get()) : null;
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
    // by "too close to the crowd" and "too close to where we started". (run_break_line) a search under skeleton fire adds the
    // line's price on top, a goal cell in a line included: one in line is that much worse an end than one out of it
    @Override
    public double heuristic(int x, int y, int z) {
        double h = super.heuristic(x, y, z) + CrowdRepulsion.originHeuristic(_originX, _originZ, _farDistance, x + 0.5, z + 0.5);
        if (!_lineBound) return h;
        Line line = _line.get();
        return line == null ? h : h + line.cost(x, y, z);
    }

    // (run_break_line) the search's thread, before its first node: the shooters and the cells as they are now, a fresh memo.
    // the hook is the search's start (AStarPathFinder.calculate0), not the goal's issue: the goal is kept for the whole run and
    // baritone searches again on its own (the next segment, a path gone bad), and each of those should see the shooters as
    // they are when it starts. no shooters, no cells (the tests' null maker) or the key off: no line, the plain heuristic
    @Override
    public void searchStarts() {
        Shooters fire = _fire;
        Cells cells = _cells;
        if (fire == null || cells == null || !lineOn()) {
            if (_lineBound) _line.remove();
            return;
        }
        _line.set(new Line(fire, cells, Tuning.get("run_break_line_cost", LINE_COST)));
        _lineBound = true;
    }

    @Override
    public void searchEnds() {
        if (_lineBound) _line.remove();
    }

    // the shooters' eyes, the first LINE_SHOOTERS alive of the list (nearest first); null for none
    static Shooters eyes(List<? extends Entity> shooters) {
        if (shooters == null || shooters.isEmpty()) return null;
        double[] x = new double[LINE_SHOOTERS], y = new double[LINE_SHOOTERS], z = new double[LINE_SHOOTERS];
        int n = 0;
        for (Entity e : shooters) {
            if (n == LINE_SHOOTERS) break;
            if (!e.isAlive()) continue;
            x[n] = e.getX();
            y[n] = e.getEyeY();
            z[n] = e.getZ();
            n++;
        }
        return n == 0 ? null : new Shooters(Arrays.copyOf(x, n), Arrays.copyOf(y, n), Arrays.copyOf(z, n));
    }

    // the tests' shooters, as refresh would have seen them (no entity without a world)
    void fireAt(Shooters fire) {
        _fire = fire;
    }

    // the eyes of the shooters one search prices
    record Shooters(double[] x, double[] y, double[] z) {
    }

    // one search's look at the shooters' lines: for a cell, cost for each shooter within LINE_RANGE of the eye of the buddy
    // standing in it with a clear line from the shooter's eye to that eye (RiskField.clear, on the goal's cells). memoised per
    // shooter and cell, at most LINE_RAYS rays a search
    static final class Line {
        private final Shooters fire;
        private final Cells cells;
        private final double cost;
        private final Long2ByteOpenHashMap[] seen;
        private int rays;

        Line(Shooters fire, Cells cells, double cost) {
            this.fire = fire;
            this.cells = cells;
            this.cost = cost;
            this.seen = new Long2ByteOpenHashMap[fire.x().length];
        }

        double cost(int x, int y, int z) {
            double cx = x + 0.5, ey = y + EYE, cz = z + 0.5;
            double sum = 0;
            for (int i = 0; i < seen.length; i++) {
                double dx = fire.x()[i] - cx, dy = fire.y()[i] - ey, dz = fire.z()[i] - cz;
                if (dx * dx + dy * dy + dz * dz > LINE_RANGE * LINE_RANGE) continue;
                if (inLine(i, cx, ey, cz, x, y, z)) sum += cost;
            }
            return sum;
        }

        private boolean inLine(int i, double cx, double ey, double cz, int x, int y, int z) {
            Long2ByteOpenHashMap memo = seen[i];
            if (memo == null) memo = seen[i] = new Long2ByteOpenHashMap();
            long key = GoalFlee.pack(x, y, z);
            byte known = memo.get(key);
            if (known != 0) return known == 1;
            if (rays >= LINE_RAYS) return true;
            rays++;
            boolean clear = RiskField.clear(cells, fire.x()[i], fire.y()[i], fire.z()[i], cx, ey, cz);
            memo.put(key, clear ? (byte) 1 : (byte) 2);
            return clear;
        }

        // the rays cast so far (the tests)
        int rays() {
            return rays;
        }
    }

    // this thread's line (the tests), null when no search bound one
    Line line() {
        return _lineBound ? _line.get() : null;
    }

    @Override
    protected List<Entity> getCrowd() {
        return _crowd.get();
    }
}
