package adris.altoclef.util;

import adris.altoclef.util.helpers.CraftMath;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// "how many can i craft from this inventory", the number that stops "craft all" from reaching for items we ran out of.
// strings stand in for items, the math is generic
public class CraftMathTest {

    private static final int ALL = 99999999;

    private static Map<String, Integer> inv(Object... pairs) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], (Integer) pairs[i + 1]);
        return result;
    }

    private static int crafts(List<List<String>> slots, Map<String, Integer> have, int cap) {
        return CraftMath.maxCrafts(slots, item -> have.getOrDefault(item, 0), cap);
    }

    @Test
    public void craftsForRoundsUp() {
        assertEquals(3, CraftMath.craftsFor(9, 4));
        assertEquals(1, CraftMath.craftsFor(1, 4));
        assertEquals(2, CraftMath.craftsFor(8, 4));
        assertEquals(11111111, CraftMath.craftsFor(ALL, 9));
        assertEquals(0, CraftMath.craftsFor(0, 4));
        assertEquals(5, CraftMath.craftsFor(5, 0));
    }

    @Test
    public void heldOutputComesOffTheTarget() {
        // 20 planks wanted, 10 in the bag: 3 logs, not 5
        assertEquals(3, CraftMath.craftsStillOwed(20, 10, 4));
        assertEquals(5, CraftMath.craftsStillOwed(20, 0, 4));
        // already there, or past it, is nothing to craft
        assertEquals(0, CraftMath.craftsStillOwed(20, 20, 4));
        assertEquals(0, CraftMath.craftsStillOwed(20, 33, 4));
        // "craft all" stays huge
        assertEquals(11111111, CraftMath.craftsStillOwed(ALL, 0, 9));
    }

    @Test
    public void hayToWheatIsOneSlot() {
        List<List<String>> hayToWheat = Collections.singletonList(Collections.singletonList("hay"));
        assertEquals(0, crafts(hayToWheat, inv(), ALL));
        assertEquals(5, crafts(hayToWheat, inv("hay", 5), ALL));
        // the target caps it, we do not want to burn the stack
        assertEquals(2, crafts(hayToWheat, inv("hay", 5), 2));
    }

    @Test
    public void breadNeedsThreeWheatPerCraftFromTheSamePile() {
        List<String> wheat = Collections.singletonList("wheat");
        List<List<String>> bread = Arrays.asList(wheat, wheat, wheat);
        assertEquals(0, crafts(bread, inv("wheat", 2), ALL));
        assertEquals(1, crafts(bread, inv("wheat", 3), ALL));
        assertEquals(1, crafts(bread, inv("wheat", 5), ALL));
        assertEquals(21, crafts(bread, inv("wheat", 64), ALL));
    }

    @Test
    public void anyOfSeveralItemsFillsASlotButOneTypePerSlot() {
        // planks recipe from any log, one slot
        List<List<String>> planks = Collections.singletonList(Arrays.asList("oak_log", "birch_log"));
        assertEquals(7, crafts(planks, inv("oak_log", 3, "birch_log", 7), ALL));
        // a 2x2 of any planks: 4 slots, each one stacks a single type, so a crafting table is 4 planks per craft, not a pile of mixed ones doubling up
        List<String> anyPlank = Arrays.asList("oak_planks", "birch_planks");
        List<List<String>> table = Arrays.asList(anyPlank, anyPlank, anyPlank, anyPlank);
        assertEquals(1, crafts(table, inv("oak_planks", 4), ALL));
        assertEquals(1, crafts(table, inv("oak_planks", 3, "birch_planks", 1), ALL));
        assertEquals(0, crafts(table, inv("oak_planks", 3), ALL));
    }

    @Test
    public void everySlotHasToBeFillable() {
        // sticks style: planks over planks
        List<String> planks = Collections.singletonList("planks");
        List<List<String>> sticks = Arrays.asList(planks, planks);
        assertEquals(0, crafts(sticks, inv("planks", 1), ALL));
        assertEquals(2, crafts(sticks, inv("planks", 4), ALL));
        // a slot nothing can fill kills the recipe
        assertEquals(0, CraftMath.maxCrafts(Arrays.asList(planks, Collections.<String>emptyList()), item -> 64, ALL));
    }

    @Test
    public void mixedIngredientsAreLimitedByTheScarcest() {
        // bed-ish: 3 wool + 3 planks
        List<String> wool = Collections.singletonList("wool");
        List<String> planks = Collections.singletonList("planks");
        List<List<String>> bed = Arrays.asList(wool, wool, wool, planks, planks, planks);
        assertEquals(2, crafts(bed, inv("wool", 6, "planks", 30), ALL));
        assertEquals(0, crafts(bed, inv("wool", 6), ALL));
    }

    @Test
    public void noSlotsOrNoCapIsZero() {
        assertEquals(0, crafts(Collections.<List<String>>emptyList(), inv("x", 5), ALL));
        assertEquals(0, crafts(Collections.singletonList(Collections.singletonList("x")), inv("x", 5), 0));
    }

    @Test
    public void hugeCapDoesNotLoopForever() {
        List<List<String>> one = Collections.singletonList(Collections.singletonList("x"));
        assertEquals(Integer.MAX_VALUE, crafts(one, inv("x", Integer.MAX_VALUE), Integer.MAX_VALUE));
    }

    @Test
    public void oneLogForTwoPlanksQuickMoves() {
        // 1 log in the grid makes 4 planks and we wanted 2: one craft either way, no reason to park 4 planks on the cursor
        assertEquals(true, CraftMath.quickMoveCraftsNoExtra(2, 4, 1));
    }

    @Test
    public void severalCraftsInTheGridWantingFewerGoesThroughTheCursor() {
        // 3 logs in the grid and we want 2 planks: a quick move would craft all 12 and eat logs somebody else wanted
        assertEquals(false, CraftMath.quickMoveCraftsNoExtra(2, 12, 3));
    }

    @Test
    public void wantingAllOfItQuickMoves() {
        assertEquals(true, CraftMath.quickMoveCraftsNoExtra(12, 12, 3));
    }

    @Test
    public void wantingNothingMoreNeverQuickMoves() {
        assertEquals(false, CraftMath.quickMoveCraftsNoExtra(0, 4, 1));
    }

    private static int usable(List<String> items, Map<String, Integer> bag, Map<String, Integer> grid) {
        return CraftMath.usableCount(items, item -> bag.getOrDefault(item, 0), item -> grid.getOrDefault(item, 0));
    }

    @Test
    public void usableCountIsBagPlusGrid() {
        assertEquals(5, usable(Arrays.asList("birch_log"), inv("birch_log", 2), inv("birch_log", 3)));
    }

    @Test
    public void usableCountSumsEveryItemAsked() {
        assertEquals(7, usable(Arrays.asList("birch_log", "oak_log"), inv("birch_log", 2, "oak_log", 4), inv("oak_log", 1)));
    }

    @Test
    public void usableCountIgnoresItemsNotAskedFor() {
        // logs sitting somewhere we did not ask about (another menu's slots, say) are not ours to craft with
        assertEquals(0, usable(Arrays.asList("birch_log"), inv("oak_log", 4), inv("spruce_log", 4)));
    }

    @Test
    public void usableCountCountsADuplicateOnce() {
        assertEquals(3, usable(Arrays.asList("birch_log", "birch_log"), inv("birch_log", 2), inv("birch_log", 1)));
    }

    @Test
    public void usableCountOfNothingIsZero() {
        assertEquals(0, usable(Collections.emptyList(), inv("birch_log", 2), inv()));
    }

    // planks and logs per wood, "birch" stands for birch planks, its log count lives in the second map
    private static CraftMath.TopUp<String> topUp(List<String> woods, Map<String, Integer> planks, Map<String, Integer> logs, int sameNeeded) {
        return CraftMath.sameResourceTopUp(woods, w -> planks.getOrDefault(w, 0), w -> logs.getOrDefault(w, 0), 4, sameNeeded);
    }

    @Test
    public void slabsAskForWhatTheCraftsEatNotOnePlank() {
        // 16 slabs, 6 per craft, 3 planks each: 9 planks owed
        int sameNeeded = CraftMath.craftsFor(16, 6) * 3;
        assertEquals(9, sameNeeded);
        // 2 planks + 1 log: "1 birch plank" was already met and nothing ever crafted. 6 is all this wood can be
        CraftMath.TopUp<String> got = topUp(Arrays.asList("birch"), inv("birch", 2), inv("birch", 1), sameNeeded);
        assertEquals("birch", got.item());
        assertEquals(6, got.target());
    }

    @Test
    public void mixedWoodsPickTheOneWithALog() {
        // spruce has planks but no log to convert, birch is the only one the step can do anything about
        CraftMath.TopUp<String> got = topUp(Arrays.asList("spruce", "birch"), inv("birch", 2, "spruce", 3), inv("birch", 1), 9);
        assertEquals("birch", got.item());
        assertEquals(6, got.target());
    }

    @Test
    public void biggestPileWinsAndIsCappedAtWhatIsOwed() {
        CraftMath.TopUp<String> got = topUp(Arrays.asList("birch", "oak"), inv("birch", 2), inv("birch", 1, "oak", 3), 9);
        assertEquals("oak", got.item());
        assertEquals(9, got.target());
    }

    @Test
    public void targetHoldsStillWhileLogsTurnIntoPlanks() {
        // ItemTarget equality includes the count, a target that moves every craft is a fresh task every craft
        assertEquals(9, topUp(Arrays.asList("birch"), inv("birch", 1), inv("birch", 2), 9).target());
        assertEquals(9, topUp(Arrays.asList("birch"), inv("birch", 5), inv("birch", 1), 9).target());
    }

    @Test
    public void targetIsAlwaysAboveWhatWeHold() {
        // the parent only asks when no wood holds sameNeeded yet. with a log in the bag the step must really craft
        for (int sameNeeded = 1; sameNeeded <= 27; ++sameNeeded) {
            for (int planks = 0; planks < sameNeeded; ++planks) {
                for (int logs = 1; logs <= 8; ++logs) {
                    CraftMath.TopUp<String> got = topUp(Arrays.asList("birch"), inv("birch", planks), inv("birch", logs), sameNeeded);
                    assertTrue(got.target() > planks);
                    assertTrue(got.target() <= planks + 4 * logs);
                }
            }
        }
    }

    @Test
    public void noLogAnywhereIsNoTopUp() {
        assertNull(topUp(Arrays.asList("birch", "spruce"), inv("birch", 2, "spruce", 3), inv(), 9));
        assertNull(topUp(Collections.emptyList(), inv(), inv(), 9));
        assertNull(topUp(Arrays.asList("birch"), inv(), inv("birch", 1), 0));
    }

    private static int perSlot(List<List<String>> slots, Map<String, Integer> have, int perSlot) {
        return CraftMath.perSlotToFill(slots, item -> have.getOrDefault(item, 0), perSlot);
    }

    // six slots of any wood and one of iron, the shield
    private static List<List<String>> shield() {
        List<String> planks = Arrays.asList("oak", "birch");
        return Arrays.asList(planks, planks, planks, planks, planks, planks, Arrays.asList("iron"));
    }

    @Test
    public void aBatchOfMixedWoodIsOneCraftARound() {
        // maxCrafts says 3 and it is right, three slots of each. the fill can't do that
        Map<String, Integer> have = inv("oak", 9, "birch", 9, "iron", 3);
        assertEquals(3, CraftMath.maxCrafts(shield(), item -> have.getOrDefault(item, 0), ALL));
        assertEquals(1, perSlot(shield(), have, 3));
    }

    @Test
    public void oneWoodKeepsTheBatch() {
        assertEquals(3, perSlot(shield(), inv("oak", 18, "iron", 3), 3));
        assertEquals(3, perSlot(shield(), inv("birch", 40, "iron", 3), 3));
    }

    @Test
    public void enoughOfOneWoodIsNotEnoughWithAnotherAround() {
        // oak could pay for all of it, but the fill goes by stack size: split the oak and the birch gets picked
        assertEquals(1, perSlot(shield(), inv("oak", 18, "birch", 2, "iron", 3), 3));
        assertEquals(1, perSlot(shield(), inv("oak", 64, "birch", 1, "iron", 3), 3));
    }

    @Test
    public void theBatchIsBackOnceTheOtherWoodIsGone() {
        assertEquals(1, perSlot(shield(), inv("oak", 12, "birch", 1, "iron", 2), 2));
        assertEquals(2, perSlot(shield(), inv("oak", 12, "birch", 0, "iron", 2), 2));
    }

    @Test
    public void oneCraftIsLeftAloneAndSoIsNothing() {
        assertEquals(1, perSlot(shield(), inv("oak", 3, "birch", 3, "iron", 1), 1));
        assertEquals(0, perSlot(shield(), inv(), 0));
    }

    @Test
    public void aSingleItemIngredientCantBeMixed() {
        // short of the batch is craftsPossible's business, the fill has nothing to swap
        List<String> wheat = Arrays.asList("wheat");
        assertEquals(5, perSlot(Arrays.asList(wheat, wheat, wheat), inv("wheat", 3), 5));
    }

    @Test
    public void everyMixedIngredientHasToBeCovered() {
        List<String> planks = Arrays.asList("oak", "birch");
        List<String> stones = Arrays.asList("cobble", "blackstone");
        List<List<String>> slots = Arrays.asList(planks, planks, stones, stones);
        assertEquals(2, perSlot(slots, inv("oak", 4, "cobble", 4), 2));
        assertEquals(1, perSlot(slots, inv("oak", 4, "cobble", 2, "blackstone", 2), 2));
        assertEquals(1, perSlot(slots, inv("oak", 4, "birch", 1, "cobble", 4), 2));
    }

    @Test
    public void aLoneSlotCanSwapToo() {
        // one stack on the slot and another in hand, neither reaches the batch
        List<String> logs = Arrays.asList("oak", "birch");
        assertEquals(1, perSlot(Arrays.asList(logs), inv("oak", 7, "birch", 12), 12));
        assertEquals(12, perSlot(Arrays.asList(logs), inv("birch", 12), 12));
        // the same item twice in the options is still one type
        List<String> twice = Arrays.asList("oak", "oak");
        assertEquals(2, perSlot(Arrays.asList(twice, twice), inv("oak", 4), 2));
    }

    @Test
    public void theMatchingTargetCountsOnlyTheColourItMakes() {
        // 3 village beds of other colours, 9 white wool, 6 still wanted: white goes to 3, not 3 + 3 that the wool can't pay for
        assertEquals(3, adris.altoclef.util.helpers.CraftMath.matchingTarget(0, 3, 6));
        // some white already held counts itself
        assertEquals(5, adris.altoclef.util.helpers.CraftMath.matchingTarget(2, 3, 6));
        // never past what's still wanted
        assertEquals(1, adris.altoclef.util.helpers.CraftMath.matchingTarget(0, 6, 1));
        // one colour only comes out at the old min(held + can craft, wanted)
        assertEquals(8, adris.altoclef.util.helpers.CraftMath.matchingTarget(5, 9, 3));
    }
}
