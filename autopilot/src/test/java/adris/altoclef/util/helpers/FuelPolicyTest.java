package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

// which fuel a furnace may take when somebody has plans for the wood
public class FuelPolicyTest {
    // coal is 8 smelts, a log 1.5, planks 1.5 and so on, close enough for picking between them
    private static double fuel(Item item) {
        if (item == Items.COAL || item == Items.CHARCOAL) return 8;
        return 1.5;
    }

    private static boolean supported(Item item) {
        return true;
    }

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @After
    public void cleanUp() {
        FuelPolicy.clear();
    }

    @Test
    public void liftingAStackOntoTheCursorDoesNotChangeThePick() {
        // the 13:54 furnace: acacia and oak in the bag, part of the wood kept for beds. moving the acacia to the cursor moved it in
        // the list, and the pick flipped between the two species every tick with the window open
        FuelPolicy.set(4, 0, true);
        ItemStack acacia = new ItemStack(Items.ACACIA_LOG, 5);
        ItemStack oak = new ItemStack(Items.OAK_LOG, 5);
        FuelPolicy.Pick before = FuelPolicy.choose(List.of(acacia, oak), 3, FuelPolicyTest::supported, FuelPolicyTest::fuel);
        FuelPolicy.Pick after = FuelPolicy.choose(List.of(oak.copy(), acacia.copy()), 3, FuelPolicyTest::supported, FuelPolicyTest::fuel);
        assertNotNull(before);
        assertNotNull(after);
        assertEquals(before.stack().getItem(), after.stack().getItem());
        assertEquals(before.count(), after.count());
    }

    @Test
    public void theReserveLandsOnTheSameSpeciesWhateverTheOrder() {
        FuelPolicy.set(4, 0, true);
        ItemStack acacia = new ItemStack(Items.ACACIA_LOG, 5);
        ItemStack oak = new ItemStack(Items.OAK_LOG, 5);
        int[] ab = FuelPolicy.usable(List.of(acacia, oak));
        int[] ba = FuelPolicy.usable(List.of(oak, acacia));
        assertEquals(ab[0], ba[1]);
        assertEquals(ab[1], ba[0]);
    }

