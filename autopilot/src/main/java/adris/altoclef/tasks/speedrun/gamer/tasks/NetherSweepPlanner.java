package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.NetherConfig;
import adris.altoclef.world.NetherComplexGrid;
import adris.altoclef.world.NetherComplexGrid.Cell;
import adris.altoclef.world.NetherComplexGrid.Point;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

// the brain of the fortress search: which structure cell, which waypoint, what to do with what we saw. pure (no game, no
// minecraft types) so a fake "seen" oracle can drive it in a test. FindNetherStructureTask only walks where it says.
// everything durable goes into RunState (visited cells, bastions, fortress), so a relog does not walk the same cell twice
public final class NetherSweepPlanner {
    public enum Goal {
        FORTRESS,
        // a warped forest is where the endermen are, the sweep doubles as the search for one
        WARPED
    }

    public enum Sight {
        FORTRESS,
        BASTION,
        WARPED
    }

    public enum Kind {
        GOTO,
        FOUND,
        FAIL
    }

    public record Action(Kind kind, int x, int z, String why) {
    }

    // the two grid calls, behind an interface so tests do not depend on the real cell layout
    public interface Grid {
        List<Point> waypoints(Cell cell, int spacingChunks, int playerX, int playerZ);

        Optional<Cell> nextCell(int playerX, int playerZ, Set<String> visited);

        // fresh cells first, then the deferred ones once nothing fresh is near the portal. fakes that only know the plain
        // pick get fresh-then-any-deferred
        default Optional<Cell> nextCell(int playerX, int playerZ, Set<String> visited, Set<String> deferred, Point portal) {
            if (deferred.isEmpty()) {
                return nextCell(playerX, playerZ, visited);
            }
            Set<String> taken = new HashSet<>(visited);
            taken.addAll(deferred);
            Optional<Cell> fresh = nextCell(playerX, playerZ, taken);
            if (fresh.isPresent()) {
                return fresh;
            }
            return deferred.stream().sorted().findFirst().map(Cell::parse);
        }

        // same pick with the cells beyond `rings` of the leash point dropped first. the default asks the plain pick again with
        // each far answer marked spent, which is fine for fakes that are finite
        default Optional<Cell> nextCellWithin(int playerX, int playerZ, Set<String> visited, Set<String> deferred, Point portal,
                                              Point leash, int rings) {
            Cell centre = NetherComplexGrid.cellOf(leash.x(), leash.z());
            Set<String> spent = new HashSet<>(visited);
            Set<String> waiting = new HashSet<>();
            for (String key : deferred) {
                if (NetherComplexGrid.inLeash(Cell.parse(key), centre, rings)) {
                    waiting.add(key);
                } else {
                    spent.add(key);
                }
            }
            for (int i = 0; i < 10000; i++) {
                Optional<Cell> pick = nextCell(playerX, playerZ, spent, waiting, portal);
                if (pick.isEmpty() || NetherComplexGrid.inLeash(pick.get(), centre, rings)) {
                    return pick;
                }
                spent.add(pick.get().key());
            }
            return Optional.empty();
        }

        // the order for a revisit: start far from where we got wedged
        default List<Point> waypointsAwayFrom(Cell cell, int spacingChunks, int fromX, int fromZ) {
            return waypoints(cell, spacingChunks, fromX, fromZ);
        }
    }

    public static final Grid REAL_GRID = new Grid() {
        @Override
        public List<Point> waypoints(Cell cell, int spacingChunks, int playerX, int playerZ) {
            return NetherComplexGrid.sweepWaypoints(cell, spacingChunks, playerX, playerZ);
        }

        @Override
        public Optional<Cell> nextCell(int playerX, int playerZ, Set<String> visited) {
            return NetherComplexGrid.nextCell(playerX, playerZ, visited);
        }

        @Override
        public Optional<Cell> nextCell(int playerX, int playerZ, Set<String> visited, Set<String> deferred, Point portal) {
            return NetherComplexGrid.nextCell(playerX, playerZ, visited, deferred, portal);
        }

        @Override
        public Optional<Cell> nextCellWithin(int playerX, int playerZ, Set<String> visited, Set<String> deferred, Point portal,
                                             Point leash, int rings) {
            return NetherComplexGrid.nextCellWithin(playerX, playerZ, visited, deferred, portal, leash, rings);
        }

        @Override
        public List<Point> waypointsAwayFrom(Cell cell, int spacingChunks, int fromX, int fromZ) {
            return NetherComplexGrid.sweepWaypointsAwayFrom(cell, spacingChunks, fromX, fromZ);
        }
    };

