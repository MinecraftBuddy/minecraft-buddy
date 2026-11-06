package baritone.pathing.movement.movements;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// a sneak bridge hanging still over a drop gives up after a second instead of riding the clamp into the movement timeout
public class TraverseBridgeStallTest {

    // the place answer is not an input on purpose: ready, attempting and no option all count the same
    private static int run(int ticks, boolean overDrop, boolean moved, int start) {
        int n = start;
        for (int i = 0; i < ticks; i++) {
            n = MovementTraverse.stallTicks(n, overDrop, moved);
        }
        return n;
    }

    @Test
    public void aSecondStillOverTheDropGivesUp() {
        int n = run(MovementTraverse.STALL_TICKS - 1, true, false, 0);
        assertFalse(MovementTraverse.bridgeGivesUp(n));
        n = MovementTraverse.stallTicks(n, true, false);
        assertTrue(MovementTraverse.bridgeGivesUp(n));
    }

    @Test
    public void movingStartsOver() {
        int n = run(15, true, false, 0);
        assertEquals(15, n);
        assertEquals(0, MovementTraverse.stallTicks(n, true, true));
        assertFalse(MovementTraverse.bridgeGivesUp(run(MovementTraverse.STALL_TICKS - 1, true, false, 0)));
    }

    @Test
    public void notOverTheDropStartsOver() {
        // a block that showed up or a small drop under dest is not a stall
        assertEquals(0, MovementTraverse.stallTicks(19, false, false));
        assertEquals(0, run(100, false, false, 0));
    }
}
