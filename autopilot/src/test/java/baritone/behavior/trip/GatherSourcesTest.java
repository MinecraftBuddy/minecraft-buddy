package baritone.behavior.trip;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// what a trip gather goes for. the one thing it must never do is chase an item it can't get by mining
public class GatherSourcesTest {

    @Before
    public void setUp() {
        ItemFixture.boot();
    }

    @Test
    public void theDefaultThrowawaysMinusTheOnesThatDontStay() {
        List<Item> out = GatherSources.items(List.of(Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.STONE, Items.SAND, Items.GRAVEL, Items.DIRT));
        assertEquals(List.of(Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK), out);
    }

    @Test
    public void itemsThatBreakIntoSomethingElseAreLeftOut() {
        assertTrue(GatherSources.items(List.of(Items.GLASS, Items.DEEPSLATE, Items.GRASS_BLOCK, Items.OAK_LEAVES, Items.ICE, Items.STICK)).isEmpty());
        assertFalse(GatherSources.dropsItself(Blocks.GLASS));
        assertTrue(GatherSources.dropsItself(Blocks.COBBLED_DEEPSLATE));
        assertTrue(GatherSources.dropsItself(Blocks.OAK_PLANKS));
    }

    @Test
    public void cobblestoneAndDirtComeFromWhatsLyingAround() {
        List<Block> sources = GatherSources.sources(List.of(Items.COBBLESTONE, Items.DIRT));
        assertTrue(sources.contains(Blocks.STONE));
        assertTrue(sources.contains(Blocks.COBBLESTONE));
        assertTrue(sources.contains(Blocks.GRASS_BLOCK));
        assertTrue(sources.contains(Blocks.DIRT));
    }

    @Test
    public void stoneDropsNeedAPickaxeDirtDoesnt() {
        assertTrue(GatherSources.needsPickaxe(Blocks.STONE));
        assertFalse(GatherSources.needsPickaxe(Blocks.DIRT));
    }

    @Test
    public void byHandKeepsTheDirtAndDropsTheStone() {
        List<Block> hand = GatherSources.byHand(GatherSources.sources(List.of(Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK)));
        assertTrue(hand.contains(Blocks.DIRT));
        assertTrue(hand.contains(Blocks.GRASS_BLOCK));
        assertFalse(hand.contains(Blocks.STONE));
        assertFalse(hand.contains(Blocks.COBBLESTONE));
        assertFalse(hand.contains(Blocks.NETHERRACK));
        assertTrue(GatherSources.byHand(GatherSources.sources(List.of(Items.NETHERRACK))).isEmpty());
    }
}