    // what we know is out there (the block tracker's loaded chunks in the game, a set in tests)
    public interface SightSource {
        Optional<RunState.Pos> nearestSeen(Sight sight);
    }

    public enum Look {
        // its chunks are not all in yet, we cannot tell: walk at the bare waypoint and ask again
        UNLOADED,
        FINE,
        // over lava, the nearest column with a floor is (x, z) of the spot
        SHIFTED,
        NOWHERE
    }

    public record Spot(Look look, int x, int z) {
    }

    // what is under a waypoint. the world half is NetherGround, tests hand in a fake
    public interface Ground {
        Spot look(int x, int z);
    }

    public static final Ground BLIND = (x, z) -> new Spot(Look.UNLOADED, x, z);

    // a second block of the same structure this close to one we know is the same structure
    private static final int SAME_STRUCTURE_BLOCKS = 64;
    // fortresses are big and spill over cell borders, a given up one covers about this much
    private static final int EXHAUSTED_BLOCKS = 192;
    // nylium this close to a forest we couldn't reach is that same forest behind that same rock
    static final int WARPED_EXHAUSTED_BLOCKS = 64;
    // not asked for this long = something else had the wheel
    private static final double IDLE_SECONDS = 5;
    private static final int MAX_GAP_RESETS = 2;
    static final int SKIPPED_CELL_SLACK = 3;
    // two stuck give-ups this close together are the same dead end pocket
    static final int POCKET_BLOCKS = 24;
    // a give-up from a bot that never left where the cell started says nothing about the cell, see pinned
    static final int PINNED_BLOCKS = 6;

    private final RunState state;
    private final NetherConfig cfg;
    private final Grid grid;
    // how many cells out from the portal's own cell the warped sweep may walk (chebyshev)
    public static final int WARPED_LEASH_RINGS = 1;

    private final int maxCells;

    private Cell cell;
    private List<Point> waypoints = List.of();
    private int index;
    private double waypointSince;
    // what we are walking at: the waypoint, or where it was moved to once its chunks loaded (decided once, then it sticks)
    private Point aim;
    private boolean judged;
    // best distance to the aim so far and the walking seconds since it last got 'waypointNoProgressBlocks' better
    private double bestDist = Double.NaN;
    private double stuckFor;
    private int gaps;
    // seconds between this step and the last one, 0 when something else had the wheel in between
    private double stepSeconds;
    // waypoints given up on or skipped, for the life of this planner: a cell picked up again never hands them back
    private final Set<Long> dead = new HashSet<>();
    private Ground ground = BLIND;
    private Consumer<String> log = line -> {
    };
    // cells started per goal: a fortress that took four cells must not eat the warped forest search's share
    private final int[] cellsStarted = new int[Goal.values().length];
    // the ones of those we really walked off from, the count maxCells is about
    private final int[] cellsWalked = new int[Goal.values().length];
    private double lastStepAt = Double.NaN;
    // where the last stuck give-up happened and in which cell. planner local on purpose, same as 'dead': a rebuilt planner
    // (phase entry after a retry, a death or a regress) starts over, the worst case is one more try at a pocket. a dropped
    // cell is in visitedCells or deferredCells, that part does survive
    private int pocketX;
    private int pocketZ;
    private String pocketCell;
    // where the current cell started, and whether the bot has been PINNED_BLOCKS away from it since (a latch: walking off and
    // getting backed up to the start is not "never moved")
    private int cellStartX;
    private int cellStartZ;
    private boolean leftStart;
    // waypoints that already had their one retry after a stuck give-up
    private final Set<Long> retried = new HashSet<>();
    // a waypoint was given up on since the task last asked (the bag may be what ran dry, see FindNetherStructureTask)
    private boolean gaveUp;
    // the current cell was dropped by a pocket (not walked out): step() defers it instead of marking it visited
    private boolean pocketDropped;
    // the cell that was just deferred sits out the very next pick: the player is standing in it, so "nearest deferred" would hand
    // it straight back and the revisit would start from the same wall it gave up at
    private String justDeferred;
    // where the pocket that deferred each cell was, so the revisit can start on the far side. planner local like pocketX, a
    // rebuilt planner just walks the revisit in the normal nearest-first order
    private final Map<String, Point> pocketPoints = new HashMap<>();
    // the waypoint on the clock gets this long before the no progress rule may end it, scaled to the leg when it became current
    private double softSeconds;

