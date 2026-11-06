package adris.altoclef.tasks.resources;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// wood in the bag is wood we do not have to go outside for
public class HeldWoodTest {

    @Test
    public void heldLogsAreCraftedBeforeAnyTreeEvenWhenTheyFallShort() {
        // 3 logs for 16 planks: 12 of them are right here
        assertTrue(CollectPlanksTask.craftHeldLogsNow(3, 12, 16, false));
        // and when they do cover it there is no difference
        assertTrue(CollectPlanksTask.craftHeldLogsNow(4, 16, 16, false));
    }

    @Test
    public void noLogsMeansNothingToCraft() {
        assertFalse(CollectPlanksTask.craftHeldLogsNow(0, 8, 16, false));
        assertFalse(CollectPlanksTask.craftHeldLogsNow(0, 16, 16, true));
    }

    @Test
    public void onceChoppingEachNewLogWaitsForTheWholeBatch() {
        assertFalse(CollectPlanksTask.craftHeldLogsNow(1, 4, 16, true));
        assertTrue(CollectPlanksTask.craftHeldLogsNow(4, 16, 16, true));
    }

    @Test
    public void twoPlanksOrOneLogMakeSticks() {
        assertFalse(CollectSticksTask.woodMakesSticks(1, 0));
        assertTrue(CollectSticksTask.woodMakesSticks(2, 0));
        assertTrue(CollectSticksTask.woodMakesSticks(0, 1));
        assertFalse(CollectSticksTask.woodMakesSticks(0, 0));
    }
}
