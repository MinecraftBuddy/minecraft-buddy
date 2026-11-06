package adris.altoclef.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DumpCooldownTest {
    @Before
    public void fresh() {
        Trace.forgetCooldown();
    }

    @After
    public void putTheSinkBack() {
        Trace.setSink(null);
        Trace.forgetCooldown();
    }

    @Test
    public void aMonitorDumpGoesOutOncePerMinute() {
        DumpCooldown c = new DumpCooldown();
        assertTrue(c.allow("monitor:storm", 1_000));
        assertFalse(c.allow("monitor:storm", 30_000));
        // a different monitor shares the window
        assertFalse(c.allow("monitor:cursor", 60_999));
        assertTrue(c.allow("monitor:cursor", 61_000));
    }

    @Test
    public void aRefusedDumpDoesNotRestartTheWindow() {
        DumpCooldown c = new DumpCooldown();
        assertTrue(c.allow("monitor:storm", 0));
        assertFalse(c.allow("monitor:storm", 59_000));
        assertTrue(c.allow("monitor:storm", 60_001));
    }

    @Test
    public void deathStuckAndCommandDumpsAreNeverLimited() {
        DumpCooldown c = new DumpCooldown();
        assertTrue(c.allow("monitor:storm", 0));
        for (String reason : new String[] {"respawn", "stuck: no progress", "command", null}) {
            assertTrue(reason, c.allow(reason, 1));
            assertTrue(reason, c.allow(reason, 2));
        }
        // and they do not use up the monitor window either
        assertFalse(c.allow("monitor:storm", 3));
    }

    @Test
    public void aClockThatWentBackwardsStartsANewWindow() {
        DumpCooldown c = new DumpCooldown();
        assertTrue(c.allow("monitor:storm", 100_000));
        assertTrue(c.allow("monitor:storm", 5_000));
    }

    @Test
    public void theTraceDumpHonoursIt() {
        List<String> out = new ArrayList<>();
        Trace.setSink(out::add);
        Trace.dump("monitor:storm", 0);
        int first = out.size();
        assertTrue(first > 0);
        Trace.dump("monitor:storm", 10_000);
        assertEquals(first, out.size());
        Trace.dump("command", 10_001);
        assertTrue(out.size() > first);
        int afterCommand = out.size();
        Trace.dump("monitor:storm", 60_000);
        assertTrue(out.size() > afterCommand);
    }
}
