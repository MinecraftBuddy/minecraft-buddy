package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.junit.Test;

public class LandFrontierTest {

    @Test
    public void aRiverThroughAChunkLeavesItLand() {
        assertFalse(LandFrontier.isWater(6, 9));
        assertTrue(LandFrontier.isWater(7, 9));
        assertTrue(LandFrontier.isWater(9, 9));
        assertFalse(LandFrontier.isWater(0, 0));
    }

    @Test
    public void onlyLandWeSawOpensAFrontier() {
        assertTrue(LandFrontier.frontierOk(true, false, false));
        // a coast: land wins
        assertTrue(LandFrontier.frontierOk(true, true, false));
        assertFalse(LandFrontier.frontierOk(false, true, false));
        // the unknown next to unknown case, this is the leak out to sea
        assertFalse(LandFrontier.frontierOk(false, false, false));
    }

    @Test
    public void anOldChunkCountsUnlessWaterIsRightThere() {
        assertTrue(LandFrontier.frontierOk(false, false, true));
        assertFalse(LandFrontier.frontierOk(false, true, true));
    }

    @Test
    public void openWaterNeedsAllOfIt() {
        assertTrue(LandFrontier.openWater(true, false, 32, false));
        assertTrue(LandFrontier.openWater(true, false, Double.POSITIVE_INFINITY, false));
        assertFalse(LandFrontier.openWater(false, false, 100, false));
        // sunk to the sea floor, reads as ground
        assertFalse(LandFrontier.openWater(true, true, 100, false));
        assertFalse(LandFrontier.openWater(true, false, 31, false));
    }

    @Test
    public void aRiverWithAShoreInRangeIsNoTurnBack() {
        assertFalse(LandFrontier.openWater(true, false, 40, true));
    }

    @Test
    public void unstickWandersDoNotTurnBack() {
        assertFalse(LandFrontier.turnsBack(5));
        assertTrue(LandFrontier.turnsBack(64));
        assertTrue(LandFrontier.turnsBack(Float.POSITIVE_INFINITY));
    }

    @Test
    public void crossingHeadsAwayFromTheCentroid() {
        assertEquals(new LandFrontier.Spot(1124, 0), LandFrontier.crossingTarget(100, 0, 0, 0));
        assertEquals(new LandFrontier.Spot(0, -1124), LandFrontier.crossingTarget(0, -100, 0, 0));
    }

    @Test
    public void standingOnTheCentroidStillGoesSomewhere() {
        assertEquals(new LandFrontier.Spot(1024, 7), LandFrontier.crossingTarget(0, 7, 0, 7));
    }

    @Test
    public void reanchorWantsDryLandAndSomethingToExplore() {
        assertTrue(LandFrontier.reanchor(true, true));
        // swimming past land we can see, we have to actually get there
        assertFalse(LandFrontier.reanchor(false, true));
        // still walking across the island we are leaving
        assertFalse(LandFrontier.reanchor(true, false));
    }

    @Test
    public void banTakesWhatWasAheadAndLeavesTheInlandSide() {
        ChunkPos sea = new ChunkPos(5, 0);
        ChunkPos inland = new ChunkPos(-5, 0);
        // dry land at the origin, swam out toward +x
        assertEquals(List.of(sea), LandFrontier.toBan(0, 0, 50, 0, List.of(sea, inland)));
    }

    @Test
    public void banAlwaysTakesOne() {
        ChunkPos near = new ChunkPos(-1, 5);
        ChunkPos far = new ChunkPos(-9, 9);
        // nothing ahead of a swim toward +x, the closest one pays
        assertEquals(List.of(near), LandFrontier.toBan(0, 0, 50, 0, List.of(far, near)));
        assertTrue(LandFrontier.toBan(0, 0, 50, 0, List.of()).isEmpty());
    }
}
