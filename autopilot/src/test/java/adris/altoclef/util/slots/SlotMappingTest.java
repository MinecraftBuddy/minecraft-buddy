/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package adris.altoclef.util.slots;

import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.SmithingMenu;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.Assert.*;

// the numbers are read off 1.21.4's menus with javap (InventoryMenu, CraftingMenu, AbstractFurnaceMenu, SmithingMenu),
// not remembered. the vanilla constants are compile time ints so no game is needed. CraftingMenu's are private:
// result 0, grid 1-9, inventory 10-36, hotbar 37-45
public class SlotMappingTest {

    @Test
    public void playerMenuGrid() {
        assertEquals(InventoryMenu.RESULT_SLOT, PlayerSlot.CRAFT_OUTPUT_SLOT.getWindowSlot());
        assertEquals(4, PlayerSlot.CRAFT_INPUT_SLOTS.length);
        for (int i = 0; i < 4; i++) {
            assertEquals(InventoryMenu.CRAFT_SLOT_START + i, PlayerSlot.CRAFT_INPUT_SLOTS[i].getWindowSlot());
        }
        assertEquals(InventoryMenu.CRAFT_SLOT_END, PlayerSlot.CRAFT_INPUT_SLOTS[3].getWindowSlot() + 1);
        // x/y overload is row major
        assertEquals(1, PlayerSlot.getCraftInputSlot(0, 0).getWindowSlot());
        assertEquals(2, PlayerSlot.getCraftInputSlot(1, 0).getWindowSlot());
        assertEquals(3, PlayerSlot.getCraftInputSlot(0, 1).getWindowSlot());
        assertEquals(4, PlayerSlot.getCraftInputSlot(1, 1).getWindowSlot());
    }

    @Test
    public void playerMenuArmorAndOffhand() {
        assertEquals(InventoryMenu.ARMOR_SLOT_START, PlayerSlot.ARMOR_HELMET_SLOT.getWindowSlot());
        assertEquals(InventoryMenu.ARMOR_SLOT_END - 1, PlayerSlot.ARMOR_BOOTS_SLOT.getWindowSlot());
        assertEquals(InventoryMenu.SHIELD_SLOT, PlayerSlot.OFFHAND_SLOT.getWindowSlot());
    }

    @Test
    public void playerMenuInventoryAndHotbar() {
        assertEquals(InventoryMenu.USE_ROW_SLOT_START, new PlayerSlot(0, true).getWindowSlot());
        assertEquals(InventoryMenu.USE_ROW_SLOT_END - 1, new PlayerSlot(8, true).getWindowSlot());
        assertEquals(InventoryMenu.INV_SLOT_START, new PlayerSlot(9, true).getWindowSlot());
        assertEquals(InventoryMenu.INV_SLOT_END - 1, new PlayerSlot(35, true).getWindowSlot());
    }

    @Test
    public void craftingTableGridAndInventory() {
        assertEquals(0, CraftingTableSlot.OUTPUT_SLOT.getWindowSlot());
        assertEquals(9, CraftingTableSlot.INPUT_SLOTS.length);
        for (int i = 0; i < 9; i++) {
            assertEquals(i + 1, CraftingTableSlot.INPUT_SLOTS[i].getWindowSlot());
        }
        assertEquals(5, CraftingTableSlot.getInputSlot(1, 1).getWindowSlot());
        assertEquals(9, CraftingTableSlot.getInputSlot(2, 2).getWindowSlot());
        assertEquals(10, new CraftingTableSlot(9, true).getWindowSlot());
        assertEquals(36, new CraftingTableSlot(35, true).getWindowSlot());
        assertEquals(37, new CraftingTableSlot(0, true).getWindowSlot());
        assertEquals(45, new CraftingTableSlot(8, true).getWindowSlot());
    }

