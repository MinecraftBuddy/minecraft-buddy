package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlacedByUsTest {

    @Test
    public void aFreshPlacementIsRecent() {
        PlacedByUs reg = new PlacedByUs();
        reg.record(10, 64, -3, 100);
        assertTrue(reg.recent(10, 64, -3, 100));
        assertTrue(reg.recent(10, 64, -3, 100 + PlacedByUs.TTL_TICKS));
    }

    @Test
    public void otherSpotsAreNot() {
        PlacedByUs reg = new PlacedByUs();
        reg.record(10, 64, -3, 100);
        assertFalse(reg.recent(10, 65, -3, 100));
        assertFalse(reg.recent(11, 64, -3, 100));
        assertFalse(reg.recent(10, 64, -2, 100));
    }

    @Test
    public void itExpiresAfterTheMinute() {
        PlacedByUs reg = new PlacedByUs();
        reg.record(1, 2, 3, 0);
        assertFalse(reg.recent(1, 2, 3, PlacedByUs.TTL_TICKS + 1));
        // and the stale entry is gone, not just hidden
        assertEquals(0, reg.size());
    }

    @Test
    public void placingTheSameSpotAgainRefreshesIt() {
        PlacedByUs reg = new PlacedByUs();
        reg.record(1, 2, 3, 0);
        reg.record(1, 2, 3, 1000);
        assertEquals(1, reg.size());
        assertTrue(reg.recent(1, 2, 3, 1000 + PlacedByUs.TTL_TICKS));
    }

    @Test
    public void theOldestGoesWhenTheCapBites() {
        PlacedByUs reg = new PlacedByUs();
        for (int i = 0; i < PlacedByUs.CAP + 5; i++) {
            reg.record(i, 64, 0, i);
        }
        assertEquals(PlacedByUs.CAP, reg.size());
        long now = PlacedByUs.CAP + 5;
        for (int i = 0; i < 5; i++) {
            assertFalse("oldest " + i + " should be gone", reg.recent(i, 64, 0, now));
        }
        assertTrue(reg.recent(5, 64, 0, now));
        assertTrue(reg.recent(PlacedByUs.CAP + 4, 64, 0, now));
    }

    @Test
    public void refreshingASpotSavesItFromTheCap() {
        PlacedByUs reg = new PlacedByUs();
        for (int i = 0; i < PlacedByUs.CAP; i++) {
            reg.record(i, 64, 0, i);
        }
        // spot 0 is the oldest, until it is placed on again
        reg.record(0, 64, 0, 500);
        reg.record(999, 64, 0, 501);
        assertTrue(reg.recent(0, 64, 0, 501));
        assertFalse(reg.recent(1, 64, 0, 501));
    }

    @Test
    public void negativeCoordinatesDontCollide() {
        PlacedByUs reg = new PlacedByUs();
        reg.record(-1, 64, -1, 0);
        assertTrue(reg.recent(-1, 64, -1, 0));
        assertFalse(reg.recent(1, 64, 1, 0));
        assertFalse(reg.recent(-1, 64, 1, 0));
        assertFalse(reg.recent(1, 64, -1, 0));
        // and a low y (the overworld's floor is -64) is its own key too
        reg.record(0, -60, 0, 0);
        assertTrue(reg.recent(0, -60, 0, 0));
        assertFalse(reg.recent(0, 60, 0, 0));
    }

    @Test
    public void clearForgetsEverything() {
        PlacedByUs reg = new PlacedByUs();
        reg.record(1, 2, 3, 0);
        reg.clear();
        assertFalse(reg.recent(1, 2, 3, 0));
    }

    @Test
    public void onlyBlocksInsideReachCount() {
        // standing at the middle of (0, 64, 0), eyes at 65.6
        assertTrue(PlacedByUs.withinReach(0.5, 65.6, 0.5, 2, 64, 1));
        assertFalse(PlacedByUs.withinReach(0.5, 65.6, 0.5, 40, 64, 1));
        assertFalse(PlacedByUs.withinReach(0.5, 65.6, 0.5, 0, 90, 0));
    }
}
