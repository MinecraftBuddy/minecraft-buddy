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
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import adris.altoclef.util.helpers.RecoverHold.Body;
import java.util.List;
import org.junit.Test;

// a run that hits its cap with the mobs still on our heels. it used to end, and the run rule started a fresh run with a fresh
// origin on the very same second, forever. now a crowd turns around at the cap (25 s, no extension) and fights, and that fight
// latches the runs off until nothing is within 12 for two seconds. under the flee line it is a fresh run instead, never a fight
public class CombatCommitChasedTest {

    private static final long NOW = 1000;
    private static final long NEVER = CombatCommit.NEVER;
    private static final long CAP = CombatCommit.RUN_CAP;

    private static Foe zombie(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit);
    }

    private static Foe zombie(int id, double distance) {
        return zombie(id, distance, NEVER);
    }

    private static Foe creeper(int id, double distance) {
        return new Foe(id, distance, false, true, NEVER);
    }

    private static Foe warden(int id, double distance) {
        return new Foe(id, distance, false, false, NEVER, Kind.UNTOUCHABLE);
    }

    private static Tick tick(long now, float health, double x, Foe... foes) {
        return new Tick(now, health, x, 0, List.of(foes), null);
    }

    // two blocks back and forth every half second: moving, so never cornered, never stuck, never far
    private static double shuffle(long now) {
        return ((now - NOW) / 10) % 2 == 0 ? 0 : 2;
    }

    private static void holds(CombatCommit c, long from, long to, float health, Foe... foes) {
        for (long now = from; now <= to; now++) {
            assertEquals("tick " + now, Event.NONE, c.step(tick(now, health, shuffle(now), foes)));
        }
    }

    // hp 7, a zombie five blocks back the whole time (inside the watch, outside reach)
    private static CombatCommit lowHpRun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 7, 0, zombie(1, 5))));
        assertEquals(Why.LOW_HP, c.why());
        return c;
    }

    // the cap lands on the last tick, with the foes still where they were
    private static CombatCommit atTheCap(Foe... foes) {
        CombatCommit c = lowHpRun();
        holds(c, NOW + 1, NOW + CAP - 1, 7, foes);
        return c;
    }

    // three on us at 10 hp, none of them hit yet
    private static Foe[] crowd() {
        return new Foe[]{zombie(1, 2), zombie(2, 2.4), zombie(3, 2.8)};
    }

    // the same, as a crowd run: above the flee line, so the cap turns round
    private static CombatCommit crowdAtTheCap() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 10, 0, crowd())));
        holds(c, NOW + 1, NOW + CAP - 1, 10, crowd());
        return c;
    }

    // not moving and boxed in: the run has nowhere to go, so the low hp cap keeps the old turn round
    private static Tick boxed(long now, float health, Foe... foes) {
        return new Tick(now, health, 0, 0, List.of(foes), null, Way.NONE, true, false, false, Body.NONE, true);
    }

    @Test
    public void theCapStillChasedTurnsAroundAndFights() {
        CombatCommit c = crowdAtTheCap();
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(NOW + CAP, 10, 0, crowd())));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.CHASED, c.why());
        assertEquals(1, c.targetId());
        // boxed in rules, but the latch breaks once: the crowd still on us is the run again, one time
        assertTrue(c.cornered());
        Foe target = zombie(1, 2);
        assertEquals(Event.FIGHT_TO_RUN, c.step(new Tick(NOW + CAP + 1, 10, 0, 0, List.of(crowd()), target)));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.CROWD, c.why());
    }

    @Test
    public void aLowHpCapIsAFreshRunWithAFreshOrigin() {
        CombatCommit c = atTheCap(zombie(1, 5));
        assertEquals(Event.RUN_START, c.step(tick(NOW + CAP, 7, 6, zombie(1, 5))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.LOW_HP, c.why());
        assertEquals(6, c.originX(), 0);
    }

    @Test
    public void aLowHpCapBoxedInStillTurnsRoundAndFights() {
        CombatCommit c = atTheCap(zombie(1, 5));
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(boxed(NOW + CAP, 7, zombie(1, 5))));
        assertEquals(Why.CHASED, c.why());
    }

    @Test
    public void thereIsNoExtensionTheRunDoesNotOutliveTheCap() {
        CombatCommit c = crowdAtTheCap();
        assertEquals(Mode.RUN, c.mode());
        assertNotEquals(Event.NONE, c.step(tick(NOW + CAP, 10, shuffle(NOW + CAP), crowd())));
        assertNotEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aWardenWalkingUpMidChasedFightIsStillARun() {
        CombatCommit c = crowdAtTheCap();
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(NOW + CAP, 10, 0, crowd())));
        Foe target = zombie(1, 2);
        assertEquals(Event.FIGHT_TO_RUN, c.step(new Tick(NOW + CAP + 1, 10, 0, 0, List.of(target, warden(2, 6)), target)));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.HEAVY, c.why());
    }

    @Test
    public void aWardenAboutAtTheCapSkipsTheFightAndRunsAgain() {
        Foe[] foes = {zombie(1, 2), warden(2, 7)};
        CombatCommit c = atTheCap(foes);
        assertEquals(Event.RUN_START, c.step(tick(NOW + CAP, 7, 0, foes)));
        assertEquals(Event.RUN_CAP, c.endedFirst());
        assertEquals(Why.HEAVY, c.why());
    }

    @Test
    public void onlyACreeperAtRangeLeftAtTheCapJustEndsTheRun() {
        CombatCommit c = atTheCap(creeper(2, 5));
        assertEquals(Event.RUN_CAP, c.step(tick(NOW + CAP, 7, 0, creeper(2, 5))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void theTurnPicksWhatIsOnUsOrHitUsOverTheClosest() {
        // 2 is closest but never touched us, 3 hit us from seven blocks: 3 it is. (seven, not six: a sinceHit that stays 20 for
        // a thousand ticks is a fresh hit every tick, and inside SWARM_RANGE that many hits turns the run around long before the cap)
        Foe[] foes = {zombie(2, 4), zombie(3, 7, 20)};
        CombatCommit c = atTheCap(foes);
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(boxed(NOW + CAP, 7, foes)));
        assertEquals(3, c.targetId());
    }

    @Test
    public void nobodyOnUsTurnsOnTheClosestWeCanWalkUpTo() {
        // the creeper is closer, but walking up to a creeper is not a fight
        Foe[] foes = {creeper(2, 4), zombie(3, 7)};
        CombatCommit c = atTheCap(foes);
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(boxed(NOW + CAP, 7, foes)));
        assertEquals(3, c.targetId());
    }

    @Test
    public void neverTurnsOnTheWarden() {
        CombatCommit c = atTheCap(warden(2, 7));
        // it stays a run, never a fight with it: the old run ends and danger starts the next one on the same step
        assertEquals(Event.RUN_START, c.step(tick(NOW + CAP, 7, 5, warden(2, 7))));
        assertEquals(Event.RUN_CAP, c.endedFirst());
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.HEAVY, c.why());
        assertEquals(5, c.originX(), 0);
    }

    // ---- no gap between one commitment and the next

    @Test
    public void aChasedFightThatWinsDoesNotStartAFreshRunOnTheSameStep() {
        CombatCommit c = crowdAtTheCap();
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(NOW + CAP, 10, 0, crowd())));
        // zombie 1 dies as a skeleton puts an arrow in us from six blocks: the kill itself is no bail (the fight ends as dead), and
        // the one latch break the chased turn carries is what starts the run on that same step
        Foe skeleton = new Foe(2, 6, true, false, 0);
        assertEquals(Event.RUN_START, c.step(tick(NOW + CAP + 1, 6, 0, skeleton)));
        assertEquals(Event.FIGHT_DEAD, c.endedFirst());
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aStalledFightWithSomethingElseSwingingAtUsGoesStraightToThatOne() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, 0, zombie(1, 2, 0))));
        Foe hangingBack = zombie(1, 5, 50);
        long stall = NOW + CombatCommit.STALL_TICKS;
        for (long now = NOW + 1; now < stall; now++) {
            assertEquals("tick " + now, Event.NONE, c.step(new Tick(now, 20, 0, 0, List.of(hangingBack, zombie(2, 5)), hangingBack)));
        }
        // the stall lands on the tick zombie 2 walks up and swings: one step, two lines, and never Mode.NONE in between
        assertEquals(Event.FIGHT_START, c.step(new Tick(stall, 20, 0, 0, List.of(hangingBack, zombie(2, 2, 0)), hangingBack)));
        assertEquals(Event.FIGHT_STALLED, c.endedFirst());
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(2, c.targetId());
        assertTrue(c.isIgnored(1));
    }

    @Test
    public void anEndWithNothingNextIsJustTheEnd() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, 0, zombie(1, 2, 0))));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 1, 20, 0, zombie(2, 5))));
        assertEquals(Event.NONE, c.endedFirst());
        assertEquals(Mode.NONE, c.mode());
        // and the next step forgets it
        assertEquals(Event.NONE, c.step(tick(NOW + 2, 20, 0)));
        assertEquals(Event.NONE, c.endedFirst());
    }

    @Test
    public void aRunThatStartsFromIdleHasNothingEndedFirst() {
        CombatCommit c = lowHpRun();
        assertEquals(Event.NONE, c.endedFirst());
    }

    @Test
    public void aRunStillEndsClearOnceItIsFarEnough() {
        CombatCommit c = lowHpRun();
        // 24 out from where it started, nothing within 12 for two seconds: over, no fight
        long from = NOW + 1;
        for (long now = from; now < from + CombatCommit.RUN_CLEAR_TICKS; now++) {
            assertEquals("tick " + now, Event.NONE, c.step(tick(now, 7, 24)));
        }
        assertEquals(Event.RUN_CLEAR, c.step(tick(from + CombatCommit.RUN_CLEAR_TICKS, 7, 24)));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aCapWithNothingWithinEightJustEnds() {
        CombatCommit c = lowHpRun();
        holds(c, NOW + 1, NOW + CAP - 1, 7, zombie(1, 9));
        assertEquals(Event.RUN_CAP, c.step(tick(NOW + CAP, 7, shuffle(NOW + CAP), zombie(1, 9))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aCapWhileHealthyJustEnds() {
        CombatCommit c = lowHpRun();
        // ate on the way, hp 15 now: the run is done, the zombie at five is walked past
        holds(c, NOW + 1, NOW + CAP - 1, 15, zombie(1, 5));
        assertEquals(Event.RUN_CAP, c.step(tick(NOW + CAP, 15, shuffle(NOW + CAP), zombie(1, 5))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aWonChasedFightLetsALowHpRunThroughTheLatch() {
        CombatCommit c = crowdAtTheCap();
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(NOW + CAP, 10, 0, crowd())));
        long won = NOW + CAP + 1;
        assertEquals(Event.FIGHT_DEAD, c.step(tick(won, 7, 0)));
        // the cooldown is over and a zombie stands five blocks off at 7 hp: the latch lets the run through (the heal-up
        // would otherwise stand still next to it)
        long again = won + CombatCommit.COOLDOWN;
        assertEquals(Event.RUN_START, c.step(tick(again, 7, 0, zombie(4, 5))));
        assertEquals(Why.LOW_HP, c.why());
    }

    // ---- low hp in the cooldown

    private static CombatCommit wonFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, 0, zombie(1, 2, 0))));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 10, 20, 0)));
        assertTrue(c.coolingDown(NOW + 11));
        return c;
    }

    @Test
    public void lowHpInTheCooldownIgnoresSomethingJustStandingWithinEight() {
        CombatCommit c = wonFight();
        holds(c, NOW + 11, NOW + 10 + CombatCommit.COOLDOWN - 1, 6, zombie(2, 5));
        assertEquals(Mode.NONE, c.mode());
        // and after the cooldown it is a run again, like always
        assertEquals(Event.RUN_START, c.step(tick(NOW + 10 + CombatCommit.COOLDOWN, 6, 0, zombie(2, 5))));
    }

    @Test
    public void lowHpInTheCooldownStillRunsFromSomethingInContact() {
        CombatCommit c = wonFight();
        assertEquals(Event.RUN_START, c.step(tick(NOW + 11, 6, 0, zombie(2, CombatCommit.CONTACT))));
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void lowHpInTheCooldownStillRunsFromAFreshHit() {
        CombatCommit c = wonFight();
        assertEquals(Event.NONE, c.step(tick(NOW + 11, 6, 0, zombie(2, 6, CombatCommit.FRESH + 1))));
        assertEquals(Event.RUN_START, c.step(tick(NOW + 12, 6, 0, zombie(2, 6, CombatCommit.FRESH))));
        assertEquals(Why.LOW_HP, c.why());
    }
}
