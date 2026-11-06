package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

public class EnsureFreePlayerCraftingGridTask extends Task {

    // pure so it can be tested without a game. the cursor part is for the pickup that empties the grid: the
    // item is in your hand now, the grid is "clean", and if we let go here it goes right back in. ping pong
    public static boolean shouldClear(boolean gridInUse, boolean ensureActive, boolean gridHasItems, boolean cursorHasItems) {
        if (gridInUse && !ensureActive) {
            return false;
        }
        return gridHasItems || (ensureActive && cursorHasItems);
    }
    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected Task onTick(AltoClef mod) {
        setDebugState("Clearing the 2x2 crafting grid");
        for (Slot slot : PlayerSlot.CRAFT_INPUT_SLOTS) {
            ItemStack items = StorageHelper.getItemStackInSlot(slot);
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
            if (!cursor.isEmpty()) {
                return new EnsureFreeCursorSlotTask();
            }
            if (!items.isEmpty()) {
                mod.getSlotHandler().clickSlot(slot, 0, ContainerInput.PICKUP);
                return null;
            }
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EnsureFreePlayerCraftingGridTask;
    }

    @Override
    protected String toDebugString() {
        return "Breaking the crafting grid";
    }

    @Override
    protected String toHudString() {
        return "Clearing the crafting grid";
    }

    @Override
    protected boolean isHudPlumbing() {
        return true;
    }
}
