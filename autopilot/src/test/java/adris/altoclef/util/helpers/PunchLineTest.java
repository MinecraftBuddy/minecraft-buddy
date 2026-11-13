package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PunchLineTest {
    // a ball at the origin sent east toward its ghast 20 blocks off
    @Test
    public void aBystanderOnTheWayOrPastTheGhastCounts() {
        assertTrue(PunchLine.near(0, 0, 0, 20, 0, 0, PunchLine.LENGTH, PunchLine.RADIUS, 10, 1, 2));
        // past the ghast, where a ball that misses it flies on
        assertTrue(PunchLine.near(0, 0, 0, 20, 0, 0, PunchLine.LENGTH, PunchLine.RADIUS, 40, 0, -2.5));
        // just behind the ball counts as at the ball
        assertTrue(PunchLine.near(0, 0, 0, 20, 0, 0, PunchLine.LENGTH, PunchLine.RADIUS, -1, 0, 0));
    }

    @Test
    public void oneOffTheLineOrBeyondItsLengthDoesNot() {
        assertFalse(PunchLine.near(0, 0, 0, 20, 0, 0, PunchLine.LENGTH, PunchLine.RADIUS, 10, 0, 4));
        assertFalse(PunchLine.near(0, 0, 0, 20, 0, 0, PunchLine.LENGTH, PunchLine.RADIUS, 55, 0, 0));
        assertFalse(PunchLine.near(0, 0, 0, 20, 0, 0, PunchLine.LENGTH, PunchLine.RADIUS, -6, 0, 0));
        // no direction: nothing to test against
        assertFalse(PunchLine.near(0, 0, 0, 0, 0, 0, PunchLine.LENGTH, PunchLine.RADIUS, 0, 0, 0));
    }
}
