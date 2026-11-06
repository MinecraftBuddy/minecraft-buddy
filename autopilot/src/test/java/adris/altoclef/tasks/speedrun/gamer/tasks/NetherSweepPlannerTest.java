package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.RunStateStore;
import adris.altoclef.tasks.speedrun.gamer.config.NetherConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Action;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Goal;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Kind;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Sight;
import adris.altoclef.world.NetherComplexGrid;
import adris.altoclef.world.NetherComplexGrid.Cell;
import adris.altoclef.world.NetherComplexGrid.Point;
import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NetherSweepPlannerTest {
    // cells in a row along x, each with three waypoints 100 blocks apart starting at the cell's x * 1000
    private static final NetherSweepPlanner.Grid ROW = new NetherSweepPlanner.Grid() {
        @Override
        public List<Point> waypoints(Cell cell, int spacingChunks, int px, int pz) {
            int base = cell.cx() * 1000;
            return List.of(new Point(base, 0), new Point(base + 100, 0), new Point(base + 200, 0));
        }

        @Override
        public Optional<Cell> nextCell(int px, int pz, Set<String> visited) {
            for (int cx = 0; cx < 100; cx++) {
                if (!visited.contains(new Cell(cx, 0).key())) {
                    return Optional.of(new Cell(cx, 0));
                }
            }
            return Optional.empty();
        }
    };

    private RunState state;
    private NetherConfig cfg;
    private NetherSweepPlanner planner;

    @Before
    public void setUp() {
        state = new RunState();
        cfg = new NetherConfig();
        planner = new NetherSweepPlanner(state, cfg, ROW, 12);
    }

    private static RunState.Pos pos(int x, int z) {
        return new RunState.Pos(x, 64, z);
    }

    // a fake "seen" oracle: only what is in the map has been seen
    private static NetherSweepPlanner.SightSource seeing(Map<Sight, RunState.Pos> seen) {
        return sight -> Optional.ofNullable(seen.get(sight));
    }

    @Test
    public void walksTheWaypointsOfTheNearestCellInOrder() {
        Action a = planner.step(Goal.FORTRESS, -300, 0, 0);
        assertEquals(Kind.GOTO, a.kind());
        assertEquals(0, a.x());
        // not there yet: same waypoint
        assertEquals(0, planner.step(Goal.FORTRESS, 80, 0, 1).x());
        // inside the arrive radius of the first one: on to the second
        Action b = planner.step(Goal.FORTRESS, 10, 0, 2);
        assertEquals(100, b.x());
        Action c = planner.step(Goal.FORTRESS, 100, 0, 3);
        assertEquals(200, c.x());
    }

    // the walk's goal uses this radius, so the path ends where the planner moves on and nothing is replaced mid-path
    @Test
    public void walkRadiusIsTheArriveRadiusTheStepUses() {
        assertEquals(cfg.waypointArriveBlocks, planner.arriveRadius(), 0);
        planner.step(Goal.FORTRESS, -300, 0, 0);
        int edge = (int) planner.arriveRadius();
        assertEquals(0, planner.step(Goal.FORTRESS, -edge - 1, 0, 1).x());
        assertEquals(100, planner.step(Goal.FORTRESS, -edge, 0, 2).x());
    }

    @Test
    public void finishedCellIsMarkedVisitedAndTheNextOneStarts() {
        planner.step(Goal.FORTRESS, 0, 0, 0);
        planner.step(Goal.FORTRESS, 0, 0, 1);
        planner.step(Goal.FORTRESS, 100, 0, 2);
        // standing on the last waypoint: cell 0 is done, cell 1 starts
        Action next = planner.step(Goal.FORTRESS, 200, 0, 3);
        assertEquals(Kind.GOTO, next.kind());
        assertEquals(1000, next.x());
        assertTrue(state.visitedCells.contains("0,0"));
        assertEquals(2, planner.cellsStarted(Goal.FORTRESS));
    }

    @Test
    public void neverRevisitsAVisitedCell() {
        state.visitedCells.add("0,0");
        state.visitedCells.add("1,0");
        Action a = planner.step(Goal.FORTRESS, 0, 0, 0);
        assertEquals(2000, a.x());
    }

    @Test
    public void fortressSightingIsRecordedAndFinishes() {
        Map<Sight, RunState.Pos> seen = new EnumMap<>(Sight.class);
        planner.step(Goal.FORTRESS, 0, 0, 0);
        assertFalse(planner.scan(seeing(seen)));
        seen.put(Sight.FORTRESS, pos(150, 40));
        assertTrue(planner.scan(seeing(seen)));
        assertEquals(1, state.fortress.size());
        String key = NetherComplexGrid.cellOf(150, 40).key();
        assertTrue(state.fortressCells.contains(key));
        assertTrue(state.visitedCells.contains(key));
        assertEquals(Kind.FOUND, planner.step(Goal.FORTRESS, 0, 0, 1).kind());
        assertTrue(planner.goalMet(Goal.FORTRESS));
        // the same sighting again is not news
        assertFalse(planner.scan(seeing(seen)));
        assertEquals(1, state.fortress.size());
    }

    @Test
    public void unseenBlocksAreNeverRecorded() {
        planner.step(Goal.FORTRESS, 0, 0, 0);
        planner.scan(seeing(new EnumMap<>(Sight.class)));
        assertTrue(state.fortress.isEmpty());
        assertTrue(state.bastion.isEmpty());
        assertNull(state.warpedForest);
    }

    @Test
    public void bastionMarksItsCellAndIsAvoided() {
        // cell 0 is x in 0..431: a bastion at x=250 is in cell 0, our current cell
        planner.step(Goal.FORTRESS, 0, 0, 0);
        assertTrue(planner.report(Sight.BASTION, pos(250, 0)));
        String key = NetherComplexGrid.cellOf(250, 0).key();
        assertTrue(state.bastionCells.contains(key));
        assertTrue(state.visitedCells.contains(key));
        // the current cell was abandoned, the next one starts
        Action a = planner.step(Goal.FORTRESS, 0, 0, 1);
        assertEquals(1000, a.x());
        assertFalse(planner.goalMet(Goal.FORTRESS));
        // 48 blocks around it are off limits
        assertTrue(planner.nearBastion(250, 40));
        assertFalse(planner.nearBastion(250, 60));
    }

    @Test
    public void waypointsInsideABastionZoneAreSkipped() {
        // bastion right on the second waypoint of cell 1
        state.visitedCells.add("0,0");
        planner.report(Sight.BASTION, pos(1100, 10));
        // real cell of that bastion is 2, so the fake cell 1 (waypoints 1000, 1100, 1200) still gets swept
        // we stand on the first waypoint, the second one is 10 blocks from the bastion: skipped, third is 100 away
        Action a = planner.step(Goal.FORTRESS, 1000, 0, 0);
        assertEquals(Kind.GOTO, a.kind());
        assertEquals(1200, a.x());
    }

    @Test
    public void pathPredicateLetsUsOutWhenWeStandInsideAZone() {
        planner.report(Sight.BASTION, pos(5000, 0));
        planner.step(Goal.FORTRESS, 0, 0, 0);
        assertTrue(planner.blocksPath(5010, 0));
        // now we are in the zone ourselves: no walls
        planner.step(Goal.FORTRESS, 5010, 0, 1);
        assertFalse(planner.blocksPath(5010, 0));
        assertFalse(planner.blocksPath(5020, 0));
    }

    @Test
    public void bastionBlocksInAFortressCellAreIgnored() {
        planner.report(Sight.FORTRESS, pos(100, 100));
        assertFalse(planner.report(Sight.BASTION, pos(120, 120)));
        assertTrue(state.bastion.isEmpty());
        assertFalse(planner.nearBastion(120, 120));
    }

    @Test
    public void warpedForestIsRecordedOnceAndDoesNotStopTheSweep() {
        assertTrue(planner.report(Sight.WARPED, pos(30, 30)));
        assertFalse(planner.report(Sight.WARPED, pos(900, 900)));
        assertEquals(pos(30, 30), state.warpedForest);
        // looking for a fortress: still sweeping
        assertEquals(Kind.GOTO, planner.step(Goal.FORTRESS, 0, 0, 0).kind());
        // looking for a forest: done
        assertEquals(Kind.FOUND, planner.step(Goal.WARPED, 0, 0, 1).kind());
    }

    @Test
    public void aForestGivenUpOnIsForgottenAndTheSweepGoesAgain() {
        planner.report(Sight.WARPED, pos(30, 30));
        assertEquals(Kind.FOUND, planner.step(Goal.WARPED, 0, 0, 0).kind());
        planner.giveUpWarped(pos(30, 30));
        assertNull(state.warpedForest);
        assertEquals(List.of(pos(30, 30)), state.warpedExhausted);
        assertFalse(planner.goalMet(Goal.WARPED));
        assertEquals(Kind.GOTO, planner.step(Goal.WARPED, 0, 0, 1).kind());
    }

    @Test
    public void nyliumNearAGivenUpForestIsTheSameForest() {
        planner.report(Sight.WARPED, pos(30, 30));
        planner.giveUpWarped(pos(30, 30));
        assertTrue(planner.isGivenUpWarped(pos(93, 30)));
        assertFalse(planner.report(Sight.WARPED, pos(93, 30)));
        assertNull(state.warpedForest);
        // 64 out is somewhere else
        assertFalse(planner.isGivenUpWarped(pos(94, 30)));
        assertTrue(planner.report(Sight.WARPED, pos(94, 30)));
        assertEquals(pos(94, 30), state.warpedForest);
    }

    @Test
    public void aGivenUpForestSurvivesARelog() {
        planner.report(Sight.WARPED, pos(30, 30));
        planner.giveUpWarped(pos(30, 30));
        RunState back = new Gson().fromJson(RunStateStore.toJson(state), RunState.class);
        assertEquals(state.warpedExhausted, back.warpedExhausted);
        assertNull(back.warpedForest);
        NetherSweepPlanner again = new NetherSweepPlanner(back, cfg, ROW, 12);
        assertFalse(again.report(Sight.WARPED, pos(40, 30)));
    }

    @Test
    public void aSaveFromBeforeTheListStillLoads() {
        RunState old = new Gson().fromJson("{}", RunState.class);
        assertTrue(old.warpedExhausted.isEmpty());
    }

    // asks once a second like the task does (a bigger gap reads as "something else had the wheel")
    private static Action stepAt(NetherSweepPlanner p, Goal goal, int px, int pz, int from, int to) {
        Action a = null;
        for (int t = from; t <= to; t++) {
            a = p.step(goal, px, pz, t);
        }
        return a;
    }

    // starts the cell 50 blocks west of where the stuck bot will stand, a bot that never left the start is not given up on
    private static void begin(NetherSweepPlanner p, int px, int pz) {
        p.step(Goal.FORTRESS, px - 50, pz, 0);
    }

    // keeps asking while we are far from everything and drifting away from it (so every waypoint gives up, and the bot is not
    // pinned) until it stops saying GOTO
    private static Action stepUntilDone(NetherSweepPlanner p, Goal goal, int from) {
        Action a;
        int t = from;
        do {
            a = p.step(goal, 500, 500 + t, t);
            t++;
        } while (a.kind() == Kind.GOTO && t < from + 20000);
        return a;
    }

    @Test
    public void slowWaypointIsSkippedAfterTheTimeout() {
        // a stationary player would trip the no progress rule at 20 s, this one pins the flat backstop
        cfg.waypointNoProgressSeconds = 0;
        // the cell starts on the far side of the same 225 block leg, a bot that never moved is never given up on
        planner.step(Goal.FORTRESS, 225, 0, 0);
        assertEquals(0, stepAt(planner, Goal.FORTRESS, -225, 0, 0, 89).x());
        // 90 s: skipped, the timer restarts on the next one
        assertEquals(100, stepAt(planner, Goal.FORTRESS, -225, 0, 90, 90).x());
        // and its own clock is scaled to the leg it started from: 325 blocks is 130 s, not the flat 90
        assertEquals(100, stepAt(planner, Goal.FORTRESS, -225, 0, 91, 219).x());
        assertEquals(200, stepAt(planner, Goal.FORTRESS, -225, 0, 220, 220).x());
    }

    @Test
    public void capOfCellsFails() {
        NetherSweepPlanner small = new NetherSweepPlanner(state, cfg, ROW, 3);
        // nothing ever gets closer and the bot drifts off, so every cell is a pocket drop after two stuck give-ups
        Action a = stepUntilDone(small, Goal.FORTRESS, 0);
        assertNotNull(a);
        assertEquals(Kind.FAIL, a.kind());
        assertEquals(3, small.cellsStarted(Goal.FORTRESS));
        // every cell was a pocket (nobody gets closer), so they are deferred, not visited
        assertEquals(3, state.visitedCells.size() + state.deferredCells.size());
    }

    @Test
    public void runningOutOfCellsFails() {
        NetherSweepPlanner none = new NetherSweepPlanner(state, cfg, new NetherSweepPlanner.Grid() {
            @Override
            public List<Point> waypoints(Cell cell, int spacingChunks, int px, int pz) {
                return List.of();
            }

            @Override
            public Optional<Cell> nextCell(int px, int pz, Set<String> visited) {
                return Optional.empty();
            }
        }, 12);
        assertEquals(Kind.FAIL, none.step(Goal.FORTRESS, 0, 0, 0).kind());
    }

    @Test
    public void emptyWaypointListsStillTerminate() {
        NetherSweepPlanner blank = new NetherSweepPlanner(state, cfg, new NetherSweepPlanner.Grid() {
            private int n;

            @Override
            public List<Point> waypoints(Cell cell, int spacingChunks, int px, int pz) {
                return List.of();
            }

            @Override
            public Optional<Cell> nextCell(int px, int pz, Set<String> visited) {
                return Optional.of(new Cell(n++, 0));
            }
        }, 12);
        assertEquals(Kind.FAIL, blank.step(Goal.FORTRESS, 0, 0, 0).kind());
        // cells that end without a step are free, the slack is what stops a grid that never runs out
        assertEquals(12 * NetherSweepPlanner.SKIPPED_CELL_SLACK, blank.cellsStarted(Goal.FORTRESS));
    }

    @Test
    public void givenUpFortressIsNotPickedUpAgain() {
        planner.report(Sight.FORTRESS, pos(150, 40));
        planner.giveUpFortress();
        assertTrue(state.fortress.isEmpty());
        assertNull(state.spawner);
        assertEquals(1, state.fortressExhausted.size());
        // its bricks are still in view, that must not count again, not even from the next block over the cell border
        assertFalse(planner.report(Sight.FORTRESS, pos(150, 40)));
        assertFalse(planner.report(Sight.FORTRESS, pos(300, 40)));
        assertTrue(state.fortress.isEmpty());
        // a fortress far away is fine
        assertTrue(planner.report(Sight.FORTRESS, pos(2000, 40)));
        assertEquals(1, state.fortress.size());
    }

    @Test
    public void givingUpOnASpawnerOnlyForgetsThatFortress() {
        planner.report(Sight.FORTRESS, pos(150, 40));
        planner.report(Sight.FORTRESS, pos(2000, 40));
        assertEquals(2, state.fortress.size());
        state.spawner = new RunState.Pos(160, 50, 40);
        planner.giveUpFortress(state.spawner);
        // the far one is still known and is the next stop
        assertEquals(List.of(pos(2000, 40)), state.fortress);
        assertEquals(List.of(pos(150, 40)), state.fortressExhausted);
        assertNull(state.spawner);
        // the dud one still does not come back
        assertFalse(planner.report(Sight.FORTRESS, pos(150, 40)));
    }

    @Test
    public void aSpawnerFarFromEverySightingStillCostsTheClosestFortress() {
        planner.report(Sight.FORTRESS, pos(150, 40));
        planner.report(Sight.FORTRESS, pos(2000, 40));
        planner.giveUpFortress(new RunState.Pos(600, 50, 40));
        assertEquals(List.of(pos(2000, 40)), state.fortress);
        assertEquals(List.of(pos(150, 40)), state.fortressExhausted);
    }

    @Test
    public void givingUpWithNoSpawnerPositionForgetsEveryFortress() {
        planner.report(Sight.FORTRESS, pos(150, 40));
        planner.report(Sight.FORTRESS, pos(2000, 40));
        planner.giveUpFortress(null);
        assertTrue(state.fortress.isEmpty());
        assertEquals(2, state.fortressExhausted.size());
    }

    @Test
    public void whatItRecordedSurvivesAJsonRoundTrip() {
        planner.report(Sight.FORTRESS, pos(150, 40));
        planner.report(Sight.BASTION, pos(-700, 10));
        planner.report(Sight.WARPED, pos(5, 6));
        planner.giveUpFortress();
        state.netherRodsGaveUp = true;
        RunState back = new Gson().fromJson(RunStateStore.toJson(state), RunState.class);
        assertEquals(state.visitedCells, back.visitedCells);
        assertEquals(state.fortressCells, back.fortressCells);
        assertEquals(state.bastionCells, back.bastionCells);
        assertEquals(state.bastion, back.bastion);
        assertEquals(state.fortressExhausted, back.fortressExhausted);
        assertEquals(state.warpedForest, back.warpedForest);
        assertTrue(back.netherRodsGaveUp);
        // a planner built on the loaded state still avoids that bastion and skips the visited cells (0,0 and -2,0)
        NetherSweepPlanner again = new NetherSweepPlanner(back, cfg, ROW, 12);
        assertTrue(again.nearBastion(-700, 10));
        assertEquals(1000, again.step(Goal.FORTRESS, 0, 0, 0).x());
    }

    @Test
    public void eachGoalHasItsOwnCellCap() {
        NetherSweepPlanner two = new NetherSweepPlanner(state, cfg, ROW, 2);
        Action a = stepUntilDone(two, Goal.FORTRESS, 0);
        assertEquals(Kind.FAIL, a.kind());
        // the fortress search used its two cells, the forest search still has its own two
        assertEquals(Kind.GOTO, two.step(Goal.WARPED, 500, 500, 20001).kind());
        assertEquals(1, two.cellsStarted(Goal.WARPED));
        assertEquals(2, two.cellsStarted(Goal.FORTRESS));
    }

    @Test
    public void idlingDoesNotEatTheWaypointClock() {
        cfg.waypointNoProgressSeconds = 0;
        // the cell starts 450 blocks away: a bot that never moved would not be given up on at all
        planner.step(Goal.FORTRESS, 225, 0, 0);
        assertEquals(0, planner.step(Goal.FORTRESS, -225, 0, 0).x());
        // rods, a ghast, whatever had the wheel for ten minutes: the waypoint is as fresh as it was
        assertEquals(0, planner.step(Goal.FORTRESS, -225, 0, 600).x());
        assertEquals(0, stepAt(planner, Goal.FORTRESS, -225, 0, 601, 689).x());
        // and then it times out normally, 90 s after it was picked up again
        assertEquals(100, stepAt(planner, Goal.FORTRESS, -225, 0, 690, 690).x());
    }

    @Test
    public void scanningOneKindRecordsOnlyThatKind() {
        Map<Sight, RunState.Pos> seen = new EnumMap<>(Sight.class);
        seen.put(Sight.FORTRESS, pos(150, 40));
        seen.put(Sight.BASTION, pos(2000, 40));
        assertTrue(planner.scan(seeing(seen), Sight.BASTION));
        assertTrue(state.fortress.isEmpty());
        assertEquals(1, state.bastion.size());
        assertFalse(planner.scan(seeing(seen), Sight.WARPED));
    }

    // the real grid: every cell is started once, the cap ends the search, nothing is revisited
    @Test
    public void realGridSweepTerminatesAtTheCapAndNeverRepeats() {
        NetherSweepPlanner real = new NetherSweepPlanner(state, cfg);
        Set<String> started = new java.util.LinkedHashSet<>();
        int px = 0;
        int pz = 0;
        double now = 0;
        int steps = 0;
        Action a;
        while (true) {
            a = real.step(Goal.FORTRESS, px, pz, now);
            if (a.kind() != Kind.GOTO) {
                break;
            }
            // the cell the waypoint is in is the cell being swept
            assertEquals(NetherComplexGrid.cellOf(a.x(), a.z()).key(), real.currentCellKey());
            started.add(real.currentCellKey());
            // walk there, it takes a second
            px = a.x();
            pz = a.z();
            now += 1;
            assertTrue("never ends", ++steps < 1000);
        }
        assertEquals(Kind.FAIL, a.kind());
        assertEquals(12, started.size());
        assertEquals(12, real.cellsStarted(Goal.FORTRESS));
        assertEquals(started, state.visitedCells);
    }

    @Test
    public void realGridSweepSkipsWhatIsAlreadyVisited() {
        NetherSweepPlanner real = new NetherSweepPlanner(state, cfg);
        Cell home = NetherComplexGrid.cellOf(0, 0);
        state.visitedCells.add(home.key());
        Action a = real.step(Goal.FORTRESS, 0, 0, 0);
        assertEquals(Kind.GOTO, a.kind());
        assertFalse(NetherComplexGrid.cellOf(a.x(), a.z()).equals(home));
    }

    // ---- stuck give-ups: one retry, then the pocket rule

    @Test
    public void firstStuckGiveUpRetriesTheSameWaypointAndFlagsIt() {
        // standing still: the 20 s rule fires at t=20
        begin(planner, 500, 500);
        assertEquals(0, stepAt(planner, Goal.FORTRESS, 500, 500, 0, 19).x());
        assertFalse(planner.takeGiveUp());
        assertEquals(0, planner.step(Goal.FORTRESS, 500, 500, 20).x());
        assertTrue(planner.takeGiveUp());
        // taken once
        assertFalse(planner.takeGiveUp());
        assertEquals("0,0", planner.currentCellKey());
    }

    @Test
    public void secondStuckGiveUpInTheSamePocketDropsTheCell() {
        begin(planner, 500, 500);
        stepAt(planner, Goal.FORTRESS, 500, 500, 0, 20);
        // the retry is just as stuck, from the same spot: the other waypoints are behind the same wall
        Action a = stepAt(planner, Goal.FORTRESS, 510, 505, 21, 41);
        assertEquals(Kind.GOTO, a.kind());
        assertEquals(1000, a.x());
        // a pocket defers the cell, it is not visited for good
        assertFalse(state.visitedCells.contains("0,0"));
        assertEquals(Integer.valueOf(0), state.deferredCells.get("0,0"));
        assertEquals("1,0", planner.currentCellKey());
    }

    @Test
    public void aBotThatNeverMovedIsNeverGivenUpOnOrBurnsACell() {
        // boxed in at the spot the cell started: stuck again and again, and the cell stays current and untouched
        for (int round = 0; round < 5; round++) {
            Action a = stepAt(planner, Goal.FORTRESS, 500, 500, round * 25, round * 25 + 24);
            assertEquals(Kind.GOTO, a.kind());
            assertEquals(0, a.x());
        }
        assertEquals("0,0", planner.currentCellKey());
        assertEquals(1, planner.cellsStarted(Goal.FORTRESS));
        assertTrue(state.visitedCells.isEmpty());
        assertTrue(state.deferredCells.isEmpty());
    }

    @Test
    public void aTimeoutWithoutMovingDoesNotKillTheWaypointEither() {
        cfg.waypointNoProgressSeconds = 0;
        // the hard cap (240 s) fires from the spot the cell started in: the same waypoint is asked for again, not skipped
        assertEquals(0, stepAt(planner, Goal.FORTRESS, 500, 500, 0, 300).x());
        assertEquals(1, planner.cellsStarted(Goal.FORTRESS));
    }

    @Test
    public void aBotThatMovedAwayFromTheStartIsGivenUpOnAsBefore() {
        begin(planner, 500, 500);
        stepAt(planner, Goal.FORTRESS, 500, 500, 0, 20);
        stepAt(planner, Goal.FORTRESS, 505, 500, 21, 41);
        assertEquals(Integer.valueOf(0), state.deferredCells.get("0,0"));
        assertEquals(2, planner.cellsStarted(Goal.FORTRESS));
    }

    @Test
    public void sixBlocksFromTheStartIsFarEnoughAndFiveIsNot() {
        // cell starts at (500,500), stuck five blocks off: pinned, nothing is given up on
        planner.step(Goal.FORTRESS, 500, 500, 0);
        stepAt(planner, Goal.FORTRESS, 505, 500, 1, 60);
        assertEquals(1, planner.cellsStarted(Goal.FORTRESS));
        assertTrue(state.deferredCells.isEmpty());
        // six off: a real walk, so the normal retry then pocket drop applies
        NetherSweepPlanner other = new NetherSweepPlanner(new RunState(), cfg, ROW, 12);
        other.step(Goal.FORTRESS, 500, 500, 0);
        stepAt(other, Goal.FORTRESS, 506, 500, 1, 60);
        assertEquals(2, other.cellsStarted(Goal.FORTRESS));
    }

    @Test
    public void walkingOffAndBeingBackedUpToTheStartIsNotNeverMoving() {
        begin(planner, 500, 500);
        planner.step(Goal.FORTRESS, 600, 500, 1);
        // back where the cell began, and stuck there: still the normal give-up rule
        stepAt(planner, Goal.FORTRESS, 450, 500, 2, 62);
        assertEquals(2, planner.cellsStarted(Goal.FORTRESS));
    }

    // cells 0..n-1 in a row, three waypoints each, the plain fresh-then-deferred default for the rest
    private static NetherSweepPlanner.Grid rowOf(int n) {
        return new NetherSweepPlanner.Grid() {
            @Override
            public List<Point> waypoints(Cell cell, int spacingChunks, int px, int pz) {
                return ROW.waypoints(cell, spacingChunks, px, pz);
            }

            @Override
            public Optional<Cell> nextCell(int px, int pz, Set<String> visited) {
                for (int cx = 0; cx < n; cx++) {
                    if (!visited.contains(new Cell(cx, 0).key())) {
                        return Optional.of(new Cell(cx, 0));
                    }
                }
                return Optional.empty();
            }
        };
    }

    @Test
    public void deferredCellGetsExactlyOneRevisitWithCleanMemory() {
        planner = new NetherSweepPlanner(state, cfg, rowOf(2), 12);
        begin(planner, 500, 500);
        stepAt(planner, Goal.FORTRESS, 500, 500, 0, 20);
        // pocket: cell 0 deferred, its first waypoint is dead and retried now
        assertEquals(1000, stepAt(planner, Goal.FORTRESS, 510, 505, 21, 41).x());
        assertEquals(Integer.valueOf(0), state.deferredCells.get("0,0"));
        // cell 1 walked out normally
        planner.step(Goal.FORTRESS, 1000, 0, 42);
        planner.step(Goal.FORTRESS, 1100, 0, 43);
        Action back = planner.step(Goal.FORTRESS, 1200, 0, 44);
        assertTrue(state.visitedCells.contains("1,0"));
        // nothing fresh is left: the deferred cell comes back, and its dead first waypoint is walkable again
        assertEquals(Kind.GOTO, back.kind());
        assertEquals(0, back.x());
        assertEquals("0,0", planner.currentCellKey());
        assertEquals(Integer.valueOf(1), state.deferredCells.get("0,0"));
        // a pocket on the revisit too: visited for good, nothing to come back to
        stepAt(planner, Goal.FORTRESS, 500, 500, 45, 65);
        Action end = stepAt(planner, Goal.FORTRESS, 505, 505, 66, 86);
        assertEquals(Kind.FAIL, end.kind());
        assertTrue(state.visitedCells.contains("0,0"));
        assertTrue(state.deferredCells.isEmpty());
    }

    @Test
    public void theCellJustDeferredSitsOutThePickItWasDeferredIn() {
        planner = new NetherSweepPlanner(state, cfg, rowOf(1), 12);
        begin(planner, 500, 500);
        stepAt(planner, Goal.FORTRESS, 500, 500, 0, 20);
        // the only cell is the one we are standing in: it is not handed straight back for a revisit from the same wall
        Action a = stepAt(planner, Goal.FORTRESS, 510, 505, 21, 41);
        assertEquals(Kind.FAIL, a.kind());
        assertEquals(Integer.valueOf(0), state.deferredCells.get("0,0"));
        assertFalse(state.visitedCells.contains("0,0"));
    }

    @Test
    public void revisitWalksAwayFromThePocketOnTheRealGrid() {
        Cell cell = new Cell(0, 0);
        NetherSweepPlanner real = new NetherSweepPlanner(state, cfg, NetherSweepPlanner.REAL_GRID, 12);
        // wedge against the low corner of cell 0 twice: pocket
        Action first = real.step(Goal.FORTRESS, 40, 40, 0);
        assertEquals("0,0", real.currentCellKey());
        assertEquals(new Point(72, 72), new Point(first.x(), first.z()));
        stepAt(real, Goal.FORTRESS, 20, 20, 1, 21);
        // (second give-up within 24 blocks of the first)
        stepAt(real, Goal.FORTRESS, 25, 25, 22, 42);
        assertEquals(Integer.valueOf(0), state.deferredCells.get(cell.key()));
        // pretend everything else is spoken for so the revisit is the only thing left
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                String k = new Cell(dx, dz).key();
                if (!k.equals(cell.key())) {
                    state.visitedCells.add(k);
                }
            }
        }
        state.netherPortal = new RunState.Pos(10, 64, 10);
        real.giveUpFortress();
        Action again = real.step(Goal.FORTRESS, 30, 30, 100);
        assertEquals("0,0", real.currentCellKey());
        // the corner farthest from the pocket at (25,25), not the one next to it
        assertEquals(new Point(296, 296), new Point(again.x(), again.z()));
    }

    @Test
    public void legSecondsScalesWithTheLegAndHasAFloor() {
        assertEquals(45, NetherSweepPlanner.legSeconds(0), 0);
        assertEquals(45, NetherSweepPlanner.legSeconds(100), 0);
        assertEquals(120, NetherSweepPlanner.legSeconds(300), 0);
        assertEquals(224 / 2.5, NetherSweepPlanner.legSeconds(224), 0.5);
        // a longer soft limit holds off the no progress verdict the old flat 90 would have given
        assertFalse(NetherSweepPlanner.softTimedOut(100, 10, 120, cfg));
        assertTrue(NetherSweepPlanner.softTimedOut(120, 10, 120, cfg));
        assertTrue(NetherSweepPlanner.softTimedOut(240, 0, 400, cfg));
    }

    @Test
    public void farSecondStuckGiveUpOnlyKillsThatWaypoint() {
        begin(planner, 500, 500);
        stepAt(planner, Goal.FORTRESS, 500, 500, 0, 20);
        // the retry got us somewhere else entirely (more than 24 blocks away) before it stalled again
        Action a = stepAt(planner, Goal.FORTRESS, 900, 900, 21, 41);
        assertEquals(100, a.x());
        assertFalse(state.visitedCells.contains("0,0"));
        assertEquals("0,0", planner.currentCellKey());
    }

    @Test
    public void pocketMemoryIsPerCell() {
        begin(planner, 500, 500);
        stepAt(planner, Goal.FORTRESS, 500, 500, 0, 20);
        // a drop picks cell 1; a stuck give-up there at the same spot is its first, not a second one
        stepAt(planner, Goal.FORTRESS, 500, 500, 21, 41);
        assertEquals("1,0", planner.currentCellKey());
        // 20 blocks off the spot it started in: still the same pocket spot, but a bot that has not moved never gives up at all
        Action a = stepAt(planner, Goal.FORTRESS, 500, 520, 42, 62);
        assertEquals(1000, a.x());
        assertEquals("1,0", planner.currentCellKey());
    }

    @Test
    public void aWalkStillClosingPastNinetySecondsKeepsGoing() {
        // 2 blocks a second towards the waypoint: 8 blocks better than the best every few seconds
        int t = 0;
        Action a = null;
        for (; t < 240; t++) {
            a = planner.step(Goal.FORTRESS, 5000 - 2 * t, 0, t);
            assertEquals("t=" + t, 0, a.x());
        }
        // the hard cap: even a crawl that never stalls is over at 240 s
        assertEquals(100, planner.step(Goal.FORTRESS, 5000 - 2 * t, 0, t).x());
    }

    @Test
    public void softTimeoutNeedsStalledProgressHardOneDoesNot() {
        assertFalse(NetherSweepPlanner.softTimedOut(89, 50, cfg));
        // past 90 s, but walking fine
        assertFalse(NetherSweepPlanner.softTimedOut(100, 2, cfg));
        assertFalse(NetherSweepPlanner.softTimedOut(200, 9.9, cfg));
        // past 90 s and half the stuck seconds already behind it
        assertTrue(NetherSweepPlanner.softTimedOut(100, 10, cfg));
        assertTrue(NetherSweepPlanner.softTimedOut(240, 0, cfg));
        cfg.waypointNoProgressSeconds = 0;
        assertTrue(NetherSweepPlanner.softTimedOut(90, 0, cfg));
        assertFalse(NetherSweepPlanner.softTimedOut(89, 0, cfg));
    }

    @Test
    public void idleGapRestartsBothTheWaypointClockAndTheStuckSeconds() {
        stepAt(planner, Goal.FORTRESS, 500, 500, 0, 15);
        // a long fight, then back: neither clock carries the gap, so no give-up on the first step back
        assertEquals(0, planner.step(Goal.FORTRESS, 500, 500, 1000).x());
        assertFalse(planner.takeGiveUp());
        assertEquals(0, stepAt(planner, Goal.FORTRESS, 500, 500, 1001, 1018).x());
        assertFalse(planner.takeGiveUp());
    }

    @Test
    public void cellsThatEndWithoutAStepDoNotUseUpTheCap() {
        // the first three cells have nowhere to walk (nothing to stand on, all behind a bastion), the fourth is a real one
        NetherSweepPlanner.Grid skippy = new NetherSweepPlanner.Grid() {
            @Override
            public List<Point> waypoints(Cell cell, int spacingChunks, int px, int pz) {
                return cell.cx() < 3 ? List.of() : ROW.waypoints(cell, spacingChunks, px, pz);
            }

            @Override
            public Optional<Cell> nextCell(int px, int pz, Set<String> visited) {
                return ROW.nextCell(px, pz, visited);
            }
        };
        NetherSweepPlanner two = new NetherSweepPlanner(state, cfg, skippy, 2);
        Action a = two.step(Goal.FORTRESS, 0, 0, 0);
        assertEquals(Kind.GOTO, a.kind());
        assertEquals("3,0", two.currentCellKey());
    }

    @Test
    public void aCellCountsOnceTheBotHasWalkedOffFromItsStart() {
        NetherSweepPlanner one = new NetherSweepPlanner(state, cfg, ROW, 1);
        one.step(Goal.FORTRESS, 500, 500, 0);
        // boxed in at the start: the cell is neither burned nor counted, the bot keeps being sent at it
        assertEquals(Kind.GOTO, stepAt(one, Goal.FORTRESS, 503, 500, 1, 200).kind());
        assertEquals("0,0", one.currentCellKey());
        // walked off and the cell ends: that was the one cell this planner may sweep
        one.step(Goal.FORTRESS, 560, 500, 201);
        one.step(Goal.FORTRESS, 0, 0, 202);
        one.step(Goal.FORTRESS, 100, 0, 203);
        assertEquals(Kind.FAIL, one.step(Goal.FORTRESS, 200, 0, 204).kind());
    }

    @Test
    public void onlyBricksOfAGivenUpFortressAreSkipped() {
        planner.report(Sight.FORTRESS, pos(500, 500));
        // a fortress we know still gets reported (its other cells are fortress cells too)
        assertFalse(planner.isGivenUp(pos(540, 500)));
        planner.giveUpFortress(pos(500, 500));
        // the dud's far corner is still the dud
        assertTrue(planner.isGivenUp(pos(500 + 150, 500)));
        assertFalse(planner.isGivenUp(pos(500 + 400, 500)));
    }

    @Test
    public void theWarpedSweepStaysWithinOneRingOfThePortalAndFailsPastIt() {
        state.netherPortal = pos(0, 0);
        // the cells on the leash (ROW only has cz 0, so cx 0 and 1) are done, ring two and out are all that is left
        state.visitedCells.add("0,0");
        state.visitedCells.add("1,0");
        Action a = planner.step(Goal.WARPED, 0, 0, 0);
        assertEquals(Kind.FAIL, a.kind());
        assertEquals("warped sweep: past the portal leash", a.why());
    }

    @Test
    public void theWarpedSweepStillTakesTheNearCellsAndTheFortressSweepIsNotLeashed() {
        state.netherPortal = pos(0, 0);
        assertEquals(Kind.GOTO, planner.step(Goal.WARPED, 0, 0, 0).kind());
        assertTrue(planner.cellsStarted(Goal.WARPED) == 1);

        NetherSweepPlanner far = new NetherSweepPlanner(state, cfg, ROW, 12);
        state.visitedCells.add("0,0");
        state.visitedCells.add("1,0");
        Action f = far.step(Goal.FORTRESS, 0, 0, 0);
        assertEquals(Kind.GOTO, f.kind());
        assertEquals(2000, f.x());
    }

    @Test
    public void theRealGridLeashNeverPicksARingTwoCell() {
        Point portal = new Point(0, 0);
        Cell home = NetherComplexGrid.cellOf(0, 0);
        java.util.Set<String> visited = new java.util.HashSet<>();
        // player far out east: the plain pick would go for the cells next to it
        for (int i = 0; i < 9; i++) {
            Optional<Cell> c = NetherComplexGrid.nextCellWithin(5000, 0, visited, Set.of(), portal, portal, 1);
            assertTrue(c.isPresent());
            assertTrue(NetherComplexGrid.inLeash(c.get(), home, 1));
            visited.add(c.get().key());
        }
        assertTrue(NetherComplexGrid.nextCellWithin(5000, 0, visited, Set.of(), portal, portal, 1).isEmpty());
    }

    @Test
    public void aFortressCellPastTheLeashIsLetGoWhenTheGoalFlipsToWarped() {
        state.netherPortal = pos(0, 0);
        state.visitedCells.add("0,0");
        state.visitedCells.add("1,0");
        // the fortress sweep is walking cell 2,0 (ring two)
        assertEquals(2000, planner.step(Goal.FORTRESS, 0, 0, 0).x());
        Action a = planner.step(Goal.WARPED, 0, 0, 1);
        assertEquals(Kind.FAIL, a.kind());
        assertFalse(state.visitedCells.contains("2,0"));
    }

    @Test
    public void aBlockNearAKnownBastionIsKnownAndAFarOneIsNot() {
        planner.report(Sight.BASTION, pos(250, 0));
        assertTrue(planner.isKnownBastion(pos(250 + 63, 0)));
        assertFalse(planner.isKnownBastion(pos(250 + 65, 0)));
    }
}
