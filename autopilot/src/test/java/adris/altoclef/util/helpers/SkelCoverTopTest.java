package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// (pillbox_top_support) test heel: on flat bare ground the pillbox's top had nothing to go against ("1 of 5 cells shut, no top"),
// and off the cell's middle after a knock the body stood in the cells toward the skeleton. the support goes on the first wall
// that is up with a free cell over it, the skeleton's side first; the click waits for the eye over the wall's top; the body goes
// to the middle first
public class SkelCoverTopTest {

    @Test
    public void theSupportGoesOnTheFirstWallUpWithRoomOverIt() {
        // the skeleton's side not up yet, the next one up with room over it
        assertEquals(1, SkelCover.topSupportSide(false, new boolean[]{false, true, true, true}, new boolean[]{true, true, true, true}));
        // the skeleton's side up and free over it: that one
        assertEquals(0, SkelCover.topSupportSide(false, new boolean[]{true, true, true, true}, new boolean[]{true, true, true, true}));
        // no room over the walls that are up
        assertEquals(-1, SkelCover.topSupportSide(false, new boolean[]{true, false, false, true}, new boolean[]{false, true, true, false}));
        // the top has a face already: no support
        assertEquals(-1, SkelCover.topSupportSide(true, new boolean[]{true, true, true, true}, new boolean[]{true, true, true, true}));
    }

    @Test
    public void theClickWaitsForTheEyeOverTheWallsTop() {
        // standing: the eye 1.62 over the feet at y 64, the wall's top at 66
        assertFalse(SkelCover.eyeOverTop(65.62, 66));
        // sneaking at the top of a jump: 64 + 1.25 + 1.27
        assertTrue(SkelCover.eyeOverTop(66.52, 66));
        assertFalse(SkelCover.eyeOverTop(66.05, 66));
    }

    @Test
    public void offTheMiddleIsMoreThanTheSlackOnAnAxis() {
        // the knock in test heel: 0.64 and 0.28 off the middle, the body in two side cells
        assertTrue(SkelCover.offCentre(41337.14, 2577.78, 41337, 2577, SkelCover.CENTRE_SLACK));
        assertFalse(SkelCover.offCentre(41337.6, 2577.4, 41337, 2577, SkelCover.CENTRE_SLACK));
        // the slack keeps half the body inside the cell
        assertTrue(0.3 + SkelCover.CENTRE_SLACK < 0.5);
    }
}
