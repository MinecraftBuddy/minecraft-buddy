package adris.altoclef.util.helpers;

import static adris.altoclef.util.helpers.LavaWorld.Cell.AIR;
import static adris.altoclef.util.helpers.LavaWorld.Cell.LAVA;
import static adris.altoclef.util.helpers.LavaWorld.Cell.STONE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

// where a player in lava should swim when baritone has nothing for them. feet are the lava cell, the shore is one up
public class LavaExitTest {

    // a lake over x 0..12 in a stone floor (top y 63), banks are x -1 and x 13
    private static LavaWorld lake() {
        return LavaWorld.flat(63).fill(0, 63, -5, 12, 63, 25, LAVA);
    }

    private static LavaExit.Cell nearest(int x, int y, int z, LavaWorld w) {
        return LavaExit.nearest(x, y, z, w, w);
    }

    @Test
    public void picksTheNearerBank() {
        LavaExit.Cell exit = nearest(5, 63, 10, lake());
        assertNotNull(exit);
        // standing on top of the bank block, one up from the lava surface
        assertEquals(-1, exit.x());
        assertEquals(64, exit.y());
        assertEquals(10, exit.z());
    }

    @Test
    public void flipsToTheOtherBankWhenThatIsCloser() {
        LavaExit.Cell exit = nearest(11, 63, 10, lake());
        assertNotNull(exit);
        assertEquals(13, exit.x());
    }

    @Test
    public void aTwoHighWallBesideUsIsNotAWayOut() {
        // a pillar two blocks tall next to us, its top is two above the lava surface: closer than the bank, and no use
        LavaWorld w = lake().set(6, 63, 10, STONE).set(6, 64, 10, STONE);
        LavaExit.Cell exit = nearest(5, 63, 10, w);
        assertNotNull(exit);
        assertEquals(-1, exit.x());
    }

    @Test
    public void aOneHighStepBesideUsIsAWayOut() {
        LavaWorld w = lake().set(6, 63, 10, STONE);
        LavaExit.Cell exit = nearest(5, 63, 10, w);
        assertNotNull(exit);
        assertEquals(6, exit.x());
        assertEquals(64, exit.y());
    }

    @Test
    public void underTheSurfaceTheBankIsMeasuredFromTheSurface() {
        // three deep, we are two below the top layer, the bank is three above our feet but one above the surface
        LavaWorld w = LavaWorld.flat(63).fill(0, 61, -5, 12, 63, 25, LAVA);
        LavaExit.Cell exit = nearest(5, 61, 10, w);
        assertNotNull(exit);
        assertEquals(-1, exit.x());
        assertEquals(64, exit.y());
    }

    @Test
    public void aLakeBiggerThanTheRadiusHasNoAnswer() {
        LavaWorld w = new LavaWorld().fill(-30, 63, -30, 30, 63, 30, LAVA);
        assertNull(nearest(0, 63, 0, w));
    }

