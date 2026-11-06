package baritone.altoclef;

import adris.altoclef.util.helpers.ItemFixture;
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
}
