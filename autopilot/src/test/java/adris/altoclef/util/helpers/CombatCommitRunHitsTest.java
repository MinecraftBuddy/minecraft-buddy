package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import java.util.function.LongFunction;
import org.junit.Test;

// a run that keeps taking hits is not working. a mob that runs in, swings and gets knocked off never stays in contact,
// so the hits get counted: RUN_HITS after the grace and we turn
public class CombatCommitRunHitsTest {

    private static final long NOW = 1000;
    private static final long NEVER = CombatCommit.NEVER;
    private static final long GRACE = CombatCommit.RUN_HIT_GRACE;

    private static Foe zombie(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit);
    }

    private static Tick tick(long now, float hp, double x, boolean armed, Foe target, Foe... foes) {
        return new Tick(now, hp, x, 0, List.of(foes), target, Way.NONE, armed);
    }

    // ten ticks in contact, five knocked off to 3.8: never a second of unbroken contact
    private static double bounce(long now, long from) {
        return ((now - from) % 15) < 10 ? 2 : 3.8;
    }

    // ticks since the latest of these hits, NEVER before the first
    private static long since(long now, long... hits) {
        long last = -1;
        for (long hit : hits) {
            if (hit <= now) last = hit;
        }
        return last < 0 ? NEVER : now - last;
    }

    // steps from..to moving 0.3 a tick from x0 (never cornered, never stuck), returns the first event that is not NONE
    private static Event walk(CombatCommit c, long from, long to, float hp, boolean armed, double x0, LongFunction<Foe[]> foes) {
        for (long now = from; now <= to; now++) {
            Event seen = c.step(tick(now, hp, x0 + (now - from) * 0.3, armed, null, foes.apply(now)));
            if (seen != Event.NONE) return seen;
        }
        return Event.NONE;
    }

    // three in contact at full health: a crowd run. two of them hang back at 10 afterwards, zombie 1 keeps bouncing in
    private static CombatCommit crowdRun(long at, boolean armed, double x) {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(at, 20, x, armed, null, zombie(1, 2, NEVER), zombie(2, 2, NEVER), zombie(3, 2.5, NEVER))));
        assertEquals(Why.CROWD, c.why());
        return c;
    }

    private static LongFunction<Foe[]> bouncer(long start, long... hits) {
        return now -> new Foe[]{zombie(1, bounce(now, start), since(now, hits)), zombie(2, 10, NEVER), zombie(3, 10, NEVER)};
    }

    @Test
    public void twoHitsFromABouncingMobAfterTheGraceTurnTheRunAround() {
        CombatCommit c = crowdRun(NOW, true, 0);
        long first = NOW + GRACE + 10;
        long second = first + 30;
        // nothing on the first hit, the second one is the turn (it lands with zombie 1 knocked off to 3.8, out of contact)
        assertEquals(Event.NONE, walk(c, NOW + 1, second - 1, 20, true, 0, bouncer(NOW, first, second)));
        assertEquals(Event.RUN_CHASED_FIGHT, walk(c, second, second, 20, true, 0.3 * (second - NOW - 1), bouncer(NOW, first, second)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.CHASED, c.why());
        assertEquals(1, c.targetId());
        assertTrue(c.cornered());
    }

    @Test
    public void oneHitAfterTheGraceIsNotEnough() {
        // zombie 1 has bitten us once since the run began and spends two thirds of every second in contact,
        // and one hit is not a failing run. this is the run that tanked hits until the cap
        CombatCommit c = crowdRun(NOW, true, 0);
        assertEquals(Event.NONE, walk(c, NOW + 1, NOW + 300, 20, true, 0, bouncer(NOW, NOW + GRACE + 10)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void hitsInsideTheGraceAreWhyWeRanNotNews() {
        CombatCommit c = crowdRun(NOW, true, 0);
        // three swings while we turn round, the last on the last tick of the grace
        assertEquals(Event.NONE, walk(c, NOW + 1, NOW + 300, 20, true, 0, bouncer(NOW, NOW + 5, NOW + 20, NOW + GRACE)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void unarmedKeepsRunning() {
        CombatCommit c = crowdRun(NOW, false, 0);
        long first = NOW + GRACE + 10;
        assertEquals(Event.NONE, walk(c, NOW + 1, NOW + 300, 20, false, 0, bouncer(NOW, first, first + 30, first + 60)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aHeavyHitterOnUsAtLowHpKeepsItARun() {
        // hp 10 with a hoglin in contact: that is the danger rules' run, and turning on the zombie would bail on the next tick
        Foe hoglin = new Foe(4, 2.5, false, false, NEVER, Kind.HEAVY);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 10, 0, true, null, hoglin)));
        assertEquals(Why.HEAVY, c.why());
        long first = NOW + GRACE + 10;
        assertEquals(Event.NONE, walk(c, NOW + 1, NOW + 300, 10, true, 0,
                now -> new Foe[]{zombie(1, bounce(now, NOW), since(now, first, first + 30, first + 60)), hoglin}));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void theWardenAboutKeepsItARun() {
        Foe warden = new Foe(4, 7, false, false, NEVER, Kind.UNTOUCHABLE);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, 0, true, null, warden)));
        long first = NOW + GRACE + 10;
        assertEquals(Event.NONE, walk(c, NOW + 1, NOW + 300, 20, true, 0,
                now -> new Foe[]{zombie(1, bounce(now, NOW), since(now, first, first + 30, first + 60)), warden}));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void oneHitSeenForAHundredTicksIsOneHit() {
        CombatCommit c = crowdRun(NOW, true, 0);
        long first = NOW + GRACE + 10;
        long second = first + 120;
        // zombie 1 hits once and hangs about at 3.8 with the same hit on the books the whole time
        LongFunction<Foe[]> foes = now -> new Foe[]{zombie(1, 3.8, since(now, first, second)), zombie(2, 10, NEVER)};
        assertEquals(Event.NONE, walk(c, NOW + 1, second - 1, 20, true, 0, foes));
        // and the next real swing is the second one
        assertEquals(Event.RUN_CHASED_FIGHT, walk(c, second, second, 20, true, 0.3 * (second - NOW - 1), foes));
        assertEquals(1, c.targetId());
    }

    @Test
    public void theHitThatStartedTheRunDoesNotCount() {
        // hp 7 and a zombie bites us: a low hp run. that bite plus one more later is one hit, not two
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 7, 0, true, null, zombie(1, 2, 0))));
        assertEquals(Why.LOW_HP, c.why());
        long later = NOW + GRACE + 10;
        assertEquals(Event.NONE, walk(c, NOW + 1, NOW + 300, 7, true, 0,
                now -> new Foe[]{zombie(1, bounce(now, NOW), since(now, NOW, later))}));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aMobWeGaveUpOnThatBitesUsMidRunCountsAsHits() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, 0, true, null, zombie(1, 2, 0))));
        // it hangs back at 5 for the stall window: written off
        long stall = NOW + CombatCommit.STALL_TICKS;
        for (long now = NOW + 1; now < stall; now++) {
            Foe hangingBack = zombie(1, 5, now - NOW);
            assertEquals("tick " + now, Event.NONE, c.step(tick(now, 20, 0, true, hangingBack, hangingBack)));
        }
        Foe hangingBack = zombie(1, 5, stall - NOW);
        assertEquals(Event.FIGHT_STALLED, c.step(tick(stall, 20, 0, true, hangingBack, hangingBack)));
        assertTrue(c.isIgnored(1));
        // after the cooldown, a crowd with it in the picture again: a run
        long run = stall + CombatCommit.COOLDOWN;
        assertEquals(Event.RUN_START, c.step(tick(run, 20, 0, true, null, zombie(2, 2.4, NEVER), zombie(3, 2.6, NEVER), zombie(4, 2.8, NEVER), zombie(1, 6, run - NOW))));
        // zombie 1 catches up and stays on us biting. it hit us since we gave up on it, so it is news, and the hits
        // add up to the turn
        long bite = run + 1;
        Event seen = walk(c, bite, bite + 100, 20, true, 0, now -> new Foe[]{zombie(1, 2, since(now, bite, bite + GRACE + 2, bite + GRACE + 15)), zombie(2, 2.4, NEVER), zombie(3, 2.6, NEVER), zombie(4, 2.8, NEVER)});
        assertEquals(Event.RUN_CHASED_FIGHT, seen);
        assertEquals(1, c.targetId());
        assertEquals(Why.CHASED, c.why());
    }

    @Test
    public void aLowHpRunHitTwiceStaysARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 7, 0, true, null, zombie(1, 2, 0))));
        assertEquals(Why.LOW_HP, c.why());
        long first = NOW + GRACE + 10;
        long second = first + 30;
        // the same two hits that turn a healthy run round: hurt, turning round is the fight that ends us
        assertEquals(Event.NONE, walk(c, NOW + 1, NOW + 300, 7, true, 0,
                now -> new Foe[]{zombie(1, bounce(now, NOW), since(now, first, second))}));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void theCountStartsOverWithEveryRun() {
        CombatCommit c = crowdRun(NOW, true, 0);
        long first = NOW + GRACE + 10;
        assertEquals(Event.NONE, walk(c, NOW + 1, first, 20, true, 0, bouncer(NOW, first)));
        // everybody gone and we are far out: the run ends clear
        long gone = first + 1;
        Event end = Event.NONE;
        for (long now = gone; now < gone + 200 && end == Event.NONE; now++) {
            end = c.step(tick(now, 20, 50, true, null));
        }
        assertEquals(Event.RUN_CLEAR, end);
        // a new crowd run later on, one hit after its grace: that is one, not two
        long again = gone + 400;
        assertEquals(Event.RUN_START, c.step(tick(again, 20, 50, true, null, zombie(1, 2, NEVER), zombie(2, 2, NEVER), zombie(3, 2.5, NEVER))));
        assertEquals(Event.NONE, walk(c, again + 1, again + 300, 20, true, 50, bouncer(again, again + GRACE + 10)));
        assertEquals(Mode.RUN, c.mode());
    }
}
