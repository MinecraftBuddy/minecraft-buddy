package adris.altoclef.butler;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ButlerStopLineTest {

    @Test
    public void onlyAStopOrCancelFirstWordCounts() {
        assertTrue(Butler.isStopLine("stop"));
        assertTrue(Butler.isStopLine("  CANCEL  "));
        assertTrue(Butler.isStopLine("stop ; get log 4"));
        assertFalse(Butler.isStopLine("get stop"));
        assertFalse(Butler.isStopLine("stopper"));
        assertFalse(Butler.isStopLine(""));
    }
}
