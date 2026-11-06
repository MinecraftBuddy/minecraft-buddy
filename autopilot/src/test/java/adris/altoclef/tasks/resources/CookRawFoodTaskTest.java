package adris.altoclef.tasks.resources;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.container.AsyncSmelting;
import org.junit.Test;

// the cook task's smelt is "finished" two ways, and only one of them is a load
public class CookRawFoodTaskTest {
    @Test
    public void aSmeltThatLoadedAndLeftIsAHandoffAndTheBagMeetingItsTargetIsNot() {
        AsyncSmelting.Handoff loaded = () -> true;
        AsyncSmelting.Handoff notYet = () -> false;
        assertTrue(CookRawFoodTask.isHandoff(loaded));
        // 23:19:39: three cooked beef collected from the smoker met "have three cooked beef" with the raw beef still in the bag
        assertFalse(CookRawFoodTask.isHandoff(notYet));
        assertFalse(CookRawFoodTask.isHandoff(new Object()));
        assertFalse(CookRawFoodTask.isHandoff(null));
    }

    @Test
    public void onlyAPileAlreadyInTheSmokerKeepsItsSmeltAcrossAFight() {
        // the whole pile is in the slot: a fresh look reads an empty bag, says done and leaves it there
        assertTrue(CookRawFoodTask.keepSmelt(true, false, 0));
        // never got to the screen, or some still in the bag: the target gets rebuilt off what is left after any bites
        assertFalse(CookRawFoodTask.keepSmelt(false, false, 0));
        assertFalse(CookRawFoodTask.keepSmelt(true, false, 4));
        // loaded and left: the job has it, the overworld's hand off stays as it was
        assertFalse(CookRawFoodTask.keepSmelt(true, true, 0));
    }
}
