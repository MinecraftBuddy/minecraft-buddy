package adris.altoclef.tasks.resources;

import adris.altoclef.tasks.resources.HaySweep.Hoe;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// when to keep taking bales after the food math says we are fine, and when a hoe is worth the craft
public class HaySweepTest {

    @Test
    public void keepsGoingWhilePileRemains() {
        assertTrue(HaySweep.keepSweeping(true, 12, 5, 3));
    }

    @Test
    public void stopsWhenThePileIsGone() {
        assertFalse(HaySweep.keepSweeping(true, 0, 20, 3));
    }

    @Test
    public void stopsAtTheCap() {
        assertTrue(HaySweep.keepSweeping(true, 5, HaySweep.HAY_CAP - 1, 3));
        assertFalse(HaySweep.keepSweeping(true, 5, HaySweep.HAY_CAP, 3));
    }

    @Test
    public void stopsWhenTheBudgetRunsOut() {
        assertTrue(HaySweep.keepSweeping(true, 5, 10, HaySweep.BUDGET_SECONDS - 1));
        assertFalse(HaySweep.keepSweeping(true, 5, 10, HaySweep.BUDGET_SECONDS));
    }

    @Test
    public void staysOutOfTheWayWhenFoodIsStillShort() {
        // the normal hay path is already taking everything in that case
        assertFalse(HaySweep.keepSweeping(false, 12, 0, 0));
    }

    @Test
    public void smallPileIsNotWorthACraft() {
        assertEquals(Hoe.NONE, HaySweep.pickHoe(HaySweep.HOE_PILE_MIN - 1, false, true, 8, 8, 8));
        assertEquals(Hoe.STONE, HaySweep.pickHoe(HaySweep.HOE_PILE_MIN, false, true, 8, 8, 8));
    }

    @Test
    public void alreadyHoldingOneMeansNoCraft() {
        assertEquals(Hoe.NONE, HaySweep.pickHoe(20, true, true, 8, 8, 8));
    }

    @Test
    public void noTableNoHoe() {
        assertEquals(Hoe.NONE, HaySweep.pickHoe(20, false, false, 8, 8, 8));
    }

    @Test
    public void stoneBeatsWoodWhenWeHaveBoth() {
        assertEquals(Hoe.STONE, HaySweep.pickHoe(20, false, true, 2, 20, 2));
    }

    @Test
    public void stoneNeedsTwoCobbleAndSticksOrThePlanksForThem() {
        assertEquals(Hoe.STONE, HaySweep.pickHoe(20, false, true, 2, 0, 2));
        assertEquals(Hoe.STONE, HaySweep.pickHoe(20, false, true, 2, 2, 0));
        // one stick is still short, two planks cover it
        assertEquals(Hoe.STONE, HaySweep.pickHoe(20, false, true, 2, 2, 1));
        assertEquals(Hoe.NONE, HaySweep.pickHoe(20, false, true, 2, 1, 1));
        assertEquals(Hoe.NONE, HaySweep.pickHoe(20, false, true, 1, 0, 2));
    }

    @Test
    public void woodenNeedsPlanksForTheHeadAndMaybeTheSticks() {
        assertEquals(Hoe.WOODEN, HaySweep.pickHoe(20, false, true, 0, 2, 2));
        assertEquals(Hoe.NONE, HaySweep.pickHoe(20, false, true, 0, 3, 0));
        assertEquals(Hoe.WOODEN, HaySweep.pickHoe(20, false, true, 0, 4, 0));
        assertEquals(Hoe.NONE, HaySweep.pickHoe(20, false, true, 0, 1, 5));
    }

    @Test
    public void oneCobbleFallsBackToWood() {
        assertEquals(Hoe.WOODEN, HaySweep.pickHoe(20, false, true, 1, 4, 0));
    }
}
