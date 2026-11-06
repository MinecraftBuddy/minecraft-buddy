package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReachHoldTest {
    // standing still with a ray gets us in on the first tick
    private static ReachHold inAlready() {
        ReachHold hold = new ReachHold();
        assertTrue(hold.step(true, false, true));
        return hold;
    }

    @Test
    public void aFlickerOfNoRayKeepsUsIn() {
        ReachHold hold = inAlready();
        for (int i = 0; i < ReachHold.HOLD_TICKS; i++) {
            assertTrue("tick " + i, hold.step(false, false, true));
        }
        // one past the hold and we let go
        assertFalse(hold.step(false, false, true));
        // and that was a real out, not a hold that comes back on its own
        assertFalse(hold.step(false, false, true));
    }

    @Test
    public void aRayInTheMiddleOfTheHoldStartsTheClockOver() {
        ReachHold hold = inAlready();
        for (int i = 0; i < ReachHold.HOLD_TICKS; i++) {
            hold.step(false, false, true);
        }
        assertTrue(hold.step(true, false, true));
        for (int i = 0; i < ReachHold.HOLD_TICKS; i++) {
            assertTrue(hold.step(false, false, true));
        }
    }

    @Test
    public void walkingAwayDropsTheHoldRightAway() {
        ReachHold hold = inAlready();
        assertFalse(hold.step(false, false, false));
        // out means out: the next miss in distance doesn't bring the hold back
        assertFalse(hold.step(false, false, true));
    }

    @Test
    public void whileWalkingItTakesThreeRaysInARow() {
        ReachHold hold = new ReachHold();
        for (int i = 1; i < ReachHold.ENTER_TICKS; i++) {
            assertFalse(hold.step(true, true, true));
        }
        assertTrue(hold.step(true, true, true));
    }

    @Test
    public void standingStillOneRayIsEnough() {
        assertTrue(new ReachHold().step(true, false, true));
    }

    @Test
    public void aBrokenStreakStartsOver() {
        ReachHold hold = new ReachHold();
        assertFalse(hold.step(true, true, true));
        assertFalse(hold.step(true, true, true));
        assertFalse(hold.step(false, true, true));
        assertFalse(hold.step(true, true, true));
        assertFalse(hold.step(true, true, true));
        assertTrue(hold.step(true, true, true));
    }

    @Test
    public void tooFarNeverGetsIn() {
        ReachHold hold = new ReachHold();
        for (int i = 0; i < 5; i++) {
            assertFalse(hold.step(true, false, false));
        }
    }
}
