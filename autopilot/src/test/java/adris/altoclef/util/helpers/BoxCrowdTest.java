package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the brew box not opened into a crowd of blazes (box_crowd_hold): BoxCrowd
public class BoxCrowdTest {

    // a long potion just drunk: eight minutes
    private static final int LONG = 9600;

    @Test
    public void theSwitchOffNeverHolds() {
        assertFalse(BoxCrowd.holds(false, LONG, 7, 0));
    }

    @Test
    public void threeOrMoreWithinFourHoldTheBoxShut() {
        assertEquals(4, BoxCrowd.CROWD_RADIUS, 0);
        assertFalse(BoxCrowd.holds(true, LONG, 0, 0));
        assertFalse(BoxCrowd.holds(true, LONG, 2, 0));
        assertTrue(BoxCrowd.holds(true, LONG, 3, 0));
        // the death read: seven within six, three within four at the drink
        assertTrue(BoxCrowd.holds(true, LONG, 7, 100));
    }

    @Test
    public void theHoldEndsWhenFewerAreClose() {
        assertTrue(BoxCrowd.holds(true, LONG, 3, 300));
        assertFalse(BoxCrowd.holds(true, LONG, 2, 301));
        assertEquals("the crowd down to 2 within 4", BoxCrowd.endWhy(LONG, 2, 301));
    }

    @Test
    public void theHoldLastsAMinuteAtMost() {
        assertTrue(BoxCrowd.holds(true, LONG, 6, BoxCrowd.HOLD_TICKS - 1));
        assertFalse(BoxCrowd.holds(true, LONG, 6, BoxCrowd.HOLD_TICKS));
        assertEquals(1200, BoxCrowd.HOLD_TICKS);
        assertEquals("held 60 s, the cap, 6 still within 4", BoxCrowd.endWhy(LONG, 6, BoxCrowd.HOLD_TICKS));
    }

    @Test
    public void noHoldWithoutMoreThanAMinuteOfTheEffect() {
        // no effect, or a minute or less of it left: the fight needs the potion more than the wait
        assertFalse(BoxCrowd.holds(true, 0, 7, 0));
        assertFalse(BoxCrowd.holds(true, BoxCrowd.EFFECT_FLOOR, 7, 0));
        assertTrue(BoxCrowd.holds(true, BoxCrowd.EFFECT_FLOOR + 1, 7, 0));
        // an endless one
        assertTrue(BoxCrowd.holds(true, Integer.MAX_VALUE, 7, 0));
        assertEquals("fire resistance down to 60 s", BoxCrowd.endWhy(BoxCrowd.EFFECT_FLOOR, 7, 40));
    }
}
