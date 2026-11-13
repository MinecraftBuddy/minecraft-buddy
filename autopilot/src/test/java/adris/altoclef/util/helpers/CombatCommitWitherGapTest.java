package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import adris.altoclef.util.helpers.WitherGapRules.Facts;
import adris.altoclef.util.helpers.WitherGapRules.Plan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

// Tuning wither_gap (off by default): the run from two wither skeletons becomes the fight from the plan's cell, which holds
// in the cell and ends at the leave line, when none is left, or when the cell is not reached (then the old way for a while);
// with the switch off the run stays a run
public class CombatCommitWitherGapTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final Plan PLAN = new Plan(WitherGapRules.Kind.DUG, 1, 64, 0,
            List.of(new int[]{0, 64, 0}, new int[]{0, 65, 0}, new int[]{1, 64, 0}, new int[]{1, 65, 0}), List.of(), -1, 64, 0, 3);
    private Path tuning;
    private String tuningWas;

    @Before
    public void on() throws Exception {
        tuningWas = System.getProperty("buddy.tuning");
        tuning = Files.createTempFile("tuning", ".properties");
        System.setProperty("buddy.tuning", tuning.toString());
        set(true);
    }

    @After
    public void off() throws Exception {
        Files.writeString(tuning, "");
        Tuning.now("wither_gap", 0);
        Files.deleteIfExists(tuning);
        if (tuningWas == null) {
            System.clearProperty("buddy.tuning");
        } else {
            System.setProperty("buddy.tuning", tuningWas);
        }
        Tuning.now("wither_gap", 0);
    }

    private void set(boolean gap) throws Exception {
        Files.writeString(tuning, "wither_gap=" + (gap ? 1 : 0) + "\n");
        Tuning.now("wither_gap", 0);
    }

    private static Foe witherAt(int id, double distance) {
        return new Foe(id, distance, false, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER, false, null, false, 0, 0, 0,
                false, false, false, true);
    }

    private static List<Foe> pair(int first) {
        return List.of(witherAt(first, 3), witherAt(first + 1, 6));
    }

    private static Tick tick(long now, float hp, List<Foe> foes) {
        return new Tick(now, hp, 0, 0, foes, null, Way.NONE, true);
    }

    // a run from the pair, then the gap from it
    private static CombatCommit gapAt(long now) {
        CombatCommit c = new CombatCommit();
        Tick t = tick(now, 20, pair(1));
        assertEquals(Event.RUN_START, c.step(t));
        assertEquals(Event.GAP_START, c.startGap(t, 2, 6, PLAN, Event.RUN_START));
        return c;
    }

    @Test
    public void theRunFromAPairBecomesTheGap() {
        CombatCommit c = gapAt(0);
        assertTrue(c.gapping());
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.GAP, c.why());
        assertEquals(-1, c.targetId());
        assertEquals(Event.NONE, c.endedFirst());
        assertEquals("2 wither skeletons within 6 at 20.0 hp, the safe cell at 1 64 0 (dug, 4 blocks), going in", c.takeGapNote());
    }

    @Test
    public void offTheRunStaysARun() throws Exception {
        set(false);
        CombatCommit c = new CombatCommit();
        Tick t = tick(0, 20, pair(1));
        assertEquals(Event.RUN_START, c.step(t));
        assertFalse(c.gapMay(0));
        assertEquals(Event.NONE, c.startGap(t, 2, 6, PLAN, Event.RUN_START));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void onlyARunTurnsIntoTheGap() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.startGap(tick(0, 20, List.of()), 2, 6, PLAN, Event.NONE));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void inTheCellItHoldsTillTheLeaveLine() {
        CombatCommit c = gapAt(0);
        c.takeGapNote();
        // hit down to 9 in the cell: it holds, no bail of the fight's
        c.gapFacts(new Facts(true, true, null, 2));
        assertEquals(Event.NONE, c.step(tick(20, 9, pair(1))));
        assertTrue(c.gapping());
        c.gapFacts(new Facts(true, true, null, 2));
        Event e = c.step(tick(21, 7.5f, pair(1)));
        assertFalse(c.gapping());
        assertTrue(e + " " + c.endedFirst(), e == Event.GAP_END || c.endedFirst() == Event.GAP_END);
        assertEquals("in the cell, leaving: hp 7.5 under 8, the old way", c.takeGapNote());
    }

    @Test
    public void noneLeftEndsItAfterTwoSeconds() {
        CombatCommit c = gapAt(0);
        c.gapFacts(new Facts(true, true, null, 0));
        assertEquals(Event.NONE, c.step(tick(10, 20, List.of())));
        c.gapFacts(new Facts(true, true, null, 0));
        assertEquals(Event.NONE, c.step(tick(10 + WitherGapRules.QUIET_TICKS - 1, 20, List.of())));
        c.gapFacts(new Facts(true, true, null, 0));
        assertEquals(Event.GAP_END, c.step(tick(10 + WitherGapRules.QUIET_TICKS, 20, List.of())));
        c.takeGapNote();
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aCellNotReachedIsTheOldWayForAWhile() {
        CombatCommit c = gapAt(0);
        c.takeGapNote();
        long build = WitherGapRules.buildTicks();
        c.gapFacts(new Facts(false, false, null, 2));
        assertEquals(Event.NONE, c.step(tick(build - 1, 20, pair(1))));
        c.gapFacts(new Facts(false, false, null, 2));
        Event e = c.step(tick(build, 20, pair(1)));
        assertTrue(e + " " + c.endedFirst(), e == Event.GAP_END || c.endedFirst() == Event.GAP_END);
        assertEquals("leaving before the cell at 1 64 0 was reached: the cell not reached in 6 s, the old way for 30 s", c.takeGapNote());
        // a new pair starts a new run: no gap from it till the hold back is over
        long now = build + 1;
        while (c.mode() != Mode.RUN && now < build + 200) c.step(tick(now++, 20, pair(10)));
        assertEquals(Mode.RUN, c.mode());
        assertFalse(c.gapMay(now));
        assertEquals(Event.NONE, c.startGap(tick(now, 20, pair(10)), 2, 6, PLAN, Event.NONE));
        assertTrue(c.gapMay(build + WitherGapRules.RETRY_TICKS));
    }

    @Test
    public void theTaskGivingUpEndsIt() {
        CombatCommit c = gapAt(0);
        c.takeGapNote();
        c.gapFacts(new Facts(false, true, "out of the cell for 5 s", 2));
        Event e = c.step(tick(40, 18, pair(1)));
        assertTrue(e + " " + c.endedFirst(), e == Event.GAP_END || c.endedFirst() == Event.GAP_END);
        assertEquals("in the cell, leaving: out of the cell for 5 s, the old way for 30 s", c.takeGapNote());
    }
}
