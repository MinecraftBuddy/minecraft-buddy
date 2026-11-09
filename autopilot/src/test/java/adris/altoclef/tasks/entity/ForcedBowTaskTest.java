package adris.altoclef.tasks.entity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the forced fight's bow aims above the mob by the arrow's drop, more the farther it is, never far over its top
public class ForcedBowTaskTest {
    @Test
    public void theAimRisesWithTheDistance() {
        double[] eye = {0, 65.62, 0};
        double[] near = ForcedBowTask.aimPoint(eye, new double[]{0, 67.5, 10}, 68.4);
        double[] far = ForcedBowTask.aimPoint(eye, new double[]{0, 67.5, 30}, 68.4);
        assertTrue(near[1] > 67.5 && near[1] < 67.9);
        assertTrue(far[1] > near[1]);
        assertEquals(0, far[0], 1e-9);
        assertEquals(30, far[2], 1e-9);
    }

    @Test
    public void theRotationLooksAtThePoint() {
        float[] r = ForcedBowTask.rotationTo(new double[]{0, 64, 0}, new double[]{0, 64, 10});
        assertEquals(0, r[0], 1e-4);
        assertEquals(0, r[1], 1e-4);
        float[] up = ForcedBowTask.rotationTo(new double[]{0, 64, 0}, new double[]{-10, 74, 0});
        assertEquals(90, up[0], 1e-4);
        assertEquals(-45, up[1], 1e-3);
    }

    // a blaze 10 blocks off and 3 up: its box 0.6 wide, 1.8 tall
    private static final double[] EYE = {0, 65.62, 0};
    private static final double[] BLAZE = {-0.3, 67, 9.7, 0.3, 68.8, 10.3};

    @Test
    public void aLookAtTheRaisedPointMeetsTheBlaze() {
        double[] point = ForcedBowTask.aimPoint(EYE, new double[]{0, 67.9, 10}, 68.8);
        float[] r = ForcedBowTask.rotationTo(EYE, point);
        assertTrue(ForcedBowTask.onTarget(EYE, r[0], r[1], BLAZE));
    }

    @Test
    public void aLookBesideOrBelowItMisses() {
        double[] point = ForcedBowTask.aimPoint(EYE, new double[]{0, 67.9, 10}, 68.8);
        float[] r = ForcedBowTask.rotationTo(EYE, point);
        // three degrees aside is half a block at ten blocks: past its side
        assertFalse(ForcedBowTask.onTarget(EYE, r[0] + 3, r[1], BLAZE));
        // aimed at its feet the arrow drops under them
        float[] feet = ForcedBowTask.rotationTo(EYE, new double[]{0, 67.05, 10});
        assertFalse(ForcedBowTask.onTarget(EYE, feet[0], feet[1], BLAZE));
        // straight up never meets anything across
        assertFalse(ForcedBowTask.onTarget(EYE, 0, -90, BLAZE));
    }

    @Test
    public void theDirectionMatchesTheGame() {
        // yaw 0 looks to +z, yaw 90 to -x, pitch -90 straight up
        double[] south = ForcedBowTask.direction(0, 0);
        assertEquals(1, south[2], 1e-9);
        double[] west = ForcedBowTask.direction(90, 0);
        assertEquals(-1, west[0], 1e-9);
        double[] up = ForcedBowTask.direction(0, -90);
        assertEquals(1, up[1], 1e-9);
    }
}
