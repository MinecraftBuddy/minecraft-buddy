package adris.altoclef.trackers.storage;

import baritone.api.utils.Dimension;
import java.util.HashMap;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class ContainerCache {

    private final BlockPos _blockPos;
    private final Dimension _dimension;
    private final ContainerType _containerType;

    private final HashMap<Item, Integer> _itemCounts = new HashMap<>();
    private int _emptySlots;

    public ContainerCache(Dimension dimension, BlockPos blockPos, ContainerType containerType) {
        _dimension = dimension;
        _blockPos = blockPos;
        _containerType = containerType;
    }

    public void update(AbstractContainerMenu screenHandler, Consumer<ItemStack> onStack) {
        _itemCounts.clear();
        _emptySlots = 0;
        int start = 0;
        int end = screenHandler.slots.size() - (4 * 9); // subtract by player inventory
        // do NOT count the furnace output slot as an empty slot, it cannot be used.
        boolean isFurnace = (screenHandler instanceof FurnaceMenu);

        // Iterate through all STORAGE slots
        for (int i = start; i < end; ++i) {
            ItemStack stack = screenHandler.slots.get(i).getItem().copy();

            if (stack.isEmpty()) {
                // Ignore furnace output slot
                if (!(isFurnace && i == 2)) {
                    _emptySlots++;
                }
            } else {
                Item item = stack.getItem();
                int count = stack.getCount();
                _itemCounts.put(item, _itemCounts.getOrDefault(item, 0) + count);
                onStack.accept(stack);
            }
        }
    }

    public int getItemCount(Item... items) {
        int result = 0;
        for (Item item : items) {
            result += _itemCounts.getOrDefault(item, 0);
        }
        return result;
    }

    public boolean hasItem(Item... items) {
        for (Item item : items) {
            if (_itemCounts.containsKey(item) && _itemCounts.get(item) > 0)
                return true;
        }
        return false;
    }

    // anything at all in the last look we had (a furnace's input, fuel or output counts)
    public boolean holdsAnything() {
        return !_itemCounts.isEmpty();
    }

    // something in the last look that is not just fuel: ore, meat, anything cooked. a station with only spare fuel in it is not
    // holding anything worth keeping it standing for (a visit takes the spare fuel out anyway), and nothing ever visits a station
    // that has no job, so counting it would leave it behind for good
    public boolean holdsMoreThanFuel() {
        return sumOver((item, count) -> adris.altoclef.util.helpers.ItemHelper.isFuel(item) ? 0.0 : count) > 0;
    }

    // how much of the last look is not fuel, and the item there is most of (null for none). an interrupted load is adopted as a job
    // from these (Workbenches.adoptLoad)
    public int nonFuelCount() {
        return (int) sumOver((item, count) -> adris.altoclef.util.helpers.ItemHelper.isFuel(item) ? 0.0 : count);
    }

    public Item mostOfNonFuel() {
        Item best = null;
        int most = 0;
        for (var entry : _itemCounts.entrySet()) {
            if (!adris.altoclef.util.helpers.ItemHelper.isFuel(entry.getKey()) && entry.getValue() > most) {
                best = entry.getKey();
                most = entry.getValue();
            }
        }
        return best;
    }

    // adds up f(item, count) over what the last look saw
    public double sumOver(java.util.function.ToDoubleBiFunction<Item, Integer> f) {
        double total = 0;
        for (var entry : _itemCounts.entrySet()) {
            total += f.applyAsDouble(entry.getKey(), entry.getValue());
        }
        return total;
    }

    public int getEmptySlotCount() {
        return _emptySlots;
    }

    public boolean isFull() {
        return _emptySlots == 0;
    }

    public BlockPos getBlockPos() {
        return _blockPos;
    }

    public ContainerType getContainerType() {
        return _containerType;
    }

    public Dimension getDimension() {
        return _dimension;
    }
}
