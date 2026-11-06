package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CampPingerTest {
    @Test
    public void pingsEveryInterval() {
        CampPinger p = new CampPinger(30);
        assertFalse(p.ping(10, true));
        assertTrue(p.ping(30, true));
        assertFalse(p.ping(59, true));
        assertTrue(p.ping(60, true));
    }

    @Test
    public void silentWhenNotCamping() {
        CampPinger p = new CampPinger(30);
        assertFalse(p.ping(100, false));
        assertFalse(p.ping(500, false));
    }

    // the clock of a retry starts at 0 again: a pinger from the last attempt (last ping at 500) would sit quiet until
    // 530, so the phase makes a new one in onEnter, and a new one pings on time
    @Test
    public void aFreshPingerAfterARetryPingsOnTime() {
        CampPinger old = new CampPinger(30);
        assertTrue(old.ping(500, true));
        assertFalse(old.ping(31, true));
        CampPinger fresh = new CampPinger(30);
        assertTrue(fresh.ping(31, true));
    }
}
