package adris.altoclef.trace;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StartStormWatchTest {

    @Test
    public void twentyStartsInAMinuteIsFine() {
        StartStormWatch w = new StartStormWatch();
        for (int i = 0; i < 20; i++) {
            assertFalse(w.start(String.class, i * 1000L));
        }
    }

    @Test
    public void theTwentyFirstInTheWindowFires() {
        StartStormWatch w = new StartStormWatch();
        for (int i = 0; i < 20; i++) {
            w.start(String.class, i * 1000L);
        }
        assertTrue(w.start(String.class, 20_000));
    }

    @Test
    public void theEdgeOfTheWindowStillCounts() {
        StartStormWatch w = new StartStormWatch();
        for (int i = 0; i < 20; i++) {
            w.start(String.class, i == 0 ? 0 : 59_000L);
        }
        // the first start is exactly a minute old
        assertTrue(w.start(String.class, StartStormWatch.WINDOW_MS));
    }

    @Test
    public void oneMillisecondPastTheWindowDoesNot() {
        StartStormWatch w = new StartStormWatch();
        for (int i = 0; i < 20; i++) {
            w.start(String.class, i == 0 ? 0 : 59_000L);
        }
        assertFalse(w.start(String.class, StartStormWatch.WINDOW_MS + 1));
    }

    @Test
    public void firesOncePerStorm() {
        StartStormWatch w = new StartStormWatch();
        int fired = 0;
        for (int i = 0; i < 100; i++) {
            if (w.start(String.class, i * 100L)) {
                fired++;
            }
        }
        org.junit.Assert.assertEquals(1, fired);
    }

    @Test
    public void rearmsOnceTheRateDropsAndFiresAgainOnTheNextStorm() {
        StartStormWatch w = new StartStormWatch();
        for (int i = 0; i < 30; i++) {
            w.start(String.class, i * 100L);
        }
        // a quiet stretch, then a slow start (the window no longer holds 21) re-arms
        long later = 10 * StartStormWatch.WINDOW_MS;
        assertFalse(w.start(String.class, later));
        boolean fired = false;
        for (int i = 1; i < 30; i++) {
            fired |= w.start(String.class, later + i * 100L);
        }
        assertTrue(fired);
    }

    @Test
    public void aClassHoveringJustUnderTheLimitDoesNotFlapIntoASecondDump() {
        StartStormWatch w = new StartStormWatch();
        int fired = 0;
        for (int i = 0; i < 21; i++) {
            if (w.start(String.class, i * 1000L)) {
                fired++;
            }
        }
        // then 15 a minute for ten minutes: under the limit, but nowhere near half of it
        for (long t = 24_000; t < 600_000; t += 4000) {
            if (w.start(String.class, t)) {
                fired++;
            }
        }
        // and a burst on top of that is still the same incident
        for (int i = 0; i < 30; i++) {
            if (w.start(String.class, 600_000 + i * 100L)) {
                fired++;
            }
        }
        assertEquals(1, fired);
    }

    @Test
    public void classesAreKeptApart() {
        StartStormWatch w = new StartStormWatch();
        for (int i = 0; i < 20; i++) {
            w.start(String.class, i);
            w.start(Integer.class, i);
        }
        assertTrue(w.start(String.class, 21));
        assertTrue(w.start(Integer.class, 21));
        assertFalse(w.start(Long.class, 21));
    }

    @Test
    public void recentCountsTheStartsInsideTheWindowOnly() {
        StartStormWatch w = new StartStormWatch();
        assertEquals(0, w.recent(String.class, 0));
        for (int i = 0; i < 15; i++) {
            w.start(String.class, i * 1000L);
        }
        assertEquals(15, w.recent(String.class, 15_000));
        // the first five are a minute and more old by now
        assertEquals(10, w.recent(String.class, 65_000));
        assertEquals(0, w.recent(Integer.class, 65_000));
    }
}
