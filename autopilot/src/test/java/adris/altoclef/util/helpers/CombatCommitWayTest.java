package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import java.util.Set;
import org.junit.Test;

// a mob standing in the path (or boxed in a hole with us) is a fight before it swings, nothing else about the bold rules moves
public class CombatCommitWayTest {

    private static final long NEVER = CombatCommit.NEVER;

    private static Foe zombie(double distance) {
        return new Foe(1, distance, false, false, NEVER);
    }

    private static Tick tick(long now, List<Foe> foes, Foe target, Way way) {
        return new Tick(now, 20, 0, 0, foes, target, way);
    }

    private static Way inWay(int... ids) {
        Set<Integer> set = new java.util.HashSet<>();
        for (int id : ids) set.add(id);
        return new Way(set, true, 64);
    }

    private static Way pathing(double y) {
        return new Way(Set.of(), true, y);
    }

    @Test
    public void aMobInContactOnTheNextNodeIsAFight() {
        CombatCommit c = new CombatCommit();
        Foe z = zombie(1.5);
        assertEquals(Event.FIGHT_START, c.step(tick(0, List.of(z), null, inWay(1))));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.IN_WAY, c.why());
        assertEquals(1, c.targetId());
    }

    @Test
    public void inContactButNotInTheWayAndNotHittingIsNothing() {
        CombatCommit c = new CombatCommit();
        for (int t = 0; t < 400; t++) {
            // walking along fine (x moves), zombie beside us
            assertEquals(Event.NONE, c.step(new Tick(t, 20, t * 0.2, 0, List.of(zombie(2)), null, pathing(64))));
        }
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void inTheWayButOutOfContactIsNothing() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(0, List.of(zombie(3.5)), null, inWay(1))));
    }

    @Test
    public void boxedInForASecondAndAHalfWithAMobWithinThreeIsAFight() {
        CombatCommit c = new CombatCommit();
        Foe z = zombie(2);
        for (long t = 0; t < CombatCommit.WAY_BOXED_TICKS; t++) {
            assertEquals(Event.NONE, c.step(tick(t, List.of(z), null, pathing(64))));
        }
        assertEquals(Event.FIGHT_START, c.step(tick(CombatCommit.WAY_BOXED_TICKS, List.of(z), null, pathing(64))));
        assertEquals(Why.IN_WAY, c.why());
    }

    @Test
    public void aShortGapInThePathDoesNotStartTheBoxedClockOver() {
        CombatCommit c = new CombatCommit();
        Foe z = zombie(2);
        for (long t = 0; t < CombatCommit.WAY_BOXED_TICKS; t++) {
            // baritone gave up on a place and has not searched again yet, for a few ticks
            boolean gap = t >= 10 && t < 10 + CombatCommit.WAY_GAP_TICKS - 2;
            assertEquals(Event.NONE, c.step(tick(t, List.of(z), null, gap ? new Way(Set.of(), false, 64) : pathing(64))));
        }
        assertEquals(Event.FIGHT_START, c.step(tick(CombatCommit.WAY_BOXED_TICKS, List.of(z), null, pathing(64))));
    }

    @Test
    public void aLongGapDoesStartItOver() {
        CombatCommit c = new CombatCommit();
        Foe z = zombie(2);
        long t = 0;
        for (; t < 20; t++) c.step(tick(t, List.of(z), null, pathing(64)));
        for (; t <= 20 + CombatCommit.WAY_GAP_TICKS; t++) c.step(tick(t, List.of(z), null, new Way(Set.of(), false, 64)));
        long restart = t;
        for (; t < restart + CombatCommit.WAY_BOXED_TICKS; t++) {
            assertEquals(Event.NONE, c.step(tick(t, List.of(z), null, pathing(64))));
        }
        assertEquals(Event.FIGHT_START, c.step(tick(t, List.of(z), null, pathing(64))));
    }

    @Test
    public void aShooterInTheWayIsNotAFight() {
        CombatCommit c = new CombatCommit();
        Foe skeleton = new Foe(1, 2, true, false, NEVER);
        for (long t = 0; t < 200; t++) {
            assertEquals(Event.NONE, c.step(tick(t, List.of(skeleton), null, inWay(1))));
        }
    }

    @Test
    public void notPathingIsNotBoxedIn() {
        CombatCommit c = new CombatCommit();
        for (long t = 0; t < 200; t++) {
            assertEquals(Event.NONE, c.step(tick(t, List.of(zombie(2)), null, new Way(Set.of(), false, 64))));
        }
    }

    @Test
    public void pillaringUpIsProgress() {
        CombatCommit c = new CombatCommit();
        for (long t = 0; t < 200; t++) {
            // a block up every second, same x and z
            assertEquals(Event.NONE, c.step(tick(t, List.of(zombie(2)), null, pathing(64 + t / 20.0))));
        }
    }

    @Test
    public void aCreeperIsNeverInTheWay() {
        CombatCommit c = new CombatCommit();
        Foe creeper = new Foe(1, 1.2, false, true, NEVER);
        for (long t = 0; t < 200; t++) {
            assertEquals(Event.NONE, c.step(tick(t, List.of(creeper), null, inWay(1))));
        }
    }

    @Test
    public void theWardenIsNeverAnInTheWayFight() {
        CombatCommit c = new CombatCommit();
        Foe warden = new Foe(1, 2, false, false, NEVER, Kind.UNTOUCHABLE);
        assertEquals(Event.RUN_START, c.step(tick(0, List.of(warden), null, inWay(1))));
    }

    @Test
    public void theFightEndsWhenItStepsOutOfTheWay() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, List.of(zombie(1.5)), null, inWay(1)));
        assertEquals(Event.NONE, c.step(tick(1, List.of(zombie(3.9)), zombie(3.9), pathing(64))));
        assertEquals(Mode.FIGHT, c.mode());
        // (a melee foe has to stay out there, see the hold test)
        long t = 2;
        for (; t < 2 + CombatCommit.WAY_RELEASE_TICKS; t++) {
            assertEquals(Event.NONE, c.step(tick(t, List.of(zombie(4.5)), zombie(4.5), pathing(64))));
        }
        assertEquals(Event.FIGHT_CLEARED, c.step(tick(t, List.of(zombie(4.5)), zombie(4.5), pathing(64))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aKnockbackBlipPastTheLineDoesNotEndTheFight() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, List.of(zombie(1.5)), null, inWay(1)));
        // our own swing pushes it out for a few ticks, then it is back on us
        long t = 1;
        for (; t < 10; t++) assertEquals(Event.NONE, c.step(tick(t, List.of(zombie(4.5)), zombie(4.5), pathing(64))));
        assertEquals(Event.NONE, c.step(tick(t++, List.of(zombie(2)), zombie(2), pathing(64))));
        // and the clock starts over: another stretch shorter than the hold is still a fight
        for (long end = t + CombatCommit.WAY_RELEASE_TICKS - 1; t < end; t++) {
            assertEquals(Event.NONE, c.step(tick(t, List.of(zombie(4.5)), zombie(4.5), pathing(64))));
        }
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aShooterWayFightStillLetsGoAtOnce() {
        // the hold is for melee foes. a shooter (a sniper fight is PINNED, but the same target kind) keeps the old line
        CombatCommit c = new CombatCommit();
        c.step(tick(0, List.of(zombie(1.5)), null, inWay(1)));
        Foe skeleton = new Foe(1, 4.5, true, false, NEVER);
        assertEquals(Event.FIGHT_CLEARED, c.step(tick(1, List.of(skeleton), skeleton, pathing(64))));
    }

    @Test
    public void oneThatHitUsMeanwhileIsANormalFight() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, List.of(zombie(1.5)), null, inWay(1)));
        Foe hitter = new Foe(1, 4.5, false, false, 10);
        assertEquals(Event.NONE, c.step(tick(1, List.of(hitter), hitter, pathing(64))));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void theFightEndsWhenItDies() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, List.of(zombie(1.5)), null, inWay(1)));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(1, List.of(), null, pathing(64))));
    }

    @Test
    public void stillInTheWayRightAfterACooldownStartsAgain() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, List.of(zombie(1.5)), null, inWay(1)));
        long t = 1;
        for (; t <= 1 + CombatCommit.WAY_RELEASE_TICKS; t++) c.step(tick(t, List.of(zombie(5)), zombie(5), pathing(64)));
        assertEquals(Mode.NONE, c.mode());
        // the cooldown is on, but a mob back in the way is a path that cannot go on
        assertEquals(Event.FIGHT_START, c.step(tick(t, List.of(zombie(2)), null, inWay(1))));
    }

    @Test
    public void theLogLineSaysItWasInTheWay() {
        assertEquals("combat: [overworld] FIGHT zombie (in our way, hp 18)",
                CombatLog.line(baritone.api.utils.Dimension.OVERWORLD, Event.FIGHT_START, Why.IN_WAY, false, "zombie", "it", "", 18, 0));
        assertEquals("combat: [overworld] fight over, zombie is out of the way",
                CombatLog.line(baritone.api.utils.Dimension.OVERWORLD, Event.FIGHT_CLEARED, Why.NONE, false, "it", "zombie", "", 18, 0));
    }
}
