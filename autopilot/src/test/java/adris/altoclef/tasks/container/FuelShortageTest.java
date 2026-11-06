package adris.altoclef.tasks.container;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import adris.altoclef.tasks.speedrun.gamer.CookGate;
import adris.altoclef.tasks.speedrun.gamer.FakeFacts;
import adris.altoclef.tasks.speedrun.gamer.FurnaceJobs;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.FuelPolicy;
import adris.altoclef.util.helpers.ItemFixture;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

// fuel units for the smelt tasks: when to believe "out of fuel", and what a coal is worth in a furnace and a smoker
public class FuelShortageTest {
    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @After
    public void cleanUp() {
        FuelPolicy.clear();
    }

    @Test
    public void aBlipIsNotAShortage() {
        FuelShortage s = new FuelShortage();
        // the tick after a fuel click: gone for a few ticks, then it is in the slot
        for (long t = 100; t < 100 + FuelShortage.HOLD_TICKS - 1; t++) {
            assertFalse(s.confirmed(true, t));
        }
        assertFalse(s.confirmed(false, 109));
        // and it starts over, the earlier blip does not count towards the next
        assertFalse(s.confirmed(true, 110));
        assertFalse(s.confirmed(true, 118));
    }

    @Test
    public void aShortageThatHoldsIsConfirmed() {
        FuelShortage s = new FuelShortage();
        assertFalse(s.confirmed(true, 100));
        assertFalse(s.confirmed(true, 105));
        assertTrue(s.confirmed(true, 100 + FuelShortage.HOLD_TICKS));
        assertTrue(s.confirmed(true, 130));
        s.reset();
        assertFalse(s.confirmed(true, 131));
    }

    @Test
    public void aShortageNotAskedAboutForAWhileIsANewOne() {
        FuelShortage s = new FuelShortage();
        assertFalse(s.confirmed(true, 100));
        // other things happened for a couple of seconds, the old timestamp is not evidence
        assertFalse(s.confirmed(true, 400));
        assertTrue(s.confirmed(true, 410));
    }

    @Test
    public void coalInTheSlotCountsInCookModeToo() {
        // 8 mutton in the slot, one coal just moved in and not lit yet (the bag is empty): nothing left to fetch
        assertEquals(0, FuelShortage.needed(true, 8, 8, 0, 0, 8), 1e-9);
        // before the coal went in it was the whole batch
        assertEquals(8, FuelShortage.needed(true, 8, 8, 0, 0, 0), 1e-9);
        // the slot is what is fueled, not the target
        assertEquals(3, FuelShortage.needed(true, 3, 8, 0, 0, 0), 1e-9);
        // the iron mode: target less the output we already hold, less the fuel in the furnace
        assertEquals(10, FuelShortage.needed(false, 0, 40, 20, 5, 5), 1e-9);
    }

    @Test
    public void aFuelTripRunsUntilTheBagHoldsTheShortfall() {
        FuelShortage s = new FuelShortage();
        assertEquals(0, s.fetchTarget(0), 1e-9);
        // 8 meat, 3 items of fire left: fetch 5, and a lit reading from the next look at the screen does not cancel it
        s.fetchUntil(FuelShortage.missing(8, 3, 0, 0));
        assertEquals(5, s.fetchTarget(0), 1e-9);
        assertEquals(5, s.fetchTarget(4.5), 1e-9);
        // one coal is in the bag: the trip is over, and stays over
        assertEquals(0, s.fetchTarget(8), 1e-9);
        assertEquals(0, s.fetchTarget(0), 1e-9);
    }

    @Test
    public void aSmokerWithABitOfFireLeftAndNoFuelInTheBagIsDry() {
        // 8 mutton in, 3 items of fire left, nothing in the fuel slot and nothing to burn in the bag: this is the "Waiting..." that
        // never ended, it has to go and get 5 more
        assertTrue(FuelShortage.dry(8, 3, 0, 0, 0));
        assertEquals(5, FuelShortage.missing(8, 3, 0, 0), 1e-9);
        // a coal in the bag covers it, so it fills the slot instead of leaving
        assertFalse(FuelShortage.dry(8, 3, 0, 0, 8));
        // a coal already in the slot covers it
        assertFalse(FuelShortage.dry(8, 0, 0, 8, 0));
        // the progress on the item in hand counts too, same sum as the outer check
        assertFalse(FuelShortage.dry(8, 7.5, 0.5, 0, 0));
        // nothing in the input is nothing to fuel
        assertFalse(FuelShortage.dry(0, 0, 0, 0, 0));
    }

