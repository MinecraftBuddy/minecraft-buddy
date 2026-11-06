package adris.altoclef.util.slots;

import java.util.stream.IntStream;

public class CraftingTableSlot extends Slot {
    public static final CraftingTableSlot OUTPUT_SLOT = new CraftingTableSlot(0);

    public static final CraftingTableSlot[] INPUT_SLOTS = IntStream.range(0, 9).mapToObj(ind -> getInputSlot(ind, true)).toArray(CraftingTableSlot[]::new);

    public CraftingTableSlot(int windowSlot) {
        this(windowSlot, false);
    }

    protected CraftingTableSlot(int slot, boolean inventory) {
        super(slot, inventory);
    }

    public static CraftingTableSlot getInputSlot(int x, int y) {
        return getInputSlot(y * 3 + x, true);
    }

    public static CraftingTableSlot getInputSlot(int index, boolean big) {
        if (big) {
            // Default. window slot 0 is the output, the grid is 1-9
            return new CraftingTableSlot(index + 1);
        } else {
            // Small recipe in big window: a 2 wide recipe goes in the top left of the 3 wide grid. this used to
            // add the +1 for the output slot before splitting into x/y and then again inside getInputSlot, so
            // the 2x2 landed on slots 2,4,5,7 (a little z shape) and nothing ever matched
            return getInputSlot(index % 2, index / 2);
        }
    }

    @Override
    public int inventorySlotToWindowSlot(int inventorySlot) {
        if (inventorySlot < 9) {
            return inventorySlot + 37;
        }
        return inventorySlot + 1;
    }

    @Override
    protected int windowSlotToInventorySlot(int windowSlot) {
        if (windowSlot >= 37) {
            return windowSlot - 37;
        }
        return windowSlot - 1;
    }

    @Override
    protected String getName() {
        return "CraftingTable";
    }
}
