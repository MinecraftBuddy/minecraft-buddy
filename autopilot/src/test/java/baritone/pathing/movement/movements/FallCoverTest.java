/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement.movements;

import baritone.pathing.movement.movements.MovementFall.FallMode;
import org.junit.Test;

import static org.junit.Assert.*;

public class FallCoverTest {

    // a fall from y 70 down to y 40, walking off the +x side of the block at 10,70,10
    private static boolean in(double px, double py, double pz, double mx, double mz) {
        return FallCover.inColumn(10, 70, 10, 11, 40, 10, px, py, pz, mx, mz);
    }

    @Test
    public void theFallItselfIsInTheColumn() {
        // right off the ledge, halfway down in the dest column, and just above the floor
        assertTrue(in(10.9, 69.5, 10.5, 0.1, 0));
        assertTrue(in(11.5, 55, 10.5, 0.05, 0));
        assertTrue(in(11.5, 40.2, 10.5, 0, 0));
        // sprinting off the edge still drifts, that's ours
        assertTrue(in(11.2, 60, 10.5, 0.28, 0));
    }

    @Test
    public void knockbackIsNotTheFall() {
        // shoved hard enough to be a hit, even though we're still over the column
        assertFalse(in(11.5, 55, 10.5, 0.4, 0.3));
        // or knocked clear of both cells
        assertFalse(in(13.5, 55, 10.5, 0.1, 0));
        assertFalse(in(11.5, 55, 12.5, 0, 0.1));
        assertFalse(in(8.5, 55, 10.5, 0, 0));
    }

    @Test
    public void heightHasToMakeSense() {
        // below the floor we planned to land on, or way above the ledge: not the same fall
        assertFalse(in(11.5, 38, 10.5, 0, 0));
        assertFalse(in(10.5, 75, 10.5, 0, 0));
        // a jump off the edge is
        assertTrue(in(10.9, 71, 10.5, 0.1, 0));
    }

    @Test
    public void diagonalFallsGetBothCells() {
        // src 10,10 dest 11,11: the box is the union, the corner we cut across is fine
        assertTrue(FallCover.inColumn(10, 70, 10, 11, 40, 11, 11.0, 60, 11.0, 0.1, 0.1));
        assertFalse(FallCover.inColumn(10, 70, 10, 11, 40, 11, 9.0, 60, 9.0, 0.05, 0.05));
    }

    @Test
    public void safeFallsAndClutchesAreBaritones() {
        for (boolean hotbar : new boolean[]{false, true}) {
            for (boolean alto : new boolean[]{false, true}) {
                assertTrue(FallCover.handles(FallMode.NONE, false, hotbar, alto, false));
                assertTrue(FallCover.handles(FallMode.CLUTCH, false, hotbar, alto, false));
            }
        }
    }

    @Test
    public void bucketIsBaritonesWhenItCanClickIt() {
        // alto doing buckets (the usual setup) still defers to a hotbar bucket
        assertTrue(FallCover.handles(FallMode.BUCKET, false, true, true, false));
        // the bucket that lives in the inventory is alto's slot handler's problem
        assertFalse(FallCover.handles(FallMode.BUCKET, false, false, true, false));
        // and with alto not doing buckets there's nobody else to do it
        assertTrue(FallCover.handles(FallMode.BUCKET, false, false, false, false));
    }

    @Test
    public void nobodyHasAClutchThatGaveUp() {
        // clutch ran out of options with nothing to land with: whatever alto has (hay, a bucket) can try
        assertFalse(FallCover.handles(FallMode.NONE, true, false, true, false));
    }

    @Test
    public void aPlannedHurtingFallIsBaritonesWhileItsAffordable() {
        // whatever the bucket and alto are up to, nobody has anything to clutch with, the fall is the plan
        for (boolean hotbar : new boolean[]{false, true}) {
            for (boolean alto : new boolean[]{false, true}) {
                assertTrue(FallCover.handles(FallMode.HURT, false, hotbar, alto, true));
            }
        }
    }

    @Test
    public void aHurtingFallWeCantAffordAnymoreIsAltos() {
        // took a hit since the plan, now it's an emergency and alto gets to clutch
        for (boolean hotbar : new boolean[]{false, true}) {
            for (boolean alto : new boolean[]{false, true}) {
                assertFalse(FallCover.handles(FallMode.HURT, false, hotbar, alto, false));
            }
        }
    }

