package adris.altoclef.tasks.entity;

import static org.junit.Assert.assertEquals;
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
}
