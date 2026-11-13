package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.WallState;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import java.util.function.LongFunction;
import org.junit.Test;

// (unarmed_no_corner_fight) an unarmed run with no room puts up a wall instead of the bare-handed fight; with nothing to wall
// with, or armed, it is the fight as before
public class CombatCommitCornerWallTest {

    private static final long NOW = 1000;
    private static final long HIT = NOW - 5;

    // a zombie in contact, its last hit at hitAt
    private static Foe zombie(long now, long hitAt) {
        return new Foe(7, 1.2, false, false, now - hitAt, Kind.NORMAL, 0.2);
    }

    // a run that is trying to move and does not (x and z stay put)
    private static Tick tick(long now, boolean armed, Foe... foes) {
        return new Tick(now, 17, 0, 0, List.of(foes), null, Way.NONE, armed, true);
    }

    // an unarmed run from a zombie that just hit us, with the wall's switch and facts as given
    private static CombatCommit unarmedRun(boolean on, boolean offered) {
        CombatCommit c = new CombatCommit();
        c.unarmedNoCornerFight(on);
        c.wallFacts(offered, WallState.NONE);
        assertEquals(Event.RUN_START, c.step(tick(NOW, false, zombie(NOW, HIT))));
        assertEquals(Why.UNARMED, c.why());
        return c;
    }

    // the first event from..to, the facts handed in before each step
    private static Event first(CombatCommit c, long from, long to, boolean offered, WallState state, LongFunction<Tick> scene) {
        for (long now = from; now <= to; now++) {
            c.wallFacts(offered, state);
            Event e = c.step(scene.apply(now));
            if (e != Event.NONE) return e;
        }
        return Event.NONE;
    }

    private static final LongFunction<Tick> STILL = now -> tick(now, false, zombie(now, HIT));

    @Test
    public void switchedOffTheNoRoomRunIsTheFightAsBefore() {
        CombatCommit c = unarmedRun(false, true);
        assertEquals(Event.RUN_TO_FIGHT, first(c, NOW + 1, NOW + 40, true, WallState.NONE, STILL));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void unarmedWithABlockTheNoRoomRunPutsUpAWall() {
        CombatCommit c = unarmedRun(true, true);
        assertEquals(Event.NONE, first(c, NOW + 1, NOW + 40, true, WallState.BUILDING, STILL));
        assertEquals(Mode.RUN, c.mode());
        assertTrue(c.walling());
        assertEquals(7, c.wallFoeId());
    }

    @Test
    public void unarmedWithNothingToWallWithItIsTheFightAsTheLastResort() {
        CombatCommit c = unarmedRun(true, false);
        String note = null;
        Event seen = Event.NONE;
        for (long now = NOW + 1; now <= NOW + 40 && seen == Event.NONE; now++) {
            c.wallFacts(false, WallState.NONE);
            seen = c.step(STILL.apply(now));
            String n = c.takeNote();
            if (n != null) note = n;
        }
        assertEquals(Event.RUN_TO_FIGHT, seen);
        assertEquals("cornered and unarmed, no block: fighting as the last resort", note);
    }

    @Test
    public void armedNothingChanges() {
        CombatCommit c = unarmedRun(true, true);
        // (a sword picked up on the run)
        assertEquals(Event.RUN_TO_FIGHT, first(c, NOW + 1, NOW + 40, true, WallState.NONE, now -> tick(now, true, zombie(now, HIT))));
        assertFalse(c.walling());
    }

    // a wall asked for, and the build up from the tick after
    private static CombatCommit walled() {
        CombatCommit c = unarmedRun(true, true);
        assertEquals(Event.NONE, first(c, NOW + 1, NOW + 20, true, WallState.BUILDING, STILL));
        assertTrue(c.walling());
        return c;
    }

    @Test
    public void aFailedBuildIsLetGoAndTheNextCornerIsTheFight() {
        CombatCommit c = walled();
        assertEquals(Event.RUN_TO_FIGHT, first(c, NOW + 21, NOW + 80, true, WallState.FAILED, STILL));
        assertFalse(c.walling());
    }

    @Test
    public void aWallNotUpInTimeIsLetGo() {
        CombatCommit c = walled();
        Event e = first(c, NOW + 21, NOW + 21 + CombatCommit.WALL_BUILD_TICKS + 40, true, WallState.BUILDING, STILL);
        assertEquals(Event.RUN_TO_FIGHT, e);
    }

    @Test
    public void behindTheWallTheRunEndsOnceNothingIsNear() {
        CombatCommit c = walled();
        assertEquals(Event.NONE, first(c, NOW + 21, NOW + 60, true, WallState.BUILT, STILL));
        assertEquals(Event.RUN_CLEAR, first(c, NOW + 61, NOW + 61 + CombatCommit.RUN_CLEAR_TICKS + 5, true, WallState.BUILT,
                now -> tick(now, false)));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void behindTheWallAZombieThatCannotHitHoldsTheRunTillTheHoldEndsWithNoFight() {
        CombatCommit c = walled();
        Event e = first(c, NOW + 21, NOW + 21 + CombatCommit.WALL_HOLD_TICKS + 10, true, WallState.BUILT, STILL);
        assertEquals(Event.RUN_CAP, e);
        assertNotEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aHitPastTheWallIsTheNextRound() {
        CombatCommit c = walled();
        assertEquals(Event.NONE, first(c, NOW + 21, NOW + 60, true, WallState.BUILT, STILL));
        int round = c.wallRound();
        long hit = NOW + 61;
        assertEquals(Event.NONE, first(c, NOW + 61, NOW + 62, true, WallState.BUILT, now -> tick(now, false, zombie(now, hit))));
        assertTrue(c.walling());
        assertEquals(round + 1, c.wallRound());
    }
}
