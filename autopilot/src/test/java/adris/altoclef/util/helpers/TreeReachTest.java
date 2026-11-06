package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.TreeReach.Cell;
import adris.altoclef.util.helpers.TreeReach.Column;
import org.junit.Test;

import java.util.function.IntFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TreeReachTest {
    private static final int FLOOR = 63;

    // flat ground at FLOOR, optionally with a layer of grass on it
    private static IntFunction<Cell> ground(boolean cover) {
        return y -> y <= FLOOR ? Cell.SOLID : (cover && y == FLOOR + 1 ? Cell.BLOCKING : Cell.AIR);
    }

    @Test
    public void aLeafUnderTheLogEndsTheAirCountAndTheGroundIsStillTheGround() {
        // leaves right under a canopy log: not a place to stand, so the log is as far from the feet as the ground says
        int log = FLOOR + 8;
        Column c = TreeReach.column(log, y -> y == log - 1 ? Cell.BLOCKING : ground(false).apply(y));
        assertEquals(0, c.airCells());
        assertEquals(FLOOR + 1, c.feetY());
        assertFalse(TreeReach.fromGround(log, c, false));
        // but a leaf in a shaft that is otherwise short is just something to cut on the way
        int low = FLOOR + 3;
        assertTrue(TreeReach.fromGround(low, TreeReach.column(low, y -> y == low - 1 ? Cell.BLOCKING : ground(false).apply(y)), false));
    }

    @Test
    public void aMinePostIsBuried() {
        // planks right over it and the surface way up there
        assertTrue(TreeReach.buried(2, 69, y -> y == 3 || y > 60));
    }

    @Test
    public void aTallTreeHasOnlyItselfOverIt() {
        // deep by the heightmap, and nothing in the way that isn't the tree (leaves and trunk read as not solid)
        assertFalse(TreeReach.buried(64, 94, y -> false));
    }

    @Test
    public void aLedgeWithinTheDepthIsStillATree() {
        assertFalse(TreeReach.buried(64, 64 + TreeReach.BURIED_DEPTH, y -> true));
        // one further and it takes the ceiling to matter
        assertTrue(TreeReach.buried(64, 64 + TreeReach.BURIED_DEPTH + 1, y -> true));
    }

    @Test
    public void flatWorldNumbersAreNotBuried() {
        // no y limit: a tree at the bottom of the world is a tree
        assertFalse(TreeReach.buried(-60, -55, y -> true));
    }

    @Test
    public void theTopOfTheColumnIsNotBuried() {
        assertFalse(TreeReach.buried(70, 70, y -> true));
        // and a heightmap that reads lower than the log (no data from the server) turns the rule off instead of tripping it
        assertFalse(TreeReach.buried(70, 40, y -> true));
    }

    @Test
    public void aDeepOverhangReadsBuriedAndThatIsTheDeal() {
        // a tree under a cliff more than the depth overhead. rare, and there are other trees
        assertTrue(TreeReach.buried(64, 90, y -> y == 85));
    }

    @Test
    public void onlyAskedAboutTheCellsOverTheLog() {
        // the log's own cell and anything past the top are not part of the question
        assertFalse(TreeReach.buried(64, 90, y -> y == 64 || y > 90));
        assertTrue(TreeReach.buried(64, 90, y -> y == 90));
    }

    @Test
    public void theColumnStopsOnTheFirstThingThatIsNotAir() {
        Column bare = TreeReach.column(FLOOR + 8, ground(false));
        assertEquals(FLOOR + 1, bare.feetY());
        assertEquals(7, bare.airCells());
        // grass is stood in, and it is not open air for the shaft
        Column grass = TreeReach.column(FLOOR + 8, ground(true));
        assertEquals(FLOOR + 1, grass.feetY());
        assertEquals(6, grass.airCells());
    }

    @Test
    public void waterIsStoodInAndLavaIsNoPlace() {
        assertEquals(60, TreeReach.column(70, y -> y <= 60 ? Cell.WATER : Cell.AIR).feetY());
        assertEquals(TreeReach.NO_FEET, TreeReach.column(70, y -> y <= 60 ? Cell.LAVA : Cell.AIR).feetY());
        assertEquals(TreeReach.NO_FEET, TreeReach.column(70, y -> Cell.AIR).feetY());
    }

    @Test
    public void theShaftReachesFiveAirCellsUp() {
        assertTrue(TreeReach.fromGround(FLOOR + 1 + TreeReach.MAX_UNDER, TreeReach.column(FLOOR + 1 + TreeReach.MAX_UNDER, ground(false)), false));
        int past = FLOOR + 2 + TreeReach.MAX_UNDER;
        assertFalse(TreeReach.fromGround(past, TreeReach.column(past, ground(false)), false));
    }

    @Test
    public void groundCoverCostsTheShaftACell() {
        // the goal counts only air, so with grass on the floor the same log is one cell further away than it looks
        int log = FLOOR + 1 + TreeReach.MAX_UNDER;
        assertTrue(TreeReach.fromGround(log, TreeReach.column(log, ground(false)), false));
        assertFalse(TreeReach.fromGround(log, TreeReach.column(log, ground(true)), false));
        // and the clamp at two keeps the short ones working
        int low = FLOOR + 3;
        assertTrue(TreeReach.fromGround(low, TreeReach.column(low, ground(true)), false));
    }

    @Test
    public void aSideToStandOnIsAlwaysReach() {
        Column none = new Column(TreeReach.NO_FEET, 3);
        assertTrue(TreeReach.fromGround(FLOOR + 20, none, true));
        assertFalse(TreeReach.fromGround(FLOOR + 20, none, false));
    }

    @Test
    public void aLogWithinAStepOfTheFeetIsASideCase() {
        // feet at the log's height or one under it: the side cells, nothing to tower whatever the air count says
        assertTrue(TreeReach.fromGround(70, new Column(70, 0), false));
        assertTrue(TreeReach.fromGround(70, new Column(69, 0), false));
    }

    @Test
    public void gapIsFeetToLogAndUnknownIsAsFarAsItGets() {
        assertEquals(7, TreeReach.gap(FLOOR + 8, TreeReach.column(FLOOR + 8, ground(false))));
        assertEquals(Integer.MAX_VALUE, TreeReach.gap(70, new Column(TreeReach.NO_FEET, 0)));
    }

    @Test
    public void onlyAFlaggedLogOutOfReachWithNoMeansIsSkipped() {
        // flagged, can't be had from the ground
        assertTrue(TreeReach.skip(true, false, false, false));
        // a tower, or dirt to build one from, makes it fair again
        assertFalse(TreeReach.skip(true, false, true, false));
        assertFalse(TreeReach.skip(true, false, false, true));
        // the shaft gets it
        assertFalse(TreeReach.skip(true, true, false, false));
        // an intact trunk is not flagged, whatever the rest says. it opens up as it is cut from the bottom
        assertFalse(TreeReach.skip(false, false, false, false));
    }

    @Test
    public void oneBlockInTheBagStillOwesTheRestOfTheTower() {
        int gap = 8;
        assertEquals(TreeReach.dirtWanted(gap), TreeReach.dirtShortfall(gap, 0));
        assertEquals(TreeReach.dirtWanted(gap) - 1, TreeReach.dirtShortfall(gap, 1));
        assertEquals(0, TreeReach.dirtShortfall(gap, TreeReach.dirtWanted(gap)));
        assertEquals(0, TreeReach.dirtShortfall(gap, 64));
    }

    @Test
    public void dirtWantedFollowsTheClimbWithinReason() {
        assertEquals(4, TreeReach.dirtWanted(0));
        assertEquals(4, TreeReach.dirtWanted(3));
        assertEquals(7, TreeReach.dirtWanted(6));
        assertEquals(10, TreeReach.dirtWanted(40));
        assertEquals(8, TreeReach.dirtWanted(Integer.MAX_VALUE));
    }
}
