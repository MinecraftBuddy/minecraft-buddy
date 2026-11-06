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
}
