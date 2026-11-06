package adris.altoclef.trace;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MonitorErrorsTest {

    @Test
    public void theFirstThrowFromAMonitorIsToldWithClassAndMessage() {
        MonitorErrors errors = new MonitorErrors();
        assertEquals("MONITOR: vitals threw IllegalStateException: no player (said once, it keeps being skipped)",
                errors.first("vitals", new IllegalStateException("no player")));
    }

    @Test
    public void theSecondOneFromTheSameMonitorIsNot() {
        MonitorErrors errors = new MonitorErrors();
        errors.first("vitals", new RuntimeException("a"));
        assertNull(errors.first("vitals", new RuntimeException("b")));
    }

    @Test
    public void eachMonitorGetsItsOwnFirst() {
        MonitorErrors errors = new MonitorErrors();
        errors.first("vitals", new RuntimeException("a"));
        assertEquals("MONITOR: cursor threw NullPointerException: null (said once, it keeps being skipped)",
                errors.first("cursor", new NullPointerException()));
    }
}
