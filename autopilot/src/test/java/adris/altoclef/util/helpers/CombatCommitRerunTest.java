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
import java.util.List;
import org.junit.Test;

// a run that is over as clear and a new one straight after: the new one goes on from the old origin, further, instead of taking
// any cell 24 from where it stands (the way back past the skeletons was one of those, a cave at y 7, 3 Nov 2026)
public class CombatCommitRerunTest {

    private static final long NOW = 1000;

    private static Tick tick(long now, float health, double x, Foe... foes) {
        return new Tick(now, health, x, 0, List.of(foes), null, Way.NONE, true, false);
    }

    // hurt with a zombie at eight blocks: a run, whatever else is about
    private static Foe zombie(int id) {
        return new Foe(id, 8, false, false, CombatCommit.NEVER);
    }

    // a skeleton that shot us a moment ago and still sees us, just past the run's clear line
    private static Foe shooter(int id, long sinceHit) {
        return new Foe(id, CombatCommit.RUN_CLEAR + 1, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, true, CombatCommit.NEVER);
    }

    // the cave: a run from x 0 that gets 25 blocks east while three skeletons keep shooting from 13. the run is over as clear (nobody
    // within 12) and on the same step the arrows start the next one. returns the step that did both
    private static long overAndAgain(CombatCommit c) {
        assertEquals(Event.RUN_START, c.step(tick(NOW, 8, 0, zombie(900))));
        for (long t = NOW + 1; t < NOW + 400; t++) {
            long since = t % 20;
            Event e = c.step(tick(t, 14, 25, shooter(1, since), shooter(2, since), shooter(3, since)));
            if (e == Event.RUN_START) {
                // (clear, or stuck since upstream keeps a run going while a skeleton can still reach us, b44fc50: the arrows
                // from 13 keep it going until it is stuck; the next one goes on from the old origin all the same)
                org.junit.Assert.assertTrue("ended " + c.endedFirst(), c.endedFirst() == Event.RUN_CLEAR || c.endedFirst() == Event.RUN_STUCK);
                return t;
            }
            assertEquals("tick " + t, Event.NONE, e);
        }
        throw new AssertionError("the run never came back");
    }

    // (since the merge of upstream e802a32 the run under the skeletons' fire does not end as clear at all: upstream keeps a run
    // going while a skeleton can still reach us, b44fc50, which takes on the cause this rule was for, a run over as clear in
    // the middle of the arrows. it ends stuck, and the next starts where it got to)
    @Test
    public void aRunRightAfterOneThatWasClearGoesOnFromItsOrigin() {
        CombatCommit c = new CombatCommit();
        overAndAgain(c);
        assertEquals(Mode.RUN, c.mode());
        if (c.endedFirst() == Event.RUN_CLEAR) {
            assertEquals(0, c.originX(), 0);
            assertEquals(25 + CombatCommit.RUN_DISTANCE, c.runDistance(), 1e-9);
        } else {
            assertEquals(Event.RUN_STUCK, c.endedFirst());
            assertEquals(25, c.originX(), 0);
        }
    }

    // a leg with nobody about, standing at x: over as clear once it is far enough. returns the step it ended at
    private static long legOver(CombatCommit c, long from, double x) {
        for (long t = from; t < from + 400; t++) {
            Event e = c.step(tick(t, 20, x));
            if (e == Event.RUN_CLEAR) return t;
            assertEquals("tick " + t, Event.NONE, e);
        }
        throw new AssertionError("the leg never ended");
    }

    // hurt with a zombie at eight, just past the cooldown of the last end: a run (a zombie that close is no line-of-sight run)
    private static void hurtAgain(CombatCommit c, long at, double x) {
        assertEquals(Event.RUN_START, c.step(tick(at, 8, x, zombie(901))));
    }

    @Test
    public void theLongerRunIsNotOverWhereTheFirstOneWas() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 8, 0, zombie(900))));
        long end = legOver(c, NOW + 1, 25);
        hurtAgain(c, end + CombatCommit.COOLDOWN + 10, 25);
        assertEquals(0, c.originX(), 0);
        assertEquals(49, c.runDistance(), 1e-9);
        // walking on from 25 with nobody about is clear, but not far enough until 49: the line now
        long again = end + CombatCommit.COOLDOWN + 10;
        for (long t = again + 1; t < again + 200; t++) {
            assertEquals("tick " + t, Event.NONE, c.step(tick(t, 20, 25 + (t - again) * 0.1)));
        }
        assertEquals(Mode.RUN, c.mode());
        // and at 50 it is over
        legOver(c, again + 200, 50);
    }

    @Test
    public void theLegsAreCapped() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 8, 0, zombie(900))));
        long t = legOver(c, NOW + 1, 25);
        double x = 25;
        for (int leg = 2; leg <= CombatCommit.RERUN_LEGS; leg++) {
            t += CombatCommit.COOLDOWN + 10;
            hurtAgain(c, t, x);
            assertEquals(0, c.originX(), 0);
            x = c.runDistance() + 1;
            t = legOver(c, t + 1, x);
        }
        // one more in a row: from where it stands now
        t += CombatCommit.COOLDOWN + 10;
        hurtAgain(c, t, x);
        assertEquals(x, c.originX(), 0);
        assertEquals(CombatCommit.RUN_DISTANCE, c.runDistance(), 0);
    }

    @Test
    public void aFirstRunStartsWhereWeStand() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 8, 12.5, zombie(900))));
        assertEquals(12.5, c.originX(), 0);
        assertEquals(CombatCommit.RUN_DISTANCE, c.runDistance(), 0);
    }

    @Test
    public void theRerunWindowAndItsLegs() {
        assertTrue(CombatCommit.rerun(NOW + 10, NOW, 1));
        assertTrue(CombatCommit.rerun(NOW + CombatCommit.RERUN_TICKS, NOW, CombatCommit.RERUN_LEGS - 1));
        assertFalse(CombatCommit.rerun(NOW + CombatCommit.RERUN_TICKS + 1, NOW, 1));
        assertFalse(CombatCommit.rerun(NOW + 10, NOW, CombatCommit.RERUN_LEGS));
        // a clock that went backwards: a new world
        assertFalse(CombatCommit.rerun(NOW - 10, NOW, 1));
        // nothing ran yet
        assertFalse(CombatCommit.rerun(NOW, Long.MIN_VALUE / 2, 0));
    }
}
