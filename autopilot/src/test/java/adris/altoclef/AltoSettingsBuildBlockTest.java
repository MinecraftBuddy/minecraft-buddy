package adris.altoclef;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// one rule for "baritone can place this", so the gate and the nether restock count the same bag
public class AltoSettingsBuildBlockTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void plainBlocksCount() {
        assertTrue(AltoSettings.isBuildBlock(Items.COBBLESTONE));
        assertTrue(AltoSettings.isBuildBlock(Items.NETHERRACK));
        assertTrue(AltoSettings.isBuildBlock(Items.GRANITE));
        assertTrue(AltoSettings.isBuildBlock(Items.DIRT));
    }

    @Test
    public void junkInTheListDoesNot() {
        assertFalse(AltoSettings.isBuildBlock(Items.OAK_LEAVES));
        assertFalse(AltoSettings.isBuildBlock(Items.AZALEA_LEAVES));
        assertFalse(AltoSettings.isBuildBlock(Items.MAGMA_BLOCK));
        assertFalse(AltoSettings.isBuildBlock(Items.SOUL_SAND));
    }

    @Test
    public void thingsThatFallOverDoNot() {
        assertFalse(AltoSettings.isBuildBlock(Items.SAND));
        assertFalse(AltoSettings.isBuildBlock(Items.GRAVEL));
    }

    @Test
    public void anyFallingBlockDoesNot() {
        assertFalse(AltoSettings.isBuildBlock(Items.RED_SAND));
        assertFalse(AltoSettings.isBuildBlock(Items.CONCRETE_POWDER.white()));
    }

    @Test
    public void nonBlocksDoNot() {
        assertFalse(AltoSettings.isBuildBlock(Items.STICK));
        assertFalse(AltoSettings.isBuildBlock(Items.NETHER_BRICK));
    }
}