    // read by the path predicate on the pathing threads, so snapshots and volatiles only
    private volatile int[] avoidXZ = new int[0];
    private volatile int playerX;
    private volatile int playerZ;

    public NetherSweepPlanner(RunState state, NetherConfig cfg, Grid grid, int maxCells) {
        this.state = state;
        this.cfg = cfg;
        this.grid = grid;
        this.maxCells = maxCells;
        this.softSeconds = cfg.waypointSeconds;
        refreshAvoid();
    }

    public NetherSweepPlanner(RunState state, NetherConfig cfg) {
        this(state, cfg, REAL_GRID, cfg.maxCells);
    }

    public void setGround(Ground ground) {
        this.ground = ground;
    }

    public void setLog(Consumer<String> log) {
        this.log = log;
    }

    public int cellsStarted(Goal goal) {
        return cellsStarted[goal.ordinal()];
    }

    public String currentCellKey() {
        return cell == null ? null : cell.key();
    }

    // true once per give-up, so whoever walks can look at the bag before the next try
    public boolean takeGiveUp() {
        boolean was = gaveUp;
        gaveUp = false;
        return was;
    }

    public boolean goalMet(Goal goal) {
        return goal == Goal.FORTRESS ? !state.fortress.isEmpty() : state.warpedForest != null;
    }

    // asks the source for each kind of sighting and records what it says. true = something new went into the RunState
    // (the caller saves)
    public boolean scan(SightSource source) {
        boolean changed = false;
        for (Sight sight : Sight.values()) {
            changed |= scan(source, sight);
        }
        return changed;
    }

    // one kind only, so a caller can spread them over a few ticks instead of paying for all three at once
    public boolean scan(SightSource source, Sight sight) {
        Optional<RunState.Pos> pos = source.nearestSeen(sight);
        return pos.isPresent() && report(sight, pos.get());
    }

    public boolean report(Sight sight, RunState.Pos pos) {
        String key = NetherComplexGrid.cellOf(pos.x, pos.z).key();
        return switch (sight) {
            case FORTRESS -> reportFortress(pos, key);
            case BASTION -> reportBastion(pos, key);
            case WARPED -> reportWarped(pos);
        };
    }

    private boolean reportFortress(RunState.Pos pos, String key) {
        // a fortress we gave up on is still standing right there in view, do not fall for it again
        if (nearAny(state.fortressExhausted, pos, EXHAUSTED_BLOCKS)) {
            return false;
        }
        boolean fresh = !nearAny(state.fortress, pos, SAME_STRUCTURE_BLOCKS);
        if (fresh) {
            state.fortress.add(pos);
        }
        // the cell is spoken for either way: never sweep it again
        fresh |= state.fortressCells.add(key);
        state.visitedCells.add(key);
        state.deferredCells.remove(key);
        dropCell(key);
        return fresh;
    }

    // a brick of a fortress we gave up on: reporting it does nothing, so the scan should not spend its look on it
    public boolean isGivenUp(RunState.Pos pos) {
        return nearAny(state.fortressExhausted, pos, EXHAUSTED_BLOCKS);
    }

    // a block of a bastion we already know: reporting it does nothing, so the scan should not spend its look on it
    public boolean isKnownBastion(RunState.Pos pos) {
        return nearAny(state.bastion, pos, SAME_STRUCTURE_BLOCKS);
    }

    private boolean reportBastion(RunState.Pos pos, String key) {
        // a fortress and a bastion never share a cell, so a "bastion" block in a fortress cell is something else
        // (a ruined portal, a blackstone blob) and must not make us avoid the fortress we want
        if (state.fortressCells.contains(key)) {
            return false;
        }
        boolean fresh = !nearAny(state.bastion, pos, SAME_STRUCTURE_BLOCKS);
        if (fresh) {
            state.bastion.add(pos);
            refreshAvoid();
        }
        fresh |= state.bastionCells.add(key);
        state.visitedCells.add(key);
        state.deferredCells.remove(key);
        dropCell(key);
        return fresh;
    }

    private boolean reportWarped(RunState.Pos pos) {
        if (state.warpedForest != null || isGivenUpWarped(pos)) {
            return false;
        }
        state.warpedForest = pos;
        return true;
    }

