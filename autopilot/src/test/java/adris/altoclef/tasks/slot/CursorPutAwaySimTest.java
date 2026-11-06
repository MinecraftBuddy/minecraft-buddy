package adris.altoclef.tasks.slot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.GarbageRules;
import adris.altoclef.util.helpers.GarbageRules.Cand;
import adris.altoclef.util.helpers.ItemFixture;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

// a full bag and a stack on the cursor that nothing fits, clicked through by hand. PICKUP on a bag slot swaps the two, a
// click outside the window throws the cursor. the picks come from the real GarbageRules and FreeSlotPlan
public class CursorPutAwaySimTest {

    private static final int RESERVE = 64;
    private static Map<Item, Integer> ranks;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
        ranks = GarbageRules.throwRanks(new Item[]{Items.GRAVEL, Items.DIORITE, Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK});
    }

    private static GarbageRules.Entry junk(Item item, int count) {
        return new GarbageRules.Entry(new ItemStack(item, count), true, ranks.get(item));
    }

    private static GarbageRules.Entry keep(Item item, int count) {
        return new GarbageRules.Entry(new ItemStack(item, count), false, -1);
    }

    // 36 slots, not one free: two small junk stacks up front, plenty more junk past the building reserve, keepers after
    private static List<GarbageRules.Entry> fullBag() {
        List<GarbageRules.Entry> bag = new ArrayList<>();
        bag.add(junk(Items.GRAVEL, 10));
        bag.add(junk(Items.DIORITE, 15));
        bag.add(junk(Items.COBBLESTONE, 64));
        bag.add(junk(Items.DIRT, 64));
        bag.add(junk(Items.NETHERRACK, 64));
        while (bag.size() < 36) {
            bag.add(keep(Items.IRON_INGOT, 64));
        }
        return bag;
    }

    private static int garbage(List<GarbageRules.Entry> bag) {
        return GarbageRules.nextPick(bag, RESERVE, false, 0, 0);
    }

    private static final class Sim {
        final List<GarbageRules.Entry> bag = fullBag();
        // reserved sticks: not throwable, and nothing in the bag takes them
        GarbageRules.Entry cursor = keep(Items.STICK, 4);
        final List<GarbageRules.Entry> thrown = new ArrayList<>();
        final Set<Item> lifted = new HashSet<>();

        void swap(int slot) {
            GarbageRules.Entry up = bag.get(slot);
            bag.set(slot, cursor);
            cursor = up;
            lifted.add(up.stack().getItem());
        }

        void throwCursor() {
            thrown.add(cursor);
            cursor = null;
        }

        // what CraftGenericManuallyTask did inline: fit (never, the bag is full) or else the garbage slot, and never a throw
        // while there is garbage
        void oldStep() {
            int g = garbage(bag);
            if (g >= 0) swap(g);
            else throwCursor();
        }

        // what EnsureFreeCursorSlotTask does: ask FreeSlotPlan, the lifted garbage is throwable so it goes the tick after
        void planStep() {
            FreeSlotPlan.Action action = FreeSlotPlan.plan(false, cursor.mayGo(), garbage(bag) >= 0, false);
            switch (action) {
                case CLICK_GARBAGE -> swap(garbage(bag));
                case THROW_CURSOR -> throwCursor();
                default -> throw new AssertionError("no way out: " + action);
            }
        }
    }

    @Test
    public void theOldFitOrGarbageClickJustTradesTwoJunkStacksForever() {
        Sim sim = new Sim();
        for (int step = 0; step < 20; step++) {
            sim.oldStep();
        }
        // the sticks went in on click one, then gravel and diorite took turns on the cursor and nothing ever left the bag
        assertTrue(sim.cursor != null);
        assertTrue(sim.thrown.isEmpty());
        assertEquals(Set.of(Items.GRAVEL, Items.DIORITE), sim.lifted);
    }

    @Test
    public void thePlanSwapsWithTheGarbageAndThrowsItNextTick() {
        Sim sim = new Sim();
        Item expected = sim.bag.get(garbage(sim.bag)).stack().getItem();
        int steps = 0;
        while (sim.cursor != null && steps < 3) {
            sim.planStep();
            steps++;
        }
        assertNull(sim.cursor);
        assertEquals(1, sim.thrown.size());
        assertEquals(Items.GRAVEL, expected);
        assertEquals(expected, sim.thrown.get(0).stack().getItem());
        // and the sticks are in the bag where the gravel was
        assertEquals(Items.STICK, sim.bag.get(0).stack().getItem());
        assertFalse(sim.bag.stream().anyMatch(e -> e.stack().getItem() == Items.GRAVEL));
    }

    private static Cand cand(Item item, int count) {
        return GarbageRules.cand(new ItemStack(item, count));
    }

    @Test
    public void theLastResortSkipsWhatWeAreHoldingOnToWhenItCan() {
        List<Cand> cands = List.of(cand(Items.WATER_BUCKET, 1), cand(Items.CRAFTING_TABLE, 1), cand(Items.COBBLESTONE, 64));
        // the old pick: one item is the smallest count, so the bucket went
        assertEquals(0, GarbageRules.lastResort(cands));
        assertEquals(2, GarbageRules.lastResort(cands, new boolean[]{true, true, false}));
        // every one of them guarded, then it is the old pick again rather than nothing
        assertEquals(0, GarbageRules.lastResort(cands, new boolean[]{true, true, true}));
        // the gapple stays out either way
        List<Cand> gapple = List.of(cand(Items.GOLDEN_APPLE, 1));
        assertEquals(-1, GarbageRules.lastResort(gapple, new boolean[]{true}));
    }
}
