package baritone.pathing.movement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HitBlockerTest {

    @Test
    public void onlyAPlainCritterInReachGetsHit() {
        assertTrue(HitBlocker.hittable(true, false, false, false, true));
        assertFalse("not a critter", HitBlocker.hittable(false, false, false, false, true));
        assertFalse("somebody named it", HitBlocker.hittable(true, true, false, false, true));
        assertFalse("somebody leashed it", HitBlocker.hittable(true, false, true, false, true));
        assertFalse("chicken jockey", HitBlocker.hittable(true, false, false, true, true));
        assertFalse("too far to hit", HitBlocker.hittable(true, false, false, false, false));
    }

    @Test
    public void theCellSortsItsBlockers() {
        assertEquals(HitBlocker.Cell.CLEAR, HitBlocker.cell(0, 0, 0));
        assertEquals(HitBlocker.Cell.HIT, HitBlocker.cell(1, 0, 1));
        assertEquals(HitBlocker.Cell.CLEARING, HitBlocker.cell(1, 0, 0));
        // a live bat next to a corpse is still worth a swing
        assertEquals(HitBlocker.Cell.HIT, HitBlocker.cell(2, 0, 1));
        // a zombie next to the bat: punching the bat won't free the cell
        assertEquals(HitBlocker.Cell.BLOCKED, HitBlocker.cell(2, 1, 1));
        assertEquals(HitBlocker.Cell.BLOCKED, HitBlocker.cell(1, 1, 0));
    }

    @Test
    public void swingsWaitForCooldownAndTheGap() {
        assertTrue("never swung", HitBlocker.swingNow(1, 0, Integer.MIN_VALUE));
        assertFalse("cooldown not full", HitBlocker.swingNow(0.9f, 100, Integer.MIN_VALUE));
        assertFalse("mob is still invulnerable", HitBlocker.swingNow(1, 100 + HitBlocker.SWING_GAP - 1, 100));
        assertTrue(HitBlocker.swingNow(1, 100 + HitBlocker.SWING_GAP, 100));
        // tickCount went backwards (respawn), don't lock up forever
        assertTrue(HitBlocker.swingNow(1, 5, 100));
    }

    @Test
    public void swingDueRemembersTheSwing() {
        MovementState s = new MovementState();
        assertTrue(s.swingDue(1, 50));
        assertFalse(s.swingDue(1, 51));
        assertFalse(s.swingDue(1, 50 + HitBlocker.SWING_GAP - 1));
        assertTrue(s.swingDue(1, 50 + HitBlocker.SWING_GAP));
    }

    @Test
    public void hittingHasItsOwnCap() {
        MovementState s = new MovementState();
        for (int i = 1; i <= HitBlocker.HIT_PATIENCE; i++) {
            assertEquals("tick " + i, HitBlocker.Action.HIT, s.placeInto(HitBlocker.Cell.HIT));
        }
        assertEquals(HitBlocker.Action.GIVE_UP, s.placeInto(HitBlocker.Cell.HIT));
    }

    // way past PlaceWait's 20 ticks, a bat being punched is not a mob we're waiting out
    @Test
    public void hittingOutlastsPlaceWait() {
        MovementState s = new MovementState();
        for (int i = 0; i < PlaceWait.MOVEMENT_PATIENCE * 3; i++) {
            assertEquals(HitBlocker.Action.HIT, s.placeInto(HitBlocker.Cell.HIT));
        }
    }

    // the corpse is gone on its own within 20 ticks, so those ticks cost nothing
    @Test
    public void corpsesDontCountTowardGivingUp() {
        MovementState s = new MovementState();
        for (int i = 0; i < HitBlocker.HIT_PATIENCE; i++) s.placeInto(HitBlocker.Cell.HIT);
        for (int i = 0; i < 100; i++) {
            assertEquals(HitBlocker.Action.WAIT, s.placeInto(HitBlocker.Cell.CLEARING));
        }
        assertEquals(HitBlocker.Action.PLACE, s.placeInto(HitBlocker.Cell.CLEAR));
    }

    // a bat that keeps flying out and back in still hits the cap, the clock never starts over
    @Test
    public void aFlittingBatStillRunsOutTheClock() {
        MovementState s = new MovementState();
        for (int i = 0; i < HitBlocker.HIT_PATIENCE; i++) {
            s.placeInto(HitBlocker.Cell.HIT);
            assertEquals(HitBlocker.Action.PLACE, s.placeInto(HitBlocker.Cell.CLEAR));
        }
        assertEquals(HitBlocker.Action.GIVE_UP, s.placeInto(HitBlocker.Cell.HIT));
    }

    // anything we won't hit gets PlaceWait's patience, same as before
    @Test
    public void aStubbornBlockerStillGetsPlaceWait() {
        MovementState s = new MovementState();
        for (int i = 1; i <= PlaceWait.MOVEMENT_PATIENCE; i++) {
            assertEquals("tick " + i, HitBlocker.Action.WAIT, s.placeInto(HitBlocker.Cell.BLOCKED));
        }
        assertEquals(HitBlocker.Action.GIVE_UP, s.placeInto(HitBlocker.Cell.BLOCKED));
        assertEquals(HitBlocker.Action.PLACE, s.placeInto(HitBlocker.Cell.CLEAR));
        assertEquals(HitBlocker.Action.WAIT, s.placeInto(HitBlocker.Cell.BLOCKED));
    }
}