    // same as isGivenUp, for nylium: dropped before the scan's nearest forty or the dud forest is all it ever looks at
    public boolean isGivenUpWarped(RunState.Pos pos) {
        return nearAny(state.warpedExhausted, pos, WARPED_EXHAUSTED_BLOCKS);
    }

    // the walk there stalled or ran out of time, so it's behind something. nothing else ever clears the forest and it survives a
    // retry, so without this a bad one gets walked at for the rest of the run. goalMet flips back and the sweep can go again
    public void giveUpWarped(RunState.Pos spot) {
        state.warpedExhausted.add(spot);
        state.warpedForest = null;
    }

    // the spawner ran dry (or we could not reach it): forget the fortress it sits in so the search looks for another cell.
    // its cell stays visited and in fortressCells, we do not come back here. a second fortress we already saw stays known,
    // that one is the next stop. near = the spawner, null = we do not know where it was and every fortress goes
    public void giveUpFortress(RunState.Pos near) {
        if (near == null) {
            state.fortressExhausted.addAll(state.fortress);
            state.fortress.clear();
        } else {
            dropFortressesAround(near);
        }
        state.spawner = null;
        cell = null;
    }

    public void giveUpFortress() {
        giveUpFortress(null);
    }

    // everything inside the exhausted radius is the same fortress. a spawner further out than that from every sighting
    // (they are spots on the edge we looked at from) still costs us the closest one, or the dud would be picked again
    private void dropFortressesAround(RunState.Pos near) {
        long radius2 = (long) EXHAUSTED_BLOCKS * EXHAUSTED_BLOCKS;
        List<RunState.Pos> gone = new ArrayList<>();
        RunState.Pos closest = null;
        long best = Long.MAX_VALUE;
        for (RunState.Pos k : state.fortress) {
            long d = dist2(k.x, k.z, near.x, near.z);
            if (d < radius2) {
                gone.add(k);
            } else if (d < best) {
                best = d;
                closest = k;
            }
        }
        if (gone.isEmpty() && closest != null) {
            gone.add(closest);
        }
        state.fortress.removeAll(gone);
        state.fortressExhausted.addAll(gone);
    }

    // one decision. now is any monotonic clock in seconds
    public Action step(Goal goal, int px, int pz, double now) {
        playerX = px;
        playerZ = pz;
        if (cell != null && !leftStart && dist2(px, pz, cellStartX, cellStartZ) >= (long) PINNED_BLOCKS * PINNED_BLOCKS) {
            leftStart = true;
            // a cell only counts toward maxCells once we have really walked off. one we sat boxed in at is the bot's problem, not
            // a sweep of nothing (the 120 s stall still ends a bot that is pocketed for ever)
            cellsWalked[goal.ordinal()]++;
        }
        // we were busy with something else (rods, a ghast, a barter) so the waypoint's clock did not run for real:
        // without this the first step back would time the waypoint out on the spot
        stepSeconds = Double.isNaN(lastStepAt) ? 0 : now - lastStepAt;
        if (stepSeconds > IDLE_SECONDS) {
            waypointSince = now;
            stepSeconds = 0;
            // and whatever dragged us off (a fight, a ghast run) moved us, so the old best distance means nothing either. the
            // stuck seconds start over for the first couple of gaps only, or a waypoint that keeps getting interrupted would
            // never be given up on
            bestDist = Double.NaN;
            if (++gaps <= MAX_GAP_RESETS) {
                stuckFor = 0;
            }
        }
        lastStepAt = now;
        if (goalMet(goal)) {
            return new Action(Kind.FOUND, px, pz, "found");
        }
        // a cell the fortress sweep left half walked can be past the leash by the time the goal flips to the warped forest. it is
        // not visited (the fortress sweep may still want it), just not ours to walk
        if (goal == Goal.WARPED && cell != null && state.netherPortal != null
                && !NetherComplexGrid.inLeash(cell, NetherComplexGrid.cellOf(state.netherPortal.x, state.netherPortal.z), WARPED_LEASH_RINGS)) {
            cell = null;
            pocketDropped = false;
        }
        // every pass of this loop either returns or finishes a cell. a finished cell is visited or deferred, a deferred one is never a
        // fresh pick and its revisit ends visited, so it ends
        while (true) {
            if (cell == null) {
                Action fail = startNextCell(goal, px, pz, now);
                if (fail != null) {
                    return fail;
                }
            }
            Point target = nextWaypoint(px, pz, now);
            if (target != null) {
                return new Action(Kind.GOTO, target.x(), target.z(), "cell " + cell.key());
            }
            finishCell();
        }
    }

