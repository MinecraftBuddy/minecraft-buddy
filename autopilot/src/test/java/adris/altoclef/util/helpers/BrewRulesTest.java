package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class BrewRulesTest {

    @Test
    public void theEarliestBottleIsTheStage() {
        assertEquals(BrewRules.Stage.WATER, BrewRules.stage(1, 2, 0, 0));
        assertEquals(BrewRules.Stage.AWKWARD, BrewRules.stage(0, 3, 1, 0));
        assertEquals(BrewRules.Stage.FIRE, BrewRules.stage(0, 0, 3, 0));
        assertEquals(BrewRules.Stage.LONG_FIRE, BrewRules.stage(0, 0, 0, 3));
        assertEquals(BrewRules.Stage.NONE, BrewRules.stage(0, 0, 0, 0));
    }

    @Test
    public void waterTakesWartThenCreamThenRedstone() {
        assertEquals(BrewRules.Ingredient.WART, BrewRules.next(3, 0, 0, true));
        assertEquals(BrewRules.Ingredient.CREAM, BrewRules.next(0, 3, 0, true));
        assertEquals(BrewRules.Ingredient.REDSTONE, BrewRules.next(0, 0, 3, true));
        // short fire resistance with no dust is as far as it goes
        assertNull(BrewRules.next(0, 0, 3, false));
        assertNull(BrewRules.next(0, 0, 0, true));
    }

    @Test
    public void anIngredientBrewingCountsAsBrewed() {
        // a wart brewing over water: the next errand is the cream, never a second wart
        assertEquals(BrewRules.Stage.AWKWARD, BrewRules.after(BrewRules.Stage.WATER, BrewRules.Ingredient.WART));
        assertEquals(false, BrewRules.needsCream(BrewRules.after(BrewRules.Stage.AWKWARD, BrewRules.Ingredient.CREAM)));
        assertEquals(BrewRules.Stage.LONG_FIRE, BrewRules.after(BrewRules.Stage.FIRE, BrewRules.Ingredient.REDSTONE));
        // nothing in the slot, or one that fits none of the bottles, leaves the stage
        assertEquals(BrewRules.Stage.WATER, BrewRules.after(BrewRules.Stage.WATER, null));
        assertEquals(BrewRules.Stage.WATER, BrewRules.after(BrewRules.Stage.WATER, BrewRules.Ingredient.CREAM));
    }

    @Test
    public void fuelOnlyForAStepStillToBrew() {
        // a new stand has none
        assertEquals(true, BrewRules.needsFuel(-1, BrewRules.Stage.WATER, false));
        assertEquals(true, BrewRules.needsFuel(0, BrewRules.Stage.AWKWARD, false));
        assertEquals(false, BrewRules.needsFuel(18, BrewRules.Stage.WATER, false));
        assertEquals(true, BrewRules.needsFuel(0, BrewRules.Stage.FIRE, true));
        assertEquals(false, BrewRules.needsFuel(0, BrewRules.Stage.FIRE, false));
        assertEquals(false, BrewRules.needsFuel(0, BrewRules.Stage.LONG_FIRE, true));
    }

    @Test
    public void creamWhileAnyBottleIsBeforeFire() {
        assertEquals(true, BrewRules.needsCream(BrewRules.Stage.WATER));
        assertEquals(true, BrewRules.needsCream(BrewRules.Stage.AWKWARD));
        assertEquals(false, BrewRules.needsCream(BrewRules.Stage.FIRE));
        assertEquals(false, BrewRules.needsCream(BrewRules.Stage.NONE));
    }

    // brew_hidden_out (10 Nov 2026): a brew item in the bag's slots 9 to 12, which the stand's screen reads as armour, changes
    // places with an empty hotbar slot, else with a hotbar thing that is neither a brew item nor kept
    private static boolean[] at(int... slots) {
        boolean[] b = new boolean[36];
        for (int s : slots) b[s] = true;
        return b;
    }

    @Test
    public void aBrewItemHiddenInSlotsNineToTwelveGoesToTheHotbar() {
        // (route-16 run 49: three wart in the bag, "missing nether wart" for 8 min)
        boolean[] full = new boolean[36];
        assertArrayEquals(new int[]{10, 4}, BrewRules.hiddenMove(at(10), at(4, 7), full));
        // a full hotbar: the first slot that is neither a brew item nor kept
        assertArrayEquals(new int[]{9, 2}, BrewRules.hiddenMove(at(9, 1), full, at(0)));
        // nothing hidden, or a brew item only outside 9 to 12
        assertNull(BrewRules.hiddenMove(at(13, 0), at(5), full));
        // nowhere to go: every hotbar slot a brew item or kept
        assertNull(BrewRules.hiddenMove(at(12, 0, 1, 2), full, at(3, 4, 5, 6, 7, 8)));
    }
}
