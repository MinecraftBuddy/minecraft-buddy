package adris.altoclef.tasks.construction;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaceSpotRankTest {

    private static double score(double distSq, boolean hasBelow, int dy, boolean canScaffold) {
        return PlaceSpotRank.score(distSq, false, hasBelow, false, dy, canScaffold);
    }

    @Test
    public void airWithNoFloorAndNothingToBuildItOneIsRejected() {
        assertEquals(Double.POSITIVE_INFINITY, score(1, false, 0, false), 0);
    }

    @Test
    public void airWithNoFloorIsFineWhenAThrowawayCanBuildOne() {
        assertTrue(score(1, false, 0, true) < Double.POSITIVE_INFINITY);
    }

    @Test
    public void aSupportedSpotNeedsNoThrowaway() {
        assertTrue(score(4, true, 0, false) < Double.POSITIVE_INFINITY);
    }

    @Test
    public void aFloorStillBeatsAnEquallyCloseDrop() {
        assertTrue(score(4, true, 0, true) < score(4, false, 0, true));
    }

    @Test
    public void ourOwnLevelBeatsAnEquallyCloseClimb() {
        assertTrue(score(4, true, 0, false) < score(4, true, 2, false));
        assertTrue(score(4, true, 0, false) < score(4, true, -2, false));
    }

    @Test
    public void aSupportedSpotNextToUsBeatsAFarAwayOneEvenOnTheSameLevel() {
        assertTrue(score(1, true, 0, false) < score(36, true, 0, false));
    }

    @Test
    public void theOldScoreIsWhatItWas() {
        // distance, +4 for a block already there, +10 for no floor, +3 for inside us
        assertEquals(5, PlaceSpotRank.oldScore(5, false, true, false), 0);
        assertEquals(9, PlaceSpotRank.oldScore(5, true, true, false), 0);
        assertEquals(15, PlaceSpotRank.oldScore(5, false, false, false), 0);
        assertEquals(8, PlaceSpotRank.oldScore(5, false, true, true), 0);
    }

    // feet at 6,50,-200 (the shaft from the log)
    private static boolean pillars(int x, int y, int z, boolean hasBelow) {
        return PlaceSpotRank.wouldPillar(x, y, z, 6, 50, -200, hasBelow);
    }

    @Test
    public void ourOwnFeetAndHeadCellsAreNeverPicked() {
        assertTrue(pillars(6, 50, -200, true));
        assertTrue(pillars(6, 51, -200, true));
        assertTrue(pillars(6, 50, -200, false));
    }

    @Test
    public void aCellAboveOurFeetWithNothingUnderItNeedsPillaring() {
        // 6,52 is what the builder was climbing to after 6,51 was struck
        assertTrue(pillars(6, 52, -200, false));
        assertTrue(pillars(7, 51, -200, false));
    }

    @Test
    public void aSupportedCellAboveOurFeetIsAFloorAlready() {
        // the top of a ledge next to us at head height
        assertFalse(pillars(7, 51, -200, true));
    }

    @Test
    public void cellsAtOrBelowFeetLevelNeedNoPillar() {
        assertFalse(pillars(7, 50, -200, false));
        assertFalse(pillars(7, 49, -200, false));
        assertFalse(pillars(6, 49, -200, true));
    }

    @Test
    public void theColumnAboveTheHeadCellIsOnlyOutWhenItHasNoFloor() {
        assertTrue(pillars(6, 52, -200, false));
        assertFalse(pillars(6, 52, -200, true));
    }

    @Test
    public void floatingRulesOutOurOwnColumnBelowUs() {
        // feet at 6 50 -200, bobbing in water
        assertTrue(PlaceSpotRank.underFloatingFeet(6, 49, -200, 6, 50, -200, false));
        assertTrue(PlaceSpotRank.underFloatingFeet(6, 47, -200, 6, 50, -200, false));
        // the cell next to the column is fair game, so is anything at or above our feet (wouldPillar handles those)
        assertFalse(PlaceSpotRank.underFloatingFeet(7, 49, -200, 6, 50, -200, false));
        assertFalse(PlaceSpotRank.underFloatingFeet(6, 50, -200, 6, 50, -200, false));
        assertFalse(PlaceSpotRank.underFloatingFeet(6, 51, -200, 6, 50, -200, false));
    }

    @Test
    public void standingOnSomethingTheColumnBelowIsNotOurBusiness() {
        assertFalse(PlaceSpotRank.underFloatingFeet(6, 49, -200, 6, 50, -200, true));
    }

    @Test
    public void rejectionIsOnlyInTheNewRankSoTheFallbackStillHasAnAnswer() {
        assertTrue(PlaceSpotRank.oldScore(1, false, false, false) < Double.POSITIVE_INFINITY);
    }
}
