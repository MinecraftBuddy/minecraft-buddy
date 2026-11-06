package adris.altoclef.trace;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RepeatCounterTest {

    @Test
    public void saysNothingUntilTheFifth() {
        RepeatCounter c = new RepeatCounter();
        for (int i = 0; i < 4; i++) {
            assertEquals(0, c.hit("A|mining", i * 1000L));
        }
        assertEquals(5, c.hit("A|mining", 4000));
    }

    @Test
    public void saysAtFiveTenAndTwentyOnly() {
        RepeatCounter c = new RepeatCounter();
        int told = 0;
        int[] seen = new int[3];
        for (int i = 1; i <= 25; i++) {
            int m = c.hit("A|mining", i * 1000L);
            if (m != 0) {
                seen[told++] = m;
            }
        }
        assertEquals(3, told);
        assertEquals(5, seen[0]);
        assertEquals(10, seen[1]);
        assertEquals(20, seen[2]);
    }

    @Test
    public void keysAreCountedApart() {
        RepeatCounter c = new RepeatCounter();
        for (int i = 0; i < 4; i++) {
            c.hit("A|mining", i);
            c.hit("B|mining", i);
        }
        assertEquals(5, c.hit("A|mining", 5));
        assertEquals(5, c.hit("B|mining", 5));
        assertEquals(0, c.hit("C|mining", 5));
    }

    @Test
    public void flipsOutsideTheWindowDoNotCount() {
        RepeatCounter c = new RepeatCounter();
        // four now, then a minute of nothing, then one more is a first again
        for (int i = 0; i < 4; i++) {
            c.hit("A|x", i);
        }
        assertEquals(0, c.hit("A|x", RepeatCounter.WINDOW_MS + 100));
    }

    @Test
    public void aSlowTrickleNeverReachesFive() {
        RepeatCounter c = new RepeatCounter();
        // one every 20 s: three per window
        for (int i = 0; i < 40; i++) {
            assertEquals(0, c.hit("A|x", i * 20_000L));
        }
    }

    @Test
    public void aSteadyFivePerWindowOnlyAnnouncesOnce() {
        RepeatCounter c = new RepeatCounter();
        int told = 0;
        // one every 12 s sits right at five per minute
        for (int i = 0; i < 50; i++) {
            if (c.hit("A|x", i * 12_000L) != 0) {
                told++;
            }
        }
        assertEquals(1, told);
    }

    @Test
    public void aSlowTrickleAfterABurstLetsTheNextBurstBeHeard() {
        RepeatCounter c = new RepeatCounter();
        for (int i = 1; i <= 5; i++) {
            c.hit("A|x", i * 1000L);
        }
        // one every 40 s keeps the entry alive without ever being five in a window
        long t = 5000;
        for (int i = 0; i < 6; i++) {
            t += 40_000;
            assertEquals(0, c.hit("A|x", t));
        }
        // the last two trickle hits are still in the window, so the third of these makes five
        assertEquals(0, c.hit("A|x", t + 100));
        assertEquals(0, c.hit("A|x", t + 101));
        assertEquals(5, c.hit("A|x", t + 102));
    }

    @Test
    public void aRetryRightOnTheEdgeOfFiveDoesNotAnnounceEveryOtherLap() {
        RepeatCounter c = new RepeatCounter();
        int told = 0;
        long t = 0;
        // gaps wobbling around 15 s: four or five in a window, back and forth
        for (int i = 0; i < 60; i++) {
            if (c.hit("A|x", t) != 0) {
                told++;
            }
            t += i % 2 == 0 ? 14_500 : 15_500;
        }
        assertEquals(1, told);
    }

    @Test
    public void rearmsAfterTheWindowGoesQuiet() {
        RepeatCounter c = new RepeatCounter();
        for (int i = 0; i < 5; i++) {
            c.hit("A|x", i);
        }
        long later = 5 * RepeatCounter.WINDOW_MS;
        for (int i = 0; i < 4; i++) {
            assertEquals(0, c.hit("A|x", later + i));
        }
        assertEquals(5, c.hit("A|x", later + 4));
    }

    @Test
    public void manyDistinctKeysGetSwept() {
        RepeatCounter c = new RepeatCounter();
        for (int i = 0; i < 2000; i++) {
            c.hit("key " + i, i * 100L);
        }
        // still working after the sweeps, and a fresh key counts from one
        for (int i = 0; i < 4; i++) {
            assertEquals(0, c.hit("fresh", 300_000L + i));
        }
        assertEquals(5, c.hit("fresh", 300_010L));
    }
}
