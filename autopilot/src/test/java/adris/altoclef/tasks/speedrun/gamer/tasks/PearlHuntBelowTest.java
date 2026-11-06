package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PearlHuntBelowTest {
    @Test
    public void acquiringSkipsPastSixBelow() {
        assertFalse(PearlHuntTask.tooFarBelow(-6, false, true));
        assertTrue(PearlHuntTask.tooFarBelow(-6.01, false, true));
    }

    @Test
    public void chasingKeepsUntilTenBelow() {
        assertFalse(PearlHuntTask.tooFarBelow(-8, true, true));
        assertFalse(PearlHuntTask.tooFarBelow(-10, true, true));
        assertTrue(PearlHuntTask.tooFarBelow(-10.01, true, true));
    }

    @Test
    public void aboveOrLevelNeverSkips() {
        assertFalse(PearlHuntTask.tooFarBelow(0, false, true));
        assertFalse(PearlHuntTask.tooFarBelow(25, true, true));
    }

    @Test
    public void outsideTheNetherNeverSkips() {
        assertFalse(PearlHuntTask.tooFarBelow(-40, false, false));
        assertFalse(PearlHuntTask.tooFarBelow(-40, true, false));
    }
}
