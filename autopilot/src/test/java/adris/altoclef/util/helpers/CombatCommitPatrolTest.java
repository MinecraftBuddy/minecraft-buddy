package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

// a pillager patrol keeps up with a run, so dressed and armed it is one target at a time until the hp line. the flag is the
// whole difference: the same five shooters without it are still the old run
public class CombatCommitPatrolTest {

    private static final long NOW = 1000;
    // a shot a little while ago: still shooting(), but too old for what a cooldown lets through
    private static final long LATELY = 30;

    private static Foe shooter(int id, double distance, long sinceHit, boolean sight) {
        return new Foe(id, distance, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, sight, CombatCommit.NEVER).withFast(true);
    }

    private static Foe pillager(int id, double distance, long sinceHit) {
        return shooter(id, distance, sinceHit, true).withPatrol(true);
    }

    private static Foe zombie(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit);
    }

    // five of them, the nearest is id 1
    private static List<Foe> patrol() {
        return List.of(pillager(1, 4, 3), pillager(2, 5, 3), pillager(3, 6, 3), pillager(4, 7, 3), pillager(5, 9, 3));
    }

    private static Tick tick(long now, float hp, Foe target, List<Foe> foes) {
        return new Tick(now, hp, 0, 0, foes, target, Way.NONE, true, false);
    }

    private static Tick boxedTick(long now, float hp, Foe target, List<Foe> foes) {
        return new Tick(now, hp, 0, 0, foes, target, Way.NONE, true, false, false, RecoverHold.Body.NONE, true);
    }

    private static List<Foe> without(List<Foe> foes, int id) {
        List<Foe> rest = new ArrayList<>(foes);
        rest.removeIf(foe -> foe.id() == id);
        return rest;
    }

    // a fight going on the nearest of the five
    private static CombatCommit fighting() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, patrol())));
        return c;
    }

    @Test
    public void fiveOnUsAtFullHpIsAFightWithTheNearest() {
        CombatCommit c = fighting();
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.PINNED, c.why());
        assertEquals(1, c.targetId());
    }

    @Test
    public void theSameFiveWithoutTheFlagAreStillTheOldRuns() {
        // three of them within the swarm range that hit us: a crowd
        List<Foe> close = List.of(shooter(1, 4, 3, true), shooter(2, 5, 3, true), shooter(3, 6, 3, true), shooter(4, 7, 3, true), shooter(5, 9, 3, true));
        CombatCommit crowd = new CombatCommit();
        assertEquals(Event.RUN_START, crowd.step(tick(NOW, 20, null, close)));
        assertEquals(Why.CROWD, crowd.why());
        // further out they are just two shooters too many
        List<Foe> far = List.of(shooter(1, 8, 3, true), shooter(2, 9, 3, true), shooter(3, 10, 3, true), shooter(4, 11, 3, true), shooter(5, 12, 3, true));
        CombatCommit shot = new CombatCommit();
        assertEquals(Event.RUN_START, shot.step(tick(NOW, 20, null, far)));
        assertEquals(Why.SHOT, shot.why());
    }

    @Test
    public void theFightStaysForTheOtherFour() {
        CombatCommit c = fighting();
        List<Foe> foes = patrol();
        assertEquals(Event.NONE, c.step(tick(NOW + 1, 20, foes.get(0), foes)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(1, c.targetId());
    }

    @Test
    public void aKillGoesOnToTheNearestOneShootingWithNoGap() {
        CombatCommit c = fighting();
        List<Foe> rest = List.of(pillager(2, 8, LATELY), pillager(3, 7, LATELY), pillager(4, 9, LATELY));
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 1, 20, null, rest)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.PINNED, c.why());
        assertEquals(3, c.targetId());
    }

    @Test
    public void theNextOneIsNotLostForStandingAWalkAway() {
        CombatCommit c = fighting();
        Foe next = pillager(2, 8, LATELY);
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 1, 20, null, List.of(next))));
        // a HIT fight past LOST_RANGE lets go after LOST_TICKS, this one is out there by design
        for (long at = NOW + 2; at < NOW + 2 + CombatCommit.LOST_TICKS + 20; at++) {
            assertEquals(Event.NONE, c.step(tick(at, 20, next, List.of(next))));
        }
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(2, c.targetId());
    }

    @Test
    public void oneInContactThatHitUsGoesFirst() {
        CombatCommit c = fighting();
        List<Foe> rest = List.of(pillager(2, 2, 3), pillager(3, 1 + CombatRules.SWARM_RANGE, LATELY));
        assertEquals(Event.FIGHT_NEXT, c.step(tick(NOW + 1, 20, null, rest)));
        assertEquals(2, c.targetId());
        assertEquals(Why.HIT, c.why());
    }

    @Test
    public void oneThatIsNotShootingIsLeftAlone() {
        CombatCommit c = fighting();
        // no line, then no shot in a long while. neither is on us, the task can walk on
        Foe hidden = shooter(2, 8, LATELY, false).withPatrol(true);
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 1, 20, null, List.of(hidden))));
        assertEquals(Mode.NONE, c.mode());
        CombatCommit old = fighting();
        Foe quiet = pillager(2, 8, CombatCommit.SHOT_MEMORY + 1);
        assertEquals(Event.FIGHT_DEAD, old.step(tick(NOW + 1, 20, null, List.of(quiet))));
        assertEquals(Mode.NONE, old.mode());
    }

    @Test
    public void withoutTheFlagAKillIsStillFightOver() {
        CombatCommit c = new CombatCommit();
        Foe first = shooter(1, 4, 3, true);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, List.of(first))));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 1, 20, null, List.of(shooter(2, 8, LATELY, true)))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void boxedInThereIsNoWalkingOverToTheNextOne() {
        CombatCommit c = fighting();
        assertEquals(Event.FIGHT_DEAD, c.step(boxedTick(NOW + 1, 20, null, List.of(pillager(2, 8, LATELY)))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void theHpLineStillEndsIt() {
        CombatCommit c = fighting();
        List<Foe> foes = patrol();
        // two a bolt: at 12 the next one leaves 10, at 10 it leaves the line
        long at = NOW;
        for (float hp = 18; hp >= 12; hp -= 2) {
            assertEquals(Event.NONE, c.step(tick(++at, hp, foes.get(0), foes)));
        }
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(++at, 10, foes.get(0), foes)));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void underTheLineItIsNotAFightToStart() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, CombatCommit.FLEE_HP, null, patrol())));
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void aKillAtTheLineIsARunAndNotTheNextOne() {
        CombatCommit c = fighting();
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 1, CombatCommit.FLEE_HP, null, List.of(pillager(2, 8, LATELY), pillager(3, 7, LATELY)))));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void oneRealSkeletonWithThePatrolIsNoSecondShooter() {
        CombatCommit c = new CombatCommit();
        List<Foe> foes = List.of(pillager(1, 7, 3), shooter(2, 9, 3, true));
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, foes)));
        assertEquals(Why.PINNED, c.why());
        assertEquals(1, c.targetId());
        assertEquals(Event.NONE, c.step(tick(NOW + 1, 20, foes.get(0), foes)));
    }

    @Test
    public void twoRealSkeletonsWithThePatrolAreStillARun() {
        List<Foe> foes = List.of(pillager(1, 7, 3), shooter(2, 9, 3, true), shooter(3, 10, 3, true));
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, null, foes)));
        assertEquals(Why.SHOT, c.why());
        // and the same two turning up mid fight end it
        CombatCommit mid = new CombatCommit();
        Foe target = pillager(1, 7, 3);
        assertEquals(Event.FIGHT_START, mid.step(tick(NOW, 20, null, List.of(target))));
        assertEquals(Event.FIGHT_TO_RUN, mid.step(tick(NOW + 1, 20, target, foes)));
        assertEquals(Why.SHOT, mid.why());
    }

    @Test
    public void aRealCrowdOnUsIsStillACrowd() {
        List<Foe> foes = List.of(zombie(1, 2, 3), zombie(2, 2.5, 3), zombie(3, 2.8, 3), pillager(4, 7, 3));
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, null, foes)));
        assertEquals(Why.CROWD, c.why());
        // two zombies and the patrol is under the headcount, the zombie in contact is the swing
        CombatCommit under = new CombatCommit();
        assertEquals(Event.FIGHT_START, under.step(tick(NOW, 20, null, without(foes, 3))));
        assertEquals(1, under.targetId());
    }
}
