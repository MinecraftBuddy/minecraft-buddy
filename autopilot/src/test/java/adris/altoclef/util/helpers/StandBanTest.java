package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class StandBanTest {
    @After
    public void clear() {
        StandBan.reset();
    }

    // phase 6's no-path loop (12 Nov 2026): the second blaze banned from one stand within a minute holds that stand 30 s
    @Test
    public void theSecondBanFromOneStandHoldsIt() {
        assertFalse(StandBan.noted(0, 10, 64, 10));
        assertFalse(StandBan.holds(10, 10, 64, 10));
        assertTrue(StandBan.noted(200, 11, 64, 10));
        assertTrue(StandBan.holds(300, 10.5, 64, 10));
        // another stand is free, and the hold ends after 30 s
        assertFalse(StandBan.holds(300, 20, 64, 10));
        assertFalse(StandBan.holds(200 + 600, 11, 64, 10));
    }

    // two bans a minute and more apart, or from stands apart, hold nothing
    @Test
    public void bansApartHoldNothing() {
        assertFalse(StandBan.noted(0, 10, 64, 10));
        assertFalse(StandBan.noted(1300, 10, 64, 10));
        assertFalse(StandBan.noted(1400, 30, 64, 10));
        assertFalse(StandBan.holds(1500, 10, 64, 10));
    }

    // look 69: the bans that made a hold are spent with it; one ban after the hold does not hold the stand again, two do
    @Test
    public void aHoldNeedsTwoNewBansAfterIt() {
        assertFalse(StandBan.noted(0, 10, 64, 10));
        assertTrue(StandBan.noted(100, 10, 64, 10));
        long after = 100 + StandBan.HOLD + 1;
        assertFalse(StandBan.holds(after, 10, 64, 10));
        assertFalse(StandBan.noted(after, 10, 64, 10));
        assertFalse(StandBan.holds(after + 1, 10, 64, 10));
        assertTrue(StandBan.noted(after + 40, 10, 64, 10));
    }
}
