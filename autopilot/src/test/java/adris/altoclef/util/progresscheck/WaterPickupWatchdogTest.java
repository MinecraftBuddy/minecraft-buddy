package adris.altoclef.util.progresscheck;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WaterPickupWatchdogTest {

    private static final Object ITEM = "iron";

    @Test
    public void bobbingInPlaceGivesUpAfterFourSeconds() {
        WaterPickupWatchdog w = new WaterPickupWatchdog();
        assertFalse(w.update(ITEM, true, 6.0, 0));
        // a little jitter from bobbing is not progress
        assertFalse(w.update(ITEM, true, 5.9, 40));
        assertFalse(w.update(ITEM, true, 6.1, 79));
        assertTrue(w.update(ITEM, true, 6.0, 80));
    }

    @Test
    public void realProgressKeepsPushingTheDeadlineBack() {
        WaterPickupWatchdog w = new WaterPickupWatchdog();
        double d = 10;
        for (int t = 0; t <= 400; t += 20) {
            assertFalse("tick " + t, w.update(ITEM, true, d, t));
            d -= 0.6;
        }
    }

    @Test
    public void swimmingToAnItemThenStallingStillGetsCaught() {
        WaterPickupWatchdog w = new WaterPickupWatchdog();
        assertFalse(w.update(ITEM, true, 8, 0));
        assertFalse(w.update(ITEM, true, 4, 40));
        // arrived at 4 blocks and stuck there
        assertFalse(w.update(ITEM, true, 4, 100));
        assertTrue(w.update(ITEM, true, 4, 120));
    }

    @Test
    public void dryLandNeverTrips() {
        WaterPickupWatchdog w = new WaterPickupWatchdog();
        for (int t = 0; t < 1000; t += 10) {
            assertFalse(w.update(ITEM, false, 5.0, t));
        }
    }

    @Test
    public void leavingTheWaterRestartsTheClock() {
        WaterPickupWatchdog w = new WaterPickupWatchdog();
        assertFalse(w.update(ITEM, true, 6, 0));
        assertFalse(w.update(ITEM, true, 6, 70));
        // one dry tick and the wet stretch is over
        assertFalse(w.update(ITEM, false, 6, 75));
        assertFalse(w.update(ITEM, true, 6, 80));
        assertFalse(w.update(ITEM, true, 6, 150));
        assertTrue(w.update(ITEM, true, 6, 160));
    }

    @Test
    public void newTargetRestartsTheClock() {
        WaterPickupWatchdog w = new WaterPickupWatchdog();
        assertFalse(w.update(ITEM, true, 6, 0));
        assertFalse(w.update(ITEM, true, 6, 70));
        assertFalse(w.update("coal", true, 6, 75));
        assertFalse(w.update("coal", true, 6, 150));
        assertTrue(w.update("coal", true, 6, 155));
    }

    @Test
    public void resetForgetsEverything() {
        WaterPickupWatchdog w = new WaterPickupWatchdog();
        assertFalse(w.update(ITEM, true, 6, 0));
        w.reset();
        assertFalse(w.update(ITEM, true, 6, 200));
        assertFalse(w.update(ITEM, true, 6, 270));
        assertTrue(w.update(ITEM, true, 6, 280));
    }

    @Test
    public void noTargetNeverTrips() {
        WaterPickupWatchdog w = new WaterPickupWatchdog();
        for (int t = 0; t < 500; t += 10) {
            assertFalse(w.update(null, true, 0, t));
        }
    }
}
