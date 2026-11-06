package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.BucketFillRules.Fill;
import adris.altoclef.util.helpers.BucketFillRules.Hold;
import org.junit.Test;

public class BucketFillRulesTest {

    @Test
    public void aFreeSlotMeansNothingToMake() {
        assertFalse(BucketFillRules.needsFreeSlot(new int[]{5, 0, 0}, true));
        assertFalse(BucketFillRules.needsFreeSlot(new int[]{1}, true));
    }

    @Test
    public void aLoneBucketIsSwappedInPlaceEvenInAFullBag() {
        assertFalse(BucketFillRules.needsFreeSlot(new int[]{0, 1, 0}, false));
        // two stacks of one each: still no new slot, whichever one gets picked
        assertFalse(BucketFillRules.needsFreeSlot(new int[]{1, 0, 1}, false));
    }

    @Test
    public void aStackOfTwoInAFullBagNeedsARoom() {
        assertTrue(BucketFillRules.needsFreeSlot(new int[]{2}, false));
        assertTrue(BucketFillRules.needsFreeSlot(new int[]{0, 16, 0}, false));
        // forceEquipItem takes the first stack it finds, so a big one anywhere is enough
        assertTrue(BucketFillRules.needsFreeSlot(new int[]{1, 3}, false));
    }

    @Test
    public void noBucketsAtAllNeedsNothing() {
        assertFalse(BucketFillRules.needsFreeSlot(new int[]{0, 0, 0}, false));
        assertFalse(BucketFillRules.needsFreeSlot(new int[]{}, false));
    }

    @Test
    public void planGoesStraightToTheFillWhenNothingIsNeeded() {
        assertEquals(Fill.GO, BucketFillRules.plan(new int[]{1}, false, false, true));
        assertEquals(Fill.GO, BucketFillRules.plan(new int[]{5}, true, false, true));
        // stuck or not does not matter without a need
        assertEquals(Fill.GO, BucketFillRules.plan(new int[]{1}, false, true, true));
    }

    @Test
    public void planMakesRoomFirstForBothLiquids() {
        assertEquals(Fill.MAKE_ROOM, BucketFillRules.plan(new int[]{3}, false, false, true));
        assertEquals(Fill.MAKE_ROOM, BucketFillRules.plan(new int[]{3}, false, false, false));
    }

    @Test
    public void stuckWithNothingToThrowHoldsLavaButFillsWater() {
        assertEquals(Fill.HOLD, BucketFillRules.plan(new int[]{3}, false, true, true));
        // a thrown water bucket is picked up again, a thrown lava bucket is not
        assertEquals(Fill.GO, BucketFillRules.plan(new int[]{3}, false, true, false));
    }

    @Test
    public void aFreedSlotLiftsTheHold() {
        assertEquals(Fill.HOLD, BucketFillRules.plan(new int[]{3}, false, true, true));
        assertEquals(Fill.GO, BucketFillRules.plan(new int[]{3}, true, true, true));
    }

    @Test
    public void aHoldJustStartedKeepsHolding() {
        // nothing is dropped, and a lone bucket is not even looked at, before the wait is up
        assertEquals(Hold.WAIT, BucketFillRules.holdStep(5, 0));
        assertEquals(Hold.WAIT, BucketFillRules.holdStep(5, BucketFillRules.HOLD_DROP_TICKS - 1));
        assertEquals(Hold.WAIT, BucketFillRules.holdStep(1, BucketFillRules.HOLD_DROP_TICKS - 1));
    }

    @Test
    public void afterTheWaitTheSparesGo() {
        assertEquals(Hold.DROP_SPARES, BucketFillRules.holdStep(2, BucketFillRules.HOLD_DROP_TICKS));
        assertEquals(Hold.DROP_SPARES, BucketFillRules.holdStep(16, BucketFillRules.HOLD_DROP_TICKS + 500));
    }

    @Test
    public void aLoneBucketInHandFillsInPlace() {
        assertEquals(Hold.FILL_IN_PLACE, BucketFillRules.holdStep(1, BucketFillRules.HOLD_DROP_TICKS));
    }

    @Test
    public void noBucketInHandJustWaits() {
        assertEquals(Hold.WAIT, BucketFillRules.holdStep(0, BucketFillRules.HOLD_DROP_TICKS + 1));
    }


    @Test
    public void sparesAreEverythingButTheLastBucket() {
        for (int held = 2; held <= 16; held++) {
            assertEquals(held - 1, BucketFillRules.spares(held));
            // throwing them all in one go leaves a lone bucket, which then fills in place
            assertEquals(Hold.FILL_IN_PLACE, BucketFillRules.holdStep(held - BucketFillRules.spares(held), 1000));
        }
    }

    @Test
    public void thereIsNothingToSpareFromALoneBucketOrNone() {
        assertEquals(0, BucketFillRules.spares(1));
        assertEquals(0, BucketFillRules.spares(0));
        assertEquals(0, BucketFillRules.spares(-3));
    }

    // same bookkeeping the bucket task does each tick MAKE_ROOM shows up
    private static long since;
    private static long last;

    private static boolean makeRoomAt(long now) {
        if (last < 0 || BucketFillRules.roomStretchBroke(last, now)) {
            since = now;
        }
        last = now;
        return BucketFillRules.roomTimedOut(since, now);
    }

    @Test
    public void aSteadyStretchTimesOutAtTheCap() {
        since = -1;
        last = -1;
        for (long t = 0; t < BucketFillRules.ROOM_GIVE_UP_TICKS; t++) {
            assertFalse(makeRoomAt(t));
        }
        assertTrue(makeRoomAt(BucketFillRules.ROOM_GIVE_UP_TICKS));
    }

    @Test
    public void briefFlickersDoNotRestartTheClock() {
        since = -1;
        last = -1;
        boolean out = false;
        for (long t = 0; t <= BucketFillRules.ROOM_GIVE_UP_TICKS; t++) {
            // two GO ticks out of every 40
            if (t % 40 == 10 || t % 40 == 11) continue;
            out = makeRoomAt(t);
        }
        assertTrue(out);
    }

    @Test
    public void aLongGapRestartsTheClock() {
        since = -1;
        last = -1;
        for (long t = 0; t < 150; t++) {
            makeRoomAt(t);
        }
        // 40 ticks of nothing, then it is back
        for (long t = 190; t < 190 + BucketFillRules.ROOM_GIVE_UP_TICKS; t++) {
            assertFalse(makeRoomAt(t));
        }
        assertTrue(makeRoomAt(190 + BucketFillRules.ROOM_GIVE_UP_TICKS));
    }
}
