package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the lava hop latch: flicker ticks keep it, dry ticks release it, 50 ticks and it is done for good
public class HopLatchTest {

    @Test
    public void beginOnlyTakesTheLatchOnce() {
        HopLatch l = new HopLatch();
        assertTrue(l.begin());
        assertTrue(l.active());
        assertFalse(l.begin());
    }

    @Test
    public void flickerTicksKeepTheLatch() {
        HopLatch l = new HopLatch();
        l.begin();
        // bob out for a few ticks at a time, never ten in a row
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < 5; i++) {
                l.tick(true);
            }
            l.tick(false);
            assertTrue(l.active());
        }
    }

    @Test
    public void tenClearTicksRelease() {
        HopLatch l = new HopLatch();
        l.begin();
        for (int i = 0; i < HopLatch.CLEAR_TICKS - 1; i++) {
            l.tick(true);
            assertTrue(l.active());
        }
        l.tick(true);
        assertFalse(l.active());
        assertFalse(l.spent());
        // a clean escape can hop again later
        assertTrue(l.begin());
    }

    @Test
    public void fiftyTicksGiveUpForGood() {
        HopLatch l = new HopLatch();
        l.begin();
        for (int i = 0; i < HopLatch.GIVE_UP_TICKS - 1; i++) {
            l.tick(false);
            assertTrue(l.active());
        }
        l.tick(false);
        assertFalse(l.active());
        assertTrue(l.spent());
        assertFalse(l.begin());
        assertFalse(l.active());
    }

    @Test
    public void rearmLetsASpentLatchHopAgain() {
        HopLatch l = new HopLatch();
        l.begin();
        for (int i = 0; i < HopLatch.GIVE_UP_TICKS; i++) {
            l.tick(false);
        }
        assertTrue(l.spent());
        l.rearm();
        assertFalse(l.spent());
        assertTrue(l.begin());
    }

    @Test
    public void inTheLavaItSwims() {
        HopLatch l = new HopLatch();
        l.begin();
        assertEquals(HopLatch.Keys.SWIM, l.step(true, false, true));
        // a lava floor under our feet is still lava
        assertEquals(HopLatch.Keys.SWIM, l.step(true, true, false));
    }

    @Test
    public void onTheLipItOnlyWalks() {
        HopLatch l = new HopLatch();
        l.begin();
        l.step(true, false, true);
        assertEquals(HopLatch.Keys.WALK_ON, l.step(false, true, true));
        assertTrue(l.active());
    }

    @Test
    public void dryUnderOurMiddleEndsItWithNoCounting() {
        HopLatch l = new HopLatch();
        l.begin();
        l.step(false, true, true);
        assertEquals(HopLatch.Keys.NONE, l.step(false, true, false));
        assertFalse(l.active());
        assertFalse(l.spent());
        assertTrue(l.begin());
    }

    @Test
    public void landingDryStraightAwayEndsItToo() {
        HopLatch l = new HopLatch();
        l.begin();
        assertEquals(HopLatch.Keys.NONE, l.step(false, true, false));
        assertFalse(l.active());
    }

    @Test
    public void aGroundBlinkOnTheLipDoesNotPressJump() {
        HopLatch l = new HopLatch();
        l.begin();
        l.step(false, true, true);
        assertEquals(HopLatch.Keys.WALK_ON, l.step(false, false, true));
        assertEquals(HopLatch.Keys.WALK_ON, l.step(false, true, true));
    }

    @Test
    public void walkingOffTheLipOverDryGroundEndsIt() {
        HopLatch l = new HopLatch();
        l.begin();
        l.step(false, true, true);
        assertEquals(HopLatch.Keys.NONE, l.step(false, false, false));
    }

    @Test
    public void backInTheLavaItSwimsAgain() {
        HopLatch l = new HopLatch();
        l.begin();
        l.step(false, true, true);
        assertEquals(HopLatch.Keys.SWIM, l.step(true, false, true));
        // and the old landing doesn't count for the next tick out
        assertEquals(HopLatch.Keys.SWIM, l.step(false, false, true));
    }

    @Test
    public void inTheAirOverDryLandItKeepsSwimmingUntilTheClearTicks() {
        HopLatch l = new HopLatch();
        l.begin();
        for (int i = 0; i < HopLatch.CLEAR_TICKS - 1; i++) {
            assertEquals(HopLatch.Keys.SWIM, l.step(false, false, false));
        }
        assertEquals(HopLatch.Keys.NONE, l.step(false, false, false));
        assertFalse(l.spent());
    }

    @Test
    public void wedgedOnALipStillGivesUp() {
        HopLatch l = new HopLatch();
        l.begin();
        for (int i = 0; i < HopLatch.GIVE_UP_TICKS - 1; i++) {
            assertEquals(HopLatch.Keys.WALK_ON, l.step(false, true, true));
        }
        assertEquals(HopLatch.Keys.NONE, l.step(false, true, true));
        assertTrue(l.spent());
    }

    @Test
    public void aNewHopForgetsTheOldLanding() {
        HopLatch l = new HopLatch();
        l.begin();
        for (int i = 0; i < HopLatch.GIVE_UP_TICKS; i++) {
            l.step(false, true, true);
        }
        l.rearm();
        l.begin();
        assertEquals(HopLatch.Keys.SWIM, l.step(false, false, true));
    }

    @Test
    public void anIdleLatchHoldsNothing() {
        assertEquals(HopLatch.Keys.NONE, new HopLatch().step(true, false, true));
    }

    @Test
    public void withNoHopTheKeysAreForBeingInItOrFallingBackIn() {
        assertTrue(HopLatch.swimKeys(true, true, false));
        assertTrue(HopLatch.swimKeys(true, false, true));
        assertTrue(HopLatch.swimKeys(false, true, false));
        // the lip, with lava under our middle
        assertFalse(HopLatch.swimKeys(false, true, true));
        assertFalse(HopLatch.swimKeys(false, false, true));
        assertFalse(HopLatch.swimKeys(false, false, false));
    }

    @Test
    public void ticksWhileIdleDoNothing() {
        HopLatch l = new HopLatch();
        for (int i = 0; i < 100; i++) {
            l.tick(false);
        }
        assertFalse(l.spent());
        assertTrue(l.begin());
    }
}
