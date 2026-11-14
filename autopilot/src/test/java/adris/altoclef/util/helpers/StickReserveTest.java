package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// (Tuning stick_reserve) the wood a deep mine keeps for its next pickaxe, and when it makes that pickaxe (look 88: no sticks at
// y -24, the log 50 blocks over the cave)
public class StickReserveTest {

    @Test
    public void theFurnaceLeavesThreePlanksAndTwoSticksWorth() {
        assertEquals(5, StickReserve.fuelKeepPlanks(0));
        assertEquals(5, StickReserve.fuelKeepPlanks(1));
        assertEquals(3, StickReserve.fuelKeepPlanks(2));
        assertEquals(3, StickReserve.fuelKeepPlanks(12));
        // a bag of one log and no planks keeps the log (FuelPolicy's plan: the planks held first, the rest in logs)
        assertEquals(2, FuelPolicy.planReserve(0, StickReserve.fuelKeepPlanks(0))[0]);
        assertEquals(1, FuelPolicy.planReserve(0, StickReserve.fuelKeepPlanks(2))[0]);
    }

    @Test
    public void sticksAreMadeFromWoodPastTheReserveOnly() {
        // fewer than two sticks and five planks: four sticks from two, three planks kept
        assertTrue(StickReserve.makeSticks(0, 5, 0));
        assertTrue(StickReserve.makeSticks(1, 0, 2));
        // a log alone is the reserve's planks: none made
        assertFalse(StickReserve.makeSticks(0, 0, 1));
        assertFalse(StickReserve.makeSticks(0, 4, 0));
        // two sticks held: nothing to make
        assertFalse(StickReserve.makeSticks(2, 64, 16));
    }

    @Test
    public void theNextPickaxeComesBeforeTheLastOneIsGone() {
        // an unworn pickaxe above wood in the bag: nothing yet
        assertEquals(StickReserve.Next.NONE, StickReserve.nextPick(1, 9, 64, 4, 8, 0, true, false));
        // the last one worn (or gone): iron from three ingots for an ore that needs it, stone otherwise
        assertEquals(StickReserve.Next.IRON, StickReserve.nextPick(0, 3, 64, 2, 0, 0, true, false));
        assertEquals(StickReserve.Next.STONE, StickReserve.nextPick(0, 2, 3, 2, 0, 0, true, false));
        // an ore stone takes (iron at y 16): stone first, the ingots stay for the armour
        assertEquals(StickReserve.Next.STONE, StickReserve.nextPick(0, 9, 3, 2, 0, 0, true, true));
        assertEquals(StickReserve.Next.IRON, StickReserve.nextPick(0, 9, 2, 2, 0, 0, true, true));
        // nothing to make it from
        assertEquals(StickReserve.Next.NONE, StickReserve.nextPick(0, 2, 2, 2, 0, 0, true, false));
    }

    @Test
    public void theSticksAndTheTableComeFromTheBagOrNotAtAll() {
        // no sticks and one plank: no pickaxe (that was look 88's bag), two planks make the sticks
        assertEquals(StickReserve.Next.NONE, StickReserve.nextPick(0, 3, 0, 0, 1, 0, true, false));
        assertEquals(StickReserve.Next.IRON, StickReserve.nextPick(0, 3, 0, 0, 2, 0, true, false));
        // no table in the bag: four planks for it besides the sticks' two, or a log and two planks
        assertEquals(StickReserve.Next.NONE, StickReserve.nextPick(0, 3, 0, 0, 5, 0, false, false));
        assertEquals(StickReserve.Next.IRON, StickReserve.nextPick(0, 3, 0, 0, 6, 0, false, false));
        assertEquals(StickReserve.Next.IRON, StickReserve.nextPick(0, 3, 0, 0, 2, 1, false, false));
        assertEquals(StickReserve.Next.IRON, StickReserve.nextPick(0, 3, 0, 2, 4, 0, false, false));
    }
    // (cheap_pick_keep) look 98 at 05:17: the batch of stone pickaxes for the rock took the last two planks and left one stick
    @Test
    public void theBatchForTheRockNeverSpendsTheKeep() {
        assertEquals(0, StickReserve.cheapBatch(1, 2, true, 64));
        // two sticks and three planks are the keep itself
        assertEquals(0, StickReserve.cheapBatch(2, 3, true, 64));
        // two sticks past the keep make one; two planks past it four sticks, two
        assertEquals(1, StickReserve.cheapBatch(4, 3, true, 64));
        assertEquals(2, StickReserve.cheapBatch(2, 5, true, 64));
        // no table in the bag: its four planks first
        assertEquals(0, StickReserve.cheapBatch(2, 5, false, 64));
        assertEquals(2, StickReserve.cheapBatch(2, 9, false, 64));
        // three stone a pickaxe, two at most
        assertEquals(1, StickReserve.cheapBatch(10, 20, true, 5));
        assertEquals(2, StickReserve.cheapBatch(10, 20, true, 64));
    }

