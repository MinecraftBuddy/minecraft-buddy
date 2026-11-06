package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import org.junit.Test;

public class WatchdogTest {

    @Test
    public void quietAtTheStart() {
        Watchdog w = new Watchdog();
        w.reset(1000);
        assertTrue(w.check(1000, 8, 120).ok());
        assertTrue(w.check(1100, 8, 120).ok());
    }

    @Test
    public void budgetRunsOutInGameMinutes() {
        Watchdog w = new Watchdog();
        w.reset(100);
        // stall is off here, the budget is the only clock
        assertTrue(w.check(100 + 480, 8, 0).ok());
        Watchdog.Verdict v = w.check(100 + 481, 8, 0);
        assertEquals(Watchdog.Result.BUDGET, v.result());
        assertTrue(v.reason(), v.reason().contains("8 minutes"));
    }

    @Test
    public void pausingTheBudgetLeavesTheStallTimerRunning() {
        Watchdog w = new Watchdog();
        w.reset(0);
        // a 10 minute walk the handler asked us not to bill: the 6 minute budget is untouched, the stall timer is not
        w.pauseBudget(600);
        assertTrue(w.check(700, 6, 0).ok());
        assertEquals(Watchdog.Result.BUDGET, w.check(961, 6, 0).result());
        assertEquals(Watchdog.Result.STALL, w.check(121, 6, 120).result());
    }

    @Test
    public void stallRunsOutWithoutProgress() {
        Watchdog w = new Watchdog();
        w.reset(0);
        assertTrue(w.check(120, 30, 120).ok());
        Watchdog.Verdict v = w.check(121, 30, 120);
        assertEquals(Watchdog.Result.STALL, v.result());
        assertTrue(v.reason(), v.reason().contains("121 seconds"));
    }

    @Test
    public void progressRestartsTheStallTimerButNotTheBudget() {
        Watchdog w = new Watchdog();
        w.reset(0);
        w.progress(100);
        assertTrue(w.check(200, 30, 120).ok());
        assertEquals(Watchdog.Result.STALL, w.check(221, 30, 120).result());
        // keep making progress for ten minutes: the stall never fires, the 5 minute budget still does
        Watchdog b = new Watchdog();
        b.reset(0);
        for (int t = 10; t <= 290; t += 10) {
            b.progress(t);
            assertTrue(b.check(t, 5, 120).ok());
        }
        b.progress(301);
        assertEquals(Watchdog.Result.BUDGET, b.check(301, 5, 120).result());
    }

    @Test
    public void budgetWinsWhenBothRanOut() {
        Watchdog w = new Watchdog();
        w.reset(0);
        assertEquals(Watchdog.Result.BUDGET, w.check(1000, 1, 10).result());
    }

    @Test
    public void zeroTurnsACheckOff() {
        Watchdog w = new Watchdog();
        w.reset(0);
        // a waiting phase (dragon perch) has no stall timer, a terminal phase has no budget
        assertTrue(w.check(100000, 0, 0).ok());
        assertTrue(w.check(100000, 0, -1).ok());
    }

    @Test
    public void resetStartsAFreshAttempt() {
        Watchdog w = new Watchdog();
        w.reset(0);
        assertEquals(Watchdog.Result.BUDGET, w.check(61, 1, 0).result());
        w.reset(61);
        assertTrue(w.check(100, 1, 120).ok());
        assertEquals(0, w.secondsInAttempt(61), 1e-9);
        assertEquals(39, w.secondsInAttempt(100), 1e-9);
    }

    @Test
    public void pauseGivesBothClocksTheTimeBack() {
        Watchdog w = new Watchdog();
        w.reset(0);
        w.pause(100);
        // 100 s of a death recovery do not count against the budget (1 min) or the stall (120 s)
        assertEquals(60, w.secondsInAttempt(160), 1e-9);
        assertTrue(w.check(160, 1, 120).ok());
        assertEquals(Watchdog.Result.BUDGET, w.check(161, 1, 120).result());
    }

    @Test
    public void excuseForgivesTheStallOnlyAndNeverPastNow() {
        Watchdog w = new Watchdog();
        w.reset(0);
        w.excuse(100, 90);
        assertEquals(10, w.secondsSinceProgress(100), 1e-9);
        // the budget did not move
        assertEquals(100, w.secondsInAttempt(100), 1e-9);
        w.excuse(100, 5000);
        assertEquals(0, w.secondsSinceProgress(100), 1e-9);
    }

    @Test
    public void backdateMovesTheStartBackAndNeverForward() {
        Watchdog w = new Watchdog();
        w.reset(500);
        w.backdate(200);
        assertEquals(400, w.secondsInAttempt(600), 1e-9);
        // the stall timer started at the reset, not at the old start
        assertEquals(100, w.secondsSinceProgress(600), 1e-9);
        w.backdate(550);
        assertEquals(400, w.secondsInAttempt(600), 1e-9);
    }

    @Test
    public void readsTheBudgetFromTheConfigAndTheStallFromTheHandler() {
        GamerConfig cfg = new GamerConfig();
        cfg.budgets.eyes = 3;
        FakeHandler h = new FakeHandler(GamerPhase.EYES);
        h.stall = 0;
        Watchdog w = new Watchdog();
        w.reset(0);
        assertTrue(w.check(180, GamerPhase.EYES, cfg, h).ok());
        assertEquals(Watchdog.Result.BUDGET, w.check(181, GamerPhase.EYES, cfg, h).result());
        // terminal phases have no budget at all
        assertTrue(w.check(99999, GamerPhase.DONE, cfg, h).ok());
    }
}