    @Test
    public void aCoalIsEightItemsInAFurnaceAndInASmoker() {
        // the cook gate counts a coal as 8 smelts, whatever the station
        assertEquals(8, CookGate.fuelSmelts(new FakeFacts().give(Items.COAL, 1), new OverworldConfig(), 0));
        // a coal lit is 1600 ticks in a furnace, 800 in a smoker, and the lit reading has to come out as the same 8 items
        assertEquals(8.0, StorageHelper.litItems(1600, 200), 1e-9);
        assertEquals(8.0, StorageHelper.litItems(800, 100), 1e-9);
        // 8 meat need exactly one coal, lit or (at 8 in the slot) not lit yet
        assertTrue(AsyncSmelting.fuelCovers(ItemStack.EMPTY, StorageHelper.litItems(800, 100), 8));
        assertFalse(AsyncSmelting.fuelCovers(ItemStack.EMPTY, StorageHelper.litItems(800, 100), 9));
        // half of one is half of eight, which is what the old /200 read for a lit smoker
        assertFalse(AsyncSmelting.fuelCovers(ItemStack.EMPTY, 400 / 200.0, 8));
        assertTrue(AsyncSmelting.fuelCovers(ItemStack.EMPTY, StorageHelper.litItems(400, 100), 4));
    }

    // a coal is 8 smelts, wood 1.5, close enough for picking between them (the real table is ItemHelper's, it needs a game)
    private static double fuel(Item item) {
        return item == Items.COAL || item == Items.CHARCOAL ? 8 : 1.5;
    }

    private static FuelPolicy.Pick fill(List<ItemStack> bag, ItemStack slot, int input, double lit, double progress) {
        return FuelShortage.fill(bag, slot, input, lit, progress, item -> true, FuelShortageTest::fuel);
    }

    private static double smelts(ItemStack slot) {
        return slot.isEmpty() ? 0 : fuel(slot.getItem()) * slot.getCount();
    }

