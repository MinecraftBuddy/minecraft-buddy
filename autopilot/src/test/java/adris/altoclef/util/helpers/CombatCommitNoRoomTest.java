package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import baritone.api.utils.Dimension;
import java.util.List;
import java.util.function.LongFunction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// a run that is really trying to move and going nowhere with a melee foe in contact is the cornered fight now, not after the
// three second window. a run that is not trying (a search still going, a bite, the air) says nothing about room
public class CombatCommitNoRoomTest {

    private static final long NOW = 1000;
    private static final long FIRES = NOW + CombatCommit.NO_ROOM_TICKS;

    private static Foe measured(int id, double distance, double gap) {
        return new Foe(id, distance, false, false, CombatCommit.NEVER, Kind.NORMAL, gap);
    }

    private static Tick runTick(long now, float health, double x, double z, boolean trying, Foe... foes) {
        return new Tick(now, health, x, z, List.of(foes), null, CombatCommit.Way.NONE, true, trying);
    }

    private static Tick runTick(long now, double x, double z, boolean trying, Foe... foes) {
        return runTick(now, 20, x, z, trying, foes);
    }

    // hurt with a zombie at eight is the cheapest way into a run, nothing else about it matters
    private static CombatCommit runFrom(long now) {
        CombatCommit c = new CombatCommit();
        Foe far = new Foe(900, 8, false, false, CombatCommit.NEVER);
        assertEquals(Event.RUN_START, c.step(new Tick(now, 8, 0, 0, List.of(far), null)));
        return c;
    }

    private static void holds(CombatCommit c, long from, long to, LongFunction<Tick> scene) {
        for (long now = from; now <= to; now++) {
            assertEquals("tick " + now, Event.NONE, c.step(scene.apply(now)));
        }
    }

    @Test
    public void aPathExecutingAgainstAWallWithAZombieInContactIsACorneredFightAtTickTen() {
        CombatCommit c = runFrom(NOW);
        Foe onUs = measured(7, 1.8, 0.2);
        holds(c, NOW + 1, FIRES - 1, now -> runTick(now, 0, 0, true, onUs));
        assertEquals(Event.RUN_TO_FIGHT, c.step(runTick(FIRES, 0, 0, true, onUs)));
        assertEquals(NOW + 10, FIRES);
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(7, c.targetId());
        assertEquals(CombatCommit.Why.CORNERED, c.why());
        assertTrue(c.cornered());
        assertTrue(c.noRoom());
    }

