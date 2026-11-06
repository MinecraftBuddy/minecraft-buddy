package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.WalkCost;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

// leaving the mine with something still cooking at the bottom of it
public class PackUpTest {
    private static RunState.FurnaceJob job(int x, int y, int z, long doneTick) {
        return new RunState.FurnaceJob(new RunState.Pos(x, y, z), "OVERWORLD", "furnace", "raw_iron", 3, "iron_ingot", 0, doneTick);
    }

    @Test
    public void onlyWhatIsReallyDownTheMineIsStranded() {
        assertFalse(PackUp.stranded(0));
        assertFalse(PackUp.stranded(SmeltSurface.GO_UP_DEPTH));
        assertTrue(PackUp.stranded(SmeltSurface.GO_UP_DEPTH + 1));
        // the smoker at y 32 under a surface around 70
        assertTrue(PackUp.stranded(SmeltSurface.depth(70, 32)));
    }

    // the trip is a TAKE_ALL with this as its "nearly done" window, so a job that is about to finish is waited for and the rest
    // is taken back out
    @Test
    public void theWaitIsAsLongAsTheWalkAwayAndNeverUnderTenSeconds() {
        assertEquals(FurnacePlan.NEARLY_TICKS, FurnacePlan.leaveWindow(1));
        assertEquals(FurnacePlan.PATIENCE_TICKS, FurnacePlan.leaveWindow(500));
        assertTrue(FurnacePlan.leaveWindow(20) > FurnacePlan.leaveWindow(10));
        // 30 down: 600 ticks, a job that finishes inside it is waited for
        assertEquals(600, FurnacePlan.leaveWindow(30));
    }

    @Test
    public void thePickIsTheDeepJobThatIsReadyFirst() {
        RunState.FurnaceJob up = job(0, 70, 0, 100);
        RunState.FurnaceJob deepLate = job(5, 30, 0, 900);
        RunState.FurnaceJob deepSoon = job(9, 32, 0, 400);
        List<RunState.FurnaceJob> jobs = List.of(up, deepLate, deepSoon);
        RunState.FurnaceJob got = PackUp.pick(jobs, new HashSet<>(), j -> 70 - j.pos.y, j -> 10);
        assertSame(deepSoon, got);
    }

    @Test
    public void aSurfaceJobIsLeftToTheNormalCollect() {
        List<RunState.FurnaceJob> jobs = List.of(job(0, 68, 0, 100));
        assertNull(PackUp.pick(jobs, new HashSet<>(), j -> 2, j -> 3));
    }

    @Test
    public void aTripAlreadyMadeIsNotRepeated() {
        RunState.FurnaceJob deep = job(5, 30, 0, 900);
        Set<RunState.Pos> tried = new HashSet<>();
        assertSame(deep, PackUp.pick(List.of(deep), tried, j -> 40, j -> 10));
        tried.add(deep.pos);
        assertNull(PackUp.pick(List.of(deep), tried, j -> 40, j -> 10));
    }

    @Test
    public void aFurnaceOnTheOtherSideOfTheWorldIsNotWorthTheTripNow() {
        RunState.FurnaceJob far = job(400, 30, 0, 900);
        assertNull(PackUp.pick(List.of(far), new HashSet<>(), j -> 40, j -> PackUp.walk(j, 0.5, 30, 0.5)));
        // 12 blocks down is 48 of walking, 10 down and 20 across is 60: both worth the one trip now
        RunState.FurnaceJob near = job(0, 18, 0, 900);
        assertSame(near, PackUp.pick(List.of(near), new HashSet<>(), j -> 52, j -> PackUp.walk(j, 0.5, 30, 0.5)));
        RunState.FurnaceJob across = job(20, 20, 0, 900);
        assertSame(across, PackUp.pick(List.of(across), new HashSet<>(), j -> 50, j -> PackUp.walk(j, 0.5, 30, 0.5)));
    }

    // the pack-up trip is a one off chance to take the contents without a second trip down, so it keeps its own walk number and is
    // not the station line: a furnace 30 across and 10 down is past STATION_NEAR as a straight line (31.5) and not reusable, but 70
    // of walking is still worth the one trip
    @Test
    public void theTripLineIsItsOwnNumberNotTheStationLine() {
        assertEquals(180, PackUp.TRIP_BUDGET, 0);
        RunState.FurnaceJob farButWorthIt = job(30, 20, 0, 900);
        double walk = PackUp.walk(farButWorthIt, 0.5, 30, 0.5);
        assertFalse(WalkCost.stationDistance(farButWorthIt.pos.x, farButWorthIt.pos.y, farButWorthIt.pos.z, 0.5, 30, 0.5) <= WalkCost.STATION_NEAR);
        assertTrue(walk <= PackUp.TRIP_BUDGET);
        assertSame(farButWorthIt, PackUp.pick(List.of(farButWorthIt), new HashSet<>(), j -> 50, j -> PackUp.walk(j, 0.5, 30, 0.5)));
    }
}
