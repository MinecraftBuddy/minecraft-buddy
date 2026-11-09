package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// a slime ball stands for the cream only with the switch on, and is crafted only when no cream is in the bag
public class SlimeCreamTest {

    @Test
    public void creamIsCreamEitherWay() {
        assertTrue(SlimeCream.have(true, false, false));
        assertTrue(SlimeCream.have(true, true, true));
    }

    @Test
    public void aBallCountsOnlyWithTheSwitch() {
        assertFalse(SlimeCream.have(false, true, false));
        assertTrue(SlimeCream.have(false, true, true));
        assertFalse(SlimeCream.have(false, false, true));
    }

    @Test
    public void craftOnlyWithABallAndNoCream() {
        assertTrue(SlimeCream.craft(false, true, true));
        assertFalse(SlimeCream.craft(true, true, true));
        assertFalse(SlimeCream.craft(false, true, false));
        assertFalse(SlimeCream.craft(false, false, true));
    }
}
