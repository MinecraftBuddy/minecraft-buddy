package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

// clock_no_step_back: the server's time behind the client's is held out for as many ticks, up to MAX_HOLD
public class ClockStepTest {

    @Test
    public void aStepBackIsHeldOut() {
        assertEquals(0, ClockStep.hold(1000, 1000));
        assertEquals(0, ClockStep.hold(1000, 1005));
        assertEquals(3, ClockStep.hold(1000, 997));
        assertEquals(ClockStep.MAX_HOLD, ClockStep.hold(5000, 5000 - ClockStep.MAX_HOLD));
        assertEquals(-1, ClockStep.hold(5000, 5000 - ClockStep.MAX_HOLD - 1));
    }
}
