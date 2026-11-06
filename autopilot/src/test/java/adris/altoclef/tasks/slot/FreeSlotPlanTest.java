package adris.altoclef.tasks.slot;

import static adris.altoclef.tasks.slot.FreeSlotPlan.Action.CLICK_GARBAGE;
import static adris.altoclef.tasks.slot.FreeSlotPlan.Action.PUT_BACK;
import static adris.altoclef.tasks.slot.FreeSlotPlan.Action.STUCK;
import static adris.altoclef.tasks.slot.FreeSlotPlan.Action.THROW_CURSOR;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FreeSlotPlanTest {

    @Test
    public void emptyCursorPicksUpGarbage() {
        assertEquals(CLICK_GARBAGE, FreeSlotPlan.plan(true, false, true, false));
    }

    @Test
    public void emptyCursorWithNothingToThrowIsStuck() {
        assertEquals(STUCK, FreeSlotPlan.plan(true, false, false, false));
        assertEquals(STUCK, FreeSlotPlan.plan(true, false, false, true));
    }

    @Test
    public void throwawayInHandIsThrown() {
        assertEquals(THROW_CURSOR, FreeSlotPlan.plan(false, true, true, true));
        assertEquals(THROW_CURSOR, FreeSlotPlan.plan(false, true, false, false));
    }

    @Test
    public void ironInHandIsNeverThrown() {
        // the old task clicked outside the window for anything held
        assertEquals(CLICK_GARBAGE, FreeSlotPlan.plan(false, false, true, false));
        assertEquals(PUT_BACK, FreeSlotPlan.plan(false, false, false, true));
        assertEquals(STUCK, FreeSlotPlan.plan(false, false, false, false));
    }

    @Test
    public void swappingWithGarbageBeatsPuttingBackInTheChest() {
        // putting it back just gets looted again next tick
        assertEquals(CLICK_GARBAGE, FreeSlotPlan.plan(false, false, true, true));
    }

    @Test
    public void foodWeCannotSpareOnTheCursorSwapsWithGarbageInsteadOfBeingThrown() {
        // canDropStack says no to food under the floor, so the cursor is not throwable and the cobble in the bag is the way out
        assertEquals(CLICK_GARBAGE, FreeSlotPlan.plan(false, false, true, false));
        assertEquals(CLICK_GARBAGE, FreeSlotPlan.plan(false, false, true, true));
    }

    @Test
    public void foodWeCannotSpareWithNoGarbageGoesBackInTheChestNotOnTheFloor() {
        assertEquals(PUT_BACK, FreeSlotPlan.plan(false, false, false, true));
    }
}
