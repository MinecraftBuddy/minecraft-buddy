package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OceanRuleTest {

    @Test
    public void theFirstAnswerIsThePlainLine() {
        assertEquals(true, OceanRule.atSurface(null, 48));
        assertEquals(false, OceanRule.atSurface(null, 47));
    }

    @Test
    public void aStepBackAndForthAcrossTheLineChangesNothing() {
        // a path down set off at 47 and its first move went to 48, the wander took it back to 47
        OceanRule.Latch below = new OceanRule.Latch();
        assertEquals(false, below.atSurface(47));
        for (int i = 0; i < 20; i++) {
            assertEquals(false, below.atSurface(48));
            assertEquals(false, below.atSurface(47));
        }
        OceanRule.Latch above = new OceanRule.Latch();
        assertEquals(true, above.atSurface(60));
        for (int i = 0; i < 20; i++) {
            assertEquals(true, above.atSurface(47));
            assertEquals(true, above.atSurface(48));
        }
    }

    @Test
    public void clearlyPastTheLineTheAnswerChanges() {
        OceanRule.Latch latch = new OceanRule.Latch();
        assertEquals(true, latch.atSurface(70));
        assertEquals(true, latch.atSurface(45.5));
        assertEquals(false, latch.atSurface(45));
        assertEquals(false, latch.atSurface(49));
        assertEquals(true, latch.atSurface(49.01));
    }
}
