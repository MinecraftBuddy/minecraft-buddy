package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GhastWatchTest {
    @Test
    public void oneHitIsBadLuckTwoIsAPattern() {
        GhastWatch w = new GhastWatch(2, 20);
        assertFalse(w.onHit(0));
        assertTrue(w.onHit(10));
    }

    @Test
    public void oldHitsExpire() {
        GhastWatch w = new GhastWatch(2, 20);
        assertFalse(w.onHit(0));
        assertFalse(w.onHit(21));
        assertTrue(w.onHit(30));
    }

    @Test
    public void tallyRestartsAfterItTriggers() {
        GhastWatch w = new GhastWatch(2, 20);
        w.onHit(0);
        assertTrue(w.onHit(1));
        assertFalse(w.onHit(2));
        assertTrue(w.onHit(3));
    }
}
