package adris.altoclef.tasks.slot;

// what EnsureFreeInventorySlotTask does this tick, as plain booleans so it can be tested without a game
public final class FreeSlotPlan {

    public enum Action {
        // click the garbage stack: empty cursor picks it up (next tick throws it), held stack swaps with it
        CLICK_GARBAGE,
        // the held stack is throwaway, yeet it. food we cannot spare is not throwaway (ItemHelper.canDropStack), so it takes the
        // CLICK_GARBAGE road below: the click swaps it into the garbage's slot and the garbage is what gets thrown next tick
        THROW_CURSOR,
        // the held stack is worth keeping and nothing is garbage, so it goes back in the open container
        PUT_BACK,
        // nothing here can be thrown or moved, the task calls itself finished instead of clicking at nothing
        STUCK
    }

    private FreeSlotPlan() {
    }

    // the old version clicked outside the window for ANY held stack, so iron and cooked food got thrown to free a slot
    // that throwing them never frees. only throwaway stuff leaves now, anything else swaps with garbage or goes back
    public static Action plan(boolean cursorEmpty, boolean cursorThrowable, boolean garbageAvailable, boolean containerSlotFitsCursor) {
        if (cursorEmpty) {
            return garbageAvailable ? Action.CLICK_GARBAGE : Action.STUCK;
        }
        if (cursorThrowable) {
            return Action.THROW_CURSOR;
        }
        if (garbageAvailable) {
            return Action.CLICK_GARBAGE;
        }
        return containerSlotFitsCursor ? Action.PUT_BACK : Action.STUCK;
    }
}
