package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StandCapTest {

    private static void stand(StandCap cap, int ticks) {
        for (int i = 0; i < ticks; i++) cap.tick(true, false);
    }

    @Test
    public void countsTicksStoodInReachAndTripsAtTheWaitCap() {
        StandCap cap = new StandCap();
        stand(cap, ApproachRules.WAIT_TICKS - 1);
        assertEquals(false, cap.waitedLong());
        stand(cap, 1);
        assertEquals(true, cap.waitedLong());
    }

    @Test
    public void aSwingTheMobLeavingReachOrANewTargetStartsItOver() {
        StandCap cap = new StandCap();
        stand(cap, ApproachRules.WAIT_TICKS);
        cap.tick(true, true);
        assertEquals(false, cap.waitedLong());
        stand(cap, ApproachRules.WAIT_TICKS);
        cap.tick(false, false);
        assertEquals(false, cap.waitedLong());
        stand(cap, ApproachRules.WAIT_TICKS);
        cap.reset();
        assertEquals(false, cap.waitedLong());
    }

    @Test
    public void aForcedSwingKeepsItInStallModeSoTheNextOneIsSoon() {
        StandCap cap = new StandCap();
        stand(cap, ApproachRules.WAIT_TICKS);
        cap.forced();
        cap.tick(true, true);
        assertEquals(false, cap.waitedLong());
        stand(cap, StandCap.REPEAT_TICKS - 1);
        assertEquals(false, cap.waitedLong());
        stand(cap, 1);
        assertEquals(true, cap.waitedLong());
    }

    @Test
    public void anOrdinarySwingEndsStallMode() {
        StandCap cap = new StandCap();
        stand(cap, ApproachRules.WAIT_TICKS);
        cap.forced();
        cap.tick(true, true);
        // the gate opened and a normal swing went out: back to the full wait
        stand(cap, 10);
        cap.tick(true, true);
        stand(cap, ApproachRules.WAIT_TICKS - 1);
        assertEquals(false, cap.waitedLong());
    }

    @Test
    public void aForcedSwingThatDidNotGetOutKeepsAskingEveryTick() {
        StandCap cap = new StandCap();
        stand(cap, ApproachRules.WAIT_TICKS);
        for (int i = 0; i < 10; i++) {
            cap.forced();
            cap.tick(true, false);
            assertEquals(true, cap.waitedLong());
        }
    }

    @Test
    public void noShotRightOnTopCountsAsStandingButNoShotFarAwayDoesNot() {
        assertTrue(StandCap.standing(true, true, false));
        assertTrue(StandCap.standing(true, false, true));
        assertFalse(StandCap.standing(true, false, false));
        assertFalse(StandCap.standing(false, false, true));
        StandCap cap = new StandCap();
        for (int i = 0; i < ApproachRules.WAIT_TICKS; i++) cap.tick(StandCap.standing(true, false, true), false);
        assertTrue(cap.waitedLong());
    }
}
