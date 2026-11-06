package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import adris.altoclef.util.helpers.RecoverHold.Body;
import java.util.List;
import org.junit.Test;

// the heal-up hold: when it starts, what keeps it going, and every way out. the rules on their own first, then through the
// machine, which is where the re-run and the stall clock live
public class RecoverHoldTest {

    private static final long NEVER = CombatCommit.NEVER;
    // fed, nothing in the bag, free to hold
    private static final Body FED = new Body(20, false, false, false);
    private static final Body EATING = new Body(15, true, false, true);
    private static final Body BARRED = new Body(20, true, true, false);

    private static Foe zombie(int id, double distance) {
        return new Foe(id, distance, false, false, NEVER);
    }

    private static Tick tick(long now, float health, double x, Body body, Foe... foes) {
        return fighting(now, health, x, body, null, foes);
    }

    private static Tick fighting(long now, float health, double x, Body body, Foe target, Foe... foes) {
        return new Tick(now, health, x, 0, List.of(foes), target, Way.NONE, true, false, false, body);
    }

    // a low hp run from a zombie at 5, then 30 blocks out with nothing about until it calls clear. returns the tick it ended on
    private static long runAndClear(CombatCommit c, long start, float endHealth, Body body) {
        assertEquals(Event.RUN_START, c.step(tick(start, 6, 0, body, zombie(1, 5))));
        long now = start;
        for (int i = 0; i < 100; i++) {
            now++;
            Event e = c.step(tick(now, endHealth, 30, body));
            if (e == Event.RUN_CLEAR) return now;
        }
        throw new AssertionError("the run never cleared");
    }

    // ---- the rules

    @Test
    public void runEndUnderTheRunLineIsAHold() {
        // a run that ends at 15.6 holds, and the line is the one the hold lets go at
        assertTrue(RecoverHold.wants(Mode.RUN, 15.6f));
        assertTrue(RecoverHold.wants(Mode.RUN, RecoverHold.RUN_BELOW - 0.5f));
        assertFalse(RecoverHold.wants(Mode.RUN, RecoverHold.RUN_BELOW));
    }

    @Test
    public void fightEndAtTheFleeLineIsAHold() {
        assertTrue(RecoverHold.wants(Mode.FIGHT, 8));
        assertFalse(RecoverHold.wants(Mode.FIGHT, 9));
        // nothing ended, nothing to hold for (a fall or a stray arrow while calm)
        assertFalse(RecoverHold.wants(Mode.NONE, 2));
    }

    @Test
    public void onlyStartsWhenRegenCanHappen() {
        assertTrue(RecoverHold.mayStart(new Body(18, false, false, false)));
        assertFalse(RecoverHold.mayStart(new Body(17, false, false, false)));
        assertTrue(RecoverHold.mayStart(new Body(6, true, false, false)));
        // the golem pillar, the dragon, blaze rods, the end
        assertFalse(RecoverHold.mayStart(new Body(20, true, true, false)));
        assertFalse(RecoverHold.mayStart(Body.NONE));
    }

    // ---- through the machine

    @Test
    public void runThatEndsHurtHoldsInsteadOfHandingBack() {
        CombatCommit c = new CombatCommit();
        runAndClear(c, 1000, 6, FED);
        assertEquals(Mode.NONE, c.mode());
        assertTrue(c.recovering());
    }

    @Test
    public void runThatEndsAtTheRunLineDoesNot() {
        CombatCommit c = new CombatCommit();
        runAndClear(c, 1000, RecoverHold.RUN_BELOW, FED);
        assertFalse(c.recovering());
    }

    @Test
    public void noFoodAndALowBarIsNoHold() {
        CombatCommit c = new CombatCommit();
        runAndClear(c, 1000, 6, new Body(10, false, false, false));
        assertFalse(c.recovering());
    }

    @Test
    public void nobodySaidMeansNoHold() {
        CombatCommit c = new CombatCommit();
        runAndClear(c, 1000, 6, Body.NONE);
        assertFalse(c.recovering());
    }

