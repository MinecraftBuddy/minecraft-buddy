package adris.altoclef.tasks.movement;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HealUpTaskTest {
    @Test
    public void unsafeSpotWithACellSteps() {
        assertTrue(HealUpTask.wantsStep(true, false, 0));
        assertTrue(HealUpTask.wantsStep(true, false, 99));
    }

    @Test
    public void safeSpotOrNoCellStands() {
        assertFalse(HealUpTask.wantsStep(false, false, 0));
    }

    @Test
    public void arrivedOrBudgetSpentStands() {
        assertFalse(HealUpTask.wantsStep(true, true, 10));
        assertFalse(HealUpTask.wantsStep(true, false, 100));
    }
}
