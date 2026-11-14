package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// (cover_low_dig) since heel-17 the skeleton cover gave up in 4 of 23 runs and the buddy ran in the open at 6 to 7 hp and died:
// at 8 hp or less the give-up digs two down and shuts the top instead, once a cover, where the ground digs
public class SkelCoverLowDigTest {

    @Test
    public void onByDefault() {
        assertEquals(1.0, Tuning.DEFAULTS.get("cover_low_dig"), 0);
    }

    @Test
    public void atEightHpOrLessOnDiggableGroundOnce() {
        assertTrue(SkelCover.lowDig(true, false, 6.5f, true, true));
        assertTrue(SkelCover.lowDig(true, false, 8f, true, true));
        // above 8 hp: the give-up as before
        assertFalse(SkelCover.lowDig(true, false, 8.5f, true, true));
        // dug in once already in this cover
        assertFalse(SkelCover.lowDig(true, true, 6.5f, true, true));
        // in the air after a knock, or ground that does not dig
        assertFalse(SkelCover.lowDig(true, false, 6.5f, false, true));
        assertFalse(SkelCover.lowDig(true, false, 6.5f, true, false));
        // the key off
        assertFalse(SkelCover.lowDig(false, false, 6.5f, true, true));
    }
}
