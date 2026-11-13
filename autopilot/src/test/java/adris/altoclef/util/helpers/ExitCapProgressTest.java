package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

// Tuning exit_cap_progress: past the exit's cap the walk out goes on while it came closer lately, and twice the cap ends it
public class ExitCapProgressTest {

    private static final long CAP = 600 * 20;

    @Test
    public void noPortalKnownIsGivenUpAtOnce() {
        assertEquals("no portal known", RouteRules.exitGiveUp(false, 0, CAP, 0));
    }

    @Test
    public void underTheCapItGoesOn() {
        assertNull(RouteRules.exitGiveUp(true, CAP - 1, CAP, CAP - 1));
    }

    // route-61-mc3 run 25: 640 blocks at 0.9 a second, still coming closer at the cap
    @Test
    public void pastTheCapAWalkThatCameCloserLatelyGoesOn() {
        assertNull(RouteRules.exitGiveUp(true, CAP + 20 * 60, CAP, 20 * 30));
    }

    @Test
    public void pastTheCapAWalkNoCloserInTwoMinutesIsGivenUp() {
        String why = RouteRules.exitGiveUp(true, CAP + 20 * 60, CAP, 120 * 20);
        assertEquals("not out in 660 s, no closer in 120 s", why);
    }

    @Test
    public void twiceTheCapEndsItWhatever() {
        assertEquals("not out in 1200 s", RouteRules.exitGiveUp(true, 2 * CAP, CAP, 0));
    }
}
