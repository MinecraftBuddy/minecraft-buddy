package adris.altoclef;

import baritone.pathing.movement.Movement;
import baritone.pathing.path.PathExecutor;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

// the stand line reads a few private fields by name. renaming one would turn its answer into "?" and nobody would see it
// until the next freeze, so this is the one that fails instead
public class StandReportTest {

    @Test
    public void theFieldsTheStandLineReadsStillExist() throws ReflectiveOperationException {
        for (String name : new String[]{"ticksOnCurrent", "currentMovementOriginalCostEstimate", "boat", "groundShortcut", "flight"}) {
            assertNotNull(name, PathExecutor.class.getDeclaredField(name));
        }
        assertNotNull(Movement.class.getDeclaredField("currentState"));
    }
}
