package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import org.junit.Test;

// the corner a fast mob puts us in has to be able to end, or a fight on the next split never lets go
public class CombatCommitFastCornerTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final long NOW = 10_000;
    private static final long RELEASE = CombatCommit.FAST_CORNER_RELEASE_TICKS;

    private static Foe cube(int id, double distance) {
        return new Foe(id, distance, false, false, 3).withFast(true);
    }

    private static Foe zombie(int id, double distance) {
        return new Foe(id, distance, false, false, 3);
    }

    private static Foe warden(int id, double distance) {
        return new Foe(id, distance, false, false, NEVER, Kind.UNTOUCHABLE);
    }

    // past the range the fast rule looks at, still close enough to count as on us
    private static Foe backedOff(int id) {
        return cube(id, CombatCommit.FAST_FIGHT_RANGE + 0.5);
    }

    private static Tick tick(long now, float hp, Foe target, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), target);
    }

    private static Tick boxed(long now, float hp, Foe target, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), target, Way.NONE, true, false, false, RecoverHold.Body.NONE, true);
    }

    private static CombatCommit cornered() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_TO_FIGHT, c.step(tick(NOW, 7, null, cube(1, 2))));
        assertEquals("fast", c.cornerKind());
        return c;
    }

    @Test
    public void theCornerSurvivesAChain() {
        CombatCommit c = cornered();
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 1, 7, null, cube(2, 2))));
        assertEquals(2, c.targetId());
        assertTrue(c.cornered());
        assertEquals("fast", c.cornerKind());
        assertEquals(Why.CORNERED, c.why());
    }

    @Test
    public void theHeavyBailSurvivesAChainToo() {
        CombatCommit c = cornered();
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 1, 7, null, cube(2, 2))));
        Foe target = cube(2, 2);
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 2, 7, target, target, warden(3, 6))));
        assertEquals(Why.HEAVY, c.why());
    }

    @Test
    public void aFreshFightIsNotAFastCorner() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 14, null, cube(1, 2))));
        assertFalse(c.cornered());
        assertNull(c.cornerKind());
    }

    @Test
    public void itHoldsWhileTheFastOneIsOnUs() {
        CombatCommit c = cornered();
        Foe target = cube(1, 2);
        for (long t = 1; t < 3 * RELEASE; t++) {
            assertEquals(Event.NONE, c.step(tick(NOW + t, 7, target, target)));
            assertTrue(c.cornered());
        }
    }

    @Test
    public void aKnockPastTheRangeIsNotARelease() {
        CombatCommit c = cornered();
        Foe target = backedOff(1);
        for (long t = 1; t <= RELEASE; t++) {
            assertEquals(Event.NONE, c.step(tick(NOW + t, 7, target, target)));
            assertTrue("at " + t, c.cornered());
        }
    }

    @Test
    public void comingBackStartsTheClockOver() {
        CombatCommit c = cornered();
        Foe away = backedOff(1);
        Foe back = cube(1, 2);
        for (long t = 1; t <= RELEASE; t++) c.step(tick(NOW + t, 7, away, away));
        c.step(tick(NOW + RELEASE + 1, 7, back, back));
        for (long t = RELEASE + 2; t <= 2 * RELEASE + 1; t++) {
            c.step(tick(NOW + t, 7, away, away));
            assertTrue("at " + t, c.cornered());
        }
    }

    @Test
    public void offUsLongEnoughTheBailRulesAreBack() {
        CombatCommit c = cornered();
        Foe target = backedOff(1);
        for (long t = 1; t <= RELEASE; t++) c.step(tick(NOW + t, 7, target, target));
        // under the flee line with it still on us by the swarm rule: that is the run
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + RELEASE + 1, 7, target, target)));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void offUsAboveTheLineItIsJustAFight() {
        CombatCommit c = cornered();
        Foe target = backedOff(1);
        for (long t = 1; t <= RELEASE; t++) c.step(tick(NOW + t, 12, target, target));
        assertEquals(Event.NONE, c.step(tick(NOW + RELEASE + 1, 12, target, target)));
        assertEquals(Mode.FIGHT, c.mode());
        assertFalse(c.cornered());
        assertNull(c.cornerKind());
        assertEquals(Why.HIT, c.why());
    }

    @Test
    public void onceOffItDoesNotFlipBackOnTheNextBite() {
        CombatCommit c = cornered();
        Foe away = backedOff(1);
        for (long t = 1; t <= RELEASE + 1; t++) c.step(tick(NOW + t, 12, away, away));
        assertFalse(c.cornered());
        // bitten again under the line: the fight just carries on, nothing flips to a run and back
        Foe back = cube(1, 2);
        assertEquals(Event.NONE, c.step(tick(NOW + RELEASE + 2, 7, back, back)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aLoneWitherCornerLetsGoTheSameWay() {
        Foe near = new Foe(1, 3, false, false, 3, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER).withWither(true);
        Foe far = new Foe(1, CombatCommit.FAST_FIGHT_RANGE + 0.5, false, false, 3, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER).withWither(true);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_TO_FIGHT, c.step(tick(NOW, 7, null, near)));
        assertEquals("wither", c.cornerKind());
        for (long t = 1; t <= RELEASE + 1; t++) c.step(tick(NOW + t, 12, far, far));
        assertFalse(c.cornered());
        assertNull(c.cornerKind());
    }

    // ---- the box corner is left as it was

    @Test
    public void aBoxCornerStillEndsTheTickTheBoxOpens() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_TO_FIGHT, c.step(boxed(NOW, 7, null, zombie(1, 2))));
        assertEquals("box", c.cornerKind());
        Foe target = zombie(1, 2);
        assertEquals(Event.NONE, c.step(boxed(NOW + 1, 12, target, target)));
        assertTrue(c.cornered());
        assertEquals(Event.NONE, c.step(tick(NOW + 2, 12, target, target)));
        assertFalse(c.cornered());
        assertEquals(Why.HIT, c.why());
    }

    @Test
    public void aBoxCornerKeepsThroughAChainAndAnyClock() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_TO_FIGHT, c.step(boxed(NOW, 7, null, zombie(1, 2))));
        assertEquals(Event.FIGHT_NEXT, c.step(boxed(NOW + 1, 7, null, zombie(2, 2))));
        assertEquals("box", c.cornerKind());
        Foe target = zombie(2, 2);
        for (long t = 2; t < 3 * RELEASE; t++) {
            assertEquals(Event.NONE, c.step(boxed(NOW + t, 7, target, target)));
            assertTrue(c.cornered());
        }
    }
}
