package adris.altoclef.util.helpers;

import static adris.altoclef.util.helpers.BreathDodge.Move;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the breath dodge pick and the dunk water pickup caps
public class BreathDodgeTest {

    @Test
    public void outOfTheCloudOrInTheAirDoesNothing() {
        assertEquals(Move.NONE, BreathDodge.choose(false, true, true, true));
        assertEquals(Move.NONE, BreathDodge.choose(true, false, false, true));
    }

    @Test
    public void groundRuns() {
        assertEquals(Move.RUN, BreathDodge.choose(true, true, true, false));
        assertEquals(Move.RUN, BreathDodge.choose(true, true, true, true));
    }

    @Test
    public void pillarTopDropsWithABucketAndDigsWithout() {
        assertEquals(Move.DROP, BreathDodge.choose(true, true, false, true));
        assertEquals(Move.DIG, BreathDodge.choose(true, true, false, false));
    }

    @Test
    public void aRunStuckInTheCloudDropsWithWaterAndDigsWithout() {
        assertEquals(Move.RUN, BreathDodge.escalate(Move.RUN, false, true, true, false));
        assertEquals(Move.DROP, BreathDodge.escalate(Move.RUN, true, true, true, false));
        assertEquals(Move.DIG, BreathDodge.escalate(Move.RUN, true, false, false, false));
    }

    @Test
    public void neverDigsObsidianKeepsRunningWithoutWater() {
        assertEquals(Move.RUN, BreathDodge.escalate(Move.RUN, true, false, false, true));
        assertEquals(Move.RUN, BreathDodge.escalate(Move.DIG, true, false, false, true));
        assertEquals(Move.DROP, BreathDodge.escalate(Move.DIG, true, true, true, true));
        assertEquals(Move.DROP, BreathDodge.escalate(Move.RUN, true, true, true, true));
    }

    @Test
    public void otherMovesPassThrough() {
        assertEquals(Move.NONE, BreathDodge.escalate(Move.NONE, true, true, true, true));
        assertEquals(Move.DROP, BreathDodge.escalate(Move.DROP, true, false, false, true));
        assertEquals(Move.DIG, BreathDodge.escalate(Move.DIG, true, false, false, false));
    }

    @Test
    public void aRunOutOfTheCloudKeepsGoingWhileTheCloudIsClose() {
        assertEquals(Move.RUN, BreathDodge.follow(Move.NONE, Move.RUN, true, 1, true, 0));
        // really clear
        assertEquals(Move.NONE, BreathDodge.follow(Move.NONE, Move.RUN, false, 1, true, 0));
        // the cap, a wall of clouds can't hold us forever
        assertEquals(Move.RUN, BreathDodge.follow(Move.NONE, Move.RUN, true, BreathDodge.MAX_CLEAR_TICKS - 1, true, 0));
        assertEquals(Move.NONE, BreathDodge.follow(Move.NONE, Move.RUN, true, BreathDodge.MAX_CLEAR_TICKS, true, 0));
    }

    @Test
    public void onlyARunIsKeptAndOnlyWhenNothingElseWasPicked() {
        assertEquals(Move.NONE, BreathDodge.follow(Move.NONE, Move.NONE, true, 1, true, 0));
        assertEquals(Move.NONE, BreathDodge.follow(Move.NONE, Move.DROP, true, 1, true, 0));
        assertEquals(Move.NONE, BreathDodge.follow(Move.NONE, Move.DIG, true, 1, true, 0));
        assertEquals(Move.DROP, BreathDodge.follow(Move.DROP, Move.RUN, true, 1, true, 0));
    }

    @Test
    public void aHopIsStillTheRunButAFallIsMlgs() {
        assertEquals(Move.RUN, BreathDodge.follow(Move.NONE, Move.RUN, true, 1, false, 0.4));
        assertEquals(Move.RUN, BreathDodge.follow(Move.NONE, Move.RUN, true, 1, false, BreathDodge.FALL_SPEED));
        assertEquals(Move.NONE, BreathDodge.follow(Move.NONE, Move.RUN, true, 1, false, BreathDodge.FALL_SPEED - 0.1));
    }

