package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecentTest {
    @Test
    public void neverIsNotRecentAndIsDue() {
        assertFalse(Recent.within(0L, Long.MIN_VALUE, 20L));
        assertFalse(Recent.within(1_000_000L, Long.MIN_VALUE, 20L));
        assertFalse(Recent.within(Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE));
        assertTrue(Recent.due(0L, Long.MIN_VALUE, 20L));
        assertTrue(Recent.due(1_000_000L, Long.MIN_VALUE, 20L));
        assertTrue(Recent.due(System.currentTimeMillis(), Long.MIN_VALUE, 5_000L));
    }

    @Test
    public void anIntClockNeverSetIsNotRecentAndIsDue() {
        assertFalse(Recent.within(0, Integer.MIN_VALUE, 40));
        assertFalse(Recent.within(39, Integer.MIN_VALUE, 40));
        assertFalse(Recent.within(Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertTrue(Recent.due(0, Integer.MIN_VALUE, 100));
        assertTrue(Recent.due(99, Integer.MIN_VALUE, 100));
        assertTrue(Recent.due(Integer.MAX_VALUE, Integer.MIN_VALUE, 100));
    }

    @Test
    public void bothEdgesOfTheSpan() {
        assertTrue(Recent.within(100L, 100L, 20L));
        assertTrue(Recent.within(119L, 100L, 20L));
        assertFalse(Recent.within(120L, 100L, 20L));
        assertFalse(Recent.due(100L, 100L, 20L));
        assertFalse(Recent.due(119L, 100L, 20L));
        assertTrue(Recent.due(120L, 100L, 20L));
        assertTrue(Recent.within(100, 100, 20));
        assertTrue(Recent.within(119, 100, 20));
        assertFalse(Recent.within(120, 100, 20));
        assertTrue(Recent.due(120, 100, 20));
    }

    @Test
    public void aClockAtZero() {
        assertTrue(Recent.within(0L, 0L, 20L));
        assertTrue(Recent.within(19L, 0L, 20L));
        assertTrue(Recent.due(20L, 0L, 20L));
        assertTrue(Recent.within(0, 0, 40));
        assertFalse(Recent.due(0, 0, 40));
        assertTrue(Recent.due(40, 0, 40));
    }

    @Test
    public void aLargeNow() {
        long ms = 1_789_000_000_000L;
        assertTrue(Recent.within(ms + 4_999, ms, 5_000L));
        assertFalse(Recent.within(ms + 5_000, ms, 5_000L));
        assertTrue(Recent.due(Long.MAX_VALUE, 0L, 20L));
        assertFalse(Recent.within(Long.MAX_VALUE, 0L, 20L));
        assertTrue(Recent.within(Long.MAX_VALUE, Long.MAX_VALUE - 1, 20L));
        assertTrue(Recent.within(Integer.MAX_VALUE, Integer.MAX_VALUE - 39, 40));
        assertFalse(Recent.within(Integer.MAX_VALUE, Integer.MAX_VALUE - 40, 40));
        // a start far back that is not never stays a plain number
        assertFalse(Recent.within(0, Integer.MIN_VALUE + 1, 40));
        assertTrue(Recent.due(0, Integer.MIN_VALUE / 2, 40));
    }

    // time going back (lag, a relog, another world): never recent, always due, a tick back or a day
    @Test
    public void aClockInTheFutureIsNotRecentAndIsDue() {
        assertFalse(Recent.within(99L, 100L, 20L));
        assertFalse(Recent.within(0L, 100L, 20L));
        assertFalse(Recent.within(100L, 50_000L, 20L));
        assertTrue(Recent.due(99L, 100L, 20L));
        assertTrue(Recent.due(100L, 50_000L, 20L));
        assertFalse(Recent.within(99, 100, 40));
        assertTrue(Recent.due(99, 100, 40));
        assertTrue(Recent.due(-5, 0, 40));
    }
}
