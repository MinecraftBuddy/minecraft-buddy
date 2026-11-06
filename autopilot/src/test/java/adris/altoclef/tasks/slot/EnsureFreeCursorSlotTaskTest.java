package adris.altoclef.tasks.slot;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EnsureFreeCursorSlotTaskTest {
    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // the last resort throws a plain cursor stack as before, gear trades places with the least valuable bag stack instead.
    // the craft output with no room in the bag is usually a tool, which is the whole reason this exists
    @Test
    public void gearIsWhatTheLastResortKeeps() {
        assertTrue(EnsureFreeCursorSlotTask.isGear(Items.IRON_PICKAXE));
        assertTrue(EnsureFreeCursorSlotTask.isGear(Items.STONE_SWORD));
        assertTrue(EnsureFreeCursorSlotTask.isGear(Items.IRON_CHESTPLATE));
        assertTrue(EnsureFreeCursorSlotTask.isGear(Items.SHIELD));
        assertFalse(EnsureFreeCursorSlotTask.isGear(Items.NETHERRACK));
        assertFalse(EnsureFreeCursorSlotTask.isGear(Items.COOKED_BEEF));
        assertFalse(EnsureFreeCursorSlotTask.isGear(Items.STICK));
    }
}
