package adris.altoclef.util.helpers;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// (Tuning mine_leave_given_up) the spots given up, the way to leave by, and the way down's failures counted by place
public class MineLeaveTest {

    @Test
    public void aSpotCountsNearAndFresh() {
        MineLeave.Spots spots = new MineLeave.Spots(8);
        spots.record("OVERWORLD", 100, 200, 10000);
        // within 24 blocks and 30 game minutes
        MineLeave.Spot s = spots.near("OVERWORLD", 110, 215, 12000, MineLeave.NEAR, MineLeave.FRESH, null);
        assertEquals(100, s.x());
        assertEquals(200, s.z());
        // 30 blocks off, too far
        assertNull(spots.near("OVERWORLD", 130, 200, 12000, MineLeave.NEAR, MineLeave.FRESH, null));
        // 31 game minutes later, too old
        assertNull(spots.near("OVERWORLD", 100, 200, 10000 + 37200, MineLeave.NEAR, MineLeave.FRESH, null));
        // another world's earlier clock, or another dimension
        assertNull(spots.near("OVERWORLD", 100, 200, 5000, MineLeave.NEAR, MineLeave.FRESH, null));
        assertNull(spots.near("NETHER", 100, 200, 12000, MineLeave.NEAR, MineLeave.FRESH, null));
    }

    @Test
    public void theNearestSpotNotLeftAlready() {
        MineLeave.Spots spots = new MineLeave.Spots(8);
        spots.record("OVERWORLD", 100, 200, 10000);
        spots.record("OVERWORLD", 115, 200, 11000);
        MineLeave.Spot near = spots.near("OVERWORLD", 118, 200, 12000, MineLeave.NEAR, MineLeave.FRESH, null);
        assertEquals(115, near.x());
        // that one left: the other, still within 24
        MineLeave.Spot other = spots.near("OVERWORLD", 118, 200, 12000, MineLeave.NEAR, MineLeave.FRESH, Set.of(near));
        assertEquals(100, other.x());
        assertNull(spots.near("OVERWORLD", 118, 200, 12000, MineLeave.NEAR, MineLeave.FRESH, Set.of(near, other)));
    }

    @Test
    public void aHandfulKeptTheOldestDropped() {
        MineLeave.Spots spots = new MineLeave.Spots(3);
        for (int i = 0; i < 5; i++) spots.record("OVERWORLD", i * 100, 0, 1000 + i);
        assertEquals(3, spots.size());
        assertNull(spots.near("OVERWORLD", 0, 0, 2000, MineLeave.NEAR, MineLeave.FRESH, null));
        assertNull(spots.near("OVERWORLD", 100, 0, 2000, MineLeave.NEAR, MineLeave.FRESH, null));
        assertEquals(400, spots.near("OVERWORLD", 400, 0, 2000, MineLeave.NEAR, MineLeave.FRESH, null).x());
    }

    @Test
    public void theWayIsAwayFromTheWaterAndTheSpot() {
        // no water anywhere, the buddy east of the spot: east
        assertEquals(0, MineLeave.nextWay(new int[8], 10, 0, 0));
        // water east and north-east and south-east: of the rest, the nearest to away from the spot (south, index 2, and
        // north, index 6, equal; south first)
        int[] water = {40, 30, 0, 0, 0, 0, 0, 30};
        assertEquals(2, MineLeave.nextWay(water, 10, 0, 0));
        // with no away (the buddy on the spot), the least water
        int[] some = {5, 5, 5, 5, 1, 5, 5, 5};
        assertEquals(4, MineLeave.nextWay(some, 0, 0, 0));
        // a way back over the spot (west) costs 16: west with 1 water against east with 10 goes east
        int[] back = {10, 20, 20, 20, 1, 20, 20, 20};
        assertEquals(0, MineLeave.nextWay(back, 10, 0, 0));
    }

    @Test
    public void noWayWithin45DegreesOfOneTried() {
        int[] none = new int[8];
        // east tried: not east, north-east or south-east
        int way = MineLeave.nextWay(none, 10, 0, 1);
        assertTrue(way >= 2 && way <= 6);
        // east, south and north tried: west alone is not within 45 degrees of one; with west tried too, none is left
        int tried = (1 << 0) | (1 << 2) | (1 << 6);
        int w = MineLeave.nextWay(none, 10, 0, tried);
        assertEquals(4, w);
        assertEquals(-1, MineLeave.nextWay(none, 10, 0, tried | (1 << 4)));
    }

    @Test
    public void theTargetLiesPastTheLeave() {
        assertArrayEquals(new int[]{156, 200}, MineLeave.target(100, 200, 0));
        int[] diag = MineLeave.target(100, 200, 5);
        double d = Math.hypot(diag[0] - 100, diag[1] - 200);
        assertTrue(d >= MineLeave.FAR + MineLeave.TARGET_RADIUS);
        MineLeave.Spot spot = new MineLeave.Spot("OVERWORLD", 100, 200, 0);
        assertFalse(MineLeave.left(130, 200, spot));
        assertTrue(MineLeave.left(148, 200, spot));
        assertTrue(MineLeave.left(100, 152, spot));
    }

    @Test
    public void threeFailuresInARowAtOnePlaceFire() {
        MineLeave.Streak s = new MineLeave.Streak(3, 10, 1200);
        // the loop of the flooded cave: a failure every 7 s on ledges a few blocks apart
        assertFalse(s.fail(100, 200, 1000));
        assertFalse(s.fail(104, 203, 1140));
        assertTrue(s.fail(98, 207, 1280));
        assertEquals(100, s.x());
        assertEquals(200, s.z());
        // it starts again after a fire
        assertEquals(0, s.count());
        assertFalse(s.fail(100, 200, 1420));
        assertEquals(1, s.count());
    }

