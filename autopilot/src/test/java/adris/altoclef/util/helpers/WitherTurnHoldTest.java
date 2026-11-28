package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.WitherTurnHold.End;
import org.junit.Test;

public class WitherTurnHoldTest {

    @Test
    public void wantsASkeletonInReachOnOurFloor() {
        assertTrue(WitherTurnHold.wants(2.0, 0.0, 12f, false, false, false));
        assertFalse("out of reach", WitherTurnHold.wants(3.0, 0.0, 12f, false, false, false));
        assertFalse("a floor up", WitherTurnHold.wants(2.0, 2.0, 12f, false, false, false));
        assertFalse("at 5 hp the run goes at once", WitherTurnHold.wants(2.0, 0.0, 5f, false, false, false));
        assertFalse("the rim has its own guard", WitherTurnHold.wants(2.0, 0.0, 12f, true, false, false));
        assertFalse("in lava", WitherTurnHold.wants(2.0, 0.0, 12f, false, true, false));
        assertFalse("another one behind", WitherTurnHold.wants(2.0, 0.0, 12f, false, false, true));
    }

    @Test
    public void aSwingOnTheRaisedShieldReleasesTheRun() {
        WitherTurnHold h = new WitherTurnHold();
        h.begin(7, false);
        assertEquals(7, h.holding());
        // raising: not blocking yet
        for (int i = 0; i < 4; i++) assertEquals(End.NONE, h.step(true, true, 2.0, 12f, false, false, false, false));
        assertEquals(End.NONE, h.step(true, true, 2.0, 12f, false, false, true, false));
        // its swing, the shield blocking: the run goes
        assertEquals(End.BLOCKED, h.step(true, true, 2.0, 12f, false, false, true, true));
    }

    @Test
    public void aSwingBeforeTheBlockReleasesTheRunToo() {
        WitherTurnHold h = new WitherTurnHold();
        h.begin(7, false);
        assertEquals(End.NONE, h.step(true, true, 2.0, 12f, false, false, false, false));
        // the blow lands while the shield is still rising: the next one is a cycle away all the same
        assertEquals(End.SWUNG, h.step(true, true, 2.0, 12f, false, false, false, true));
    }

    @Test
    public void whyNotNamesTheReason() {
        assertEquals(null, WitherTurnHold.whyNot(2.0, 0.0, 12f, false, false, false));
        assertEquals("out of reach", WitherTurnHold.whyNot(3.0, 0.0, 12f, false, false, false));
        assertEquals("not on our floor", WitherTurnHold.whyNot(2.0, 2.0, 12f, false, false, false));
        assertEquals("hp 5", WitherTurnHold.whyNot(2.0, 0.0, 5f, false, false, false));
        assertEquals("another one behind", WitherTurnHold.whyNot(2.0, 0.0, 12f, false, false, true));
    }

    @Test
    public void aSwingUnderWayAtTheStartIsNotTheNextBlow() {
        WitherTurnHold h = new WitherTurnHold();
        h.begin(7, true);
        assertEquals(End.NONE, h.step(true, true, 2.0, 12f, false, false, true, true));
    }

    @Test
    public void theCapEndsAHoldWithNoSwing() {
        WitherTurnHold h = new WitherTurnHold();
        h.begin(7, false);
        for (int i = 1; i < WitherTurnHold.CAP; i++) assertEquals(End.NONE, h.step(true, true, 2.0, 12f, false, false, true, false));
        assertEquals(End.CAP, h.step(true, true, 2.0, 12f, false, false, true, false));
    }

    @Test
    public void theHoldEndsForItsReasons() {
        WitherTurnHold h = new WitherTurnHold();
        h.begin(7, false);
        assertEquals(End.RUN_OVER, h.step(false, true, 2.0, 12f, false, false, true, false));
        h.begin(7, false);
        assertEquals(End.GONE, h.step(true, false, 2.0, 12f, false, false, true, false));
        h.begin(7, false);
        assertEquals(End.FAR, h.step(true, true, 3.6, 12f, false, false, true, false));
        h.begin(7, false);
        assertEquals(End.LOW_HP, h.step(true, true, 2.0, 5f, false, false, true, false));
        h.begin(7, false);
        assertEquals(End.BEHIND, h.step(true, true, 2.0, 12f, false, true, true, false));
        h.begin(7, false);
        assertEquals(End.LAVA, h.step(true, true, 2.0, 12f, true, false, true, false));
        h.reset();
        assertEquals(-1, h.holding());
    }

    @Test
    public void behindIsMoreThanNinetyDegrees() {
        assertFalse(WitherTurnHold.behind(1, 0, 1, 1));
        assertFalse(WitherTurnHold.behind(1, 0, 0, 1));
        assertTrue(WitherTurnHold.behind(1, 0, -1, 0.5));
    }

    @Test
    public void theAngleIsMeasuredFromTheHeadsYaw() {
        // yaw 0 looks to +z
        assertEquals(0, WitherTurnHold.angleTo(0f, 0, 2), 0.01);
        assertEquals(180, WitherTurnHold.angleTo(0f, 0, -2), 0.01);
        // yaw 90 looks to -x
        assertEquals(0, WitherTurnHold.angleTo(90f, -3, 0), 0.01);
        assertEquals(90, WitherTurnHold.angleTo(90f, 0, 3), 0.01);
    }
}
