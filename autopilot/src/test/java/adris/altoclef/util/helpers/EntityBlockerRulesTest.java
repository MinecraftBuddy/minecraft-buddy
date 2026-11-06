package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// a blaze behind a fence: when is digging the fence the right call
public class EntityBlockerRulesTest {

    @Test
    public void aBreakableFenceInReachGetsDug() {
        assertTrue(EntityBlockerRules.shouldMine(true, 0, 2, false, false));
        assertTrue(EntityBlockerRules.shouldMine(true, 1, 4.5, false, false));
    }

    @Test
    public void twoDigsPerTargetThenWeGiveUp() {
        assertFalse(EntityBlockerRules.shouldMine(true, EntityBlockerRules.MAX_BREAKS, 2, false, false));
        assertFalse(EntityBlockerRules.shouldMine(true, EntityBlockerRules.MAX_BREAKS + 3, 2, false, false));
    }

    @Test
    public void unbreakableThingsAreLeftAlone() {
        assertFalse(EntityBlockerRules.shouldMine(false, 0, 2, false, false));
    }

    @Test
    public void neverTheBlockHoldingUsUp() {
        assertFalse(EntityBlockerRules.shouldMine(true, 0, 1, true, false));
    }

    @Test
    public void neverALavaDoorNextToOurFeet() {
        assertFalse(EntityBlockerRules.shouldMine(true, 0, 1, false, true));
    }

    @Test
    public void tooFarMeansTheDigTaskWouldHaveToWalkAndWeAreBackToStalling() {
        assertFalse(EntityBlockerRules.shouldMine(true, 0, 4.6, false, false));
    }
}
