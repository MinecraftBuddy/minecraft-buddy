package adris.altoclef.trackers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MiscBlockTrackerTest {

    @Test
    public void dryLandIsFeetDownAndNotWet() {
        assertTrue(MiscBlockTracker.isDryLand(true, false, false));
        // swimming, and sunk to the sea floor (onGround and wet)
        assertFalse(MiscBlockTracker.isDryLand(false, true, false));
        assertFalse(MiscBlockTracker.isDryLand(true, true, false));
        // a boat deck on open sea is not land
        assertFalse(MiscBlockTracker.isDryLand(true, false, true));
        // mid jump
        assertFalse(MiscBlockTracker.isDryLand(false, false, false));
    }
}
