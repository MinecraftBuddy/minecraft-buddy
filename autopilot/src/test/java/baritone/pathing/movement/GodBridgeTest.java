package baritone.pathing.movement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the numbers behind walking backwards over a gap. this only knows the model (walk speed, drag, the ray from the eye), so
// it says the decisions are right for that model. whether the game matches the model is a play test
public class GodBridgeTest {
    private static final double ACCEL = GodBridge.accel(0.1);
    // 0.15 degrees, one mouse step at 100% sensitivity
    private static final double MOUSE_STEP = 0.15;

    @Test
    public void walkSpeedConvergesOnTheKnownWalkSpeed() {
        // 4.317 blocks a second is the number everybody knows
        double velocity = 0;
        double moved = 0;
        for (int i = 0; i < 100; i++) {
            moved = GodBridge.step(velocity, ACCEL);
            velocity = GodBridge.carry(moved);
        }
        assertEquals(4.317, moved * 20, 0.01);
    }

    @Test
    public void theAimLandsOnTheFaceAcrossTheWholeWindow() {
        for (double e = GodBridge.MIN_AIM; e <= GodBridge.HALF_WIDTH; e += 0.005) {
            double depth = GodBridge.depthHit(GodBridge.aimPitch(e), e);
            assertEquals("aiming for the middle at e=" + e, GodBridge.FACE_AIM, depth, 1e-9);
        }
    }

    @Test
    public void aMouseStepEitherWayStillHitsTheFace() {
        for (double e = GodBridge.MIN_AIM; e <= GodBridge.HALF_WIDTH; e += 0.005) {
            for (int sign = -1; sign <= 1; sign += 2) {
                double pitch = Math.min(90, GodBridge.aimPitch(e) + sign * MOUSE_STEP);
                assertTrue("one mouse step " + sign + " at e=" + e, GodBridge.depthOnFace(GodBridge.depthHit(pitch, e)));
            }
        }
    }

    @Test
    public void leadingByTheVelocityKeepsTheHitOnTheFace() {
        // the pitch is set a tick before the click, aimed at where the player will be. if it is a hair off in where, the
        // face is still there. each speed from standing to the walk terminal and a bit over
        for (double u = 0; u <= 0.16; u += 0.02) {
            for (double e = -0.3; e <= 0.2; e += 0.01) {
                GodBridge.Plan plan = GodBridge.decide(e, u, ACCEL, false, true);
                double arrive = e + GodBridge.step(u, ACCEL);
                if (arrive < GodBridge.MIN_AIM || arrive > GodBridge.HALF_WIDTH) {
                    continue;
                }
                for (double off = -0.004; off <= 0.004; off += 0.002) {
                    double depth = GodBridge.depthHit(plan.pitch, arrive + off);
                    assertTrue("u=" + u + " e=" + e + " off=" + off + " depth=" + depth, GodBridge.depthOnFace(depth));
                }
            }
        }
    }

    @Test
    public void withoutTheLeadItWouldMissAtWalkSpeed() {
        // aiming at where we are, instead of where we will be, lands off the face: this is why the lead exists
        double e = 0.1;
        double arrive = e + GodBridge.step(0.118, ACCEL);
        double stale = GodBridge.depthHit(GodBridge.aimPitch(e), arrive);
        assertFalse("stale aim depth " + stale, GodBridge.depthOnFace(stale));
    }

    @Test
    public void nothingFiresBeforeThePlaneIsPassed() {
        for (double e = -1; e <= 0; e += 0.01) {
            // even a crosshair that claims the face does not click from behind the plane
            GodBridge.Plan plan = GodBridge.decide(e, 0.118, ACCEL, true, true);
            assertFalse("click at e=" + e, plan.click);
        }
    }

    @Test
    public void clickOnlyFiresOnTheFaceAndWhenTheThrottleAllowsIt() {
        assertTrue(GodBridge.decide(0.1, 0.118, ACCEL, true, true).click);
        assertFalse(GodBridge.decide(0.1, 0.118, ACCEL, false, true).click);
        // a wasted click costs the throttle, which is the whole window
        assertFalse(GodBridge.decide(0.1, 0.118, ACCEL, true, false).click);
    }

    @Test
    public void theCatchFiresBeforeSupportIsLostAtWalkSpeed() {
        double walk = GodBridge.step(0.118, ACCEL);
        // the tick that would end past the limit with no click is the catch, and it sneaks that very tick
        double edge = GodBridge.SAFE_END - walk;
        GodBridge.Plan fine = GodBridge.decide(edge - 0.001, 0.118, ACCEL, false, true);
        assertFalse(fine.sneak);
        assertFalse(fine.abandon);
        GodBridge.Plan caught = GodBridge.decide(edge + 0.001, 0.118, ACCEL, false, true);
        assertTrue(caught.sneak);
        assertTrue(caught.abandon);
        // and the unsneaked end of the last tick that did not catch is still over the block
        assertTrue(edge + GodBridge.step(0.118, ACCEL) - 0.001 < GodBridge.HALF_WIDTH);
    }

