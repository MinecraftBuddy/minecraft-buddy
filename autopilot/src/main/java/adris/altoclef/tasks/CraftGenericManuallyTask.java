package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.trackers.storage.ItemStorageTracker;
import adris.altoclef.tasks.slot.DragSplitToSlotsTask;
import adris.altoclef.tasks.slot.EnsureFreeCursorSlotTask;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasks.slot.ReceiveCraftingOutputSlotTask;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.CraftMath;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.ui.HudText;
import net.minecraft.world.item.Item;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Assuming a crafting screen is open, crafts a recipe.
 * <p>
 * Not useful for custom tasks.
 */
// the grid is full of our stuff for as long as this runs, so ResourceTask must not sweep it. the recipe book
// version of this used to carry the marker, and then the recipe book left in 1.21.2 and took it with it
public class CraftGenericManuallyTask extends Task implements ITaskUsesCraftingGrid {

    private final RecipeTarget _target;
    // one probe line per stuck stretch, not one per tick: said once, quiet until that target can craft again. static
    // because the parent may hand us a fresh task every tick
    private static String _probeTarget;
    private static long _probeAt;
    // same idea for "nothing owed", its own fields so the two lines don't silence each other
    private static String _idleTarget;
    private static long _idleSince;
    private static long _idleLast;
    private static long _idleAt;

