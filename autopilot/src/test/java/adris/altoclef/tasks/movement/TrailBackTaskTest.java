package adris.altoclef.tasks.movement;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// (pickless_way_out, food_climb_trail) when the walk back up the way down is over: done or stuck, or past its cap
public class TrailBackTaskTest {
    @Test
    public void theWalkEndsDoneOrPastItsCap() {
        assertFalse(TrailBackTask.over(false, 1000, 1000 + TrailBackTask.CAP));
        assertTrue(TrailBackTask.over(false, 1000, 1000 + TrailBackTask.CAP + 1));
        assertTrue(TrailBackTask.over(true, 1000, 1001));
        // a clock gone back (another world) ends it too
        assertTrue(TrailBackTask.over(false, 1000, 999));
    }
}
