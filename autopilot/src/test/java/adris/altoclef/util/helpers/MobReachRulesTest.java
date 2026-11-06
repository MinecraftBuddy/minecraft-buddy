package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

// a voxel world of walls and doors and nothing else, enough to ask "are we in a box" without minecraft
public class MobReachRulesTest {

    private enum Cell { AIR, STONE, SLAB, DOOR }

    private static class Grid implements MobReachRules.Terrain {
        private final Map<Long, Cell> cells = new HashMap<>();

        private static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFF) | (((long) y & 0xFFFFF) << 20) | (((long) z & 0xFFFFF) << 40);
        }

        Grid set(int x, int y, int z, Cell c) {
            cells.put(key(x, y, z), c);
            return this;
        }

        Cell at(int x, int y, int z) {
            return cells.getOrDefault(key(x, y, z), Cell.AIR);
        }

        // doors collide in real life but zombies chew through them, so like the real terrain this reports them as open
        @Override
        public boolean wall(int x, int y, int z) {
            return at(x, y, z) == Cell.STONE;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            Cell c = at(x, y, z);
            return c == Cell.STONE || c == Cell.SLAB;
        }
    }

    // player feet at (0, 0, 0): ceiling at y=2, the four sides stone at both heights
    private static Grid box() {
        Grid g = new Grid();
        g.set(0, 2, 0, Cell.STONE);
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] s : sides) {
            g.set(s[0], 0, s[1], Cell.STONE);
            g.set(s[0], 1, s[1], Cell.STONE);
        }
        return g;
    }

    // ask about a mob every 10 ticks like the real thing does, returns what the last answer was
    private static boolean watch(MobReachRules.StallTracker t, int id, double distance, long from, long to) {
        boolean stalled = false;
        for (long tick = from; tick <= to; tick += 10) {
            stalled = t.update(id, distance, tick);
        }
        return stalled;
    }

    @Test
    public void sealedBoxIsEnclosed() {
        assertTrue(MobReachRules.isEnclosed(box(), 0, 0, 0));
    }

    @Test
    public void openRoofIsNotEnclosed() {
        Grid g = box().set(0, 2, 0, Cell.AIR);
        assertFalse(MobReachRules.isEnclosed(g, 0, 0, 0));
    }

    @Test
    public void openSideIsNotEnclosed() {
        Grid g = box().set(1, 0, 0, Cell.AIR).set(1, 1, 0, Cell.AIR);
        assertFalse(MobReachRules.isEnclosed(g, 0, 0, 0));
    }

    @Test
    public void oneHighGapAtHeadStillCounts() {
        // a mob is two blocks tall, a gap at head height with a block under it does not let it through
        Grid g = box().set(1, 1, 0, Cell.AIR);
        assertTrue(MobReachRules.isEnclosed(g, 0, 0, 0));
    }

    @Test
    public void oneHighGapAtFeetStillCounts() {
        Grid g = box().set(0, 0, 1, Cell.AIR);
        assertTrue(MobReachRules.isEnclosed(g, 0, 0, 0));
    }

    @Test
    public void twoHighGapIsAnExit() {
        Grid g = box().set(-1, 0, 0, Cell.AIR).set(-1, 1, 0, Cell.AIR);
        assertFalse(MobReachRules.isEnclosed(g, 0, 0, 0));
    }

    @Test
    public void doorsAreAnExitBecauseZombiesBreakThem() {
        Grid g = box().set(0, 0, -1, Cell.DOOR).set(0, 1, -1, Cell.DOOR);
        assertFalse(MobReachRules.isEnclosed(g, 0, 0, 0));
    }

    @Test
    public void lowSlabCeilingStillCountsAsACeiling() {
        Grid g = box().set(0, 2, 0, Cell.SLAB);
        assertTrue(MobReachRules.isEnclosed(g, 0, 0, 0));
    }

    @Test
    public void lowSlabOnTheFloorIsNotAWall() {
        // a slab at feet level is a step, a zombie hops it. so the side only stays shut if the head cell is blocked
        Grid g = box().set(1, 0, 0, Cell.SLAB).set(1, 1, 0, Cell.AIR);
        assertFalse(MobReachRules.isEnclosed(g, 0, 0, 0));
    }

    @Test
    public void farAboveAndCloseIsOut() {
        assertTrue(MobReachRules.isVerticallyOut(1, 4, 1));
        assertTrue(MobReachRules.isVerticallyOut(0, -5, 4));
    }

    @Test
    public void threeBlocksUpIsStillAClimb() {
        assertFalse(MobReachRules.isVerticallyOut(1, 3, 1));
        assertFalse(MobReachRules.isVerticallyOut(1, -3, 1));
    }

    @Test
    public void farAwayHorizontallyIsLeftToTheStallCheck() {
        assertFalse(MobReachRules.isVerticallyOut(10, 6, 0));
    }

    @Test
    public void mobThatNeverGetsCloserStalls() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        assertFalse(watch(t, 1, 8.0, 100, 130));
        assertTrue(watch(t, 1, 7.6, 140, 140));
    }

    @Test
    public void mobThatKeepsApproachingNeverStalls() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        double d = 12;
        for (long tick = 0; tick < 400; tick += 10) {
            assertFalse(t.update(1, d, tick));
            d -= 1.5;
            if (d < 2) d = 12;
        }
    }

    @Test
    public void gettingCloserRestartsTheClock() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        watch(t, 1, 8.0, 0, 30);
        assertFalse(watch(t, 1, 6.5, 40, 70));
        assertTrue(watch(t, 1, 6.5, 80, 80));
    }

    @Test
    public void stuckMobStaysStalledUntilItMovesAgain() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        assertTrue(watch(t, 1, 8.0, 0, 60));
        assertFalse(t.update(1, 6.0, 70));
    }

    @Test
    public void walkingAwayIsNotStuck() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        watch(t, 1, 5.0, 0, 20);
        assertFalse(watch(t, 1, 9.0, 30, 60));
        assertTrue(watch(t, 1, 9.0, 70, 70));
    }

    @Test
    public void notBeingWatchedForAWhileForgetsTheClock() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        t.update(1, 8.0, 0);
        // we were off eating for 3 seconds, that is not the mob's fault
        assertFalse(t.update(1, 8.0, 60));
    }

    @Test
    public void mobsAreTrackedSeparately() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        for (long tick = 0; tick <= 40; tick += 10) {
            t.update(1, 8.0, tick);
            t.update(2, 8.0 - tick / 10.0 * 1.5, tick);
        }
        assertTrue(t.update(1, 8.0, 50));
        assertFalse(t.update(2, 1.0, 50));
    }

    @Test
    public void pruneDropsOldIds() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        t.update(1, 8.0, 0);
        t.update(2, 8.0, 500);
        t.prune(500);
        assertEquals(1, t.size());
    }

    // ---- in contact range is never stuck

    @Test
    public void aMobInContactRangeIsNeverStalledNoMatterHowLong() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        // the zombie behind us while we back off: the gap never closes, and it does not have to
        assertFalse(watch(t, 1, 2.5, 0, 400));
        assertFalse(watch(t, 2, MobReachRules.STALL_EXEMPT_RANGE, 0, 400));
    }

    @Test
    public void justOutsideContactRangeStillStalls() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        assertTrue(watch(t, 1, MobReachRules.STALL_EXEMPT_RANGE + 0.5, 0, 100));
    }

    @Test
    public void theContactRangeIsTheOneTheFightCountsContactBy() {
        assertEquals(CombatRules.CONTACT_RANGE, MobReachRules.STALL_EXEMPT_RANGE, 0);
    }

    @Test
    public void leavingContactRangeStartsTheClockFromScratch() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        // on top of us for a long while, then we get a few blocks of room
        watch(t, 1, 2.0, 0, 200);
        // one sample out there is not 40 ticks of being stuck
        assertFalse(t.update(1, 4.5, 210));
        assertFalse(watch(t, 1, 4.5, 220, 240));
        assertTrue(watch(t, 1, 4.5, 250, 250));
    }

    @Test
    public void aStalledMobWalkingUpToUsIsNotStalledAnyMore() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        assertTrue(watch(t, 1, 8.0, 0, 60));
        assertFalse(t.update(1, 2.8, 70));
        assertFalse(t.update(1, 2.8, 80));
    }

    @Test
    public void aStalledVerdictDoesNotOutliveTheMobArriving() {
        MobReachRules.Verdict stalled = MobReachRules.Verdict.STALLED;
        assertTrue(MobReachRules.verdictHolds(stalled, MobReachRules.STALL_EXEMPT_RANGE + 0.5));
        assertFalse(MobReachRules.verdictHolds(stalled, MobReachRules.STALL_EXEMPT_RANGE));
        assertFalse(MobReachRules.verdictHolds(stalled, 1));
    }

    @Test
    public void everyOtherVerdictHoldsAtAnyRange() {
        // a wall is a wall at two blocks, and an enclosure is ours whatever is outside it
        for (MobReachRules.Verdict v : new MobReachRules.Verdict[]{MobReachRules.Verdict.REACHABLE, MobReachRules.Verdict.ENCLOSED,
                MobReachRules.Verdict.VERTICAL_GAP}) {
            assertTrue(v.name(), MobReachRules.verdictHolds(v, 1));
            assertTrue(v.name(), MobReachRules.verdictHolds(v, 20));
        }
    }

    // ---- running from it: a mob that keeps pace is the one we are running from

    private static boolean watchRunning(MobReachRules.StallTracker t, int id, double distance, long from, long to, boolean fleeing) {
        boolean stalled = false;
        for (long tick = from; tick <= to; tick += 10) {
            stalled = t.update(id, distance, tick, fleeing);
        }
        return stalled;
    }

    @Test
    public void whileRunningAMobThatKeepsPaceIsNeverStalled() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        // five blocks back and not a block closer in ten seconds, because we are the ones moving
        assertFalse(watchRunning(t, 1, 5.0, 0, 400, true));
        assertFalse(watchRunning(t, 2, MobReachRules.FLEEING_STALL_RANGE, 0, 400, true));
    }

    @Test
    public void theSameMobNotWhileRunningStillStallsInFortyTicks() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        assertTrue(watchRunning(t, 1, 5.0, 0, 60, false));
    }

    @Test
    public void pastTheRunningRangeTheOldRuleStandsEvenWhileRunning() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        assertTrue(watchRunning(t, 1, MobReachRules.FLEEING_STALL_RANGE + 0.5, 0, 60, true));
    }

    @Test
    public void theRunEndingStartsTheClockFromScratch() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        watchRunning(t, 1, 5.0, 0, 200, true);
        // run over, mob still there. it was not stuck while we ran, so its forty ticks count from where the run ended
        assertFalse(t.update(1, 5.0, 210, false));
        assertFalse(watchRunning(t, 1, 5.0, 220, 230, false));
        assertTrue(watchRunning(t, 1, 5.0, 240, 240, false));
    }

    @Test
    public void theRunningRangeIsTheLowHpRange() {
        assertEquals(CombatRules.LOW_HP_RANGE, MobReachRules.FLEEING_STALL_RANGE, 0);
        assertEquals(MobReachRules.STALL_EXEMPT_RANGE, MobReachRules.stallExemptRange(false), 0);
        assertEquals(MobReachRules.FLEEING_STALL_RANGE, MobReachRules.stallExemptRange(true), 0);
        // running never narrows it
        assertTrue(MobReachRules.stallExemptRange(true) >= MobReachRules.stallExemptRange(false));
    }

    @Test
    public void aCachedStalledVerdictStopsHoldingOnAMobOnOurHeelsWhileRunning() {
        MobReachRules.Verdict stalled = MobReachRules.Verdict.STALLED;
        assertFalse(MobReachRules.verdictHolds(stalled, 6, true));
        assertTrue(MobReachRules.verdictHolds(stalled, 6, false));
        assertTrue(MobReachRules.verdictHolds(stalled, MobReachRules.FLEEING_STALL_RANGE + 0.5, true));
        // the other verdicts do not care whether we are running
        assertTrue(MobReachRules.verdictHolds(MobReachRules.Verdict.ENCLOSED, 2, true));
        assertTrue(MobReachRules.verdictHolds(MobReachRules.Verdict.VERTICAL_GAP, 2, true));
    }

    // the one that lit at two blocks was written off as stuck a block earlier
    @Test
    public void aCreeperInsideTheStepTriggerIsNeverStalled() {
        MobReachRules.StallTracker t = new MobReachRules.StallTracker();
        for (long tick = 0; tick < 200; tick++) {
            assertFalse(t.update(1, CreeperStep.TRIGGER, tick, false, true));
        }
        // a zombie at the same spot is stuck as ever, and a creeper past the trigger is still the stall check's
        MobReachRules.StallTracker other = new MobReachRules.StallTracker();
        boolean zombie = false;
        boolean far = false;
        MobReachRules.StallTracker farTracker = new MobReachRules.StallTracker();
        for (long tick = 0; tick < 200; tick++) {
            zombie = other.update(2, CreeperStep.TRIGGER, tick, false, false);
            far = farTracker.update(3, CreeperStep.TRIGGER + 1, tick, false, true);
        }
        assertTrue(zombie);
        assertTrue(far);
        assertFalse(MobReachRules.verdictHolds(MobReachRules.Verdict.STALLED, CreeperStep.TRIGGER, false, true));
        assertTrue(MobReachRules.verdictHolds(MobReachRules.Verdict.STALLED, CreeperStep.TRIGGER, false, false));
    }
}
