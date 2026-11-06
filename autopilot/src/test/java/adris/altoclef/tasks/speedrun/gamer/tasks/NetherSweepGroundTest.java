package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.NetherConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Action;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Goal;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Kind;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Look;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Spot;
import adris.altoclef.world.NetherComplexGrid.Cell;
import adris.altoclef.world.NetherComplexGrid.Point;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// the planner against a fake ground and a fake clock: what lava does to a waypoint and what a stuck walker does to it
public class NetherSweepGroundTest {
    // one cell, three waypoints along x: 0, 100, 200 (z 0). the cell is only visited when it is finished
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
    private final List<String> lines = new ArrayList<>();
    private int looks;
    private BiFunction<Integer, Integer, Spot> world = (x, z) -> new Spot(Look.FINE, x, z);

    @Before
    public void setUp() {
        state = new RunState();
        cfg = new NetherConfig();
        planner = new NetherSweepPlanner(state, cfg, ROW, 12);
        planner.setLog(lines::add);
        planner.setGround((x, z) -> {
            looks++;
            return world.apply(x, z);
        });
    }

    // the cell starts on the mirror side of the same leg: a bot that never left the spot where its cell began is never
    // given up on (see pinned), so the stuck walks below start somewhere else
    private void begin(int px) {
        planner.step(Goal.FORTRESS, -px, 0, 0);
    }

    // once a second like the task does
    private Action walk(int px, int pz, int from, int to) {
        Action a = null;
        for (int t = from; t <= to; t++) {
            a = planner.step(Goal.FORTRESS, px, pz, t);
        }
        return a;
    }

    @Test
    public void aLavaWaypointIsMovedToTheNearestStandableColumn() {
        world = (x, z) -> x == 0 ? new Spot(Look.SHIFTED, 7, 3) : new Spot(Look.FINE, x, z);
        Action a = walk(-300, 0, 0, 0);
        assertEquals(Kind.GOTO, a.kind());
        assertEquals(7, a.x());
        assertEquals(3, a.z());
        assertEquals(List.of("sweep: waypoint 0 0 is over lava, using 7 3"), lines);
    }

    @Test
    public void nothingStandableInTheRingSkipsTheWaypoint() {
        world = (x, z) -> x == 0 ? new Spot(Look.NOWHERE, x, z) : new Spot(Look.FINE, x, z);
        Action a = walk(-300, 0, 0, 0);
        assertEquals(100, a.x());
        assertEquals(List.of("sweep: waypoint 0 0 has nowhere to stand, skipping"), lines);
    }

    @Test
    public void aSkippedWaypointNeverComesBackWhenTheCellIsPickedUpAgain() {
        world = (x, z) -> x == 0 ? new Spot(Look.NOWHERE, x, z) : new Spot(Look.FINE, x, z);
        walk(-300, 0, 0, 0);
        // the cell was not finished, so it is not visited and the grid hands it out again with all three waypoints
        planner.giveUpFortress();
        Action again = walk(-300, 0, 1, 1);
        assertEquals(100, again.x());
    }

    @Test
    public void anUnloadedChunkIsAPlainWalkUntilItLoads() {
        boolean[] loaded = {false};
        world = (x, z) -> loaded[0] ? new Spot(Look.SHIFTED, 5, 5) : new Spot(Look.UNLOADED, x, z);
        assertEquals(0, walk(-300, 0, 0, 5).x());
        assertTrue(lines.isEmpty());
        loaded[0] = true;
        Action a = walk(-300, 0, 6, 6);
        assertEquals(5, a.x());
        assertEquals(5, a.z());
    }

    @Test
    public void theDecisionIsMadeOnceAndSticks() {
        boolean[] lava = {true};
        world = (x, z) -> x == 0 && lava[0] ? new Spot(Look.SHIFTED, 7, 3) : new Spot(Look.FINE, x, z);
        walk(-300, 0, 0, 0);
        int asked = looks;
        // the world changes its mind, the planner does not
        lava[0] = false;
        Action a = walk(-290, 0, 1, 10);
        assertEquals(7, a.x());
        assertEquals(asked, looks);
        assertEquals(1, lines.size());
    }

    @Test
    public void aFineColumnIsLookedAtOnceToo() {
        walk(-300, 0, 0, 0);
        int asked = looks;
        walk(-290, 0, 1, 15);
        assertEquals(asked, looks);
        assertTrue(lines.isEmpty());
    }

