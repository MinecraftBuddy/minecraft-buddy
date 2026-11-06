package adris.altoclef.chains;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the lava escape keeps the wheel past the first dry tick, until we stand clear of the lava or the hold runs out
public class LavaEscapeHoldTest {

    @Test
    public void justOutButLavaBesideIsStillTheEscape() {
        // practice run 116: one dry tick, the chain let go, the user task walked back into the fall
        assertTrue(WorldSurvivalChain.holdEscape(false, 1));
        assertTrue(WorldSurvivalChain.holdEscape(false, WorldSurvivalChain.ESCAPE_HOLD_TICKS - 1));
    }

    @Test
    public void clearOfTheLavaLetsGo() {
        assertFalse(WorldSurvivalChain.holdEscape(true, 1));
    }

    @Test
    public void anEscapeThatFindsNoSafeGroundStillLetsGo() {
        assertFalse(WorldSurvivalChain.holdEscape(false, WorldSurvivalChain.ESCAPE_HOLD_TICKS));
        assertFalse(WorldSurvivalChain.holdEscape(false, Long.MAX_VALUE / 2));
    }
}