    // a cell is done. walked out = visited. dropped by a pocket = deferred for one revisit, and a pocket on the revisit too is
    // visited for good (the cell is nothing but a dead end pocket)
    private void finishCell() {
        String key = cell.key();
        Integer used = state.deferredCells.get(key);
        if (pocketDropped && used == null) {
            state.deferredCells.put(key, 0);
            justDeferred = key;
        } else {
            state.visitedCells.add(key);
            state.deferredCells.remove(key);
        }
        pocketDropped = false;
        cell = null;
    }

    // null = a cell is current now
    private Action startNextCell(Goal goal, int px, int pz, double now) {
        int walked = cellsWalked[goal.ordinal()];
        // cells that end without a step (nowhere to stand, all behind a bastion) are free, but a grid that never runs out of them
        // still has to end
        if (walked >= maxCells || cellsStarted[goal.ordinal()] >= maxCells * SKIPPED_CELL_SLACK) {
            return new Action(Kind.FAIL, px, pz, "swept " + walked + " cells and found nothing");
        }
        // a revisit in flight (count 1) is spent: out of the fresh pick, and not offered as a deferred one either
        Set<String> spent = state.visitedCells;
        Set<String> waiting = new HashSet<>();
        String sitOut = justDeferred;
        justDeferred = null;
        for (var e : state.deferredCells.entrySet()) {
            if (e.getValue() == 0 && !e.getKey().equals(sitOut)) {
                waiting.add(e.getKey());
            } else {
                if (spent == state.visitedCells) {
                    spent = new HashSet<>(state.visitedCells);
                }
                spent.add(e.getKey());
            }
        }
        RunState.Pos home = state.netherPortal;
        Point portal = home == null ? null : new Point(home.x, home.z);
        // the warped forest search walks cells nearest the player, so every pick pulled it farther out. a miss falls through to
        // a forest-less pearl hunt anyway, so it stays near the portal (the fortress is not leashed, it really is far)
        boolean leashed = goal == Goal.WARPED && portal != null;
        Optional<Cell> next = leashed
                ? grid.nextCellWithin(px, pz, spent, waiting, portal, portal, WARPED_LEASH_RINGS)
                : grid.nextCell(px, pz, spent, waiting, portal);
        if (next.isEmpty()) {
            return new Action(Kind.FAIL, px, pz, leashed ? "warped sweep: past the portal leash" : "ran out of cells");
        }
        cell = next.get();
        cellStartX = px;
        cellStartZ = pz;
        leftStart = false;
        cellsStarted[goal.ordinal()]++;
        pocketDropped = false;
        if (waiting.contains(cell.key())) {
            beginRevisit(px, pz);
        } else {
            waypoints = grid.waypoints(cell, cfg.sweepSpacingChunks, px, pz);
        }
        index = 0;
        waypointSince = now;
        freshWaypoint();
        beginLeg(px, pz);
        return null;
    }

    // the second go at a cell a pocket ate: the waypoints that were dead or retried get a clean slate, the walk starts at
    // the corner farthest from the pocket, and the pocket rule forgets the old spot so it takes two new give-ups to drop it
    private void beginRevisit(int px, int pz) {
        state.deferredCells.put(cell.key(), 1);
        for (Point w : grid.waypoints(cell, cfg.sweepSpacingChunks, px, pz)) {
            dead.remove(key(w));
            retried.remove(key(w));
        }
        pocketCell = null;
        Point from = pocketPoints.get(cell.key());
        waypoints = from == null
                ? grid.waypoints(cell, cfg.sweepSpacingChunks, px, pz)
                : grid.waypointsAwayFrom(cell, cfg.sweepSpacingChunks, from.x(), from.z());
    }

    private void freshWaypoint() {
        gaps = 0;
        judged = false;
        bestDist = Double.NaN;
        stuckFor = 0;
    }

    // a long leg gets longer before the soft timeout may fire (about 2.5 blocks/s is a slow nether walk), measured from where we
    // stand when the waypoint becomes current. a gap reset later moves the clock, not this
    private void beginLeg(int px, int pz) {
        if (index < waypoints.size()) {
            Point w = waypoints.get(index);
            softSeconds = legSeconds(Math.sqrt(dist2(px, pz, w.x(), w.z())));
        }
    }

