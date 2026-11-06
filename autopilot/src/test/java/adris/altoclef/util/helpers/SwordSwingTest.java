package adris.altoclef.util.helpers;

import net.minecraft.world.phys.AABB;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the sword's charge gate and the sprint lock that sets up the sweep
public class SwordSwingTest {

    @Test
    public void sprintComesOffInTheLastTicksOfTheCharge() {
        assertTrue(SwordSwing.lockSprint(true, true, 0, false));
        assertTrue(SwordSwing.lockSprint(true, true, SwordSwing.LOCK_LEAD_TICKS, false));
        // still charging with ticks to spare: nothing to lock yet, sprinting up to the mob is fine
        assertFalse(SwordSwing.lockSprint(true, true, SwordSwing.LOCK_LEAD_TICKS + 0.5, false));
        assertFalse(SwordSwing.lockSprint(true, true, 20, false));
    }

    @Test
    public void noLockWithoutASwordOrOutOfReach() {
        assertFalse(SwordSwing.lockSprint(false, true, 0, false));
        assertFalse(SwordSwing.lockSprint(true, false, 0, false));
    }

    @Test
    public void aCalmNeutralMobBesideTheTargetKeepsSprintAlone() {
        // a sprint hit can't sweep, so the neutral one next to the target is left out of it
        assertFalse(SwordSwing.lockSprint(true, true, 0, true));
    }

    @Test
    public void swingNeedsTheChargeWhateverElse() {
        assertFalse(SwordSwing.swing(false, false, false));
        assertFalse(SwordSwing.swing(false, true, true));
    }

    @Test
    public void chargedAndNotLockedSwingsAtOnce() {
        assertTrue(SwordSwing.swing(true, false, false));
    }

    @Test
    public void aFreshLockWaitsOneTickForTheStopPacket() {
        // asked this very tick: the server has not seen not-sprinting yet
        assertFalse(SwordSwing.swing(true, true, false));
        // asked on an earlier tick too: the stop is out
        assertTrue(SwordSwing.swing(true, true, true));
    }

    @Test
    public void aCalmNeutralBesideTheTargetSwingsEarlyUnderTheSweepLine() {
        assertTrue(SwordSwing.charged(0.6, true));
        assertTrue(SwordSwing.charged(0.85, true));
        assertTrue(SwordSwing.charged(0.9, true));
        assertFalse(SwordSwing.charged(0.5, true));
        // the sweep needs more than 0.9, the early swing stays under it
        assertTrue(SwordSwing.CALM_SWING_SCALE < 0.9);
    }

    @Test
    public void withoutACalmNeutralOnlyFullChargeSwings() {
        assertFalse(SwordSwing.charged(0.6, false));
        assertFalse(SwordSwing.charged(0.99, false));
        assertTrue(SwordSwing.charged(1, false));
    }

    @Test
    public void aFullChargeNeverSwingsBesideACalmNeutral() {
        assertFalse(SwordSwing.charged(0.91, true));
        assertFalse(SwordSwing.charged(1, true));
    }

    @Test
    public void overTheSweepLineBesideACalmNeutralThrowsTheChargeAway() {
        assertTrue(SwordSwing.needsReset(0.91, true));
        assertTrue(SwordSwing.needsReset(1, true));
        assertFalse(SwordSwing.needsReset(0.9, true));
        assertFalse(SwordSwing.needsReset(0.6, true));
        // nothing calm near: a full swing is the normal one
        assertFalse(SwordSwing.needsReset(1, false));
    }

    @Test
    public void aScaleNeverBothSwingsAndResetsBesideACalmNeutral() {
        for (double scale = 0; scale <= 1.0001; scale += 0.01) {
            boolean swing = SwordSwing.charged(scale, true);
            boolean reset = SwordSwing.needsReset(scale, true);
            assertFalse("scale " + scale, swing && reset);
        }
    }

    @Test
    public void theOddItemStaysInHandForThreeTicksPastTheSwap() {
        long at = 100;
        for (long now = at; now <= at + SwordSwing.RESET_HOLD_TICKS; now++) {
            assertTrue("tick " + now, SwordSwing.resetHeld(now, at));
        }
        assertFalse(SwordSwing.resetHeld(at + SwordSwing.RESET_HOLD_TICKS + 1, at));
        // the clock going backwards (a new world) is no hold
        assertFalse(SwordSwing.resetHeld(at - 1, at));
        assertTrue(SwordSwing.RESET_HOLD_TICKS >= 3);
    }

