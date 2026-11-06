package adris.altoclef.ui.gui.mc.move;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MoveOrbitTest {

    private static final double EPS = 1e-9;

    private static double dist(double[] a, double[] b) {
        double[] d = MoveMath.sub(a, b);
        return Math.sqrt(MoveMath.dot(d, d));
    }

    @Test
    public void framingLooksAtTheBuildFromThePlayersSide() {
        // the build is 100 blocks south of the player: the camera faces south and sits between them, above
        MoveOrbit o = MoveOrbit.framing(0.5, 70, 100.5, 20, 10, 30, 0.5, 0.5, 123, 128);
        double[] eye = o.eye();
        assertEquals(0, o.yaw(), 1e-9);
        assertTrue(eye[1] > 70);
        assertTrue(eye[2] < 100.5 && eye[2] > 0.5);
        assertEquals(45, o.pitch(), EPS);
        assertEquals(30 * 1.2 + 6, o.dist(), EPS);
        // east of the player is yaw 270 (-90)
        assertEquals(270, MoveOrbit.framing(50, 70, 0, 5, 5, 5, 0, 0, 0, 128).yaw(), 1e-9);
        // standing in it: the way the player faces
        assertEquals(30, MoveOrbit.framing(0.5, 70, 0.5, 5, 5, 5, 0.5, 0.5, 30, 128).yaw(), 1e-9);
        // a tiny build still gets a sane distance, a giant one stays inside the leash
        assertEquals(8, MoveOrbit.framing(0, 0, 0, 1, 1, 1, 0, 0, 0, 128).dist(), EPS);
        assertEquals(90, MoveOrbit.framing(0, 0, 0, 500, 10, 500, 0, 0, 0, 100).dist(), EPS);
    }

    @Test
    public void turningKeepsTheDistance() {
        MoveOrbit o = new MoveOrbit(10, 64, 10, 0, 30, 20);
        o.turn(400, -40);
        assertEquals(100, o.yaw(), EPS);
        assertEquals(20, o.pitch(), EPS);
        assertEquals(20, dist(o.eye(), o.center()), 1e-9);
        // never over the pole
        o.turn(0, 10_000);
        assertEquals(MoveMath.MAX_PITCH, o.pitch(), EPS);
        // yaw wraps
        o.turn(-800, 0);
        assertTrue(o.yaw() >= 0 && o.yaw() < 360);
    }

    @Test
    public void zoomIsAFactorAndClamped() {
        MoveOrbit o = new MoveOrbit(0, 0, 0, 0, 0, 20);
        o.zoom(1);
        assertEquals(17, o.dist(), EPS);
        o.zoom(-2);
        assertEquals(20 / 0.85, o.dist(), 1e-9);
        o.zoom(100);
        assertEquals(MoveOrbit.MIN_DIST, o.dist(), EPS);
        o.zoom(-1000);
        assertEquals(MoveOrbit.MAX_DIST, o.dist(), EPS);
    }

    @Test
    public void panIsFlatEvenWhenLookingDown() {
        MoveOrbit o = new MoveOrbit(0, 64, 0, 0, 80, 20);
        // facing south: forward is +z, right is -x
        o.pan(2, 0, 3);
        assertArrayEquals(new double[]{-2, 64, 3}, o.center(), EPS);
        o.pan(0, 5, 0);
        assertArrayEquals(new double[]{-2, 69, 3}, o.center(), EPS);
    }

    @Test
    public void middleDragMovesTheWorldWithTheCursor() {
        MoveOrbit o = new MoveOrbit(0, 64, 0, 0, 0, 20);
        // dragging right by 10 px at 0.1 blocks a pixel slides the camera left (+x when facing south)
        o.drag(10, 0, 0.1);
        assertArrayEquals(new double[]{1, 64, 0}, o.center(), 1e-9);
        // dragging down slides it up
        o.drag(0, 10, 0.1);
        assertArrayEquals(new double[]{1, 65, 0}, o.center(), 1e-9);
    }

    @Test
    public void theLeashPullsTheWholeRig() {
        MoveOrbit o = new MoveOrbit(300, 64, 0, 90, 0, 20);
        double[] before = MoveMath.sub(o.center(), o.eye());
        o.leash(new double[]{0, 64, 0}, 128);
        assertEquals(128, dist(o.eye(), new double[]{0, 64, 0}), 1e-9);
        // same view, just slid
        assertArrayEquals(before, MoveMath.sub(o.center(), o.eye()), 1e-9);
    }
}
