package baritone.api.utils;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BlockOptionalMetaPickupTest {
    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    @Test
    public void oreAlsoMatchesItsOwnBlockItem() {
        List<Item> items = BlockOptionalMeta.pickupItems(Blocks.DIAMOND_ORE, List.of(Items.DIAMOND));
        assertTrue(items.contains(Items.DIAMOND));
        assertTrue(items.contains(Items.DIAMOND_ORE));
    }

    @Test
    public void blockThatDropsItselfIsNotDoubled() {
        List<Item> items = BlockOptionalMeta.pickupItems(Blocks.STONE_BRICKS, List.of(Items.STONE_BRICKS));
        assertEquals(List.of(Items.STONE_BRICKS), items);
    }

    @Test
    public void airHasNoItemToAdd() {
        assertEquals(List.of(), BlockOptionalMeta.pickupItems(Blocks.AIR, List.of()));
    }
}
