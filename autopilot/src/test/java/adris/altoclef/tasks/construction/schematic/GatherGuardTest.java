package adris.altoclef.tasks.construction.schematic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GatherGuardTest {

    @Test
    public void theFirstCountIsTheBaseline() {
        GatherGuard g = new GatherGuard();
        assertFalse(g.round(0));
    }

    @Test
    public void threeRoundsWithoutANewBestIsStuck() {
        GatherGuard g = new GatherGuard();
        assertFalse(g.round(100));
        assertFalse(g.round(100));
        assertFalse(g.round(90));
        assertTrue(g.round(100));
    }

    @Test
    public void aNewBestStartsTheRoundsOver() {
        GatherGuard g = new GatherGuard();
        assertFalse(g.round(100));
        assertFalse(g.round(100));
        assertFalse(g.round(100));
        assertFalse(g.round(101));
        assertFalse(g.round(101));
        assertFalse(g.round(101));
        assertTrue(g.round(101));
    }

    @Test
    public void theWatchCountsForTheNextRound() {
        // the builder placed while we watched, then broke it all again before the count. the best still rose
        GatherGuard g = new GatherGuard();
        assertFalse(g.round(100));
        g.seen(150);
        assertFalse(g.round(100));
        assertFalse(g.round(100));
        assertFalse(g.round(100));
        assertTrue(g.round(100));
    }

    @Test
    public void ticksWithoutANewBestRunOut() {
        GatherGuard g = new GatherGuard();
        g.round(10);
        for (int i = 0; i < GatherGuard.MAX_IDLE_TICKS; i++) {
            assertFalse(g.tick());
        }
        assertTrue(g.tick());
    }

    @Test
    public void aGatherGettingSomewhereBuysTime() {
        GatherGuard g = new GatherGuard();
        g.round(10);
        g.newBatch();
        for (int i = 0; i < GatherGuard.MAX_IDLE_TICKS; i++) {
            g.gathering(i / 100);
            assertFalse(g.tick());
        }
        // stuck at the same count for the whole clock: that one runs out
        for (int i = 0; i < GatherGuard.MAX_IDLE_TICKS; i++) {
            g.gathering(5);
            g.tick();
        }
        g.gathering(5);
        assertTrue(g.tick());
    }

    @Test
    public void walkingBackBuysTimeButIsNoRound() {
        GatherGuard g = new GatherGuard();
        assertFalse(g.round(10));
        for (int i = 0; i < GatherGuard.MAX_IDLE_TICKS; i++) {
            g.tick();
        }
        g.closer();
        assertFalse(g.tick());
        assertFalse(g.round(10));
        assertFalse(g.round(10));
        assertTrue(g.round(10));
    }

    @Test
    public void aGatherNeverCountsAsARound() {
        GatherGuard g = new GatherGuard();
        assertFalse(g.round(10));
        g.gathering(64);
        assertFalse(g.round(10));
        assertFalse(g.round(10));
        assertTrue(g.round(10));
    }

    @Test
    public void aNewBestResetsTheTicks() {
        GatherGuard g = new GatherGuard();
        g.round(10);
        for (int i = 0; i < GatherGuard.MAX_IDLE_TICKS; i++) {
            g.tick();
        }
        g.seen(11);
        assertFalse(g.tick());
        // the same number again is not news
        for (int i = 0; i < GatherGuard.MAX_IDLE_TICKS; i++) {
            g.tick();
        }
        g.seen(11);
        assertTrue(g.tick());
    }
}
