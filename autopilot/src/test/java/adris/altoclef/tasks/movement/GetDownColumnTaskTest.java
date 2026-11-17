package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

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

    // (dig_lava_check) the block under the feet is the next one the dig breaks only in the column and above its floor
    @Test
    public void theNextDigIsUnderTheFeetInTheColumnAboveTheFloor() {
        assertTrue(GetDownColumnTask.nextDigInColumn(30005, -40, 8135, 30005, 8135, -41));
        // at the floor: done, nothing more to break
        assertFalse(GetDownColumnTask.nextDigInColumn(30005, -41, 8135, 30005, 8135, -41));
        // a cell beside the column (knocked off it): baritone's way back, not the dig's block
        assertFalse(GetDownColumnTask.nextDigInColumn(30005, -40, 8134, 30005, 8135, -41));
        assertFalse(GetDownColumnTask.nextDigInColumn(30006, -40, 8135, 30005, 8135, -41));
    }
}
