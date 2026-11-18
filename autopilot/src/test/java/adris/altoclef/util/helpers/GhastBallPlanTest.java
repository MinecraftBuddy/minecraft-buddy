package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GhastBallPlanTest {
    static final double MIN = 3.3, REACH = 4.0, LEAD = 8.5;

    @Test
    public void theBallSpeedsUpAsTheGameMovesIt() {
        // v' = 0.95 (v + 0.1): toward 1.9 a tick
        assertEquals(1.33, GhastBallPlan.nextSpeed(1.3), 1e-9);
        assertEquals(1.9, GhastBallPlan.nextSpeed(1.9), 1e-9);
    }

    @Test
    public void ticksToTheHit() {
        // 12 blocks at 1.3 a tick and speeding up: 10.67, 9.31, 7.93 ... 0.64 on the eighth tick, past 0.5 on the ninth
        assertEquals(9, GhastBallPlan.ticksToHit(12, 1.3, 1.3));
        assertEquals(0, GhastBallPlan.ticksToHit(0.4, 1.3, 1.3));
        // not closing: never
        assertTrue(GhastBallPlan.ticksToHit(12, 0.0, 1.3) > 60);
    }

    @Test
    public void aBallWithATickInsideTheWindowIsLeftToThePunch() {
        // 9.0 at 1.3: 7.67, 6.31, 4.92, 3.50 (window 3.45 to 3.85)
        assertTrue(GhastBallPlan.punchable(9.0, 1.3, 1.3, MIN, REACH));
        assertFalse(GhastBallPlan.shield(9.0, 1.3, 1.3, MIN, REACH, LEAD));
    }

    @Test
    public void aBallThatJumpsTheWindowGetsTheShieldInTime() {
        // 9.6 at 1.3: 8.27, 6.91, 5.52, 4.10, then 2.65: never between 3.45 and 3.85
        assertFalse(GhastBallPlan.punchable(9.6, 1.3, 1.3, MIN, REACH));
        assertTrue(GhastBallPlan.shield(9.6, 1.3, 1.3, MIN, REACH, LEAD));
        // and the shield is chosen with about seven ticks to the hit, the five it needs and the server's lead
        assertTrue(GhastBallPlan.ticksToHit(9.6, 1.3, 1.3) >= 6);
    }

    @Test
    public void notYetWhileTheHitIsFurtherThanTheLead() {
        // 20 blocks out: 13 ticks or so, the plan waits (a nearer gap measures the closing better)
        assertFalse(GhastBallPlan.shield(20, 1.3, 1.3, MIN, REACH, LEAD));
    }

    @Test
    public void aTickNearAnEndOfTheWindowIsNotCountedOn() {
        // 3.32 now: inside min, but within the margin of it
        assertFalse(GhastBallPlan.punchable(3.32, 1.3, 1.3, MIN, REACH));
        // 3.95 now: in reach by 0.05 only, and the next tick is at 2.6
        assertFalse(GhastBallPlan.punchable(3.95, 1.3, 1.3, MIN, REACH));
        assertTrue(GhastBallPlan.punchable(3.6, 1.3, 1.3, MIN, REACH));
    }

    @Test
    public void aBallGoingAwayOrPastIsNoBallForTheShield() {
        assertFalse(GhastBallPlan.shield(6, 0.0, 1.3, MIN, REACH, LEAD));
        assertFalse(GhastBallPlan.shield(6, -1.0, 1.3, MIN, REACH, LEAD));
    }

    @Test
    public void aSlowCloseTakesTheWindowTwice() {
        // passing at an angle: 0.3 a tick closes the gap slowly and a tick lands in the window
        assertTrue(GhastBallPlan.punchable(5.0, 0.3, 1.3, MIN, REACH));
    }
}
