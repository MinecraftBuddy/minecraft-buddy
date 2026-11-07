package baritone.altoclef;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ThrowawayMergeTest {
    @Test
    public void mergeKeepsFirstOrderAndDropsDuplicates() {
        List<String> merged = BaritoneSettingsScope.mergeUnique(List.of("dirt", "cobble", "netherrack"),
                List.of("netherrack", "dirt", "soul", "cobble", "end"));
        assertEquals(List.of("dirt", "cobble", "netherrack", "soul", "end"), merged);
    }

    // the stone gather mines blackstone for tools, so the walker never builds with it
    @Test
    public void walkerBuildsWithNoBlackstone() {
        ItemFixture.boot();
        assertFalse(BaritoneSettingsScope.walkerPlaces(Items.BLACKSTONE));
        assertFalse(BaritoneSettingsScope.walkerPlaces(Items.GRAVEL));
        assertTrue(BaritoneSettingsScope.walkerPlaces(Items.NETHERRACK));
        assertTrue(BaritoneSettingsScope.walkerPlaces(Items.COBBLESTONE));
    }

    // the nether brick ingot from bartering is on the alto junk list; in hand it places nothing, so a sneak bridge picked
    // it, clicked, and hung over the drop. the block of nether bricks still builds
    @Test
    public void walkerBuildsWithBlocksOnly() {
        ItemFixture.boot();
        assertFalse(BaritoneSettingsScope.walkerPlaces(Items.NETHER_BRICK));
        assertFalse(BaritoneSettingsScope.placeable(Items.NETHER_BRICK));
        assertTrue(BaritoneSettingsScope.walkerPlaces(Items.NETHER_BRICKS));
        for (Item item : BaritoneAPI.getSettings().altoThrowawayItems.defaultValue) {
            if (BaritoneSettingsScope.walkerPlaces(item)) {
                assertTrue(item + " is on the walker's list", item instanceof BlockItem);
            }
        }
    }
}
