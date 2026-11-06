package adris.altoclef.tasks.container;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.ItemFixture;
import adris.altoclef.util.helpers.StationChoice;
import adris.altoclef.util.helpers.StationHook.Kind;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.Test;

// "the bag can make a furnace" and the split smelt's spare count only count the cobble the furnace craft can really eat: TaskCatalogue's
// recipe takes plain cobblestone, and so do the stone tools whose cobble is owed. cobbled deepslate and blackstone are neither
public class SpareCobbleTest {
    static {
        ItemFixture.boot();
    }

    private static int spare(Map<Item, Integer> bag, int owed) {
        return DoStuffInContainerTask.spareCobble(item -> bag.getOrDefault(item, 0), owed);
    }

    // the case that was wrong: 14 cobble + 8 deepslate with 14 owed read as 8 spare, and the craft then took 8 of the 14 the tools needed
    @Test
    public void deepslateOnTopOfTheOwedCobbleIsNotSpare() {
        Map<Item, Integer> bag = Map.of(Items.COBBLESTONE, 14, Items.COBBLED_DEEPSLATE, 8);
        assertEquals(0, spare(bag, 14));
        assertFalse(StationChoice.canMakeFrom(Kind.FURNACE, spare(bag, 14), 0, 0, 0));
        assertEquals(1, SmeltSplit.loads(37, 0, 0, spare(bag, 14)));
    }

    @Test
    public void blackstoneIsNotSpareEither() {
        Map<Item, Integer> bag = Map.of(Items.COBBLESTONE, 14, Items.COBBLED_DEEPSLATE, 8, Items.BLACKSTONE, 20);
        assertEquals(0, spare(bag, 14));
        assertFalse(StationChoice.canMakeFrom(Kind.FURNACE, spare(bag, 14), 0, 0, 0));
        // and a smoker is a furnace's cobble plus logs
        assertFalse(StationChoice.canMakeFrom(Kind.SMOKER, spare(bag, 14), 4, 0, 0));
    }

    // a bag of nothing but the other stones makes no furnace here, with or without anything owed
    @Test
    public void onlyOtherStonesMakeNoFurnace() {
        Map<Item, Integer> bag = Map.of(Items.COBBLED_DEEPSLATE, 64, Items.BLACKSTONE, 64);
        assertEquals(0, spare(bag, 0));
        assertFalse(StationChoice.canMakeFrom(Kind.FURNACE, spare(bag, 0), 0, 0, 0));
    }

    // plain cobble past what the tools are owed still counts, in 8s
    @Test
    public void plainCobblePastWhatIsOwedStillMakesFurnaces() {
        Map<Item, Integer> bag = Map.of(Items.COBBLESTONE, 22, Items.COBBLED_DEEPSLATE, 8);
        assertEquals(8, spare(bag, 14));
        assertTrue(StationChoice.canMakeFrom(Kind.FURNACE, spare(bag, 14), 0, 0, 0));
        assertEquals(1, SmeltSplit.loads(37, 0, 0, spare(bag, 14)));
        Map<Item, Integer> more = Map.of(Items.COBBLESTONE, 30, Items.COBBLED_DEEPSLATE, 8);
        assertEquals(2, SmeltSplit.loads(37, 0, 0, spare(more, 14)));
        // spare can come out negative when the bag is short of what is owed, loads copes with that
        assertEquals(-4, spare(Map.of(Items.COBBLESTONE, 10), 14));
        assertEquals(1, SmeltSplit.loads(37, 0, 0, spare(Map.of(Items.COBBLESTONE, 10), 14)));
    }
}
