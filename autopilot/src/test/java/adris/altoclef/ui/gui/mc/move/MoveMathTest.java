package adris.altoclef.ui.gui.mc.move;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MoveMathTest {

    private static final double EPS = 1e-9;
    // fov 70, the default
    private static final double TAN = Math.tan(Math.toRadians(35));

    @Test
    public void lookFollowsMinecraftsAngles() {
        assertArrayEquals(new double[]{0, 0, 1}, MoveMath.look(0, 0), EPS);
        assertArrayEquals(new double[]{-1, 0, 0}, MoveMath.look(90, 0), EPS);
        assertArrayEquals(new double[]{0, -1, 0}, MoveMath.look(0, 90), EPS);
        // facing south, right hand points west
        assertArrayEquals(new double[]{-1, 0, 0}, MoveMath.right(0), EPS);
        assertArrayEquals(new double[]{0, 1, 0}, MoveMath.up(0, 0), EPS);
    }

    @Test
    public void orbitEyeLooksAtTheCenter() {
        double[] c = {10, 64, -5};
        double[] eye = MoveMath.orbitEye(c, 30, 45, 20);
        double[] toCenter = MoveMath.sub(c, eye);
        assertEquals(20, Math.sqrt(MoveMath.dot(toCenter, toCenter)), 1e-6);
        // pitched down: the eye is above
        assertTrue(eye[1] > c[1]);
        assertArrayEquals(MoveMath.look(30, 45), MoveMath.normalize(toCenter), 1e-9);
    }

    @Test
    public void theMiddleOfTheScreenIsStraightAhead() {
        assertArrayEquals(MoveMath.look(123, -20), MoveMath.ray(123, -20, TAN, 16 / 9.0, 0, 0), EPS);
    }

    @Test
    public void projectAndRayAreInverses() {
        double[] eye = {3, 70, 8};
        double yaw = 210;
        double pitch = 30;
        double w = 1920;
        double h = 1080;
        double aspect = w / h;
        double[] p = {-4, 61, 2};
        double[] s = MoveMath.project(p, eye, yaw, pitch, TAN, aspect, w, h);
        assertNotNull(s);
        double[] dir = MoveMath.ray(yaw, pitch, TAN, aspect, MoveMath.ndcX(s[0], w), MoveMath.ndcY(s[1], h));
        double[] want = MoveMath.normalize(MoveMath.sub(p, eye));
        assertArrayEquals(want, dir, 1e-9);
        // and the screen edge is the fov
        double[] top = MoveMath.ray(0, 0, TAN, aspect, 0, 1);
        assertEquals(Math.toRadians(35), Math.atan2(top[1], top[2]), 1e-9);
    }

    @Test
    public void behindTheEyeDoesNotProject() {
        assertNull(MoveMath.project(new double[]{0, 64, -5}, new double[]{0, 64, 0}, 0, 0, TAN, 1, 100, 100));
    }

    @Test
    public void closestPointOnTheAxis() {
        // x axis through the origin, a ray dropping straight down at x = 4.3
        double t = MoveMath.alongAxis(new double[]{0, 0, 0}, new double[]{1, 0, 0}, new double[]{4.3, 10, 2}, new double[]{0, -1, 0});
        assertEquals(4.3, t, EPS);
        // a slanted ray that passes the axis line at x = -2
        double[] from = {-2 - 5, 5, 3};
        double[] dir = MoveMath.normalize(new double[]{5, -5, -3});
        assertEquals(-2, MoveMath.alongAxis(new double[]{0, 0, 0}, new double[]{1, 0, 0}, from, dir), 1e-9);
        // the axis doesn't have to start at the origin
        assertEquals(-2 - 7, MoveMath.alongAxis(new double[]{7, 0, 0}, new double[]{1, 0, 0}, from, dir), 1e-9);
    }

    @Test
    public void aRayAlongTheAxisGivesUp() {
        assertTrue(Double.isNaN(MoveMath.alongAxis(new double[]{0, 0, 0}, new double[]{0, 1, 0},
            new double[]{0.1, 20, 0}, new double[]{0, -1, 0})));
        // just inside the cutoff: dot^2 = 0.985, 1 - 0.985 < 0.02
        double tilt = Math.sqrt(0.015);
        assertTrue(Double.isNaN(MoveMath.alongAxis(new double[]{0, 0, 0}, new double[]{0, 1, 0},
            new double[]{1, 20, 0}, MoveMath.normalize(new double[]{tilt, -Math.sqrt(1 - 0.015), 0}))));
        // and comfortably outside it works
        assertTrue(Double.isFinite(MoveMath.alongAxis(new double[]{0, 0, 0}, new double[]{0, 1, 0},
            new double[]{5, 20, 0}, MoveMath.normalize(new double[]{-1, -1, 0}))));
    }

    @Test
    public void anAxisBehindTheEyeIsNotGrabbed() {
        // the closest point of the line is behind where the ray starts
        assertTrue(Double.isNaN(MoveMath.alongAxis(new double[]{0, 0, 0}, new double[]{1, 0, 0},
            new double[]{0, 5, 0}, new double[]{0, 1, 0})));
    }

    @Test
    public void blocksAreTheDeltaFromTheGrab() {
        // grabbed at 3.4, the cursor at 3.6 is not a block yet
        assertEquals(0, MoveMath.blocks(3.4, 3.6));
        assertEquals(1, MoveMath.blocks(3.4, 4.5));
        assertEquals(-2, MoveMath.blocks(3.4, 1.3));
        assertEquals(0, MoveMath.blocks(3.4, Double.NaN));
    }

    @Test
    public void segmentDistanceInPixels() {
        assertEquals(5, MoveMath.segmentDistance(5, 5, 0, 0, 10, 0), EPS);
        // past the end is the distance to the end
        assertEquals(5, MoveMath.segmentDistance(13, 4, 0, 0, 10, 0), EPS);
        assertEquals(Math.sqrt(2), MoveMath.segmentDistance(1, 1, 0, 0, 0, 0), EPS);
    }

    @Test
    public void theLeashSlidesTheEyeBack() {
        double[] none = MoveMath.leash(new double[]{10, 0, 0}, new double[]{0, 0, 0}, 128);
        assertArrayEquals(new double[]{0, 0, 0}, none, EPS);
        double[] shift = MoveMath.leash(new double[]{200, 0, 0}, new double[]{0, 0, 0}, 128);
        assertArrayEquals(new double[]{-72, 0, 0}, shift, EPS);
    }

    @Test
    public void pitchStopsShortOfStraightDown() {
        assertEquals(89, MoveMath.clampPitch(120), EPS);
        assertEquals(-89, MoveMath.clampPitch(-91), EPS);
        assertEquals(12, MoveMath.clampPitch(12), EPS);
    }
}
