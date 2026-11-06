package baritone.pathing.movement;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NoThrowawayLogGateTest {

    @Before
    public void fresh() {
        NoThrowawayLogGate.reset();
    }

    @Test
    public void theFirstCallLogsEvenAtTimeZero() {
        assertTrue(NoThrowawayLogGate.due(0));
    }

    // the log had ~300 of these in 8 seconds, one per fresh movement
    @Test
    public void aBurstOfCallsPrintsOneLine() {
        assertTrue(NoThrowawayLogGate.due(1000));
        for (long t = 1001; t < 6000; t += 7) {
            assertFalse("at " + t, NoThrowawayLogGate.due(t));
        }
    }

    @Test
    public void itOpensAgainAfterTheInterval() {
        assertTrue(NoThrowawayLogGate.due(1000));
        assertFalse(NoThrowawayLogGate.due(1000 + NoThrowawayLogGate.INTERVAL_MS - 1));
        assertTrue(NoThrowawayLogGate.due(1000 + NoThrowawayLogGate.INTERVAL_MS));
        assertFalse(NoThrowawayLogGate.due(1000 + NoThrowawayLogGate.INTERVAL_MS + 1));
    }
}
