package baritone.utils.schematic.library;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

// what the player has, by item id. counts items, not block states: a stack of torches covers wall torches too, since
// the material list already turned both into "torch". no chest counting, just whatever is in the slots handed in
public final class InventoryCounts {

    private InventoryCounts() {
    }

    // works on player.getInventory(): main slots, armor and offhand all count
    public static Map<String, Integer> of(Container container) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            add(out, container.getItem(i));
        }
        return out;
    }

    public static Map<String, Integer> of(Iterable<ItemStack> stacks) {
        Map<String, Integer> out = new HashMap<>();
        for (ItemStack stack : stacks) {
            add(out, stack);
        }
        return out;
    }

    private static void add(Map<String, Integer> out, ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            out.merge(MaterialList.itemId(stack.getItem()), stack.getCount(), Integer::sum);
        }
    }
}
