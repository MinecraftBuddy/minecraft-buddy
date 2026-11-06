package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LavaShoreTest {
    // a flat world: floor is solid at y < 0 (feet at y = 0), everything above is open. lava replaces a floor cell, a void
    // column is open all the way down, a ledge column has its floor further down
    private static final class World implements Cells {
        final Set<Long> lava = new HashSet<>();
        final Set<Long> voids = new HashSet<>();
        final Set<Long> water = new HashSet<>();
        final Set<Long> solid = new HashSet<>();
        final Map<Long, Integer> floorAt = new HashMap<>();

        static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | ((long) z & 0xFFFFF);
        }

        World lava(int x, int y, int z) {
            lava.add(key(x, y, z));
            return this;
        }

        World pit(int x, int z) {
            voids.add(key(x, 0, z));
            return this;
        }

        World water(int x, int y, int z) {
            water.add(key(x, y, z));
            return this;
        }

        World solid(int x, int y, int z) {
            solid.add(key(x, y, z));
            return this;
        }

        World ledge(int x, int z, int floorY) {
            floorAt.put(key(x, 0, z), floorY);
            return this;
        }

        @Override
        public Cell at(int x, int y, int z) {
            if (lava.contains(key(x, y, z))) return Cell.LAVA;
            if (water.contains(key(x, y, z))) return Cell.WATER;
            if (solid.contains(key(x, y, z))) return Cell.SOLID;
            if (voids.contains(key(x, 0, z))) return Cell.OPEN;
            Integer floor = floorAt.get(key(x, 0, z));
            if (floor != null) return y > floor ? Cell.OPEN : Cell.SOLID;
            return y >= 0 ? Cell.OPEN : Cell.SOLID;
        }
    }

    @Test
    public void inlandApproachesAsBefore() {
        assertFalse(LavaShore.approachBlocked(new World(), 0.5, 0, 0.5, 6.5, 0, 0.5));
    }

    @Test
    public void lavaTwoDownBesideABridgeIsAShore() {
        // a one wide span: floor under us, an open column beside it with lava at the bottom. lavaNear only looks one down
        World w = new World().pit(0, 1).lava(0, -3, 1);
        assertFalse(LavaShore.lavaNear(w, 0.5, 0, 0.5, LavaShore.NEAR));
        assertTrue(LavaShore.lavaDeepNear(w, 0.5, 0, 0.5, LavaShore.NEAR));
        assertTrue(LavaShore.approachBlocked(w, 0.5, 0, 0.5, 8.5, 0, 0.5));
    }

    @Test
    public void aDropThatLandsOnStoneIsNoDeepLava() {
        World w = new World().pit(0, 1).solid(0, -3, 1).lava(0, -4, 1);
        assertFalse(LavaShore.lavaDeepNear(w, 0.5, 0, 0.5, LavaShore.NEAR));
        // and a drop with nothing at all is cliff avoidance's problem, not a lava hold
        assertFalse(LavaShore.lavaDeepNear(new World().pit(0, 1), 0.5, 0, 0.5, LavaShore.NEAR));
    }

    @Test
    public void deepLavaOutOfDepthOrOutOfRangeIsNothing() {
        assertFalse(LavaShore.lavaDeepNear(new World().pit(0, 1).lava(0, -20, 1), 0.5, 0, 0.5, LavaShore.NEAR));
        assertFalse(LavaShore.lavaDeepNear(new World().pit(0, 5).lava(0, -3, 5), 0.5, 0, 0.5, LavaShore.NEAR));
    }

    @Test
    public void waterOnTheWayDownDoesNotHideTheLava() {
        World w = new World().pit(0, 1).water(0, -1, 1).lava(0, -3, 1);
        assertTrue(LavaShore.lavaDeepNear(w, 0.5, 0, 0.5, LavaShore.NEAR));
    }

    @Test
    public void foeOnTheShoreIsLeftToComeToUs() {
        World w = new World().lava(7, -1, 0);
        assertTrue(LavaShore.approachBlocked(w, 0.5, 0, 0.5, 6.5, 0, 0.5));
    }

    @Test
    public void ourOwnShoreStopsTheWalkToo() {
        World w = new World().lava(-1, -1, 0);
        assertTrue(LavaShore.approachBlocked(w, 0.5, 0, 0.5, 8.5, 0, 0.5));
    }

    @Test
    public void lavaOnTheStraightLineBlocks() {
        // 3 blocks of lava in the middle, more than two away from either end
        World w = new World().lava(5, -1, 0).lava(6, -1, 0).lava(7, -1, 0);
        assertTrue(LavaShore.approachBlocked(w, 0.5, 0, 0.5, 12.5, 0, 0.5));
        assertTrue(LavaShore.lineHitsLava(w, 0.5, 0, 0.5, 12.5, 0, 0.5));
    }

    @Test
    public void lavaThreeAwayIsNotAShore() {
        World w = new World().lava(3, -1, 6);
        assertFalse(LavaShore.approachBlocked(w, 0.5, 0, 6.5, 0.5, 0, 12.5));
    }

    @Test
    public void lavaAtThreeCellsBehindIsUnsafe() {
        // foe at +x of us, the knock goes toward -x
        World w = new World().lava(-3, -1, 0);
        assertTrue(LavaShore.backUnsafe(w, 0.5, 0, 0.5, 1.5, 0.5));
        // the other way round it is not in the line
        assertFalse(LavaShore.backUnsafe(w, 0.5, 0, 0.5, -1.5, 0.5));
    }

    @Test
    public void lavaFourBehindIsOutOfTheLine() {
        World w = new World().lava(-4, -1, 0);
        assertFalse(LavaShore.backUnsafe(w, 0.5, 0, 0.5, 1.5, 0.5));
    }

    @Test
    public void diagonalKnockFindsTheLava() {
        // foe at +x +z, knock toward -x -z, lava two cells down that diagonal
        World w = new World().lava(-2, -1, -2);
        assertTrue(LavaShore.backUnsafe(w, 0.5, 0, 0.5, 1.5, 1.5));
    }

    @Test
    public void fortressBridgeSideIsADropBehindUs() {
        // a bridge one wide along x, void on both sides
        World w = new World();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (z != 0) w.pit(x, z);
            }
        }
        // foe in front of us along the bridge: the knock goes back along the bridge, the floor is there
        assertFalse(LavaShore.backUnsafe(w, 0.5, 0, 0.5, 2.5, 0.5));
        // foe off the side of the bridge: the knock goes the other way, into the void
        assertTrue(LavaShore.backUnsafe(w, 0.5, 0, 0.5, 0.5, 1.5));
    }

    @Test
    public void ledgeWithAFloorFourDownIsSafe() {
        World w = new World();
        for (int x = -4; x <= -1; x++) {
            w.ledge(x, 0, -4);
        }
        assertFalse(LavaShore.backUnsafe(w, 0.5, 0, 0.5, 1.5, 0.5));
    }

    @Test
    public void ledgeWithNoFloorWithinEightIsUnsafe() {
        World w = new World().pit(-2, 0);
        assertTrue(LavaShore.backUnsafe(w, 0.5, 0, 0.5, 1.5, 0.5));
    }

    @Test
    public void sweptBoxClipsTheCorner() {
        // lava just off the line but inside the body's width
        World w = new World().lava(-2, -1, 1);
        assertTrue(LavaShore.backUnsafe(w, 0.5, 0, 0.75, 1.5, 0.75));
    }

    @Test
    public void keysFollowTheYaw() {
        // yaw 0 faces +z. foe straight ahead
        assertEquals(new LavaShore.Keys(true, false, false, false), LavaShore.keysToward(0, 0, 3));
        // foe behind
        assertEquals(new LavaShore.Keys(false, true, false, false), LavaShore.keysToward(0, 0, -3));
        // facing +z, a foe at -x is on our right (west is right of south)
        assertEquals(new LavaShore.Keys(false, false, false, true), LavaShore.keysToward(0, -3, 0));
        assertEquals(new LavaShore.Keys(false, false, true, false), LavaShore.keysToward(0, 3, 0));
        // yaw 90 faces -x: a foe at -x is straight ahead
        assertEquals(new LavaShore.Keys(true, false, false, false), LavaShore.keysToward(90, -3, 0));
        // diagonal front left at yaw 0 (foe at +x +z)
        assertEquals(new LavaShore.Keys(true, false, true, false), LavaShore.keysToward(0, 3, 3));
        assertFalse(LavaShore.keysToward(0, 0, 0).any());
    }

    @Test
    public void holdOnlyWhenTheBackIsBadAndTheFrontIsNot() {
        // standing, back unsafe
        assertTrue(LavaShore.hold(true, true, false, false, true, false));
        // standing, back fine
        assertFalse(LavaShore.hold(true, true, false, false, false, false));
        // the cell ahead is bad too
        assertFalse(LavaShore.hold(true, true, false, false, true, true));
        // overworld
        assertFalse(LavaShore.hold(false, true, false, false, true, false));
        // a live path on the ground is the path's business
        assertFalse(LavaShore.hold(true, true, false, true, true, false));
        // airborne after a hit with the back bad
        assertTrue(LavaShore.hold(true, false, true, false, true, false));
        assertFalse(LavaShore.hold(true, false, true, false, false, false));
        // a path wipes the keys each tick, no hold
        assertFalse(LavaShore.hold(true, false, true, true, true, false));
        // airborne with no fresh hit (a jump)
        assertFalse(LavaShore.hold(true, false, false, true, true, false));
    }

    @Test
    public void landingCancelNeedsEverything() {
        assertTrue(LavaShore.cancelOnLanding(true, true, true, false, true));
        assertFalse(LavaShore.cancelOnLanding(false, true, true, false, true));
        assertFalse(LavaShore.cancelOnLanding(true, false, true, false, true));
        assertFalse(LavaShore.cancelOnLanding(true, true, false, false, true));
        assertFalse(LavaShore.cancelOnLanding(true, true, true, true, true));
        assertFalse(LavaShore.cancelOnLanding(true, true, true, false, false));
    }

    @Test
    public void landingLavaRadiusIsThree() {
        World w = new World().lava(3, -1, 0);
        assertTrue(LavaShore.lavaClose(w, 0.5, 0, 0.5));
        assertFalse(LavaShore.lavaClose(new World().lava(4, -1, 0), 0.5, 0, 0.5));
    }

    @Test
    public void passiveOnlyCaresAboutLavaByTheAnimal() {
        // lava by us and on the line, the cow is on dry stone: baritone paths around it, nothing to wait for
        World w = new World().lava(-1, -1, 0).lava(5, -1, 0);
        assertTrue(LavaShore.approachBlocked(w, 0.5, 0, 0.5, 12.5, 0, 0.5));
        assertFalse(LavaShore.blockedFor(true, w, 0.5, 0, 0.5, 12.5, 0, 0.5));
        assertTrue(LavaShore.blockedFor(false, w, 0.5, 0, 0.5, 12.5, 0, 0.5));
    }

    @Test
    public void passiveByThePoolIsBlockedAndLetGoOfAtOnce() {
        World w = new World().lava(7, -1, 0);
        assertTrue(LavaShore.blockedFor(true, w, 0.5, 0, 0.5, 6.5, 0, 0.5));
        assertTrue(LavaShore.giveUp(true, 1));
    }

    @Test
    public void hostileWaitEndsAtTheCap() {
        assertFalse(LavaShore.giveUp(false, 1));
        assertFalse(LavaShore.giveUp(false, LavaShore.WAIT_CAP_TICKS - 1));
        assertTrue(LavaShore.giveUp(false, LavaShore.WAIT_CAP_TICKS));
    }

    @Test
    public void rimStepPicksACellWithASafeBack() {
        World w = new World().lava(-3, -1, 0);
        int[] step = LavaShore.safeStep(w, 0.5, 0, 0.5, 1.5, 0.5);
        assertNotNull(step);
        int x = step[0];
        int z = step[1];
        // not the foe's own cell, and the knock from there goes somewhere fine
        assertFalse(x == 1 && z == 0);
        assertFalse(LavaShore.backUnsafe(w, x + 0.5, 0, z + 0.5, 1.5, 0.5));
        assertFalse(LavaShore.lavaNear(w, x + 0.5, 0, z + 0.5, 1));
    }

    @Test
    public void rimStepKeepsAwayFromLavaItCanSee() {
        // lava two cells off the cells on the knock side: those lose to the ones that are not near it
        int[] step = LavaShore.safeStep(new World().lava(-3, -1, 0), 0.5, 0, 0.5, 1.5, 0.5);
        assertNotNull(step);
        assertTrue(step[0] >= 0);
    }

    @Test
    public void rimStepStaysPutWhenTheSpotIsFine() {
        assertNull(LavaShore.safeStep(new World(), 0.5, 0, 0.5, 1.5, 0.5));
        assertNull(LavaShore.safeStep(new World().lava(-4, -1, 0), 0.5, 0, 0.5, 1.5, 0.5));
    }

    @Test
    public void rimStepHasNowhereToGoOnABridge() {
        // a one wide bridge: the only free neighbour is the one further down the knock line
        World w = new World().lava(-3, -1, 0);
        for (int x = -1; x <= 1; x++) {
            w.pit(x, 1).pit(x, -1);
        }
        assertNull(LavaShore.safeStep(w, 0.5, 0, 0.5, 1.5, 0.5));
    }

    // foe at (-1.5, 0.5) so the knock line through cell (0, 0) runs along +x and never touches column (0, 1)
    @Test
    public void aDropBesideACellOffTheKnockLineMakesItNoStep() {
        assertTrue(LavaShore.standable(new World(), 0, 0, 0, -1.5, 0.5));
        assertFalse(LavaShore.standable(new World().pit(0, 1), 0, 0, 0, -1.5, 0.5));
        assertFalse(LavaShore.standable(new World().pit(1, 1), 0, 0, 0, -1.5, 0.5));
    }

    @Test
    public void aPondBesideACellIsFine() {
        World w = new World().pit(0, 1).water(0, -2, 1);
        assertTrue(LavaShore.standable(w, 0, 0, 0, -1.5, 0.5));
    }

    @Test
    public void aWallBesideACellIsFine() {
        World w = new World().pit(0, 1).solid(0, 0, 1);
        assertTrue(LavaShore.standable(w, 0, 0, 0, -1.5, 0.5));
        // a wall only at head height still stops a knock
        assertTrue(LavaShore.standable(new World().pit(0, 1).solid(0, 1, 1), 0, 0, 0, -1.5, 0.5));
    }

    @Test
    public void aLandingThreeCellsDownCountsAndLavaDoesNot() {
        // first non-open cell at y-3 is solid: fine. lava there is not a landing
        assertTrue(LavaShore.standable(new World().pit(0, 1).solid(0, -3, 1), 0, 0, 0, -1.5, 0.5));
        assertFalse(LavaShore.standable(new World().pit(0, 1).lava(0, -2, 1), 0, 0, 0, -1.5, 0.5));
        assertFalse(LavaShore.standable(new World().pit(0, 1).solid(0, -4, 1), 0, 0, 0, -1.5, 0.5));
    }

    @Test
    public void rimSwingDoesNotWaitForTheGround() {
        assertTrue(LavaShore.swingReady(true, false, false, true));
        assertFalse(LavaShore.swingReady(true, false, false, false));
        assertTrue(LavaShore.swingReady(true, true, false, false));
        assertTrue(LavaShore.swingReady(true, false, true, false));
        // the other gates (eating, the bucket) still hold on a rim
        assertFalse(LavaShore.swingReady(false, true, true, true));
    }

    @Test
    public void aFreshBiteOnASideRimCanSwingAirborne() {
        assertTrue(LavaShore.swingReady(true, false, false, LavaShore.rimSwing(false, true, 7)));
        // bite window over, or no rim near: the ground is waited for as before
        assertFalse(LavaShore.swingReady(true, false, false, LavaShore.rimSwing(false, true, 0)));
        assertFalse(LavaShore.swingReady(true, false, false, LavaShore.rimSwing(false, false, 7)));
        assertTrue(LavaShore.rimSwing(true, false, 0));
    }

    @Test
    public void beingBitenInReachSkipsTheRimStep() {
        assertTrue(LavaShore.underFire(0, true));
        assertTrue(LavaShore.underFire(LavaShore.UNDER_FIRE_TICKS, true));
        assertFalse(LavaShore.underFire(LavaShore.UNDER_FIRE_TICKS + 1, true));
        assertFalse(LavaShore.underFire(7, false));
    }

    @Test
    public void aRimStepThatStopsGettingCloserGivesUp() {
        assertFalse(LavaShore.stepStalled(LavaShore.STEP_STALL_TICKS - 1));
        assertTrue(LavaShore.stepStalled(LavaShore.STEP_STALL_TICKS));
        // a wobble is not progress, a real step closer is
        assertEquals(1.0, LavaShore.stepBest(1.0, 0.9), 1e-9);
        assertEquals(1.0, LavaShore.stepBest(1.0, 1.4), 1e-9);
        assertEquals(0.6, LavaShore.stepBest(1.0, 0.6), 1e-9);
    }

    @Test
    public void aStepThatMovesThenStallsStillGivesUp() {
        // walks 1.0 -> 0.6, then sits there: the idle count restarts at the progress and runs out after it
        double best = 1.0;
        int idle = 0;
        double[] dists = {0.9, 0.6, 0.6, 0.6, 0.6, 0.6, 0.6, 0.6};
        boolean stalled = false;
        for (double d : dists) {
            double nb = LavaShore.stepBest(best, d);
            idle = nb < best ? 0 : idle + 1;
            best = nb;
            stalled = LavaShore.stepStalled(idle);
        }
        assertTrue(stalled);
    }

    // foe on the +x side, the drop is off to the side (z), so the knock line behind us (-x) never touches it
    @Test
    public void aDropToTheSideIsAnEdgeEvenOffTheKnockLine() {
        World w = new World().pit(0, 1).pit(-1, 1).pit(1, 1);
        assertFalse(LavaShore.backUnsafe(w, 0.5, 0, 0.5, 1.5, 0.5));
        assertTrue(LavaShore.edgeNear(w, 0.5, 0, 0.5));
        assertTrue(LavaShore.rimUnsafe(w, 0.5, 0, 0.5, 1.5, 0.5));
        assertNotNull(LavaShore.safeStep(w, 0.5, 0, 0.5, 1.5, 0.5));
    }

    @Test
    public void aDropTwoBlocksAwayIsNotAnEdge() {
        assertFalse(LavaShore.edgeNear(new World().pit(0, 3), 0.5, 0, 0.5));
        assertFalse(LavaShore.edgeNear(new World(), 0.5, 0, 0.5));
        // a wall between us and the drop
        assertFalse(LavaShore.edgeNear(new World().pit(0, 1).solid(0, 0, 1), 0.5, 0, 0.5));
    }

    @Test
    public void aShallowDropIsNotAnEdge() {
        assertFalse(LavaShore.edgeNear(new World().ledge(0, 1, -4), 0.5, 0, 0.5));
    }

    @Test
    public void aCellBesideADropIsNoStep() {
        // the stair step: the cell itself sits next to the drop, even with the knock line behind it clean
        World w = new World().pit(0, 2).pit(-1, 2).pit(1, 2);
        assertFalse(LavaShore.standable(w, 0, 0, 1, -1.5, 1.5));
    }

    @Test
    public void rimBackOffIsSkipped() {
        assertFalse(LavaShore.backOffAllowed(true));
        assertTrue(LavaShore.backOffAllowed(false));
    }

    @Test
    public void shoreWaitEndsBeforeTheFightDoes() {
        // the stall ends the fight and the cube gets picked again with its count running, so the cap has to come first
        assertTrue(LavaShore.WAIT_CAP_TICKS < CombatCommit.STALL_TICKS);
        assertEquals(160, LavaShore.WAIT_CAP_TICKS);
    }

    // a long channel along z, open from the feet down with the lava two under them
    private static World channel(int fromX, int toX) {
        World w = new World();
        for (int x = fromX; x <= toX; x++) {
            for (int z = -12; z <= 12; z++) {
                w.pit(x, z).lava(x, -2, z);
            }
        }
        return w;
    }

    @Test
    public void lavaBelowARimStillGetsAWalkToGround() {
        World w = channel(2, 4);
        // the miss the walk is for: lavaNear stops one under the feet and calls this spot clean
        assertFalse(LavaShore.lavaNear(w, 0.5, 0, 0.5, LavaShore.GROUND_CLEAR));
        int[] cell = LavaShore.groundCell(w, 0.5, 0, 0.5, 1.5, 0.5);
        assertNotNull(cell);
        assertEquals(-2, cell[0]);
        assertEquals(0, cell[1]);
        assertEquals(0, cell[2]);
        assertFalse(LavaShore.lavaDeepNear(w, cell[0] + 0.5, cell[1], cell[2] + 0.5, LavaShore.GROUND_CLEAR));
    }

    @Test
    public void groundIsFoundUpOrDownAStep() {
        // everything west of the channel's knock range is one block higher, the walk has to climb to it
        World w = channel(2, 4);
        for (int x = -12; x <= -2; x++) {
            for (int z = -12; z <= 12; z++) {
                w.solid(x, 0, z);
            }
        }
        int[] cell = LavaShore.groundCell(w, 0.5, 0, 0.5, 1.5, 0.5);
        assertNotNull(cell);
        assertEquals(-2, cell[0]);
        assertEquals(1, cell[1]);
    }

    @Test
    public void noGroundInReachMeansNoWalk() {
        // an island in a lava sea, every cell around is in knock range of something
        World w = new World();
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                if (Math.abs(x) > 1 || Math.abs(z) > 1) w.lava(x, -1, z);
            }
        }
        assertNull(LavaShore.groundCell(w, 0.5, 0, 0.5, 1.5, 0.5));
    }

    @Test
    public void aClearSpotGivesNoWalk() {
        assertNull(LavaShore.groundCell(new World(), 0.5, 0, 0.5, 1.5, 0.5));
        assertNull(LavaShore.groundCell(new World().lava(4, -1, 0), 0.5, 0, 0.5, 1.5, 0.5));
        // three away is the last one that counts
        assertNotNull(LavaShore.groundCell(new World().lava(3, -1, 0), 0.5, 0, 0.5, 1.5, 0.5));
    }

    // a one wide channel, rock at our back up past what a climb can reach: the only clean cells are over on the far bank
    @Test
    public void theFarBankOfAChannelIsNoWalk() {
        World w = channel(1, 1);
        for (int x = -12; x <= -1; x++) {
            for (int z = -12; z <= 12; z++) {
                for (int y = 0; y <= 3; y++) {
                    w.solid(x, y, z);
                }
            }
        }
        assertTrue(LavaShore.groundFit(w, 5, 0, 0, 0.5, 2.5));
        assertTrue(LavaShore.wayCrossesLava(w, 0.5, 0, 0.5, 5, 0, 0));
        assertNull(LavaShore.groundCell(w, 0.5, 0, 0.5, 0.5, 2.5));
    }

    @Test
    public void aCellOutsideTheSearchIsNoWalk() {
        // rock everywhere west until seven out, past the six we'll walk
        World w = channel(2, 4);
        for (int x = -6; x <= -1; x++) {
            for (int z = -12; z <= 12; z++) {
                for (int y = 0; y <= 3; y++) {
                    w.solid(x, y, z);
                }
            }
        }
        assertTrue(LavaShore.groundFit(w, -7, 0, 0, 0.5, 2.5));
        assertNull(LavaShore.groundCell(w, 0.5, 0, 0.5, 0.5, 2.5));
    }

    @Test
    public void theFoesOwnCellIsNotGround() {
        World w = channel(2, 4);
        // the foe stands right on the nearest cell, so the walk takes the next one along
        int[] cell = LavaShore.groundCell(w, 0.5, 0, 0.5, -1.5, 0.5);
        assertNotNull(cell);
        assertFalse(cell[0] == -2 && cell[2] == 0);
    }

    @Test
    public void aCellThatWentNowhereGivesTheNextOne() {
        World w = channel(2, 4);
        int[] cell = LavaShore.groundCell(w, 0.5, 0, 0.5, 1.5, 0.5, (x, y, z) -> x == -2 && z == 0);
        assertNotNull(cell);
        assertEquals(-2, cell[0]);
        assertEquals(1, Math.abs(cell[2]));
        // every cell gone is no walk, same as none being there
        assertNull(LavaShore.groundCell(w, 0.5, 0, 0.5, 1.5, 0.5, (x, y, z) -> true));
    }

    @Test
    public void aWalkGetsLongerThanAStepBeforeItCountsAsStuck() {
        assertTrue(LavaShore.STEP_STALL_TICKS < LavaShore.WALK_STALL_TICKS);
        assertFalse(LavaShore.walkStalled(LavaShore.WALK_STALL_TICKS - 1));
        assertTrue(LavaShore.walkStalled(LavaShore.WALK_STALL_TICKS));
    }

    // the fallback step: lava two under the feet of one neighbour's range, lavaNear would have called them all the same
    @Test
    public void rimStepCountsLavaFarDownAsLava() {
        World w = new World().lava(0, -1, -3).pit(2, 2).lava(2, -2, 2);
        assertFalse(LavaShore.lavaNear(w, 0.5, 0, 1.5, LavaShore.NEAR));
        assertTrue(LavaShore.lavaDeepNear(w, 0.5, 0, 1.5, LavaShore.NEAR));
        int[] step = LavaShore.safeStep(w, 0.5, 0, 0.5, 0.5, 2.5);
        assertNotNull(step);
        assertEquals(-1, step[0]);
        assertEquals(1, step[1]);
    }

    @Test
    public void edgeStepLeavesAnInlandSpotAlone() {
        assertNull(LavaShore.edgeStep(new World(), 0.5, 0, 0.5));
    }

    @Test
    public void edgeStepWalksAwayFromARim() {
        // pit one block east of us: the step has to be a standable neighbour that is itself clear of the edge
        World w = new World().pit(1, 0).pit(1, -1).pit(1, 1).pit(2, 0).pit(2, -1).pit(2, 1);
        assertTrue(LavaShore.edgeNear(w, 0.5, 0, 0.5));
        int[] step = LavaShore.edgeStep(w, 0.5, 0, 0.5);
        assertNotNull(step);
        assertTrue(step[0] < 0);
    }

    @Test
    public void edgeStepHasNowhereToGoWhenEveryNeighbourIsBad() {
        World w = new World();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (x != 0 || z != 0) w.pit(x, z);
            }
        }
        assertNull(LavaShore.edgeStep(w, 0.5, 0, 0.5));
    }

    // a blaze in melee contact is a rim foe, a ghast and the real ranged kinds are not
    @Test
    public void rimFoeTakesAMeleeBlazeButNotGhastsOrArchers() {
        assertTrue(LavaShore.rimFoe(false, false, false, true, 2.5, 3.5));
        assertFalse(LavaShore.rimFoe(false, false, false, true, 4, 3.5));
        assertFalse(LavaShore.rimFoe(true, false, false, true, 2, 3.5));
        assertFalse(LavaShore.rimFoe(false, true, false, true, 2, 3.5));
        assertFalse(LavaShore.rimFoe(false, false, true, true, 2, 3.5));
        assertFalse(LavaShore.rimFoe(false, false, false, false, 2, 3.5));
    }

    @Test
    public void campSpotSitsSixToEightOutWithALineToTheSpawner() {
        int[] spot = LavaShore.campSpot(new World(), 0, 0, 0, 20, 0);
        assertNotNull(spot);
        double out = Math.hypot(spot[0], spot[2]);
        assertTrue(out >= LavaShore.CAMP_MIN && out <= LavaShore.CAMP_MAX);
    }

    @Test
    public void campSpotSkipsCellsBehindAWall() {
        // a wall ring at 5 hides everything past it, so the only spots with a line are none
        World w = new World();
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) == 5) {
                    for (int y = 0; y <= 3; y++) {
                        w.solid(x, y, z);
                    }
                }
            }
        }
        assertNull(LavaShore.campSpot(w, 0, 0, 0, 20, 0));
    }

    @Test
    public void theWideLookFindsAFiveOutSpotWhereSixToEightHasNone() {
        // a round wall 5.5 to 7.5 out hides six to eight; five out stays open with a line, out of the spawn area all the same
        World w = new World();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                double r = Math.hypot(x, z);
                if (r > 5.5 && r <= 7.5) {
                    for (int y = 0; y <= 3; y++) {
                        w.solid(x, y, z);
                    }
                }
            }
        }
        assertNull(LavaShore.campSpot(w, 0, 0, 0, 20, 0));
        int[] spot = LavaShore.campSpot(w, 0, 0, 0, 20, 0, LavaShore.WIDE_MIN, LavaShore.WIDE_MAX);
        assertNotNull(spot);
        double out = Math.hypot(spot[0], spot[2]);
        assertTrue(out >= LavaShore.WIDE_MIN && out <= 5.5);
    }

    @Test
    public void campSpotPrefersTheOneNearestUs() {
        int[] spot = LavaShore.campSpot(new World(), 0, 0, 0, 30, 0);
        assertNotNull(spot);
        assertTrue(spot[0] > 0);
    }

    // the same cases with SafeStand's switch on (tuning safe_stand): its knock reach instead of the old edge and lava tests
    @Test
    public void aDropToTheSideIsAnEdgeEvenOffTheKnockLineWithSafeStand() {
        SafeStand.forced = true;
        try {
            World w = new World().pit(0, 1).pit(-1, 1).pit(1, 1);
            assertFalse(LavaShore.backUnsafe(w, 0.5, 0, 0.5, 1.5, 0.5));
            assertTrue(LavaShore.edgeNear(w, 0.5, 0, 0.5));
            assertTrue(LavaShore.rimUnsafe(w, 0.5, 0, 0.5, 1.5, 0.5));
            // every neighbour is within a knock (2.5) of that row of pits: no step is any better
            assertNull(LavaShore.safeStep(w, 0.5, 0, 0.5, 1.5, 0.5));
            // one pit behind us to the side: the neighbour across from it, ahead toward the foe, is out of its reach
            World one = new World().pit(-1, 1);
            assertFalse(LavaShore.backUnsafe(one, 0.5, 0, 0.5, 1.5, 0.5));
            assertTrue(LavaShore.rimUnsafe(one, 0.5, 0, 0.5, 1.5, 0.5));
            assertArrayEquals(new int[]{1, -1}, LavaShore.safeStep(one, 0.5, 0, 0.5, 1.5, 0.5));
        } finally {
            SafeStand.forced = null;
        }
    }

    @Test
    public void edgeStepWalksAwayFromARimWithSafeStand() {
        SafeStand.forced = true;
        try {
            // the step has to be a standable neighbour that is itself clear of the edge. a pit along our east side leaves none
            // (every neighbour is within a knock of it); a pit at the north east corner leaves the one diagonally away from it
            World w = new World().pit(1, 0).pit(1, -1).pit(1, 1).pit(2, 0).pit(2, -1).pit(2, 1);
            assertTrue(LavaShore.edgeNear(w, 0.5, 0, 0.5));
            assertNull(LavaShore.edgeStep(w, 0.5, 0, 0.5));
            World corner = new World().pit(1, 1);
            assertTrue(LavaShore.edgeNear(corner, 0.5, 0, 0.5));
            assertArrayEquals(new int[]{-1, -1}, LavaShore.edgeStep(corner, 0.5, 0, 0.5));
        } finally {
            SafeStand.forced = null;
        }
    }
}
