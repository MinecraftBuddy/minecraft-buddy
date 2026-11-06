package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NoReturnTest {
    @Test
    public void aThenBThenAInsideTenSecondsStaysOnB() {
        NoReturn n = new NoReturn();
        n.left("A", 100);
        assertTrue(n.blocks("A", 150));
        assertTrue(n.blocks("A", 100 + NoReturn.HOLD_TICKS - 1));
    }

    @Test
    public void theHoldEndsAndOtherTargetsAreNeverHeld() {
        NoReturn n = new NoReturn();
        n.left("A", 100);
        assertFalse(n.blocks("A", 100 + NoReturn.HOLD_TICKS));
        assertFalse(n.blocks("C", 120));
    }

    @Test
    public void nothingDroppedHoldsNothing() {
        NoReturn n = new NoReturn();
        assertFalse(n.blocks("A", 0));
        n.left("A", 0);
        n.clear();
        assertFalse(n.blocks("A", 1));
    }
}
