package baritone.behavior;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// (station_no_step_ban) a search that got not one step nearer counts for this goal, from these feet, for a few seconds
public class NoStepHereTest {
    @Test
    public void onlyThisGoalFromTheseFeetLately() {
        long at = 1000;
        assertTrue(PathingBehavior.noStepFresh("a", "goal", at, "a", "goal", at));
        assertTrue(PathingBehavior.noStepFresh("a", "goal", at, "a", "goal", at + PathingBehavior.NO_STEP_TICKS));
        assertFalse(PathingBehavior.noStepFresh("a", "goal", at, "a", "goal", at + PathingBehavior.NO_STEP_TICKS + 1));
        assertFalse(PathingBehavior.noStepFresh("a", "goal", at, "b", "goal", at));
        assertFalse(PathingBehavior.noStepFresh("a", "goal", at, "a", "other", at));
        assertFalse(PathingBehavior.noStepFresh(null, "goal", at, "a", "goal", at));
        // a clock gone back (another world)
        assertFalse(PathingBehavior.noStepFresh("a", "goal", at, "a", "goal", at - 1));
    }
}
