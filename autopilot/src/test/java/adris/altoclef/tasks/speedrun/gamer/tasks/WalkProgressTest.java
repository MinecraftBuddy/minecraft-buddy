package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WalkProgressTest {
    // one ask a second at this distance, true if any of them said stalled
    private static boolean run(WalkProgress w, int from, int to, double dist) {
        boolean stalled = false;
        for (int t = from; t <= to; t++) {
            stalled |= w.stalled(t, dist);
        }
        return stalled;
    }

    @Test
    public void aClimbingWalkIsNeverStalled() {
        WalkProgress w = new WalkProgress();
        double d = 200;
        for (int t = 0; t < 600; t += 2) {
            assertFalse(w.stalled(t, d));
            d -= 1;
        }
    }

    @Test
    public void noGainForAWindowIsAStall() {
        WalkProgress w = new WalkProgress();
        assertFalse(run(w, 0, 0, 100));
        // under the gain line (3 blocks of the 4 it needs) is still standing
        assertFalse(run(w, 1, 44, 97));
        assertTrue(run(w, 45, 45, 97));
    }

    @Test
    public void aRealGainRestartsTheWindow() {
        WalkProgress w = new WalkProgress();
        run(w, 0, 40, 100);
        assertFalse(run(w, 41, 84, 96));
        assertTrue(run(w, 85, 86, 96));
    }

    @Test
    public void somebodyElseHavingTheWheelStartsOver() {
        WalkProgress w = new WalkProgress();
        run(w, 0, 40, 100);
        // a fight for a minute: the old clock means nothing
        assertFalse(w.stalled(100, 100));
        assertFalse(run(w, 101, 140, 100));
    }
}
