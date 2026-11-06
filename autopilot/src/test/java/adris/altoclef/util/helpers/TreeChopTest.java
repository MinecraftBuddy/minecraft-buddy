package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.TreeChop.StripStep;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TreeChopTest {

    @Test
    public void onTheTreeTheLogBeatsACloseDrop() {
        // drop 2 blocks off, next log 4 up: the old rule took the drop every time
        assertFalse(TreeChop.dropWins(true, false, false, 4, 16));
    }

    @Test
    public void offATreeTheCloseDropStillWins() {
        assertTrue(TreeChop.dropWins(false, false, false, 4, 16));
        // and a far drop still loses to a close block
        assertFalse(TreeChop.dropWins(false, false, false, 100, 4));
        // but a drop a lot closer than the block wins from further than 6
        assertTrue(TreeChop.dropWins(false, false, false, 49, 100));
    }

    @Test
    public void aDespawningDropWinsOnTheTreeToo() {
        assertTrue(TreeChop.dropWins(true, true, false, 4, 16));
    }

    @Test
    public void aFullBagGoesForTheDrop() {
        assertTrue(TreeChop.dropWins(true, false, true, 4, 16));
    }

    @Test
    public void noDropNeverWinsAndNoBlockAlwaysLoses() {
        assertFalse(TreeChop.dropWins(true, true, true, Double.POSITIVE_INFINITY, 16));
        assertTrue(TreeChop.dropWins(true, false, false, 400, Double.POSITIVE_INFINITY));
    }

    @Test
    public void floorCoveringTheCountStopsTheChop() {
        assertTrue(TreeChop.enough(3, 2, 5));
        assertTrue(TreeChop.enough(0, 6, 5));
        assertFalse(TreeChop.enough(3, 1, 5));
    }

    @Test
    public void bannedOrDespawningDropsDontCount() {
        assertTrue(TreeChop.countable(false, 20));
        assertFalse(TreeChop.countable(true, 20));
        assertFalse(TreeChop.countable(false, MineStick.DESPAWN_SOON_AGE));
    }

    @Test
    public void onATowerTheHighestLogInReachGoesFirst() {
        // trunk 64..70, feet at 68 on a tower beside it, reach stops at 70
        List<BlockPos> tree = List.of(new BlockPos(0, 64, 0), new BlockPos(0, 66, 0), new BlockPos(0, 69, 0), new BlockPos(0, 70, 0), new BlockPos(0, 72, 0));
        BlockPos pick = TreeChop.topDown(tree, 68, at -> at.getY() <= 70);
        assertEquals(new BlockPos(0, 70, 0), pick);
    }

    @Test
    public void theTrunkGoesAllTheWayDownFromTheAnchor() {
        // the anchor is the log just broken up at hand height, the base of the tree is way under it
        BlockPos anchor = new BlockPos(0, 74, 0);
        assertTrue(TreeChop.inTrunk(anchor, new BlockPos(0, 64, 0)));
        assertTrue(TreeChop.inTrunk(anchor, new BlockPos(1, 80, -2)));
        assertFalse(TreeChop.inTrunk(anchor, new BlockPos(3, 74, 0)));
        List<BlockPos> tree = List.of(new BlockPos(0, 64, 0), new BlockPos(0, 73, 0), new BlockPos(0, 75, 0));
        assertEquals(new BlockPos(0, 75, 0), TreeChop.topDown(tree, 72, at -> true));
    }

    @Test
    public void onTheGroundTopDownStaysOut() {
        List<BlockPos> tree = List.of(new BlockPos(0, 64, 0), new BlockPos(0, 65, 0), new BlockPos(0, 66, 0));
        assertNull(TreeChop.topDown(tree, 64, at -> true));
        assertNull(TreeChop.topDown(tree, 66, at -> true));
        assertEquals(new BlockPos(0, 66, 0), TreeChop.topDown(tree, 67, at -> true));
    }

    @Test
    public void topDownWithNothingInReachIsNull() {
        List<BlockPos> tree = List.of(new BlockPos(0, 64, 0), new BlockPos(0, 80, 0));
        assertNull(TreeChop.topDown(tree, 70, at -> false));
        assertNull(TreeChop.topDown(List.of(), 70, at -> true));
    }

    @Test
    public void onlyACanopyDropOnLeavesGetsItsLeafCut() {
        assertTrue(TreeChop.cutLeafUnder(4, true));
        assertFalse(TreeChop.cutLeafUnder(4, false));
        assertFalse(TreeChop.cutLeafUnder(1, true));
    }

    @Test
    public void noPathWithNothingToPlaceGoesForDirt() {
        assertTrue(TreeChop.noPathDirt(true, false, false, 0, 1));
    }

    @Test
    public void noPathAfterTheTripOrWithBlocksIsABan() {
        // tower blocks in the bag and still no path: the tower isn't the problem
        assertFalse(TreeChop.noPathDirt(true, true, false, 0, 1));
        // the trip happened and it still has no path
        assertFalse(TreeChop.noPathDirt(true, false, false, 1, 1));
        // the last trip fell short
        assertFalse(TreeChop.noPathDirt(true, false, true, 0, 1));
        // placing off, dirt is no use
        assertFalse(TreeChop.noPathDirt(false, false, false, 0, 1));
    }

    @Test
    public void aGiantGetsASecondTrip() {
        assertEquals(1, TreeChop.tripsFor(6));
        assertEquals(1, TreeChop.tripsFor(TreeReach.DIRT_MAX));
        assertEquals(2, TreeChop.tripsFor(TreeReach.DIRT_MAX + 3));
        assertEquals(1, TreeChop.tripsFor(Integer.MAX_VALUE));
        assertTrue(TreeChop.noPathDirt(true, false, false, 1, TreeChop.tripsFor(14)));
        assertFalse(TreeChop.noPathDirt(true, false, false, 2, TreeChop.tripsFor(14)));
    }

    @Test
    public void onlyAHighLogIsWorthADirtTrip() {
        assertFalse(TreeChop.highUp(0));
        assertFalse(TreeChop.highUp(TreeReach.MAX_UNDER));
        assertTrue(TreeChop.highUp(TreeReach.MAX_UNDER + 1));
        // no floor under it at all, nothing to swing from either
        assertTrue(TreeChop.highUp(Integer.MAX_VALUE));
    }

    @Test
    public void strippedLogGetsBroken() {
        assertEquals(StripStep.BREAK, TreeChop.stripStep(true, true, false));
    }

    @Test
    public void barkStillOnKeepsStripping() {
        assertEquals(StripStep.STRIP, TreeChop.stripStep(true, false, true));
    }

    @Test
    public void airMovesOnToTheNextLog() {
        assertEquals(StripStep.NEXT, TreeChop.stripStep(true, false, false));
        assertEquals(StripStep.NEXT, TreeChop.stripStep(false, false, false));
    }

    @Test
    public void theNextStripIsTheNearestLogOfTheSameTree() {
        // the way CollectStrippedLogTask.nextInTree asks: from the log we just broke, in its MineAnchor box
        BlockPos last = new BlockPos(0, 64, 0);
        List<BlockPos> known = List.of(new BlockPos(5, 64, 0), new BlockPos(1, 67, 0), new BlockPos(0, 65, 0));
        assertEquals(new BlockPos(0, 65, 0), MineAnchor.nearest(last, known, true, 0.5, 64.5, 0.5, at -> true).orElse(null));
        // the other tree is not in the box, so with this one done it's the caller's global nearest
        assertTrue(MineAnchor.nearest(last, List.of(new BlockPos(5, 64, 0)), true, 0.5, 64.5, 0.5, at -> true).isEmpty());
    }

    @Test
    public void noDirtTripFromDeepDownAMine() {
        assertFalse(TreeChop.dirtDepthOk(30, 8));
        assertFalse(TreeChop.dirtDepthOk(9, 8));
    }

    @Test
    public void dirtTripStillFineNearTheSurface() {
        assertTrue(TreeChop.dirtDepthOk(8, 8));
        assertTrue(TreeChop.dirtDepthOk(0, 8));
    }
}
