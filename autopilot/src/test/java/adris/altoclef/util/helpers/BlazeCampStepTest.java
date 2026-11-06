package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BlazeCampStepTest {
    @Test
    public void stepsWhileThereIsACellAndBudget() {
        assertTrue(BlazeFightRules.wantsCampStep(true, false, 0));
        assertTrue(BlazeFightRules.wantsCampStep(true, false, BlazeFightRules.CAMP_STEP_TICKS - 1));
    }

    @Test
    public void staysPutWhenThereOrOutOfBudgetOrNoCell() {
        assertFalse(BlazeFightRules.wantsCampStep(true, true, 0));
        assertFalse(BlazeFightRules.wantsCampStep(true, false, BlazeFightRules.CAMP_STEP_TICKS));
        assertFalse(BlazeFightRules.wantsCampStep(false, false, 0));
    }
}
