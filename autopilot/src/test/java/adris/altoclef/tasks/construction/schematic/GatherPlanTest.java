package adris.altoclef.tasks.construction.schematic;

import adris.altoclef.tasks.construction.schematic.GatherPlan.Plan;
import adris.altoclef.tasks.construction.schematic.GatherPlan.Slot;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GatherPlanTest {

    private static final Predicate<String> ALL = id -> true;
    // beds stack to one, everything else to 64. ender pearls to 16
    private static final ToIntFunction<String> STACKS = id -> switch (id) {
        case "red_bed" -> 1;
        case "ender_pearl" -> 16;
        default -> 64;
    };

    private static List<Slot> bag(int empty, Slot... full) {
        List<Slot> out = new ArrayList<>(List.of(full));
        for (int i = 0; i < empty; i++) {
            out.add(Slot.empty());
        }
        while (out.size() < 36) {
            out.add(new Slot("dirt", 64, 64));
        }
        return out;
    }

    private static Map<String, Integer> ordered(Object... pairs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }

    @Test
    public void everythingFitsInAnEmptyBag() {
        Plan p = GatherPlan.plan(ordered("oak_planks", 100, "cobblestone", 30), Map.of(), ALL, bag(36), STACKS);
        assertEquals(ordered("oak_planks", 100, "cobblestone", 30), p.batch());
        assertFalse(p.more());
        assertTrue(p.ungettable().isEmpty());
    }

    @Test
    public void whatWeCarryComesOffTheBill() {
        Plan p = GatherPlan.plan(ordered("cobblestone", 100), Map.of("cobblestone", 70), ALL,
            bag(30, new Slot("cobblestone", 64, 64), new Slot("cobblestone", 6, 64)), STACKS);
        assertEquals(Map.of("cobblestone", 30), p.batch());
    }

    @Test
    public void enoughAlreadyIsNotInTheBatch() {
        Plan p = GatherPlan.plan(ordered("cobblestone", 10), Map.of("cobblestone", 64), ALL, bag(10), STACKS);
        assertTrue(p.nothingToGet());
        assertFalse(p.bagFull());
    }

    @Test
    public void twoFreeSlotsAreKeptForTheCraftsOnTheWay() {
        // 5 empty, 3 usable: 192 planks this round, the other 108 next round
        Plan p = GatherPlan.plan(ordered("oak_planks", 300), Map.of(), ALL, bag(5), STACKS);
        assertEquals(Map.of("oak_planks", 192), p.batch());
        assertTrue(p.more());
    }

    @Test
    public void theReserveNeverTakesTheLastHole() {
        assertEquals(1, GatherPlan.usableSlots(bag(1)));
        assertEquals(1, GatherPlan.usableSlots(bag(2)));
        assertEquals(1, GatherPlan.usableSlots(bag(3)));
        assertEquals(0, GatherPlan.usableSlots(bag(0)));
    }

    @Test
    public void aHalfStackFillsUpBeforeANewSlot() {
        // 40 in a slot: 24 more go on top, then one slot for the remaining 26
        Plan p = GatherPlan.plan(ordered("cobblestone", 90, "stone", 64), Map.of("cobblestone", 40), ALL,
            bag(3, new Slot("cobblestone", 40, 64)), STACKS);
        assertEquals(ordered("cobblestone", 50), p.batch());
        assertTrue(p.more());
    }

    @Test
    public void aFullBagWithRoomOnTopStillTopsUp() {
        Plan p = GatherPlan.plan(ordered("cobblestone", 100), Map.of("cobblestone", 10), ALL,
            bag(0, new Slot("cobblestone", 10, 64)), STACKS);
        assertEquals(Map.of("cobblestone", 54), p.batch());
        assertTrue(p.more());
        assertFalse(p.bagFull());
    }

    @Test
    public void nothingFitsIsABagFull() {
        Plan p = GatherPlan.plan(ordered("cobblestone", 100), Map.of(), ALL, bag(0), STACKS);
        assertTrue(p.nothingToGet());
        assertTrue(p.bagFull());
    }

    @Test
    public void unstackablesTakeASlotEach() {
        // 6 empty = 4 usable = 4 beds, and the pearls don't get a slot
        Plan p = GatherPlan.plan(ordered("red_bed", 5, "ender_pearl", 20), Map.of(), ALL, bag(6), STACKS);
        assertEquals(ordered("red_bed", 4), p.batch());
        assertTrue(p.more());
    }

    @Test
    public void smallStacksCountTheirOwnSize() {
        Plan p = GatherPlan.plan(ordered("ender_pearl", 40), Map.of(), ALL, bag(4), STACKS);
        assertEquals(Map.of("ender_pearl", 32), p.batch());
    }

    @Test
    public void laterItemsSqueezeIntoWhatIsLeft() {
        // 4 usable: 130 planks take 3, 10 cobble take the 4th, glass doesn't fit
        Plan p = GatherPlan.plan(ordered("oak_planks", 130, "cobblestone", 10, "glass", 5), Map.of(), ALL, bag(6), STACKS);
        assertEquals(ordered("oak_planks", 130, "cobblestone", 10), p.batch());
        assertTrue(p.more());
    }

    @Test
    public void ungettableIsListedAndSkipped() {
        Set<String> known = Set.of("oak_planks");
        Plan p = GatherPlan.plan(ordered("white_concrete", 50, "oak_planks", 20), Map.of("white_concrete", 8),
            known::contains, bag(36), STACKS);
        assertEquals(Map.of("oak_planks", 20), p.batch());
        assertEquals(Map.of("white_concrete", 42), p.ungettable());
        assertFalse(p.more());
    }

    @Test
    public void onlyUngettableIsNothingToGetButNotABagFull() {
        Plan p = GatherPlan.plan(ordered("white_concrete", 50), Map.of(), id -> false, bag(36), STACKS);
        assertTrue(p.nothingToGet());
        assertFalse(p.bagFull());
        assertEquals(Map.of("white_concrete", 50), p.ungettable());
    }

    @Test
    public void theBuildersMissingTopsUpTheDiff() {
        Map<String, Integer> need = GatherPlan.need(ordered("stone", 10, "glass", 4), Map.of("glass", 9, "torch", 2));
        assertEquals(ordered("stone", 10, "glass", 9, "torch", 2), need);
        assertEquals(ordered("stone", 10), GatherPlan.need(ordered("stone", 10), null));
    }

    @Test
    public void slotMaths() {
        assertEquals(0, GatherPlan.slotsFor(0, 64));
        assertEquals(0, GatherPlan.slotsFor(-5, 64));
        assertEquals(1, GatherPlan.slotsFor(1, 64));
        assertEquals(1, GatherPlan.slotsFor(64, 64));
        assertEquals(2, GatherPlan.slotsFor(65, 64));
        assertEquals(3, GatherPlan.slotsFor(3, 1));
        assertEquals(30, GatherPlan.roomIn(List.of(new Slot("a", 40, 64), new Slot("a", 58, 64), new Slot("b", 1, 64)), "a"));
    }
}
