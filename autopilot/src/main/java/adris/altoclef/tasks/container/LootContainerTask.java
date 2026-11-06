package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerType;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;


public class LootContainerTask extends Task {
    public final BlockPos chest;
    public final List<Item> targets = new ArrayList<>();
    private final Predicate<ItemStack> _check;
    private boolean _weDoneHere = false;
    private final EmptyWatch _emptyWatch = new EmptyWatch();
    private final Map<String, Integer> _taken = new TreeMap<>();

    public LootContainerTask(BlockPos chestPos, List<Item> items) {
        chest = chestPos;
        targets.addAll(items);
        _check = x -> true;
    }

    public LootContainerTask(BlockPos chestPos, List<Item> items, Predicate<ItemStack> pred) {
        chest = chestPos;
        targets.addAll(items);
        _check = pred;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        for (Item item : targets) {
            if (!mod.getBehaviour().isProtected(item)) {
                mod.getBehaviour().addProtectedItems(item);
            }
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!ContainerType.screenHandlerMatches(ContainerType.CHEST)) {
            // a closed screen (mob defense does that) means the next one starts unsynced again
            _emptyWatch.reset();
            setDebugState("Interact with container");
            return new InteractWithBlockTask(chest);
        }
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursor.isEmpty()) {
            Optional<Slot> toFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
            if (toFit.isPresent()) {
                setDebugState("Putting cursor in inventory");
                mod.getSlotHandler().clickSlot(toFit.get(), 0, ContainerInput.PICKUP);
                return null;
            } else {
                setDebugState("Ensuring space");
                return new EnsureFreeInventorySlotTask();
            }
        }
        Optional<Slot> optimal = getAMatchingSlot(mod);
        // empty on the first ticks after a reopen is just the contents packet not being here yet. wait it out
        if (_emptyWatch.tick(menuHasContainerSlots(), optimal.isPresent())) {
            _weDoneHere = true;
            return null;
        }
        if (optimal.isEmpty()) {
            setDebugState("Waiting for the container to sync");
            return null;
        }
        ItemStack stack = StorageHelper.getItemStackInSlot(optimal.get());
        setDebugState("Looting items: " + targets);
        _taken.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), stack.getCount(), Integer::sum);
        mod.getSlotHandler().clickSlot(optimal.get(), 0, ContainerInput.PICKUP);
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task task) {
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            moveTo.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ContainerInput.PICKUP));
            if (ItemHelper.canDropStack(mod, cursorStack)) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            garbage.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ContainerInput.PICKUP));
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
        } else {
            StorageHelper.closeScreen();
        }
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof LootContainerTask && targets == ((LootContainerTask) other).targets;
    }

    private Optional<Slot> getAMatchingSlot(AltoClef mod) {
        for (Item item : targets) {
            List<Slot> slots = mod.getItemStorage().getSlotsWithItemContainer(item);
            if (!slots.isEmpty()) for (Slot slot : slots) {
                ItemStack stack = StorageHelper.getItemStackInSlot(slot);
                if (_check.test(stack) && hasRoomFor(mod, stack)) return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    // the loot is protected, so a full bag with no garbage of its own has nothing to throw and the stack would go back in
    // the chest just to be looted again. same fit question as the cursor branch, or the two disagree and it loops.
    // chest junk doesn't count, the free slot task would dump it on the floor one stack at a time for nothing
    private static boolean hasRoomFor(AltoClef mod, ItemStack stack) {
        return mod.getItemStorage().getSlotThatCanFitInPlayerInventory(stack, false).isPresent()
                || StorageHelper.getGarbageSlot(mod).filter(Slot::isSlotInPlayerInventory).isPresent();
    }

    // a chest menu with real chest slots on top of the 36 player ones, ie. the contents have somewhere to land
    private static boolean menuHasContainerSlots() {
        if (!ContainerType.screenHandlerMatches(ContainerType.CHEST) || Minecraft.getInstance().player == null) {
            return false;
        }
        AbstractContainerMenu menu = Minecraft.getInstance().player.containerMenu;
        return menu != null && menu.slots.size() > 36;
    }

    // only the settled path finishes us. "a container screen is open and nothing matches" used to count, which is
    // also true for the first ticks of every reopen, and that marked a full chest as done
    @Override
    public boolean isFinished(AltoClef mod) {
        return _weDoneHere;
    }

    // item name to count that we clicked out, for the log
    public Map<String, Integer> taken() {
        return _taken;
    }

    @Override
    protected String toHudString() {
        return "Looting a chest";
    }

    @Override
    protected String toDebugString() {
        return "Looting a container";
    }
}
