package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import adris.altoclef.util.helpers.DangerMeter.Level;
import java.util.List;
import org.junit.Test;

// S137 step 3 (risk_flee): the danger meter decides the runs. at flee a run starts (Why.DANGER) and goes on till the meter
// has been under cover for RUN_CLEAR_TICKS; the count rules (a crowd, the flee line, a heavy hitter) start none; what is not
// about how much danger (the warden, a box) keeps its rule
public class CombatCommitMeterTest {

    private static final long NOW = 1000;
    private static final long NEVER = CombatCommit.NEVER;

    private static Foe zombie(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit);
    }

    private static Tick tick(long now, float hp, double x, Foe... foes) {
        return new Tick(now, hp, x, 0, List.of(foes), null, Way.NONE, true);
    }

    private static Tick boxed(long now, float hp, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), null, Way.NONE, true, false, false, RecoverHold.Body.NONE, true);
    }

    private static CombatCommit at(Level level) {
        CombatCommit c = new CombatCommit();
        c.meter(level);
        return c;
    }

    @Test
    public void theMeterAtFleeIsARunAtFullHealth() {
        CombatCommit c = at(Level.FLEE);
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, 0, zombie(1, 1, 3))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.DANGER, c.why());
    }

    @Test
    public void aCrowdIsNoRunUnlessTheMeterSaysSo() {
        // the count rules: three that hit us are a crowd, a run at any hp
        CombatCommit count = new CombatCommit();
        count.step(tick(NOW, 20, 0, zombie(1, 2, 3), zombie(2, 4, 3), zombie(3, 2.5, 3)));
        assertEquals(Why.CROWD, count.why());
        // the meter at fight: the one on us is fought
        CombatCommit c = at(Level.FIGHT);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, 0, zombie(1, 2, 3), zombie(2, 4, 3), zombie(3, 2.5, 3))));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void underTheFleeLineTheMeterStillDecides() {
        // hp 6 with a zombie on us: the count rules run, the meter at fight fights
        CombatCommit count = new CombatCommit();
        count.step(tick(NOW, 6, 0, zombie(1, 1, 3)));
        assertEquals(Mode.RUN, count.mode());
        CombatCommit c = at(Level.FIGHT);
        c.step(tick(NOW, 6, 0, zombie(1, 1, 3)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aFightBailsWhenTheMeterTurnsToFlee() {
        CombatCommit c = at(Level.FIGHT);
        c.step(tick(NOW, 20, 0, zombie(1, 1, 3)));
        assertEquals(Mode.FIGHT, c.mode());
        c.meter(Level.FLEE);
        assertEquals(Event.FIGHT_TO_RUN, c.step(new Tick(NOW + 5, 20, 0, 0, List.of(zombie(1, 1, 3), zombie(2, 2, 3)), zombie(1, 1, 3),
                Way.NONE, true)));
        assertEquals(Why.DANGER, c.why());
    }

    @Test
    public void theRunIsOverOnceTheMeterHasBeenUnderCoverTwoSeconds() {
        CombatCommit c = at(Level.FLEE);
        c.step(tick(NOW, 20, 0, zombie(1, 1, 3)));
        assertEquals(Mode.RUN, c.mode());
        // cover keeps it going, however far it got
        c.meter(Level.COVER);
        for (long t = 1; t <= 100; t++) {
            assertEquals(Event.NONE, c.step(tick(NOW + t, 20, t)));
        }
        assertEquals(Mode.RUN, c.mode());
        c.meter(Level.CALM);
        long calmAt = NOW + 101;
        for (long t = 0; t < CombatCommit.RUN_CLEAR_TICKS; t++) {
            assertEquals(Event.NONE, c.step(tick(calmAt + t, 20, 101 + t)));
        }
        assertEquals(Event.RUN_CLEAR, c.step(tick(calmAt + CombatCommit.RUN_CLEAR_TICKS, 20, 101 + CombatCommit.RUN_CLEAR_TICKS)));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aFleeInBetweenStartsTheCalmClockOver() {
        CombatCommit c = at(Level.FLEE);
        c.step(tick(NOW, 20, 0, zombie(1, 1, 3)));
        c.meter(Level.FIGHT);
        for (long t = 1; t < CombatCommit.RUN_CLEAR_TICKS; t++) c.step(tick(NOW + t, 20, t));
        c.meter(Level.FLEE);
        c.step(tick(NOW + CombatCommit.RUN_CLEAR_TICKS, 20, CombatCommit.RUN_CLEAR_TICKS));
        c.meter(Level.FIGHT);
        assertNotEquals(Event.RUN_CLEAR, c.step(tick(NOW + CombatCommit.RUN_CLEAR_TICKS + 1, 20, CombatCommit.RUN_CLEAR_TICKS + 1)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void boxedInTheMeterFleeIsTheCorneredFight() {
        CombatCommit c = at(Level.FLEE);
        assertEquals(Event.RUN_TO_FIGHT, c.step(boxed(NOW, 20, zombie(1, 1, 3))));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.CORNERED, c.why());
    }

    @Test
    public void theWardenIsARunWhateverTheMeterSays() {
        CombatCommit c = at(Level.CALM);
        c.step(tick(NOW, 20, 0, new Foe(9, 5, false, false, NEVER, Kind.UNTOUCHABLE)));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.HEAVY, c.why());
    }

    @Test
    public void aHeavyHitterAtItsLineIsNoRunUnderTheMeter() {
        // hp 9 with a hoglin in contact: the count rules run (HEAVY_FLEE_HP), the meter at fight does not
        CombatCommit count = new CombatCommit();
        count.step(tick(NOW, 9, 0, new Foe(4, 1, false, false, 3, Kind.HEAVY)));
        assertEquals(Mode.RUN, count.mode());
        CombatCommit c = at(Level.FIGHT);
        c.step(tick(NOW, 9, 0, new Foe(4, 1, false, false, 3, Kind.HEAVY)));
        assertNotEquals(Mode.RUN, c.mode());
    }

    // the first smoke: 130 of 161 runs for the meter were "from nothing in sight", on the spot. where the mob field prices the
    // buddy's own cell calm the flee search has nowhere to go, and the meter at flee starts no run: the fight goes on
    @Test
    public void onGroundTheFieldPricesCalmTheMeterAtFleeStartsNoRun() {
        CombatCommit c = new CombatCommit();
        c.meter(Level.FLEE, true);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, 0, zombie(1, 1, 3))));
        assertEquals(Mode.FIGHT, c.mode());
        // off that ground it is a run again
        c.meter(Level.FLEE, false);
        assertEquals(Event.FIGHT_TO_RUN, c.step(new Tick(NOW + 5, 20, 0, 0, List.of(zombie(1, 1, 3), zombie(2, 2, 3)), zombie(1, 1, 3),
                Way.NONE, true)));
        assertEquals(Why.DANGER, c.why());
        // with nothing about and the meter at flee: nothing
        CombatCommit idle = new CombatCommit();
        idle.meter(Level.FLEE, true);
        assertEquals(Event.NONE, idle.step(tick(NOW, 20, 0)));
        assertEquals(Mode.NONE, idle.mode());
    }

    // a run that came to calm ground is over after RUN_CLEAR_TICKS there though the meter still says flee, and no run for the
    // meter starts for BLIND_TICKS after: the field does not price what the meter counts, so the next run would be a step
    // off this ground and back (the rhythm of the first smoke: 109 of 157 runs began within 3 s of the last one's end)
    @Test
    public void aRunThatCameToCalmGroundEndsThereAndIsNotStartedAgainAtOnce() {
        CombatCommit c = at(Level.FLEE);
        c.step(tick(NOW, 20, 0, zombie(1, 1, 3)));
        assertEquals(Mode.RUN, c.mode());
        // on the way: no calm ground yet, the run goes on
        for (long t = 1; t <= 20; t++) assertEquals(Event.NONE, c.step(tick(NOW + t, 20, t)));
        // at the goal, the meter still at flee
        c.meter(Level.FLEE, true);
        long at = NOW + 21;
        for (long t = 0; t < CombatCommit.RUN_CLEAR_TICKS; t++) assertEquals(Event.NONE, c.step(tick(at + t, 20, 21)));
        assertEquals(Event.RUN_CLEAR, c.step(tick(at + CombatCommit.RUN_CLEAR_TICKS, 20, 21)));
        assertEquals(Mode.NONE, c.mode());
        String note = c.takeNote();
        assertEquals(true, note != null && note.contains("still at flee"));
        // a step off that ground with the meter at flee: no run while the field is known blind
        long end = at + CombatCommit.RUN_CLEAR_TICKS;
        c.meter(Level.FLEE, false);
        for (long t = 1; t < CombatCommit.BLIND_TICKS; t += 7) {
            c.step(tick(end + t, 20, 22));
            assertNotEquals(Mode.RUN, c.mode());
        }
        // after it the meter decides again
        c.step(tick(end + CombatCommit.BLIND_TICKS, 20, 22, zombie(1, 1, 3)));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.DANGER, c.why());
    }

    // a run the meter itself called over (calm for two seconds) leaves no such window: the next flee is a run at once
    @Test
    public void aRunTheMeterCalledOverLeavesTheNextFleeARun() {
        CombatCommit c = at(Level.FLEE);
        c.step(tick(NOW, 20, 0, zombie(1, 1, 3)));
        c.meter(Level.CALM, true);
        for (long t = 1; t <= CombatCommit.RUN_CLEAR_TICKS + 1; t++) c.step(tick(NOW + t, 20, t));
        assertEquals(Mode.NONE, c.mode());
        assertEquals(null, c.takeNote());
        c.meter(Level.FLEE, false);
        long later = NOW + CombatCommit.RUN_CLEAR_TICKS + 200;
        c.step(tick(later, 20, 60, zombie(1, 1, 3)));
        assertEquals(Mode.RUN, c.mode());
    }

    // the third smoke: a wither skeleton kept hitting through the runs for the meter's flee (10 of its 13 hits landed in one),
    // and at the cap the next run began on the same step. two hits after the grace turn the run into a fight with the
    // hitter, and that fight holds while the meter stays at flee
    @Test
    public void aChaserThatKeepsHittingThroughAMeterRunIsFought() {
        CombatCommit c = at(Level.FLEE);
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, 0, zombie(1, 1, 3))));
        long t = NOW;
        // the grace: a hit in it is not counted
        t += 10;
        assertEquals(Event.NONE, c.step(tick(t, 18, 5, zombie(1, 2, 1))));
        // the first counted hit
        t = NOW + CombatCommit.RUN_HIT_GRACE + 10;
        assertEquals(Event.NONE, c.step(tick(t, 14, 20, zombie(1, 2, 1))));
        assertEquals(Mode.RUN, c.mode());
        // the second: the run is not shaking it
        t += 20;
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(t, 10, 30, zombie(1, 2, 1))));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.CHASED, c.why());
        // the meter still says flee: the fight goes on, with a second mob about too
        for (long i = 1; i <= 40; i++) {
            c.step(new Tick(t + i, 8, 30, 0, List.of(zombie(1, 1, 3), zombie(2, 6, NEVER)), zombie(1, 1, 3), Way.NONE, true));
            assertEquals(Mode.FIGHT, c.mode());
            assertEquals(1, c.targetId());
        }
    }

    // unarmed the hits are no fight, and the warden about holds the turn back as it always did
    @Test
    public void theHitsTurnNoRunUnarmedOrWithTheWardenAbout() {
        CombatCommit bare = at(Level.FLEE);
        bare.step(new Tick(NOW, 20, 0, 0, List.of(zombie(1, 1, 3)), null, Way.NONE, false));
        assertEquals(Mode.RUN, bare.mode());
        long t = NOW + CombatCommit.RUN_HIT_GRACE + 10;
        bare.step(new Tick(t, 14, 20, 0, List.of(zombie(1, 2, 1)), null, Way.NONE, false));
        bare.step(new Tick(t + 20, 10, 30, 0, List.of(zombie(1, 2, 1)), null, Way.NONE, false));
        assertEquals(Mode.RUN, bare.mode());

        CombatCommit c = at(Level.FLEE);
        Foe warden = new Foe(9, 5, false, false, NEVER, Kind.UNTOUCHABLE);
        c.step(tick(NOW, 20, 0, zombie(1, 1, 3), warden));
        assertEquals(Mode.RUN, c.mode());
        c.step(tick(t, 14, 20, zombie(1, 2, 1), warden));
        c.step(tick(t + 20, 10, 30, zombie(1, 2, 1), warden));
        assertEquals(Mode.RUN, c.mode());
    }

    // at the cap with the meter at flee: a melee chaser in contact is fought, and that fight holds; with nobody on the buddy
    // the run ends and the next one starts
    @Test
    public void atTheCapAChaserOnTheBuddyIsFoughtThoughTheMeterSaysFlee() {
        CombatCommit c = at(Level.FLEE);
        c.step(tick(NOW, 20, 0, zombie(1, 1, 3)));
        // a run with the chaser a few blocks behind, never hitting
        for (long i = 1; i < CombatCommit.RUN_CAP; i++) {
            assertEquals(Event.NONE, c.step(tick(NOW + i, 20, i, zombie(1, 6, NEVER))));
        }
        long cap = NOW + CombatCommit.RUN_CAP;
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(cap, 20, CombatCommit.RUN_CAP, zombie(1, 1, NEVER))));
        assertEquals(Mode.FIGHT, c.mode());
        c.step(new Tick(cap + 1, 20, CombatCommit.RUN_CAP, 0, List.of(zombie(1, 1, 3)), zombie(1, 1, 3), Way.NONE, true));
        assertEquals(Mode.FIGHT, c.mode());

        CombatCommit far = at(Level.FLEE);
        far.step(tick(NOW, 20, 0, zombie(1, 1, 3)));
        for (long i = 1; i < CombatCommit.RUN_CAP; i++) {
            assertEquals(Event.NONE, far.step(tick(NOW + i, 20, i, zombie(1, 6, NEVER))));
        }
        far.step(tick(cap, 20, CombatCommit.RUN_CAP, zombie(1, 6, NEVER)));
        assertNotEquals(Mode.FIGHT, far.mode());
    }

    // a turn on the hits under the meter's flee line keeps the old rule: that fight still bails once when the meter turns
    @Test
    public void aTurnUnderTheFleeLineStillBailsWhenTheMeterTurnsToFlee() {
        CombatCommit c = at(Level.FLEE);
        c.step(tick(NOW, 20, 0, zombie(1, 1, 3)));
        c.meter(Level.COVER);
        long t = NOW + CombatCommit.RUN_HIT_GRACE + 10;
        c.step(tick(t, 14, 20, zombie(1, 2, 1)));
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(t + 20, 10, 30, zombie(1, 2, 1))));
        c.meter(Level.FLEE);
        assertEquals(Event.FIGHT_TO_RUN, c.step(new Tick(t + 25, 10, 30, 0, List.of(zombie(1, 1, 3)), zombie(1, 1, 3), Way.NONE, true)));
        assertEquals(Why.DANGER, c.why());
    }
}
