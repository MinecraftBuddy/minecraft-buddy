package adris.altoclef.util.helpers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

// (nether_keep_rack) in the nether netherrack, blackstone and basalt are the building blocks the bag keeps, NETHER_KEEP of them,
// netherrack first; the barter's junk goes first. an M4 run threw 113 netherrack in the piglin trade and 25 basalt after it, and
// the brew's pillbox starved with 9 blocks in the bag for 15
public class GarbageNetherTest {

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    private static List<ItemStack> bag(Object... itemsAndCounts) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < itemsAndCounts.length; i += 2) out.add(new ItemStack((Item) itemsAndCounts[i], (Integer) itemsAndCounts[i + 1]));
        return out;
    }

    @Test
    public void theTradesTwoStacksOfNetherrackAreKept() {
        // 64 and 49: the second is kept too (64 kept before it, under 96); the basalt after them is not
        assertArrayEquals(new boolean[]{true, true, false}, GarbageRules.netherKept(bag(Items.NETHERRACK, 64, Items.NETHERRACK, 49, Items.BASALT, 25)));
        // a third stack of netherrack is past the keep
        assertArrayEquals(new boolean[]{true, true, false}, GarbageRules.netherKept(bag(Items.NETHERRACK, 64, Items.NETHERRACK, 64, Items.NETHERRACK, 10)));
    }

    @Test
    public void netherrackIsKeptFirstWhereverItSits() {
        // basalt and blackstone first in the bag: netherrack is counted first, then blackstone, then basalt
        boolean[] kept = GarbageRules.netherKept(bag(Items.BASALT, 40, Items.BLACKSTONE, 40, Items.NETHERRACK, 64, Items.COBBLESTONE, 12));
        assertArrayEquals(new boolean[]{false, true, true, false}, kept);
        // little netherrack: the basalt makes the rest up
        assertArrayEquals(new boolean[]{true, true}, GarbageRules.netherKept(bag(Items.BASALT, 25, Items.NETHERRACK, 20)));
        // the biggest stack of a kind first
        assertArrayEquals(new boolean[]{false, true, true}, GarbageRules.netherKept(bag(Items.NETHERRACK, 5, Items.NETHERRACK, 64, Items.NETHERRACK, 40)));
    }

    @Test
    public void theBartersJunkGoesFirstAndBasaltIsNoJunkThere() {
        java.util.function.ToIntFunction<Item> wide = GarbageRules::junkAllRank;
        assertEquals(0, GarbageRules.junkNether(wide, Items.NETHER_BRICK));
        assertEquals(1, GarbageRules.junkNether(wide, Items.SOUL_SAND));
        assertEquals(2, GarbageRules.junkNether(wide, Items.GRAVEL));
        assertEquals(-1, GarbageRules.junkNether(wide, Items.BASALT));
        assertEquals(-1, GarbageRules.junkNether(wide, Items.NETHERRACK));
        assertEquals(-1, GarbageRules.junkNether(wide, Items.BLACKSTONE));
        // the rest of the tier after the barter's, in its own order
        assertEquals(GarbageRules.junkAllRank(Items.CINNABAR) + 3, GarbageRules.junkNether(wide, Items.CINNABAR));
        assertEquals(GarbageRules.junkAllRank(Items.CALCITE) + 3, GarbageRules.junkNether(wide, Items.CALCITE));
        assertEquals(-1, GarbageRules.junkNether(wide, Items.IRON_INGOT));
    }

    @Test
    public void aFullBagInTheNetherThrowsTheBarterFirstAndNeverTheKeptRack() {
        List<ItemStack> stacks = bag(Items.NETHERRACK, 64, Items.NETHERRACK, 49, Items.BASALT, 25, Items.SOUL_SAND, 10, Items.GOLD_INGOT, 20);
        boolean[] kept = GarbageRules.netherKept(stacks);
        java.util.Map<Item, Integer> ranks = GarbageRules.throwRanks(new Item[]{Items.GRAVEL, Items.COBBLESTONE, Items.NETHERRACK, Items.SOUL_SAND,
                Items.BASALT, Items.BLACKSTONE});
        List<GarbageRules.Entry> entries = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) entries.add(new GarbageRules.Entry(stacks.get(i), !kept[i], ranks.getOrDefault(stacks.get(i).getItem(), -1)));
        java.util.function.ToIntFunction<Item> nether = item -> GarbageRules.junkNether(GarbageRules::junkAllRank, item);
        // the soul sand, not the basalt the wide tier ranks ahead of it elsewhere
        assertEquals(3, GarbageRules.nextPick(entries, 64, true, 0, 0, nether, GarbageRules::kitItem));
        // the soul sand gone: the basalt past the keep, never the netherrack
        entries.set(3, new GarbageRules.Entry(ItemStack.EMPTY, true, -1));
        assertEquals(2, GarbageRules.nextPick(entries, 64, true, 0, 0, nether, GarbageRules::kitItem));
        // the basalt gone too: the netherrack stays, the old pick went for it (past the reserve of 64)
        entries.set(2, new GarbageRules.Entry(ItemStack.EMPTY, true, -1));
        List<GarbageRules.Entry> old = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) old.add(new GarbageRules.Entry(i == 2 || i == 3 ? ItemStack.EMPTY : stacks.get(i), true,
                i == 2 || i == 3 ? -1 : ranks.getOrDefault(stacks.get(i).getItem(), -1)));
        assertEquals(0, GarbageRules.junkPick(old, 64, GarbageRules::kitItem));
        assertEquals(-1, GarbageRules.junkPick(entries, 64, GarbageRules::kitItem));
    }
}