    @Test
    public void aClickNeverSneaksEvenWhenTheMoveWouldEndPastTheLimit() {
        // the block is in before the move, so the common case is sneak free
        for (double e = 0.01; e <= 0.29; e += 0.01) {
            GodBridge.Plan plan = GodBridge.decide(e, 0.118, ACCEL, true, true);
            assertTrue(plan.click);
            assertFalse("sneak at e=" + e, plan.sneak);
            assertFalse(plan.abandon);
        }
    }

    @Test
    public void speedyPlayersCatchEarlierInsteadOfFalling() {
        // a speed potion: more than the window per tick, so even before the plane the catch has to fire
        double fast = GodBridge.accel(0.2);
        GodBridge.Plan plan = GodBridge.decide(-0.05, 0.2, fast, false, true);
        assertTrue(plan.abandon);
    }

    // one bridge block as the model sees it, a tick at a time. the ray is cast from where the player is now with the pitch
    // set last tick, the click lands before the move, sneak clamps the move in 0.05 steps like Player.maybeBackOffFromEdge
    private static String runOneBlock(double e0, double u0) {
        double e = e0;
        double u = u0;
        double pitch = 0;
        for (int tick = 0; tick < 60; tick++) {
            boolean onFace = e > 0 && GodBridge.depthOnFace(GodBridge.depthHit(pitch, e));
            GodBridge.Plan plan = GodBridge.decide(e, u, ACCEL, onFace, true);
            pitch = plan.pitch;
            double dx = GodBridge.step(u, ACCEL);
            if (plan.sneak) {
                while (dx != 0 && e + dx >= GodBridge.HALF_WIDTH) {
                    dx = Math.abs(dx) <= 0.05 ? 0 : dx - 0.05;
                }
            }
            if (plan.click) {
                return "placed";
            }
            if (plan.abandon) {
                return "caught";
            }
            e += dx;
            u = GodBridge.carry(dx);
            if (e >= GodBridge.HALF_WIDTH) {
                return "fell";
            }
        }
        return "stuck";
    }

    @Test
    public void everyStartingSpotPlacesOrCatchesAndNeverFalls() {
        int placed = 0;
        int caught = 0;
        int total = 0;
        // 0 to 0.153 is standing to the carried velocity of a sprint, and the start is anywhere along the block before the
        // edge. the walk spacing is 0.216, so every phase of it gets tried
        for (double u0 = 0; u0 <= 0.16; u0 += 0.01) {
            for (double e0 = -1.0; e0 <= -0.6; e0 += 0.002) {
                String result = runOneBlock(e0, u0);
                assertTrue("e0=" + e0 + " u0=" + u0 + " ended " + result, result.equals("placed") || result.equals("caught"));
                total++;
                if (result.equals("placed")) placed++;
                else caught++;
            }
        }
        // in the model the click lands whenever the lead is right, which is every phase
        assertEquals("caught " + caught + " of " + total, total, placed);
    }

    @Test
    public void evenStartsHangingOverTheEdgeOrAtSpeedNeverFall() {
        // wider than the above on purpose: already past the edge, and carrying a lot more than a sprint does. these are
        // allowed to be caught, they are not allowed to fall
        for (double u0 = 0; u0 <= 0.35; u0 += 0.01) {
            for (double e0 = -1.0; e0 <= 0.25; e0 += 0.002) {
                String result = runOneBlock(e0, u0);
                assertTrue("e0=" + e0 + " u0=" + u0 + " ended " + result, result.equals("placed") || result.equals("caught"));
            }
        }
    }

    @Test
    public void pastTheEdgeAndTheStartDistanceMath() {
        // dest is east, src at x=10: the edge is at x=10.5+0.5=11.0
        assertEquals(-0.5, GodBridge.pastEdge(10.5, 3.5, 10, 3, 1, 0), 1e-9);
        assertEquals(0.25, GodBridge.pastEdge(11.25, 3.5, 10, 3, 1, 0), 1e-9);
        // dest is north (-z), src at z=3: the edge is at z=3.0
        assertEquals(0.1, GodBridge.pastEdge(10.5, 2.9, 10, 3, 0, -1), 1e-9);
        assertEquals(0.2, GodBridge.lateral(10.5, 3.7, 10, 3, 1, 0), 1e-9);
        assertEquals(0.06, GodBridge.lateralSpeed(0.3, 0.06, 1, 0), 1e-9);
        assertEquals(0.3, GodBridge.along(0.3, 0.06, 1, 0), 1e-9);
    }

    @Test
    public void facingYawLooksBackTheWayWeCame() {
        // minecraft: yaw 0 looks +z (south), 90 looks -x (west), 180 north, -90 east
        assertEquals(90f, GodBridge.facingYaw(1, 0), 1e-4f); // going east, looking west
        assertEquals(-90f, GodBridge.facingYaw(-1, 0), 1e-4f); // going west, looking east
        assertEquals(180f, Math.abs(GodBridge.facingYaw(0, 1)), 1e-4f); // going south, looking north
        assertEquals(0f, GodBridge.facingYaw(0, -1), 1e-4f); // going north, looking south
    }
}
