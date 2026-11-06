package adris.altoclef.trackers;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PearlWatchTest {
    @Test
    public void saysTheFirstSightingAndEachVerdictChangeOnce() {
        PearlWatch w = new PearlWatch();
        String first = w.see(5, 1, 64, 2, false, false, "airborne");
        assertTrue(first, first.contains("#5: seen") && first.endsWith("airborne"));
        w.sweep(0, 64, 0, 3, 0);
        assertNull(w.see(5, 1, 63.5, 2, false, false, "airborne"));
        w.sweep(0, 64, 0, 3, 1);
        String change = w.see(5, 1, 63, 2, true, false, "listed");
        assertTrue(change, change.contains("airborne -> listed"));
    }

    @Test
    public void theGoneLineWaitsForTheBagToSettle() {
        PearlWatch w = new PearlWatch();
        w.see(5, 3, 64, 4, true, false, "listed");
        assertTrue(w.sweep(0, 64, 0, 3, 100).isEmpty());
        // not in this pass: gone, but the count is read a few ticks later
        assertTrue(w.sweep(0, 64, 0, 3, 101).isEmpty());
        assertTrue(w.sweep(0, 64, 0, 4, 101 + PearlWatch.SETTLE_TICKS - 1).isEmpty());
        List<String> lines = w.sweep(0, 64, 0, 4, 101 + PearlWatch.SETTLE_TICKS);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains("#5: gone from 3.0 64.0 4.0, we were 5.0 away, pearls 3 -> 4"));
        assertTrue(w.idle());
    }

    @Test
    public void resetForgetsEverything() {
        PearlWatch w = new PearlWatch();
        w.see(5, 3, 64, 4, true, false, "listed");
        w.sweep(0, 64, 0, 3, 0);
        w.reset();
        assertTrue(w.idle());
        assertTrue(w.sweep(0, 64, 0, 3, 50).isEmpty());
    }

    @Test
    public void aFlippingPearlOnlyGetsAFewChangeLines() {
        PearlWatch w = new PearlWatch();
        w.see(5, 0, 64, 0, false, false, "airborne");
        int lines = 0;
        for (int i = 0; i < 20; i++) {
            if (w.see(5, 0, 64, 0, false, false, i % 2 == 0 ? "listed" : "airborne") != null) {
                lines++;
            }
        }
        assertEquals(PearlWatch.CHANGE_LINES, lines);
    }
}