    @Test
    public void noCloserInTwentySecondsGivesTheWaypointUp() {
        begin(-300);
        // t 0 is the first look, 20 more walking seconds without getting closer
        assertEquals(0, walk(-300, 0, 0, 19).x());
        // the first time is a retry of the same waypoint, nothing logged
        assertEquals(0, walk(-300, 0, 20, 20).x());
        assertTrue(lines.isEmpty());
        // stuck again from the same spot: same pocket, the cell is dropped
        Action a = walk(-300, 0, 21, 41);
        assertEquals(1000, a.x());
        assertEquals(List.of("sweep: gave up on 0 0, no closer in 20 s (300 away)"), lines);
    }

    @Test
    public void gettingEightBlocksCloserKeepsTheWaypointAlive() {
        // eight closer every fifteen seconds: always progress, never stuck (the 90 s backstop is its own test)
        int px = -300;
        for (int t = 0; t < 90; t++) {
            if (t > 0 && t % 15 == 0) {
                px += 8;
            }
            assertEquals("t=" + t, 0, planner.step(Goal.FORTRESS, px, 0, t).x());
        }
    }

    @Test
    public void aSlowCrawlStillHitsTheNinetySecondBackstop() {
        int px = -225;
        for (int t = 0; t < 90; t++) {
            if (t > 0 && t % 15 == 0) {
                px += 8;
            }
            planner.step(Goal.FORTRESS, px, 0, t);
        }
        assertEquals(100, planner.step(Goal.FORTRESS, px, 0, 90).x());
        assertTrue(lines.get(0).startsWith("sweep: gave up on 0 0, not there after 90 s"));
    }

    @Test
    public void aWaypointGivenUpOnIsNeverHandedBack() {
        begin(-300);
        walk(-300, 0, 0, 20);
        walk(-300, 0, 21, 41);
        // the pocket deferred it, take that back so the cell is fresh again and only the dead waypoint is left to check
        state.deferredCells.remove("0,0");
        planner.giveUpFortress();
        assertEquals(100, walk(-300, 0, 42, 42).x());
    }

    @Test
    public void timeSpentOnSomethingElseDoesNotCountAsNoCloser() {
        begin(-300);
        walk(-300, 0, 0, 10);
        // a fight, a ghast, a pick craft: the sweep is not asked for a minute
        assertEquals(0, walk(-300, 0, 70, 70).x());
        // the clock starts over after a gap, 19 walking seconds later it is still alive and the twentieth drops it
        assertEquals(0, walk(-300, 0, 71, 89).x());
        assertEquals(0, walk(-300, 0, 90, 90).x());
        assertEquals(1000, walk(-300, 0, 91, 111).x());
    }

    @Test
    public void beingDraggedFurtherAwayByAnInterruptionIsNotHeldAgainstTheWaypoint() {
        walk(-300, 0, 0, 15);
        // the fight ended 40 blocks further out than the best we had
        assertEquals(0, walk(-340, 0, 60, 60).x());
        assertEquals(0, walk(-340, 0, 61, 78).x());
        assertEquals(0, walk(-340, 0, 79, 80).x());
        assertEquals(1000, walk(-340, 0, 81, 101).x());
    }

    @Test
    public void aWaypointThatKeepsGettingInterruptedIsStillGivenUpOnEventually() {
        begin(-300);
        walk(-300, 0, 0, 15);
        walk(-300, 0, 60, 75);
        walk(-300, 0, 120, 135);
        // the third gap no longer wipes the 15 stuck seconds, so five more walking seconds are enough
        assertEquals(0, walk(-300, 0, 200, 204).x());
        assertEquals(0, walk(-300, 0, 205, 205).x());
        assertEquals(1000, walk(-300, 0, 206, 226).x());
    }

    @Test
    public void aLongInterruptionBeforeAnyWalkingIsNotHeldAgainstTheWaypoint() {
        walk(-300, 0, 0, 0);
        assertEquals(0, walk(-300, 0, 60, 70).x());
        assertEquals(0, walk(-300, 0, 71, 78).x());
    }

    @Test
    public void switchingTheNoProgressRuleOffLeavesOnlyTheBackstop() {
        cfg.waypointNoProgressSeconds = 0;
        begin(-225);
        assertEquals(0, walk(-225, 0, 0, 89).x());
        assertEquals(100, walk(-225, 0, 90, 90).x());
    }
}