    @Test
    public void aFailureFarOffLateOrAtOnceStartsOrJoinsNoRow() {
        MineLeave.Streak s = new MineLeave.Streak(3, 10, 1200);
        s.fail(100, 200, 1000);
        s.fail(102, 200, 1140);
        // 20 blocks off: a new row from there
        assertFalse(s.fail(120, 200, 1280));
        assertEquals(1, s.count());
        assertEquals(120, s.x());
        // two game minutes later: a new row
        s.fail(121, 200, 1420);
        assertFalse(s.fail(121, 200, 1420 + 2400));
        assertEquals(1, s.count());
        // a report within 3 s of the last is the same failure
        assertFalse(s.fail(121, 200, 1420 + 2420));
        assertEquals(1, s.count());
        assertFalse(s.fail(121, 200, 1420 + 2500));
        assertTrue(s.fail(121, 200, 1420 + 2600));
    }

    @Test
    public void reachingTheDepthEndsTheRow() {
        MineLeave.Streak s = new MineLeave.Streak(3, 10, 1200);
        s.fail(100, 200, 1000);
        s.fail(100, 200, 1140);
        s.reset();
        assertFalse(s.fail(100, 200, 1280));
        assertEquals(1, s.count());
    }

    // ---- (Tuning mine_leave_dry, look 92: the leave ended at 48 blocks on open sea, and the land walked to from there lay back
    // toward the spot given up)

    @Test
    public void theLeaveEndsOnlyOnDryGroundClearOfEverySpot() {
        assertTrue(MineLeave.leaveEnds(true, true, true));
        // 48 blocks off, swimming: on along the way
        assertFalse(MineLeave.leaveEnds(true, false, true));
        // 48 from this spot but within 48 of another one given up
        assertFalse(MineLeave.leaveEnds(true, true, false));
        assertFalse(MineLeave.leaveEnds(false, true, true));
    }

    @Test
    public void aWaysGoalMovesOnUntilItsMax() {
        assertEquals(MineLeave.TARGET + MineLeave.EXTEND, MineLeave.nextReach(MineLeave.TARGET));
        int reach = MineLeave.TARGET, n = 0;
        while ((reach = MineLeave.nextReach(reach)) > 0) n++;
        // 56, 72, 88, 104, 120: four more goals along a way, then the next way
        assertEquals(4, n);
        assertArrayEquals(new int[]{100 + 72, 200}, MineLeave.target(100, 200, 0, 72));
        assertArrayEquals(MineLeave.target(100, 200, 3), MineLeave.target(100, 200, 3, MineLeave.TARGET));
    }

    @Test
    public void theLandFromOpenWaterIsClearOfTheSpotsGivenUp() {
        MineLeave.Spots spots = new MineLeave.Spots(8);
        spots.record("OVERWORLD", 0, 0, 1000);
        // the nearest land 33 blocks from the spot (outside NEAR, inside FAR): not clear
        assertFalse(spots.clearOf("OVERWORLD", 33, 0, 2000, MineLeave.FAR, MineLeave.FRESH));
        assertTrue(spots.clearOf("OVERWORLD", 49, 0, 2000, MineLeave.FAR, MineLeave.FRESH));
        // an old spot, another dimension: clear
        assertTrue(spots.clearOf("OVERWORLD", 33, 0, 1000 + MineLeave.FRESH + 1, MineLeave.FAR, MineLeave.FRESH));
        assertTrue(spots.clearOf("NETHER", 33, 0, 2000, MineLeave.FAR, MineLeave.FRESH));
        // the land picked: the nearest clear one, else the nearest of any, else none
        assertEquals("far", MineLeave.landPick("far", "near"));
        assertEquals("near", MineLeave.landPick(null, "near"));
        assertNull(MineLeave.landPick(null, null));
    }

    // (Tuning mine_leave_once) a spot one mine left, or gave up leaving, is left by no later mine of the order: the route's switch
    // between the helmet and the gold made a new task, which set out from the same spot again in the second the last gave up
    @Test
    public void aSpotLeftOnceIsNotLeftByTheNextMine() {
        MineLeave.Spots spots = new MineLeave.Spots(3);
        spots.record("OVERWORLD", 100, 200, 10000);
        MineLeave.Spot s = spots.near("OVERWORLD", 100, 200, 12000, MineLeave.NEAR, MineLeave.FRESH, null);
        assertFalse(spots.wasLeft(s));
        spots.left(s);
        assertTrue(spots.wasLeft(s));
        // the next task's own set is empty: the order's set still skips it
        assertNull(spots.near("OVERWORLD", 100, 200, 12100, MineLeave.NEAR, MineLeave.FRESH, spots.leftAnd(Set.of())));
        // a new give-up at the same place is a new spot, left anew
        spots.record("OVERWORLD", 100, 200, 13000);
        MineLeave.Spot again = spots.near("OVERWORLD", 100, 200, 13100, MineLeave.NEAR, MineLeave.FRESH, spots.leftAnd(null));
        assertEquals(13000, again.at());
        // a spot dropped from the handful is dropped from the left ones too; one never recorded is not taken
        spots.record("OVERWORLD", 0, 0, 14000);
        spots.record("OVERWORLD", 0, 0, 15000);
        assertFalse(spots.wasLeft(s));
        spots.left(new MineLeave.Spot("OVERWORLD", 5, 5, 1));
        assertEquals(0, spots.leftAnd(null).size());
    }
}
