package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the walk estimate (still what drops and the pack-up trip are priced in) and the one straight line the stations use
public class WalkCostTest {
    @Test
    public void flatWalkIsJustTheDistance() {
        assertEquals(10.0, WalkCost.estimate(10, 0, 0), 1e-9);
        assertEquals(5.0, WalkCost.estimate(3, 0, 4), 1e-9);
    }

    @Test
    public void everyBlockOfHeightCostsFourFlatOnes() {
        assertEquals(4.0, WalkCost.estimate(0, 1, 0), 1e-9);
        // up and down are the same trip
        assertEquals(WalkCost.estimate(2, 3, 0), WalkCost.estimate(2, -3, 0), 1e-9);
        assertEquals(14.0, WalkCost.estimate(2, -3, 0), 1e-9);
    }

    @Test
    public void withinIsInclusiveOnTheBudgetItIsGiven() {
        assertTrue(WalkCost.within(20, 0, 0, 20));
        assertFalse(WalkCost.within(20.1, 0, 0, 20));
        // five up is exactly 20 of walk, six is a loss
        assertTrue(WalkCost.within(0, 5, 0, 20));
        assertFalse(WalkCost.within(0, 6, 0, 20));
    }

    @Test
    public void aDropWeCouldCraftIsOnlyWorthAShortWalk() {
        // sticks on the surface, planks in the bag: 5 up is 20, not worth it. 8 flat is
        assertFalse(WalkCost.dropWorthWalking(0, 5, 0, true));
        assertFalse(WalkCost.dropWorthWalking(0, 2.1, 0, true));
        assertTrue(WalkCost.dropWorthWalking(8, 0, 0, true));
        assertFalse(WalkCost.dropWorthWalking(8.1, 0, 0, true));
        // our own mining drops are right at our feet
        assertTrue(WalkCost.dropWorthWalking(3, 1, 0, true));
    }

    @Test
    public void aDropWeCannotCraftStillHasACap() {
        assertTrue(WalkCost.dropWorthWalking(30, 0, 0, false));
        assertTrue(WalkCost.dropWorthWalking(0, 8, 0, false));
        assertFalse(WalkCost.dropWorthWalking(0, 9, 0, false));
        assertFalse(WalkCost.dropWorthWalking(40, 0, 0, false));
        assertTrue(WalkCost.dropBudget(true) < WalkCost.dropBudget(false));
    }

    @Test
    public void theStationLinesAreTwentyOneAndOneTwentyEight() {
        assertEquals(21.0, WalkCost.STATION_NEAR, 0);
        assertEquals(128.0, WalkCost.STATION_FORGET, 0);
        // forgetting has to come well after reusing or a station would be dropped the moment it stopped being near
        assertTrue(WalkCost.STATION_FORGET > WalkCost.STATION_NEAR);
    }

    @Test
    public void distance3dIsThePlainStraightLine() {
        assertEquals(5.0, WalkCost.distance3d(3, 0, 4), 1e-9);
        assertEquals(5.0, WalkCost.distance3d(0, 3, 4), 1e-9);
        assertEquals(Math.sqrt(3) * 10, WalkCost.distance3d(10, 10, 10), 1e-9);
        // sign does not matter on any axis
        assertEquals(WalkCost.distance3d(1, 2, 3), WalkCost.distance3d(-1, -2, -3), 1e-9);
        assertEquals(0.0, WalkCost.distance3d(0, 0, 0), 0);
    }

    // height used to cost four flat blocks each, now it is just one more axis of the same line
    @Test
    public void heightCountsAsOneAxisNotFour() {
        // 15 across and 15 up is 21.2 away, one hair past
        assertEquals(21.213, WalkCost.distance3d(15, 15, 0), 1e-3);
        assertFalse(WalkCost.nearStation(15, 15, 0));
        assertFalse(WalkCost.nearStation(15, -15, 0));
        // ...while 10 / 10 / 10 is 17.3
        assertTrue(WalkCost.nearStation(10, 10, 10));
        assertTrue(WalkCost.nearStation(-10, -10, -10));
        // five up used to be 20 of walk and the edge of reuse, it is 5 of line
        assertTrue(WalkCost.nearStation(0, 5, 0));
        assertTrue(WalkCost.nearStation(8, -4, 0));
    }

    @Test
    public void nearIsInclusiveAtExactlyTwentyOne() {
        assertTrue(WalkCost.nearStation(21, 0, 0));
        assertTrue(WalkCost.nearStation(0, 21, 0));
        assertTrue(WalkCost.nearStation(0, 0, -21));
        assertFalse(WalkCost.nearStation(21.01, 0, 0));
        assertFalse(WalkCost.nearStation(0, -21.01, 0));
        // 12 / 12 / 12 is 20.8, 13 / 12 / 12 is 21.2
        assertTrue(WalkCost.nearStation(12, 12, 12));
        assertFalse(WalkCost.nearStation(13, 12, 12));
        // the line is a sphere, not a box: 20 across and 7 up is 21.2
        assertFalse(WalkCost.nearStation(20, 7, 0));
    }

    @Test
    public void aStationRightOnUsIsNear() {
        assertTrue(WalkCost.nearStation(0, 0, 0));
    }

    // the callers all hand over the block and where they stand, and they must all land on the same side of the line
    @Test
    public void theBlockFormMeasuresToTheMiddleOfTheBlock() {
        // the block at 21,0,0 has its middle 21.5 away from a player at the origin: past. one block closer is in
        assertFalse(WalkCost.stationDistance(21, 0, 0, 0, 0, 0) <= WalkCost.STATION_NEAR);
        assertTrue(WalkCost.stationDistance(20, -1, 0, 0, 0, 0) <= WalkCost.STATION_NEAR);
        // from the block's own centre it is on top of us
        assertTrue(WalkCost.stationDistance(5, 70, 5, 5.5, 70.5, 5.5) <= WalkCost.STATION_NEAR);
        // the same answer as the offsets form
        assertEquals(WalkCost.nearStation(20.5 - 3.2, 64.5 - 70.0, 7.5 + 1.1), WalkCost.stationDistance(20, 64, 7, 3.2, 70.0, -1.1) <= WalkCost.STATION_NEAR);
        assertEquals(WalkCost.nearStation(30.5, 0.5, 0.5), WalkCost.stationDistance(30, 0, 0, 0, 0, 0) <= WalkCost.STATION_NEAR);
    }

    @Test
    public void stationDistanceIsTheSameLineWithTheNumberKept() {
        assertEquals(WalkCost.distance3d(3.5, -2.5, 1.5), WalkCost.stationDistance(3, -3, 1, 0, 0, 0), 1e-9);
        assertEquals(0.0, WalkCost.stationDistance(5, 70, 5, 5.5, 70.5, 5.5), 1e-9);
    }

    // a table in a mine 34 down is not a table to go back for: not near, and the climb is far over any drop budget
    @Test
    public void theCaveTripIsNotWorthIt() {
        assertFalse(WalkCost.nearStation(3, 34, 0));
        assertFalse(WalkCost.within(3, 34, 0, WalkCost.DROP_BUDGET));
        assertTrue(WalkCost.estimate(3, 34, 0) > 100);
    }
}
