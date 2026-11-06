package adris.altoclef;

import adris.altoclef.util.helpers.ItemFixture;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the blocks a schematic build can now gather. "has an entry" is what makes a build try for a block, so the colours with
// no dye source must have none or the build waits forever on something nothing can make
public class TaskCatalogueSchematicTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void terracottaAndWoodBlocksAreGettable() {
        for (String name : new String[]{"terracotta", "oak_wood", "stripped_oak_wood", "crimson_hyphae",
                "red_terracotta", "red_glazed_terracotta", "yellow_dye", "white_dye", "purple_dye"}) {
            assertTrue(name, TaskCatalogue.taskExists(name));
        }
        assertTrue(TaskCatalogue.isObtainable(net.minecraft.world.item.Items.TERRACOTTA));
        assertTrue(TaskCatalogue.isObtainable(net.minecraft.world.item.Items.OAK_WOOD));
    }

    @Test
    public void sourcedColoursGetGlassCarpetAndPanes() {
        for (String name : new String[]{"blue_stained_glass", "blue_stained_glass_pane", "lime_carpet", "orange_terracotta"}) {
            assertTrue(name, TaskCatalogue.taskExists(name));
        }
    }

    @Test
    public void unsourcedColoursGetNothingDyed() {
        for (String color : new String[]{"black", "brown", "gray"}) {
            assertFalse(color + "_dye", TaskCatalogue.taskExists(color + "_dye"));
            assertFalse(color + "_terracotta", TaskCatalogue.taskExists(color + "_terracotta"));
            assertFalse(color + "_glazed_terracotta", TaskCatalogue.taskExists(color + "_glazed_terracotta"));
            assertFalse(color + "_stained_glass", TaskCatalogue.taskExists(color + "_stained_glass"));
            assertFalse(color + "_stained_glass_pane", TaskCatalogue.taskExists(color + "_stained_glass_pane"));
        }
    }

    @Test
    public void strippedNetherWoodIsLeftOut() {
        assertFalse(TaskCatalogue.taskExists("stripped_crimson_hyphae"));
        assertFalse(TaskCatalogue.taskExists("stripped_warped_hyphae"));
    }

    @Test
    public void bambooHasNoWoodBlock() {
        assertFalse(TaskCatalogue.taskExists("bamboo_wood"));
    }
}
