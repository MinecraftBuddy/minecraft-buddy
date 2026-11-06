package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.Test;

// which bed the run may walk to, and when it has had enough of villages
public class BedRulesTest {
    private static final List<RunState.Pos> NONE = List.of();
    private static final Predicate<RunState.Pos> ANY = p -> true;

    private static RunState.Pos pos(int x, int y, int z) {
        return new RunState.Pos(x, y, z);
    }

    private static RunState.Pos pick(List<RunState.Pos> beds, List<RunState.Pos> evidence, List<RunState.Pos> own,
                                     List<RunState.Pos> ownJobs, List<RunState.Pos> tried, Predicate<RunState.Pos> usable) {
        return BedRules.pick(beds, evidence, own, ownJobs, tried, usable, 0.5, 64, 0.5, 48, 16);
    }

    @Test
    public void aLoneBedIsNotAVillageBed() {
        // a player base, a bed in a cave: both halves, nothing else around
        List<RunState.Pos> bed = List.of(pos(5, 64, 5), pos(5, 64, 6));
        assertNull(pick(bed, NONE, NONE, NONE, NONE, ANY));
        // and a job block far from it is somebody else's village
        assertNull(pick(bed, List.of(pos(5, 64, 40)), NONE, NONE, NONE, ANY));
    }

    @Test
    public void aJobBlockOrABellCloseByMakesItAVillageBed() {
        List<RunState.Pos> bed = List.of(pos(5, 64, 5), pos(5, 64, 6));
        assertEquals(pos(5, 64, 5), pick(bed, List.of(pos(12, 64, 9)), NONE, NONE, NONE, ANY));
    }

    @Test
    public void aSecondBedOfAnotherHouseCounts() {
        List<RunState.Pos> beds = List.of(pos(5, 64, 5), pos(5, 64, 6), pos(14, 64, 5), pos(15, 64, 5));
        // the nearer house first
        assertEquals(pos(5, 64, 5), pick(beds, NONE, NONE, NONE, NONE, ANY));
        // but the same bed does not vouch for itself
        assertNull(pick(beds.subList(0, 2), NONE, NONE, NONE, NONE, ANY));
        // and one 30 blocks off is a different house of a different story
        assertNull(pick(List.of(pos(5, 64, 5), pos(5, 64, 6), pos(35, 64, 5), pos(36, 64, 5)), NONE, NONE, NONE, NONE, ANY));
    }

    @Test
    public void ourOwnBedIsNeverTakenAndNeverVouches() {
        List<RunState.Pos> spawn = List.of(pos(5, 64, 5));
        // the spawn bed, either half, next to a job block we did not place
        List<RunState.Pos> bed = List.of(pos(5, 64, 5), pos(5, 64, 6));
        assertNull(pick(bed, List.of(pos(8, 64, 5)), spawn, NONE, NONE, ANY));
        // a lone bed next to our spawn bed is still a lone bed
        assertNull(pick(List.of(pos(5, 64, 5), pos(5, 64, 6), pos(9, 64, 5), pos(10, 64, 5)), NONE, List.of(pos(5, 64, 6)), NONE, NONE, ANY));
    }

    @Test
    public void aJobBlockWePlacedIsNotAVillage() {
        // a blast furnace crafted for the kit, set down next to the spawn bed
        List<RunState.Pos> bed = List.of(pos(5, 64, 5), pos(5, 64, 6));
        List<RunState.Pos> furnace = List.of(pos(8, 64, 5));
        assertNull(pick(bed, furnace, NONE, furnace, NONE, ANY));
        assertEquals(pos(5, 64, 5), pick(bed, furnace, NONE, NONE, NONE, ANY));
    }

    @Test
    public void nearestFirstAndOutOfRangeIsOut() {
        List<RunState.Pos> evidence = List.of(pos(0, 64, 0), pos(0, 64, 100));
        List<RunState.Pos> beds = List.of(pos(20, 64, 0), pos(10, 64, 0), pos(0, 64, 100));
        assertEquals(pos(10, 64, 0), pick(beds, evidence, NONE, NONE, NONE, ANY));
        assertNull(pick(List.of(pos(0, 64, 100)), evidence, NONE, NONE, NONE, ANY));
    }

    @Test
    public void aBedWeWentForIsWrittenOffAndSoIsItsOtherHalf() {
        List<RunState.Pos> evidence = List.of(pos(8, 64, 0));
        List<RunState.Pos> beds = List.of(pos(10, 64, 0), pos(11, 64, 0), pos(30, 64, 0), pos(31, 64, 0));
        List<RunState.Pos> tried = new ArrayList<>(List.of(pos(10, 64, 0)));
        assertEquals(pos(30, 64, 0), pick(beds, List.of(pos(8, 64, 0), pos(33, 64, 0)), NONE, NONE, tried, ANY));
        // the first half was the one that failed, the second half must not be tried instead
        assertNull(pick(beds.subList(0, 2), evidence, NONE, NONE, tried, ANY));
    }

    @Test
    public void usableDecidesAndIsOnlyAskedInOrder() {
        List<RunState.Pos> evidence = List.of(pos(8, 64, 0));
        List<RunState.Pos> beds = List.of(pos(10, 64, 0), pos(20, 64, 0));
        List<RunState.Pos> asked = new ArrayList<>();
        // the near bed is behind a wall, so the far one it is
        assertEquals(pos(20, 64, 0), pick(beds, evidence, NONE, NONE, NONE, p -> {
            asked.add(p);
            return p.x == 20;
        }));
        assertEquals(List.of(pos(10, 64, 0), pos(20, 64, 0)), asked);
        assertNull(pick(beds, evidence, NONE, NONE, NONE, p -> false));
    }

    @Test
    public void theNightBedIsOursWhileItStandsAndNeverAVillageBed() {
        // a night bed up on a pillar in a village, a job block next to it: without the own list it is a village's and the
        // bed shortfall would break it and pocket it as a free bed. with it, both halves are left alone
        List<RunState.Pos> bed = List.of(pos(5, 64, 5), pos(5, 64, 6));
        List<RunState.Pos> evidence = List.of(pos(8, 64, 5));
        assertEquals(pos(5, 64, 5), pick(bed, evidence, NONE, NONE, NONE, ANY));
        List<RunState.Pos> own = BedRules.ownBeds(null, pos(5, 64, 5));
        assertEquals(List.of(pos(5, 64, 5)), own);
        assertNull(pick(bed, evidence, own, NONE, NONE, ANY));
        // and it does not vouch for a lone bed beside it any more than the spawn bed does
        assertNull(pick(List.of(pos(5, 64, 5), pos(5, 64, 6), pos(9, 64, 5), pos(10, 64, 5)), NONE, own, NONE, NONE, ANY));
    }

    @Test
    public void ownBedsAreWhicheverOfTheTwoExist() {
        assertTrue(BedRules.ownBeds(null, null).isEmpty());
        assertEquals(List.of(pos(1, 2, 3)), BedRules.ownBeds(pos(1, 2, 3), null));
        assertEquals(List.of(pos(1, 2, 3), pos(4, 5, 6)), BedRules.ownBeds(pos(1, 2, 3), pos(4, 5, 6)));
    }

    @Test
    public void theBudgetIsTicksAndSurvivesARelog() {
        assertTrue(BedRules.withinBudget(0, 120));
        assertTrue(BedRules.withinBudget(2399, 120));
        assertFalse(BedRules.withinBudget(2400, 120));
        assertFalse(BedRules.withinBudget(0, 0));
    }
}
