package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.util.baritone.GoalColumnDown;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RoomCoverageTest {
    @Test
    public void aChunkCountsWhenWeAreNearItsMiddleAtTheRightLevel() {
        // chunk (2, -1) has its middle at (40, -8)
        assertTrue(RoomCoverage.covers(40, 30, -8, 2, -1, 30));
        assertTrue(RoomCoverage.covers(40 + 23, 30 + 12, -8, 2, -1, 30));
        assertFalse(RoomCoverage.covers(40 + 25, 30, -8, 2, -1, 30));
        // a tunnel two levels above or below does not see the room
        assertFalse(RoomCoverage.covers(40, 30 + 13, -8, 2, -1, 30));
        assertFalse(RoomCoverage.covers(40, 30 - 13, -8, 2, -1, 30));
        // no working level yet = nothing counts
        assertFalse(RoomCoverage.covers(40, 30, -8, 2, -1, Double.NaN));
    }

    @Test
    public void followsTheNearestUnvisitedChunkWithSeenBricks() {
        List<int[]> bricks = new ArrayList<>();
        bricks.add(new int[]{8, 8});      // chunk 0,0
        bricks.add(new int[]{40, 8});     // chunk 2,0
        bricks.add(new int[]{-100, -100}); // chunk -7,-7 (-100 / 16 floors to -7)
        Set<String> visited = new HashSet<>();
        Optional<int[]> next = RoomCoverage.nearestUnvisitedBrickChunk(bricks, visited, 0, 0, 0, 0, 7);
        assertArrayEquals(new int[]{0, 0}, next.get());
        visited.add("0,0");
        assertArrayEquals(new int[]{2, 0}, RoomCoverage.nearestUnvisitedBrickChunk(bricks, visited, 0, 0, 0, 0, 7).get());
        visited.add("2,0");
        assertArrayEquals(new int[]{-7, -7}, RoomCoverage.nearestUnvisitedBrickChunk(bricks, visited, 0, 0, 0, 0, 7).get());
        visited.add("-7,-7");
        assertTrue(RoomCoverage.nearestUnvisitedBrickChunk(bricks, visited, 0, 0, 0, 0, 7).isEmpty());
    }

    @Test
    public void bricksOutsideTheSearchSquareAreIgnored() {
        List<int[]> bricks = List.of(new int[]{16 * 20, 0});
        assertTrue(RoomCoverage.nearestUnvisitedBrickChunk(bricks, Set.of(), 0, 0, 0, 0, 7).isEmpty());
        assertTrue(RoomCoverage.nearestUnvisitedBrickChunk(List.of(), Set.of(), 0, 0, 0, 0, 7).isEmpty());
    }

    @Test
    public void digEndsOnBricksNotOnDepth() {
        // surface, mid, and one block above the floor: still digging until bricks show
        assertEquals(RoomCoverage.DigVerdict.KEEP_DIGGING, RoomCoverage.digVerdict(false, 70, -54, true));
        assertEquals(RoomCoverage.DigVerdict.KEEP_DIGGING, RoomCoverage.digVerdict(false, 9, -54, true));
        assertEquals(RoomCoverage.DigVerdict.BRICKS, RoomCoverage.digVerdict(true, 40, -54, true));
        assertEquals(RoomCoverage.DigVerdict.BRICKS, RoomCoverage.digVerdict(true, -54, -54, true));
    }

    @Test
    public void reachingTheFloorWithoutBricksFallsBack() {
        assertEquals(RoomCoverage.DigVerdict.FLOOR, RoomCoverage.digVerdict(false, -52, -54, true));
        assertEquals(RoomCoverage.DigVerdict.FLOOR, RoomCoverage.digVerdict(false, -60, -54, true));
    }

    @Test
    public void workYIsTheBrickLevelNotTheDigEnd() {
        assertEquals(31, RoomCoverage.workY(OptionalInt.of(31), -54), 0);
        assertEquals(-54, RoomCoverage.workY(OptionalInt.empty(), -54), 0);
    }

    @Test
    public void aTimedOutChunkIsSkippedNotCovered() {
        Set<String> visited = new HashSet<>();
        Set<String> skipped = new HashSet<>();
        assertTrue(RoomCoverage.giveUp(visited, skipped, "3,4"));
        assertTrue(skipped.contains("3,4"));
        assertFalse(visited.contains("3,4"));
        // one markCovered did get is not skipped
        visited.add("5,5");
        assertFalse(RoomCoverage.giveUp(visited, skipped, "5,5"));
        assertFalse(skipped.contains("5,5"));
    }

    @Test
    public void brickFilterRunsBeforeTheCap() {
        BlockPos origin = new BlockPos(0, 60, 0);
        List<BlockPos> tracked = new ArrayList<>();
        // a village on the surface right next to us, more than the cap of it
        for (int i = 1; i <= 300; i++) {
            tracked.add(new BlockPos(i % 10, 70, i / 10));
        }
        // and the real stronghold bricks, further away and lower
        tracked.add(new BlockPos(60, 20, 0));
        tracked.add(new BlockPos(1000, 20, 0));
        List<BlockPos> kept = RoomCoverage.brickCandidates(tracked, 56, true, 0, 0, 140, Double.NaN);
        assertEquals(1, kept.size());
        assertEquals(60, kept.get(0).getX());
        assertEquals(60, StrongholdScan.nearest(kept, origin, StrongholdScan.MAX_SCAN).get(0).getX());
        // without a start nothing is cut by distance
        assertEquals(2, RoomCoverage.brickCandidates(tracked, 56, false, 0, 0, 140, Double.NaN).size());
        // bricks a level away from us are not "we are inside"
        assertEquals(1, RoomCoverage.brickCandidates(tracked, 56, true, 0, 0, 140, 30).size());
        assertEquals(0, RoomCoverage.brickCandidates(tracked, 56, false, 0, 0, 140, 60).size());
    }

    @Test
    public void digGoalIsPinnedToTheStartColumn() {
        GoalColumnDown goal = new GoalColumnDown(996, 1684, -54);
        assertTrue(goal.isInGoal(996, -54, 1684));
        assertTrue(goal.isInGoal(996, -60, 1684));
        assertFalse(goal.isInGoal(996, -53, 1684));
        // the same depth in another column is not it
        assertFalse(goal.isInGoal(1040, -54, 1684));
        assertTrue(goal.heuristic(1040, 9, 1684) > goal.heuristic(996, 9, 1684));
        assertEquals(0, goal.heuristic(996, -54, 1684), 0);
    }

    @Test
    public void nearestSortsByDistanceAndCaps() {
        BlockPos origin = new BlockPos(0, 0, 0);
        List<BlockPos> many = new ArrayList<>();
        for (int i = 300; i >= 1; i--) {
            many.add(new BlockPos(i, 0, 0));
        }
        List<BlockPos> near = StrongholdScan.nearest(many, origin, 200);
        assertEquals(200, near.size());
        assertEquals(1, near.get(0).getX());
        assertEquals(200, near.get(199).getX());
        // the input is untouched
        assertEquals(300, many.get(0).getX());
        // fewer than the cap just comes back sorted
        assertEquals(3, StrongholdScan.nearest(List.of(new BlockPos(9, 0, 0), new BlockPos(3, 0, 0)), origin, 200).get(0).getX());
    }

    @Test
    public void standingStillForTwentySecondsIsAStall() {
        RoomCoverage.Stall stall = new RoomCoverage.Stall();
        assertFalse(stall.stalled(0, 0, 0, 100));
        assertFalse(stall.stalled(1, 0, 1, 119));
        assertTrue(stall.stalled(1, 0, 1, 120));
    }

    @Test
    public void movingMoreThanTwoBlocksReAnchors() {
        RoomCoverage.Stall stall = new RoomCoverage.Stall();
        assertFalse(stall.stalled(0, 0, 0, 0));
        assertFalse(stall.stalled(3, 0, 0, 19));
        assertFalse(stall.stalled(3, 0, 0, 38));
        assertTrue(stall.stalled(3, 0, 0, 39));
        stall.reset();
        assertFalse(stall.stalled(3, 0, 0, 40));
    }

    @Test
    public void aLegOnlyEndsOnCoveredFinishedTimerOrStall() {
        assertFalse(RoomCoverage.legOver(false, false, false, false, false));
        assertTrue(RoomCoverage.legOver(true, false, false, false, false));
        assertTrue(RoomCoverage.legOver(false, true, false, false, false));
        assertTrue(RoomCoverage.legOver(false, false, true, false, false));
        assertTrue(RoomCoverage.legOver(false, false, false, true, false));
        assertTrue(RoomCoverage.legOver(false, false, false, false, true));
    }

    @Test
    public void aFloorOffTheColumnIsNotAFloor() {
        // the caves took us eight blocks around the staircase: keep going, the column is where the answer is
        assertEquals(RoomCoverage.DigVerdict.KEEP_DIGGING, RoomCoverage.digVerdict(false, -52, -54, false));
        assertEquals(RoomCoverage.DigVerdict.BRICKS, RoomCoverage.digVerdict(true, -52, -54, false));
        assertEquals(RoomCoverage.DigVerdict.FLOOR, RoomCoverage.digVerdict(false, -52, -54, true));
    }

    @Test
    public void theColumnIsWithinThreeOfTheMiddle() {
        assertTrue(RoomCoverage.onColumn(996.5, 1684.5, 996, 1684));
        assertTrue(RoomCoverage.onColumn(998.4, 1685.4, 996, 1684));
        assertFalse(RoomCoverage.onColumn(1004.5, 1687.5, 996, 1684));
        assertFalse(RoomCoverage.onColumn(1000.5, 1684.5, 996, 1684));
    }

    @Test
    public void segmentsStepDownEightAtATimeOnTheColumn() {
        // from the surface at 79: first goal 71, never under the floor
        assertEquals(71, RoomCoverage.firstSegmentY(79, -54));
        assertEquals(-54, RoomCoverage.firstSegmentY(-50, -54));
        // not there yet, or there but off the column: the goal stays
        assertEquals(71, RoomCoverage.nextSegmentY(71, 75.2, true, -54));
        assertEquals(71, RoomCoverage.nextSegmentY(71, 70, false, -54));
        // standing at it (feet y 71.0, or just under) on the column: next 8 down
        assertEquals(63, RoomCoverage.nextSegmentY(71, 71.0, true, -54));
        assertEquals(62, RoomCoverage.nextSegmentY(71, 70.5, true, -54));
        // fell past it, the step counts from where we are
        assertEquals(52, RoomCoverage.nextSegmentY(71, 60.0, true, -54));
        // the last one clamps to the floor and stays there
        assertEquals(-54, RoomCoverage.nextSegmentY(-50, -50.0, true, -54));
        assertEquals(-54, RoomCoverage.nextSegmentY(-54, -54.0, true, -54));
    }

    @Test
    public void aDigThatNeverGotDownIsStuck() {
        assertTrue(RoomCoverage.stuckDig(79, 78, 56));
        assertTrue(RoomCoverage.stuckDig(79, 72, 56));
        // dropped 8 but still up where no stronghold is
        assertTrue(RoomCoverage.stuckDig(79, 71, 56));
        assertFalse(RoomCoverage.stuckDig(79, 50, 56));
        assertFalse(RoomCoverage.stuckDig(79, 20, 56));
        // a high start that dropped 8 and is still above the band
        assertTrue(RoomCoverage.stuckDig(120, 100, 56));
    }

    @Test
    public void theDigOutcomeFollowsTheConfigBudget() {
        RoomCoverage.DigOutcome keep = RoomCoverage.DigOutcome.KEEP_DIGGING;
        // within budget: keep going, whatever the budget is (source default 420, a run config 240)
        assertEquals(keep, RoomCoverage.digOutcome(false, 300, 420, 79, 78, 56));
        assertEquals(RoomCoverage.DigOutcome.FAIL, RoomCoverage.digOutcome(false, 300, 240, 79, 78, 56));
        // bricks win over everything, even a stuck-looking dig
        assertEquals(RoomCoverage.DigOutcome.SPIRAL, RoomCoverage.digOutcome(true, 421, 420, 79, 78, 56));
        // timed out deep on the column with no bricks keeps the old behaviour
        assertEquals(RoomCoverage.DigOutcome.SPIRAL, RoomCoverage.digOutcome(false, 421, 420, 79, 10, 56));
    }

    @Test
    public void aCaveFallNeverTurnsAStuckDigIntoASpiral() {
        // the column only got to 70 (the fall to 41 is not passed in), so the budget ends the attempt, not the spiral
        assertEquals(RoomCoverage.DigOutcome.FAIL, RoomCoverage.digOutcome(false, 421, 420, 77, 70, 56));
        // 8 under entry but still above the stronghold band: also a fail
        assertEquals(RoomCoverage.DigOutcome.FAIL, RoomCoverage.digOutcome(false, 421, 420, 120, 100, 56));
    }

    @Test
    public void onlyYsOnTheColumnCount() {
        RoomCoverage.DigWatch w = new RoomCoverage.DigWatch(77, 0);
        w.update(996.5, 61.2, 1684.5, 996, 1684, 15);
        assertEquals(61, w.columnLowestY());
        // fell into a cave 4 blocks off the column to 41
        w.update(1000.5, 41.0, 1684.5, 996, 1684, 20);
        assertEquals(61, w.columnLowestY());
        // within 1.5 counts
        w.update(997.5, 60.0, 1684.5, 996, 1684, 25);
        assertEquals(60, w.columnLowestY());
    }

    @Test
    public void theWatchdogWantsANewLowEveryMinute() {
        RoomCoverage.DigWatch w = new RoomCoverage.DigWatch(77, 0);
        assertFalse(w.stuck(59));
        assertTrue(w.stuck(60));
        // a new low resets the clock, standing still or falling off the column does not
        w.update(996.5, 70, 1684.5, 996, 1684, 50);
        assertFalse(w.stuck(109));
        w.update(1004.5, 41, 1684.5, 996, 1684, 100);
        assertTrue(w.stuck(110));
        w.update(996.5, 70, 1684.5, 996, 1684, 115);
        assertTrue(w.stuck(115));
    }

    @Test
    public void theWatchdogTriesEachCellOnceThenGivesUp() {
        RoomCoverage.DigWatch w = new RoomCoverage.DigWatch(77, 0);
        assertArrayEquals(new int[]{998, 1684}, w.shift(996, 1684, 60));
        assertArrayEquals(new int[]{994, 1684}, w.shift(996, 1684, 120));
        assertArrayEquals(new int[]{996, 1686}, w.shift(996, 1684, 180));
        assertArrayEquals(new int[]{996, 1682}, w.shift(996, 1684, 240));
        assertNull(w.shift(996, 1684, 300));
        // every cell is still inside the staircase around the original middle
        for (int[] c : RoomCoverage.FOOTPRINT) {
            assertTrue(RoomCoverage.onColumn(996 + c[0] + 0.5, 1684 + c[1] + 0.5, 996, 1684));
        }
    }

    @Test
    public void aShiftedCellHasToBeatTheOldLowToResetTheClock() {
        RoomCoverage.DigWatch w = new RoomCoverage.DigWatch(77, 0);
        w.update(996.5, 62, 1684.5, 996, 1684, 10);
        w.shift(996, 1684, 60);
        assertFalse(w.stuck(119));
        // a higher spot on the new cell is not progress, only getting under the old low is
        w.update(998.5, 70, 1684.5, 998, 1684, 70);
        assertTrue(w.stuck(120));
        w.update(998.5, 61, 1684.5, 998, 1684, 100);
        assertFalse(w.stuck(159));
        assertTrue(w.stuck(160));
        assertEquals(61, w.columnLowestY());
    }

    @Test
    public void theFloorIsJudgedAgainstTheOriginalCentre() {
        // standing on a shifted cell (x+2) is still on the original column, so the floor verdict stands
        assertEquals(RoomCoverage.DigVerdict.FLOOR,
                RoomCoverage.digVerdict(false, -52, -54, RoomCoverage.onColumn(998.5, 1684.5, 996, 1684)));
        // and a cave 8 away is not
        assertEquals(RoomCoverage.DigVerdict.KEEP_DIGGING,
                RoomCoverage.digVerdict(false, -52, -54, RoomCoverage.onColumn(1004.5, 1684.5, 996, 1684)));
    }
    @Test
    public void aNewWorkLevelWipesBothSets() {
        Set<String> visited = new HashSet<>(Set.of("62,105", "63,105"));
        Set<String> skipped = new HashSet<>(Set.of("64,105"));
        assertFalse(RoomCoverage.clearForNewLevel(visited, skipped, -52, -52));
        assertEquals(2, visited.size());
        assertTrue(RoomCoverage.clearForNewLevel(visited, skipped, -52, 31));
        assertTrue(visited.isEmpty());
        assertTrue(skipped.isEmpty());
        // the first level we ever learn counts as a change too
        visited.add("1,1");
        assertTrue(RoomCoverage.clearForNewLevel(visited, skipped, Double.NaN, 31));
        assertTrue(visited.isEmpty());
        // and a NaN that stays NaN is not one
        visited.add("1,1");
        assertFalse(RoomCoverage.clearForNewLevel(visited, skipped, Double.NaN, Double.NaN));
    }

    @Test
    public void walkLevelIsTheMedianOfNearbyBricksPlusOne() {
        // a ceiling brick nearest, floor bricks around it: the median ignores the odd one
        List<BlockPos> bricks = List.of(new BlockPos(1, 34, 0), new BlockPos(2, 30, 0), new BlockPos(3, 30, 1),
                new BlockPos(4, 31, 0), new BlockPos(5, 30, 2));
        assertEquals(31, RoomCoverage.walkLevel(bricks, 0, 0).getAsInt());
        // too far horizontally to vote (50 away): the nearest alone
        assertEquals(40, RoomCoverage.walkLevel(List.of(new BlockPos(50, 40, 0), new BlockPos(51, 20, 0)), 0, 0).getAsInt());
        assertTrue(RoomCoverage.walkLevel(List.of(), 0, 0).isEmpty());
        // the far ones are not counted when near ones exist
        List<BlockPos> mixed = List.of(new BlockPos(2, 30, 0), new BlockPos(60, 10, 0), new BlockPos(61, 10, 0));
        assertEquals(30, RoomCoverage.walkLevel(mixed, 0, 0).getAsInt());
        // two voters is not a median: a floor and a ceiling brick must not give a level inside the ceiling
        List<BlockPos> two = List.of(new BlockPos(1, 30, 0), new BlockPos(2, 34, 0));
        assertEquals(30, RoomCoverage.walkLevel(two, 0, 0).getAsInt());
    }

    @Test
    public void corridorCellsAreStandableSpotsBesideSeenBricks() {
        // a wall brick at (0,30,0): the air east of it is standable, the rest is rock
        Set<BlockPos> air = Set.of(new BlockPos(1, 30, 0), new BlockPos(-1, 29, 0));
        List<BlockPos> cells = RoomCoverage.corridorCells(List.of(new BlockPos(0, 30, 0)), air::contains);
        assertEquals(2, cells.size());
        assertTrue(cells.contains(new BlockPos(1, 30, 0)));
        // a floor brick gives the cell on top of it
        List<BlockPos> floor = RoomCoverage.corridorCells(List.of(new BlockPos(0, 29, 0)), new BlockPos(0, 30, 0)::equals);
        assertEquals(List.of(new BlockPos(0, 30, 0)), floor);
        assertTrue(RoomCoverage.corridorCells(List.of(new BlockPos(0, 30, 0)), p -> false).isEmpty());
    }

    @Test
    public void corridorPickSkipsVisitedAndPrefersOutward() {
        RoomCoverage.Visited visited = new RoomCoverage.Visited();
        BlockPos near = new BlockPos(4, 30, 0);
        BlockPos far = new BlockPos(-12, 30, 0);
        // standing at the origin: near wins when both are unvisited
        assertEquals(near, RoomCoverage.pickCorridorCell(List.of(near, far), visited, 0, 30, 0, 0, 0, 30).get());
        // been within 4 blocks of it: the other one
        visited.add(4, 30, 0);
        assertEquals(far, RoomCoverage.pickCorridorCell(List.of(near, far), visited, 0, 30, 0, 0, 0, 30).get());
        visited.add(-12, 30, 0);
        assertTrue(RoomCoverage.pickCorridorCell(List.of(near, far), visited, 0, 30, 0, 0, 0, 30).isEmpty());
        // the push outward breaks ties: both 10 from us, the staircase is at z 20 so the -10 one is the way out
        RoomCoverage.Visited fresh = new RoomCoverage.Visited();
        BlockPos inward = new BlockPos(0, 30, 10);
        BlockPos outward = new BlockPos(0, 30, -10);
        assertEquals(outward, RoomCoverage.pickCorridorCell(List.of(inward, outward), fresh, 0.5, 30, 0.5, 0.5, 20.5, 30).get());
        // a cell on another level is not this search's
        assertTrue(RoomCoverage.pickCorridorCell(List.of(new BlockPos(2, 60, 0)), fresh, 0, 30, 0, 0, 0, 30).isEmpty());
    }

    @Test
    public void visitedStaysBounded() {
        RoomCoverage.Visited visited = new RoomCoverage.Visited();
        for (int i = 0; i < RoomCoverage.Visited.CAP + 500; i++) {
            visited.add(i * 2, 0, 0);
        }
        assertEquals(RoomCoverage.Visited.CAP, visited.size());
        assertFalse(visited.near(0, 0, 0));
        assertTrue(visited.near((RoomCoverage.Visited.CAP + 499) * 2, 0, 0));
    }

    @Test
    public void aLegEndsOnExhaustedOrTheSecondTimeOut() {
        assertFalse(RoomCoverage.noPathEnds(false, 0));
        assertFalse(RoomCoverage.noPathEnds(false, 1));
        assertTrue(RoomCoverage.noPathEnds(false, 2));
        assertTrue(RoomCoverage.noPathEnds(true, 0));
        assertFalse(RoomCoverage.noPathCascade(3));
        assertTrue(RoomCoverage.noPathCascade(4));
    }
}
