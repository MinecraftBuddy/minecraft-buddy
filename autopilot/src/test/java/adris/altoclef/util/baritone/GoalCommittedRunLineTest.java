package adris.altoclef.util.baritone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import adris.altoclef.util.helpers.Tuning;
import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

// Tuning run_break_line (off by default): under skeleton fire the committed run's search adds run_break_line_cost ticks to a
// cell's estimate for each shooter within 18 with a clear line from its eye to the eye of the buddy standing in the cell. the
// shooters and the cells are fixed at the search's start, a line is cast once per shooter and cell. off: the heuristic as it was
public class GoalCommittedRunLineTest {

    private static final double C = GoalCommittedRun.LINE_COST;
    // the feet on y 64, a skeleton's eye 1.74 over its feet on the same floor
    private static final double SKEL_EYE = 64 + 1.74;
    private Path tuning;
    private String tuningWas;

    // open everywhere but the cells set solid
    private static final class World implements Cells {
        final Set<Long> solid = new HashSet<>();

        World wall(int x, int y0, int y1, int z0, int z1) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    solid.add(GoalFlee.pack(x, y, z));
                }
            }
            return this;
        }

        @Override
        public Cell at(int x, int y, int z) {
            return solid.contains(GoalFlee.pack(x, y, z)) ? Cell.SOLID : Cell.OPEN;
        }
    }

    @Before
    public void on() throws Exception {
        tuningWas = System.getProperty("buddy.tuning");
        tuning = Files.createTempFile("tuning", ".properties");
        System.setProperty("buddy.tuning", tuning.toString());
        set(true);
    }

    @After
    public void off() throws Exception {
        Files.writeString(tuning, "");
        Tuning.now("run_break_line", 0);
        Files.deleteIfExists(tuning);
        if (tuningWas == null) {
            System.clearProperty("buddy.tuning");
        } else {
            System.setProperty("buddy.tuning", tuningWas);
        }
        Tuning.now("run_break_line", 0);
    }

    private void set(boolean on) throws Exception {
        Files.writeString(tuning, "run_break_line=" + (on ? 1 : 0) + "\n");
        assertEquals(on ? 1 : 0, Tuning.now("run_break_line", 0), 0);
    }

    // a run from the origin (0, 0) with nobody chasing, on the world's cells, the shooters asked from the list given
    private static GoalCommittedRun run(World world, List<net.minecraft.world.entity.Entity> shooters, boolean[] asked) {
        GoalCommittedRun goal = new GoalCommittedRun(0, 0, 24, 14, List::of, () -> world, false, () -> {
            if (asked != null) asked[0] = true;
            return shooters;
        });
        goal.refresh();
        return goal;
    }

    private static GoalCommittedRun plain(World world) {
        GoalCommittedRun goal = new GoalCommittedRun(0, 0, 24, 14, List::of, () -> world);
        goal.refresh();
        return goal;
    }

    private static GoalCommittedRun.Shooters at(double... xz) {
        int n = xz.length / 2;
        double[] x = new double[n], y = new double[n], z = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = xz[2 * i];
            y[i] = SKEL_EYE;
            z[i] = xz[2 * i + 1];
        }
        return new GoalCommittedRun.Shooters(x, y, z);
    }

    // what the line adds at (x, 64, z) over the plain run's estimate
    private static double added(GoalCommittedRun goal, GoalCommittedRun plain, int x, int z) {
        return goal.heuristic(x, 64, z) - plain.heuristic(x, 64, z);
    }

    @Test
    public void offLeavesTheHeuristicAsItWas() throws Exception {
        set(false);
        World world = new World();
        boolean[] asked = {false};
        GoalCommittedRun goal = run(world, List.of(), asked);
        GoalCommittedRun plain = plain(world);
        // (shooters as if refresh had seen them: the search still prices nothing with the key off)
        goal.fireAt(at(10.5, 0.5));
        goal.searchStarts();
        assertNull(goal.line());
        for (int x = -4; x <= 8; x += 2) {
            assertEquals(plain.heuristic(x, 64, 0), goal.heuristic(x, 64, 0), 0);
        }
        goal.searchEnds();
        // with the key off the shooters are not even asked
        assertFalse(asked[0]);
    }

    @Test
    public void aCellInTheLineCostsCMoreThanOneBehindABlock() {
        // a wall at x 6 hides z 3..7 from a skeleton at (10.5, 0.5); (2, 0) stands in the open in its line
        World world = new World().wall(6, 63, 67, 3, 7);
        GoalCommittedRun goal = run(world, List.of(), null);
        GoalCommittedRun plain = plain(world);
        goal.fireAt(at(10.5, 0.5));
        goal.searchStarts();
        assertEquals(C, added(goal, plain, 2, 0), 1e-9);
        assertEquals(0, added(goal, plain, 2, 5), 1e-9);
        goal.searchEnds();
    }

    @Test
    public void twoShootersInLineCostTwiceC() {
        World world = new World();
        GoalCommittedRun goal = run(world, List.of(), null);
        GoalCommittedRun plain = plain(world);
        goal.fireAt(at(10.5, 0.5, -6.5, 4.5));
        goal.searchStarts();
        assertEquals(2 * C, added(goal, plain, 2, 0), 1e-9);
        goal.searchEnds();
    }

    @Test
    public void beyondEighteenNoCostAndNoRay() {
        World world = new World();
        GoalCommittedRun goal = run(world, List.of(), null);
        GoalCommittedRun plain = plain(world);
        goal.fireAt(at(10.5, 0.5));
        goal.searchStarts();
        // 20 blocks off: out of its reach, nothing cast
        assertEquals(0, added(goal, plain, -10, 0), 1e-9);
        assertEquals(0, goal.line().rays());
        // 17 off and in the open: in line
        assertEquals(C, added(goal, plain, -7, 0), 1e-9);
        goal.searchEnds();
    }

    @Test
    public void theMemoGivesTheSameAnswerWithoutASecondRay() {
        World world = new World();
        GoalCommittedRun goal = run(world, List.of(), null);
        goal.fireAt(at(10.5, 0.5));
        goal.searchStarts();
        GoalCommittedRun.Line line = goal.line();
        assertNotNull(line);
        assertEquals(C, line.cost(2, 64, 0), 1e-9);
        assertEquals(1, line.rays());
        // a block put in the way after the search began: the search keeps its first answer, and casts no second line
        world.wall(6, 63, 67, -2, 2);
        assertEquals(C, line.cost(2, 64, 0), 1e-9);
        assertEquals(1, line.rays());
        goal.searchEnds();
        assertNull(goal.line());
    }

    @Test
    public void theShootersAreTheOnesTheSearchStartedWith() {
        World world = new World();
        GoalCommittedRun goal = run(world, List.of(), null);
        GoalCommittedRun plain = plain(world);
        goal.fireAt(at(10.5, 0.5));
        goal.searchStarts();
        // refresh mid search (the key on, nobody under fire now): this search still prices the shooter it began with
        goal.refresh();
        assertEquals(C, added(goal, plain, 2, 0), 1e-9);
        goal.searchEnds();
        // the next search sees nobody
        goal.searchStarts();
        assertNull(goal.line());
        assertEquals(0, added(goal, plain, 2, 0), 1e-9);
        goal.searchEnds();
    }

    @Test
    public void noCellsNoPrice() {
        GoalCommittedRun goal = new GoalCommittedRun(0, 0, 24, 14, List::of, null, false, List::of);
        GoalCommittedRun bare = new GoalCommittedRun(0, 0, 24, 14, List::of);
        goal.refresh();
        bare.refresh();
        goal.fireAt(at(10.5, 0.5));
        goal.searchStarts();
        assertNull(goal.line());
        assertEquals(bare.heuristic(2, 64, 0), goal.heuristic(2, 64, 0), 0);
        goal.searchEnds();
    }
}
