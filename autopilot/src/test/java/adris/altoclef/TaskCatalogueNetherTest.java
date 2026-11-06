package adris.altoclef;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaskCatalogueNetherTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static Item item(String path) {
        return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(path));
    }

    @Test
    public void theNetherOnesSayNether() {
        // pinned there by the catalogue
        assertTrue(TaskCatalogue.needsNether(item("blaze_rod")));
        assertTrue(TaskCatalogue.needsNether(item("quartz")));
        // a craft away from a blaze rod
        assertTrue(TaskCatalogue.needsNether(item("brewing_stand")));
        // a task that walks there without the flag
        assertTrue(TaskCatalogue.needsNether(item("nether_bricks")));
        assertTrue(TaskCatalogue.needsNether(item("magma_cream")));
        // smelted from netherrack, and a craft of that
        assertTrue(TaskCatalogue.needsNether(item("nether_brick")));
        assertTrue(TaskCatalogue.needsNether(item("nether_brick_fence")));
        // smithing is a netherite ingot
        assertTrue(TaskCatalogue.needsNether(item("netherite_pickaxe")));
        // the nether woods' blocks come from lambdas, not the recipe helpers
        assertTrue(TaskCatalogue.needsNether(item("crimson_stairs")));
        assertTrue(TaskCatalogue.needsNether(item("warped_door")));
    }

    @Test
    public void theOverworldOnesDoNot() {
        assertFalse(TaskCatalogue.needsNether(item("cobblestone")));
        assertFalse(TaskCatalogue.needsNether(item("glass")));
        assertFalse(TaskCatalogue.needsNether(item("oak_planks")));
        // gold goes anywhere, the overworld ore is fine
        assertFalse(TaskCatalogue.needsNether(item("gold_ingot")));
        assertFalse(TaskCatalogue.needsNether(item("gold_block")));
        // "planks" takes any planks, crimson being one of them doesn't make a crafting table a nether trip
        assertFalse(TaskCatalogue.needsNether(item("crafting_table")));
        assertFalse(TaskCatalogue.needsNether(item("stone_bricks")));
    }

    @Test
    public void everyCataloguedItemGetsAnAnswer() {
        // no throw and no endless loop on any of them, and asking twice gives the same answer
        List<String> flipped = new ArrayList<>();
        for (String name : TaskCatalogue.resourceNames()) {
            for (Item item : TaskCatalogue.getItemMatches(name)) {
                boolean first = TaskCatalogue.needsNether(item);
                if (first != TaskCatalogue.needsNether(item)) {
                    flipped.add(name);
                }
            }
        }
        assertTrue("answers changed on the second ask: " + flipped, flipped.isEmpty());
    }
}