    @Test
    public void keepRunningHoldsTillClearAndThatsAll() {
        assertTrue(BreathDodge.keepRunning(true, false, true, 0, true, 0));
        // one tick out of the box, cloud right there: still going
        assertTrue(BreathDodge.keepRunning(false, true, true, 1, true, 0));
        // on a hop too, that's where the flapping came from
        assertTrue(BreathDodge.keepRunning(false, true, true, 1, false, 0.4));
        assertFalse(BreathDodge.keepRunning(false, true, false, 1, true, 0));
        assertFalse(BreathDodge.keepRunning(false, true, true, BreathDodge.MAX_CLEAR_TICKS, true, 0));
        assertFalse(BreathDodge.keepRunning(false, true, true, 1, false, BreathDodge.FALL_SPEED - 0.1));
        // near a cloud with no run on: nothing starts one
        assertFalse(BreathDodge.keepRunning(false, false, true, 1, true, 0));
    }

    // the dodge and the task that wants us back in the cloud, a cloud over cells -2..2 and a task walking to 0. the dodge walks
    // out, the task walks back, this counts how many times the dodge had to start over. the chain's order, in small
    private static int runStarts(boolean follow, int ticks, int hopEvery) {
        double x = 0;
        Move last = Move.NONE;
        int out = 0;
        int starts = 0;
        for (int t = 0; t < ticks; t++) {
            boolean inCloud = Math.abs(Math.floor(x)) <= 2;
            boolean grounded = hopEvery == 0 || t % hopEvery != 0;
            Move move = BreathDodge.choose(inCloud, grounded, true, false);
            out = move == Move.NONE ? out + 1 : 0;
            if (follow) {
                move = BreathDodge.follow(move, last, Math.abs(x) < 2 + BreathDodge.CLEAR_BLOCKS, out, grounded, 0.2);
            }
            if (move == Move.RUN && last != Move.RUN) starts++;
            last = move;
            x += move == Move.RUN ? 0.2 : -0.2;
        }
        return starts;
    }

    @Test
    public void theRunAndTheWalkDoNotTakeTurnsEveryTick() {
        assertTrue(runStarts(false, 60, 0) >= 10);
        assertTrue(runStarts(true, 60, 0) <= 3);
    }

    @Test
    public void hopsDoNotRestartTheRun() {
        assertTrue(runStarts(false, 20, 3) > 3);
        assertEquals(1, runStarts(true, 20, 3));
    }

    @Test
    public void endPickupNeedsReachAndGivesUpFast() {
        assertFalse(BreathDodge.pickupExpired(true, 1, 3));
        assertTrue(BreathDodge.pickupExpired(true, 1, 6));
        assertTrue(BreathDodge.pickupExpired(true, 3.5, 3));
    }

    @Test
    public void elsewhereTheWaterIsFetchedFromFarForTenSeconds() {
        assertFalse(BreathDodge.pickupExpired(false, 9, 30));
        assertTrue(BreathDodge.pickupExpired(false, 10.5, 1));
    }

    // one water bucket is the fall bucket, a breath drop only bets a second one
    @Test
    public void theLastWaterBucketIsNeverDroppedFor() {
        assertFalse(BreathDodge.spareWater(0));
        assertFalse(BreathDodge.spareWater(1));
        assertTrue(BreathDodge.spareWater(2));
        assertEquals(Move.DIG, BreathDodge.choose(true, true, false, BreathDodge.spareWater(1)));
        assertEquals(Move.DROP, BreathDodge.choose(true, true, false, BreathDodge.spareWater(2)));
        assertEquals(Move.DROP, BreathDodge.escalate(Move.DIG, true, BreathDodge.spareWater(1), true, true));
        // a hard top with no water at all: nothing better than running
        assertEquals(Move.RUN, BreathDodge.escalate(Move.DIG, true, BreathDodge.spareWater(0), false, true));
        assertEquals(Move.DROP, BreathDodge.escalate(Move.RUN, true, BreathDodge.spareWater(1), true, true));
    }

    @Test
    public void theLastBucketInTheEndIsWorthAShortWalk() {
        assertFalse(BreathDodge.pickupExpired(true, true, 5, 10));
        assertTrue(BreathDodge.pickupExpired(true, true, 5, 13));
        assertTrue(BreathDodge.pickupExpired(true, true, 10.5, 2));
        // with another water bucket in the bag it is the old click-from-here rule
        assertTrue(BreathDodge.pickupExpired(true, false, 1, 6));
    }
}