    @Test
    public void affordabilityDoesntLeakIntoTheOtherModes() {
        // the flag only means something for HURT
        assertTrue(FallCover.handles(FallMode.NONE, false, false, true, true));
        assertFalse(FallCover.handles(FallMode.NONE, true, false, true, true));
        assertFalse(FallCover.handles(FallMode.BUCKET, false, false, true, true));
    }

    @Test
    public void aHurtingFallOutOfTheColumnIsNeverBaritones() {
        // ownsFall asks inColumn first, so knockback during an affordable planned fall still goes to alto
        assertFalse(in(13.5, 55, 10.5, 0.1, 0));
        assertFalse(in(11.5, 55, 10.5, 0.4, 0.3));
    }

    @Test
    public void waterPickupIsBaritonesOnlyAtItsDest() {
        assertTrue(FallCover.pickupIsBaritones(true, true, true));
        // the water went in somewhere else, or the bucket isn't where it clicks from, so the chain still has to do it
        assertFalse(FallCover.pickupIsBaritones(false, true, true));
        assertFalse(FallCover.pickupIsBaritones(true, true, false));
        // not water yet means the placement is still in flight
        assertFalse(FallCover.pickupIsBaritones(true, false, true));
    }

    // standing in the water at dest, done falling, bucket ready
    private static boolean ready(boolean onGround, boolean inWater, double motionY) {
        return FallCover.pickupReady(true, true, 1.2, 4.5, onGround, inWater, motionY, true);
    }

    @Test
    public void standingStillInWaterPicksUp() {
        // -0.005 is what vanilla gives a player standing on the floor of a still source, the old y >= 0 never saw it
        assertTrue(ready(true, true, -0.005));
        assertTrue(ready(false, true, -0.025)); // sinking with no floor, tops out here
        // a block over on dry ground, which reads -0.0784 standing still
        assertTrue(ready(true, false, -0.0784));
    }

    @Test
    public void stillComingDownDoesNot() {
        assertFalse(ready(false, true, -0.5));
        assertFalse(ready(false, true, FallCover.NOT_FALLING_Y));
        // the top of a jump in the air is slow but nowhere near the water
        assertFalse(ready(false, false, 0));
    }

    @Test
    public void aBlockOfDriftIsStillInRange() {
        assertTrue(FallCover.nextToDest(10, 64, 10, 10, 64, 10));
        assertTrue(FallCover.nextToDest(11, 64, 10, 10, 64, 10));
        assertTrue(FallCover.nextToDest(9, 64, 11, 10, 64, 10));
        assertFalse(FallCover.nextToDest(12, 64, 10, 10, 64, 10));
        // one up is still on the way down, one under is a hole next to it
        assertFalse(FallCover.nextToDest(10, 65, 10, 10, 64, 10));
        assertFalse(FallCover.nextToDest(11, 63, 10, 10, 64, 10));
    }

    @Test
    public void everythingElseHasToBeThereToo() {
        assertFalse(FallCover.pickupReady(false, true, 1.2, 4.5, true, true, -0.005, true)); // flowing, or gone already
        assertFalse(FallCover.pickupReady(true, false, 1.2, 4.5, true, true, -0.005, true));
        assertFalse(FallCover.pickupReady(true, true, 4.6, 4.5, true, true, -0.005, true));
        assertFalse(FallCover.pickupReady(true, true, 1.2, 4.5, true, true, -0.005, false));
    }

    @Test
    public void aLadderRightOnTheFloorIsAsGoodAsStanding() {
        assertTrue(FallCover.restingOnLadder(true, 0));
        assertTrue(FallCover.restingOnLadder(true, FallCover.RESTING_ON_FLOOR));
    }

    @Test
    public void hangingHigherUpIsStillTheFall() {
        assertFalse(FallCover.restingOnLadder(true, 0.3));
        assertFalse(FallCover.restingOnLadder(true, Double.MAX_VALUE));
    }

    @Test
    public void noLadderNoRest() {
        assertFalse(FallCover.restingOnLadder(false, 0));
    }
}
