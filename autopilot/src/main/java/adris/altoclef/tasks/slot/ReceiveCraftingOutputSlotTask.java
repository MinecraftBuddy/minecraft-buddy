package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.CraftMath;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

public class ReceiveCraftingOutputSlotTask extends Task implements ITaskUsesCraftingGrid {

    private final int _toTake;
    private final Slot _slot;

    public ReceiveCraftingOutputSlotTask(Slot slot, int toTake) {
        _slot = slot;
        _toTake = toTake;
    }

    public ReceiveCraftingOutputSlotTask(Slot slot, boolean all) {
        this(slot, all ? Integer.MAX_VALUE : 1);
    }

    // How many multiples of the current crafting recipe can we craft?
    private static int getCraftMultipleCount(AltoClef mod) {
        int minNonZero = Integer.MAX_VALUE;
        boolean found = false;
        for (Slot check : (StorageHelper.isBigCraftingOpen() ? CraftingTableSlot.INPUT_SLOTS : PlayerSlot.CRAFT_INPUT_SLOTS)) {
            ItemStack stack = StorageHelper.getItemStackInSlot(check);
            if (!stack.isEmpty()) {
                minNonZero = Math.min(stack.getCount(), minNonZero);
                found = true;
            }
        }
        if (!found)
            return 0;
        return minNonZero;
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    // how many more of this stack the main inventory and hotbar can swallow. armor and the offhand don't count, a
    // quick move out of the output never goes there. this is what a quick move actually needs: it crafts the whole
    // multiple in one go, and the old "can ONE stack partially fit somewhere" check said yes with a single free
    // slot of room left, then vanilla ran out of space halfway and left ingredients stranded in the grid
    private static int inventoryRoomFor(AltoClef mod, ItemStack stack) {
        int max = stack.getMaxStackSize();
        int room = 0;
        for (ItemStack inSlot : mod.getPlayer().getInventory().getNonEquipmentItems()) {
            if (inSlot.isEmpty()) {
                room += max;
            } else if (ItemStack.isSameItemSameComponents(inSlot, stack)) {
                room += Math.max(0, max - inSlot.getCount());
            }
        }
        return room;
    }

    // the cursor and the output can share a stack, as long as they are the same thing and it fits. ItemHelper's
    // canStackTogether says no to a stack that lands exactly on the max (strict <), which broke the cursor for nothing
    private static boolean cursorTakesOutput(ItemStack cursor, ItemStack output) {
        if (cursor.isEmpty() || output.isEmpty()) return true;
        return ItemStack.isSameItemSameComponents(cursor, output) && cursor.getCount() + output.getCount() <= cursor.getMaxStackSize();
    }

    // a click on the output takes a server round trip to show up. until it does, the output slot still holds the old
    // result, and the parents (CraftGenericManuallyTask and CraftInInventoryTask both hand us a fresh task for it) happily click it
    // again. a second quick move on a refilled grid crafts MORE than we asked for and eats ingredients other things
    // were saving. so after a click that went out we sit still until the output or the cursor changes, or this many seconds pass.
    // static because it is a different instance of us doing the second click
    private static final TimerGame CLICK_GUARD = new TimerGame(0.4);
    private static boolean _guardArmed = false;
    private static int _guardContainer = -1;
    private static ItemStack _guardOutput = ItemStack.EMPTY;
    private static ItemStack _guardCursor = ItemStack.EMPTY;

    private static boolean clickStillInFlight(AltoClef mod, ItemStack output, ItemStack cursor) {
        if (!_guardArmed) return false;
        if (CLICK_GUARD.elapsed() || _guardContainer != mod.getPlayer().containerMenu.containerId
                || !ItemStack.matches(_guardOutput, output) || !ItemStack.matches(_guardCursor, cursor)) {
            _guardArmed = false;
            return false;
        }
        return true;
    }

    // only arms when the slot handler says the click really went out, a click eaten by its cooldown is retried next tick
    private void click(AltoClef mod, ItemStack output, ItemStack cursor, ContainerInput type) {
        if (mod.getSlotHandler().clickSlot(_slot, 0, type)) {
            _guardArmed = true;
            _guardContainer = mod.getPlayer().containerMenu.containerId;
            _guardOutput = output.copy();
            _guardCursor = cursor.copy();
            CLICK_GUARD.reset();
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        ItemStack inOutput = StorageHelper.getItemStackInSlot(_slot);
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursorTakesOutput(cursor, inOutput)) {
            return new EnsureFreeCursorSlotTask();
        }
        if (inOutput.isEmpty()) {
            // nothing to take (or the stale one just got taken), clicking air does nothing but spam the server
            setDebugState("Waiting for the output");
            return null;
        }
        if (clickStillInFlight(mod, inOutput, cursor)) {
            setDebugState("Waiting for the click to land");
            return null;
        }
        int multiples = getCraftMultipleCount(mod);
        int craftCount = inOutput.getCount() * multiples;
        int weWantToAddToInventory = _toTake - mod.getItemStorage().getItemCountInventoryOnly(inOutput.getItem());
        boolean takeAll = CraftMath.quickMoveCraftsNoExtra(weWantToAddToInventory, craftCount, multiples);
        // no room for the whole multiple means the quick move would stop halfway, so go through the cursor instead
        if (takeAll && inventoryRoomFor(mod, inOutput) >= craftCount) {
            setDebugState("Quick moving output");
            click(mod, inOutput, cursor, ContainerInput.QUICK_MOVE);
            return null;
        }
        // picking it up into a bag with no room parks the result on the cursor (the iron pickaxe, say), and the cursor task then has
        // to find it a home with nothing free. so make the room first, while the good stuff is still in the output. a THROW on the
        // garbage slot drops it in one click without touching the cursor: going through the cursor (EnsureFreeInventorySlotTask)
        // leaves the garbage up there for a tick, and the craft task above us puts it straight back in the slot it just left.
        // empty cursor only (a THROW with something held does nothing, and a same kind stack just grows), and only with garbage.
        // some room is not enough room: 3 free spots on a partial stack take 3 of the 4 sticks and the 4th sits on the cursor.
        // one throw frees a whole slot and an output is never more than a stack, so this goes at most once per output
        if (cursor.isEmpty() && inventoryRoomFor(mod, inOutput) < inOutput.getCount()) {
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            if (garbage.isPresent()) {
                setDebugState("Making room for the output");
                mod.getSlotHandler().clickSlot(garbage.get(), 1, ContainerInput.THROW);
                return null;
            }
        }
        setDebugState("Picking up output");
        click(mod, inOutput, cursor, ContainerInput.PICKUP);
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ReceiveCraftingOutputSlotTask task) {
            return task._slot.equals(_slot) && task._toTake == _toTake;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Receiving output";
    }

    @Override
    protected String toHudString() {
        return "Taking the result";
    }

    @Override
    protected boolean isHudPlumbing() {
        return true;
    }
}