    static double legSeconds(double legBlocks) {
        return Math.max(45, legBlocks / 2.5);
    }

    // how close counts as there. the walk uses the same radius as its goal, so it ends by itself right where the planner
    // moves on instead of getting replaced mid-path (that cancel is what dropped us off a ledge)
    public double arriveRadius() {
        return cfg.waypointArriveBlocks;
    }

    // the waypoint to walk to, or null when this cell is done. arrived, given up on, nowhere to stand and avoided ones are
    // skipped
    private Point nextWaypoint(int px, int pz, double now) {
        double arrive = cfg.waypointArriveBlocks;
        while (index < waypoints.size()) {
            Point w = waypoints.get(index);
            Point to = aimAt(w);
            if (to != null) {
                boolean arrived = dist2(px, pz, to.x(), to.z()) <= arrive * arrive;
                // noProgress first, it is the one that keeps the stuck seconds up to date
                boolean stuck = !arrived && noProgress(px, pz, to);
                boolean timedOut = !arrived && timedOut(now);
                if (!arrived && !timedOut && !stuck && !nearBastion(to.x(), to.z())) {
                    return to;
                }
                if ((timedOut || stuck) && pinned(px, pz)) {
                    // boxed in where the cell began: the walls are not the cell's fault, and burning cells from one spot
                    // is how a sweep ends with "no fortress". keep asking, the phase watchdog ends a bot that cannot move
                    gaveUp = true;
                    waypointSince = now;
                    freshWaypoint();
                    beginLeg(px, pz);
                    return to;
                }
                if (timedOut) {
                    dead.add(key(w));
                    gaveUp = true;
                    // wedged and out of time at once still counts as a pocket for the next give-up
                    if (stuck) {
                        pocketX = px;
                        pocketZ = pz;
                        pocketCell = cell.key();
                    }
                    log.accept("sweep: gave up on " + to.x() + " " + to.z() + ", not there after "
                            + (int) (now - waypointSince) + " s (" + away(px, pz, to) + " away)");
                } else if (stuck) {
                    gaveUp = true;
                    if (stuckGiveUp(w, px, pz)) {
                        log.accept("sweep: gave up on " + to.x() + " " + to.z() + ", no closer in "
                                + (int) cfg.waypointNoProgressSeconds + " s (" + away(px, pz, to) + " away)");
                        // the rest of the cell is behind the same wall, step() defers it (visited if this was the revisit) and picks another
                        index = waypoints.size();
                        return null;
                    }
                    if (!dead.contains(key(w))) {
                        // first time stuck here: same waypoint again, the bag gets looked at in between
                        waypointSince = now;
                        freshWaypoint();
                        beginLeg(px, pz);
                        return to;
                    }
                    log.accept("sweep: gave up on " + to.x() + " " + to.z() + ", no closer in "
                            + (int) cfg.waypointNoProgressSeconds + " s (" + away(px, pz, to) + " away)");
                }
            }
            index++;
            waypointSince = now;
            freshWaypoint();
            beginLeg(px, pz);
        }
        return null;
    }

    // the soft timeout (max(45, leg / 2.5) s) is only a verdict on a walk that has also stopped getting closer (half the stuck seconds is enough, a walk that
    // is still closing at 1.5 blocks/s resets them every few seconds). the hard cap catches the crawl that never trips either.
    // waypointSince and the stuck seconds are on the same clock: both restart on an idle gap and only stepped time adds up
    private boolean timedOut(double now) {
        return softTimedOut(now - waypointSince, stuckFor, softSeconds, cfg);
    }

    static boolean softTimedOut(double elapsed, double stuckFor, NetherConfig cfg) {
        return softTimedOut(elapsed, stuckFor, cfg.waypointSeconds, cfg);
    }

    static boolean softTimedOut(double elapsed, double stuckFor, double softSeconds, NetherConfig cfg) {
        if (elapsed >= cfg.waypointHardSeconds) {
            return true;
        }
        if (elapsed < softSeconds) {
            return false;
        }
        // no progress rule off = the old flat backstop
        return cfg.waypointNoProgressSeconds <= 0 || stuckFor >= cfg.waypointNoProgressSeconds / 2;
    }

    // never got PINNED_BLOCKS away from where this cell started (step keeps leftStart current)
    private boolean pinned(int px, int pz) {
        return !leftStart;
    }

