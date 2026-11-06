package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.RecoverHold.Body;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the fight follows whoever is actually hitting us, and a run with nowhere to go is not a run
public class CombatCommitTakeoverTest {

    private static final long NOW = 1000;
    private static final Body CAN_HEAL = new Body(20, true, false, false);

    // a mob that hit us a moment ago, with the eye to box gap somebody measured
    private static Foe zombie(int id, double distance, long sinceHit, double gap) {
        return new Foe(id, distance, false, false, sinceHit, Kind.NORMAL, gap);
    }

    private static Foe skeleton(int id, double distance, long sinceHit, double gap) {
        return new Foe(id, distance, true, false, sinceHit, Kind.NORMAL, gap);
    }

    private static Tick tick(long now, float health, Foe target, boolean boxed, Foe... foes) {
        return new Tick(now, health, 0, 0, List.of(foes), target, Way.NONE, true, false, false, CAN_HEAL, boxed);
    }

    private static CombatCommit fightingSkeleton() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, false, skeleton(1, 2, 1, 2))));
        assertEquals(1, c.targetId());
        return c;
    }

    @Test
    public void aShooterTargetWithAZombieThatHitUsInReachSwitchesOnce() {
        CombatCommit c = fightingSkeleton();
        Foe shooter = skeleton(1, 2, 20, 2);
        Foe biter = zombie(2, 1.5, 3, 1.4);
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 1, 20, shooter, false, shooter, biter)));
        assertEquals(2, c.targetId());
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(CombatCommit.Why.HIT, c.why());
        // and never back: the zombie is the target now and in reach
        for (long now = NOW + 2; now < NOW + 80; now++) {
            assertEquals(Event.NONE, c.step(tick(now, 20, zombie(2, 1.5, 30, 1.4), false, shooter, zombie(2, 1.5, 30, 1.4))));
        }
        assertEquals(2, c.targetId());
    }

    @Test
    public void aShooterTargetStaysWhenNothingMeleeHitUs() {
        CombatCommit c = fightingSkeleton();
        Foe shooter = skeleton(1, 2, 20, 2);
        // a zombie that hit us but stands just out of reach
        Foe far = zombie(3, 3.4, 5, 3.2);
        for (long now = NOW + 1; now < NOW + 60; now++) {
            assertEquals(Event.NONE, c.step(tick(now, 20, shooter, false, shooter, far)));
        }
        // and one in reach that never did
        Foe quiet = zombie(2, 1.5, CombatCommit.NEVER, 1.4);
        assertEquals(Event.NONE, c.step(tick(NOW + 60, 20, shooter, false, shooter, quiet)));
        assertEquals(1, c.targetId());
    }

    @Test
    public void twoZombiesTakingTurnsThroughIFramesDoNotPingPong() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, false, zombie(1, 1.5, 1, 1.4))));
        for (long now = NOW + 1; now < NOW + 200; now++) {
            // each one hits on alternate half seconds, both stand in reach the whole time
            boolean first = (now / 10) % 2 == 0;
            Foe a = zombie(1, 1.5, first ? 2 : 12, 1.4);
            Foe b = zombie(2, 1.6, first ? 12 : 2, 1.5);
            assertEquals("tick " + now, Event.NONE, c.step(tick(now, 20, a, false, a, b)));
        }
        assertEquals(1, c.targetId());
    }

    @Test
    public void aMeleeTargetOutOfReachForTwentyTicksHandsOverToTheOneInReach() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, false, zombie(1, 1.5, 1, 1.4))));
        Foe away = zombie(1, 4, 40, 3.8);
        Foe biter = zombie(2, 1.5, 3, 1.4);
        for (long now = NOW + 1; now < NOW + 1 + CombatCommit.TAKEOVER_TICKS; now++) {
            assertEquals("tick " + now, Event.NONE, c.step(tick(now, 20, away, false, away, biter)));
        }
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 1 + CombatCommit.TAKEOVER_TICKS, 20, away, false, away, biter)));
        assertEquals(2, c.targetId());
    }

    @Test
    public void aTargetThatComesBackIntoReachStartsTheTakeoverClockOver() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, false, zombie(1, 1.5, 1, 1.4))));
        Foe away = zombie(1, 4, 40, 3.8);
        Foe near = zombie(1, 1.5, 40, 1.4);
        Foe biter = zombie(2, 1.5, 3, 1.4);
        for (long now = NOW + 1; now < NOW + 15; now++) {
            c.step(tick(now, 20, away, false, away, biter));
        }
        c.step(tick(NOW + 15, 20, near, false, near, biter));
        for (long now = NOW + 16; now < NOW + 16 + CombatCommit.TAKEOVER_TICKS; now++) {
            assertEquals("tick " + now, Event.NONE, c.step(tick(now, 20, away, false, away, biter)));
        }
        assertEquals(1, c.targetId());
    }

    // ---- boxed in

    private static Tick crowd(boolean boxed) {
        return tick(NOW, 20, null, boxed, zombie(1, 1.5, CombatCommit.NEVER, 1.4), zombie(2, 2, CombatCommit.NEVER, 1.9),
                zombie(3, 2.5, CombatCommit.NEVER, 2.4));
    }

    @Test
    public void aCrowdWithNowhereToGoIsTheCorneredFightStraightAway() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_TO_FIGHT, c.step(crowd(true)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(1, c.targetId());
        assertTrue(c.cornered());
        assertEquals(CombatCommit.Why.CORNERED, c.why());
    }

    @Test
    public void aCrowdInTheOpenIsStillARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(crowd(false)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void lowHpWithNowhereToGoFightsAndWithRoomRuns() {
        Foe close = zombie(1, 2, CombatCommit.NEVER, 1.9);
        assertEquals(Event.RUN_TO_FIGHT, new CombatCommit().step(tick(NOW, 7, null, true, close)));
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(NOW, 7, null, false, close)));
    }

    @Test
    public void aHeavyHitterIsStillARunEvenBoxedIn() {
        Foe heavy = new Foe(1, 2, false, false, 1, Kind.HEAVY, 1.9);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 10, null, true, heavy)));
        assertEquals(CombatCommit.Why.HEAVY, c.why());
    }

    @Test
    public void aBoxedFightThatWouldBailForACrowdKeepsFighting() {
        Foe first = zombie(1, 1.5, 1, 1.4);
        CombatCommit open = new CombatCommit();
        assertEquals(Event.FIGHT_START, open.step(tick(NOW, 20, null, false, first)));
        Foe second = zombie(2, 2, 2, 1.9);
        // hurt with two hitting us is a crowd
        assertEquals(Event.FIGHT_TO_RUN, open.step(tick(NOW + 1, 11, first, false, first, second)));

        CombatCommit boxed = new CombatCommit();
        assertEquals(Event.FIGHT_START, boxed.step(tick(NOW, 20, null, true, first)));
        assertEquals(Event.NONE, boxed.step(tick(NOW + 1, 11, first, true, first, second)));
        assertEquals(Mode.FIGHT, boxed.mode());
        assertTrue(boxed.cornered());
    }

    @Test
    public void whenTheBoxOpensOnlyTheDangerBailIsBack() {
        Foe first = zombie(1, 1.5, 1, 1.4);
        Foe second = zombie(2, 2, 2, 1.9);
        Foe warden = new Foe(3, 6, false, false, CombatCommit.NEVER, CombatCommit.Kind.UNTOUCHABLE);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, true, first)));
        assertEquals(Event.NONE, c.step(tick(NOW + 1, 11, first, true, first, second)));
        assertTrue(c.cornered());
        // the gap is free now and two are still hitting us: the box made this the cornered fight and the latch holds, hp is no reason
        assertEquals(Event.NONE, c.step(tick(NOW + 2, 11, first, false, first, second)));
        // but the warden walking up still is
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 3, 11, first, false, first, second, warden)));
    }

    // ---- the hold

    @Test
    public void aFightThatCameFromARunHoldsAtTenHp() {
        CombatCommit c = new CombatCommit();
        // low hp with nowhere to go starts it, a bit of regen later the zombie dies at ten
        assertEquals(Event.RUN_TO_FIGHT, c.step(tick(NOW, 7, null, true, zombie(1, 1.5, 1, 1.4))));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 5, 10, null, true)));
        assertTrue(c.recovering());
    }

    @Test
    public void aPlainFightThatEndsAtTenHpIsAWonFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 10, null, false, zombie(1, 1.5, 1, 1.4))));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 5, 10, null, false)));
        assertFalse(c.recovering());
    }

    @Test
    public void aDeadPlayerStartsNoHold() {
        RecoverHold hold = new RecoverHold();
        hold.ended(NOW, Mode.RUN, 0, CAN_HEAL);
        hold.ended(NOW, Mode.FIGHT, 0, CAN_HEAL);
        assertFalse(hold.on());
    }

    private static final RecoverHold.Body CAN_HEAL_HURTING = new RecoverHold.Body(20, true, false, false, true);

    @Test
    public void aFightEndedAtEightAndAHalfWithWitherOnStartsAHold() {
        RecoverHold hold = new RecoverHold();
        hold.ended(NOW, Mode.FIGHT, 8.5f, CAN_HEAL_HURTING);
        assertTrue(hold.on());
    }

    @Test
    public void theSameFightWithNothingLingeringIsAWonFight() {
        RecoverHold hold = new RecoverHold();
        hold.ended(NOW, Mode.FIGHT, 8.5f, CAN_HEAL);
        assertFalse(hold.on());
    }

    @Test
    public void lingeringPausesTheStallClockButNotTheCap() {
        RecoverHold hold = new RecoverHold();
        hold.ended(NOW, Mode.FIGHT, 8.5f, CAN_HEAL_HURTING);
        // hp only goes down, a whole stall window and then some
        long t = NOW + RecoverHold.STALL_TICKS * 2;
        hold.update(t, 7f, false, CAN_HEAL_HURTING);
        assertTrue(hold.on());
        assertEquals(0, hold.stallTicks());
        // effect gone, clock runs again
        hold.update(t + RecoverHold.STALL_TICKS, 7f, false, CAN_HEAL);
        assertFalse(hold.on());

        RecoverHold capped = new RecoverHold();
        capped.ended(NOW, Mode.FIGHT, 8.5f, CAN_HEAL_HURTING);
        capped.update(NOW + RecoverHold.CAP_TICKS, 7f, false, CAN_HEAL_HURTING);
        assertFalse(capped.on());
    }
}
