package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.Test;

public class ThrowAimTest {

    private static RandomGenerator fixed(int value) {
        return new java.util.Random() {
            @Override
            public int nextInt(int bound) {
                return Math.min(value, bound - 1);
            }
        };
    }

    @Test
    public void longestRunWins() {
        int[] runs = new int[16];
        runs[5] = 4;
        runs[9] = 2;
        assertEquals(ThrowAim.yawOf(5), ThrowAim.pick(runs, fixed(0)), 0f);
    }

    @Test
    public void tiesAreSplitByTheRandomInput() {
        int[] runs = new int[16];
        runs[0] = 3;
        runs[8] = 3;
        assertEquals(0f, ThrowAim.pick(runs, fixed(0)), 0f);
        assertEquals(180f, ThrowAim.pick(runs, fixed(1)), 0f);
    }

    @Test
    public void allZeroStillGivesAYaw() {
        Set<Float> seen = new HashSet<>();
        for (int i = 0; i < 16; i++) {
            seen.add(ThrowAim.pick(new int[16], fixed(i)));
        }
        assertEquals(16, seen.size());
    }

    @Test
    public void yawsMapToCompassDirections() {
        // mc look vector is (-sin yaw, cos yaw)
        assertEquals(0f, ThrowAim.yawOf(0), 0f);
        assertEquals(90f, ThrowAim.yawOf(4), 0f);
        assertEquals(180f, ThrowAim.yawOf(8), 0f);
        assertEquals(270f, ThrowAim.yawOf(12), 0f);
        assertEquals(-1.0, -Math.sin(Math.toRadians(ThrowAim.yawOf(4))), 1e-9);
        assertEquals(-1.0, Math.cos(Math.toRadians(ThrowAim.yawOf(8))), 1e-9);
        assertEquals(1.0, -Math.sin(Math.toRadians(ThrowAim.yawOf(12))), 1e-9);
    }

    @Test
    public void gateThrowsWhenAlignedWaitsThenGivesUp() {
        assertTrue(ThrowAim.shouldThrow(true, 0));
        assertFalse(ThrowAim.shouldThrow(false, 4));
        assertTrue(ThrowAim.shouldThrow(false, 5));
    }

    @Test
    public void alignmentWrapsYaw() {
        assertTrue(ThrowAim.isAligned(720f + 22.5f, -10f, 22.5f, -10f));
        assertTrue(ThrowAim.isAligned(-179f, -10f, 181f, -10f));
        assertFalse(ThrowAim.isAligned(90f, -10f, 22.5f, -10f));
    }
}