    @Test
    public void aCalmNeutralIsNearInsideTheBoxGrownOneAndAHalfAndThreeBlocksOut() {
        AABB target = new AABB(0, 0, 0, 0.6, 1.95, 0.6);
        // 1.4 off the edge: past the vanilla sweep box (1.0), inside ours
        AABB far = new AABB(2.0, 0, 0, 2.6, 1.95, 0.6);
        assertFalse(SwordSwing.inSweepBox(target, far, 4));
        assertTrue(SwordSwing.calmNear(target, far, 4));
        // 1.6 off the edge: outside
        assertFalse(SwordSwing.calmNear(target, new AABB(2.2, 0, 0, 2.8, 1.95, 0.6), 4));
        // grown 0.75 up and down: a mob a block over its head is not near, half a block is
        assertFalse(SwordSwing.calmNear(target, new AABB(0, 3.0, 0, 0.6, 4.9, 0.6), 4));
        assertTrue(SwordSwing.calmNear(target, new AABB(0, 2.4, 0, 0.6, 4.3, 0.6), 4));
        // more than 3 blocks from us: not near
        assertTrue(SwordSwing.calmNear(target, far, 8.9));
        assertFalse(SwordSwing.calmNear(target, far, 9));
    }

    @Test
    public void theEarlyWindowIsFourTicksOfTheSwordCharge() {
        // a sword's scale reads 0.60 0.68 0.76 0.84 before it passes the sweep line, and every one of them swings early
        int window = 0;
        for (int tick = 0; tick < 12; tick++) {
            double scale = (tick + 0.5) / 12.5;
            if (scale <= SwordSwing.SWEEP_SCALE && SwordSwing.charged(scale, true)) {
                window++;
            }
        }
        assertEquals(4, window);
    }

    @Test
    public void theSweepBoxIsTheTargetsBoxGrownOneSideways() {
        AABB target = new AABB(0, 0, 0, 0.6, 1.95, 0.6);
        // beside it, a hair under 1 block off its edge: inside
        assertTrue(SwordSwing.inSweepBox(target, new AABB(1.5, 0, 0, 2.1, 1.95, 0.6), 4));
        // 2 blocks off its edge: outside
        assertFalse(SwordSwing.inSweepBox(target, new AABB(2.7, 0, 0, 3.3, 1.95, 0.6), 4));
        // grown a quarter up and down only: a mob standing a block over its head is not
        assertFalse(SwordSwing.inSweepBox(target, new AABB(0, 2.5, 0, 0.6, 4.4, 0.6), 4));
        assertTrue(SwordSwing.inSweepBox(target, new AABB(0, 2.1, 0, 0.6, 4.0, 0.6), 4));
    }

    @Test
    public void theSweepBoxOnlyReachesThreeBlocksFromUs() {
        AABB target = new AABB(0, 0, 0, 0.6, 1.95, 0.6);
        AABB next = new AABB(0.7, 0, 0, 1.3, 1.95, 0.6);
        assertTrue(SwordSwing.inSweepBox(target, next, 8.9));
        assertFalse(SwordSwing.inSweepBox(target, next, 9));
    }

    @Test
    public void everyChargeAndLockStateHasOneAnswer() {
        for (boolean ready : new boolean[]{false, true}) {
            for (boolean asked : new boolean[]{false, true}) {
                for (boolean earlier : new boolean[]{false, true}) {
                    boolean expected = ready && (!asked || earlier);
                    assertEquals(ready + " " + asked + " " + earlier, expected, SwordSwing.swing(ready, asked, earlier));
                }
            }
        }
    }

    @Test
    public void aCalmNeutralRightBesideUsCountsWhateverTheTargetsBoxSays() {
        assertTrue(SwordSwing.calmByUs(1.2 * 1.2));
        assertTrue(SwordSwing.calmByUs(2.9 * 2.9));
        assertFalse(SwordSwing.calmByUs(3.1 * 3.1));
    }
}
