package adris.altoclef.control;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EdgeBrakeTest {
    // args: wasEngaged, userTask, onGround, blocked, pathing, realMoveKey, speed, supportedAhead, dropAhead
    private static boolean start(boolean userTask, boolean onGround, boolean blocked, boolean pathing, boolean key,
                                 double speed, boolean supported, boolean drop) {
        return EdgeBrake.decide(false, userTask, onGround, blocked, pathing, key, speed, supported, drop);
    }

    private static boolean hold(boolean userTask, boolean onGround, boolean blocked, boolean pathing, boolean key,
                                double speed, boolean supported) {
        return EdgeBrake.decide(true, userTask, onGround, blocked, pathing, key, speed, supported, false);
    }

    @Test
    public void engagesOnADriftTowardADrop() {
        assertTrue(start(true, true, false, false, false, 0.1, false, true));
        // a soft shove on a rim walks us off just the same
        assertTrue(start(true, true, false, false, false, 0.02, false, true));
    }

    @Test
    public void doesNotEngageWhenSafeOrGated() {
        assertFalse(start(true, true, false, true, false, 0.1, false, true));
        assertFalse(start(true, true, false, false, false, 0.009, false, true));
        assertFalse(start(true, false, false, false, false, 0.1, false, true));
        assertFalse(start(true, true, true, false, false, 0.1, false, true));
        assertFalse(start(true, true, false, false, true, 0.1, false, true));
        assertFalse(start(false, true, false, false, false, 0.1, false, true));
        assertFalse(start(true, true, false, false, false, 0.1, true, true));
        assertFalse(start(true, true, false, false, false, 0.1, false, false));
    }

    // slowing down is the brake working: it stays on until the bot is basically stopped
    @Test
    public void holdsWhileSlowing() {
        assertTrue(hold(true, true, false, false, false, 0.02, false));
        assertTrue(hold(true, true, false, false, false, 0.004, false));
        // the jar stops us under 0.003, not at it
        assertTrue(hold(true, true, false, false, false, 0.003, false));
        assertFalse(hold(true, true, false, false, false, 0.002, false));
    }

    @Test
    public void releasesWhenSupportedOrAGateFails() {
        assertFalse(hold(true, true, false, false, false, 0.1, true));
        assertFalse(hold(true, true, false, true, false, 0.1, false));
        assertFalse(hold(true, true, false, false, true, 0.1, false));
        assertFalse(hold(true, false, false, false, false, 0.1, false));
        assertFalse(hold(false, true, false, false, false, 0.1, false));
        assertFalse(hold(true, true, true, false, false, 0.1, false));
    }
}
