package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the step out of a cell is for a shape that holds the body (a fence, a wall), not for one we stand on top of
public class FenceCellStepTest {

    @Test
    public void fenceBesideTheFeetHoldsTheBody() {
        // feet on the floor at y 64 in a fence's cell, the fence's collision reaches 1.5 up
        assertTrue(CustomBaritoneGoalTask.reachesAboveFeet(1.5, 64, 64.0));
    }

    @Test
    public void standingOnASlabOrSoulSandIsNot() {
        assertFalse(CustomBaritoneGoalTask.reachesAboveFeet(0.5, 64, 64.5));
        assertFalse(CustomBaritoneGoalTask.reachesAboveFeet(0.875, 64, 64.875));
        // a carpet, a hair of float error above its top
        assertFalse(CustomBaritoneGoalTask.reachesAboveFeet(0.0625, 64, 64.0625 - 1e-9));
    }
}
