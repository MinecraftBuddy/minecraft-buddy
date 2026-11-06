package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.Set;
import org.junit.Test;

// a run that turned into a fight latches the runs off: the fight ending with a cube bouncing out past reach used to hand the
// same step to the low hp rule, which started a fresh run with a fresh origin and grace, which turned back into a fight...
public class CombatCommitLatchTest {

    private static final long NOW = 1000;
    private static final long NEVER = CombatCommit.NEVER;
    private static final long CAP = CombatCommit.RUN_CAP;

    private static Foe zombie(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit);
    }

    private static Foe cube(int id, double distance, long sinceHit) {
        return zombie(id, distance, sinceHit).withFast(true);
    }

    private static Foe warden(int id, double distance) {
        return new Foe(id, distance, false, false, NEVER, Kind.UNTOUCHABLE);
    }

    private static Tick tick(long now, float health, Foe... foes) {
        return new Tick(now, health, 0, 0, List.of(foes), null);
    }

    // two blocks back and forth every half second: moving, so never cornered, never stuck, never far
    private static Tick shuffle(long now, float health, Foe... foes) {
        double x = ((now - NOW) / 10) % 2 == 0 ? 0 : 2;
        return new Tick(now, health, x, 0, List.of(foes), null);
    }

    // three on us, none of them hit yet: a crowd, which is what turns a run into a chased fight above the low hp line
    private static Foe[] crowd() {
        return new Foe[]{zombie(1, 2, NEVER), zombie(2, 2.4, NEVER), zombie(3, 2.8, NEVER)};
    }

    // a crowd run at 10 hp that never gets away from the zombies behind it for the whole cap, then turns round: a chased fight
    // on zombie 1. above the low line on purpose, under it the cap starts a fresh run instead
    private static CombatCommit chasedFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 10, crowd())));
        for (long now = NOW + 1; now < NOW + CAP; now++) {
            assertEquals(Event.NONE, c.step(shuffle(now, 10, crowd())));
        }
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(NOW + CAP, 10, crowd())));
        return c;
    }

    // the crowd bails the chased fight once (that is the latch's one break), runs to the next cap and turns round again
    private static CombatCommit breakSpentFight() {
        CombatCommit c = chasedFight();
        long start = NOW + CAP + 1;
        Foe target = zombie(1, 2, NEVER);
        assertEquals(Event.FIGHT_TO_RUN, c.step(new Tick(start, 10, 0, 0, List.of(crowd()), target)));
        for (long now = start + 1; now < start + CAP; now++) {
            assertEquals(Event.NONE, c.step(shuffle(now, 10, crowd())));
        }
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(start + CAP, 10, crowd())));
        return c;
    }

    @Test
    public void theRunCapIsTwentyFiveSecondsAndNothingStretchesIt() {
        assertEquals(500, CombatCommit.RUN_CAP);
        chasedFight();
    }

    @Test
    public void aTargetDyingAtHpEightWithACubeFiveBlocksOutDoesNotStartANewRun() {
        CombatCommit c = chasedFight();
        long dead = NOW + CAP + 1;
        // the cube hit us a moment ago and bounced out to five blocks: not who the cornered pick (three blocks) chains to
        Foe bouncer = cube(2, 5, 20);
        assertEquals(Event.FIGHT_DEAD, c.step(tick(dead, 8, bouncer)));
        assertEquals(Mode.NONE, c.mode());
        // and not a tick later: the cooldown sits it out. once it is over the latch lets its one low hp run through
        for (long now = dead + 1; now < dead + CombatCommit.COOLDOWN; now++) {
            assertEquals("tick " + now, Event.NONE, c.step(tick(now, 8, bouncer)));
        }
        assertEquals(Event.RUN_START, c.step(tick(dead + CombatCommit.COOLDOWN, 8, bouncer)));
    }

    @Test
    public void theLatchLetsGoAfterTwoSecondsOfNothingWithinTwelve() {
        CombatCommit c = chasedFight();
        long dead = NOW + CAP + 1;
        assertEquals(Event.FIGHT_DEAD, c.step(tick(dead, 8)));
        long quiet = dead + 1;
        for (long now = quiet; now <= quiet + CombatCommit.RUN_CLEAR_TICKS; now++) {
            assertEquals(Event.NONE, c.step(tick(now, 8)));
        }
        long back = quiet + CombatCommit.COOLDOWN;
        assertEquals(Event.RUN_START, c.step(tick(back, 8, zombie(3, 5, NEVER))));
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void somethingWithinTwelveRestartsTheClearClock() {
        CombatCommit c = chasedFight();
        long dead = NOW + CAP + 1;
        assertEquals(Event.FIGHT_DEAD, c.step(tick(dead, 8)));
        long start = dead + 1;
        for (long now = start; now < start + CombatCommit.RUN_CLEAR_TICKS - 1; now++) {
            c.step(tick(now, 8));
        }
        // a mob walks in at ten blocks just before the two seconds are up: the clock starts over
        long mid = start + CombatCommit.RUN_CLEAR_TICKS - 1;
        assertEquals(Event.NONE, c.step(tick(mid, 8, zombie(3, 10, NEVER))));
        // (the latch is still on, so this is its one break and not a fresh run)
        long after = start + CombatCommit.COOLDOWN;
        assertEquals(Event.RUN_START, c.step(tick(after, 8, zombie(3, 5, NEVER))));
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void aFullClearGivesTheBreakBack() {
        CombatCommit c = chasedFight();
        long dead = NOW + CAP + 1;
        assertEquals(Event.FIGHT_DEAD, c.step(tick(dead, 10)));
        long again = dead + CombatCommit.COOLDOWN;
        assertEquals(Event.RUN_START, c.step(tick(again, 10, crowd())));
        assertEquals(Why.CROWD, c.why());
        // out of sight and 24 away: the run ends clear, and the latch lets go with nothing within 12 for two seconds
        long now = again + 1;
        while (c.mode() == Mode.RUN && now < again + 300) {
            c.step(new Tick(now++, 10, 24, 0, List.of(), null));
        }
        assertEquals(Mode.NONE, c.mode());
        now += CombatCommit.COOLDOWN;
        // the same story again: a run that never shakes the crowd, the cap turns round, and the break is back
        long start = now;
        assertEquals(Event.RUN_START, c.step(tick(start, 10, crowd())));
        for (now = start + 1; now < start + CAP; now++) {
            assertEquals(Event.NONE, c.step(shuffle(now, 10, crowd())));
        }
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(start + CAP, 10, crowd())));
        Foe target = zombie(1, 2, NEVER);
        assertEquals(Event.FIGHT_TO_RUN, c.step(new Tick(start + CAP + 1, 10, 0, 0, List.of(crowd()), target)));
    }

    @Test
    public void aCrowdAboveTheLowLineGetsOneBreakAndNoMore() {
        CombatCommit c = breakSpentFight();
        // the second turn round, the crowd is still on us at 10 hp: no second break, the fight stays
        Foe target = zombie(1, 2, NEVER);
        long next = NOW + 2 * CAP + 2;
        assertEquals(Event.NONE, c.step(new Tick(next, 10, 0, 0, List.of(crowd()), target)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void lowHpAfterTheBreakIsSpentIsARunNotAFight() {
        CombatCommit c = breakSpentFight();
        long dead = NOW + 2 * CAP + 2;
        // zombie 1 dies at 7 hp with another one on us: no IN_WAY or HIT fight, the run is the answer
        assertEquals(Event.RUN_START, c.step(tick(dead, 7, zombie(9, 2, NEVER))));
        assertEquals(Event.FIGHT_DEAD, c.endedFirst());
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void theHealUpHoldRunsAtLowHpAfterTheBreakIsSpent() {
        CombatCommit c = breakSpentFight();
        Body canHeal = new Body(20, true, false, false);
        long dead = NOW + 2 * CAP + 2;
        assertEquals(Event.FIGHT_DEAD, c.step(new Tick(dead, 8, 0, 0, List.of(), null, Way.NONE, true, false, false, canHeal)));
        assertTrue(c.recovering());
        // hp 8 and a zombie that hit us a while ago, four blocks off: the hold's rerun asks, and the latch no longer says no
        long hit = dead + 2;
        Foe biter = zombie(9, 4, CombatCommit.FRESH + 1);
        assertEquals(Event.RUN_START, c.step(new Tick(hit, 8, 0, 0, List.of(biter), null, Way.NONE, true, false, false, canHeal)));
        assertEquals(Why.LOW_HP, c.why());
    }

    // the latch said none to a zombie that had not hit us yet (meleeOnUs wants contact or a hit), so the hold stood there at hp 8
    // with it walking over. in the hold anything melee inside the rerun range is a run
    @Test
    public void theHealUpHoldRunsFromAZombieThatHasNotHitUsYetUnderTheLatch() {
        CombatCommit c = breakSpentFight();
        Body canHeal = new Body(20, true, false, false);
        long dead = NOW + 2 * CAP + 2;
        assertEquals(Event.FIGHT_DEAD, c.step(new Tick(dead, 8, 0, 0, List.of(), null, Way.NONE, true, false, false, canHeal)));
        assertTrue(c.recovering());
        long walk = dead + 2;
        Foe walker = zombie(9, 6, NEVER);
        assertEquals(Event.RUN_START, c.step(new Tick(walk, 8, 0, 0, List.of(walker), null, Way.NONE, true, false, false, canHeal)));
        assertEquals(Why.LOW_HP, c.why());
    }

    // boxed there is no run to take instead, so an old hit still starts the cornered fight: the fresh-only gate is for the
    // ones that can walk away
    @Test
    public void aBoxedHealUpHoldStillFightsAZombieThatHitUsAWhileAgo() {
        CombatCommit c = chasedFight();
        Body canHeal = new Body(20, true, false, false);
        long dead = NOW + CAP + 1;
        assertEquals(Event.FIGHT_DEAD, c.step(new Tick(dead, 8, 0, 0, List.of(), null, Way.NONE, true, false, false, canHeal)));
        assertTrue(c.recovering());
        long later = dead + CombatCommit.COOLDOWN + 1;
        Foe biter = zombie(9, 2, 20);
        assertEquals(Event.FIGHT_START, c.step(new Tick(later, 8, 0, 0, List.of(biter), null, Way.NONE, true, false, false, canHeal, true)));
    }

    // a crossbow piglin out there with a line, the meal under way and the latch still on from the fight before it
    private static Foe crossbow(long sinceHit) {
        return new Foe(9, 7, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
    }

    private static Event holdWith(Foe shooter) {
        CombatCommit c = breakSpentFight();
        Body canHeal = new Body(20, true, false, false);
        long dead = NOW + 2 * CAP + 2;
        assertEquals(Event.FIGHT_DEAD, c.step(new Tick(dead, 5, 0, 0, List.of(), null, Way.NONE, false, false, false, canHeal)));
        assertTrue(c.recovering());
        return c.step(new Tick(dead + 2, 5, 0, 0, List.of(shooter), null, Way.NONE, false, false, false, canHeal));
    }

    @Test
    public void aShotThatJustLandedBreaksTheLatchInTheHealUpHold() {
        assertEquals(Event.RUN_START, holdWith(crossbow(10)));
    }

    @Test
    public void anOldShotLeavesTheLatchOnInTheHealUpHold() {
        assertEquals(Event.NONE, holdWith(crossbow(CombatCommit.SHOT_FRESH + 20)));
    }

    @Test
    public void aShooterWithoutALineDoesNotBreakTheLatch() {
        Foe blind = new Foe(9, 7, true, false, 10, Kind.NORMAL, Double.POSITIVE_INFINITY, false, NEVER);
        assertEquals(Event.NONE, holdWith(blind));
    }

    @Test
    public void noInTheWayPickWhileAShooterHasALineOnUs() {
        Foe z = zombie(1, 2, NEVER);
        Way sword = new Way(Set.of(1), true, 0, true);
        Foe skeleton = new Foe(9, 12, true, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(new Tick(NOW, 20, 0, 0, List.of(z, skeleton), null, sword, true)));
        // the same shooter out of its range, or with the line gone, is the old pick
        Foe far = new Foe(9, CombatCommit.FOE_RANGE + 2, true, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
        assertEquals(Event.FIGHT_START, new CombatCommit().step(new Tick(NOW, 20, 0, 0, List.of(z, far), null, sword, true)));
        Foe blind = new Foe(9, 12, true, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, false, NEVER);
        assertEquals(Event.FIGHT_START, new CombatCommit().step(new Tick(NOW, 20, 0, 0, List.of(z, blind), null, sword, true)));
    }

    @Test
    public void noNoteWhileNothingHappens() {
        assertEquals(null, new CombatCommit().takeNote());
    }

    @Test
    public void aLowHpCapStartsAFreshRunNotAFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 7, zombie(1, 5, NEVER))));
        for (long now = NOW + 1; now < NOW + CAP; now++) {
            assertEquals(Event.NONE, c.step(shuffle(now, 7, zombie(1, 5, NEVER))));
        }
        assertEquals(Event.RUN_START, c.step(new Tick(NOW + CAP, 7, 6, 0, List.of(zombie(1, 5, NEVER)), null)));
        assertEquals(Why.LOW_HP, c.why());
        assertEquals(Mode.RUN, c.mode());
        assertEquals(6, c.originX(), 0);
    }

    @Test
    public void aFastMobOnUsNeverBreaksTheLatch() {
        CombatCommit c = chasedFight();
        Foe target = zombie(1, 2, 3);
        // hp 5 with a cube that bit us three blocks away: it comes along on a run, so the fight stays
        assertEquals(Event.NONE, c.step(new Tick(NOW + CAP + 1, 5, 0, 0, List.of(target, cube(2, 3, 3)), target)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Event.NONE, c.step(new Tick(NOW + CAP + 2, 5, 0, 0, List.of(target, cube(2, 3, 4)), target)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aWardenGetsPastTheLatch() {
        CombatCommit c = chasedFight();
        long dead = NOW + CAP + 1;
        assertEquals(Event.FIGHT_DEAD, c.step(tick(dead, 8)));
        assertEquals(Event.RUN_START, c.step(tick(dead + 1, 8, warden(4, 6))));
        assertEquals(Why.HEAVY, c.why());
    }

    @Test
    public void resetClearsTheLatch() {
        CombatCommit c = chasedFight();
        c.reset();
        assertEquals(Event.RUN_START, c.step(tick(NOW + CAP + 50, 7, zombie(1, 5, NEVER))));
    }

    // ---- the hurt pair is gone

    @Test
    public void twoAttackersAtTenHpAreNotACrowd() {
        assertFalse(CombatCommit.crowded(10, List.of(zombie(1, 2, 3), zombie(2, 2.5, 3)), false));
        assertTrue(CombatCommit.crowded(10, List.of(zombie(1, 2, 3), zombie(2, 2.5, 3), zombie(3, 2, 3)), false));
    }

    // ---- fast mobs at the run decision

    @Test
    public void aCubeThatHitUsAtLowHpIsTheCorneredFightNotARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_TO_FIGHT, c.step(tick(NOW, 7, cube(1, 3, 3))));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.CORNERED, c.why());
        assertTrue(c.cornered());
        assertEquals(1, c.targetId());
    }

    @Test
    public void aWardenWalkingUpOnAFastFightIsStillARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_TO_FIGHT, c.step(tick(NOW, 7, cube(1, 3, 3))));
        Foe target = cube(1, 3, 3);
        assertEquals(Event.FIGHT_TO_RUN, c.step(new Tick(NOW + 1, 7, 0, 0, List.of(target, warden(2, 6)), target)));
        assertEquals(Why.HEAVY, c.why());
    }

    @Test
    public void aSlowZombieInTheSameSpotIsStillARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 7, zombie(1, 3, 3))));
    }

    @Test
    public void aFastOneThatNeverHitUsOrIsFarOrWeHaveNoWeaponStaysARun() {
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(NOW, 7, cube(1, 3, NEVER))));
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(NOW, 7, cube(1, CombatCommit.FAST_FIGHT_RANGE + 1, 3))));
        Tick bare = new Tick(NOW, 7, 0, 0, List.of(cube(1, 3, 3)), null, Way.NONE, false);
        assertEquals(Event.RUN_START, new CombatCommit().step(bare));
    }

    @Test
    public void aFightLowOnHpWithAFastOneOnUsKeepsFightingAndLatches() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, cube(1, 2, 3))));
        Foe target = cube(1, 2, 3);
        assertEquals(Event.NONE, c.step(new Tick(NOW + 1, 7, 0, 0, List.of(target), target)));
        assertEquals(Mode.FIGHT, c.mode());
        assertTrue(c.cornered());
        // it dies, another bounces out: no run
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 2, 7, cube(2, 5, 20))));
        assertEquals(Mode.NONE, c.mode());
    }

    // ---- the fight we pick wants a real weapon

    @Test
    public void anInTheWayFightNeedsASwordOrAnAxe() {
        Foe z = zombie(1, 2, NEVER);
        Tick pickOnly = new Tick(NOW, 20, 0, 0, List.of(z), null, new Way(Set.of(1), true, 0, false), true);
        assertEquals(Event.NONE, new CombatCommit().step(pickOnly));
        Tick sword = new Tick(NOW, 20, 0, 0, List.of(z), null, new Way(Set.of(1), true, 0, true), true);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(sword));
        assertEquals(Why.IN_WAY, c.why());
    }

    @Test
    public void aMobThatHitsUsIsStillAFightWithOnlyAPick() {
        Foe z = zombie(1, 2, 3);
        Tick pickOnly = new Tick(NOW, 20, 0, 0, List.of(z), null, new Way(Set.of(1), true, 0, false), true);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(pickOnly));
        assertEquals(Why.HIT, c.why());
    }
}
