/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package adris.altoclef.chains;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.*;

public class MLGBucketFallChainTest {

    @Test
    public void noClutchItemAndNothingDeadlyMeansNothingToTakeOverFor() {
        // this is the one that used to cancel a planned fall for nothing
        assertFalse(MLGBucketFallChain.claimsFall(true, false, false));
    }

    @Test
    public void aFallWithSomethingToPlaceIsStillTheChains() {
        assertTrue(MLGBucketFallChain.claimsFall(true, true, false));
        assertTrue(MLGBucketFallChain.claimsFall(true, true, true));
    }

    @Test
    public void aDeadlyFallIsTheChainsEvenWithAnEmptyHand() {
        // nothing to place, but the steering toward lava or water can still save us
        assertTrue(MLGBucketFallChain.claimsFall(true, false, true));
    }

    @Test
    public void notFallingIsNotFalling() {
        for (boolean item : new boolean[]{false, true}) {
            for (boolean deadly : new boolean[]{false, true}) {
                assertFalse(MLGBucketFallChain.claimsFall(false, item, deadly));
            }
        }
    }

    // a cell that's held water on our side, landed a while ago
    private static MLGBucketFallChain.CellEnd seen(boolean stillSource, boolean gained, double distance) {
        return MLGBucketFallChain.cellEnd(stillSource, true, true, gained, distance);
    }

    @Test
    public void ourSourceGoneWithABucketGainedIsThePickup() {
        assertEquals(MLGBucketFallChain.CellEnd.PICKED_UP, seen(false, true, 1));
        // gone is gone, however far off we are
        assertEquals(MLGBucketFallChain.CellEnd.PICKED_UP, seen(false, true, 10));
        // and that's not waiting on the settle time either
        assertEquals(MLGBucketFallChain.CellEnd.PICKED_UP, MLGBucketFallChain.cellEnd(false, true, false, true, 1));
    }

    @Test
    public void goneWithoutABucketIsJustGone() {
        // flowed off a ledge, a block on it, someone else's bucket
        assertEquals(MLGBucketFallChain.CellEnd.GONE, seen(false, false, 1));
    }

    @Test
    public void aSourceInReachStaysOnTheList() {
        // a tick we couldn't click (no aim yet, somebody else had the wheel) is not the end of it
        assertEquals(MLGBucketFallChain.CellEnd.KEEP, seen(true, false, 0.5));
        assertEquals(MLGBucketFallChain.CellEnd.KEEP, seen(true, true, MLGBucketFallChain.CLEANUP_REACH));
    }

    @Test
    public void walkingOffLeavesIt() {
        assertEquals(MLGBucketFallChain.CellEnd.TOO_FAR, seen(true, false, MLGBucketFallChain.CLEANUP_WALK + 0.01));
        assertEquals(MLGBucketFallChain.CellEnd.TOO_FAR, MLGBucketFallChain.cellEnd(false, false, false, false, MLGBucketFallChain.CLEANUP_WALK + 0.01));
    }

    @Test
    public void waterThatHasntShownUpYetGetsARoundTrip() {
        // a waterlogged block fills on the server only, so right after landing ours still says dry
        assertEquals(MLGBucketFallChain.CellEnd.KEEP, MLGBucketFallChain.cellEnd(false, false, false, false, 1));
        // once the round trip is over and it never came, the server said no
        assertEquals(MLGBucketFallChain.CellEnd.GONE, MLGBucketFallChain.cellEnd(false, false, true, false, 1));
        assertEquals(MLGBucketFallChain.CellEnd.GONE, MLGBucketFallChain.cellEnd(false, false, true, true, 1));
    }

    @Test
    public void aDriftOffTheCellIsAWalkBackNotAGiveUp() {
        // past click reach but inside the walk: the cell stays on the list
        assertEquals(MLGBucketFallChain.CellEnd.KEEP, seen(true, false, MLGBucketFallChain.CLEANUP_REACH + 0.01));
        assertEquals(MLGBucketFallChain.CellEnd.KEEP, seen(true, false, MLGBucketFallChain.CLEANUP_WALK));
    }

    @Test
    public void aSurvivableDropKeepsTheBucket() {
        // 4 blocks at full hp: 1 damage, nowhere near the edge
        assertFalse(MLGBucketFallChain.clutchWorthIt(20, 1, 4, false));
        assertFalse(MLGBucketFallChain.clutchWorthIt(20, 8, 11, false));
    }

    @Test
    public void aFallThatLeavesUsOnTheEdgeGetsTheWater() {
        assertTrue(MLGBucketFallChain.clutchWorthIt(10, 5, 8, false));
        assertTrue(MLGBucketFallChain.clutchWorthIt(5, 1, 4, false));
        // exactly the floor still counts
        assertTrue(MLGBucketFallChain.clutchWorthIt(8, 2, 5, false));
        assertFalse(MLGBucketFallChain.clutchWorthIt(9, 2, 5, false));
    }

