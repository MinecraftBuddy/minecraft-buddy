package adris.altoclef.tasks.squashed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

// which sibling the catalogued gather works on. a "task" here is just its targets, the counts are handed in, no game
public class CataloguedResourceTaskTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void planksLentToTheGridStillCount() {
        // birch first and short by the four sitting in the 2x2 for the stairs' crafting table. counting the grid it is met,
        // so the stairs keep the wheel instead of birch yanking its planks back out
        ItemTarget[] birch = {new ItemTarget(Items.BIRCH_PLANKS, 112)};
        ItemTarget[] stairs = {new ItemTarget(Items.SPRUCE_STAIRS, 57)};
        Map<Item, Integer> inventory = Map.of(Items.BIRCH_PLANKS, 108);
        Map<Item, Integer> grid = Map.of(Items.BIRCH_PLANKS, 4);
        List<ItemTarget[]> tasks = List.of(birch, stairs);

        assertSame(birch, CataloguedResourceTask.firstUnmet(tasks, t -> t, t -> count(t, inventory)));
        assertSame(stairs, CataloguedResourceTask.firstUnmet(tasks, t -> t, t -> count(t, inventory) + count(t, grid)));
    }

    @Test
    public void allMetIsNull() {
        ItemTarget[] birch = {new ItemTarget(Items.BIRCH_PLANKS, 4)};
        Map<Item, Integer> held = Map.of(Items.BIRCH_PLANKS, 4);
        assertNull(CataloguedResourceTask.firstUnmet(List.<ItemTarget[]>of(birch), t -> t, t -> count(t, held)));
    }

    // ores are the strings starting with "ore:", the merge glues them into one "+" string
    private static List<String> together(String... tasks) {
        List<String> list = new ArrayList<>(List.of(tasks));
        CataloguedResourceTask.oresTogether(list, t -> t.startsWith("ore:"), ores -> String.join("+", ores));
        return list;
    }

    @Test
    public void oresMergeInOrderAtTheFirstOnesSlot() {
        assertEquals(List.of("ore:iron+ore:diamond"), together("ore:iron", "ore:diamond"));
        assertEquals(List.of("ore:diamond+ore:iron"), together("ore:diamond", "ore:iron"));
    }

    @Test
    public void oneOreStaysAsItIs() {
        // and stays at all: the list is the constructor's own, emptying it would make the task finished on the spot
        assertEquals(List.of("planks", "ore:iron", "stick"), together("planks", "ore:iron", "stick"));
        assertEquals(List.of("planks"), together("planks"));
        assertEquals(List.of(), together());
    }

    @Test
    public void nonOresKeepTheirSlots() {
        assertEquals(List.of("log", "ore:coal+ore:iron+ore:diamond", "sand", "cobble"),
                together("log", "ore:coal", "sand", "ore:iron", "cobble", "ore:diamond"));
        assertEquals(List.of("ore:coal+ore:iron", "log"), together("ore:coal", "log", "ore:iron"));
    }

    private static int count(ItemTarget target, Map<Item, Integer> where) {
        int n = 0;
        for (Item item : target.getMatches()) {
            n += where.getOrDefault(item, 0);
        }
        return n;
    }
}
