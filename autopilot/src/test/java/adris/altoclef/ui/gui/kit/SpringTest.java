package adris.altoclef.ui.gui.kit;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class SpringTest {

    @After
    public void restoreMotion() {
        Anim.enabled = true;
    }

    @Test
    public void reversalPreservesPositionAndVelocity() {
        Spring spring = new Spring(0f);
        spring.set(100f, 0, 320);
        long turn = Anim.ms(70);
        float position = spring.value(turn);
        float velocity = spring.velocity(turn);
        assertTrue(position > 0f && position < 100f);
        assertTrue(velocity > 0f);
        spring.set(0f, turn, 320);
        assertEquals(position, spring.value(turn), 0.0001f);
        assertEquals(velocity, spring.velocity(turn), 0.001f);
        assertTrue(spring.value(turn + Anim.ms(1)) > position);
        assertEquals(0f, spring.value(turn + Anim.ms(500)), 0f);
        assertFalse(spring.running(turn + Anim.ms(500)));
    }

    @Test
    public void settlesWithoutOvershootAtAnyFrameRate() {
        Spring sampled = new Spring(0f);
        Spring skipped = new Spring(0f);
        sampled.set(200f, 0, 240);
        skipped.set(200f, 0, 240);
        float previous = 0f;
        for (int ms = 0; ms <= 240; ms += 4) {
            float value = sampled.value(Anim.ms(ms));
            assertTrue(value >= previous && value <= 200f);
            previous = value;
        }
        assertEquals(sampled.value(Anim.ms(135)), skipped.value(Anim.ms(135)), 0f);
        assertEquals(200f, sampled.value(Anim.ms(240)), 0f);
    }

    @Test
    public void disablingMotionSettlesAnAlreadyRunningSpring() {
        Spring spring = new Spring(0f);
        spring.set(1f, 0, 320);
        assertTrue(spring.running(Anim.ms(30)));
        Anim.enabled = false;
        assertEquals(1f, spring.value(Anim.ms(30)), 0f);
        assertFalse(spring.running(Anim.ms(30)));
        spring.set(0f, Anim.ms(30), 320);
        assertEquals(0f, spring.value(Anim.ms(30)), 0f);
    }
}
