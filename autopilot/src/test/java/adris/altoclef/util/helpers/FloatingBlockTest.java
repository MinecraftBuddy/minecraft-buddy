package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FloatingBlockTest {

    @Test
    public void threeEmptyCellsUnderItIsABridge() {
        assertTrue(FloatingBlock.floating(k -> true));
    }

    @Test
    public void anyGroundWithinTheDropKeepsItAGroundBlock() {
        for (int ground = 1; ground <= FloatingBlock.DROP; ground++) {
            int at = ground;
            assertFalse(FloatingBlock.floating(k -> k != at));
        }
    }

    @Test
    public void aLedgeWithAFloorFarBelowStillFloats() {
        // floor at 4 blocks down is past the drop we look at
        assertTrue(FloatingBlock.floating(k -> k < 4));
    }
}
