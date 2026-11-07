package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

// FireOnTheWay.skipFires (Tuning fr_skip_fires): under fire resistance with ten seconds or more left, no fire is put out
public class FireOnTheWayTest {

    @Test
    public void theSwitchOffPutsOutEveryFire() {
        assertEquals(false, FireOnTheWay.skipFires(0, 0));
        assertEquals(false, FireOnTheWay.skipFires(0, 3600));
        assertEquals(false, FireOnTheWay.skipFires(0, Integer.MAX_VALUE));
        assertEquals(false, FireOnTheWay.skipFires(-1, 3600));
    }

    @Test
    public void theSwitchOnSkipsOnlyWithTenSecondsOfThePotionLeft() {
        // no potion
        assertEquals(false, FireOnTheWay.skipFires(1, 0));
        // nearly gone: the fire would still burn when it ends
        assertEquals(false, FireOnTheWay.skipFires(1, FireOnTheWay.SKIP_MIN_TICKS - 1));
        assertEquals(true, FireOnTheWay.skipFires(1, FireOnTheWay.SKIP_MIN_TICKS));
        assertEquals(true, FireOnTheWay.skipFires(1, 3600));
        // an endless effect
        assertEquals(true, FireOnTheWay.skipFires(1, Integer.MAX_VALUE));
    }

    @Test
    public void tenSecondsIs200Ticks() {
        assertEquals(200, FireOnTheWay.SKIP_MIN_TICKS);
    }
}