    @Test
    public void withNoReserveEverythingBurns() {
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_LOG, 10), new ItemStack(Items.OAK_PLANKS, 5));
        assertEquals(22.5, FuelPolicy.usableFuel(bag, FuelPolicyTest::supported, FuelPolicyTest::fuel), 1e-9);
        assertEquals(10, FuelPolicy.choose(bag, 15, FuelPolicyTest::supported, FuelPolicyTest::fuel).count());
    }

    @Test
    public void theReserveComesOffTheTopAcrossSpecies() {
        FuelPolicy.set(12, 0, false);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_LOG, 10), new ItemStack(Items.BIRCH_LOG, 5));
        // 15 logs, 12 kept: 3 burn. which species gives them up is by item now, not by list order
        assertEquals(3, java.util.Arrays.stream(FuelPolicy.usable(bag)).sum());
    }

    @Test
    public void reservedWoodIsNotFuel() {
        FuelPolicy.set(10, 4, false);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_LOG, 10), new ItemStack(Items.OAK_PLANKS, 4));
        assertEquals(0, FuelPolicy.usableFuel(bag, FuelPolicyTest::supported, FuelPolicyTest::fuel), 1e-9);
        assertNull(FuelPolicy.choose(bag, 3, FuelPolicyTest::supported, FuelPolicyTest::fuel));
    }

    @Test
    public void coalGoesFirstWhenItCoversTheJob() {
        FuelPolicy.set(0, 0, true);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_LOG, 3), new ItemStack(Items.COAL, 2));
        // 3 smelts: the closest stack would be the logs (4.5), but coal alone covers it
        FuelPolicy.Pick pick = FuelPolicy.choose(bag, 3, FuelPolicyTest::supported, FuelPolicyTest::fuel);
        assertNotNull(pick);
        assertEquals(Items.COAL, pick.stack().getItem());
    }

    @Test
    public void coalFirstMeansOneCoalItemThatCoversTheJobAlone() {
        FuelPolicy.set(0, 0, true);
        // 24 smelts of coal and 24 of charcoal are 48 pooled, but the slot takes one item and neither covers 37: the planks do
        List<ItemStack> bag = List.of(new ItemStack(Items.COAL, 3), new ItemStack(Items.CHARCOAL, 3), new ItemStack(Items.OAK_PLANKS, 40));
        assertEquals(Items.OAK_PLANKS, FuelPolicy.choose(bag, 37, FuelPolicyTest::supported, FuelPolicyTest::fuel).stack().getItem());
        // coal in two stacks does cover it, as one item, and charcoal next to it is not a candidate
        List<ItemStack> twoStacks = List.of(new ItemStack(Items.CHARCOAL, 3), new ItemStack(Items.COAL, 3), new ItemStack(Items.COAL, 3),
                new ItemStack(Items.OAK_PLANKS, 40));
        assertEquals(Items.COAL, FuelPolicy.choose(twoStacks, 37, FuelPolicyTest::supported, FuelPolicyTest::fuel).stack().getItem());
    }

    @Test
    public void coalThatFallsShortDoesNotForceItself() {
        FuelPolicy.set(0, 0, true);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_LOG, 10), new ItemStack(Items.COAL, 1));
        // 12 smelts: one coal is 8, the logs are 15. the normal pick stands
        assertEquals(Items.OAK_LOG, FuelPolicy.choose(bag, 12, FuelPolicyTest::supported, FuelPolicyTest::fuel).stack().getItem());
    }

    @Test
    public void woodIsHandedOverAsFarAsTheJobNeedsAndNoFurther() {
        // five beef want five smelts: four logs, not the fourteen the bag could spare (they would be gone from the wood budget)
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_LOG, 14));
        assertEquals(4, FuelPolicy.choose(bag, 5, FuelPolicyTest::supported, FuelPolicyTest::fuel).count());
        // coal goes in whole, it comes back out at the collect and nobody counts it
        List<ItemStack> coal = List.of(new ItemStack(Items.COAL, 5));
        assertEquals(5, FuelPolicy.choose(coal, 5, FuelPolicyTest::supported, FuelPolicyTest::fuel).count());
    }

    @Test
    public void aStalledStationOnlyGetsFuelThatCoversTheWholeJob() {
        // 5 meat, 3 spare logs is 4.5 smelts: not enough to light it for all of it, so the meat comes back out instead
        List<ItemStack> short3 = List.of(new ItemStack(Items.OAK_LOG, 3));
        assertNull(FuelPolicy.chooseCovering(short3, 5, FuelPolicyTest::supported, FuelPolicyTest::fuel));
        // four logs cover it
        List<ItemStack> four = List.of(new ItemStack(Items.OAK_LOG, 4));
        assertEquals(4, FuelPolicy.chooseCovering(four, 5, FuelPolicyTest::supported, FuelPolicyTest::fuel).count());
        // one coal covers anything up to eight
        List<ItemStack> coal = List.of(new ItemStack(Items.COAL, 1));
        assertNotNull(FuelPolicy.chooseCovering(coal, 8, FuelPolicyTest::supported, FuelPolicyTest::fuel));
        assertNull(FuelPolicy.chooseCovering(coal, 9, FuelPolicyTest::supported, FuelPolicyTest::fuel));
        // and wood the kit keeps is no fuel
        FuelPolicy.set(14, 0, false);
        assertNull(FuelPolicy.chooseCovering(List.of(new ItemStack(Items.OAK_LOG, 14)), 5, FuelPolicyTest::supported, FuelPolicyTest::fuel));
    }

    @Test
    public void onlyTheSurplusOfALogStackIsHandedOver() {
        FuelPolicy.set(6, 0, false);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_LOG, 10));
        assertEquals(4, FuelPolicy.choose(bag, 6, FuelPolicyTest::supported, FuelPolicyTest::fuel).count());
    }

    @Test
    public void aStackInTheSlotIsNeverSwappedForAnotherFuel() {
        FuelPolicy.set(0, 0, true);
        // coal waiting in the slot, planks in the bag: planks could cover the rest, but they would swap the coal out
        ItemStack coalSlot = new ItemStack(Items.COAL, 4);
        List<ItemStack> planks = List.of(new ItemStack(Items.OAK_PLANKS, 10));
        assertNull(FuelPolicy.chooseFor(planks, coalSlot, 5, FuelPolicyTest::supported, FuelPolicyTest::fuel));
        // and the other way round, a slot of planks and coal in the bag
        ItemStack planksSlot = new ItemStack(Items.OAK_PLANKS, 6);
        List<ItemStack> coal = List.of(new ItemStack(Items.COAL, 5));
        assertNull(FuelPolicy.chooseFor(coal, planksSlot, 10, FuelPolicyTest::supported, FuelPolicyTest::fuel));
        // coal and charcoal are two items, the second one swaps the first just the same
        assertNull(FuelPolicy.chooseFor(List.of(new ItemStack(Items.CHARCOAL, 5)), coalSlot, 10, FuelPolicyTest::supported, FuelPolicyTest::fuel));
    }

    @Test
    public void aSlotOfCoalIsToppedUpWithCoalOnly() {
        for (boolean coalFirst : new boolean[]{true, false}) {
            FuelPolicy.set(0, 0, coalFirst);
            ItemStack slot = new ItemStack(Items.COAL, 2);
            List<ItemStack> bag = List.of(new ItemStack(Items.OAK_PLANKS, 10), new ItemStack(Items.COAL, 3));
            // 13 smelts short: the planks are the closer fit on paper (15), but they are not the slot's item
            FuelPolicy.Pick pick = FuelPolicy.chooseFor(bag, slot, 13, FuelPolicyTest::supported, FuelPolicyTest::fuel);
            assertNotNull(pick);
            assertEquals(Items.COAL, pick.stack().getItem());
            // the count is what the slot ends up holding: the 2 in it and the 3 from the bag
            assertEquals(5, pick.count());
        }
    }

    @Test
    public void anEmptySlotStillTakesCoalFirst() {
        FuelPolicy.set(0, 0, true);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_PLANKS, 10), new ItemStack(Items.COAL, 5));
        FuelPolicy.Pick pick = FuelPolicy.chooseFor(bag, ItemStack.EMPTY, 37, FuelPolicyTest::supported, FuelPolicyTest::fuel);
        assertNotNull(pick);
        assertEquals(Items.COAL, pick.stack().getItem());
        assertEquals(5, pick.count());
    }

    @Test
    public void aTopUpStopsAtAFullStack() {
        FuelPolicy.set(0, 0, true);
        List<ItemStack> bag = List.of(new ItemStack(Items.COAL, 10));
        assertEquals(64, FuelPolicy.chooseFor(bag, new ItemStack(Items.COAL, 62), 40, FuelPolicyTest::supported, FuelPolicyTest::fuel).count());
        // nothing fits on a full one
        assertNull(FuelPolicy.chooseFor(bag, new ItemStack(Items.COAL, 64), 40, FuelPolicyTest::supported, FuelPolicyTest::fuel));
    }

    @Test
    public void aWoodTopUpGoesInAsFarAsTheJobNeeds() {
        // 4.5 smelts short is three planks on top of what is in the slot, not the ten in the bag
        ItemStack slot = new ItemStack(Items.OAK_PLANKS, 2);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_PLANKS, 10));
        assertEquals(5, FuelPolicy.chooseFor(bag, slot, 4.5, FuelPolicyTest::supported, FuelPolicyTest::fuel).count());
    }

    @Test
    public void theReserveHoldsForATopUpToo() {
        FuelPolicy.set(0, 10, false);
        ItemStack slot = new ItemStack(Items.OAK_PLANKS, 2);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_PLANKS, 10));
        assertNull(FuelPolicy.chooseFor(bag, slot, 4.5, FuelPolicyTest::supported, FuelPolicyTest::fuel));
    }

    @Test
    public void aSwapNeedsAPickThatCoversTheWholeJobAlone() {
        FuelPolicy.set(0, 0, true);
        // two planks in the slot (3 smelts), 34 still lacking: the job is 37
        ItemStack slot = new ItemStack(Items.OAK_PLANKS, 2);
        FuelPolicy.Pick pick = FuelPolicy.chooseSwap(List.of(new ItemStack(Items.COAL, 5)), slot, 34, FuelPolicyTest::supported, FuelPolicyTest::fuel);
        assertNotNull(pick);
        assertEquals(Items.COAL, pick.stack().getItem());
        // the count is what the slot holds afterwards, not what is added
        assertEquals(5, pick.count());
        // 24 smelts of coal is short of 37 even with the planks gone: the slot keeps what it has
        assertNull(FuelPolicy.chooseSwap(List.of(new ItemStack(Items.COAL, 3)), slot, 34, FuelPolicyTest::supported, FuelPolicyTest::fuel));
        // more of the slot's own item is a top-up, never a swap
        assertNull(FuelPolicy.chooseSwap(List.of(new ItemStack(Items.OAK_PLANKS, 30)), slot, 34, FuelPolicyTest::supported, FuelPolicyTest::fuel));
        // and the same chooseFor refuses the coal, the swap is its own question
        assertNull(FuelPolicy.chooseFor(List.of(new ItemStack(Items.COAL, 5)), slot, 34, FuelPolicyTest::supported, FuelPolicyTest::fuel));
    }

    @Test
    public void aSwapHonoursTheReserve() {
        FuelPolicy.set(0, 20, true);
        // 30 planks with 20 kept leave 10 (15 smelts): no swap to wood for a 37 smelt job
        ItemStack slot = new ItemStack(Items.COAL, 2);
        assertNull(FuelPolicy.chooseSwap(List.of(new ItemStack(Items.OAK_PLANKS, 30)), slot, 21, FuelPolicyTest::supported, FuelPolicyTest::fuel));
    }

    @Test
    public void nothingIsAddedToACoveredJob() {
        List<ItemStack> bag = List.of(new ItemStack(Items.COAL, 5));
        assertNull(FuelPolicy.chooseFor(bag, ItemStack.EMPTY, 0, FuelPolicyTest::supported, FuelPolicyTest::fuel));
        assertNull(FuelPolicy.chooseFor(bag, new ItemStack(Items.COAL, 1), -3, FuelPolicyTest::supported, FuelPolicyTest::fuel));
    }
}
