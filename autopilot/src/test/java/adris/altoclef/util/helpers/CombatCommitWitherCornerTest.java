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
import org.junit.Test;

// one wither skeleton that already hit us from close is fought like a fast mob: a run is 4.5 in the back every swing
public class CombatCommitWitherCornerTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final long NOW = 10_000;

    private static Foe wither(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER).withWither(true);
    }

    private static Foe zombie(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
    }

    private static Foe blaze(int id, double distance) {
        return new Foe(id, distance, true, false, 3, Kind.FLYER, Double.POSITIVE_INFINITY, true, NEVER);
    }

    private static Tick tick(long now, float hp, Foe target, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), target);
    }

    private static CombatCommit cornered() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_TO_FIGHT, c.step(tick(NOW, 7, null, wither(1, 3, 3))));
        return c;
    }

    @Test
    public void aLoneOneThatHitUsCloseIsTheCorneredFightAtLowHp() {
        CombatCommit c = cornered();
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.CORNERED, c.why());
        assertEquals(1, c.targetId());
        assertTrue(c.cornered());
    }

    @Test
    public void theCornerHoldsUnderTheFleeLine() {
        CombatCommit c = cornered();
        Foe target = wither(1, 2, 2);
        for (long t = 1; t < 40; t++) {
            assertEquals(Event.NONE, c.step(tick(NOW + t, 3, target, target)));
            assertEquals(Mode.FIGHT, c.mode());
        }
    }

    @Test
    public void blazesShootingDoNotMakeItARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_TO_FIGHT, c.step(tick(NOW, 7, null, wither(1, 3, 3), blaze(2, 10))));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void notOneThatHasNotHitUsOrIsPastFourOrWithNoWeapon() {
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(NOW, 7, null, wither(1, 3, NEVER))));
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(NOW, 7, null, wither(1, CombatCommit.FAST_FIGHT_RANGE + 0.5, 3))));
        Tick bare = new Tick(NOW, 7, 0, 0, List.of(wither(1, 3, 3)), null, Way.NONE, false);
        assertEquals(Event.RUN_START, new CombatCommit().step(bare));
    }

    @Test
    public void notWithAnotherMeleeMobAlongside() {
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(NOW, 7, null, wither(1, 3, 3), zombie(2, 6, NEVER))));
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(NOW, 7, null, wither(1, 3, 3), wither(2, 7, NEVER))));
    }

    @Test
    public void aCrowdTurningUpMidFightStillBails() {
        CombatCommit c = cornered();
        Foe target = wither(1, 2, 2);
        assertEquals(Event.NONE, c.step(tick(NOW + 1, 5, target, target)));
        Event e = c.step(tick(NOW + 2, 5, target, target, zombie(2, 1.5, 3), zombie(3, 2, 3)));
        assertEquals(Event.FIGHT_TO_RUN, e);
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.CROWD, c.why());
    }

    @Test
    public void aSkeletonAndTwoBlazesLandingHitsIsTheCrowdRun() {
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(NOW, 7, null, wither(1, 3, 3), blaze(2, 5), blaze(3, 5))));
        CombatCommit c = cornered();
        Foe target = wither(1, 2, 2);
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 1, 5, target, target, blaze(2, 5), blaze(3, 5))));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void theCornerDoesNotCarryOverToTheNextTarget() {
        CombatCommit c = cornered();
        Foe zombie = new Foe(2, 2, false, false, 3, Kind.NORMAL, 0, true, NEVER);
        // the skeleton is dead and a zombie that hit us is in reach: a plain fight with it, the bail rules are back
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 1, 7, null, zombie)));
        assertEquals(2, c.targetId());
        org.junit.Assert.assertFalse(c.cornered());
    }

    @Test
    public void aFightLowOnHpWithALoneOneOnUsBecomesTheCorner() {
        // the fight started healthy (the way fight), the hp line comes mid fight: it stays and corners instead of bailing
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, wither(1, 3, NEVER))));
        Foe target = wither(1, 2, 2);
        assertEquals(Event.NONE, c.step(tick(NOW + 1, 6, target, target)));
        assertEquals(Mode.FIGHT, c.mode());
        assertTrue(c.cornered());
    }
}
