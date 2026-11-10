package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaceWatchTest {
    // ticks every 20 game ticks at (x, z) from one tick to another; whether any fired
    private static boolean stay(PlaceWatch w, int x, int z, long from, long to) {
        boolean fired = false;
        for (long t = from; t <= to; t += 20) fired |= w.tick(x, z, t);
        return fired;
    }

    @Test
    public void firesAfterTheLimitWithinTheRadius() {
        PlaceWatch w = new PlaceWatch(10, 3600, 600);
        assertFalse(w.tick(100, 200, 1000));
        for (long t = 1020; t <= 4600; t += 20) assertFalse(w.tick(100 + (int) (t / 20) % 8, 200, t));
        assertTrue(w.tick(105, 205, 4620));
        // it starts again from there
        assertFalse(w.tick(105, 205, 4640));
    }

    @Test
    public void movingOnStartsAgain() {
        PlaceWatch w = new PlaceWatch(10, 3600, 600);
        assertFalse(stay(w, 100, 200, 1000, 4000));
        // 20 blocks on: a new spot, its own clock
        assertFalse(stay(w, 120, 200, 4020, 7600));
        assertTrue(stay(w, 120, 200, 7620, 7660));
    }

    @Test
    public void timeAwayFromTheTaskDoesNotCount() {
        PlaceWatch w = new PlaceWatch(10, 3600, 600);
        assertFalse(stay(w, 100, 200, 1000, 3000));
        // two game minutes away (a meal, the smelting), then back at the same spot: a fresh clock
        assertFalse(stay(w, 100, 200, 5400, 8900));
        assertTrue(stay(w, 100, 200, 8920, 9100));
    }
}
