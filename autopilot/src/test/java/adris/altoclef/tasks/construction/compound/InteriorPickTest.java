package adris.altoclef.tasks.construction.compound;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class InteriorPickTest {

    // inside cells first, then the rings, like PORTAL_INTERIOR
    private static final List<String> CELLS = List.of("in0", "in1", "ring0", "ring1");

    private final Set<String> air = new HashSet<>();
    private final Set<String> banned = new HashSet<>();
    private final Set<String> reach = new HashSet<>();

    private String pick(String current, int noReachTicks) {
        return pick(current, noReachTicks, 0);
    }

    private String pick(String current, int noReachTicks, int reachSwitches) {
        return InteriorPick.pick(CELLS, air::contains, banned::contains, reach::contains, current, noReachTicks, reachSwitches);
    }

    @Test
    public void allAirIsNothing() {
        air.addAll(CELLS);
        assertNull(pick(null, 0));
    }

    @Test
    public void nothingInReachIsListOrder() {
        assertEquals("in0", pick(null, 0));
        air.add("in0");
        assertEquals("in1", pick(null, 0));
    }

    @Test
    public void aCellWeCanHitGoesFirst() {
        // the ring cell blocking the line to the inside one
        reach.add("ring1");
        assertEquals("ring1", pick(null, 0));
    }

    @Test
    public void bannedCellsAreSkipped() {
        banned.add("in0");
        assertEquals("in1", pick(null, 0));
        reach.add("in0");
        assertEquals("in1", pick(null, 0));
    }

    @Test
    public void everythingBannedIsListOrder() {
        banned.addAll(CELLS);
        reach.add("ring0");
        assertEquals("in0", pick(null, 0));
        // and the cell we're on stays, hopping between banned cells gets nowhere
        assertEquals("ring1", pick("ring1", 100));
    }

    @Test
    public void aBannedCellIsDropped() {
        banned.add("in0");
        assertEquals("in1", pick("in0", 0));
    }

    @Test
    public void reachlessForASecondSwitchesToOneWithReach() {
        reach.add("ring0");
        assertEquals("in0", pick("in0", InteriorPick.NO_REACH_SWITCH_TICKS - 1));
        assertEquals("ring0", pick("in0", InteriorPick.NO_REACH_SWITCH_TICKS));
    }

    @Test
    public void reachlessButNothingElseHasReachStays() {
        assertEquals("in1", pick("in1", 1000));
    }

    @Test
    public void flickeringReachDoesNotSwitch() {
        reach.add("ring0");
        // the task's counter: a few ticks without a ray, then it's back, then a few more
        int ticks = 0;
        boolean[] hadReach = {false, false, false, true, false, false, false, false, true, false};
        for (boolean had : hadReach) {
            ticks = had ? 0 : ticks + 1;
            assertEquals("in0", pick("in0", ticks));
        }
    }

    @Test
    public void switchingForReachRunsOut() {
        reach.add("ring0");
        assertEquals("ring0", pick("in0", 100, InteriorPick.MAX_REACH_SWITCHES - 1));
        // two cells that keep handing the ray to each other stop trading here
        assertEquals("in0", pick("in0", 100, InteriorPick.MAX_REACH_SWITCHES));
        // a ban still gets us off it
        banned.add("in0");
        assertEquals("ring0", pick("in0", 100, InteriorPick.MAX_REACH_SWITCHES));
    }

    @Test
    public void currentGoneToAirPicksFresh() {
        air.add("in0");
        assertEquals("in1", pick("in0", 0));
    }

    @Test
    public void ringOnlyStopsTheFlintUntilItHasBeenAirOnce() {
        assertEquals(18, InteriorPick.preemptCount(18, 6, false));
        assertEquals(6, InteriorPick.preemptCount(18, 6, true));
    }

    @Test
    public void ringSeenClearNeedsEveryOutsideCellAirAtSomePoint() {
        List<String> cells = List.of("in0", "in1", "r0", "r1");
        Set<String> seen = new HashSet<>();
        // in-plane cells don't count, and one ring cell alone isn't enough
        assertEquals(false, InteriorPick.ringSeenClear(cells, 2, seen, c -> c.equals("in0") || c.equals("r0")));
        // r0 refills, r1 clears: never both at once, still counts as seen
        assertEquals(true, InteriorPick.ringSeenClear(cells, 2, seen, c -> c.equals("r1")));
    }
}
