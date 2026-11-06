package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
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
}
