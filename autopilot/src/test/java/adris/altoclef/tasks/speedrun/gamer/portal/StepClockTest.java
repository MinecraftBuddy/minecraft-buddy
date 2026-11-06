package adris.altoclef.tasks.speedrun.gamer.portal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StepClockTest {
    private static final int LIMIT = 280;

    @Test
    public void aStepWithNoStandIsStuckAfterTheLimit() {
        StepClock c = new StepClock();
        assertFalse(c.stuck("a", Double.NaN, 0, LIMIT));
        assertFalse(c.stuck("a", Double.NaN, LIMIT - 1, LIMIT));
        assertTrue(c.stuck("a", Double.NaN, LIMIT, LIMIT));
        // and the clock starts over after it fired
        assertFalse(c.stuck("a", Double.NaN, LIMIT + 1, LIMIT));
    }

    @Test
    public void aStepStillWalkingToItsStandKeepsTheClockAlive() {
        StepClock c = new StepClock();
        assertFalse(c.stuck("a", 30, 0, LIMIT));
        double d = 30;
        for (int t = 100; t <= 2000; t += 100) {
            d -= 1;
            assertFalse(c.stuck("a", d, t, LIMIT));
        }
    }

    @Test
    public void shufflingNeverBeatsTheBestSoItStillRunsOut() {
        StepClock c = new StepClock();
        assertFalse(c.stuck("a", 10, 0, LIMIT));
        boolean fired = false;
        for (int t = 10; t <= LIMIT + 20 && !fired; t += 10) {
            // back and forth around 10, never 0.5 under the best
            fired = c.stuck("a", t % 20 == 0 ? 10.2 : 11.5, t, LIMIT);
        }
        assertTrue(fired);
    }

    @Test
    public void jitterUnderTheGainDoesntCount() {
        StepClock c = new StepClock();
        assertFalse(c.stuck("a", 10, 0, LIMIT));
        assertFalse(c.stuck("a", 9.7, LIMIT - 1, LIMIT));
        assertTrue(c.stuck("a", 9.6, LIMIT, LIMIT));
    }

    @Test
    public void aNewStepStartsFresh() {
        StepClock c = new StepClock();
        assertFalse(c.stuck("a", 5, 0, LIMIT));
        assertFalse(c.stuck("b", 5, LIMIT, LIMIT));
        assertFalse(c.stuck("b", 5, LIMIT * 2 - 1, LIMIT));
        assertTrue(c.stuck("b", 5, LIMIT * 2, LIMIT));
    }
}
