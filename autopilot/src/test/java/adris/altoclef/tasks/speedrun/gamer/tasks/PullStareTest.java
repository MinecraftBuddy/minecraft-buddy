package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.EndermanNook;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PullStareTest {
    @Test
    public void aCalmVisibleOneInRangeIsACandidate() {
        assertTrue(PullStare.candidate(30, true, true, true, false, false));
        assertTrue(PullStare.candidate(12, true, true, true, false, false));
        assertTrue(PullStare.candidate(48, true, true, true, false, false));
    }

    @Test
    public void eachGateTurnsItDown() {
        assertFalse("too close, the walk up is cheap", PullStare.candidate(11.9, true, true, true, false, false));
        assertFalse("too far", PullStare.candidate(48.1, true, true, true, false, false));
        assertFalse("dead", PullStare.candidate(30, false, true, true, false, false));
        assertFalse("already angry", PullStare.candidate(30, true, false, true, false, false));
        assertFalse("behind a hill", PullStare.candidate(30, true, true, false, false, false));
        assertFalse("ignored us lately", PullStare.candidate(30, true, true, true, true, false));
        assertFalse("the loot walk is on it", PullStare.candidate(30, true, true, true, false, true));
    }

    @Test
    public void onlyTheNetherOffARimOutOfCooldown() {
        assertTrue(PullStare.allowed(true, false, false));
        assertFalse(PullStare.allowed(false, false, false));
        assertFalse(PullStare.allowed(true, true, false));
        assertFalse(PullStare.allowed(true, false, true));
    }

    @Test
    public void hpFireAndLavaGatesComeFromTheNookRules() {
        // the hunt asks stareWorthwhile with nothing else angry
        assertTrue(EndermanNook.stareWorthwhile(false, 20, true, 0, 30, true, false, true, false));
        assertFalse("hurt", EndermanNook.stareWorthwhile(false, 10, true, 0, 30, true, false, true, false));
        assertFalse("burning", EndermanNook.stareWorthwhile(true, 20, true, 0, 30, true, false, true, false));
        assertFalse("lava by us", EndermanNook.stareWorthwhile(false, 20, false, 0, 30, true, false, true, false));
        assertFalse("lava between at close range", EndermanNook.stareWorthwhile(false, 20, true, 0, 14, true, false, true, true));
        assertTrue("lava between is fine from far, it teleports", EndermanNook.stareWorthwhile(false, 20, true, 0, 30, true, false, true, true));
    }

    @Test
    public void aStareGetsItsTicksThenCountsAsIgnored() {
        PullStare s = new PullStare();
        for (int t = 0; t < PullStare.STARE_TICKS; t++) {
            assertTrue(s.stare(7, t));
        }
        assertFalse(s.stare(7, PullStare.STARE_TICKS));
        assertTrue("skipped for a while", s.skipped(7, PullStare.STARE_TICKS + 1));
        assertFalse("and not forever", s.skipped(7, PullStare.STARE_TICKS + PullStare.SKIP_TICKS + 1));
        assertEquals(-1, s.staringAt());
    }

    @Test
    public void twoIgnoredInARowStopTheStaresForAWhile() {
        PullStare s = new PullStare();
        long now = ignore(s, 1, 0);
        assertFalse(s.cooling(now));
        now = ignore(s, 2, now + 1);
        assertTrue(s.cooling(now));
        assertTrue(s.cooling(now + PullStare.COOLDOWN_TICKS - 1));
        assertFalse(s.cooling(now + PullStare.COOLDOWN_TICKS));
    }

    @Test
    public void anAnsweredStareBreaksTheStreak() {
        PullStare s = new PullStare();
        long now = ignore(s, 1, 0);
        s.stare(2, now + 1);
        assertEquals(1, s.answered(2, now + 2));
        now = ignore(s, 3, now + 3);
        assertFalse("one ignored since the answer is not a streak", s.cooling(now));
    }

    @Test
    public void anAnswerForSomeoneElseOrAStaleStareIsNotOurs() {
        PullStare s = new PullStare();
        s.stare(5, 100);
        assertEquals(-1, s.answered(6, 101));
        assertEquals(-1, s.answered(5, 100 + PullStare.STALE_TICKS + 1));
    }

    @Test
    public void aStareThatWasInterruptedStartsOver() {
        PullStare s = new PullStare();
        for (int t = 0; t < 90; t++) {
            s.stare(4, t);
        }
        // a gap (food trip, a fight): the count does not carry over
        for (int t = 1000; t < 1000 + PullStare.STARE_TICKS; t++) {
            assertTrue(s.stare(4, t));
        }
    }

    @Test
    public void lavaTargetsArePassedOverOnlyWhenADryOneExists() {
        assertTrue(PullStare.lavaFilterSkips(true, true));
        assertFalse("everything is by lava, take one anyway", PullStare.lavaFilterSkips(true, false));
        assertFalse(PullStare.lavaFilterSkips(false, true));
        assertFalse(PullStare.lavaFilterSkips(false, false));
    }

    private static long ignore(PullStare s, int id, long from) {
        long t = from;
        while (s.stare(id, t)) {
            t++;
        }
        return t;
    }
}
