package adris.altoclef.tasks.construction;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// step off, walk back on, step off. it has to notice, and it must not count one step off ten times
public class StepOffGuardTest {

    @Test
    public void tripsOnTheFourthSeparateStepOff() {
        StepOffGuard guard = new StepOffGuard(3);
        for (int lap = 1; lap <= 3; ++lap) {
            assertFalse("lap " + lap, guard.tick(true));
            assertFalse(guard.tick(false));
        }
        assertTrue(guard.tick(true));
    }

    @Test
    public void aLongStepOffIsOneStepOff() {
        StepOffGuard guard = new StepOffGuard(3);
        for (int tick = 0; tick < 100; ++tick) {
            assertFalse(guard.tick(true));
        }
    }

    @Test
    public void startsOverAfterTripping() {
        StepOffGuard guard = new StepOffGuard(1);
        guard.tick(true);
        guard.tick(false);
        assertTrue(guard.tick(true));
        guard.tick(false);
        assertFalse(guard.tick(true));
    }

    @Test
    public void miningClearsTheCount() {
        StepOffGuard guard = new StepOffGuard(2);
        for (int lap = 0; lap < 2; ++lap) {
            guard.tick(true);
            guard.tick(false);
        }
        guard.reset();
        for (int lap = 0; lap < 2; ++lap) {
            assertFalse(guard.tick(true));
            guard.tick(false);
        }
        assertTrue(guard.tick(true));
    }
}