    // a stuck give-up at (px, pz). true = the second one in the same pocket, drop the cell. otherwise the waypoint is
    // either marked dead (it already had its retry) or left alive for exactly one more go
    private boolean stuckGiveUp(Point w, int px, int pz) {
        boolean same = pocketCell != null && pocketCell.equals(cell.key())
                && dist2(px, pz, pocketX, pocketZ) <= (long) POCKET_BLOCKS * POCKET_BLOCKS;
        pocketX = px;
        pocketZ = pz;
        pocketCell = cell.key();
        if (same) {
            dead.add(key(w));
            pocketDropped = true;
            pocketPoints.put(cell.key(), new Point(px, pz));
            return true;
        }
        if (!retried.add(key(w))) {
            dead.add(key(w));
        }
        return false;
    }

    // where to walk for this waypoint. the column under it is looked at once, as soon as its chunks are in: until then the
    // bare waypoint, after that whatever was decided, even if the world changes under it. null = skip it
    private Point aimAt(Point w) {
        if (dead.contains(key(w))) {
            return null;
        }
        if (!judged) {
            aim = w;
            Spot spot = ground.look(w.x(), w.z());
            if (spot.look() == Look.UNLOADED) {
                return aim;
            }
            judged = true;
            if (spot.look() == Look.NOWHERE) {
                dead.add(key(w));
                log.accept("sweep: waypoint " + w.x() + " " + w.z() + " has nowhere to stand, skipping");
                return null;
            }
            if (spot.look() == Look.SHIFTED) {
                aim = new Point(spot.x(), spot.z());
                log.accept("sweep: waypoint " + w.x() + " " + w.z() + " is over lava, using " + aim.x() + " " + aim.z());
                // a new place to be, the old distances mean nothing
                bestDist = Double.NaN;
                stuckFor = 0;
            }
        }
        return aim;
    }

    // true once we have walked for waypointNoProgressSeconds without getting waypointNoProgressBlocks closer than our best.
    // only stepped seconds add up, so a fight or a ghast in the middle does not read as standing still
    private boolean noProgress(int px, int pz, Point to) {
        double d = Math.sqrt(dist2(px, pz, to.x(), to.z()));
        if (Double.isNaN(bestDist)) {
            // (first look: whoever cleared bestDist decided what happens to the stuck seconds, see step and freshWaypoint)
            bestDist = d;
            return false;
        }
        if (d <= bestDist - cfg.waypointNoProgressBlocks) {
            bestDist = d;
            stuckFor = 0;
            return false;
        }
        stuckFor += stepSeconds;
        return cfg.waypointNoProgressSeconds > 0 && stuckFor >= cfg.waypointNoProgressSeconds;
    }

    private static int away(int px, int pz, Point to) {
        return (int) Math.round(Math.sqrt(dist2(px, pz, to.x(), to.z())));
    }

    private static long key(Point p) {
        return ((long) p.x() << 32) ^ (p.z() & 0xffffffffL);
    }

    private void dropCell(String key) {
        if (cell != null && cell.key().equals(key)) {
            cell = null;
        }
    }

    // within the avoid radius of a bastion we saw
    public boolean nearBastion(int x, int z) {
        int[] xz = avoidXZ;
        double r = cfg.bastionAvoidRadius;
        for (int i = 0; i < xz.length; i += 2) {
            if (dist2(x, z, xz[i], xz[i + 1]) < r * r) {
                return true;
            }
        }
        return false;
    }

    // the path predicate: blocks near a bastion are off limits, unless we already stand inside one (a hard wall
    // around the player is a path that never exists)
    public boolean blocksPath(int x, int z) {
        return nearBastion(x, z) && !nearBastion(playerX, playerZ);
    }

    private void refreshAvoid() {
        int[] out = new int[state.bastion.size() * 2];
        int i = 0;
        for (RunState.Pos p : state.bastion) {
            out[i++] = p.x;
            out[i++] = p.z;
        }
        avoidXZ = out;
    }

    private static boolean nearAny(List<RunState.Pos> known, RunState.Pos pos, int radius) {
        for (RunState.Pos k : known) {
            if (dist2(k.x, k.z, pos.x, pos.z) < (long) radius * radius) {
                return true;
            }
        }
        return false;
    }

    private static long dist2(int ax, int az, int bx, int bz) {
        long dx = (long) ax - bx;
        long dz = (long) az - bz;
        return dx * dx + dz * dz;
    }
}