    public CraftGenericManuallyTask(RecipeTarget target) {
        _target = target;
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    // when the parent thinks we have the stuff and craftsPossible says no, somebody is counting wrong. this says who:
    // which menu is really open, what sits in its input slots, and the tracker's counts next to the live inventory
    private void logOutOfMaterials(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (player == null) return;
        String key = String.valueOf(_target);
        long now = System.currentTimeMillis();
        // re-armed after two minutes too, so a stop and a fresh run of the same stuck craft still gets a line
        if (key.equals(_probeTarget) && now - _probeAt < 120000) return;
        _probeTarget = key;
        _probeAt = now;
        Screen screen = Minecraft.getInstance().gui.screen();
        StringBuilder line = new StringBuilder("out of materials for ").append(_target)
                .append(": menu ").append(player.containerMenu.getClass().getSimpleName()).append(" id ").append(player.containerMenu.containerId)
                .append(", screen ").append(screen == null ? "none" : screen.getClass().getSimpleName())
                .append(", input slots [");
        for (Slot slot : ItemStorageTracker.getCurrentConversionSlots()) {
            ItemStack stack = StorageHelper.getItemStackInSlot(slot);
            if (!stack.isEmpty()) line.append(' ').append(slot).append('=').append(stack);
        }
        line.append(" ], cursor ").append(StorageHelper.getItemStackInCursorSlot()).append(", cached (cursor in) / live bag:");
        Set<Item> items = new LinkedHashSet<>();
        for (int i = 0; i < _target.getRecipe().getSlotCount(); ++i) {
            ItemTarget slot = _target.getRecipe().getSlot(i);
            if (slot != null && !slot.isEmpty()) items.addAll(Arrays.asList(slot.getMatches()));
        }
        items.add(_target.getOutputItem());
        for (Item item : items) {
            int cached = mod.getItemStorage().getItemCountInventoryOnly(item);
            int live = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); ++i) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.getItem() == item) live += stack.getCount();
            }
            // a recipe that takes any log lists every log in the game, the zeros are noise
            if (cached != 0 || live != 0 || item == _target.getOutputItem()) {
                line.append(' ').append(BuiltInRegistries.ITEM.getKey(item).getPath()).append('=').append(cached).append('/').append(live);
            }
        }
        Debug.logInternal(line.toString());
    }

    // nothing owed is done for us, but if the parent keeps handing us the same target it's waiting on a craft that
    // will never happen. that's how the birch slab freeze hid: a plank step asking for 1 plank with 2 in the bag.
    // log only, the parent is the one counting wrong
    private void logNothingOwed(int held) {
        String key = String.valueOf(_target);
        long now = System.currentTimeMillis();
        // every finished craft passes through here for a tick before the parent notices, only a stretch is worth a line
        if (!key.equals(_idleTarget) || now - _idleLast > 1000) {
            _idleTarget = key;
            _idleSince = now;
            _idleAt = 0;
        }
        _idleLast = now;
        if (now - _idleSince < 2000 || now - _idleAt < 120000) return;
        _idleAt = now;
        Debug.logInternal("asked to craft " + _target + " but already have " + held + " of " + _target.getTargetCount() + ", waiting on the parent");
    }

    @Override
    protected Task onTick(AltoClef mod) {

        boolean bigCrafting = StorageHelper.isBigCraftingOpen();

        if (!bigCrafting && !StorageHelper.isPlayerInventoryOpen()) {
            // Make sure we're not in another screen before we craft,
            // otherwise crafting won't work
            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
            if (!cursorStack.isEmpty()) {
                Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
                if (moveTo.isPresent()) {
                    mod.getSlotHandler().clickSlot(moveTo.get(), 0, ContainerInput.PICKUP);
                    return null;
                }
                if (ItemHelper.canDropStack(mod, cursorStack)) {
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
                    return null;
                }
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                // Try throwing away cursor slot if it's garbage
                if (garbage.isPresent()) {
                    mod.getSlotHandler().clickSlot(garbage.get(), 0, ContainerInput.PICKUP);
                    return null;
                }
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
            } else {
                StorageHelper.closeScreen();
            }
            // Just to be safe
        }

        Slot outputSlot = bigCrafting ? CraftingTableSlot.OUTPUT_SLOT : PlayerSlot.CRAFT_OUTPUT_SLOT;

        // Example:
        // We need 9 sticks
        // plank recipe results in 4 sticks
        // this means 3 planks per slot
        // minus what we already hold, the same count ReceiveCraftingOutputSlotTask goes by when it takes the output
        int heldOutput = mod.getItemStorage().getItemCountInventoryOnly(_target.getOutputItem());
        int wantedCrafts = CraftMath.craftsStillOwed(_target.getTargetCount(), heldOutput, _target.getRecipe().outputCount());
        // "craft as many as possible" is a target of 99999999, so the per slot number has to be clamped to what we
        // really own or the slot is never satisfied and we keep reaching for items that are gone (that was a
        // MoveItemToSlotTask error in chat every single tick). grid contents count so this doesn't shrink as we fill it
        int craftsPossible = StorageHelper.craftsPossible(mod, _target.getRecipe(), wantedCrafts);
        // and a grid slot holds one stack, 100 hay blocks are two rounds
        // and mixed wood is one craft a round, see CraftMath.perSlotToFill
        int requiredPerSlot = StorageHelper.perSlotToFill(mod, _target.getRecipe(), Math.min(craftsPossible, stackLimit(_target)));
        if (craftsPossible <= 0) {
            // nothing left to put in. whatever is still sitting in the output is ours, then we are done
            if (!StorageHelper.getItemStackInSlot(outputSlot).isEmpty()) {
                setDebugState("Out of materials: grabbing from output.");
                return new ReceiveCraftingOutputSlotTask(outputSlot, _target.getTargetCount());
            }
            setDebugState("Out of materials.");
            // nothing owed is not stuck, that's done
            if (wantedCrafts > 0) logOutOfMaterials(mod);
            else if (StorageHelper.getItemStackInCursorSlot().isEmpty()) logNothingOwed(heldOutput);
            return null;
        }
        if (_probeTarget != null && _probeTarget.equals(String.valueOf(_target))) _probeTarget = null;

        // slots that want the same ingredient get filled with one vanilla drag instead of an item at a time
        Task drag = dragFillTask(mod, bigCrafting, requiredPerSlot);
        if (drag != null) {
            setDebugState("Dragging items across the grid...");
            return drag;
        }

        // For each slot in table
        for (int craftSlot = 0; craftSlot < _target.getRecipe().getSlotCount(); ++craftSlot) {
            ItemTarget toFill = _target.getRecipe().getSlot(craftSlot);
            Slot currentCraftSlot;
            if (bigCrafting) {
                // Craft in table
                currentCraftSlot = CraftingTableSlot.getInputSlot(craftSlot, _target.getRecipe().isBig());
            } else {
                // Craft in window
                currentCraftSlot = PlayerSlot.getCraftInputSlot(craftSlot);
            }
            ItemStack present = StorageHelper.getItemStackInSlot(currentCraftSlot);
            if (toFill == null || toFill.isEmpty()) {
                if (present.getItem() != Items.AIR) {
                    // a held stack would just trade places with the stray one, hands free first
                    if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) return putDownCursor();
                    // Move this item OUT if it should be empty
                    setDebugState("Found INVALID slot");
                    mod.getSlotHandler().clickSlot(currentCraftSlot, 0, ContainerInput.PICKUP);
                    return null;
                }
            } else {
                boolean correctItem = toFill.matches(present.getItem());
                boolean isSatisfied = correctItem && present.getCount() >= requiredPerSlot;
                if (!isSatisfied) {
                    // We have items that satisfy, but we CAN NOT fill in the current slot!
                    // In that case, just grab from the output.
                    // this used to ask about present.getItem(). an empty slot is AIR, and an inventory with any free
                    // slot "has" AIR, so every empty slot sent us off to move an item we did not own
                    if (!mod.getItemStorage().hasItemInventoryOnly(toFill.getMatches())) {
                        if (!StorageHelper.getItemStackInSlot(outputSlot).isEmpty()) {
                            setDebugState("NO MORE to fit: grabbing from output.");
                            return new ReceiveCraftingOutputSlotTask(outputSlot, _target.getTargetCount());
                        } else {
                            // Move on to the NEXT slot, we can't fill this one anymore.
                            continue;
                        }
                    }

                    setDebugState("Moving item to slot...");
                    return new MoveItemToSlotFromInventoryTask(new ItemTarget(toFill, requiredPerSlot), currentCraftSlot);
                }
                // We could be OVER satisfied
                boolean oversatisfies = present.getCount() > requiredPerSlot;
                if (oversatisfies) {
                    // right click with something held drops one in or swaps, not the half-out we want
                    if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) return putDownCursor();
                    setDebugState("OVER SATISFIED slot! Right clicking slot to extract half and spread it out more.");
                    mod.getSlotHandler().clickSlot(currentCraftSlot, 1, ContainerInput.PICKUP);
                    return null;
                }
            }
        }

        // Ensure our cursor is empty/can receive our item. this used to click fit-or-garbage inline every tick, which with a
        // full bag just traded two junk stacks through the cursor forever (nothing ever got thrown), and its click ate the
        // one shared slot cooldown so the cursor task under ReceiveCraftingOutputSlotTask never got a turn. that task
        // knows how to throw, let it have the cursor
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursor.isEmpty() && !ItemHelper.canStackTogether(StorageHelper.getItemStackInSlot(outputSlot), cursor)) {
            return putDownCursor();
        }

        if (!StorageHelper.getItemStackInSlot(outputSlot).isEmpty()) {
            return new ReceiveCraftingOutputSlotTask(outputSlot, _target.getTargetCount());
        } else {
            // Wait
            return null;
        }
    }

    private Task putDownCursor() {
        setDebugState("Putting down the held stack first");
        return new EnsureFreeCursorSlotTask();
    }

    private Slot gridSlot(int craftSlot, boolean bigCrafting) {
        return bigCrafting
                ? CraftingTableSlot.getInputSlot(craftSlot, _target.getRecipe().isBig())
                : PlayerSlot.getCraftInputSlot(craftSlot);
    }

    // groups the recipe slots by ingredient (bread is three wheat, a hay block is nine). a group of two or more that
    // still wants items gets a drag task, but only when one stack of one item can cover every slot that wants
    // something (activeCount says so). mixed planks go through the single slot path below. 2x2 in the inventory goes through the same thing, the grid slot ids differ
    // and that is all
    private Task dragFillTask(AltoClef mod, boolean bigCrafting, int perSlot) {
        int count = _target.getRecipe().getSlotCount();
        // the drag gave up not long ago, one slot at a time until it cools off
        if (DragSplitToSlotsTask.recentlyBailed()) return null;
        boolean[] grouped = new boolean[count];
        for (int i = 0; i < count; ++i) {
            ItemTarget ingredient = _target.getRecipe().getSlot(i);
            if (grouped[i] || ingredient == null || ingredient.isEmpty()) continue;
            List<Slot> group = new ArrayList<>();
            for (int j = i; j < count; ++j) {
                ItemTarget other = _target.getRecipe().getSlot(j);
                if (other == null || other.isEmpty() || !Arrays.equals(ingredient.getMatches(), other.getMatches())) continue;
                grouped[j] = true;
                group.add(gridSlot(j, bigCrafting));
            }
            if (group.size() < 2) continue;
            // nothing to pick up means the old path gets to say "no more to fit" and grab the output
            boolean haveSome = mod.getItemStorage().hasItemInventoryOnly(ingredient.getMatches())
                    || ingredient.matches(StorageHelper.getItemStackInCursorSlot().getItem());
            if (haveSome && DragSplitToSlotsTask.activeCount(mod, ingredient, group, perSlot) >= 2) {
                return new DragSplitToSlotsTask(ingredient, group, perSlot);
            }
        }
        return null;
    }

    // the smallest stack any ingredient of this recipe can form, one grid slot never holds more than that
    private static int stackLimit(RecipeTarget target) {
        int limit = 64;
        for (int i = 0; i < target.getRecipe().getSlotCount(); ++i) {
            ItemTarget slot = target.getRecipe().getSlot(i);
            if (slot == null || slot.isEmpty()) continue;
            for (Item item : slot.getMatches()) {
                limit = Math.min(limit, Math.max(1, new ItemStack(item).getMaxStackSize()));
            }
        }
        return limit;
    }

    /**
     * True when the recipe can not run again: nothing left to feed it and nothing waiting in the output. Crafting
     * "as many as possible" (a target of 99999999) is only ever done through this, the parent drops us when it flips.
     */
    public static boolean isOutOfMaterials(AltoClef mod, RecipeTarget target) {
        return StorageHelper.craftsPossible(mod, target.getRecipe(), 1) <= 0 && !StorageHelper.craftOutputWaiting();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return isOutOfMaterials(mod, _target);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CraftGenericManuallyTask task) {
            return task._target.equals(_target);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Crafting: " + _target;
    }

    @Override
    protected String toHudString() {
        // same words as the craft task above it on purpose, the hud folds the two into one line
        return "Crafting " + HudText.items(new Item[]{_target.getOutputItem()}, _target.getTargetCount());
    }
}
