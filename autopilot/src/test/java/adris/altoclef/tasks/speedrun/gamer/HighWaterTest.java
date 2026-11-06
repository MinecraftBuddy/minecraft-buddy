package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.ItemFixture;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HighWaterTest {
    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static Map<Item, Integer> bag(Item a, int an, Item b, int bn) {
        Map<Item, Integer> m = new HashMap<>();
        m.put(a, an);
        m.put(b, bn);
        return m;
    }

    @Test
    public void swappingABucketBackAndForthIsNotProgress() {
        HighWater h = new HighWater();
        h.reset(bag(Items.BUCKET, 1, Items.OBSIDIAN, 4));
        assertTrue(h.rose(bag(Items.WATER_BUCKET, 1, Items.OBSIDIAN, 4)));
        assertFalse(h.rose(bag(Items.BUCKET, 1, Items.OBSIDIAN, 4)));
        assertFalse(h.rose(bag(Items.WATER_BUCKET, 1, Items.OBSIDIAN, 4)));
        assertFalse(h.rose(bag(Items.BUCKET, 1, Items.OBSIDIAN, 4)));
    }

    @Test
    public void aNewMaxOrANewItemIsProgress() {
        HighWater h = new HighWater();
        h.reset(bag(Items.BUCKET, 1, Items.OBSIDIAN, 4));
        assertTrue(h.rose(bag(Items.BUCKET, 1, Items.OBSIDIAN, 5)));
        assertFalse(h.rose(bag(Items.BUCKET, 1, Items.OBSIDIAN, 3)));
        assertFalse(h.rose(bag(Items.BUCKET, 1, Items.OBSIDIAN, 5)));
        assertTrue(h.rose(bag(Items.BUCKET, 1, Items.FLINT_AND_STEEL, 1)));
    }
}
