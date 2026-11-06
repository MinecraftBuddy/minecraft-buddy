package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import org.junit.Test;

// the corners of the machine the main test does not walk: what a chained fight is allowed to chain to, a run that is not
// going anywhere, and the numbers the user picked. same style, foes are numbers
public class CombatCommitEdgesTest {

    private static final long NEVER = CombatCommit.NEVER;

    private static Foe zombie(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit);
    }

    private static Tick tick(long now, float health, Foe target, Foe... foes) {
        return new Tick(now, health, 0, 0, List.of(foes), target);
    }

    // a fight against zombie 1 that hit us, started at tick 100
    private static CombatCommit fighting() {
        CombatCommit commit = new CombatCommit();
        Foe z = zombie(1, 2, 3);
        assertEquals(Event.FIGHT_START, commit.step(tick(100, 20, null, z)));
        return commit;
    }

    @Test
    public void aDeadTargetOnlyChainsToSomethingThatHitUsInContact() {
        // another zombie standing on us that never swung is scenery, the fight is simply over
        CombatCommit commit = fighting();
        assertEquals(Event.FIGHT_DEAD, commit.step(tick(110, 20, null, zombie(2, 2, NEVER))));
        assertEquals(Mode.NONE, commit.mode());
        // one that did swing is the next fight, no gap
        CombatCommit other = fighting();
        assertEquals(Event.FIGHT_NEXT, other.step(tick(110, 20, null, zombie(2, 2, 3))));
        assertEquals(2, other.targetId());
        assertEquals(Why.HIT, other.why());
    }

    // ---- a mob we gave up on

    // a zombie that hit us in contact, then hung back at five blocks (never closer, never far enough to be lost) for ten
    // seconds: dropped
    private static CombatCommit droppedZombie() {
        CombatCommit commit = new CombatCommit();
        assertEquals(Event.FIGHT_START, commit.step(tick(100, 20, null, zombie(2, 3, 3))));
        Foe hangingBack = zombie(2, 5, 50);
        for (long t = 101; t < 100 + CombatCommit.STALL_TICKS; t++) {
            assertEquals("tick " + t, Event.NONE, commit.step(tick(t, 20, hangingBack, hangingBack)));
        }
        assertEquals(Event.FIGHT_STALLED, commit.step(tick(100 + CombatCommit.STALL_TICKS, 20, hangingBack, hangingBack)));
        assertTrue(commit.isIgnored(2));
        return commit;
    }

    private static final long DROPPED_AT = 100 + CombatCommit.STALL_TICKS;

    @Test
    public void aDeadTargetDoesNotChainToSomethingWeGaveUpOn() {
        CombatCommit commit = droppedZombie();
        // after the cooldown a fresh fight with zombie 1, then it dies while the dropped one stands in contact. its last hit
        // was before we gave up on it, so it is no news
        long later = DROPPED_AT + CombatCommit.COOLDOWN + 1;
        assertEquals(Event.FIGHT_START, commit.step(tick(later, 20, null, zombie(1, 2, 3))));
        long sinceItsLastHit = later + 5 - (DROPPED_AT - 10);
        assertEquals(Event.FIGHT_DEAD, commit.step(tick(later + 5, 20, null, zombie(2, 2, sinceItsLastHit))));
    }

    @Test
    public void theDroppedMobHittingUsAgainInContactIsJustAFightAgain() {
        // no "it is out of reach, run" any more: if it can hit us in contact we can hit it back
        CombatCommit commit = droppedZombie();
        assertTrue(commit.coolingDown(DROPPED_AT + 1));
        // a fresh hit goes through the cooldown
        assertEquals(Event.FIGHT_START, commit.step(tick(DROPPED_AT + 50, 20, null, zombie(2, 2, 0))));
        assertEquals(Why.HIT, commit.why());
        assertEquals(2, commit.targetId());
    }

    @Test
    public void aSkeletonThatGotUsFromRangeIsNeverAFightOrARun() {
        CombatCommit commit = new CombatCommit();
        for (long t = 100; t < 400; t++) {
            assertEquals("tick " + t, Event.NONE, commit.step(tick(t, 18, null, new Foe(7, 10, true, false, t % 30))));
        }
        assertEquals(Mode.NONE, commit.mode());
    }

    // ---- cornered

    @Test
    public void aCorneredFightDoesNotBailOnAWardenWalkingUp() {
        CombatCommit commit = new CombatCommit();
        Foe z = zombie(1, 1.5, NEVER);
        assertEquals(Event.RUN_START, commit.step(tick(100, 6, null, z)));
        // stuck on the spot with it on us for three seconds
        Event event = Event.NONE;
        for (long t = 101; t <= 101 + CombatCommit.CORNER_TICKS + 1 && event == Event.NONE; t++) {
            event = commit.step(tick(t, 6, null, z));
        }
        assertEquals(Event.RUN_TO_FIGHT, event);
        assertTrue(commit.cornered());
        // a warden walking up does not undo it, there is nowhere to run to
        Foe warden = new Foe(9, 5, false, false, NEVER, CombatCommit.Kind.UNTOUCHABLE);
        assertEquals(Event.NONE, commit.step(tick(400, 6, z, z, warden)));
        assertEquals(Mode.FIGHT, commit.mode());
    }

    @Test
    public void aCorneredFightNeverChainsIntoAWardenTouchingUs() {
        CombatCommit commit = new CombatCommit();
        Foe z = zombie(1, 1.5, NEVER);
        assertEquals(Event.RUN_START, commit.step(tick(100, 6, null, z)));
        Event event = Event.NONE;
        for (long t = 101; t <= 101 + CombatCommit.CORNER_TICKS + 1 && event == Event.NONE; t++) {
            event = commit.step(tick(t, 6, null, z));
        }
        assertEquals(Event.RUN_TO_FIGHT, event);
        // the zombie dies with a warden standing on us: nothing is chained to the warden, and it is a run, not a gap
        Foe warden = new Foe(9, 2, false, false, NEVER, CombatCommit.Kind.UNTOUCHABLE);
        assertEquals(Event.FIGHT_TO_RUN, commit.step(tick(400, 6, null, warden)));
        assertEquals(Mode.RUN, commit.mode());
        assertEquals(Why.HEAVY, commit.why());
        assertEquals(-1, commit.targetId());
    }

    @Test
    public void aCorneredFightWonAtFullHealthNextToAWardenIsARunAtOnce() {
        CombatCommit commit = new CombatCommit();
        Foe z = zombie(1, 1.5, NEVER);
        assertEquals(Event.RUN_START, commit.step(tick(100, 6, null, z)));
        Event event = Event.NONE;
        for (long t = 101; t <= 101 + CombatCommit.CORNER_TICKS + 1 && event == Event.NONE; t++) {
            event = commit.step(tick(t, 6, null, z));
        }
        assertEquals(Event.RUN_TO_FIGHT, event);
        // healed on the way (a golden apple), the zombie is dead, the warden is still on us: still not a cooldown gap
        Foe warden = new Foe(9, 2, false, false, NEVER, CombatCommit.Kind.UNTOUCHABLE);
        assertEquals(Event.FIGHT_TO_RUN, commit.step(tick(400, 20, null, warden)));
        assertEquals(Mode.RUN, commit.mode());
    }

    @Test
    public void aCorneredFightWonWithTheWardenFarOffIsJustOver() {
        CombatCommit commit = new CombatCommit();
        Foe z = zombie(1, 1.5, NEVER);
        assertEquals(Event.RUN_START, commit.step(tick(100, 6, null, z)));
        Event event = Event.NONE;
        for (long t = 101; t <= 101 + CombatCommit.CORNER_TICKS + 1 && event == Event.NONE; t++) {
            event = commit.step(tick(t, 6, null, z));
        }
        assertEquals(Event.RUN_TO_FIGHT, event);
        // the warden is ten blocks away, not on us and not booming us: the won fight is a won fight
        Foe warden = new Foe(9, 10, false, false, NEVER, CombatCommit.Kind.UNTOUCHABLE);
        assertEquals(Event.FIGHT_DEAD, commit.step(tick(400, 20, null, warden)));
        assertEquals(Mode.NONE, commit.mode());
    }

    // ---- runs that are not going anywhere

    @Test
    public void aRunWithNobodyNearStillEndsAtTheCapShortOfTwentyFourBlocks() {
        CombatCommit commit = new CombatCommit();
        assertEquals(Event.RUN_START, commit.step(tick(100, 5, null, zombie(1, 6, NEVER))));
        // walked four blocks and then nobody is there any more, and the ground goes on slowly: two blocks every three seconds
        // tops out at 20, short of 24 and nowhere near stuck, so it is the cap that ends it
        Tick far = new Tick(101, 5, 4, 0, List.of(), null);
        assertEquals(Event.NONE, commit.step(far));
        for (long t = 102; t < 100 + CombatCommit.RUN_CAP; t++) {
            double x = 4 + ((t - 101) / 60) * 2;
            assertEquals("tick " + t, Event.NONE, commit.step(new Tick(t, 5, x, 0, List.of(), null)));
        }
        assertEquals(Event.RUN_CAP, commit.step(new Tick(100 + CombatCommit.RUN_CAP, 5, 20, 0, List.of(), null)));
        assertEquals(Mode.NONE, commit.mode());
    }

    @Test
    public void aRunStandingShortOfTwentyFourBlocksWithNobodyNearIsStuckLongBeforeTheCap() {
        CombatCommit commit = new CombatCommit();
        assertEquals(Event.RUN_START, commit.step(tick(100, 5, null, zombie(1, 6, NEVER))));
        // the pathfinder has nowhere to take us and nothing is within eight
        for (long t = 101; t <= 101 + CombatCommit.RUN_STUCK_TICKS - 1; t++) {
            assertEquals("tick " + t, Event.NONE, commit.step(new Tick(t, 5, 10, 0, List.of(), null)));
        }
        assertEquals(Event.RUN_STUCK, commit.step(new Tick(101 + CombatCommit.RUN_STUCK_TICKS, 5, 10, 0, List.of(), null)));
        assertEquals(Mode.NONE, commit.mode());
        assertTrue(commit.coolingDown(101 + CombatCommit.RUN_STUCK_TICKS + 1));
    }

    // ---- cornered, the second tier

    @Test
    public void theSecondTierFightsTheNearestMobWithinEight() {
        CombatCommit commit = new CombatCommit();
        Foe near = new Foe(5, 6, true, false, NEVER);
        Foe far = new Foe(6, 7.5, false, false, NEVER);
        assertEquals(Event.RUN_START, commit.step(new Tick(100, 6, 0, 0, List.of(near, far), null)));
        Event event = Event.NONE;
        long t = 101;
        for (; t <= 101 + CombatCommit.CORNER_FAR_TICKS - 1 && event == Event.NONE; t++) {
            event = commit.step(new Tick(t, 6, 0, 0, List.of(near, far), null));
        }
        assertEquals(Event.NONE, event);
        assertEquals(Event.RUN_TO_FIGHT, commit.step(new Tick(t, 6, 0, 0, List.of(near, far), null)));
        assertEquals(5, commit.targetId());
        assertTrue(commit.cornered());
    }

    // hurt, boxed in, a skeleton across the water: the second tier tries a fight, the skeleton is past the lost range and
    // quiet so the fight is lost, and it is not picked again every six seconds with a run in between
    @Test
    public void aSkeletonAcrossTheWaterIsOnlyTriedOnce() {
        CombatCommit commit = new CombatCommit();
        Foe skeleton = new Foe(5, 7, true, false, NEVER);
        List<Foe> foes = List.of(skeleton);
        assertEquals(Event.RUN_START, commit.step(new Tick(100, 6, 0, 0, foes, null)));
        long t = 101;
        Event event = Event.NONE;
        for (; event == Event.NONE; t++) {
            event = commit.step(new Tick(t, 6, 0, 0, foes, null));
        }
        assertEquals(Event.RUN_TO_FIGHT, event);
        assertEquals(5, commit.targetId());
        // three seconds of it standing out there not hitting us
        long fightStart = t - 1;
        Event end = Event.NONE;
        for (; end == Event.NONE; t++) {
            end = commit.step(new Tick(t, 6, 0, 0, foes, skeleton));
        }
        assertEquals(Event.FIGHT_LOST, end);
        assertEquals(fightStart + 1 + CombatCommit.LOST_TICKS, t - 1);
        assertTrue(commit.isIgnored(5));
        // hurt with it still within 8 but not on us and not hitting us: the cooldown sits it out, and the fight that came out of
        // a run keeps the runs latched off while it stands there within 12, so that is it
        long over = t - 1 + CombatCommit.COOLDOWN;
        for (; t < over; t++) {
            assertEquals("tick " + t, Event.NONE, commit.step(new Tick(t, 6, 0, 0, foes, null)));
        }
        assertEquals(Event.NONE, commit.step(new Tick(t, 6, 0, 0, foes, null)));
        // and stuck on the spot for as long as we like, the second tier has nobody left to try
        for (long later = t + 1; later < t + 3 * CombatCommit.CORNER_FAR_TICKS; later++) {
            assertEquals("tick " + later, Event.NONE, commit.step(new Tick(later, 6, 0, 0, foes, null)));
        }
        assertEquals(Mode.NONE, commit.mode());
    }

    @Test
    public void theSecondTierSkipsCreepersButAZombieOnTopOfUsIsAlwaysFairGame() {
        CombatCommit commit = new CombatCommit();
        Foe creeper = new Foe(5, 6, false, true, NEVER);
        assertEquals(Event.RUN_START, commit.step(new Tick(100, 6, 0, 0, List.of(creeper), null)));
        for (long t = 101; t < 101 + 2 * CombatCommit.CORNER_FAR_TICKS; t++) {
            assertEquals(Event.NONE, commit.step(new Tick(t, 6, 0, 0, List.of(creeper), null)));
        }
        // a zombie that walks up to us gets the first tier's fight at once, the window has been open all this time
        Foe zombie = new Foe(6, 2, false, false, NEVER);
        assertEquals(Event.RUN_TO_FIGHT, commit.step(new Tick(101 + 2 * CombatCommit.CORNER_FAR_TICKS, 6, 0, 0, List.of(creeper, zombie), null)));
        assertEquals(6, commit.targetId());
    }

    @Test
    public void theFirstTierStillFiresAtThreeSecondsWithSomethingOnTopOfUs() {
        CombatCommit commit = new CombatCommit();
        Foe onUs = new Foe(5, 2, false, false, NEVER);
        assertEquals(Event.RUN_START, commit.step(new Tick(100, 6, 0, 0, List.of(onUs), null)));
        assertEquals(Event.NONE, commit.step(new Tick(101, 6, 0, 0, List.of(onUs), null)));
        assertEquals(Event.RUN_TO_FIGHT, commit.step(new Tick(101 + CombatCommit.CORNER_TICKS, 6, 0, 0, List.of(onUs), null)));
    }

    @Test
    public void theNumbersTheUserPicked() {
        assertEquals(24, CombatCommit.RUN_DISTANCE, 0);
        assertEquals(12, CombatCommit.RUN_CLEAR, 0);
        assertEquals(2 * 20, CombatCommit.RUN_CLEAR_TICKS);
        assertEquals(25 * 20, CombatCommit.RUN_CAP);
        assertEquals(5 * 20, CombatCommit.COOLDOWN);
        assertEquals(10 * 20, CombatCommit.STALL_TICKS);
        assertEquals(3 * 20, CombatCommit.CORNER_TICKS);
        assertEquals(8, CombatCommit.FLEE_HP, 0);
        assertEquals(3, CombatCommit.CONTACT, 0);
        assertEquals(15, CombatCommit.BOOM_RANGE, 0);
        assertEquals(6, CombatCommit.LOST_RANGE, 0);
        assertEquals(3 * 20, CombatCommit.LOST_TICKS);
        assertEquals(5 * 20, CombatCommit.RUN_STUCK_TICKS);
    }
}
