package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerType;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.tasks.speedrun.gamer.end.EndGear;
import adris.altoclef.util.helpers.DropRoom;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import java.util.Optional;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class EnsureFreeCursorSlotTask extends Task {

    // clicks in a row that went out and left the cursor exactly as it was. the client predicts every click, so a real one shows
    // up on the very next tick. this many and the click is a no-op that would go on for ever (the craft result slot was one)
    private static final int NO_OP_CLICKS = 10;

    private ItemStack _clickedWith = ItemStack.EMPTY;
    private boolean _clicked;
    private int _noOps;
    // the last resort swapped what we keep into the bag, so what rides the cursor now is the stack we picked to lose
    private boolean _throwNext;

    @Override
    protected void onStart(AltoClef mod) {
        // YEET
        _clickedWith = ItemStack.EMPTY;
        _clicked = false;
        _noOps = 0;
        _throwNext = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (cursor.isEmpty()) {
            setDebugState("Cursor is empty");
            _throwNext = false;
            return null;
        }
        if (_clicked) {
            _noOps = ItemStack.matches(_clickedWith, cursor) ? _noOps + 1 : 0;
            _clicked = false;
        }

        Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
        if (moveTo.isPresent() && _noOps < NO_OP_CLICKS) {
            setDebugState("Moving cursor stack back");
            click(mod, moveTo.get(), cursor);
            return null;
        }
        if (_throwNext && !AltoSettings.isImportant(cursor.getItem())) {
            setDebugState("Throwing " + cursor.getCount() + " " + cursor.getItem() + " to keep what we held");
            throwCursor(mod, cursor);
            _throwNext = false;
            return null;
        }
        _throwNext = false;

        // same plan EnsureFreeInventorySlotTask goes by, with an empty cursor already handled above
        boolean storageOpen = ContainerType.screenHandlerMatches(ContainerType.CHEST) || ContainerType.screenHandlerMatches(ContainerType.SHULKER);
        Optional<Slot> containerSlot = storageOpen ? mod.getItemStorage().getSlotThatCanFitInOpenContainer(cursor, false) : Optional.empty();
        Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
        boolean throwable = ItemHelper.canDropStack(mod, cursor) && !StorageHelper.wouldWear(mod, cursor);
        FreeSlotPlan.Action action = _noOps >= NO_OP_CLICKS ? FreeSlotPlan.Action.STUCK
                : FreeSlotPlan.plan(false, throwable, garbage.isPresent(), containerSlot.isPresent());
        switch (action) {
            case CLICK_GARBAGE -> {
                // the held stack goes where the garbage was, the garbage comes up and is thrown next tick
                setDebugState("Swapping with garbage");
                click(mod, garbage.get(), cursor);
            }
            case THROW_CURSOR -> {
                // name it: when a throw does not take, the log should at least say what is stuck on the cursor
                setDebugState("Incompatible cursor stack, throwing " + cursor.getCount() + " " + cursor.getItem());
                throwCursor(mod, cursor);
            }
            case PUT_BACK -> {
                setDebugState("Putting the held stack back");
                click(mod, containerSlot.get(), cursor);
            }
            default -> lastResort(mod, cursor);
        }
        return null;
    }

    // nowhere to put it and nothing is garbage. the old way threw the cursor whatever it was (diamonds too), the other way
    // round is a cursor stuck for ever. a stack we do not keep still just goes, one we keep trades places with the least
    // valuable bag stack and that one goes instead (next tick, see _throwNext)
    private void lastResort(AltoClef mod, ItemStack cursor) {
        _noOps = 0;
        if (!keeps(cursor)) {
            setDebugState("Nothing to swap, throwing " + cursor.getCount() + " " + cursor.getItem());
            throwCursor(mod, cursor);
            return;
        }
        Optional<Slot> swap = StorageHelper.getLastResortSlot(mod);
        if (swap.isEmpty()) {
            // the whole bag is important. one of them has to give, and it is the one in hand
            setDebugState("Bag is all keepers, throwing " + cursor.getCount() + " " + cursor.getItem());
            throwCursor(mod, cursor);
            return;
        }
        setDebugState("Swapping " + cursor.getItem() + " into the bag");
        if (click(mod, swap.get(), cursor)) {
            _throwNext = true;
        }
    }

    // important list, or gear: the things a full bag must never cost us
    private static boolean keeps(ItemStack stack) {
        return AltoSettings.isImportant(stack.getItem()) || isGear(stack.getItem())
                // a bed on the cursor during a run: the End counts it, the throw-what-we-don't-keep branch would not
                || DropRoom.isBed(stack.getItem()) && EndGear.reserveBeds() > 0;
    }

    static boolean isGear(Item item) {
        return ItemHelper.isTool(item) || ItemHelper.isArmor(item)
                || item == Items.SHIELD || item == Items.BOW || item == Items.CROSSBOW || item == Items.ELYTRA;
    }

    private boolean click(AltoClef mod, Slot slot, ItemStack cursor) {
        if (mod.getSlotHandler().clickSlot(slot, 0, ContainerInput.PICKUP)) {
            _clicked = true;
            _clickedWith = cursor.copy();
            return true;
        }
        return false;
    }

    private void throwCursor(AltoClef mod, ItemStack cursor) {
        click(mod, Slot.UNDEFINED, cursor);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return StorageHelper.getItemStackInCursorSlot().isEmpty();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EnsureFreeCursorSlotTask;
    }


    // And filling this in will make it look ok in the task tree
    @Override
    protected String toDebugString() {
        return "Breaking the cursor slot";
    }

    @Override
    protected String toHudString() {
        return "Putting down the held item";
    }

    @Override
    protected boolean isHudPlumbing() {
        return true;
    }
}
