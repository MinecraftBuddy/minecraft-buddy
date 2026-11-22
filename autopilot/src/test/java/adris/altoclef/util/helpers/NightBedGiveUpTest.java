package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// (Tuning bed_failed_slept) which night trip give-ups shut the bed in the bag out for the night (bed_failed_hole)
public class NightBedGiveUpTest {

    @Test
    public void aNightThatDidNotPassCounts() {
        assertTrue(NightSleepHook.countsAgainstTheBed(true, true, true));
    }

    @Test
    public void thePlaceWithTriesLeftDoesNot() {
        // "standing in liquid" before lying down, the next try comes in 20 s (look 220)
        assertFalse(NightSleepHook.countsAgainstTheBed(true, false, true));
    }

    @Test
    public void thePlaceWithNoTryLeftCounts() {
        assertTrue(NightSleepHook.countsAgainstTheBed(true, false, false));
    }

    @Test
    public void offEveryGiveUpCounts() {
        assertTrue(NightSleepHook.countsAgainstTheBed(false, false, true));
    }
}
