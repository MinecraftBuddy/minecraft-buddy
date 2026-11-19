package adris.altoclef.tasks.resources;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// (food_climb_stuck, look 137) a climb for food that got no higher in three game minutes is given up, and the deep food rule
// waits fifteen game minutes after
public class SurfaceFoodClimbTest {

    @After
    public void tearDown() {
        SurfaceFoodTask.noteGivenUp(Long.MIN_VALUE);
    }

    @Test
    public void aClimbIsStuckOnlyAfterThreeGameMinutesWithNoNewHeight() {
        assertFalse(SurfaceFoodTask.climbStuck(1000, 1000));
        assertFalse(SurfaceFoodTask.climbStuck(1000, 1000 + SurfaceFoodTask.CLIMB_STUCK));
        assertTrue(SurfaceFoodTask.climbStuck(1000, 1001 + SurfaceFoodTask.CLIMB_STUCK));
        assertFalse("a clock that went back", SurfaceFoodTask.climbStuck(1000, 500));
    }

    @Test
    public void theDeepFoodRuleWaitsFifteenGameMinutesAfterAGiveUp() {
        assertFalse("none given up", SurfaceFoodTask.climbGivenUp(5000));
        SurfaceFoodTask.noteGivenUp(10_000);
        assertTrue(SurfaceFoodTask.climbGivenUp(10_000));
        assertTrue(SurfaceFoodTask.climbGivenUp(10_000 + SurfaceFoodTask.GIVEN_UP_HOLD - 1));
        assertFalse(SurfaceFoodTask.climbGivenUp(10_000 + SurfaceFoodTask.GIVEN_UP_HOLD));
        assertFalse("a clock that went back", SurfaceFoodTask.climbGivenUp(9_000));
    }
}