    @Test
    public void smallRecipeInTableIsTheTopLeftTwoByTwo() {
        // this used to give 2,4,5,7 and no 2x2 recipe ever matched in a table
        List<Integer> windows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            windows.add(CraftingTableSlot.getInputSlot(i, false).getWindowSlot());
        }
        assertEquals(List.of(1, 2, 4, 5), windows);
    }

    @Test
    public void bigRecipeInTableIsInOrder() {
        for (int i = 0; i < 9; i++) {
            assertEquals(i + 1, CraftingTableSlot.getInputSlot(i, true).getWindowSlot());
        }
    }

    @Test
    public void furnaceSlots() {
        assertEquals(AbstractFurnaceMenu.INGREDIENT_SLOT, FurnaceSlot.INPUT_SLOT_MATERIALS.getWindowSlot());
        assertEquals(AbstractFurnaceMenu.FUEL_SLOT, FurnaceSlot.INPUT_SLOT_FUEL.getWindowSlot());
        assertEquals(AbstractFurnaceMenu.RESULT_SLOT, FurnaceSlot.OUTPUT_SLOT.getWindowSlot());
        assertEquals(FurnaceSlot.OUTPUT_SLOT.getWindowSlot(), SmokerSlot.OUTPUT_SLOT.getWindowSlot());
        assertEquals(FurnaceSlot.INPUT_SLOT_FUEL.getWindowSlot(), BlastFurnaceSlot.INPUT_SLOT_FUEL.getWindowSlot());
        // inventory 3-29, hotbar 30-38
        assertEquals(3, new FurnaceSlot(9, true).getWindowSlot());
        assertEquals(29, new FurnaceSlot(35, true).getWindowSlot());
        assertEquals(30, new FurnaceSlot(0, true).getWindowSlot());
        assertEquals(38, new FurnaceSlot(8, true).getWindowSlot());
        assertEquals(30, new SmokerSlot(0, true).getWindowSlot());
        assertEquals(30, new BlastFurnaceSlot(0, true).getWindowSlot());
    }

    @Test
    public void smithingSlots() {
        assertEquals(SmithingMenu.TEMPLATE_SLOT, SmithingTableSlot.INPUT_SLOT_TEMPLATE.getWindowSlot());
        assertEquals(SmithingMenu.BASE_SLOT, SmithingTableSlot.INPUT_SLOT_TOOL.getWindowSlot());
        assertEquals(SmithingMenu.ADDITIONAL_SLOT, SmithingTableSlot.INPUT_SLOT_MATERIALS.getWindowSlot());
        assertEquals(SmithingMenu.RESULT_SLOT, SmithingTableSlot.OUTPUT_SLOT.getWindowSlot());
        // four slots up front, so inventory is 4-30 and the hotbar 31-39 (a furnace is one earlier)
        assertEquals(4, new SmithingTableSlot(9, true).getWindowSlot());
        assertEquals(30, new SmithingTableSlot(35, true).getWindowSlot());
        assertEquals(31, new SmithingTableSlot(0, true).getWindowSlot());
        assertEquals(39, new SmithingTableSlot(8, true).getWindowSlot());
    }

    private static void roundTrip(String name, BiFunction<Integer, Boolean, Slot> make) {
        // every one of the 36 player inventory slots goes to a window slot and back to the same one
        for (int inv = 0; inv < 36; inv++) {
            int window = make.apply(inv, true).getWindowSlot();
            assertEquals(name + " inv " + inv, inv, make.apply(window, false).getInventorySlot());
        }
    }

    @Test
    public void windowAndInventoryRoundTrip() {
        roundTrip("player", PlayerSlot::new);
        roundTrip("crafting table", CraftingTableSlot::new);
        roundTrip("furnace", FurnaceSlot::new);
        roundTrip("smoker", SmokerSlot::new);
        roundTrip("blast furnace", BlastFurnaceSlot::new);
        roundTrip("smithing", SmithingTableSlot::new);
        roundTrip("brewing", BrewingStandSlot::new);
        roundTrip("chest", (slot, inv) -> new ChestSlot(slot, false, inv));
        roundTrip("large chest", (slot, inv) -> new ChestSlot(slot, true, inv));
    }
}
