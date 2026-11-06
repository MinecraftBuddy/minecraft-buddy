package adris.altoclef.tasks.movement;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CalcFailureWatchTest {

    @Test
    public void unarmedNeverFails() {
        CalcFailureWatch w = new CalcFailureWatch();
        assertFalse(w.failed(0));
        assertFalse(w.failed(7));
    }

    @Test
    public void quietWhileTheCounterStaysPut() {
        CalcFailureWatch w = new CalcFailureWatch();
        w.arm(4);
        assertFalse(w.failed(4));
        assertFalse(w.failed(4));
    }

    // the cod: one search came back empty, we hear about it exactly once
    @Test
    public void oneFailureIsReportedOnce() {
        CalcFailureWatch w = new CalcFailureWatch();
        w.arm(4);
        assertTrue(w.failed(5));
        assertFalse(w.failed(5));
    }

    @Test
    public void failuresFromBeforeWeArmedAreNotOurs() {
        CalcFailureWatch w = new CalcFailureWatch();
        w.arm(9);
        assertFalse(w.failed(9));
        assertTrue(w.failed(10));
    }

    @Test
    public void rearmingForgetsWhatHappenedInBetween() {
        CalcFailureWatch w = new CalcFailureWatch();
        w.arm(1);
        w.arm(3);
        assertFalse(w.failed(3));
        assertTrue(w.failed(4));
    }

    @Test
    public void disarmedStopsListening() {
        CalcFailureWatch w = new CalcFailureWatch();
        w.arm(1);
        w.disarm();
        assertFalse(w.failed(2));
    }

    @Test
    public void severalFailuresAtOnceStillOnlyCountAsOneHeadsUp() {
        CalcFailureWatch w = new CalcFailureWatch();
        w.arm(0);
        assertTrue(w.failed(3));
        assertFalse(w.failed(3));
    }
}