    @Test
    public void aSlowPathStartIsStillARunhoweverLongTheSearchTakes() {
        CombatCommit c = runFrom(NOW);
        Foe onUs = measured(7, 1.8, 0.2);
        // no path yet for 30 ticks: nothing is trying, nothing counts (the old sixty tick window is the only rule)
        holds(c, NOW + 1, NOW + 30, now -> runTick(now, 0, 0, false, onUs));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void eatingOnTheRunWithAZombieAtTwoAndAHalfIsStillARun() {
        CombatCommit c = runFrom(NOW);
        Foe close = measured(7, 2.5, 2.4);
        // using an item: a run bite crawls on purpose and must not read as cornered
        holds(c, NOW + 1, NOW + 50, now -> runTick(now, 0, 0, false, close));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void lowHpInTheOpenWithAPathThatStartsLateIsStillARun() {
        CombatCommit c = runFrom(NOW);
        Foe close = measured(7, 2.5, 2.4);
        // the search takes a while at hp 7, then the path goes live and the bot is on its way
        holds(c, NOW + 1, NOW + 25, now -> runTick(now, 7, 0, 0, false, close));
        holds(c, NOW + 26, NOW + 50, now -> runTick(now, 7, (now - NOW - 25) * 0.2, 0, true, close));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void ticksThatAreNotTryingPauseTheCountInsteadOfFillingIt() {
        CombatCommit c = runFrom(NOW);
        Foe onUs = measured(7, 1.8, 0.2);
        holds(c, NOW + 1, NOW + 5, now -> runTick(now, 0, 0, true, onUs));
        // twenty ticks of a bite
        holds(c, NOW + 6, NOW + 25, now -> runTick(now, 0, 0, false, onUs));
        // four more trying ticks make nine: still quiet. the tenth is the fight
        holds(c, NOW + 26, NOW + 29, now -> runTick(now, 0, 0, true, onUs));
        assertEquals(Event.RUN_TO_FIGHT, c.step(runTick(NOW + 30, 0, 0, true, onUs)));
    }

    @Test
    public void aRunThatIsMovingNeverTripsIt() {
        CombatCommit c = runFrom(NOW);
        Foe onUs = measured(7, 1.8, 0.2);
        // a trot is 0.2 a tick: two blocks in every window
        holds(c, NOW + 1, NOW + 50, now -> runTick(now, (now - NOW) * 0.2, 0, true, onUs));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aFoeNobodyMeasuredLeavesTheOldWindowAlone() {
        CombatCommit c = runFrom(NOW);
        Foe unmeasured = new Foe(7, 1.8, false, false, CombatCommit.NEVER);
        holds(c, NOW + 1, NOW + CombatCommit.CORNER_TICKS, now -> runTick(now, 0, 0, true, unmeasured));
        assertEquals(Event.RUN_TO_FIGHT, c.step(runTick(NOW + 1 + CombatCommit.CORNER_TICKS, 0, 0, true, unmeasured)));
        assertFalse(c.noRoom());
    }

    @Test
    public void aMobOutsideContactReachDoesNotCount() {
        CombatCommit c = runFrom(NOW);
        // inside the old four block feet line, outside the three block eye to box contact
        Foe near = measured(7, 3.5, 3.2);
        holds(c, NOW + 1, FIRES + 10, now -> runTick(now, 0, 0, true, near));
    }

    @Test
    public void shootersCreepersAndWardensAreNotWhatThisRuleFightsFor() {
        CombatCommit c = runFrom(NOW);
        Foe skeleton = new Foe(1, 1.8, true, false, CombatCommit.NEVER, Kind.NORMAL, 0.5);
        Foe creeper = new Foe(2, 1.8, false, true, CombatCommit.NEVER, Kind.NORMAL, 0.5);
        Foe warden = new Foe(3, 1.8, false, false, CombatCommit.NEVER, Kind.UNTOUCHABLE, 0.5);
        holds(c, NOW + 1, FIRES + 5, now -> runTick(now, 0, 0, true, skeleton, creeper, warden));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void theCountStartsOverWhenTheFoeStepsOutOfContactAndBack() {
        CombatCommit c = runFrom(NOW);
        Foe in = measured(7, 1.8, 0.2);
        Foe out = measured(7, 3.5, 3.2);
        holds(c, NOW + 1, NOW + 8, now -> runTick(now, 0, 0, true, in));
        holds(c, NOW + 9, NOW + 10, now -> runTick(now, 0, 0, true, out));
        // back in at NOW + 11: a fresh count of ten, so the fight is at NOW + 20
        holds(c, NOW + 11, NOW + 19, now -> runTick(now, 0, 0, true, in));
        assertEquals(Event.RUN_TO_FIGHT, c.step(runTick(NOW + 20, 0, 0, true, in)));
    }

    @Test
    public void theLogLineSaysThereWasNoRoom() {
        String plain = CombatLog.line(Dimension.OVERWORLD, Event.RUN_TO_FIGHT, CombatCommit.Why.CORNERED, true, "zombie", "it", "1 zombie", 20, 0);
        String cramped = CombatLog.line(Dimension.OVERWORLD, Event.RUN_TO_FIGHT, CombatCommit.Why.CORNERED, true, true, "zombie", "it", "1 zombie", 20, 0);
        assertEquals("combat: [overworld] cornered, fighting zombie", plain);
        assertEquals("combat: [overworld] cornered, no room, fighting zombie", cramped);
    }

    // the decision the brain feeds the machine, with the search failure count as it comes out of baritone
    @Test
    public void aFailedSearchOnlyCountsWhileItIsTheLatestNews() {
        CombatCommit.Drive drive = new CombatCommit.Drive();
        // not running: never trying, whatever baritone says
        assertFalse(drive.trying(false, true, false, 0, false, true, false));
        // a search going (no path yet), a bite, the air and water: not trying
        assertFalse(drive.trying(true, false, true, 0, false, true, false));
        assertFalse(drive.trying(true, true, false, 0, true, true, false));
        assertFalse(drive.trying(true, true, false, 0, false, false, false));
        assertFalse(drive.trying(true, true, false, 0, false, true, true));
        // a path executing is trying
        assertTrue(drive.trying(true, true, false, 0, false, true, false));
        // the search came back empty: trying, and it stays so while nothing else starts
        assertFalse(drive.trying(true, false, true, 0, false, true, false));
        assertTrue(drive.trying(true, false, false, 1, false, true, false));
        assertTrue(drive.trying(true, false, false, 1, false, true, false));
        // a new search, then a path that gets there: the old failure is history, a later re-path is not trying until it lands
        assertFalse(drive.trying(true, false, true, 1, false, true, false));
        assertTrue(drive.trying(true, true, false, 1, false, true, false));
        assertFalse(drive.trying(true, false, false, 1, false, true, false));
        assertFalse(drive.trying(true, false, true, 1, false, true, false));
    }
}
