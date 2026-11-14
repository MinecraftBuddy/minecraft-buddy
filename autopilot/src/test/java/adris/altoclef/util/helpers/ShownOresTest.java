package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// (Tuning poke_take_shown) the gold the poke holes show is taken from where the buddy stands, before the golden helmet too (test
// heel: 3 of 18 shown ores mined before the helmet)
public class ShownOresTest {

    @Test
    public void aShownOreInReachIsTaken() {
        assertTrue(ShownOres.takeable(4 * 4, true, true, true, false));
        assertTrue(ShownOres.takeable(36, true, true, true, false));
        // too far, no ray, already gone, no pick that drops it, given up
        assertFalse(ShownOres.takeable(37, true, true, true, false));
        assertFalse(ShownOres.takeable(9, true, false, true, false));
        assertFalse(ShownOres.takeable(9, false, true, true, false));
        assertFalse(ShownOres.takeable(9, true, true, false, false));
        assertFalse(ShownOres.takeable(9, true, true, true, true));
    }

    @Test
    public void aHostileMobNearHoldsItBack() {
        assertFalse(ShownOres.held(-1));
        assertTrue(ShownOres.held(3));
        assertTrue(ShownOres.held(12));
        assertFalse(ShownOres.held(12.5));
    }

    @Test
    public void aTakeThatBreaksNothingEnds() {
        assertFalse(ShownOres.stuck(1000, 1300));
        assertTrue(ShownOres.stuck(1000, 1301));
        assertTrue(ShownOres.stuck(1000, 999));
    }
}
