package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import java.util.function.LongFunction;
import org.junit.Test;

// a creeper the step already dealt with lighting up beside us again is the run's, not a third lap of the step
public class CombatCommitCreeperRepeatTest {

    private static final long NOW = 1000;

    private static Foe creeper(double distance) {
        return new Foe(9, distance, false, true, CombatCommit.NEVER);
    }

    private static Foe zombie(double distance, long sinceHit) {
        return new Foe(1, distance, false, false, sinceHit);
    }

    private static Tick tick(long now, Foe target, boolean repeat, Foe... foes) {
        return new Tick(now, 20, 0, 0, List.of(foes), target, Way.NONE, true, false, repeat);
    }

    @Test
    public void aCreeperThatCameBackIsARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, null, true, creeper(2.5))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.REPEAT_CREEPER, c.why());
    }

    // the run from it got nowhere (no safe heading): the next time it comes back it is fought, a raised shield takes the blast
    // (the runner, 3 Nov 2026, attempt 109: 0 blocks a run, round and round for minutes)
    @Test
    public void aRunFromItThatGotNowhereMakesTheNextRepeatAFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, null, true, creeper(2.5))));
        long n = NOW + 1;
        for (; n < NOW + CombatCommit.RUN_CAP + 400 && c.mode() == Mode.RUN; n++) c.step(tick(n, null, false, creeper(2.5)));
        assertEquals(Mode.NONE, c.mode());
        assertEquals(Event.FIGHT_START, c.step(tick(n + 1, null, true, creeper(2.5))));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void theSameCreeperWithoutTheRepeatIsScenery() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(NOW, null, false, creeper(2.5))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void theCooldownDoesNotStopIt() {
        CombatCommit c = new CombatCommit();
        Foe z = zombie(1.5, 0);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, null, false, z)));
        // the zombie is dead, the cooldown is on
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 1, null, false)));
        assertEquals(Event.RUN_START, c.step(tick(NOW + 2, null, true, creeper(2.5))));
        assertEquals(Why.REPEAT_CREEPER, c.why());
    }

    @Test
    public void aFightIsTillDeadWithoutTheRepeat() {
        CombatCommit c = new CombatCommit();
        Foe z = zombie(1.5, 0);
        c.step(tick(NOW, null, false, z));
        assertEquals(Event.NONE, c.step(tick(NOW + 1, z, false, z, creeper(2.5))));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aFightWithACreeperThatCameBackIsARun() {
        CombatCommit c = new CombatCommit();
        Foe z = zombie(1.5, 0);
        c.step(tick(NOW, null, false, z));
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 1, z, true, z, creeper(2.5))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.REPEAT_CREEPER, c.why());
    }

    @Test
    public void boxedInTheFightStaysAFight() {
        CombatCommit c = new CombatCommit();
        Foe z = zombie(1.5, 0);
        c.step(tick(NOW, null, false, z));
        Tick boxed = new Tick(NOW + 1, 20, 0, 0, List.of(z, creeper(2.5)), z, Way.NONE, true, false, true, RecoverHold.Body.NONE, true);
        assertEquals(Event.NONE, c.step(boxed));
        assertEquals(Mode.FIGHT, c.mode());
    }

    // ---- the run does not hand the wheel back to a fight while that creeper is still within 8

    private static final long CAP = CombatCommit.RUN_CAP;

    // a run the repeat started, with only the creeper in sight
    private static CombatCommit repeatRun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(new Tick(NOW, 20, 0, 0, List.of(creeper(5)), null, Way.NONE, true, false, true)));
        assertEquals(Why.REPEAT_CREEPER, c.why());
        return c;
    }

    // a run that is not the repeat's, same scene otherwise: hurt with a zombie behind us
    private static CombatCommit lowHpRun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(new Tick(NOW, 7, 0, 0, List.of(zombie(5, CombatCommit.NEVER)), null)));
        assertEquals(Why.LOW_HP, c.why());
        return c;
    }

    // steps from..to and returns the first event that is not NONE
    private static Event firstEvent(CombatCommit c, long from, long to, LongFunction<Tick> scene) {
        for (long now = from; now <= to; now++) {
            Event seen = c.step(scene.apply(now));
            if (seen != Event.NONE) return seen;
        }
        return Event.NONE;
    }

    // two blocks back and forth every half second: moving, so never cornered, never stuck
    private static double shuffle(long now) {
        return ((now - NOW) / 10) % 2 == 0 ? 0 : 2;
    }

    // hurt and boxed in at the cap: nowhere to run to, so it is still the turn round (not boxed it is a fresh run)
    private static Tick boxed(Tick t) {
        return new Tick(t.now(), t.health(), t.x(), t.z(), t.foes(), t.target(), Way.NONE, true, false, false, RecoverHold.Body.NONE, true);
    }

    @Test
    public void theCapDoesNotTurnOnTheZombieWithTheCreeperStillNear() {
        CombatCommit c = repeatRun();
        LongFunction<Tick> scene = now -> new Tick(now, 7, shuffle(now), 0, List.of(zombie(5, CombatCommit.NEVER), creeper(5)), null);
        assertEquals(Event.NONE, firstEvent(c, NOW + 1, NOW + CAP - 1, scene));
        assertEquals(Event.RUN_CAP, c.step(scene.apply(NOW + CAP)));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void theCapTurnsOnTheZombieOnceTheCreeperIsPastEight() {
        CombatCommit c = repeatRun();
        LongFunction<Tick> scene = now -> new Tick(now, 7, shuffle(now), 0, List.of(zombie(5, CombatCommit.NEVER), creeper(9)), null);
        assertEquals(Event.NONE, firstEvent(c, NOW + 1, NOW + CAP - 1, scene));
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(boxed(scene.apply(NOW + CAP))));
    }

    @Test
    public void aCapWithoutTheRepeatStillTurnsWithACreeperNear() {
        CombatCommit c = lowHpRun();
        LongFunction<Tick> scene = now -> new Tick(now, 7, shuffle(now), 0, List.of(zombie(5, CombatCommit.NEVER), creeper(5)), null);
        assertEquals(Event.NONE, firstEvent(c, NOW + 1, NOW + CAP - 1, scene));
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(boxed(scene.apply(NOW + CAP))));
    }

    // ten ticks in contact, five knocked off to 3.8: never a second of unbroken contact
    private static double bounce(long now) {
        return ((now - NOW) % 15) < 10 ? 2 : 3.8;
    }

    private static long since(long now, long... hits) {
        long last = -1;
        for (long hit : hits) {
            if (hit <= now) last = hit;
        }
        return last < 0 ? CombatCommit.NEVER : now - last;
    }

    private static LongFunction<Tick> bouncing(double creeperAt, long... hits) {
        return now -> new Tick(now, 20, 0.3 * (now - NOW), 0, List.of(zombie(bounce(now), since(now, hits)), creeper(creeperAt)), null,
                Way.NONE, true);
    }

    @Test
    public void hitsDoNotTurnTheRunWithTheCreeperStillNear() {
        CombatCommit c = repeatRun();
        long first = NOW + CombatCommit.RUN_HIT_GRACE + 10;
        long second = first + 30;
        assertEquals(Event.NONE, firstEvent(c, NOW + 1, second + 5, bouncing(5, first, second)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void hitsTurnTheRunOnceTheCreeperIsPastEight() {
        CombatCommit c = repeatRun();
        long first = NOW + CombatCommit.RUN_HIT_GRACE + 10;
        long second = first + 30;
        assertEquals(Event.RUN_CHASED_FIGHT, firstEvent(c, NOW + 1, second + 5, bouncing(9, first, second)));
    }

    private static LongFunction<Tick> againstTheWall(double creeperAt) {
        Foe onUs = new Foe(7, 1.8, false, false, CombatCommit.NEVER, CombatCommit.Kind.NORMAL, 0.2);
        return now -> new Tick(now, 20, 0, 0, List.of(onUs, creeper(creeperAt)), null, Way.NONE, true, true);
    }

    @Test
    public void noRoomIsNotAFightWithTheCreeperStillNear() {
        CombatCommit c = repeatRun();
        assertEquals(Event.NONE, firstEvent(c, NOW + 1, NOW + CombatCommit.NO_ROOM_TICKS + 5, againstTheWall(5)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void noRoomIsAFightOnceTheCreeperIsPastEight() {
        CombatCommit c = repeatRun();
        assertEquals(Event.RUN_TO_FIGHT, firstEvent(c, NOW + 1, NOW + CombatCommit.NO_ROOM_TICKS + 5, againstTheWall(9)));
    }

    private static LongFunction<Tick> standingStill(double creeperAt) {
        return now -> new Tick(now, 20, 0, 0, List.of(zombie(2, CombatCommit.NEVER), creeper(creeperAt)), null);
    }

    @Test
    public void theCornerWindowIsNotAFightWithTheCreeperStillNear() {
        CombatCommit c = repeatRun();
        assertEquals(Event.NONE, firstEvent(c, NOW + 1, NOW + CombatCommit.CORNER_TICKS + 5, standingStill(5)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void theCornerWindowIsAFightOnceTheCreeperIsPastEight() {
        CombatCommit c = repeatRun();
        assertEquals(Event.RUN_TO_FIGHT, firstEvent(c, NOW + 1, NOW + CombatCommit.CORNER_TICKS + 5, standingStill(9)));
    }

    @Test
    public void theRunLineSaysWhy() {
        assertEquals("a creeper that keeps coming back, hp 20", CombatLog.runWhy(Why.REPEAT_CREEPER, 20));
    }
}
