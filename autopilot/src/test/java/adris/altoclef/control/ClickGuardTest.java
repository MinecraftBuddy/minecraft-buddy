package adris.altoclef.control;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClickGuardTest {

    @Test
    public void aBlockUnderTheCrosshairMayBeClicked() {
        assertTrue(ClickGuard.allowLeftHold(false));
    }

    // fire behind a zombified piglin: the click waits for the piglin, it does not become a swing at it
    @Test
    public void anEntityUnderTheCrosshairHoldsTheClickBack() {
        assertFalse(ClickGuard.allowLeftHold(true));
    }
}
