package adris.altoclef.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

// the thing standing between a task failing every tick and a wall of chat
public class ChatThrottleTest {

    @Test
    public void sameMessageOncePerWindow() {
        ChatThrottle throttle = new ChatThrottle(10_000, 100);
        assertEquals("oh no", throttle.filter("oh no", 0));
        for (int tick = 1; tick <= 170; ++tick) {
            assertNull(throttle.filter("oh no", tick * 50L));
        }
    }

    @Test
    public void comesBackAfterWindowWithTheCount() {
        ChatThrottle throttle = new ChatThrottle(10_000, 100);
        throttle.filter("oh no", 0);
        for (int i = 0; i < 170; ++i) throttle.filter("oh no", 100 + i);
        assertEquals("oh no (170 more)", throttle.filter("oh no", 10_001));
        // and the count starts over
        assertNull(throttle.filter("oh no", 10_002));
        assertEquals("oh no (1 more)", throttle.filter("oh no", 20_002));
    }

    @Test
    public void differentMessagesAreIndependent() {
        ChatThrottle throttle = new ChatThrottle(10_000, 100);
        assertEquals("a", throttle.filter("a", 0));
        assertEquals("b", throttle.filter("b", 1));
        assertNull(throttle.filter("a", 2));
        assertNull(throttle.filter("b", 3));
    }

    @Test
    public void streamOfUniqueMessagesIsCapped() {
        ChatThrottle throttle = new ChatThrottle(10_000, 5);
        int shown = 0;
        // "failed 1", "failed 2"... every tick for 5 seconds
        for (int tick = 0; tick < 100; ++tick) {
            if (throttle.filter("failed " + tick, tick * 50L) != null) shown++;
        }
        assertEquals(5, shown);
        // window passes, chat opens up again
        assertNotNull(throttle.filter("failed later", 20_000));
    }

    @Test
    public void rememberedMessagesAreBounded() {
        ChatThrottle throttle = new ChatThrottle(1_000_000, 10_000);
        for (int i = 0; i < 1000; ++i) throttle.filter("m" + i, i);
        // the oldest one fell out of memory so it is allowed through again, nothing blew up
        assertEquals("m0", throttle.filter("m0", 2000));
        // the newest one is still remembered
        assertNull(throttle.filter("m999", 2001));
    }

    @Test
    public void multiLineMessagesKeepTheirFirstLine() {
        assertEquals("boom", ChatThrottle.firstLine("boom\nat:\nfoo.bar(Baz.java:1)\n"));
        assertEquals("boom", ChatThrottle.firstLine("boom"));
        assertEquals("", ChatThrottle.firstLine(null));
        assertEquals("boom", ChatThrottle.firstLine("boom\r\nat:"));
    }
}
