package adris.altoclef.tasks.misc;

import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EquipArmorTaskTest {
    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // only the cursor test can run without a world, the rest of the task needs the mod. this is the one that keeps a diamond
    // piece fresh off a craft from being asked for again (and so mined for)
    @Test
    public void aPieceOnTheCursorCountsAsHeld() {
        ItemTarget chest = new ItemTarget(Items.DIAMOND_CHESTPLATE);
        assertTrue(EquipArmorTask.cursorHolds(new ItemStack(Items.DIAMOND_CHESTPLATE), chest));
        assertFalse(EquipArmorTask.cursorHolds(ItemStack.EMPTY, chest));
        assertFalse(EquipArmorTask.cursorHolds(new ItemStack(Items.IRON_CHESTPLATE), chest));
        assertFalse(EquipArmorTask.cursorHolds(new ItemStack(Items.DIAMOND_CHESTPLATE), new ItemTarget(Items.DIAMOND_CHESTPLATE, 2)));
    }
}
