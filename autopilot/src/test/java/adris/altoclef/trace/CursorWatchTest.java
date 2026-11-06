package adris.altoclef.trace;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CursorWatchTest {

    @Test
    public void anEmptyCursorNeverFires() {
        CursorWatch w = new CursorWatch();
        for (long t = 0; t < 30_000; t += 1000) {
            assertFalse(w.tick(false, false, t));
        }
    }

    @Test
    public void firesAtFiveSecondsAndNotBefore() {
        CursorWatch w = new CursorWatch();
        assertFalse(w.tick(true, false, 1000));
        assertFalse(w.tick(true, false, 5999));
        assertTrue(w.tick(true, false, 6000));
        assertEquals(5000, w.stuckMs(6000));
    }

    @Test
    public void firesOncePerStretch() {
        CursorWatch w = new CursorWatch();
        w.tick(true, false, 0);
        assertTrue(w.tick(true, false, 5000));
        assertFalse(w.tick(true, false, 6000));
        assertFalse(w.tick(true, false, 60_000));
    }

    @Test
    public void rearmsWhenTheCursorClears() {
        CursorWatch w = new CursorWatch();
        w.tick(true, false, 0);
        assertTrue(w.tick(true, false, 5000));
        assertFalse(w.tick(false, false, 6000));
        assertFalse(w.tick(true, false, 7000));
        assertFalse(w.tick(true, false, 11_999));
        assertTrue(w.tick(true, false, 12_000));
    }

    @Test
    public void anOpenContainerRestartsTheTimer() {
        CursorWatch w = new CursorWatch();
        w.tick(true, false, 0);
        w.tick(true, false, 4000);
        // the screen is open, an item on the cursor is normal there
        assertFalse(w.tick(true, true, 5000));
        assertFalse(w.tick(true, false, 6000));
        assertFalse(w.tick(true, false, 10_999));
        assertTrue(w.tick(true, false, 11_000));
    }

    @Test
    public void anOpenContainerDoesNotRearmAfterAFire() {
        CursorWatch w = new CursorWatch();
        w.tick(true, false, 0);
        assertTrue(w.tick(true, false, 5000));
        w.tick(true, true, 6000);
        // same stretch of holding, so it has already been said
        assertFalse(w.tick(true, false, 7000));
        assertFalse(w.tick(true, false, 30_000));
    }
}
