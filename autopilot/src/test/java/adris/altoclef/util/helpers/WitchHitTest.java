package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.FoeRules.Candidate;
import adris.altoclef.util.helpers.FoeRules.WitchLook;
import baritone.api.utils.Dimension;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// a witch poisons us from where it stands. the client is told "magic damage" with nobody attached, so the hit has to be
// blamed on the witch by hand, and then the normal "it hit us" rules take it from there
public class WitchHitTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final long NOW = 1000;

    // a witch behind a gap we cannot cross: angry (raiders always are) but "can't harm us" by the walking test
    private static Candidate witch(double distance, long sinceHit) {
        return new Candidate("witch", 5, distance, () -> false, () -> true, () -> false, sinceHit, 0, true, false);
    }

    private static Candidate skeleton(double distance, long sinceHit) {
        return new Candidate("skeleton", 6, distance, () -> false, () -> true, () -> false, sinceHit, 0, true, false);
    }

    private static Tick tick(long now, Foe target, Foe... foes) {
        return new Tick(now, 20, 0, 0, List.of(foes), target);
    }

    // ---- who gets the blame

    @Test
    public void onlyMagicDamageWithNobodyAttachedCounts() {
        assertTrue(FoeRules.unattributedMagic("magic", false));
        // a thrown harming potion comes with its witch attached: that is the plain hit rule, not this one
        assertFalse(FoeRules.unattributedMagic("indirectMagic", true));
        assertFalse(FoeRules.unattributedMagic("magic", true));
        // starvation, fire, falling, the wither effect: not a potion
        for (String other : List.of("starve", "onFire", "fall", "wither", "drown", "inFire", "generic")) {
            assertFalse(other, FoeRules.unattributedMagic(other, false));
        }
    }

    @Test
    public void theNearestWitchWithALineWithinTwelveGetsTheBlame() {
        WitchLook far = new WitchLook(1, 11, true);
        WitchLook near = new WitchLook(2, 8, true);
        assertSame(near, FoeRules.witchBehind(true, List.of(far, near)));
        assertSame(far, FoeRules.witchBehind(true, List.of(far, new WitchLook(3, 6, false))));
        assertEquals(12, FoeRules.WITCH_HIT_RANGE, 0);
        assertNotNull(FoeRules.witchBehind(true, List.of(new WitchLook(4, 12, true))));
    }

    @Test
    public void harmWithNoWitchAroundChangesNothing() {
        assertNull(FoeRules.witchBehind(true, List.of()));
        // out of range, or no line to us
        assertNull(FoeRules.witchBehind(true, List.of(new WitchLook(1, 12.5, true))));
        assertNull(FoeRules.witchBehind(true, List.of(new WitchLook(1, 5, false))));
        // and no harm, no blame, however close
        assertNull(FoeRules.witchBehind(false, List.of(new WitchLook(1, 2, true))));
    }

    // ---- what the machine does with the blame

    @Test
    public void aWitchBehindAGapIsIgnoredUntilThePoisonIsBlamedOnIt() {
        assertNull(FoeRules.accept(Dimension.OVERWORLD, witch(8, NEVER)));
        Foe foe = FoeRules.accept(Dimension.OVERWORLD, witch(8, 3));
        assertNotNull(foe);
        assertEquals(Kind.LOBBER, foe.kind());
        assertTrue(foe.ranged());
        assertTrue(foe.hitUs());
    }

    @Test
    public void aWitchThatPoisonedUsAtEightBlocksIsAFightAndAWitchNobodyBlamedIsNot() {
        CombatCommit hit = new CombatCommit();
        Foe poisoner = FoeRules.accept(Dimension.OVERWORLD, witch(8, 3));
        assertEquals(Event.FIGHT_START, hit.step(tick(NOW, null, poisoner)));
        assertEquals(Mode.FIGHT, hit.mode());
        assertEquals(CombatCommit.Why.HIT, hit.why());
        assertEquals(5, hit.targetId());

        // a witch that merely stands there angry (a hit from a long time ago is not news either)
        CombatCommit quiet = new CombatCommit();
        Foe bystander = FoeRules.accept(Dimension.OVERWORLD, new Candidate("witch", 5, 8, () -> false, () -> true, () -> true, NEVER, 0, true, false));
        assertEquals(Event.NONE, quiet.step(tick(NOW, null, bystander)));
    }

    @Test
    public void aSkeletonPlinkingFromTheSameSpotIsStillAnsweredByWalkingAway() {
        CombatCommit c = new CombatCommit();
        Foe plinker = FoeRules.accept(Dimension.OVERWORLD, skeleton(8, 3));
        assertNotNull(plinker);
        assertEquals(Event.NONE, c.step(tick(NOW, null, plinker)));
    }

    // a fight with a witch that holds its distance for good while the poison keeps ticking (a tick of it every 25 ticks): the stall
    // rule writes it off after STALL_TICKS. returns the tick it happened on
    private static long stallAFightWithAWitch(CombatCommit c) {
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, null, lobber(8, 3))));
        for (long t = NOW + 1; t < NOW + CombatCommit.STALL_TICKS; t++) {
            Foe w = lobber(8, (t - NOW) % 25);
            assertEquals("tick " + t, Event.NONE, c.step(tick(t, w, w)));
        }
        long gaveUp = NOW + CombatCommit.STALL_TICKS;
        Foe stale = lobber(8, (gaveUp - NOW) % 25);
        assertEquals(Event.FIGHT_STALLED, c.step(tick(gaveUp, stale, stale)));
        assertTrue(c.isIgnored(5));
        return gaveUp;
    }

    @Test
    public void aWitchWeCouldNotGetToIsARunTheNextTimeItIsBlamedNotAnotherFight() {
        CombatCommit c = new CombatCommit();
        long gaveUp = stallAFightWithAWitch(c);
        // the poison went on ticking but nobody blamed the witch: the old rule, ignored
        assertEquals(Event.NONE, c.step(tick(gaveUp + 10, null, lobber(8, gaveUp + 10 - NOW))));
        // blamed again twenty ticks later: the normal run, with its own reason
        assertEquals(Event.RUN_START, c.step(tick(gaveUp + 20, null, lobber(8, 2))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(CombatCommit.Why.LOBBER, c.why());
        assertEquals("combat: [overworld] RUN from witch (can't reach it) [LOBBER]",
                CombatLog.line(Dimension.OVERWORLD, Event.RUN_START, c.why(), false, "witch", "it", "1 witch", 20, 0));
    }

    @Test
    public void blamingItAgainAfterThirtySecondsTriesTheFightAgain() {
        CombatCommit c = new CombatCommit();
        long gaveUp = stallAFightWithAWitch(c);
        long later = gaveUp + CombatCommit.LOBBER_GIVE_UP_TICKS + 20;
        assertEquals(Event.FIGHT_START, c.step(tick(later, null, lobber(8, 2))));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(CombatCommit.Why.HIT, c.why());
        assertEquals(5, c.targetId());
    }

    @Test
    public void aWitchWeCanReachIsStillFoughtToTheDeath() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, null, lobber(8, 3))));
        // it walks into contact (or we do) and keeps hitting us: no stall, no give up, no run
        for (long t = NOW + 1; t < NOW + 300; t++) {
            Foe w = lobber(2, t % 25);
            assertEquals("tick " + t, Event.NONE, c.step(tick(t, w, w)));
        }
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 300, null)));
    }

    private static Foe lobber(double distance, long sinceHit) {
        return new Foe(5, distance, true, false, sinceHit, Kind.LOBBER);
    }

    // the poison stopped for a few seconds (a missed potion, a line that broke) and the fight lost track of a witch it never
    // got near: the same story as a stall, so the next blame is a run
    @Test
    public void aWitchWeLostTrackOfWithoutEverReachingItIsARunTheNextTimeToo() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, null, lobber(8, 3))));
        Event ended = Event.NONE;
        long t = NOW + 1;
        for (; t < NOW + 200 && ended == Event.NONE; t++) {
            Foe w = lobber(8, 3 + (t - NOW));
            ended = c.step(tick(t, w, w));
        }
        assertEquals(Event.FIGHT_LOST, ended);
        assertEquals(Event.RUN_START, c.step(tick(t + 20, null, lobber(8, 2))));
        assertEquals(CombatCommit.Why.LOBBER, c.why());
    }

    @Test
    public void aWitchStandingInContactIsFoughtEvenInsideTheGiveUpWindow() {
        CombatCommit c = new CombatCommit();
        long gaveUp = stallAFightWithAWitch(c);
        assertEquals(Event.FIGHT_START, c.step(tick(gaveUp + 20, null, lobber(2, 2))));
        assertEquals(Mode.FIGHT, c.mode());
    }
}