    @Test
    public void coalWaitingInTheSlotCountsAsCovered() {
        FuelPolicy.set(0, 0, true);
        // 37 raw iron: one coal burning (8) and four in the slot (32) is 40. the planks in the bag are not needed and must not be
        // touched, they used to be picked because only the lit coal counted and that swapped the slot's coal out
        ItemStack slot = new ItemStack(Items.COAL, 4);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_PLANKS, 10));
        assertNull(fill(bag, slot, 37, StorageHelper.litItems(1600, 200), 0));
        assertTrue(AsyncSmelting.fuelCovers(smelts(slot), StorageHelper.litItems(1600, 200), 0, 37));
        assertEquals(37, AsyncSmelting.cookable(smelts(slot), 8, 0, 37), 1e-9);
        assertFalse(FuelShortage.stuckShort(37, 8, 0, smelts(slot), 15));
    }

    @Test
    public void aSmokerReadsTheSameCoalTheSameWay() {
        FuelPolicy.set(0, 0, true);
        ItemStack slot = new ItemStack(Items.COAL, 4);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_PLANKS, 10));
        // one coal lit is 800 ticks in a smoker, and /100 reads that as the same 8: 40 covers the 37
        double lit = StorageHelper.litItems(800, 100);
        assertEquals(8.0, lit, 1e-9);
        assertNull(fill(bag, slot, 37, lit, 0));
        assertTrue(AsyncSmelting.fuelCovers(smelts(slot), lit, 0, 37));
        // the old /200 read it as 4, 36 is short of 37 and the planks came into it
        double oldLit = 800 / 200.0;
        assertFalse(AsyncSmelting.fuelCovers(smelts(slot), oldLit, 0, 37));
        // a half burnt coal is 4 either way, at its own scale
        assertEquals(StorageHelper.litItems(800, 200), StorageHelper.litItems(400, 100), 1e-9);
    }

    @Test
    public void aPartlyFilledSlotIsToppedUpWithTheSameFuelOnly() {
        FuelPolicy.set(0, 0, true);
        ItemStack slot = new ItemStack(Items.COAL, 2);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_PLANKS, 10), new ItemStack(Items.COAL, 3));
        // 37 iron, nothing lit yet, 16 in the slot: 21 short, three coal is 24
        FuelPolicy.Pick pick = fill(bag, slot, 37, 0, 0);
        assertNotNull(pick);
        assertEquals(Items.COAL, pick.stack().getItem());
        assertEquals(5, pick.count());
        // with the fire counted too (one coal burning) it is still coal
        pick = fill(bag, slot, 37, 8, 0);
        assertEquals(Items.COAL, pick.stack().getItem());
        // and the smoker's scale gives the same answer for the same fire
        assertEquals(5, fill(bag, slot, 37, StorageHelper.litItems(800, 100), 0).count());
    }

    @Test
    public void aSlotOfPlanksDoesNotTakeTheCoalInTheBag() {
        FuelPolicy.set(0, 0, true);
        ItemStack slot = new ItemStack(Items.OAK_PLANKS, 6);
        List<ItemStack> bag = List.of(new ItemStack(Items.COAL, 4));
        // 6 planks are 9 smelts, 28 short. the coal is not the slot's item, and 4 of it (32) would not cover the job on its own
        // either, so it is not worth swapping the planks out for: nothing moves
        assertNull(fill(bag, slot, 37, 0, 0));
        assertNull(FuelShortage.swap(bag, slot, 37, 0, 0, item -> true, FuelShortageTest::fuel));
        // the bag can finish the job at the next visit, so this is where the bot leaves instead of waiting for the slot to empty
        assertTrue(FuelShortage.stuckShort(37, 0, 0, smelts(slot), 32));
        // 9 smelts in the slot and the planks cooked 9 of the 37, the job is due when the fuel is
        assertEquals(9, AsyncSmelting.cookable(smelts(slot), 0, 0, 37), 1e-9);
    }

    @Test
    public void anEmptySlotTakesCoalBeforeWood() {
        FuelPolicy.set(0, 0, true);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_PLANKS, 20), new ItemStack(Items.COAL, 5));
        FuelPolicy.Pick pick = fill(bag, ItemStack.EMPTY, 37, 0, 0);
        assertNotNull(pick);
        assertEquals(Items.COAL, pick.stack().getItem());
        assertEquals(5, pick.count());
    }

    @Test
    public void theCoalAndPlanksLoopIsOneMoveNotForever() {
        FuelPolicy.set(0, 0, true);
        // the bag has a bit less coal than the job needs and planks to spare: the first move puts the coal in, and then every
        // tick after it has to say "nothing to move" (or "more coal"), never "planks"
        ItemStack slot = ItemStack.EMPTY;
        List<ItemStack> bag = List.of(new ItemStack(Items.COAL, 4), new ItemStack(Items.OAK_PLANKS, 10));
        FuelPolicy.Pick first = fill(bag, slot, 37, 0, 0);
        assertNotNull(first);
        assertEquals(Items.COAL, first.stack().getItem());
        // coal in the slot (the first one lights a moment later), the bag has only the planks
        slot = new ItemStack(Items.COAL, first.count());
        bag = List.of(new ItemStack(Items.OAK_PLANKS, 10));
        for (double lit : new double[]{0, 8, 7.5, 7, 5}) {
            FuelPolicy.Pick again = fill(bag, slot, 37, lit, 0);
            assertNull("lit " + lit, again);
        }
    }

    private static FuelPolicy.Pick swap(List<ItemStack> bag, ItemStack slot, int input, double lit, double progress) {
        return FuelShortage.swap(bag, slot, input, lit, progress, item -> true, FuelShortageTest::fuel);
    }

    @Test
    public void leftoverPlanksInTheSlotAreSwappedForCoalThatCoversTheJob() {
        FuelPolicy.set(0, 0, true);
        // two planks left from before (3 smelts), five coal in the bag (40): nothing can be added to the slot, and the coal covers
        // all 37 on its own, so it goes in and the planks come back out
        ItemStack slot = new ItemStack(Items.OAK_PLANKS, 2);
        List<ItemStack> bag = List.of(new ItemStack(Items.COAL, 5));
        assertNull(fill(bag, slot, 37, 0, 0));
        FuelPolicy.Pick pick = swap(bag, slot, 37, 0, 0);
        assertNotNull(pick);
        assertEquals(Items.COAL, pick.stack().getItem());
        assertEquals(5, pick.count());
        // the smoker's reading of a fire that is already going counts the same way: one coal lit (8), 29 left, the coal covers it
        assertNotNull(swap(bag, slot, 37, StorageHelper.litItems(800, 100), 0));
    }

    @Test
    public void aSwapThatDoesNotCoverTheJobNeverHappens() {
        FuelPolicy.set(0, 0, true);
        ItemStack slot = new ItemStack(Items.OAK_PLANKS, 2);
        // three coal is 24, the job is 37 and the planks are 3 of it. a swap would leave it just as short, so the slot stays
        List<ItemStack> bag = List.of(new ItemStack(Items.COAL, 3));
        assertNull(swap(bag, slot, 37, 0, 0));
        // with a coal lit the job is 29, still more than the 24
        assertNull(swap(bag, slot, 37, 8, 0));
        // a pick of the slot's own item is the top-up's business, never a swap
        assertNull(swap(List.of(new ItemStack(Items.OAK_PLANKS, 30)), slot, 37, 0, 0));
        // nothing to swap out of an empty slot, and nothing short in a covered one
        assertNull(swap(List.of(new ItemStack(Items.COAL, 5)), ItemStack.EMPTY, 37, 0, 0));
        assertNull(swap(List.of(new ItemStack(Items.COAL, 5)), new ItemStack(Items.COAL, 5), 37, 0, 0));
    }

    @Test
    public void aSlotOfCoalIsSwappedForWoodOnlyWhenTheWoodCoversTheWholeJob() {
        FuelPolicy.set(0, 0, true);
        ItemStack slot = new ItemStack(Items.COAL, 2);
        // 30 planks are 45 smelts: 25 of them cover the 37, and that is all that goes in
        FuelPolicy.Pick pick = swap(List.of(new ItemStack(Items.OAK_PLANKS, 30)), slot, 37, 0, 0);
        assertNotNull(pick);
        assertEquals(Items.OAK_PLANKS, pick.stack().getItem());
        assertEquals(25, pick.count());
        // 10 planks are 15, they do not cover it
        assertNull(swap(List.of(new ItemStack(Items.OAK_PLANKS, 10)), slot, 37, 0, 0));
    }

    @Test
    public void aWoodSwapIsSizedByTheFillAfterTheSlotIsEmptied() {
        // 10 planks kept for crafting. 40 in the bag leave 30 to burn (45 smelts), which covers the 37 behind a slot of 2 coal
        FuelPolicy.set(0, 10, true);
        ItemStack slot = new ItemStack(Items.COAL, 2);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_PLANKS, 40));
        assertNotNull(swap(bag, slot, 37, 0, 0));
        // the task empties the slot first (the coal ends up on the cursor, which the bag list counts), and then the ordinary fill puts
        // in only what the job needs: 25 planks, not the 30 the reserve allows and not the 40 a held stack would swap in whole
        List<ItemStack> afterEmptying = List.of(new ItemStack(Items.COAL, 2), new ItemStack(Items.OAK_PLANKS, 40));
        FuelPolicy.Pick pick = fill(afterEmptying, ItemStack.EMPTY, 37, 0, 0);
        assertNotNull(pick);
        assertEquals(Items.OAK_PLANKS, pick.stack().getItem());
        assertEquals(25, pick.count());
        // and with that in the slot nothing is left to do
        assertNull(fill(afterEmptying, new ItemStack(Items.OAK_PLANKS, 25), 37, 0, 0));
        assertNull(swap(afterEmptying, new ItemStack(Items.OAK_PLANKS, 25), 37, 0, 0));
    }

    // the decisions the smelt tasks take, in order, until nothing moves: fill the slot, else (the shortage having held) take the slot's
    // fuel out when another covers the job alone. returns the slot at the end, and fails the test if it never settles
    private static ItemStack settle(List<ItemStack> start, int input) {
        List<ItemStack> bag = new java.util.ArrayList<>();
        for (ItemStack s : start) {
            bag.add(s.copy());
        }
        ItemStack slot = ItemStack.EMPTY;
        for (int tick = 0; tick < 12; tick++) {
            FuelPolicy.Pick pick = fill(bag, slot, input, 0, 0);
            if (pick != null) {
                Item item = pick.stack().getItem();
                int add = pick.count() - (slot.getItem() == item ? slot.getCount() : 0);
                for (ItemStack s : bag) {
                    int take = s.getItem() == item ? Math.min(add, s.getCount()) : 0;
                    s.shrink(take);
                    add -= take;
                }
                bag.removeIf(ItemStack::isEmpty);
                slot = new ItemStack(item, pick.count());
                continue;
            }
            double bagFuel = FuelPolicy.usableFuel(bag, i -> true, FuelShortageTest::fuel);
            if (FuelShortage.stuckShort(input, 0, 0, smelts(slot), bagFuel) && swap(bag, slot, input, 0, 0) != null) {
                bag.add(slot.copy());
                slot = ItemStack.EMPTY;
                continue;
            }
            return slot;
        }
        fail("never settled, the slot is " + slot);
        return slot;
    }

    @Test
    public void coalAndCharcoalNextToCoveringWoodSettleOnTheWood() {
        FuelPolicy.set(0, 0, true);
        // 24 smelts of coal and 24 of charcoal, 40 planks, a job of 37: pooled they read as coal that covers, the pick landed on
        // one of them, the top-up could not finish it, and the planks that did cover were swapped in for it every few ticks
        ItemStack slot = settle(List.of(new ItemStack(Items.COAL, 3), new ItemStack(Items.CHARCOAL, 3), new ItemStack(Items.OAK_PLANKS, 40)), 37);
        assertEquals(Items.OAK_PLANKS, slot.getItem());
        assertTrue(smelts(slot) >= 37);
        // uneven counts, same story
        slot = settle(List.of(new ItemStack(Items.COAL, 3), new ItemStack(Items.CHARCOAL, 2), new ItemStack(Items.OAK_PLANKS, 40)), 37);
        assertEquals(Items.OAK_PLANKS, slot.getItem());
        assertTrue(smelts(slot) >= 37);
    }

    @Test
    public void coalInTwoStacksIsToppedUpToCoveredNotSwappedForCharcoal() {
        FuelPolicy.set(0, 0, true);
        ItemStack slot = settle(List.of(new ItemStack(Items.CHARCOAL, 3), new ItemStack(Items.COAL, 3), new ItemStack(Items.COAL, 3),
                new ItemStack(Items.OAK_PLANKS, 40)), 37);
        assertEquals(Items.COAL, slot.getItem());
        assertEquals(6, slot.getCount());
    }

    @Test
    public void whenNothingCoversTheJobAloneTheLoadLeavesShortAndStops() {
        FuelPolicy.set(0, 0, true);
        // coal and charcoal, 24 smelts each, no wood: no single item covers 37 and there is no swap that would. one fuel goes in,
        // nothing moves after it (and stuckShort says leave, the bag has the rest for the next visit)
        ItemStack slot = settle(List.of(new ItemStack(Items.COAL, 3), new ItemStack(Items.CHARCOAL, 3)), 37);
        assertEquals(3, slot.getCount());
        assertTrue(FuelShortage.stuckShort(37, 0, 0, smelts(slot), 24));
    }

    @Test
    public void onceTheSwapIsInThereIsNothingToSwapBack() {
        FuelPolicy.set(0, 0, true);
        // the coal went in and the planks came back to the bag: covered, so neither a fill nor a swap wants anything
        ItemStack slot = new ItemStack(Items.COAL, 5);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_PLANKS, 2));
        for (double lit : new double[]{0, 8, 5.5}) {
            assertNull("lit " + lit, fill(bag, slot, 37, lit, 0));
            assertNull("lit " + lit, swap(bag, slot, 37, lit, 0));
        }
    }

    @Test
    public void leavingShortNeedsFuelInTheSlotAndEnoughInTheBag() {
        // nothing to add and short, fuel burning in the slot, and the bag could finish it: leave
        assertTrue(FuelShortage.stuckShort(37, 0, 0, 16, 21));
        // too little in the bag is a dry station, it gets fetched
        assertFalse(FuelShortage.stuckShort(37, 0, 0, 16, 20));
        assertTrue(FuelShortage.dry(37, 0, 0, 16, 20));
        // covered is not short
        assertFalse(FuelShortage.stuckShort(37, 8, 0, 32, 40));
        // an empty slot has nothing burning to leave behind
        assertFalse(FuelShortage.stuckShort(37, 0, 0, 0, 40));
    }

    @Test
    public void theJobIsDueWhenTheFuelIsNotAfterTheWholeInput() {
        // 16 in the slot and a coal lit is 24 smelts out of 37, due the tick the fire goes out, not rounded either way
        assertEquals(24, AsyncSmelting.cookable(16, 8, 0, 37), 1e-9);
        assertEquals(24.1, AsyncSmelting.cookable(16, 8.1, 0, 37), 1e-9);
        // the arrow of the item in hand is fuel already spent
        assertEquals(24.5, AsyncSmelting.cookable(16, 8, 0.5, 37), 1e-9);
        // covered is the whole input
        assertEquals(37, AsyncSmelting.cookable(40, 8, 0, 37), 1e-9);
        assertEquals(0, AsyncSmelting.cookable(0, 0, 0, 37), 1e-9);
        // the furnace takes 200 ticks an item, the smoker 100
        assertEquals(24 * 200L, FurnaceJobs.doneTick("furnace", 0, AsyncSmelting.cookable(16, 8, 0, 37)));
        assertEquals(24 * 100L, FurnaceJobs.doneTick("smoker", 0, AsyncSmelting.cookable(16, 8, 0, 37)));
    }

    // 5 raw iron with fuel for 4.4: the old round up had the job due at 5 items, a whole 0.6 of an item after the fire went out.
    // now it is due when the fire goes out, and the first trip (NEARLY early) finds it still lit and about to dry
    @Test
    public void aShortLoadIsDueWhenItsFireGoesOut() {
        double cook = AsyncSmelting.cookable(4.4, 0, 0, 5);
        assertEquals(880L, FurnaceJobs.doneTick("furnace", 0, cook));
        long arrive = FurnaceJobs.doneTick("furnace", 0, cook) - FurnacePlan.NEARLY_TICKS;
        long untilDry = 880 - arrive;
        assertTrue(FurnacePlan.dryingSoon(true, FurnacePlan.Mode.NORMAL, untilDry, FurnacePlan.NEARLY_TICKS));
    }

    // a split load with the whole batch's coal in the bag takes its own share, not the stack: 13 ore is 2 coal, the other 4 are
    // furnace 2 and 3's
    @Test
    public void aSplitLoadTakesItsShareOfTheBatchCoal() {
        FuelPolicy.set(0, 0, true);
        List<ItemStack> bag = List.of(new ItemStack(Items.COAL, 6));
        FuelPolicy.Pick share = FuelShortage.fillShare(bag, ItemStack.EMPTY, 13, 0, 0, item -> true, FuelShortageTest::fuel);
        assertEquals(Items.COAL, share.stack().getItem());
        assertEquals(2, share.count());
        // 16 exactly is still 2, and 12 is 2 too
        assertEquals(2, FuelShortage.fillShare(bag, ItemStack.EMPTY, 16, 0, 0, item -> true, FuelShortageTest::fuel).count());
        assertEquals(2, FuelShortage.fillShare(bag, ItemStack.EMPTY, 12, 0, 0, item -> true, FuelShortageTest::fuel).count());
        // a coal already in the slot: the slot should hold 2 in all
        assertEquals(2, FuelShortage.fillShare(bag, new ItemStack(Items.COAL, 1), 13, 0, 0, item -> true, FuelShortageTest::fuel).count());
        // and covered is nothing to add
        assertNull(FuelShortage.fillShare(bag, new ItemStack(Items.COAL, 2), 13, 0, 0, item -> true, FuelShortageTest::fuel));
        // the plain fill still puts the whole stack in, the leftover comes back at the collect
        assertEquals(6, fill(bag, ItemStack.EMPTY, 13, 0, 0).count());
    }
}
