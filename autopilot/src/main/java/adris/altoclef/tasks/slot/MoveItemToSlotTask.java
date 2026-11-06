package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.EmptyWatch;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StlHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.ui.HudText;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class MoveItemToSlotTask extends Task {

    // one packet per item, see the burst below
    private static final int MAX_PLACE_BURST = 16;

    private final ItemTarget _toMove;
    private final Slot _destination;
    private final Function<AltoClef, List<Slot>> _getMovableSlots;
    private final EmptyWatch _nothingToMove = new EmptyWatch();
    private boolean _gaveUp = false;

    public MoveItemToSlotTask(ItemTarget toMove, Slot destination, Function<AltoClef, List<Slot>> getMovableSlots) {
        _toMove = toMove;
        _destination = destination;
        _getMovableSlots = getMovableSlots;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _nothingToMove.reset();
        _gaveUp = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getSlotHandler().canDoSlotAction()) {
            // Rough plan
            // - If empty slot or wrong item
            //      Find best matching item (smallest count over target, or largest count if none over)
            //      Click on it (one turn)
            // - If held slot has < items than target count
            //      Left click on destination slot (one turn)
            // - If held slot has > items than target count
            //      Right click on destination slot (one turn)
            ItemStack currentHeld = StorageHelper.getItemStackInCursorSlot();
            ItemStack atTarget = StorageHelper.getItemStackInSlot(_destination);

            // Items that CAN be moved to that slot.
            Item[] validItems = _toMove.getMatches();//Arrays.stream(_toMove.getMatches()).filter(item -> mod.getItemStorage().getItemCount(item) >= _toMove.getTargetCount()).toArray(Item[]::new);

            // We need to deal with our cursor stack OR put an item there (to move).
            boolean wrongItemHeld = !Arrays.asList(validItems).contains(currentHeld.getItem());
            if (currentHeld.isEmpty() || wrongItemHeld) {
                Optional<Slot> toPlace;
                if (currentHeld.isEmpty()) {
                    // Just pick up
                    toPlace = getBestSlotToPickUp(mod, validItems);
                } else {
                    // Try to fit the currently held item first.
                    toPlace = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(currentHeld, true);
                    if (toPlace.isEmpty()) {
                        // If all else fails, just swap it.
                        toPlace = getBestSlotToPickUp(mod, validItems);
                    }
                }
                if (toPlace.isEmpty()) {
                    // the stack list can lag a tick behind a click, so one miss is not the end. a few in a row is, and
                    // then we say so ONCE (this used to be a chat line every tick) and call ourselves finished
                    if (_nothingToMove.tick(true, false) && !_gaveUp) {
                        _gaveUp = true;
                        Debug.logError("Called MoveItemToSlotTask when item/not enough item is available! valid items: " + StlHelper.toString(validItems, Item::getDescriptionId));
                    }
                    return null;
                }
                _nothingToMove.reset();
                _gaveUp = false;
                mod.getSlotHandler().clickSlot(toPlace.get(), 0, ContainerInput.PICKUP);
                return null;
            }

            // holding the right stack means there is something to move after all
            _nothingToMove.reset();
            _gaveUp = false;
            int currentlyPlaced =Arrays.asList(validItems).contains(atTarget.getItem()) ? atTarget.getCount() : 0;
            if (currentHeld.getCount() + currentlyPlaced <= _toMove.getTargetCount()) {
                // Just place all of 'em
                mod.getSlotHandler().clickSlot(_destination, 0, ContainerInput.PICKUP);
            } else {
                // Place one at a time.
                int needed = _toMove.getTargetCount() - currentlyPlaced;
                boolean destTakesOurItem = atTarget.isEmpty() || atTarget.getItem() == currentHeld.getItem();
                // one right click is one item and one 0.2s cooldown, 20 of them was long enough for the craft
                // watchdog to call it stuck. they all go out in one slot action now. only onto an empty or same
                // item slot though, a right click onto a different stack swaps and the next one would swap back
                if (needed > 2 && destTakesOurItem) {
                    mod.getSlotHandler().clickSlotBurst(_destination, 1, ContainerInput.PICKUP, Math.min(needed, MAX_PLACE_BURST));
                } else {
                    mod.getSlotHandler().clickSlot(_destination, 1, ContainerInput.PICKUP);
                }
            }
            return null;
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (_gaveUp) return true;
        ItemStack atDestination = StorageHelper.getItemStackInSlot(_destination);
        return (_toMove.matches(atDestination.getItem()) && atDestination.getCount() >= _toMove.getTargetCount());
    }

    @Override
    protected boolean isEqual(Task obj) {
        if (obj instanceof MoveItemToSlotTask task) {
            return task._toMove.equals(_toMove) && task._destination.equals(_destination);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Moving " + _toMove + " to " + _destination;
    }

    @Override
    protected String toHudString() {
        return "Moving " + HudText.items(_toMove);
    }

    @Override
    protected boolean isHudPlumbing() {
        return true;
    }

    private Optional<Slot> getBestSlotToPickUp(AltoClef mod, Item[] validItems) {
        Slot bestMatch = null;
        if (!_getMovableSlots.apply(mod).isEmpty()) {
            for (Slot slot : _getMovableSlots.apply(mod)) {
                if (Slot.isCursor(slot))
                    continue;
                if (!_toMove.matches(StorageHelper.getItemStackInSlot(slot).getItem()))
                    continue;
                if (bestMatch == null) {
                    bestMatch = slot;
                    continue;
                }
                int countBest = StorageHelper.getItemStackInSlot(bestMatch).getCount();
                int countCheck = StorageHelper.getItemStackInSlot(slot).getCount();
                if ((countBest < _toMove.getTargetCount() && countCheck > countBest)
                        || (countBest >= _toMove.getTargetCount() && countCheck >= _toMove.getTargetCount() && countCheck > countBest)) {
                    // If we don't have enough, go for largest
                    // If we have too much, go for smallest over the limit.
                    bestMatch = slot;
                }
            }
        }
        return Optional.ofNullable(bestMatch);
    }
}