    @Test
    public void barredNeverStartsAndEndsOneThatIsOn() {
        CombatCommit c = new CombatCommit();
        runAndClear(c, 1000, 6, BARRED);
        assertFalse(c.recovering());

        CombatCommit d = new CombatCommit();
        long end = runAndClear(d, 1000, 6, FED);
        assertTrue(d.recovering());
        d.step(tick(end + 1, 6, 30, BARRED));
        assertFalse(d.recovering());
    }

    @Test
    public void fightWonAtEightHoldsAndAtNineDoesNot() {
        for (float hp : new float[]{8, 9}) {
            CombatCommit c = new CombatCommit();
            Foe z = new Foe(1, 2, false, false, 0);
            assertEquals(Event.FIGHT_START, c.step(tick(1000, 20, 0, FED, z)));
            // it died (gone from the world and the list), we are at hp
            assertEquals(Event.FIGHT_DEAD, c.step(tick(1001, hp, 0, FED)));
            assertEquals(hp <= 8, c.recovering());
        }
    }

    @Test
    public void healedToTheDoneLineLetsGo() {
        CombatCommit c = new CombatCommit();
        long end = runAndClear(c, 1000, 6, FED);
        c.step(tick(end + 1, RecoverHold.DONE_AT - 1, 30, FED));
        assertTrue(c.recovering());
        c.step(tick(end + 2, RecoverHold.DONE_AT, 30, FED));
        assertFalse(c.recovering());
    }

    @Test
    public void twelveSecondsWithoutHpIsAStall() {
        CombatCommit c = new CombatCommit();
        long end = runAndClear(c, 1000, 6, FED);
        for (long t = 1; t < RecoverHold.STALL_TICKS; t++) {
            c.step(tick(end + t, 6, 30, FED));
            assertTrue("still on at " + t, c.recovering());
        }
        c.step(tick(end + RecoverHold.STALL_TICKS, 6, 30, FED));
        assertFalse(c.recovering());
    }

    @Test
    public void aGainOrAMealKeepsTheStallClockOff() {
        CombatCommit c = new CombatCommit();
        long end = runAndClear(c, 1000, 6, FED);
        long t = end;
        // chewing the whole time: nothing counts
        for (int i = 0; i < 400; i++) c.step(tick(++t, 6, 30, EATING));
        assertTrue(c.recovering());
        // most of a stall, then one hp back starts it over
        for (int i = 0; i < 200; i++) c.step(tick(++t, 6, 30, FED));
        c.step(tick(++t, 7, 30, FED));
        assertEquals(0, c.recoverHold().stallTicks());
        for (int i = 0; i < 200; i++) c.step(tick(++t, 7, 30, FED));
        assertTrue(c.recovering());
    }

    @Test
    public void somethingWithinTenIsARunAgainCooldownOrNot() {
        CombatCommit c = new CombatCommit();
        long end = runAndClear(c, 1000, 10, FED);
        assertTrue(c.coolingDown(end + 1));
        // a zombie at 9.5 that never hit us: above the flee line and in the cooldown, normally scenery
        assertEquals(Event.RUN_START, c.step(tick(end + 1, 10, 30, FED, zombie(2, 9.5))));
        assertEquals(Why.LOW_HP, c.why());
        assertTrue(c.recovering());

        // the same thing without a hold is nothing
        CombatCommit d = new CombatCommit();
        long end2 = runAndClear(d, 1000, 10, Body.NONE);
        assertEquals(Event.NONE, d.step(tick(end2 + 1, 10, 30, Body.NONE, zombie(2, 9.5))));
    }

