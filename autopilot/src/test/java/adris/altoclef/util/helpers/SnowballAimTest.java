package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SnowballAimTest {

    // the ball's ticks as ThrowableProjectile.tick runs them: gravity, then the drag, then the move
    private static double[] simulate(double[] origin, double[] v0, int ticks) {
        double[] p = origin.clone();
        double[] v = v0.clone();
        for (int i = 0; i < ticks; i++) {
            v[1] -= SnowballAim.GRAVITY;
            for (int k = 0; k < 3; k++) {
                v[k] *= SnowballAim.DRAG;
                p[k] += v[k];
            }
        }
        return p;
    }

    private static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double[] plus(double[] p, double[] v, double n) {
        return new double[]{p[0] + v[0] * n, p[1] + v[1] * n, p[2] + v[2] * n};
    }

    // the nearest the ball comes to a target moving at vel, tick by tick (the hit test is per tick segment; whole ticks are
    // close enough with a margin)
    private static double closest(double[] origin, double[] v0, double[] target, double[] vel) {
        double best = Double.POSITIVE_INFINITY;
        for (int n = 0; n <= SnowballAim.MAX_TICKS; n++) {
            for (int s = 0; s < 10; s++) {
                double t = n + s / 10.0;
                best = Math.min(best, dist(SnowballAim.at(origin, v0, t), plus(target, vel, t)));
            }
        }
        return best;
    }

    private static final double[] EYE = {0.5, 65.52, 0.5};
    private static final double[] STILL = {0, 0, 0};

    @Test
    public void theClosedFormIsTheGamesStepByStep() {
        double[] v0 = {0.3, 0.6, 1.4};
        for (int n = 1; n <= 40; n++) {
            assertTrue("tick " + n, dist(SnowballAim.at(EYE, v0, n), simulate(EYE, v0, n)) < 1e-9);
        }
    }

    @Test
    public void hitsAStillBlaze() {
        double[] blaze = {3.5, 67.4, 15.5};
        double[] s = SnowballAim.solve(EYE, blaze, STILL, STILL);
        assertNotNull(s);
        double[] v0 = SnowballAim.velocity((float) s[0], (float) s[1], STILL);
        assertEquals(SnowballAim.SPEED, Math.sqrt(v0[0] * v0[0] + v0[1] * v0[1] + v0[2] * v0[2]), 1e-6);
        // (the float angles cost a few thousandths at 16 blocks; a blaze is 0.6 wide)
        assertTrue(dist(SnowballAim.at(EYE, v0, s[2]), blaze) < 0.02);
        // and the whole ticks of the game's own steps go through it
        assertTrue(closest(EYE, v0, blaze, STILL) < 0.05);
    }

    @Test
    public void leadsAMovingBlaze() {
        double[] blaze = {-6.5, 70.1, 14.5};
        double[] vel = {0.12, 0.04, -0.06};
        double[] s = SnowballAim.solve(EYE, blaze, vel, STILL);
        assertNotNull(s);
        double[] v0 = SnowballAim.velocity((float) s[0], (float) s[1], STILL);
        // where the blaze is when the ball gets there
        assertTrue(dist(SnowballAim.at(EYE, v0, s[2]), plus(blaze, vel, s[2])) < 0.02);
        assertTrue(closest(EYE, v0, blaze, vel) < 0.05);
        // the throw at where it is now misses it by more than its half width and the hit margin
        double[] still = SnowballAim.solve(EYE, blaze, STILL, STILL);
        double[] v1 = SnowballAim.velocity((float) still[0], (float) still[1], STILL);
        assertTrue(closest(EYE, v1, blaze, vel) > 0.6);
    }

    @Test
    public void countsTheThrowersOwnMovement() {
        double[] blaze = {10.5, 66.0, 10.5};
        double[] move = {0.15, 0, -0.1};
        double[] s = SnowballAim.solve(EYE, blaze, STILL, move);
        assertNotNull(s);
        double[] v0 = SnowballAim.velocity((float) s[0], (float) s[1], move);
        assertTrue(dist(SnowballAim.at(EYE, v0, s[2]), blaze) < 0.02);
    }

    @Test
    public void takesTheLowArcAndAimsALittleHigh() {
        // level and 20 out: some degrees over the line (the ball drops about three blocks on the way), not the lob
        double[] s = SnowballAim.solve(EYE, new double[]{0.5, 65.42, 20.5}, STILL, STILL);
        assertNotNull(s);
        assertTrue("pitch " + s[1], s[1] < -5 && s[1] > -12);
        assertTrue("flight " + s[2], s[2] > 12 && s[2] < 16);
        // straight ahead is yaw 0 (+z)
        assertEquals(0, s[0], 0.01);
        // a blaze six up at ten out and one four down at eight out
        double[] up = SnowballAim.solve(EYE, new double[]{10.5, 71.5, 0.5}, STILL, STILL);
        assertTrue(up[1] < -25);
        double[] down = SnowballAim.solve(EYE, new double[]{0.5, 61.5, -7.5}, STILL, STILL);
        assertTrue(down[1] > 20);
    }

    @Test
    public void noThrowPastWhatTheBallReaches() {
        assertNull(SnowballAim.solve(EYE, new double[]{0.5, 65.5, 150.5}, STILL, STILL));
    }

    @Test
    public void thePathEndsWhereTheFlightDoes() {
        double[] v0 = {0.2, 0.3, 1.45};
        double[][] path = SnowballAim.path(EYE, v0, 7.4);
        assertEquals(9, path.length);
        assertTrue(dist(path[0], EYE) < 1e-12);
        assertTrue(dist(path[3], simulate(EYE, v0, 3)) < 1e-9);
        assertTrue(dist(path[8], SnowballAim.at(EYE, v0, 7.4)) < 1e-12);
        assertEquals(4, SnowballAim.path(EYE, v0, 3.0).length);
    }

    @Test
    public void theGateTakesRangeSightAndArc() {
        assertNull(SnowballAim.whyNot(12, 3.5, 24, true, true, true, true));
        assertEquals("close", SnowballAim.whyNot(3, 3.5, 24, true, true, true, true));
        assertEquals("far", SnowballAim.whyNot(24.5, 3.5, 24, true, true, true, true));
        assertNull(SnowballAim.whyNot(24, 3.5, 24, true, true, true, true));
        assertEquals("no line", SnowballAim.whyNot(12, 3.5, 24, false, true, true, true));
        assertEquals("no throw", SnowballAim.whyNot(12, 3.5, 24, true, false, false, true));
        assertEquals("arc blocked", SnowballAim.whyNot(12, 3.5, 24, true, true, false, true));
        assertEquals("neutral near", SnowballAim.whyNot(12, 3.5, 24, true, true, true, false));
    }

    @Test
    public void atMostOneThrowAGap() {
        assertFalse(SnowballAim.rateOk(104, 100, 5));
        assertTrue(SnowballAim.rateOk(105, 100, 5));
        // four a second at most: five ticks apart
        int throwsInASecond = 0;
        long last = Long.MIN_VALUE / 2;
        for (long t = 0; t < 20; t++) {
            if (SnowballAim.rateOk(t, last, 5)) {
                throwsInASecond++;
                last = t;
            }
        }
        assertEquals(4, throwsInASecond);
        // a clock that went back
        assertTrue(SnowballAim.rateOk(10, 5000, 5));
    }

    @Test
    public void landsOnlyWhenTheBlazeTakesTheDamage() {
        // just hit (clock 20): a ball of 6 ticks lands at 14, nothing; one of 10 lands at 10, open
        assertFalse(SnowballAim.hurtOk(20, 6, 100, Long.MIN_VALUE, 11));
        assertTrue(SnowballAim.hurtOk(20, 10, 100, Long.MIN_VALUE, 11));
        assertTrue(SnowballAim.hurtOk(0, 3, 100, Long.MIN_VALUE, 11));
        // a ball still flying lands at 108: the next lands 11 or more after it
        assertFalse(SnowballAim.hurtOk(0, 8, 105, 108, 11));
        assertTrue(SnowballAim.hurtOk(0, 8, 111, 108, 11));
        // once the client has had time to hear of the landing, the clock alone
        assertTrue(SnowballAim.hurtOk(0, 2, 108 + SnowballAim.LANDING_SEEN, 108, 11));
        assertFalse(SnowballAim.hurtOk(0, 2, 108 + SnowballAim.LANDING_SEEN - 1, 108, 11));
    }

    @Test
    public void throwsOnlyOutOfASettledLook() {
        assertTrue(SnowballAim.settled(10, 5, 12, 4, 3));
        assertFalse(SnowballAim.settled(10, 5, 14, 4, 3));
        assertFalse(SnowballAim.settled(10, 5, 10, 9, 3));
        // round the wrap
        assertTrue(SnowballAim.settled(359, 0, -0.5f, 0, 3));
        assertTrue(SnowballAim.settled(-179, 0, 179, 0, 3));
    }

    @Test
    public void theLeadIsTheBlazesSpeedCapped() {
        double[] v = SnowballAim.targetVelocity(new double[]{0, 64, 0}, new double[]{0.4, 64.2, -4}, 4, 0.5);
        assertEquals(0.1, v[0], 1e-9);
        assertEquals(0.05, v[1], 1e-9);
        assertEquals(-0.5, v[2], 1e-9);
        double[] none = SnowballAim.targetVelocity(new double[]{0, 64, 0}, new double[]{1, 64, 0}, 1, 0.5);
        assertEquals(0, none[0], 0);
        assertEquals(0, SnowballAim.targetVelocity(null, new double[]{1, 2, 3}, 4, 0.5)[0], 0);
    }

    @Test
    public void theDefenseLeavesTheBuddysOwnBallsAlone() {
        // the owner known: always the buddy's
        assertTrue(SnowballAim.ownThrow(true, false, 500, Long.MIN_VALUE / 2, 30));
        // a snowball just thrown, close by, owner not known yet
        assertTrue(SnowballAim.ownThrow(false, true, 102, 100, 1.2));
        assertTrue(SnowballAim.ownThrow(false, true, 100 + SnowballAim.OWN_TICKS, 100, 7.5));
        // later, farther, or not a snowball: the defense's as before
        assertFalse(SnowballAim.ownThrow(false, true, 100 + SnowballAim.OWN_TICKS + 1, 100, 1.5));
        assertFalse(SnowballAim.ownThrow(false, true, 102, 100, SnowballAim.OWN_DISTANCE + 1));
        assertFalse(SnowballAim.ownThrow(false, false, 102, 100, 1.5));
        // none thrown (a clock gone back)
        assertFalse(SnowballAim.ownThrow(false, true, 50, 100, 1.5));
    }

    @Test
    public void aMobNearTheBallsWayIsSeen() {
        // a zombified piglin's box (0.6 wide, 1.95 high) at x 10, beside the way along z
        double[] piglin = {9.7, 64, 4.7, 10.3, 65.95, 5.3};
        double[] a = {10.5, 65.5, 0};
        double[] b = {10.5, 65.5, 10};
        // the way passes 0.2 beside its box: within the margin of 1
        assertTrue(SnowballAim.segmentNearBox(a, b, piglin, 1));
        // two blocks off to the side: clear
        double[] a2 = {12.5, 65.5, 0}, b2 = {12.5, 65.5, 10};
        assertFalse(SnowballAim.segmentNearBox(a2, b2, piglin, 1));
        // a segment that stops short of it
        assertFalse(SnowballAim.segmentNearBox(a, new double[]{10.5, 65.5, 3}, piglin, 1));
        // over its head by more than the margin
        assertFalse(SnowballAim.segmentNearBox(new double[]{10, 68, 0}, new double[]{10, 68, 10}, piglin, 1));
        // a thrown arc: the path's segments, one of them through it
        double[] eye = {0.5, 65.42, 0.5};
        double[] s = SnowballAim.solve(eye, new double[]{0.5, 65.4, 20.5}, new double[]{0, 0, 0}, new double[]{0, 0, 0});
        double[][] path = SnowballAim.path(eye, SnowballAim.velocity((float) s[0], (float) s[1], new double[]{0, 0, 0}), s[2]);
        double[] mid = SnowballAim.at(eye, SnowballAim.velocity((float) s[0], (float) s[1], new double[]{0, 0, 0}), s[2] / 2);
        double[] onTheWay = {mid[0] - 0.3, mid[1] - 1, mid[2] - 0.3, mid[0] + 0.3, mid[1] + 0.95, mid[2] + 0.3};
        assertTrue(SnowballAim.pathNearBox(path, onTheWay, 1));
        double[] aside = {mid[0] + 3, mid[1] - 1, mid[2] - 0.3, mid[0] + 3.6, mid[1] + 0.95, mid[2] + 0.3};
        assertFalse(SnowballAim.pathNearBox(path, aside, 1));
    }

    @Test
    public void theShieldsHandIsASwordAPickaxeOrAnEmptySlot() {
        int o = SnowballAim.KIND_OTHER, sw = SnowballAim.KIND_SWORD, pk = SnowballAim.KIND_PICKAXE, e = SnowballAim.KIND_EMPTY;
        assertEquals(4, SnowballAim.shieldSlot(new int[]{o, pk, e, o, sw, o, o, o, o}));
        assertEquals(1, SnowballAim.shieldSlot(new int[]{o, pk, e, o, o, o, o, o, o}));
        assertEquals(2, SnowballAim.shieldSlot(new int[]{o, o, e, o, o, o, o, o, o}));
        // the hotbar all snowballs, blocks and food: no slot, no shield
        assertEquals(-1, SnowballAim.shieldSlot(new int[]{o, o, o, o, o, o, o, o, o}));
    }
}
