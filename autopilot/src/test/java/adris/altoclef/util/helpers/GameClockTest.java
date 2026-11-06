package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GameClockTest {
    @Test
    public void aClockMovingOnIsThePlainDifference() {
        assertEquals(0, GameClock.since(100, 100));
        assertEquals(250, GameClock.since(100, 350));
    }

    @Test
    public void aTickOrTwoBackIsNoTimeAtAll() {
        assertEquals(0, GameClock.since(100, 99));
        assertEquals(0, GameClock.since(100, 98));
        assertEquals(0, GameClock.since(5000, 5000 - GameClock.JITTER_TICKS));
    }

    @Test
    public void wayBackIsLongAgoForAnything() {
        assertEquals(Long.MAX_VALUE, GameClock.since(5000, 5000 - GameClock.JITTER_TICKS - 1));
        assertEquals(Long.MAX_VALUE, GameClock.since(50_000, 100));
    }
}
