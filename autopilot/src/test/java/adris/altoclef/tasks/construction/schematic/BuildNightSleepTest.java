package adris.altoclef.tasks.construction.schematic;

import adris.altoclef.tasks.speedrun.gamer.NightSleep;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class BuildNightSleepTest {

    @Test
    public void noNightMeansTheBuildRunsAndItsGuardTicks() {
        assertEquals(BuildNightSleep.Turn.BUILD, BuildNightSleep.turn(false, false));
        assertEquals(BuildNightSleep.Turn.BUILD, BuildNightSleep.turn(false, true));
    }

    @Test
    public void aNightCutsARunningBuilderAndOnlyThen() {
        assertEquals(BuildNightSleep.Turn.CUT_BUILDER, BuildNightSleep.turn(true, true));
        // counting, gathering, walking back: nothing to cut, the night just has the tick
        assertEquals(BuildNightSleep.Turn.NIGHT, BuildNightSleep.turn(true, false));
    }

    @Test
    public void anotherDimensionHoldsTheNightOff() {
        assertEquals(NightSleep.Busy.PHASE, BuildNightSleep.busy(true, false, -1));
        assertEquals(NightSleep.Busy.NONE, BuildNightSleep.busy(false, false, -1));
    }

    @Test
    public void aScreenHoldsItForAWhileUnlessItsThePrepsOwn() {
        assertEquals(NightSleep.Busy.SCREEN, BuildNightSleep.busy(false, false, 0));
        assertEquals(NightSleep.Busy.SCREEN, BuildNightSleep.busy(false, false, NightSleep.SCREEN_PATIENCE_TICKS - 1));
        // stuck open, the trip shuts it
        assertEquals(NightSleep.Busy.NONE, BuildNightSleep.busy(false, false, NightSleep.SCREEN_PATIENCE_TICKS));
        assertEquals(NightSleep.Busy.NONE, BuildNightSleep.busy(false, true, 10));
    }

    @Test
    public void thePillarOnlyGetsWhatIsAboveTheFloor() {
        Map<String, Integer> held = new LinkedHashMap<>();
        held.put("cobblestone", 64);
        held.put("dirt", 20);
        held.put("andesite", 5);
        Map<String, Integer> floor = Map.of("cobblestone", 50, "andesite", 9);
        // 14 cobble over the floor, all the dirt, none of the andesite (and not minus four of it)
        assertEquals(34, BuildNightSleep.spare(held, id -> floor.getOrDefault(id, 0)));
    }

    @Test
    public void whatTheBuilderPlacedComesOffTheBill() {
        Map<String, Integer> left = new LinkedHashMap<>();
        left.put("cobblestone", 200);
        left.put("oak_planks", 40);
        left.put("glass", 10);
        Map<String, Integer> then = Map.of("cobblestone", 200, "oak_planks", 40, "glass", 4);
        // 150 cobble down, the planks untouched, more glass than before (a pickup) doesn't grow the bill
        Map<String, Integer> now = Map.of("cobblestone", 50, "oak_planks", 40, "glass", 9);
        Map<String, Integer> out = BuildNightSleep.leftAfterUse(left, then, now);
        assertEquals(Integer.valueOf(50), out.get("cobblestone"));
        assertEquals(Integer.valueOf(40), out.get("oak_planks"));
        assertEquals(Integer.valueOf(10), out.get("glass"));
        // burnt more than the bill on scaffolding: nothing owed, not less than nothing
        assertEquals(Integer.valueOf(0), BuildNightSleep.leftAfterUse(Map.of("dirt", 5), Map.of("dirt", 64), Map.of()).get("dirt"));
    }

    @Test
    public void aFullyReservedItemGivesNothingAndDoesNotWrap() {
        Map<String, Integer> held = Map.of("cobblestone", 64);
        assertEquals(0, BuildNightSleep.spare(held, id -> Integer.MAX_VALUE));
        assertEquals(0, BuildNightSleep.spare(Map.<String, Integer>of(), id -> 0));
    }

    @Test
    public void noBedSaysHowFarTheNearestOneIs() {
        assertEquals("no bed known, radius 24", BuildNightSleep.bedLine(Double.POSITIVE_INFINITY, 24));
        assertEquals("nearest known bed 37 blocks, radius 24", BuildNightSleep.bedLine(36.6, 24));
        // close enough but turned down (monsters, copper, can't reach it)
        assertEquals("nearest known bed 5 blocks, radius 24 (within, but no good)", BuildNightSleep.bedLine(5.2, 24));
    }
}
