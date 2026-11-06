package adris.altoclef.tasks.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

// the block a pillbox (and the brew box, built as one) is built of: one of its building blocks, never what the brew needs. a brew
// box's wall cell got the brewing stand and the brew had none
public class BlazePillboxBlocksTest {

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final List<Item> BREW = List.of(Items.BREWING_STAND, Items.GLASS_BOTTLE, Items.POTION, Items.BLAZE_ROD,
            Items.BLAZE_POWDER, Items.NETHER_WART, Items.MAGMA_CREAM);

    @Test
    public void theBrewsItemsAreNeverABlock() {
        for (Item item : BREW) assertTrue(item.toString(), BlazePillboxTask.brewItem(item));
        // (a bag of the brew's things alone has nothing to build with)
        assertNull(BlazePillboxTask.buildingBlock(BREW::contains));
    }

    @Test
    public void aBuildingBlockBesideTheStandIsTheBlock() {
        Set<Item> bag = Set.of(Items.BREWING_STAND, Items.NETHER_WART, Items.BLAZE_ROD, Items.NETHERRACK);
        assertEquals(Items.NETHERRACK, BlazePillboxTask.buildingBlock(bag::contains));
        assertEquals(Items.COBBLESTONE, BlazePillboxTask.buildingBlock(Set.of(Items.BREWING_STAND, Items.COBBLESTONE, Items.NETHERRACK)::contains));
        for (Item item : List.of(Items.COBBLESTONE, Items.NETHERRACK, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE, Items.STONE, Items.DIRT,
                Items.NETHER_BRICKS, Items.BASALT)) {
            assertFalse(item.toString(), BlazePillboxTask.brewItem(item));
        }
    }
}
