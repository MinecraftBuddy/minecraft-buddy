package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// spawner_wait_cap's and lure_cap's rules (BlazeCapRules): when the wait for a wither skeleton pack at the spawner is over,
// when a lure that draws no blaze into reach is given up, the nook and the hold-off on hiding after it, and when the pack
// line is said again
public class BlazeCapRulesTest {
    private static final long SPAWNER = 11L;
    private static final long OTHER = 12L;

    @Test
    public void secondsAreTicksAndNoneIsOff() {
        assertEquals(0, BlazeCapRules.ticks(0));
        assertEquals(0, BlazeCapRules.ticks(-5));
        assertEquals(1800, BlazeCapRules.ticks(90));
    }

    @Test
    public void theWaitHoldsTillTheCapThenIsOverAtThatSpawner() {
        BlazeCapRules.SpawnerWait w = new BlazeCapRules.SpawnerWait();
        for (long t = 1000; t < 1004; t++) assertEquals(BlazeCapRules.Wait.HOLD, w.tick(SPAWNER, t, 5));
        assertEquals(BlazeCapRules.Wait.CAP, w.tick(SPAWNER, 1004, 5));
        // (said once, then over for good at this spawner, the pack still there or back)
        assertEquals(BlazeCapRules.Wait.PAST, w.tick(SPAWNER, 1005, 5));
        assertEquals(BlazeCapRules.Wait.PAST, w.tick(SPAWNER, 9000, 5));
    }

    @Test
    public void theWaitCountsItsTicksInAllAcrossGaps() {
        // (the count crosses three and back by the second: the walk takes the ticks between, the waits add up)
        BlazeCapRules.SpawnerWait w = new BlazeCapRules.SpawnerWait();
        assertEquals(BlazeCapRules.Wait.HOLD, w.tick(SPAWNER, 1000, 4));
        assertEquals(BlazeCapRules.Wait.HOLD, w.tick(SPAWNER, 1001, 4));
        assertEquals(BlazeCapRules.Wait.HOLD, w.tick(SPAWNER, 1500, 4));
        // (a second ask in the same game tick is the same tick)
        assertEquals(BlazeCapRules.Wait.HOLD, w.tick(SPAWNER, 1500, 4));
        assertEquals(BlazeCapRules.Wait.CAP, w.tick(SPAWNER, 3000, 4));
        assertEquals(4, w.ticks(SPAWNER));
    }

    @Test
    public void anotherSpawnerWaitsAfresh() {
        BlazeCapRules.SpawnerWait w = new BlazeCapRules.SpawnerWait();
        w.tick(SPAWNER, 1000, 2);
        assertEquals(BlazeCapRules.Wait.CAP, w.tick(SPAWNER, 1001, 2));
        assertEquals(BlazeCapRules.Wait.HOLD, w.tick(OTHER, 1002, 2));
        assertEquals(0, w.ticks(SPAWNER));
        assertEquals(1, w.ticks(OTHER));
    }

    @Test
    public void withTheSwitchOffTheWaitHoldsForEver() {
        BlazeCapRules.SpawnerWait w = new BlazeCapRules.SpawnerWait();
        for (long t = 0; t < 20000; t++) assertEquals(BlazeCapRules.Wait.HOLD, w.tick(SPAWNER, t, 0));
    }

    @Test
    public void theWaitsOpeningLineOncePerSpawner() {
        BlazeCapRules.SpawnerWait w = new BlazeCapRules.SpawnerWait();
        assertFalse(w.firstLine(SPAWNER));
        w.tick(SPAWNER, 1000, 100);
        assertTrue(w.firstLine(SPAWNER));
        w.tick(SPAWNER, 1001, 100);
        assertFalse(w.firstLine(SPAWNER));
        w.tick(OTHER, 1002, 100);
        assertTrue(w.firstLine(OTHER));
    }

    @Test
    public void aLureThatDrawsNothingRunsOutAtTheCap() {
        BlazeCapRules.LureWatch w = new BlazeCapRules.LureWatch();
        for (long t = 1000; t < 1009; t++) assertFalse(w.tick(SPAWNER, t, Long.MIN_VALUE, 10));
        assertTrue(w.tick(SPAWNER, 1009, Long.MIN_VALUE, 10));
        assertEquals(10, w.ticks());
    }

