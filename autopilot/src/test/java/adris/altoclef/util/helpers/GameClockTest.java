package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GameClockTest {
    @Test
    public void aClockMovingOnIsThePlainDifference() {
        assertEquals(0, GameClock.since(100L, 100L));
        assertEquals(250, GameClock.since(100L, 350L));
    }

    @Test
    public void aTickOrTwoBackIsNoTimeAtAll() {
        assertEquals(0, GameClock.since(100L, 99L));
        assertEquals(0, GameClock.since(100L, 98L));
        assertEquals(0, GameClock.since(5000L, 5000 - GameClock.JITTER_TICKS));
    }

    @Test
    public void wayBackIsLongAgoForAnything() {
        assertEquals(Long.MAX_VALUE, GameClock.since(5000L, 5000 - GameClock.JITTER_TICKS - 1));
        assertEquals(Long.MAX_VALUE, GameClock.since(50_000L, 100L));
    }

    // now - Long.MIN_VALUE overflowed to a negative number, "a moment ago" for ever
    @Test
    public void neverIsLongAgoForAnything() {
        assertEquals(Long.MAX_VALUE, GameClock.since(Long.MIN_VALUE, 0L));
        assertEquals(Long.MAX_VALUE, GameClock.since(Long.MIN_VALUE, 1L));
        assertEquals(Long.MAX_VALUE, GameClock.since(Long.MIN_VALUE, 24_000_000L));
        assertEquals(Long.MAX_VALUE, GameClock.since(Long.MIN_VALUE, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, GameClock.since(Long.MIN_VALUE, -5L));
    }

    @Test
    public void anIntClockIsTheLongOne() {
        assertEquals(0, GameClock.since(100, 100));
        assertEquals(250, GameClock.since(100, 350));
        assertEquals(0, GameClock.since(100, 98));
        assertEquals(Long.MAX_VALUE, GameClock.since(50_000, 100));
        // no int overflow: the difference is taken in long
        assertEquals((long) Integer.MAX_VALUE + 1_000_000, GameClock.since(-1_000_000, Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, GameClock.since(Integer.MIN_VALUE + 1, 0));
        // a start other than never stays a plain number (MLGBucketFallChain's Integer.MIN_VALUE / 2)
        assertEquals(1L << 30, GameClock.since(Integer.MIN_VALUE / 2, 0));
    }

    @Test
    public void anIntClockNeverSetIsLongAgoForAnything() {
        assertEquals(Long.MAX_VALUE, GameClock.since(Integer.MIN_VALUE, 0));
        assertEquals(Long.MAX_VALUE, GameClock.since(Integer.MIN_VALUE, 40));
        assertEquals(Long.MAX_VALUE, GameClock.since(Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, GameClock.since(Integer.MIN_VALUE, Integer.MIN_VALUE));
    }

    // MobDefenseChain asks CreeperCap.inWindow(since(_capSince, now)) with _capSince never set: out of the window, as the
    // negative number was before
    @Test
    public void aNeverSetCapClockIsOutOfTheWindow() {
        org.junit.Assert.assertFalse(CreeperCap.inWindow(GameClock.since(Long.MIN_VALUE, 0L)));
        org.junit.Assert.assertFalse(CreeperCap.inWindow(GameClock.since(Long.MIN_VALUE, 123_456L)));
    }
}
