package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import adris.altoclef.tasks.movement.GetDownColumnTask.Shaft;
import org.junit.Test;

public class GetDownColumnTaskTest {
    // the shelter shaft must stay the same task from tick to tick or it restarts the path every tick
    @Test
    public void sameColumnAndWanderIsTheSameTask() {
        assertEquals(new Shaft(1, 2, 60, false), new Shaft(1, 2, 60, false));
    }

    @Test
    public void wanderOrColumnDifferenceIsAnotherTask() {
        Shaft base = new Shaft(1, 2, 60, false);
        assertNotEquals(base, new Shaft(1, 2, 60, true));
        assertNotEquals(base, new Shaft(1, 3, 60, false));
        assertNotEquals(base, new Shaft(1, 2, 59, false));
    }
}
