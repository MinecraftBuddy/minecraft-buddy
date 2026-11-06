package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import baritone.api.utils.Dimension;
import java.util.List;
import org.junit.Test;

// hurt, and two shooters with a line to us are landing arrows: at hp 6 that is an emergency whether we stand or walk, and
// whether they are 6 blocks off or 14. one shooter alone is the pinned fight at any hp (see shooterFight), so these use a pair
public class CombatCommitShotTest {

    private static final long NOW = 1000;
    private static final int SKELETON = 6;

    private static Foe skeleton(int id, double distance, long sinceHit, boolean sight) {
        return new Foe(id, distance, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, sight, CombatCommit.NEVER);
    }

    private static Foe skeleton(double distance, long sinceHit, boolean sight) {
        return skeleton(SKELETON, distance, sinceHit, sight);
    }

    private static Tick tick(long now, float hp, double x, Foe... foes) {
        return new Tick(now, hp, x, 0, List.of(foes), null, Way.NONE, true, false);
    }

    private static Tick tick(float hp, Foe... foes) {
        return tick(NOW, hp, 0, foes);
    }

    @Test
    public void hurtAndShotAtByTwoFromTwelveBlocksIsARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(6, skeleton(12, 40, true), skeleton(7, 12, 40, true))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(CombatCommit.Why.SHOT, c.why());
        assertEquals("combat: [overworld] RUN from 1 skeleton (hp 6, it is shooting) [SHOT]",
                CombatLog.line(Dimension.OVERWORLD, Event.RUN_START, c.why(), false, "skeleton", "it", "1 skeleton", 6, 0));
    }


    @Test
    public void hurtAndShotAtByOneFromTwelveBlocksIsTheFightWithIt() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(6, skeleton(12, 40, true))));
        assertEquals(CombatCommit.Why.PINNED, c.why());
    }

    @Test
    public void aShooterInTheWaterIsNotASniper() {
        assertEquals(true, skeleton(12, 40, true).sniper());
        assertEquals(false, skeleton(12, 40, true).withWet(true).sniper());
    }

    @Test
    public void aLoneShooterInTheWaterIsNoPinnedFightAtFullHp() {
        assertEquals(Event.NONE, new CombatCommit().step(tick(20, skeleton(12, 40, true).withWet(true))));
    }

    @Test
    public void hurtAndShotAtByOneInTheWaterIsARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(6, skeleton(12, 40, true).withWet(true))));
        assertEquals(CombatCommit.Why.SHOT, c.why());
    }

    @Test
    public void anArrowThatLandedTwoHundredTicksAgoIsNotShooting() {
        assertEquals(Event.NONE, new CombatCommit().step(tick(6, skeleton(12, 200, true))));
        assertEquals(Event.FIGHT_START, new CombatCommit().step(tick(6, skeleton(12, CombatCommit.SHOT_MEMORY, true))));
        assertEquals(Event.NONE, new CombatCommit().step(tick(6, skeleton(12, CombatCommit.SHOT_MEMORY + 1, true))));
    }

    @Test
    public void withoutALineOfSightItIsNothing() {
        assertEquals(Event.NONE, new CombatCommit().step(tick(6, skeleton(12, 40, false))));
    }

    @Test
    public void beyondTheFoeRangeItIsNothing() {
        assertEquals(Event.FIGHT_START, new CombatCommit().step(tick(6, skeleton(CombatCommit.FOE_RANGE, 40, true))));
        assertEquals(Event.NONE, new CombatCommit().step(tick(6, skeleton(CombatCommit.FOE_RANGE + 1, 40, true))));
    }

    @Test
    public void theBailLineDoesNotTurnALoneShooterIntoARun() {
        assertEquals(Event.FIGHT_START, new CombatCommit().step(tick(CombatCommit.FLEE_HP, skeleton(12, 40, true))));
        assertEquals(Event.FIGHT_START, new CombatCommit().step(tick(CombatCommit.FLEE_HP + 1, skeleton(12, 40, true))));
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(CombatCommit.FLEE_HP, skeleton(12, 40, true), skeleton(7, 12, 40, true))));
    }

    // only things that shoot: a zombie that hit us a while ago and stands at twelve blocks is the old rule, scenery
    @Test
    public void aMeleeMobFarAwayIsStillNotNear() {
        Foe zombie = new Foe(9, 12, false, false, 40, Kind.NORMAL, Double.POSITIVE_INFINITY, true, CombatCommit.NEVER);
        assertEquals(Event.NONE, new CombatCommit().step(tick(6, zombie)));
        Foe creeper = new Foe(9, 12, true, true, 40, Kind.NORMAL, Double.POSITIVE_INFINITY, true, CombatCommit.NEVER);
        assertEquals(Event.NONE, new CombatCommit().step(tick(6, creeper)));
    }

    // something within 8 keeps its own reason once a second mob is on us: the skeleton with one is the shooter fight, two are not
    @Test
    public void aCrowdOnUsStillMakesItTheOrdinaryLowHpRun() {
        CombatCommit c = new CombatCommit();
        Foe zombie = new Foe(9, 2, false, false, CombatCommit.NEVER);
        Foe other = new Foe(10, 2, false, false, CombatCommit.NEVER);
        assertEquals(Event.RUN_START, c.step(tick(6, skeleton(12, 40, true), zombie, other)));
        assertEquals(CombatCommit.Why.LOW_HP, c.why());
    }

    @Test
    public void inTheCooldownOnlyAFreshArrowCounts() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 6, 0, skeleton(12, 2, true), skeleton(7, 12, 2, true))));
        // the run ends once its line is gone for a moment
        long t = NOW + 1;
        Event ended = Event.NONE;
        for (; t < NOW + CombatCommit.RUN_CAP + 10 && ended == Event.NONE; t++) {
            ended = c.step(tick(t, 6, (t - NOW) * 0.5, skeleton(CombatRules.SHOOTER_RANGE + 1, 60, false), skeleton(7, CombatRules.SHOOTER_RANGE + 1, 60, false)));
        }
        assertNotEquals(Event.NONE, ended);
        assertEquals(Mode.NONE, c.mode());
        // still shooting a while ago: the cooldown does not start another run for old news
        assertEquals(Event.NONE, c.step(tick(t + 1, 6, 100, skeleton(12, 30, true), skeleton(7, 12, 30, true))));
        // a new arrow is news
        assertEquals(Event.RUN_START, c.step(tick(t + 2, 6, 100, skeleton(12, 1, true), skeleton(7, 12, 1, true))));
    }

    // the shooter follows and keeps a line the whole way. the run never loses its line, so it ends at the cap, and the next arrow
    // starts a new run from wherever we are by then (not from the old origin, where it would be over on the first tick)
    @Test
    public void aShooterThatFollowsEndsTheRunAtTheCapAndTheNextRunStartsFromHere() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 6, 0, skeleton(10, 2, true), skeleton(7, 10, 2, true))));
        assertEquals(0, c.originX(), 0);
        Event event = Event.NONE;
        long t = NOW + 1;
        for (; t < NOW + 2 * CombatCommit.RUN_CAP && event == Event.NONE; t++) {
            event = c.step(tick(t, 6, (t - NOW) * 0.25, skeleton(10, (t - NOW) % 50, true), skeleton(7, 10, (t - NOW) % 50, true)));
        }
        long at = t - 1;
        // the cap, and on the very same step the next arrow (it is fresh) starts the next run
        assertEquals(CombatCommit.RUN_CAP, at - NOW);
        assertEquals(Event.RUN_START, event);
        assertEquals(Event.RUN_CAP, c.endedFirst());
        assertEquals(CombatCommit.Why.SHOT, c.why());
        // the origin is where we were when it started, so the new run has 24 blocks of its own to make
        assertEquals((at - NOW) * 0.25, c.originX(), 1e-9);
        assertEquals(at, c.startTick());
    }
}