    // (kit_diamond_off_rock) only the kit's diamond pickaxe for the rock: one stone pickaxe, the keep's wood too
    @Test
    public void theKitsDiamondPickaxeAloneForTheRockGetsAStoneOneFromTheKeep() {
        assertEquals(1, StickReserve.kitRockPick(2, 0, 0, true, 3));
        assertEquals(1, StickReserve.kitRockPick(0, 2, 0, true, 3));
        assertEquals(1, StickReserve.kitRockPick(0, 0, 1, true, 3));
        // look 98's bag: one stick and no wood, nothing to make it from (the line, and the diamond one digs)
        assertEquals(0, StickReserve.kitRockPick(1, 0, 0, true, 64));
        // no table in the bag: its four planks first
        assertEquals(0, StickReserve.kitRockPick(0, 4, 0, false, 3));
        assertEquals(1, StickReserve.kitRockPick(0, 2, 1, false, 3));
        // too little stone
        assertEquals(0, StickReserve.kitRockPick(4, 9, 0, true, 2));
    }

    // (next_pick_more) look 98's bag at the end of the gold mine: 7 diamonds, 1 iron ingot, 1 stick, no planks, no logs, no
    // pickaxe above wood: NONE before, up for logs now
    @Test
    public void lookNinetyEightsBagGoesUpForLogs() {
        assertEquals(StickReserve.Next.NONE, StickReserve.nextPick(0, 1, 64, 1, 0, 0, true, false, 7, false));
        assertEquals(StickReserve.Next.LOGS, StickReserve.nextPick(0, 1, 64, 1, 0, 0, true, false, 7, true));
        assertEquals(StickReserve.Next.LOGS, StickReserve.nextPick(0, 1, 64, 1, 0, 0, false, false, 7, true));
        // the sticks there but no table and no wood for one: logs too
        assertEquals(StickReserve.Next.LOGS, StickReserve.nextPick(0, 3, 64, 2, 0, 0, false, false, 7, true));
        // a good pickaxe above wood (the kit's diamond one unworn): nothing
        assertEquals(StickReserve.Next.NONE, StickReserve.nextPick(1, 1, 64, 1, 0, 0, true, false, 7, true));
        // the logs brought: a stone one
        assertEquals(StickReserve.Next.STONE, StickReserve.nextPick(0, 1, 64, 1, 0, 3, true, false, 7, true));
    }

    @Test
    public void aDiamondPickaxeOnlyWithNoIronOrStoneOne() {
        assertEquals(StickReserve.Next.DIAMOND, StickReserve.nextPick(0, 0, 0, 2, 0, 0, true, false, 3, true));
        assertEquals(StickReserve.Next.NONE, StickReserve.nextPick(0, 0, 0, 2, 0, 0, true, false, 2, true));
        assertEquals(StickReserve.Next.NONE, StickReserve.nextPick(0, 0, 0, 2, 0, 0, true, false, 7, false));
        // iron or stone first
        assertEquals(StickReserve.Next.IRON, StickReserve.nextPick(0, 3, 0, 2, 0, 0, true, false, 7, true));
        assertEquals(StickReserve.Next.STONE, StickReserve.nextPick(0, 0, 3, 2, 0, 0, true, false, 7, true));
    }
}
