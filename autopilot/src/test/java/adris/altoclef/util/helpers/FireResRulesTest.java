package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FireResRulesTest {

    private static final double NONE = Double.POSITIVE_INFINITY;

    @Test
    public void burningOrABlazeNearIsTheTimeToDrink() {
        assertEquals(true, FireResRules.wantsToDrink(true, 0, true, NONE));
        assertEquals(true, FireResRules.wantsToDrink(true, 0, false, 12));
        assertEquals(true, FireResRules.wantsToDrink(true, 0, false, FireResRules.BLAZE_RANGE));
        // nothing about that burns
        assertEquals(false, FireResRules.wantsToDrink(true, 0, false, FireResRules.BLAZE_RANGE + 0.1));
        assertEquals(false, FireResRules.wantsToDrink(true, 0, false, NONE));
    }

    @Test
    public void nothingToDrinkIsNoDrink() {
        assertEquals(false, FireResRules.wantsToDrink(false, 0, true, 3));
    }

    @Test
    public void aRunningEffectIsDrunkAgainOnlyNearItsEnd() {
        assertEquals(false, FireResRules.wantsToDrink(true, 3600, true, 3));
        assertEquals(false, FireResRules.wantsToDrink(true, FireResRules.REDRINK_TICKS + 1, true, 3));
        assertEquals(true, FireResRules.wantsToDrink(true, FireResRules.REDRINK_TICKS, true, 3));
    }

    // the lava escape asks the food chain to stop: in the lava a wanted drink still goes down, every bite still waits
    @Test
    public void theEscapesStopLetsAFireResistanceDrinkInLavaBy() {
        assertEquals(false, FireResRules.stopHolds(true, true, true));
        // no drink wanted (none in the bag, or the effect has long to run): the stop holds
        assertEquals(true, FireResRules.stopHolds(true, true, false));
        // out of the lava, burning on the shore: the stop holds as before
        assertEquals(true, FireResRules.stopHolds(true, false, true));
        assertEquals(true, FireResRules.stopHolds(true, false, false));
    }

    @Test
    public void noStopAskedHoldsNothing() {
        assertEquals(false, FireResRules.stopHolds(false, true, true));
        assertEquals(false, FireResRules.stopHolds(false, true, false));
        assertEquals(false, FireResRules.stopHolds(false, false, true));
        assertEquals(false, FireResRules.stopHolds(false, false, false));
    }
}
