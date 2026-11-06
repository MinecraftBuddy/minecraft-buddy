package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RimRetreatTest {

    @Test
    public void firstLookComesAtOnceWhateverTheClock() {
        assertTrue(RimRetreat.due(0, Long.MIN_VALUE));
        assertTrue(RimRetreat.due(1_234_567_890L, Long.MIN_VALUE));
    }

    @Test
    public void looksEveryFewTicks() {
        assertFalse(RimRetreat.due(1002, 1000));
        assertTrue(RimRetreat.due(1005, 1000));
    }

    @Test
    public void aClockThatWentBackLooksAgain() {
        assertTrue(RimRetreat.due(10, 1000));
    }
}