    @Test
    public void longFallsAndHazardsAlwaysGetTheWater() {
        assertTrue(MLGBucketFallChain.clutchWorthIt(20, 4, MLGBucketFallChain.CLUTCH_LONG_FALL, false));
        // a short hop onto lava is not about the damage
        assertTrue(MLGBucketFallChain.clutchWorthIt(20, 0, 2, true));
    }

    @Test
    public void aMissedPourIsLookedForAroundTheCell() {
        net.minecraft.core.BlockPos cell = new net.minecraft.core.BlockPos(10, 64, 10);
        net.minecraft.core.BlockPos near = new net.minecraft.core.BlockPos(11, 63, 10);
        net.minecraft.core.BlockPos far = new net.minecraft.core.BlockPos(12, 64, 12);
        // both inside the radius: the closer one wins
        assertEquals(near, MLGBucketFallChain.sourceNear(cell, 2, p -> p.equals(near) || p.equals(far)));
        // outside it: nothing
        assertNull(MLGBucketFallChain.sourceNear(cell, 2, p -> p.equals(new net.minecraft.core.BlockPos(13, 64, 10))));
        assertNull(MLGBucketFallChain.sourceNear(cell, 2, p -> false));
    }

    private static MLGBucketFallChain.Surface surface(boolean timedOut, boolean splashing, boolean inWater, boolean under, boolean wentUnder, boolean open) {
        return MLGBucketFallChain.surfaceStep(timedOut, splashing, inWater, under, wentUnder, open);
    }

    @Test
    public void deepUnderWithSkyAboveSwimsUp() {
        assertEquals(MLGBucketFallChain.Surface.GO, surface(false, true, true, true, true, true));
        assertEquals(MLGBucketFallChain.Surface.GO, surface(false, false, true, true, true, true));
    }

    @Test
    public void aCeilingIsNotWorthHeadbuttingButWeComeBackAfterIt() {
        // under an overhang mid plunge: hands off, not done
        assertEquals(MLGBucketFallChain.Surface.WAIT, surface(false, false, true, true, true, false));
        // drifted out from under it, still under water: up we go again
        assertEquals(MLGBucketFallChain.Surface.GO, surface(false, false, true, true, true, true));
    }

    @Test
    public void theSplashGetsAFewTicksForOurEyesToGoUnder() {
        assertEquals(MLGBucketFallChain.Surface.WAIT, surface(false, true, true, false, false, false));
        // and a shallow landing where they never did is done once the splash is
        assertEquals(MLGBucketFallChain.Surface.DONE, surface(false, false, true, false, false, false));
    }

    @Test
    public void headOutAfterBeingUnderIsTheJobDone() {
        assertEquals(MLGBucketFallChain.Surface.DONE, surface(false, true, true, false, true, false));
    }

    @Test
    public void outOfWaterOrOutOfTimeStops() {
        assertEquals(MLGBucketFallChain.Surface.DONE, surface(false, false, false, false, true, false));
        assertEquals(MLGBucketFallChain.Surface.DONE, surface(true, false, true, true, true, true));
    }

    private static final BlockPos PORTAL = new BlockPos(10, 30, 20);

    private static boolean overPortal(double x, double z, double y) {
        // the player box is 0.6 wide
        return MLGBucketFallChain.inEndPortalColumn(x - 0.3, x + 0.3, z - 0.3, z + 0.3, y, PORTAL::equals);
    }

    @Test
    public void droppingIntoAnEndPortalIsNotAFall() {
        // a tick above it, standing in it, and the head still in it on the way down
        assertTrue(overPortal(10.5, 20.5, 31.2));
        assertTrue(overPortal(10.5, 20.5, 30.1));
        assertTrue(overPortal(10.5, 20.5, 27.5));
    }

    @Test
    public void aCornerOfTheBoxOverThePortalCounts() {
        // centre in the next column over, the box still hangs into the portal one
        assertTrue(overPortal(11.2, 20.5, 31));
        assertTrue(overPortal(9.8, 19.8, 31));
    }

    @Test
    public void aFallNextToOrHighAboveThePortalIsStillAFall() {
        // the box edge right on the block line does not reach into the portal column
        assertFalse(MLGBucketFallChain.inEndPortalColumn(11.0, 11.6, 20.2, 20.8, 31, PORTAL::equals));
        assertFalse(MLGBucketFallChain.inEndPortalColumn(9.4, 10.0, 20.2, 20.8, 31, PORTAL::equals));
        assertFalse(overPortal(12.5, 20.5, 31));
        assertFalse(overPortal(10.5, 20.5, 34.5));
        assertFalse(overPortal(10.5, 20.5, 26.5));
    }
}
