package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.Test;

// the chunk hold after a dimension change and the step back into a portal that dropped us by lava
public class ArrivalRulesTest {

    @Test
    public void holdsUntilTheBoxLoadsOrTheCapHits() {
        assertTrue(ArrivalRules.hold(0, false));
        assertTrue(ArrivalRules.hold(ArrivalRules.HOLD_CAP_TICKS - 1, false));
        assertFalse(ArrivalRules.hold(5, true));
        assertFalse(ArrivalRules.hold(ArrivalRules.HOLD_CAP_TICKS, false));
    }

    @Test
    public void stepsBackOnlyInTheNetherSoonAfterArrivingWithAPortalClose() {
        assertTrue(ArrivalRules.stepBack(true, 20, true, -1, true));
        assertFalse(ArrivalRules.stepBack(false, 20, true, -1, true));
        assertFalse(ArrivalRules.stepBack(true, 20, true, -1, false));
        assertFalse(ArrivalRules.stepBack(true, ArrivalRules.STEP_BACK_TICKS + 1, true, -1, true));
        // dry and never started: nothing to back out of
        assertFalse(ArrivalRules.stepBack(true, 20, false, -1, true));
    }

    @Test
    public void aStartedStepBackKeepsGoingWhileWeStandInThePortal() {
        assertTrue(ArrivalRules.stepBack(true, 100, false, 10, true));
        // a start near the end of the window still gets its full teleport wait: the latch counts from its own start
        assertTrue(ArrivalRules.stepBack(true, ArrivalRules.STEP_BACK_TICKS + 300, false, 300, true));
        assertTrue(ArrivalRules.stepBack(true, 9999, false, ArrivalRules.STEP_BACK_LATCH_TICKS, true));
        assertFalse(ArrivalRules.stepBack(true, 100, false, ArrivalRules.STEP_BACK_LATCH_TICKS + 1, true));
    }

    @Test
    public void portalWaitOutlastsTheTransitionAndBackoffClearsTheCooldown() {
        // survival transition is 80 ticks inside, the portal cooldown is 10
        assertTrue(ArrivalRules.PORTAL_WAIT_TICKS >= 80 + 5);
        assertTrue(ArrivalRules.PORTAL_BACKOFF_TICKS >= 11);
        // the latch has to fit the wait and a couple of backoffs
        assertTrue(ArrivalRules.STEP_BACK_LATCH_TICKS >= 2 * (ArrivalRules.PORTAL_WAIT_TICKS + ArrivalRules.PORTAL_BACKOFF_TICKS));
    }

    @Test
    public void givesUpOnlyIfItNeverGotIntoThePortal() {
        assertFalse(ArrivalRules.gaveUp(ArrivalRules.STEP_BACK_GIVE_UP_TICKS - 1, false));
        assertTrue(ArrivalRules.gaveUp(ArrivalRules.STEP_BACK_GIVE_UP_TICKS, false));
        assertFalse(ArrivalRules.gaveUp(ArrivalRules.STEP_BACK_LATCH_TICKS, true));
    }

    @Test
    public void thirdStepBackAtTheSamePortalIsALoop() {
        BlockPos portal = new BlockPos(10, 70, 10);
        BlockPos nearby = new BlockPos(12, 71, 10);
        assertFalse(ArrivalRules.loopCapped(null, 0, portal));
        assertFalse(ArrivalRules.loopCapped(portal, 1, nearby));
        assertTrue(ArrivalRules.loopCapped(portal, 2, nearby));
        // a different portal starts over
        assertFalse(ArrivalRules.loopCapped(portal, 2, new BlockPos(50, 70, 10)));
    }

    @Test
    public void nearestPortalPicksTheClosestCellInReach() {
        BlockPos at = new BlockPos(0, 70, 0);
        BlockPos far = new BlockPos(2, 70, 2);
        BlockPos near = new BlockPos(1, 71, 0);
        assertEquals(near, ArrivalRules.nearestPortal(at, p -> p.equals(far) || p.equals(near)));
        assertNull(ArrivalRules.nearestPortal(at, p -> p.equals(new BlockPos(3, 70, 0))));
    }
}
