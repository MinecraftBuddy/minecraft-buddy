package adris.altoclef.tasks.speedrun.gamer.tasks;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PearlRoamTest {
    @Test
    public void roamsOnlyWithAForestAndNothingToChase() {
        assertTrue(PearlRoam.wanted(true, false, -1, false, false, 0));
        assertFalse(PearlRoam.wanted(false, false, -1, false, false, 0));
        // the walk there gave up: the forest is not ours to roam
        assertFalse(PearlRoam.wanted(true, true, -1, false, false, 0));
        assertFalse(PearlRoam.wanted(true, false, 7, false, false, 0));
        assertFalse(PearlRoam.wanted(true, false, -1, true, false, 0));
        // a kill's loot is still due, swapping the loot task out would forget it
        assertFalse(PearlRoam.wanted(true, false, -1, false, true, 0));
    }

    @Test
    public void aQuietForestFallsBackToTheOldWander() {
        assertTrue(PearlRoam.wanted(true, false, -1, false, false, PearlRoam.IDLE_TICKS));
        assertFalse(PearlRoam.wanted(true, false, -1, false, false, PearlRoam.IDLE_TICKS + 1));
    }

    @Test
    public void theQuietClockCountsRoamTicksAndStartsOverOnASighting() {
        PearlRoam roam = new PearlRoam();
        for (int i = 0; i <= PearlRoam.IDLE_TICKS; i++) {
            roam.idleTick();
        }
        assertFalse(PearlRoam.wanted(true, false, -1, false, false, roam.idleTicks()));
        roam.sawEnderman();
        assertTrue(PearlRoam.wanted(true, false, -1, false, false, roam.idleTicks()));
    }

    @Test
    public void theLeashIsFlatFromTheForestAndNotUnderOurFeet() {
        // forest at 0 0, we stand at 0 0
        assertTrue(PearlRoam.inLeash(new BlockPos(30, 70, 0), 0, 0, 0.5, 0.5));
        assertTrue(PearlRoam.inLeash(new BlockPos(63, 10, 0), 0, 0, 0.5, 0.5));
        assertFalse(PearlRoam.inLeash(new BlockPos(70, 70, 0), 0, 0, 0.5, 0.5));
        // right next to us is no walk
        assertFalse(PearlRoam.inLeash(new BlockPos(3, 70, 0), 0, 0, 0.5, 0.5));
    }

    @Test
    public void aSpotNeedsRoomNoLavaAndNoDrop() {
        assertTrue(PearlRoam.spotOk(true, true, true, 60, 64, false));
        assertFalse(PearlRoam.spotOk(false, true, true, 60, 64, false));
        assertFalse(PearlRoam.spotOk(true, false, true, 60, 64, false));
        assertFalse(PearlRoam.spotOk(true, true, false, 60, 64, false));
        assertFalse(PearlRoam.spotOk(true, true, true, 60, 64, true));
        // 6 down is fine, 7 is a fall waiting to happen
        assertTrue(PearlRoam.spotOk(true, true, true, 58, 64, false));
        assertFalse(PearlRoam.spotOk(true, true, true, 57, 64, false));
        // up is always fine
        assertTrue(PearlRoam.spotOk(true, true, true, 90, 64, false));
    }

    @Test
    public void pickTakesOnlyLeashedSpotsThatPass() {
        BlockPos far = new BlockPos(200, 64, 0);
        BlockPos close = new BlockPos(2, 64, 0);
        BlockPos bad = new BlockPos(20, 64, 0);
        BlockPos good = new BlockPos(0, 64, 30);
        List<BlockPos> known = List.of(far, close, bad, good);
        for (int seed = 0; seed < 20; seed++) {
            BlockPos p = PearlRoam.pick(known, 0, 0, 0.5, 0.5, Set.of(), q -> !q.equals(bad), new Random(seed));
            assertEquals(good, p);
        }
    }

    @Test
    public void pickSkipsFailedSpotsAndGivesUpCleanly() {
        BlockPos good = new BlockPos(0, 64, 30);
        assertNull(PearlRoam.pick(List.of(good), 0, 0, 0.5, 0.5, Set.of(good), q -> true, new Random(1)));
        assertNull(PearlRoam.pick(List.of(good), 0, 0, 0.5, 0.5, Set.of(), q -> false, new Random(1)));
        assertNull(PearlRoam.pick(List.of(), 0, 0, 0.5, 0.5, Set.of(), q -> true, new Random(1)));
        assertNotNull(PearlRoam.pick(List.of(good), 0, 0, 0.5, 0.5, new HashSet<>(), q -> true, new Random(1)));
    }

    @Test
    public void theGoalIsLatchedUntilArrival() {
        PearlRoam roam = new PearlRoam();
        BlockPos goal = new BlockPos(0, 64, 30);
        roam.picked(goal);
        // walking closer a block at a time, nothing ends it
        for (double d = 30; d > PearlRoam.ARRIVE_BLOCKS + 1; d -= 0.5) {
            assertFalse(roam.step(d, () -> true));
            assertEquals(goal, roam.goal());
        }
        assertTrue(roam.step(PearlRoam.ARRIVE_BLOCKS, () -> true));
        assertNull(roam.goal());
        // arriving is not a failure, that spot may come up again
        assertTrue(roam.failed().isEmpty());
    }

    @Test
    public void aStalledGoalIsDroppedAndNotTriedAgain() {
        PearlRoam roam = new PearlRoam();
        BlockPos goal = new BlockPos(0, 64, 30);
        roam.picked(goal);
        boolean over = false;
        int ticks = 0;
        while (!over && ticks < PearlRoam.STALL_TICKS + 10) {
            // creeping a quarter block closer now and then is the path wobbling, not progress
            over = roam.step(20 - (ticks % 4) * 0.25, () -> true);
            ticks++;
        }
        assertTrue(over);
        // the first tick sets the best, then STALL_TICKS + 1 without progress
        assertEquals(PearlRoam.STALL_TICKS + 2, ticks);
        assertTrue(roam.failed().contains(goal));
    }

    @Test
    public void aSpotThatWentBadEndsTheGoalAtTheRecheck() {
        PearlRoam roam = new PearlRoam();
        roam.picked(new BlockPos(0, 64, 30));
        int ticks = 0;
        boolean over = false;
        while (!over) {
            over = roam.step(1000 - ticks * 3, () -> false);
            ticks++;
        }
        // only asked once a second, not every tick
        assertEquals(PearlRoam.RECHECK_TICKS, ticks);
        assertTrue(roam.failed().isEmpty());
    }

    @Test
    public void aFailedPickCoolsDownBeforeTheNextComb() {
        PearlRoam roam = new PearlRoam();
        assertTrue(roam.mayPick());
        roam.picked(null);
        for (int i = 0; i < PearlRoam.RETRY_TICKS; i++) {
            assertFalse(roam.mayPick());
        }
        assertTrue(roam.mayPick());
    }

    @Test
    public void comingBackFromAChaseStartsTheProgressMarkOver() {
        PearlRoam roam = new PearlRoam();
        roam.picked(new BlockPos(0, 64, 30));
        assertFalse(roam.step(10, () -> true));
        // the chase dragged us out to 40 off: without the reset this is 400 ticks of "no progress" from 10
        roam.resumed();
        assertFalse(roam.step(40, () -> true));
        for (int i = 0; i < PearlRoam.STALL_TICKS - 1; i++) {
            assertFalse(roam.step(39.5, () -> true));
        }
        assertFalse(roam.step(37, () -> true));
    }
}
