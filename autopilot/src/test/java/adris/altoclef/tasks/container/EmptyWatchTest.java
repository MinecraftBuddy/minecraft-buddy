package adris.altoclef.tasks.container;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the thing that stops a chest that has not synced yet from counting as a chest that is empty
public class EmptyWatchTest {
    private static boolean tickN(EmptyWatch w, int n, boolean ready, boolean match) {
        boolean last = false;
        for (int i = 0; i < n; i++) {
            last = w.tick(ready, match);
        }
        return last;
    }

    @Test
    public void emptyOnlyCountsAfterSettling() {
        EmptyWatch w = new EmptyWatch();
        assertFalse(tickN(w, EmptyWatch.SETTLE_TICKS - 1, true, false));
        assertTrue(w.tick(true, false));
    }

    @Test
    public void aMatchResetsTheCount() {
        EmptyWatch w = new EmptyWatch();
        tickN(w, EmptyWatch.SETTLE_TICKS - 1, true, false);
        assertFalse(w.tick(true, true));
        assertFalse(tickN(w, EmptyWatch.SETTLE_TICKS - 1, true, false));
        assertTrue(w.tick(true, false));
    }

    @Test
    public void aMenuWithoutContainerSlotsNeverSettles() {
        EmptyWatch w = new EmptyWatch();
        assertFalse(tickN(w, 100, false, false));
    }

    @Test
    public void resetOnReopenMakesItWaitAgain() {
        EmptyWatch w = new EmptyWatch();
        tickN(w, EmptyWatch.SETTLE_TICKS - 1, true, false);
        w.reset();
        assertFalse(tickN(w, EmptyWatch.SETTLE_TICKS - 1, true, false));
        assertTrue(w.tick(true, false));
    }
}