    @Test
    public void aFilledPitWithWallsOnEveryFaceClimbsToTheLedgeAtTheCorner() {
        // a one cell pit full of lava in the floor, walls two high on all four faces, so the only step out is the floor
        // top at a corner, one up and diagonal. the lava cell itself has a floor under it and is never the answer
        LavaWorld w = LavaWorld.flat(63).set(5, 63, 5, LAVA);
        int[][] faces = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] f : faces) {
            w.set(5 + f[0], 64, 5 + f[1], STONE).set(5 + f[0], 65, 5 + f[1], STONE);
        }
        LavaExit.Cell exit = nearest(5, 63, 5, w);
        assertNotNull(exit);
        assertEquals(64, exit.y());
        assertEquals(1, Math.abs(exit.x() - 5));
        assertEquals(1, Math.abs(exit.z() - 5));
    }

    @Test
    public void aBankOneCellAwayIsAHop() {
        LavaExit.Cell exit = nearest(0, 63, 10, lake());
        assertEquals(-1, exit.x());
        assertTrue(LavaExit.withinHop(exit, 0, 63, 10));
    }

    @Test
    public void aBankTwoCellsAwayIsStillAHop() {
        assertTrue(LavaExit.withinHop(nearest(1, 63, 10, lake()), 1, 63, 10));
    }

    @Test
    public void aBankThreeCellsAwayIsBaritonesProblem() {
        assertFalse(LavaExit.withinHop(nearest(2, 63, 10, lake()), 2, 63, 10));
    }

    @Test
    public void noExitIsNoHop() {
        assertFalse(LavaExit.withinHop(null, 0, 63, 0));
    }

    @Test
    public void aLedgeTwoAboveUsIsNotAHop() {
        // swimming a block under the surface: the bank is two above our feet, jump alone won't clear that
        assertFalse(LavaExit.withinHop(new LavaExit.Cell(-1, 65, 10), 0, 63, 10));
    }

    @Test
    public void aCellNeedsHeadroomAndAFloor() {
        // a stone block with a ceiling right over it is not a place to stand, and there is nothing else dry in range
        LavaWorld w = new LavaWorld().fill(-5, 63, -5, 5, 63, 5, LAVA)
                .set(3, 63, 0, STONE).set(3, 65, 0, STONE);
        assertNull(nearest(0, 63, 0, w));
    }

    @Test
    public void aTwoDeepPoolNearTheBottomStillSeesTheBank() {
        // pool two deep (62, 63) on a floor, a bank at its top level
        LavaWorld w = LavaWorld.flat(61).fill(0, 62, -5, 12, 63, 25, LAVA)
                .fill(-5, 62, -5, -1, 63, 25, STONE);
        LavaExit.Cell exit = nearest(0, 62, 10, w);
        assertNotNull(exit);
        assertEquals(-1, exit.x());
        assertEquals(64, exit.y());
        // two up from the floor cell is not a jump yet, a cell higher in the pool it is
        assertFalse(LavaExit.withinHop(exit, 0, 62, 10));
        assertTrue(LavaExit.withinHop(nearest(0, 63, 10, w), 0, 63, 10));
    }

    @Test
    public void magmaRightBesideUsBeatsARockSixAway() {
        // losing this one means no hop at all and a long crawl through lava for the sake of a little fire
        LavaWorld w = lake().putMagma(6, 63, 10);
        LavaExit.Cell exit = nearest(5, 63, 10, w);
        assertEquals(new LavaExit.Cell(6, 64, 10), exit);
        assertTrue(LavaExit.withinHop(exit, 5, 63, 10));
    }

    @Test
    public void anExactTieGoesToTheNonMagmaEitherWayRound() {
        LavaWorld magmaEast = lake().putMagma(6, 63, 10).set(4, 63, 10, STONE);
        assertEquals(new LavaExit.Cell(4, 64, 10), nearest(5, 63, 10, magmaEast));
        LavaWorld magmaWest = lake().putMagma(4, 63, 10).set(6, 63, 10, STONE);
        assertEquals(new LavaExit.Cell(6, 64, 10), nearest(5, 63, 10, magmaWest));
    }

    @Test
    public void theSlackIsTwoAndNotThree() {
        // magma is 1 over + 1 up = 2. a dry floor two over at our own level is 4, that's within it
        LavaWorld within = lake().putMagma(6, 63, 10).set(5, 63, 12, AIR);
        assertEquals(new LavaExit.Cell(5, 63, 12), nearest(5, 63, 10, within));
        // the top of a rock two over is 4 + 1 = 5, that's not
        LavaWorld past = lake().putMagma(6, 63, 10).set(3, 63, 10, STONE);
        assertEquals(new LavaExit.Cell(6, 64, 10), nearest(5, 63, 10, past));
    }

    @Test
    public void aNonMagmaOutOfHopRangeDoesNotGetIn() {
        // magma at 2, 2 and one up is 9, in hop range. a rock 3 over and one up is 10: within the slack, but that's a swim
        LavaWorld w = lake().putMagma(7, 63, 12).set(8, 63, 10, STONE);
        assertEquals(new LavaExit.Cell(7, 64, 12), nearest(5, 63, 10, w));
    }

    @Test
    public void nothingButMagmaIsStillAWayOut() {
        assertEquals(new LavaExit.Cell(6, 64, 10), nearest(5, 63, 10, lake().putMagma(6, 63, 10).putMagma(3, 63, 10)));
    }

    // a one wide channel at x = 5 with a lip on each side, one up from the lava
    private static LavaWorld channel() {
        return lake().putMagma(4, 63, 10).set(6, 63, 10, STONE);
    }

    @Test
    public void underIsTheSideOfTheColumnWeAreHangingOff() {
        assertEquals(new LavaExit.Cell(6, 64, 10), LavaExit.under(5.8, 64, 10.5, channel()));
        assertEquals(new LavaExit.Cell(4, 64, 10), LavaExit.under(5.1, 64, 10.5, channel()));
    }

    @Test
    public void underIsNothingWhenOurBoxIsOverNothing() {
        assertNull(LavaExit.under(5.5, 64, 10.5, channel()));
        assertNull(LavaExit.under(5.65, 64, 10.5, channel()));
    }

    @Test
    public void underWantsRoomToStand() {
        assertNull(LavaExit.under(5.8, 64, 10.5, lake().set(6, 63, 10, STONE).set(6, 65, 10, STONE)));
    }

    private static LavaExit.Cell stair(int x, int y, int z, LavaWorld w, float yaw) {
        return LavaExit.stairCell(x, y, z, w, w, yaw);
    }

    // a rim two high, one lava cell back from us: stone at 63 and 64 on x=7
    private static LavaWorld wallAt(int x) {
        return lake().set(x, 63, 10, STONE).set(x, 64, 10, STONE);
    }

    @Test
    public void aTwoHighWallGetsAStairBlockInTheLavaBesideUs() {
        LavaExit.Cell t = stair(5, 63, 10, wallAt(7), 0);
        assertNotNull(t);
        assertEquals(6, t.x());
        assertEquals(63, t.y());
        assertEquals(10, t.z());
    }

    @Test
    public void openSeaHasNoStairCell() {
        assertNull(stair(5, 63, 10, lake(), 0));
    }

    @Test
    public void ourOwnColumnIsNeverTheStairCell() {
        // a one cell pit with a wall on every face: the only lava cell around is the one we float in
        LavaWorld w = LavaWorld.flat(63).set(5, 63, 5, LAVA);
        int[][] faces = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] f : faces) {
            w.set(5 + f[0], 63, 5 + f[1], STONE).set(5 + f[0], 64, 5 + f[1], STONE);
        }
        assertNull(stair(5, 63, 5, w, 0));
    }

    @Test
    public void aStairCellWithALidOnItIsRejected() {
        assertNull(stair(5, 63, 10, wallAt(7).set(6, 65, 10, STONE), 0));
    }

    @Test
    public void aRimWithNoRoomOnTopIsRejected() {
        // 66 alone would leave a rim at 66 with room over it, three up from T, so 67 goes too
        assertNull(stair(5, 63, 10, wallAt(7).set(7, 66, 10, STONE).set(7, 67, 10, STONE), 0));
    }

    // a wall on x=7 whose top block is `k` up from the lava surface at 63
    private static LavaWorld wallOf(int k) {
        return lake().fill(7, 63, 10, 7, 63 + k, 10, STONE);
    }

    @Test
    public void aThreeHighWallStillGetsAStairBlock() {
        LavaExit.Cell t = stair(5, 63, 10, wallOf(2), 0);
        assertNotNull(t);
        assertEquals(6, t.x());
        assertEquals(63, t.y());
        assertEquals(10, t.z());
    }

    @Test
    public void aFourHighWallStillGetsAStairBlock() {
        LavaExit.Cell t = stair(5, 63, 10, wallOf(3), 0);
        assertNotNull(t);
        assertEquals(6, t.x());
        assertEquals(63, t.y());
    }

    @Test
    public void aFiveHighWallIsTooMuchPillar() {
        assertNull(stair(5, 63, 10, wallOf(4), 0));
    }

    @Test
    public void aTallerWallNeedsRoomToPillarOnTheStair() {
        // three high wall, a block over T's column at 66: no room for the pillar plus the step
        assertNull(stair(5, 63, 10, wallOf(2).set(6, 66, 10, STONE), 0));
        // the one up stair never asked for that cell and still doesn't
        assertNotNull(stair(5, 63, 10, wallAt(7).set(6, 66, 10, STONE), 0));
    }

    @Test
    public void theLowestRimBeatsWhereWeAreLooking() {
        // a two high wall behind us on x=3, a three high one ahead on x=7. facing +x, the short one still wins
        LavaWorld w = wallOf(2).set(3, 63, 10, STONE).set(3, 64, 10, STONE);
        assertEquals(4, stair(5, 63, 10, w, -90).x());
        assertEquals(4, stair(5, 63, 10, w, 90).x());
    }

    @Test
    public void aWallIsMeasuredFromTheSurfaceNotFromOurFeet() {
        // three deep, we are at the bottom: the stair still goes at the surface layer
        LavaWorld w = LavaWorld.flat(61).fill(0, 61, -5, 12, 63, 25, LAVA)
                .set(7, 63, 10, STONE).set(7, 64, 10, STONE);
        LavaExit.Cell t = stair(5, 61, 10, w, 0);
        assertNotNull(t);
        assertEquals(63, t.y());
    }

    @Test
    public void theStairGoesWhereWeAreLooking() {
        LavaWorld w = wallAt(7).set(3, 63, 10, STONE).set(3, 64, 10, STONE);
        // yaw -90 faces +x, 90 faces -x
        assertEquals(6, stair(5, 63, 10, w, -90).x());
        assertEquals(4, stair(5, 63, 10, w, 90).x());
    }
}
