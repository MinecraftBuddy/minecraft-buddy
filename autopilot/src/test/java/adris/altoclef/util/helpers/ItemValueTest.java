package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

public class ItemValueTest {

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    // args: pickWanted, isPick, core, kit
    @Test
    public void tiersInOrder() {
        assertEquals(ItemValue.PICK, ItemValue.tier(true, true, false, true));
        // holding a pick already, a pickaxe is just kit
        assertEquals(ItemValue.KIT, ItemValue.tier(false, true, false, true));
        assertEquals(ItemValue.CORE, ItemValue.tier(true, false, true, true));
        assertEquals(ItemValue.KIT, ItemValue.tier(false, false, false, true));
        assertEquals(ItemValue.REST, ItemValue.tier(false, false, false, false));
    }

    @Test
    public void coreIsPearlsEyesRodsPowder() {
        assertTrue(ItemValue.isCore(Items.ENDER_PEARL));
        assertTrue(ItemValue.isCore(Items.ENDER_EYE));
        assertTrue(ItemValue.isCore(Items.BLAZE_ROD));
        assertTrue(ItemValue.isCore(Items.BLAZE_POWDER));
        assertFalse(ItemValue.isCore(Items.BREAD));
        assertFalse(ItemValue.isCore(Items.DIAMOND));
    }

    @Test
    public void kitByItem() {
        assertTrue(ItemValue.isKitItem(Items.BREAD));
        assertTrue(ItemValue.isKitItem(Items.GOLDEN_APPLE));
        assertTrue(ItemValue.isKitItem(Items.IRON_SWORD));
        assertTrue(ItemValue.isKitItem(Items.WATER_BUCKET));
        assertTrue(ItemValue.isKitItem(Items.POTION));
        assertTrue(ItemValue.isKitItem(Items.ARROW));
        assertTrue(ItemValue.isKitItem(ItemHelper.BED[0]));
        assertFalse(ItemValue.isKitItem(Items.COBBLESTONE));
        assertFalse(ItemValue.isKitItem(Items.ROTTEN_FLESH));
        assertFalse(ItemValue.isKitItem(Items.STRING));
        // the stone ones are only kit through the gamer's protect list, which is the world half
        assertFalse(ItemValue.isKitItem(Items.STONE_PICKAXE));
    }
}
