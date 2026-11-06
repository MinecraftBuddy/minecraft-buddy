package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.VillageChests.Job;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.Test;

// which chest the iron phase may walk to, and when it has had enough of villages
public class VillageChestsTest {
    private static final List<RunState.Pos> NONE = List.of();
    private static final Predicate<RunState.Pos> ANY = p -> true;

    private static RunState.Pos pos(int x, int y, int z) {
        return new RunState.Pos(x, y, z);
    }

    private static RunState.Pos pick(List<RunState.Pos> chests, List<Job> jobs, List<RunState.Pos> own, List<RunState.Pos> tried, Predicate<RunState.Pos> usable) {
        return VillageChests.pick(chests, jobs, own, tried, usable, 0.5, 64, 0.5, 48, 6);
    }

    @Test
    public void aChestWithNoJobBlockNextToItIsNotAVillageChest() {
        // a random chest in a mineshaft or a ruined portal, and a grindstone nowhere near it
        List<Job> jobs = List.of(new Job(pos(30, 64, 0), VillageChests.GRINDSTONE));
        assertNull(pick(List.of(pos(5, 64, 5)), jobs, NONE, NONE, ANY));
        assertNull(pick(List.of(pos(5, 64, 5)), List.of(), NONE, NONE, ANY));
    }

    @Test
    public void aChestNextToAJobBlockIsACandidate() {
        List<Job> jobs = List.of(new Job(pos(20, 64, 0), VillageChests.BLAST_FURNACE));
        assertEquals(pos(23, 64, 2), pick(List.of(pos(23, 64, 2)), jobs, NONE, NONE, ANY));
    }

    @Test
    public void weaponsmithBeatsToolsmithBeatsArmorerEvenWhenFarther() {
        List<Job> jobs = List.of(
                new Job(pos(5, 64, 0), VillageChests.BLAST_FURNACE),
                new Job(pos(15, 64, 0), VillageChests.SMITHING_TABLE),
                new Job(pos(40, 64, 0), VillageChests.GRINDSTONE));
        List<RunState.Pos> chests = List.of(pos(5, 64, 3), pos(15, 64, 3), pos(40, 64, 3));
        assertEquals(pos(40, 64, 3), pick(chests, jobs, NONE, NONE, ANY));
        // the weaponsmith is written off, so the toolsmith is next, then the armorer
        List<RunState.Pos> tried = new ArrayList<>(List.of(pos(40, 64, 3)));
        assertEquals(pos(15, 64, 3), pick(chests, jobs, NONE, tried, ANY));
        tried.add(pos(15, 64, 3));
        assertEquals(pos(5, 64, 3), pick(chests, jobs, NONE, tried, ANY));
    }

    @Test
    public void sameRankGoesToTheNearerChest() {
        List<Job> jobs = List.of(new Job(pos(10, 64, 0), VillageChests.GRINDSTONE), new Job(pos(-30, 64, 0), VillageChests.GRINDSTONE));
        assertEquals(pos(10, 64, 3), pick(List.of(pos(-30, 64, 3), pos(10, 64, 3)), jobs, NONE, NONE, ANY));
    }

    @Test
    public void aJobBlockWeCraftedAndPlacedVouchesForNothing() {
        // the blast furnace we put down next to a chest that happens to be there
        List<Job> jobs = List.of(new Job(pos(10, 64, 0), VillageChests.BLAST_FURNACE));
        assertNull(pick(List.of(pos(10, 64, 3)), jobs, List.of(pos(10, 64, 0)), NONE, ANY));
        // but a village one next to the same chest still does
        List<Job> both = List.of(jobs.get(0), new Job(pos(12, 64, 5), VillageChests.SMITHING_TABLE));
        assertEquals(pos(10, 64, 3), pick(List.of(pos(10, 64, 3)), both, List.of(pos(10, 64, 0)), NONE, ANY));
    }

    @Test
    public void tooFarFromUsIsOutAndTooFarFromTheJobBlockIsOut() {
        List<Job> jobs = List.of(new Job(pos(60, 64, 0), VillageChests.GRINDSTONE));
        // 60 blocks away, the radius is 48
        assertNull(pick(List.of(pos(60, 64, 3)), jobs, NONE, NONE, ANY));
        // close to us but 8 blocks from the grindstone, the job radius is 6
        List<Job> near = List.of(new Job(pos(20, 64, 0), VillageChests.GRINDSTONE));
        assertNull(pick(List.of(pos(12, 64, 0)), near, NONE, NONE, ANY));
    }

    @Test
    public void triedChestsAndTheOtherHalfOfADoubleChestAreSkipped() {
        List<Job> jobs = List.of(new Job(pos(10, 64, 0), VillageChests.GRINDSTONE));
        List<RunState.Pos> tried = List.of(pos(10, 64, 3));
        assertNull(pick(List.of(pos(10, 64, 3)), jobs, NONE, tried, ANY));
        // the other half of the same double chest
        assertNull(pick(List.of(pos(11, 64, 3)), jobs, NONE, tried, ANY));
        // two blocks over is a different chest
        assertEquals(pos(12, 64, 3), pick(List.of(pos(12, 64, 3)), jobs, NONE, tried, ANY));
        // and the same x,z one floor up is not a double chest either
        assertEquals(pos(10, 65, 3), pick(List.of(pos(10, 65, 3)), jobs, NONE, tried, ANY));
    }

    @Test
    public void theWorldCheckOnlyRunsOnChestsThatAlreadyLookRight() {
        List<Job> jobs = List.of(new Job(pos(10, 64, 0), VillageChests.GRINDSTONE));
        List<RunState.Pos> asked = new ArrayList<>();
        // the first by rank/distance is under water (usable says no), the second is fine. the one with no job block is never asked
        List<RunState.Pos> chests = List.of(pos(10, 64, 2), pos(10, 64, 4), pos(-20, 64, 0));
        RunState.Pos found = pick(chests, jobs, NONE, NONE, p -> {
            asked.add(p);
            return p.z != 2;
        });
        assertEquals(pos(10, 64, 4), found);
        assertEquals(List.of(pos(10, 64, 2), pos(10, 64, 4)), asked);
    }

    @Test
    public void budgetEndsOnEitherLimit() {
        assertTrue(VillageChests.withinBudget(0, 0, 3, 240));
        assertTrue(VillageChests.withinBudget(2, 4799, 3, 240));
        // third chest used up
        assertFalse(VillageChests.withinBudget(3, 100, 3, 240));
        // 240 s = 4800 ticks
        assertFalse(VillageChests.withinBudget(1, 4800, 3, 240));
        // a zero budget turns it off
        assertFalse(VillageChests.withinBudget(0, 0, 0, 240));
        assertFalse(VillageChests.withinBudget(0, 0, 3, 0));
    }
}
