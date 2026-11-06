package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LavaSpotTest {
    @Test
    public void theGiveUpOrTwentySecondsOfHoldingArmsIt() {
        assertTrue(LavaSpot.trigger(true, 0));
        assertTrue(LavaSpot.trigger(false, LavaSpot.HOLD_TRIGGER_TICKS));
        assertFalse(LavaSpot.trigger(false, LavaSpot.HOLD_TRIGGER_TICKS - 1));
    }

    @Test
    public void endermenNearItAreSkippedUntilItExpires() {
        LavaSpot spot = new LavaSpot();
        assertFalse(spot.covers(0, 0, 0, 0));
        assertTrue(spot.arm(100, 60, 100, 1000));
        assertTrue(spot.covers(110, 60, 110, 1001));
        assertTrue(spot.covers(100 + LavaSpot.RADIUS, 60, 100, 1001));
        assertFalse(spot.covers(100 + LavaSpot.RADIUS + 1, 60, 100, 1001));
        long last = 1000 + LavaSpot.LIFETIME_TICKS - 1;
        assertTrue(spot.covers(100, 60, 100, last));
        assertFalse(spot.covers(100, 60, 100, last + 1));
    }

    @Test
    public void aSecondTriggerWhileLiveDoesNotMoveIt() {
        LavaSpot spot = new LavaSpot();
        assertTrue(spot.arm(0, 0, 0, 0));
        assertFalse(spot.arm(500, 0, 500, 10));
        assertFalse(spot.covers(500, 0, 500, 10));
        // after it ran out a new one can go up somewhere else
        assertTrue(spot.arm(500, 0, 500, LavaSpot.LIFETIME_TICKS));
        assertTrue(spot.covers(500, 0, 500, LavaSpot.LIFETIME_TICKS + 1));
        assertFalse(spot.covers(0, 0, 0, LavaSpot.LIFETIME_TICKS + 1));
    }

    @Test
    public void standingInsideALiveSpotRestartsItsClock() {
        LavaSpot spot = new LavaSpot();
        assertTrue(spot.arm(0, 0, 0, 0));
        long late = LavaSpot.LIFETIME_TICKS - 5;
        assertTrue(spot.arm(10, 0, 10, late));
        // the clock moved: it would have been over by now
        assertTrue(spot.active(LavaSpot.LIFETIME_TICKS + 100));
        assertFalse(spot.active(late + LavaSpot.LIFETIME_TICKS));
        // and it stayed where it was, the bot only had to be inside
        assertTrue(spot.covers(0, 0, 0, late + 1));
        assertFalse(spot.arm(500, 0, 500, late + 2));
    }

    @Test
    public void aWalkThroughTheSpotIsBlocked() {
        LavaSpot spot = new LavaSpot();
        spot.arm(0, 60, 0, 0);
        // us on one side, the enderman on the other, both well outside it
        assertTrue(spot.blocksWalk(-50, 0, 50, 0, 1));
        // grazing the edge counts, one block further off does not
        assertTrue(spot.blocksWalk(-50, LavaSpot.RADIUS, 50, LavaSpot.RADIUS, 1));
        assertFalse(spot.blocksWalk(-50, LavaSpot.RADIUS + 1, 50, LavaSpot.RADIUS + 1, 1));
    }

    @Test
    public void anEndermanOnOurSideOfTheSpotIsFine() {
        LavaSpot spot = new LavaSpot();
        spot.arm(0, 60, 0, 0);
        // the line carried on would hit it, the walk stops short
        assertFalse(spot.blocksWalk(-60, 0, -30, 0, 1));
        // and walking off away from it never gets close
        assertFalse(spot.blocksWalk(40, 0, 80, 0, 1));
    }

    @Test
    public void everyWalkFromInsideTheSpotIsBlocked() {
        LavaSpot spot = new LavaSpot();
        spot.arm(0, 60, 0, 0);
        assertTrue(spot.blocksWalk(10, 0, 80, 0, 1));
        // standing right on the enderman, no walk at all, the zero length line must not blow up
        assertTrue(spot.blocksWalk(10, 0, 10, 0, 1));
        assertFalse(spot.blocksWalk(80, 0, 80, 0, 1));
    }

    @Test
    public void aSpotThatRanOutBlocksNothing() {
        LavaSpot spot = new LavaSpot();
        assertFalse(spot.blocksWalk(-50, 0, 50, 0, 1));
        spot.arm(0, 60, 0, 0);
        assertTrue(spot.blocksWalk(-50, 0, 50, 0, LavaSpot.LIFETIME_TICKS - 1));
        assertFalse(spot.blocksWalk(-50, 0, 50, 0, LavaSpot.LIFETIME_TICKS));
    }

    @Test
    public void aClockThatWentBackwardsIsNotLive() {
        LavaSpot spot = new LavaSpot();
        spot.arm(0, 0, 0, 5000);
        assertFalse(spot.active(10));
    }
}
