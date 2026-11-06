package adris.altoclef.ui.gui.kit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class AnimTest {

    private static final long MS = 1_000_000L;

    @After
    public void restore() {
        Anim.enabled = true;
    }

    @Test
    public void startsAtFromAndLandsOnTo() {
        Anim a = new Anim(2f);
        a.set(10f, 0, 100);
        assertEquals(2f, a.value(0), 1e-5f);
        assertEquals(10f, a.value(100 * MS), 1e-5f);
        assertEquals(10f, a.value(500 * MS), 1e-5f);
        assertTrue(a.running(50 * MS));
        assertFalse(a.running(100 * MS));
    }

    @Test
    public void risesMonotonically() {
        Anim a = new Anim(0f);
        a.set(1f, 0, 160);
        float prev = -1f;
        for (int ms = 0; ms <= 160; ms += 8) {
            float v = a.value(ms * MS);
            assertTrue("went backwards at " + ms, v >= prev);
            prev = v;
        }
    }

    @Test
    public void easesOutSoTheFirstHalfCoversMoreThanHalf() {
        Anim a = new Anim(0f);
        a.set(1f, 0, 100);
        assertTrue(a.value(50 * MS) > 0.5f);
    }

    @Test
    public void retargetStartsFromTheCurrentValue() {
        Anim a = new Anim(0f);
        a.set(1f, 0, 100);
        float mid = a.value(40 * MS);
        a.set(0f, 40 * MS, 100);
        assertEquals(mid, a.value(40 * MS), 1e-5f);
        assertEquals(0f, a.value(140 * MS), 1e-5f);
        assertEquals(a.target(), 0f, 0f);
    }

    @Test
    public void sameTargetDoesNotRestart() {
        Anim a = new Anim(0f);
        a.set(1f, 0, 100);
        a.set(1f, 90 * MS, 100);
        assertEquals(1f, a.value(100 * MS), 1e-5f);
    }

    @Test
    public void reducedMotionLandsAtOnce() {
        Anim.enabled = false;
        Anim a = new Anim(0f);
        a.set(5f, 0, 200);
        assertEquals(5f, a.value(0), 0f);
        assertFalse(a.running(0));
    }

    @Test
    public void snapJumps() {
        Anim a = new Anim(0f);
        a.set(1f, 0, 100);
        a.snap(0.25f);
        assertEquals(0.25f, a.value(10 * MS), 0f);
        assertEquals(0.25f, a.target(), 0f);
    }

    @Test
    public void easingEndpoints() {
        assertEquals(0f, Easing.outCubic(0f), 0f);
        assertEquals(1f, Easing.outCubic(1f), 0f);
        assertEquals(0f, Easing.inOutCubic(0f), 0f);
        assertEquals(1f, Easing.inOutCubic(1f), 1e-6f);
        assertEquals(0.5f, Easing.inOutCubic(0.5f), 1e-6f);
        assertEquals(1f, Easing.outCubic(7f), 0f);
    }
}