    @Test
    public void aBlazeInReachStartsTheClockAgain() {
        BlazeCapRules.LureWatch w = new BlazeCapRules.LureWatch();
        for (long t = 1000; t < 1008; t++) assertFalse(w.tick(SPAWNER, t, Long.MIN_VALUE, 10));
        // (the fight went after one at 1010, the lure had no wheel then; back at 1020 the count starts again)
        assertFalse(w.tick(SPAWNER, 1020, 1010, 10));
        assertEquals(1, w.ticks());
        for (long t = 1021; t < 1029; t++) assertFalse(w.tick(SPAWNER, t, 1010, 10));
        assertTrue(w.tick(SPAWNER, 1029, 1010, 10));
    }

    @Test
    public void aKillBeforeTheLureBeganDoesNotCount() {
        BlazeCapRules.LureWatch w = new BlazeCapRules.LureWatch();
        assertFalse(w.tick(SPAWNER, 1000, 900, 3));
        assertFalse(w.tick(SPAWNER, 1001, 900, 3));
        assertTrue(w.tick(SPAWNER, 1002, 900, 3));
    }

    @Test
    public void onlyTheLuresOwnTicksCountAndAnotherCellIsAfresh() {
        BlazeCapRules.LureWatch w = new BlazeCapRules.LureWatch();
        assertFalse(w.tick(SPAWNER, 1000, Long.MIN_VALUE, 3));
        assertFalse(w.tick(SPAWNER, 1000, Long.MIN_VALUE, 3));
        // (a long time away, hiding or healing, is not the lure's time)
        assertFalse(w.tick(SPAWNER, 5000, Long.MIN_VALUE, 3));
        assertFalse(w.tick(OTHER, 5001, Long.MIN_VALUE, 3));
        assertEquals(1, w.ticks());
        w.reset();
        assertEquals(0, w.ticks());
    }

    @Test
    public void withTheSwitchOffTheLureNeverRunsOut() {
        BlazeCapRules.LureWatch w = new BlazeCapRules.LureWatch();
        for (long t = 0; t < 20000; t++) assertFalse(w.tick(SPAWNER, t, Long.MIN_VALUE, 0));
    }

    @Test
    public void thePackLineOnlyOnAChange() {
        // (the first one; then the same pack from the same spot is quiet, a new size, verdict or spot is said)
        assertTrue(BlazeCapRules.packLine(3, false, Double.POSITIVE_INFINITY, -1, false, 12));
        assertFalse(BlazeCapRules.packLine(3, false, 1.0, 3, false, 12));
        assertTrue(BlazeCapRules.packLine(4, false, 1.0, 3, false, 12));
        assertTrue(BlazeCapRules.packLine(3, true, 1.0, 3, false, 12));
        assertTrue(BlazeCapRules.packLine(3, false, 12.5, 3, false, 12));
    }

    // ---- after a cap: the nook and the hold-off (realfort-pair-camp2 B:7: three caps, each followed within 2 to 6 s by a
    // hide one block over, at danger 4.5 to 6 and 20 hp)

    @Test
    public void theHoldOffKeepsAModerateDangerAtFullHpOutOfHiding() {
        long cap = 10000;
        // (the three re-hides of B:7: 6.0 two seconds after, 4.5 six seconds after, at 20 hp)
        assertTrue(BlazeCapRules.holdOff(cap + 40, cap, BlazeCapRules.HOLD_OFF_TICKS, false, 6.0, 20));
        assertTrue(BlazeCapRules.holdOff(cap + 120, cap, BlazeCapRules.HOLD_OFF_TICKS, false, 4.5, 20));
        assertTrue(BlazeCapRules.holdOff(cap + 40, cap, BlazeCapRules.HOLD_OFF_TICKS, false, 7.9, 14.5f));
    }

    @Test
    public void aHighDangerOrLowHpBreaksTheHoldOff() {
        long cap = 10000;
        assertFalse(BlazeCapRules.holdOff(cap + 40, cap, BlazeCapRules.HOLD_OFF_TICKS, false, BlazeCapRules.HOLD_OFF_DANGER, 20));
        assertEquals("danger", BlazeCapRules.holdBroken(cap + 40, cap, BlazeCapRules.HOLD_OFF_TICKS, false, 10.5, 20));
        assertFalse(BlazeCapRules.holdOff(cap + 40, cap, BlazeCapRules.HOLD_OFF_TICKS, false, 4.5, BlazeCapRules.HOLD_OFF_HEALTH));
        assertEquals("hp", BlazeCapRules.holdBroken(cap + 40, cap, BlazeCapRules.HOLD_OFF_TICKS, false, 4.5, 9));
        assertFalse(BlazeCapRules.holdOff(cap + 40, cap, BlazeCapRules.HOLD_OFF_TICKS, true, 4.5, 20));
        assertEquals("a hit", BlazeCapRules.holdBroken(cap + 40, cap, BlazeCapRules.HOLD_OFF_TICKS, true, 4.5, 20));
        // (while it holds nothing broke it)
        assertEquals(null, BlazeCapRules.holdBroken(cap + 40, cap, BlazeCapRules.HOLD_OFF_TICKS, false, 4.5, 20));
    }

