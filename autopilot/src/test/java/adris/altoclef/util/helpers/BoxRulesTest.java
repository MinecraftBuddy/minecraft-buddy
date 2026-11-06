package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BoxRulesTest {

    @Test
    public void fourWallsAreBoxedIn() {
        assertTrue(BoxRules.boxedIn(new boolean[]{false, false, false, false}, new boolean[4]));
    }

    @Test
    public void aHoleWithAZombieInTheOnlyOpeningIsBoxedIn() {
        assertTrue(BoxRules.boxedIn(new boolean[]{true, false, false, false}, new boolean[]{true, false, false, false}));
    }

    @Test
    public void aTunnelWithTwoEmptyEndsIsNotBoxedIn() {
        assertFalse(BoxRules.boxedIn(new boolean[]{true, true, false, false}, new boolean[4]));
    }

    @Test
    public void aTunnelWithOneEndFilledStillHasTheOther() {
        assertFalse(BoxRules.boxedIn(new boolean[]{true, true, false, false}, new boolean[]{true, false, false, false}));
    }
}
