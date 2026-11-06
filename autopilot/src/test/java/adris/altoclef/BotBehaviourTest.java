package adris.altoclef;

import baritone.altoclef.AltoClefSettings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class BotBehaviourTest {

    private final AltoClefSettings s = AltoClefSettings.getInstance();

    @After
    public void clean() {
        s.resetAll();
    }

    @Test
    public void syncLeavesAMatchingListAlone() {
        s.getBreakAvoiders().add(pos -> true);
        AltoClefSettings.Snapshot before = s.snapshot();
        // same predicates in the same order is what every applyState after the first one looks like
        BotBehaviour.sync(s.getBreakAvoiders(), new ArrayList<>(s.getBreakAvoiders()));
        assertSame(before, s.snapshot());
    }

    @Test
    public void syncRewritesAListThatDiffers() {
        s.getBreakAvoiders().add(pos -> true);
        AltoClefSettings.Snapshot before = s.snapshot();
        List<Predicate<BlockPos>> other = new ArrayList<>();
        other.add(pos -> false);
        BotBehaviour.sync(s.getBreakAvoiders(), other);
        assertNotSame(before, s.snapshot());
        assertFalse(s.shouldAvoidBreaking(new BlockPos(1, 2, 3)));
    }

    @Test
    public void syncOnASetOnlyCaresAboutMembership() {
        Set<BlockPos> want = new HashSet<>(Arrays.asList(new BlockPos(1, 1, 1), new BlockPos(2, 2, 2)));
        s.getBlocksToAvoidBreaking().addAll(want);
        AltoClefSettings.Snapshot before = s.snapshot();
        BotBehaviour.sync(s.getBlocksToAvoidBreaking(), want);
        assertSame(before, s.snapshot());

        BotBehaviour.sync(s.getBlocksToAvoidBreaking(), new HashSet<>(List.of(new BlockPos(9, 9, 9))));
        assertTrue(s.shouldAvoidBreaking(new BlockPos(9, 9, 9)));
        assertFalse(s.shouldAvoidBreaking(new BlockPos(1, 1, 1)));
    }

    // the walk to the table, mid recipe: one level saves 6 cobble (the craft), the collect task under it saves 6 too
    @Test
    public void reserveLevelsAddUp() {
        Map<String, Integer> craft = Map.of("cobble", 6);
        Map<String, Integer> collect = Map.of("cobble", 6, "dirt", 2);
        Map<String, Integer> out = BotBehaviour.sumReserves(List.of(craft, collect), List.of(Set.of(), Set.of()));
        assertEquals(12, (int) out.get("cobble"));
        assertEquals(2, (int) out.get("dirt"));
    }

    @Test
    public void aLevelThatProtectedWithoutANumberKeepsTheItemWhole() {
        // absent from the map is how the pathing side reads "all of it", whatever the other levels said
        Map<String, Integer> collect = Map.of("cobble", 6);
        Map<String, Integer> out = BotBehaviour.sumReserves(List.of(collect, Map.of()), List.of(Set.of(), Set.of("cobble")));
        assertFalse(out.containsKey("cobble"));
    }

    @Test
    public void aLevelThatPopsTakesItsNumberWithIt() {
        Map<String, Integer> craft = Map.of("cobble", 6);
        Map<String, Integer> collect = Map.of("cobble", 6);
        assertEquals(12, (int) BotBehaviour.sumReserves(List.of(craft, collect), List.of(Set.of(), Set.of())).get("cobble"));
        assertEquals(6, (int) BotBehaviour.sumReserves(List.of(craft), List.of(Set.of())).get("cobble"));
        assertTrue(BotBehaviour.sumReserves(List.of(), List.of()).isEmpty());
    }

    @Test
    public void reserveSumsStopAtAllOfIt() {
        Map<String, Integer> huge = Map.of("cobble", Integer.MAX_VALUE - 1);
        assertEquals(Integer.MAX_VALUE, (int) BotBehaviour.sumReserves(List.of(huge, huge), List.of(Set.of(), Set.of())).get("cobble"));
    }

    // ---- floors ----

    @Test
    public void aFloorIsMaxedWithTheTaskReservesNotAddedToThem() {
        // the stone kit wants 16 and the collect task 18: slices of one need, so 18 and not 34
        Map<String, Integer> floor = Map.of("cobble", 16);
        Map<String, Integer> collect = Map.of("cobble", 18);
        Map<String, Integer> craft = Map.of("cobble", 6);
        assertEquals(18, (int) BotBehaviour.sumReserves(List.of(collect), List.of(floor), List.of(Set.of())).get("cobble"));
        // the task levels still add up among themselves, then the floor is the lower bound
        assertEquals(24, (int) BotBehaviour.sumReserves(List.of(collect, craft), List.of(floor), List.of(Set.of(), Set.of())).get("cobble"));
        assertEquals(30, (int) BotBehaviour.sumReserves(List.of(collect, craft), List.of(Map.of("cobble", 30)), List.of(Set.of(), Set.of())).get("cobble"));
    }

    @Test
    public void theFloorHoldsWhenTheTaskReservesAreSmaller() {
        // the craft only reserves its own recipe (6), the sword and the furnace are the floor's business
        Map<String, Integer> out = BotBehaviour.sumReserves(List.of(Map.of("cobble", 6)), List.of(Map.of("cobble", 16)), List.of(Set.of()));
        assertEquals(16, (int) out.get("cobble"));
        // alone it is the whole answer
        assertEquals(16, (int) BotBehaviour.sumReserves(List.of(), List.of(Map.of("cobble", 16)), List.of()).get("cobble"));
        // two floors take the bigger one
        assertEquals(20, (int) BotBehaviour.sumReserves(List.of(), List.of(Map.of("cobble", 16), Map.of("cobble", 20)), List.of()).get("cobble"));
    }

    @Test
    public void aFloorAtZeroSaysNothingIsSpokenForButStillSaysIt() {
        // absent would read as all of it, so the zero has to stay in the map
        Map<String, Integer> out = BotBehaviour.sumReserves(List.of(), List.of(Map.of("cobble", 0)), List.of());
        assertEquals(0, (int) out.get("cobble"));
        // and a reserve on top still counts
        out = BotBehaviour.sumReserves(List.of(Map.of("cobble", 6)), List.of(Map.of("cobble", 0)), List.of(Set.of()));
        assertEquals(6, (int) out.get("cobble"));
    }

    @Test
    public void aWholeProtectionBeatsTheFloor() {
        Map<String, Integer> out = BotBehaviour.sumReserves(List.of(), List.of(Map.of("cobble", 16)), List.of(Set.of("cobble")));
        assertFalse(out.containsKey("cobble"));
    }

    @Test
    public void otherItemsAreUntouchedByAFloor() {
        Map<String, Integer> out = BotBehaviour.sumReserves(List.of(Map.of("dirt", 2)), List.of(Map.of("cobble", 16)), List.of(Set.of()));
        assertEquals(2, (int) out.get("dirt"));
        assertEquals(16, (int) out.get("cobble"));
    }

    // ---- which level a write lands in ----

    @Test
    public void aWriteLandsInTheCallersLevelAndTheOnesAboveIt() {
        Object bottom = new Object();
        Object parent = new Object();
        Object child = new Object();
        Deque<Object> stack = new ArrayDeque<>();
        stack.push(bottom);
        stack.push(parent);
        stack.push(child);
        // the child was pushed after the parent, so it copied what the parent protected and has to hear about new items too
        assertEquals(List.of(child, parent), BotBehaviour.levelsFrom(stack, parent));
        assertEquals(List.of(child), BotBehaviour.levelsFrom(stack, child));
        assertEquals(List.of(child, parent, bottom), BotBehaviour.levelsFrom(stack, bottom));
    }

    @Test
    public void aLevelThatIsGoneTakesNoWrites() {
        Object bottom = new Object();
        Object popped = new Object();
        Deque<Object> stack = new ArrayDeque<>();
        stack.push(bottom);
        assertTrue(BotBehaviour.levelsFrom(stack, popped).isEmpty());
    }

    @Test
    public void aParentWritingAfterAChildPushedDoesNotCountTwice() {
        // the bug: the parent's 18 went into the child's level every tick while its own level kept the old 18, 36 for as
        // long as the child lived and 18 again the moment it popped
        Map<String, Integer> parent = Map.of("cobble", 18);
        Map<String, Integer> childOld = Map.of("cobble", 18);
        assertEquals(36, (int) BotBehaviour.sumReserves(List.of(childOld, parent), List.of(Set.of(), Set.of())).get("cobble"));
        // now the parent writes into its own level, the child's stays empty
        Map<String, Integer> childNow = Map.of();
        assertEquals(18, (int) BotBehaviour.sumReserves(List.of(childNow, parent), List.of(Set.of(), Set.of())).get("cobble"));
    }

    @Test
    public void syncOfTwoEmptyCollectionsDoesNotDirty() {
        s.getBreakAvoiders().add(pos -> true);
        AltoClefSettings.Snapshot before = s.snapshot();
        BotBehaviour.sync(s.getPlaceAvoiders(), new ArrayList<>());
        BotBehaviour.sync(s.getBlocksToAvoidBreaking(), new HashSet<>());
        assertSame(before, s.snapshot());
    }
}