    @Test
    public void theHoldOffEndsAfterItsTimeAndIsNoneBeforeACap() {
        long cap = 10000;
        assertTrue(BlazeCapRules.holdOff(cap + BlazeCapRules.HOLD_OFF_TICKS - 1, cap, BlazeCapRules.HOLD_OFF_TICKS, false, 4.5, 20));
        assertFalse(BlazeCapRules.holdOff(cap + BlazeCapRules.HOLD_OFF_TICKS, cap, BlazeCapRules.HOLD_OFF_TICKS, false, 4.5, 20));
        assertEquals(null, BlazeCapRules.holdBroken(cap + BlazeCapRules.HOLD_OFF_TICKS, cap, BlazeCapRules.HOLD_OFF_TICKS, false, 10.5, 9));
        assertFalse(BlazeCapRules.holdOff(cap, Long.MIN_VALUE, BlazeCapRules.HOLD_OFF_TICKS, false, 4.5, 20));
        assertEquals(60 * 20, BlazeCapRules.HOLD_OFF_TICKS);
    }

    @Test
    public void aHitAfterTheCapEndsTheHoldOffAndANewCapStartsItAgain() {
        BlazeCapRules.HideHold h = new BlazeCapRules.HideHold();
        assertFalse(h.holds(1000, 4.5, 20));
        // (a hit before any cap counts for nothing)
        h.hit();
        h.capped(1000);
        assertTrue(h.holds(1040, 4.5, 20));
        assertEquals(58, h.secondsLeft(1040));
        h.hit();
        assertFalse(h.holds(1041, 4.5, 20));
        assertEquals("a hit", h.broken(1041, 4.5, 20));
        h.capped(5000);
        assertTrue(h.holds(5001, 4.5, 20));
        assertFalse(h.holds(5000 + BlazeCapRules.HOLD_OFF_TICKS, 4.5, 20));
        assertEquals(0, h.secondsLeft(5000 + BlazeCapRules.HOLD_OFF_TICKS));
    }

    @Test
    public void theHoldOffsLineAtMostOnceInFiveSeconds() {
        BlazeCapRules.HideHold h = new BlazeCapRules.HideHold();
        h.capped(1000);
        assertTrue(h.line(1001));
        assertFalse(h.line(1002));
        assertFalse(h.line(1001 + BlazeCapRules.HOLD_LINE_TICKS - 1));
        assertTrue(h.line(1001 + BlazeCapRules.HOLD_LINE_TICKS));
        // (a new cap says it again at once)
        h.capped(2000);
        assertTrue(h.line(2001));
    }

    @Test
    public void theNookIsEveryCellWithinFourOfTheBarredOne() {
        BlazeCapRules.Nooks n = new BlazeCapRules.Nooks();
        assertTrue(n.isEmpty());
        assertFalse(n.barred(6187, 66, 811));
        assertTrue(n.keys().isEmpty());
        n.bar(6188, 66, 811);
        assertFalse(n.isEmpty());
        // (B:7's nook, 6186 to 6190 at 66 811, all of it)
        for (int x = 6186; x <= 6190; x++) assertTrue(n.barred(x, 66, 811));
        assertTrue(n.barred(6192, 66, 811));
        assertFalse(n.barred(6193, 66, 811));
        assertFalse(n.barred(6191, 69, 814));
        // (the next hiding place B:7 found, 15 blocks off, stays allowed)
        assertFalse(n.barred(6178, 66, 801));
        assertTrue(n.keys().contains(BlazeCover.key(6186, 66, 811)));
        assertTrue(n.keys().contains(BlazeCover.key(6188, 70, 811)));
        assertFalse(n.keys().contains(BlazeCover.key(6178, 66, 801)));
        assertFalse(n.keys().contains(BlazeCover.key(6193, 66, 811)));
        // (a second nook adds its cells; the same one twice changes nothing)
        int before = n.keys().size();
        n.bar(6188, 66, 811);
        assertEquals(before, n.keys().size());
        n.bar(6178, 66, 801);
        assertTrue(n.barred(6178, 66, 801));
        assertTrue(n.keys().contains(BlazeCover.key(6178, 66, 801)));
        assertEquals(2 * before, n.keys().size());
    }
}