    @Test
    public void aFreshShotWithALineIsARunAgain() {
        CombatCommit c = new CombatCommit();
        long end = runAndClear(c, 1000, 10, FED);
        Foe skeleton = new Foe(3, 14, true, false, 1, CombatCommit.Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
        Foe second = new Foe(4, 12, true, false, 1, CombatCommit.Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
        assertEquals(Event.RUN_START, c.step(tick(end + 1, 9, 30, FED, skeleton, second)));
        assertEquals(Why.SHOT, c.why());
    }

    @Test
    public void reRunKeepsTheStartAndTheStallClockWaits() {
        CombatCommit c = new CombatCommit();
        long end = runAndClear(c, 1000, 6, FED);
        long started = c.recoverHold().startTick();
        long t = end;
        // most of a stall
        for (int i = 0; i < 200; i++) c.step(tick(++t, 6, 30, FED));
        assertEquals(Event.RUN_START, c.step(tick(++t, 6, 30, FED, zombie(2, 9))));
        // the re-run goes 30 more blocks and clears, hurt
        Event e = Event.NONE;
        for (int i = 0; i < 100 && e != Event.RUN_CLEAR; i++) e = c.step(tick(++t, 6, 60, FED));
        assertEquals(Event.RUN_CLEAR, e);
        assertTrue(c.recovering());
        assertEquals(started, c.recoverHold().startTick());
        // a whole stall's worth minus a tick after the re-run and it is still on: the clock did not carry the 200 over
        for (int i = 1; i < RecoverHold.STALL_TICKS; i++) c.step(tick(++t, 6, 60, FED));
        assertTrue(c.recovering());
    }

    @Test
    public void theCapEndsItEvenMidReRun() {
        CombatCommit c = new CombatCommit();
        long end = runAndClear(c, 1000, 6, FED);
        long start = c.recoverHold().startTick();
        long t = end;
        float hp = 6;
        // regen too slow to get anywhere, fast enough that it never stalls
        while (t < start + RecoverHold.CAP_TICKS - 20) {
            t++;
            if (t % 200 == 0 && hp < 13) hp += 0.5f;
            c.step(tick(t, hp, 30, FED));
        }
        assertTrue(c.recovering());
        assertEquals(Event.RUN_START, c.step(tick(++t, hp, 30, FED, zombie(2, 9))));
        while (t < start + RecoverHold.CAP_TICKS) c.step(tick(++t, hp, 30, FED, zombie(2, 9)));
        assertEquals(Mode.RUN, c.mode());
        assertFalse(c.recovering());
    }

    @Test
    public void theReRunTheCapCutOffDoesNotEndIntoANewHold() {
        CombatCommit c = new CombatCommit();
        long end = runAndClear(c, 1000, 6, FED);
        long start = c.recoverHold().startTick();
        long t = end;
        float hp = 6;
        while (t < start + RecoverHold.CAP_TICKS - 20) {
            t++;
            if (t % 200 == 0) hp += 0.5f;
            c.step(tick(t, hp, 30, FED));
        }
        assertEquals(Event.RUN_START, c.step(tick(++t, hp, 30, FED, zombie(2, 9))));
        while (t < start + RecoverHold.CAP_TICKS) c.step(tick(++t, hp, 30, FED, zombie(2, 9)));
        assertFalse(c.recovering());
        // that run clears 30 blocks on, hurt: it hands back like before the hold existed
        Event e = Event.NONE;
        for (int i = 0; i < 100 && e != Event.RUN_CLEAR; i++) e = c.step(tick(++t, hp, 60, FED));
        assertEquals(Event.RUN_CLEAR, e);
        assertFalse(c.recovering());
        // a new run later on is a new story
        runAndClear(c, t + 200, 6, FED);
        assertTrue(c.recovering());
    }

    @Test
    public void aStalledHoldDoesNotBlockTheNextOne() {
        CombatCommit c = new CombatCommit();
        long end = runAndClear(c, 1000, 6, FED);
        long t = end;
        while (c.recovering()) c.step(tick(++t, 6, 30, FED));
        runAndClear(c, t + 200, 6, FED);
        assertTrue(c.recovering());
    }

    @Test
    public void holdEatsByTheRunsBiteLines() {
        CombatCommit.Bite quiet = CombatCommit.Bite.NONE;
        // hp 10, nearest 9: calm without the hold (above the flee line), no meal with it
        assertEquals(CombatRules.Stance.CALM, CombatCommit.stance(Mode.NONE, 10, 9, false, false, quiet, false));
        assertFalse(CombatCommit.stance(Mode.NONE, 10, 9, false, false, quiet, true).mayEat());
        assertTrue(CombatCommit.stance(Mode.NONE, 10, 11, false, false, quiet, true).mayEat());
        // a shooter with a line on us
        assertFalse(CombatCommit.stance(Mode.NONE, 10, 14, false, false, new CombatCommit.Bite(true, Double.POSITIVE_INFINITY, false), true)
                .mayEat());
        assertTrue(CombatCommit.stanceWhy(Mode.NONE, 10, 11, false, quiet, true).startsWith("recovering"));
    }

    @Test
    public void aWatchingBlazeWithNoHitIsARunAgain() {
        CombatCommit c = new CombatCommit();
        long end = runAndClear(c, 1000, 10, FED);
        // no foe at all (an uncharged blaze at range is not in the hostile list), only the watched flag
        Tick watched = new Tick(end + 1, 12, 30, 0, List.of(), null, Way.NONE, true, false, false, FED, false, 0, true);
        assertEquals(Event.RUN_START, c.step(watched));
        assertEquals(Why.SHOT, c.why());

        // the same tick unwatched is a quiet hold
        CombatCommit d = new CombatCommit();
        long end2 = runAndClear(d, 1000, 10, FED);
        assertEquals(Event.NONE, d.step(tick(end2 + 1, 12, 30, FED)));
    }

    @Test
    public void onlyBlazesAndSkeletonTypesAreAHoldRerunSource() {
        for (String type : new String[]{"blaze", "skeleton", "stray", "bogged", "parched"}) {
            assertTrue(type, RecoverHold.rerunSource(type));
        }
        // a ghast shoots from sixty blocks and the nether run would end in lava, the shield is its answer. a wither skeleton swings
        for (String type : new String[]{"ghast", "wither_skeleton", "zombie", "creeper", "witch", "player"}) {
            assertFalse(type, RecoverHold.rerunSource(type));
        }
    }

    @Test
    public void aThrowerWeAreToldAboutIsARunAgainAndFireAloneIsNot() {
        // the brain turns a recent blaze or skeleton hit into the watched flag, with no foe in the list at all
        CombatCommit c = new CombatCommit();
        long end = runAndClear(c, 1000, 10, FED);
        assertEquals(Event.NONE, c.step(tick(end + 1, 12, 30, FED)));
        assertTrue(c.recovering());
        Tick shot = new Tick(end + 2, 11, 30, 0, List.of(), null, Way.NONE, true, false, false, FED, false, 0, true);
        assertEquals(Event.RUN_START, c.step(shot));
        assertEquals(Why.SHOT, c.why());
    }

    // a fast one that hit us, knocked back past the close range: in the hold it is the cornered fight, outside it is a run
    @Test
    public void aFastOneSevenOutThatHitUsIsAFightInTheHoldAndARunOutsideIt() {
        CombatCommit c = new CombatCommit();
        long end = runAndClear(c, 1000, 10, FED);
        Foe piglin = new Foe(2, 7, false, false, 3).withFast(true);
        assertEquals(Event.RUN_TO_FIGHT, c.step(tick(end + 1, 10, 30, FED, piglin)));
        assertEquals(Why.CORNERED, c.why());

        CombatCommit d = new CombatCommit();
        assertEquals(Event.RUN_START, d.step(tick(1000, 7, 0, Body.NONE, piglin)));
    }

    // an angry zombified piglin is 0.23 + 0.05; a plain zombie stays under the line
    @Test
    public void theFastLineCatchesAnAngryPiglinButNotAZombie() {
        assertTrue(0.23 + 0.05 >= CombatRules.FAST_MOB_SPEED);
        assertFalse(0.23 >= CombatRules.FAST_MOB_SPEED);
        assertFalse(0.25 >= CombatRules.FAST_MOB_SPEED);
    }
}
