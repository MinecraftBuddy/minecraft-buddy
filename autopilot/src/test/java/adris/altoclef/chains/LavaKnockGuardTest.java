package adris.altoclef.chains;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LavaKnockGuardTest {
    @Test
    public void burningAloneStaysArmed() {
        assertFalse(LavaKnockGuard.standsDown(false, false));
    }

    @Test
    public void lavaOrNoPlayerStandsDown() {
        assertTrue(LavaKnockGuard.standsDown(false, true));
        assertTrue(LavaKnockGuard.standsDown(true, false));
    }
}
