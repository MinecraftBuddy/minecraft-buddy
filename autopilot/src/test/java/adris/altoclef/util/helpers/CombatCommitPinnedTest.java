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
import baritone.api.utils.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

// a skeleton that is shooting us is one target: kill it. walking or standing, no pin window, no waiting for a second arrow.
// hurt, or more than one of them, is a run that ends when none of them can see us
public class CombatCommitPinnedTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final long NOW = 1000;
    private static final int SKELETON = 6;

    private static Foe skeleton(int id, double distance, long sinceHit, boolean sight) {
        return new Foe(id, distance, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, sight, NEVER);
    }

    private static Foe skeleton(double distance, long sinceHit, boolean sight) {
        return skeleton(SKELETON, distance, sinceHit, sight);
    }

    // still a foe (FOE_RANGE) and out of its bow, so a run can end with it about
    private static final double PAST_THE_BOW = CombatRules.SHOOTER_RANGE + 1;

    // an arrow a few ticks ago, from twelve blocks with a line
    private static Foe shooter() {
        return skeleton(12, 3, true);
    }

    private static Tick tick(long now, float hp, boolean armed, Foe target, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), target, Way.NONE, armed, false);
    }

    private static Tick tick(long now, float hp, Foe target, Foe... foes) {
        return tick(now, hp, true, target, foes);
    }

    // a tick a run is walking, half a block a tick so the stuck rule has nothing to say
    private static Tick walking(long now, float hp, Foe... foes) {
        return new Tick(now, hp, (now - NOW) * 0.5, 0, List.of(foes), null, Way.NONE, true, false);
    }

    @Test
    public void oneShootingSkeletonIsAFightAtOnce() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, shooter())));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.PINNED, c.why());
        assertEquals(SKELETON, c.targetId());
    }

    @Test
    public void anOldArrowOrNoLineOrTooFarIsNothing() {
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, 20, null, skeleton(12, CombatCommit.SHOT_MEMORY + 1, true))));
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, 20, null, skeleton(12, 3, false))));
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, 20, null, skeleton(CombatCommit.FOE_RANGE + 1, 3, true))));
        assertEquals(Event.FIGHT_START, new CombatCommit().step(tick(NOW, 20, null, skeleton(CombatCommit.FOE_RANGE, 3, true))));
    }

    @Test
    public void hurtItIsStillAFightWithOneAndARunWithTwo() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, CombatCommit.FLEE_HP, null, shooter())));
        assertEquals(Why.PINNED, c.why());
        assertEquals(Event.FIGHT_START, new CombatCommit().step(tick(NOW, CombatCommit.FLEE_HP + 1, null, shooter())));
        CombatCommit pair = new CombatCommit();
        assertEquals(Event.RUN_START, pair.step(tick(NOW, CombatCommit.FLEE_HP, null, shooter(), skeleton(7, 9, 20, true))));
        assertEquals(Why.SHOT, pair.why());
    }

    @Test
    public void twoShootingSkeletonsAreARunAtAnyHp() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, null, shooter(), skeleton(7, 9, 20, true))));
        assertEquals(Why.SHOT, c.why());
    }

    @Test
    public void aSecondSkeletonJoiningTurnsTheFightIntoARun() {
        CombatCommit c = new CombatCommit();
        Foe first = shooter();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, first, first)));
        assertEquals(Event.NONE, c.step(tick(NOW + 1, 20, first, first)));
        Foe second = skeleton(7, 14, 2, true);
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 2, 20, first, first, second)));
        assertEquals(Why.SHOT, c.why());
    }

    @Test
    public void aCrowdOnUsStillWins() {
        CombatCommit c = new CombatCommit();
        List<Foe> foes = new ArrayList<>(List.of(shooter()));
        for (int i = 0; i < 3; i++) foes.add(new Foe(10 + i, 2, false, false, NEVER));
        assertEquals(Event.RUN_START, c.step(new Tick(NOW, 20, 0, 0, foes, null, Way.NONE, true, false)));
        assertEquals(Why.CROWD, c.why());
    }

    @Test
    public void somethingInContactThatHitUsIsTheOrdinaryFightFirst() {
        CombatCommit c = new CombatCommit();
        Foe zombie = new Foe(10, 2, false, false, 2);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, shooter(), zombie)));
        assertEquals(Why.HIT, c.why());
        assertEquals(10, c.targetId());
    }

    @Test
    public void bareHandsDoNotGoAndGetASkeleton() {
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, 20, false, null, shooter())));
        // hurt it is still the run
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, CombatCommit.FLEE_HP, false, null, shooter())));
        assertEquals(Why.SHOT, c.why());
    }

    @Test
    public void aWitchIsStillTheLobberRuleAndABlazeIsNotASniper() {
        Foe witch = new Foe(5, 12, true, false, 3, Kind.LOBBER, Double.POSITIVE_INFINITY, true, NEVER);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, witch)));
        assertEquals(Why.HIT, c.why());
        Foe blaze = new Foe(7, 12, true, false, 3, Kind.FLYER, Double.POSITIVE_INFINITY, true, NEVER);
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, 20, null, blaze)));
    }

    @Test
    public void theLineSurvivesTheTripThroughFoeRules() {
        FoeRules.Candidate c = new FoeRules.Candidate("skeleton", SKELETON, 12, () -> false, () -> true, () -> true, 3, 0, true, false,
                Double.POSITIVE_INFINITY, true, NEVER);
        Foe foe = FoeRules.accept(Dimension.OVERWORLD, c);
        assertTrue(foe.sight());
        assertTrue(foe.shooting());
        // the short constructors know nothing about the line, which is the cautious answer
        Foe plain = FoeRules.accept(Dimension.OVERWORLD, new FoeRules.Candidate("skeleton", SKELETON, 12, () -> false, () -> true,
                () -> true, 3, 0, true, false));
        assertTrue(!plain.sight());
    }

    @Test
    public void inTheCooldownOnlyAFreshArrowCounts() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, shooter())));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 5, 20, null)));
        assertTrue(c.coolingDown(NOW + 10));
        assertEquals(Event.NONE, c.step(tick(NOW + 20, 20, null, skeleton(12, 15, true))));
        assertEquals(Event.FIGHT_START, c.step(tick(NOW + 30, 20, null, skeleton(12, 1, true))));
    }

    // ---- a fight we cannot win

    // the skeleton sits on a ledge and keeps shooting (an arrow every 25 ticks). the stall rule writes it off
    private static long stallAFight(CombatCommit c) {
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, shooter())));
        for (long t = NOW + 1; t < NOW + CombatCommit.STALL_TICKS; t++) {
            Foe s = skeleton(12, (t - NOW) % 25, true);
            assertEquals("tick " + t, Event.NONE, c.step(tick(t, 20, s, s)));
        }
        long gaveUp = NOW + CombatCommit.STALL_TICKS;
        Foe stale = skeleton(12, (gaveUp - NOW) % 25, true);
        assertEquals(Event.FIGHT_STALLED, c.step(tick(gaveUp, 20, stale, stale)));
        assertTrue(c.isIgnored(SKELETON));
        return gaveUp;
    }

    @Test
    public void aSniperWeCouldNotGetToIsARunOutOfItsSightNotAnotherFight() {
        CombatCommit c = new CombatCommit();
        long gaveUp = stallAFight(c);
        assertEquals(Event.RUN_START, c.step(tick(gaveUp + 40, 20, null, skeleton(12, 2, true))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.SHOT, c.why());
        // and it is the sight run: out of its line (and its bow) for the count ends it, far or not
        long t = gaveUp + 41;
        for (; t < gaveUp + 41 + CombatCommit.LOS_CLEAR_TICKS; t++) {
            assertEquals("tick " + t, Event.NONE, c.step(walking(t, 20, skeleton(PAST_THE_BOW, 2, false))));
        }
        assertEquals(Event.RUN_CLEAR, c.step(walking(t, 20, skeleton(PAST_THE_BOW, 2, false))));
    }

    @Test
    public void itStaysGivenUpForLongerThanALobberWould() {
        CombatCommit c = new CombatCommit();
        long gaveUp = stallAFight(c);
        assertEquals(Event.RUN_START, c.step(tick(gaveUp + CombatCommit.LOBBER_GIVE_UP_TICKS + 20, 20, null, skeleton(12, 2, true))));
        CombatCommit later = new CombatCommit();
        long again = stallAFight(later) + CombatCommit.SNIPER_GIVE_UP_TICKS + 20;
        assertEquals(Event.FIGHT_START, later.step(tick(again, 20, null, skeleton(12, 2, true))));
    }

    @Test
    public void aSniperWeAreWalkingUpToIsNotLostForAMissedArrow() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, skeleton(16, 3, true))));
        for (long t = NOW + 1; t < NOW + 150; t++) {
            Foe s = skeleton(16 - (t - NOW) * 0.05, t - NOW + 3, true);
            assertEquals("tick " + t, Event.NONE, c.step(tick(t, 20, s, s)));
        }
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aWitchWeCouldNotReachDoesNotMakeASkeletonARun() {
        CombatCommit c = new CombatCommit();
        Foe witch = new Foe(5, 8, true, false, 3, Kind.LOBBER);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, witch)));
        for (long t = NOW + 1; t < NOW + CombatCommit.STALL_TICKS; t++) {
            Foe w = new Foe(5, 8, true, false, (t - NOW) % 25, Kind.LOBBER);
            assertEquals("tick " + t, Event.NONE, c.step(tick(t, 20, w, w)));
        }
        long gaveUp = NOW + CombatCommit.STALL_TICKS;
        Foe stale = new Foe(5, 8, true, false, (gaveUp - NOW) % 25, Kind.LOBBER);
        assertEquals(Event.FIGHT_STALLED, c.step(tick(gaveUp, 20, stale, stale)));
        assertEquals(Event.FIGHT_START, c.step(tick(gaveUp + 40, 20, null, skeleton(12, 2, true))));
        assertEquals(Why.PINNED, c.why());
    }

    // ---- the run out of sight

    // sight stays on us, so being far off for a long while is not the end of this run
    @Test
    public void aShooterRunIsNotOverWhileTheyCanSeeUs() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(walking(NOW, 6, skeleton(14, 2, true), skeleton(7, 14, 2, true))));
        for (long t = NOW + 1; t < NOW + 200; t++) {
            assertEquals("tick " + t, Event.NONE, c.step(walking(t, 6, skeleton(30, 2, true), skeleton(7, 30, 2, true))));
        }
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void itEndsOnceNoOneHasALineForAWhile() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(walking(NOW, 6, skeleton(14, 2, true), skeleton(7, 14, 2, true))));
        // the line drops for one tick short of the count, then flickers back and the count starts over
        long t = NOW + 1;
        for (; t < NOW + 1 + CombatCommit.LOS_CLEAR_TICKS; t++) {
            assertEquals("tick " + t, Event.NONE, c.step(walking(t, 6, skeleton(PAST_THE_BOW, 2, false), skeleton(7, PAST_THE_BOW, 2, false))));
        }
        assertEquals(Event.NONE, c.step(walking(t++, 6, skeleton(PAST_THE_BOW, 2, true), skeleton(7, PAST_THE_BOW, 2, true))));
        long from = t;
        for (; t < from + CombatCommit.LOS_CLEAR_TICKS; t++) {
            assertEquals("tick " + t, Event.NONE, c.step(walking(t, 6, skeleton(PAST_THE_BOW, 2, false), skeleton(7, PAST_THE_BOW, 2, false))));
        }
        assertEquals(Event.RUN_CLEAR, c.step(walking(t, 6, skeleton(PAST_THE_BOW, 2, false), skeleton(7, PAST_THE_BOW, 2, false))));
        assertEquals(Mode.NONE, c.mode());
    }

    // ---- a run does not end inside a bow's reach

    // a run that is not about sight: three zombies on us. then they are gone and a far skeleton is what is left
    private static CombatCommit crowdRun() {
        CombatCommit c = new CombatCommit();
        Foe zombie = new Foe(1, 2, false, false, NEVER);
        assertEquals(Event.RUN_START, c.step(walking(NOW, 20, zombie, new Foe(2, 2, false, false, NEVER), new Foe(3, 2, false, false, NEVER))));
        assertEquals(Why.CROWD, c.why());
        return c;
    }

    // goes the whole way to the cap with this foe about, walking the whole time
    private static void holdsToTheCap(CombatCommit c, float hp, Foe foe) {
        for (long t = NOW + 1; t < NOW + CombatCommit.RUN_CAP; t++) {
            assertEquals("tick " + t, Event.NONE, c.step(walking(t, hp, foe)));
        }
        assertEquals(Event.RUN_CAP, c.step(walking(NOW + CombatCommit.RUN_CAP, hp, foe)));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void theSightRunHoldsInBowRangeWithNoLineUntilTheCap() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(walking(NOW, 6, skeleton(14, 2, true), skeleton(7, 14, 2, true))));
        // round a corner, right on the bow's line: no line for ages is not out of range
        holdsToTheCap(c, 6, skeleton(CombatRules.SHOOTER_RANGE, 2, false));
    }

    @Test
    public void theDistanceRunHoldsInBowRangeUntilTheCap() {
        // far past the 24 and past RUN_CLEAR, with a line or without
        holdsToTheCap(crowdRun(), 20, skeleton(13, NEVER, false));
        holdsToTheCap(crowdRun(), 20, skeleton(CombatRules.SHOOTER_RANGE, NEVER, true));
    }

    @Test
    public void justPastTheBowTheRunEndsAsUsual() {
        CombatCommit c = crowdRun();
        Foe out = skeleton(CombatRules.SHOOTER_RANGE + 0.1, NEVER, false);
        Event last = Event.NONE;
        for (long t = NOW + 1; t <= NOW + 200 && last == Event.NONE; t++) last = c.step(walking(t, 20, out));
        assertEquals(Event.RUN_CLEAR, last);
    }

    @Test
    public void aSkeletonInTheWaterIsStillABow() {
        // a swim makes every one of them wet, that is no time to call the run over
        Foe wet = skeleton(14, NEVER, false).withWet(true);
        assertFalse(wet.sniper());
        holdsToTheCap(crowdRun(), 20, wet);
    }

    @Test
    public void onlyBowsHoldTheRunOutThere() {
        // a zombie past RUN_CLEAR, a calm blaze hovering (BlazeFight's), a witch: the old clear
        for (Foe foe : List.of(new Foe(9, 14, false, false, NEVER), new Foe(9, 14, true, false, NEVER, Kind.FLYER, Double.POSITIVE_INFINITY, false, NEVER),
                new Foe(9, 14, true, false, NEVER, Kind.LOBBER, Double.POSITIVE_INFINITY, false, NEVER))) {
            CombatCommit c = crowdRun();
            Event last = Event.NONE;
            for (long t = NOW + 1; t <= NOW + 200 && last == Event.NONE; t++) last = c.step(walking(t, 20, foe));
            assertEquals(foe.toString(), Event.RUN_CLEAR, last);
        }
    }

    @Test
    public void aRunThatCannotMoveStillGivesUpInBowRange() {
        // the stuck rule is untouched: standing still in the arrows until the cap would be worse
        CombatCommit c = crowdRun();
        Foe foe = skeleton(14, NEVER, false);
        Event last = Event.NONE;
        for (long t = NOW + 1; t <= NOW + CombatCommit.RUN_CAP && last == Event.NONE; t++) last = c.step(tick(t, 20, null, foe));
        assertEquals(Event.RUN_STUCK, last);
    }

    // ---- the shooter fight: one skeleton with a line and at most one melee mob on us is never run from for hp

    private static Foe zombieOnUs(int id) {
        return new Foe(id, 2, false, false, 2);
    }

    @Test
    public void aZombieAndASkeletonAtNineHpIsStillTheFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 14, null, shooter(), zombieOnUs(10))));
        assertEquals(10, c.targetId());
        assertEquals(Event.NONE, c.step(tick(NOW + 1, 9, zombieOnUs(10), shooter(), zombieOnUs(10))));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void theHurtPairIsNotACrowdWhenOneOfThemIsTheSkeleton() {
        Foe near = skeleton(5, 3, true);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 11, null, near, zombieOnUs(10))));
        assertEquals(Why.HIT, c.why());
        // two melee mobs on us are the old hurt pair
        CombatCommit pair = new CombatCommit();
        assertEquals(Event.RUN_START, pair.step(tick(NOW, 11, null, near, zombieOnUs(10), zombieOnUs(11))));
        assertEquals(Why.CROWD, pair.why());
    }

    // ---- company short of a crowd raises the low hp line (CombatCommit.fleeLine): two zombies on us and a skeleton shooting
    // from twelve leave at about 12 where one of them alone is fought down to 8 (the runner, 3 Nov 2026, attempt 96)

    @Test
    public void twoZombiesAndAShooterLeaveAtTwelve() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 12, null, shooter(), zombieOnUs(10), zombieOnUs(11))));
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void twoZombiesAndAShooterAtFifteenAreStillTheFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 15, null, shooter(), zombieOnUs(10), zombieOnUs(11))));
    }

    @Test
    public void oneZombieAloneIsFoughtDownToTheOldLine() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 11, null, zombieOnUs(10))));
        assertEquals(Why.HIT, c.why());
    }

    @Test
    public void theZombieDyingAtSixWithTheHoldOnIsThePinnedFightNotARerun() {
        RecoverHold.Body fed = new RecoverHold.Body(20, false, false, false);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(new Tick(NOW, 20, 0, 0, List.of(zombieOnUs(10)), null, Way.NONE, true, false, false, fed)));
        assertEquals(Event.FIGHT_DEAD, c.step(new Tick(NOW + 1, 6, 0, 0, List.of(), null, Way.NONE, true, false, false, fed)));
        assertTrue(c.recovering());
        assertEquals(Event.FIGHT_START, c.step(new Tick(NOW + 2, 6, 0, 0, List.of(shooter()), null, Way.NONE, true, false, false, fed)));
        assertEquals(Why.PINNED, c.why());
        assertEquals(SKELETON, c.targetId());
    }

    @Test
    public void closingOnTheSkeletonAtFiveHpStaysAFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, shooter())));
        Foe close = skeleton(6, 3, true);
        assertEquals(Event.NONE, c.step(tick(NOW + 1, 5, close, close)));
        // and it keeps the fight through a gap in its shooting, we are already on it
        Foe quiet = skeleton(6, 150, false);
        assertEquals(Event.NONE, c.step(tick(NOW + 2, 5, quiet, quiet)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aCreeperCloseKeepsTheOrdinaryRun() {
        Foe creeper = new Foe(11, 6, true, true, NEVER);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 6, null, shooter(), creeper)));
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void aWitchWithALineIsNotPartOfTheShooterFight() {
        Foe witch = new Foe(5, 9, true, false, 3, Kind.LOBBER, Double.POSITIVE_INFINITY, true, NEVER);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 6, null, shooter(), witch)));
    }

    @Test
    public void bareHandsStillRunFromAHurtShooterFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 6, false, null, shooter())));
        assertEquals(Why.SHOT, c.why());
    }

    // ---- a walk up to a skeleton wants a sword, a pickaxe is only armed for what is already on us

    // the bag answered: tools only, or a sword. half a block a tick like walking, so the run has somewhere to go
    private static Tick bag(long now, float hp, boolean sword, RecoverHold.Body body, Foe... foes) {
        return new Tick(now, hp, (now - NOW) * 0.5, 0, List.of(foes), null, new Way(Set.of(), false, 0, sword), true, false, false, body);
    }

    private static Tick toolOnly(long now, float hp, Foe... foes) {
        return bag(now, hp, false, RecoverHold.Body.NONE, foes);
    }

    @Test
    public void aPickaxeRunsFromASkeletonWhenHurt() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(toolOnly(NOW, CombatCommit.FLEE_HP, shooter())));
        assertEquals(Why.SHOT, c.why());
    }

    @Test
    public void aPickaxeDoesNotGoAndGetASkeletonWhenFine() {
        CombatCommit c = new CombatCommit();
        for (long t = NOW; t < NOW + 100; t++) {
            assertEquals("tick " + t, Event.NONE, c.step(toolOnly(t, 20, skeleton(12, 2, true))));
        }
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aSwordStillDoesBoth() {
        CombatCommit fine = new CombatCommit();
        assertEquals(Event.FIGHT_START, fine.step(bag(NOW, 20, true, RecoverHold.Body.NONE, shooter())));
        assertEquals(Why.PINNED, fine.why());
        CombatCommit hurt = new CombatCommit();
        assertEquals(Event.FIGHT_START, hurt.step(bag(NOW, CombatCommit.FLEE_HP, true, RecoverHold.Body.NONE, shooter())));
        assertEquals(Why.PINNED, hurt.why());
    }

    // one that is on us and hitting is still a swing, it is only the walk over that went away
    @Test
    public void aPickaxeStillSwingsAtASkeletonThatIsOnUs() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(toolOnly(NOW, 20, skeleton(2, 2, true))));
        assertEquals(Why.HIT, c.why());
    }

    // no spinning: the run has to actually end, only a new arrow starts another one, and it is never the walk over
    @Test
    public void aPickaxeRunEndsAndOnlyAFreshArrowStartsTheNext() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(toolOnly(NOW, 6, shooter())));
        long t = NOW + 1;
        for (; t < NOW + 1 + CombatCommit.LOS_CLEAR_TICKS; t++) {
            assertEquals("tick " + t, Event.NONE, c.step(toolOnly(t, 6, skeleton(PAST_THE_BOW, 30, false))));
        }
        assertEquals(Event.RUN_CLEAR, c.step(toolOnly(t++, 6, skeleton(PAST_THE_BOW, 30, false))));
        assertTrue(c.coolingDown(t));
        // back in its sights in the cooldown with an old arrow: nothing, on every tick
        for (long end = t + 40; t < end; t++) {
            assertEquals("tick " + t, Event.NONE, c.step(toolOnly(t, 6, skeleton(12, 30, true))));
        }
        assertEquals(Event.RUN_START, c.step(toolOnly(t, 6, skeleton(12, 1, true))));
        assertEquals(Why.SHOT, c.why());
    }

    // the heal hold says the skeleton is the next kill (see rerun). with a pickaxe it is not, hurt that is still the run
    @Test
    public void aPickaxeInTheHealHoldRunsToo() {
        RecoverHold.Body fed = new RecoverHold.Body(20, false, false, false);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(bag(NOW, 20, false, fed, zombieOnUs(10))));
        assertEquals(Event.FIGHT_DEAD, c.step(bag(NOW + 1, 6, false, fed)));
        assertTrue(c.recovering());
        assertEquals(Event.RUN_START, c.step(bag(NOW + 2, 6, false, fed, shooter())));
        assertEquals(Why.SHOT, c.why());
    }

    // into the heal hold at 6 hp off a dead zombie, the next tick is whatever the test wants
    private static final RecoverHold.Body FED = new RecoverHold.Body(20, false, false, false);

    private static CombatCommit inTheHold(boolean sword) {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(bag(NOW, 20, sword, FED, zombieOnUs(10))));
        assertEquals(Event.FIGHT_DEAD, c.step(bag(NOW + 1, 6, sword, FED)));
        assertTrue(c.recovering());
        return c;
    }

    // over the flee line, no fresh arrow, one close with a line. nobody is going to get it, so no healing in its sights
    @Test
    public void aPickaxeDoesNotHealInASkeletonsSights() {
        CombatCommit c = inTheHold(false);
        assertEquals(Event.RUN_START, c.step(bag(NOW + 2, 12, false, FED, skeleton(9, 45, true))));
        assertEquals(Why.LOW_HP, c.why());
    }

    // with a sword it is still the next kill, so the same tick is no run
    @Test
    public void aSwordStillIsNotRunFromInTheHold() {
        CombatCommit c = inTheHold(true);
        assertEquals(Event.NONE, c.step(bag(NOW + 2, 12, true, FED, skeleton(9, 45, true))));
        assertTrue(c.recovering());
    }

    // the chain remembers an arrow for longer than fresh does, so in the hold a run can follow an old one out of range too
    @Test
    public void aWatchedPickaxeRunsOnAnArrowThatIsNotFresh() {
        CombatCommit c = inTheHold(false);
        Way tools = new Way(Set.of(), false, 0, false);
        assertEquals(Event.RUN_START, c.step(new Tick(NOW + 2, 12, 1, 0, List.of(skeleton(14, 22, true)), null, tools, true, false, false, FED, false, 0, true, false)));
        assertEquals(Why.SHOT, c.why());
    }

    @Test
    public void aSkeletonBetweenArrowsStillCountsInAZombieFightAtEightHp() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 14, null, shooter(), zombieOnUs(10))));
        // the line dropped and the last arrow was a few seconds ago: no 1 on 1 low hp run for the zombie with it still out there
        Foe quiet = skeleton(12, 150, false);
        assertEquals(Event.NONE, c.step(tick(NOW + 1, 8, zombieOnUs(10), quiet, zombieOnUs(10))));
        assertEquals(Mode.FIGHT, c.mode());
    }

    // ---- (skel_cover, skel_close_rule) the overworld's skeletons, with the brain's facts handed in. no facts (the other
    // dimensions, both keys off) is every test above

    private static final int SECOND = 7;

    // SKELETON and SECOND are skeletons about; lines: those with a line on the buddy; sword and armour pieces worn; cover: the
    // brain's cover takes the buddy
    private static CombatCommit.Skel skel(Set<Integer> lines, boolean sword, int armour, boolean cover) {
        return new CombatCommit.Skel(Set.of(SKELETON, SECOND), lines, true, sword, armour, cover, SkelCover.CLOSE_HP, SkelCover.RANGE);
    }

    private static CombatCommit with(CombatCommit.Skel facts) {
        CombatCommit c = new CombatCommit();
        c.skelFacts(facts);
        return c;
    }

    @Test
    public void armouredWithASwordAtFullHpItIsStillTheFight() {
        CombatCommit c = with(skel(Set.of(SKELETON), true, 1, false));
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, shooter())));
        assertEquals(Why.PINNED, c.why());
        assertEquals(Event.FIGHT_START, with(skel(Set.of(SKELETON), true, 1, true)).step(tick(NOW, SkelCover.CLOSE_HP, null, shooter())));
    }

    @Test
    public void noArmourIsNoWalkUpButARunWhereTheCoverDoesNotTake() {
        CombatCommit c = with(skel(Set.of(SKELETON), true, 0, false));
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, null, shooter())));
        assertEquals(Why.SHOT, c.why());
        assertEquals("not closing on the skeleton: no armour on, a run out of its sight", c.takeNote());
    }

    @Test
    public void underTheCloseLineItIsTheCover() {
        CombatCommit c = with(skel(Set.of(SKELETON), true, 2, true));
        assertEquals(Event.NONE, c.step(tick(NOW, 15, null, shooter())));
        assertEquals(Mode.NONE, c.mode());
        assertEquals("not closing on the skeleton: hp 15.0, under 16, into cover", c.takeNote());
    }

    // counted by sight: a second one that has not hit yet is enough
    @Test
    public void aSecondSkeletonInSightStopsTheWalkUp() {
        Foe watching = skeleton(SECOND, 13, NEVER, true);
        CombatCommit c = with(skel(Set.of(SKELETON, SECOND), true, 2, true));
        assertEquals(Event.NONE, c.step(tick(NOW, 17, null, shooter(), watching)));
        assertEquals(Mode.NONE, c.mode());
        CombatCommit open = with(skel(Set.of(SKELETON, SECOND), true, 2, false));
        assertEquals(Event.RUN_START, open.step(tick(NOW, 20, null, shooter(), watching)));
        assertEquals(Why.SHOT, open.why());
    }

    // a pillager or a drowned is no skeleton: the rule has no say
    @Test
    public void notASkeletonKindIsAsBefore() {
        CombatCommit c = new CombatCommit();
        c.skelFacts(new CombatCommit.Skel(Set.of(99), Set.of(99), true, false, 0, false, SkelCover.CLOSE_HP, SkelCover.RANGE));
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 12, null, shooter())));
        assertEquals(Why.PINNED, c.why());
    }

    // test heel: hurt under two skeletons the run went through their lines. with the cover on offer it is the cover
    @Test
    public void hurtUnderTwoSkeletonsItIsTheCoverNotARun() {
        Foe second = skeleton(SECOND, 9, 20, true);
        CombatCommit c = with(skel(Set.of(SKELETON, SECOND), true, 2, true));
        assertEquals(Event.NONE, c.step(tick(NOW, CombatCommit.FLEE_HP, null, shooter(), second)));
        assertEquals(Mode.NONE, c.mode());
        CombatCommit open = with(skel(Set.of(SKELETON, SECOND), true, 2, false));
        assertEquals(Event.RUN_START, open.step(tick(NOW, CombatCommit.FLEE_HP, null, shooter(), second)));
        assertEquals(Why.SHOT, open.why());
    }

    // skeletons alone close at low hp are the cover too; a zombie close is still the run
    @Test
    public void skeletonsCloseAtLowHpAreTheCoverAZombieIsTheRun() {
        Foe close = skeleton(SKELETON, 6, 20, true);
        Foe far = skeleton(SECOND, 12, 30, true);
        assertEquals(Event.NONE, with(skel(Set.of(SKELETON, SECOND), true, 2, true)).step(tick(NOW, 5, null, close, far)));
        CombatCommit zombie = with(skel(Set.of(SKELETON, SECOND), true, 2, true));
        assertEquals(Event.RUN_START, zombie.step(tick(NOW, 5, null, close, far, new Foe(10, 5, false, false, NEVER))));
        assertEquals(Why.LOW_HP, zombie.why());
    }

    @Test
    public void aSecondSkeletonComingIntoSightEndsTheWalkUpInCover() {
        Foe first = shooter();
        CombatCommit c = with(skel(Set.of(SKELETON), true, 2, true));
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, first, first)));
        c.takeNote();
        c.skelFacts(skel(Set.of(SKELETON, SECOND), true, 2, true));
        assertEquals(Event.FIGHT_TO_COVER, c.step(tick(NOW + 1, 17, first, first, skeleton(SECOND, 14, NEVER, true))));
        assertEquals(Mode.NONE, c.mode());

        CombatCommit open = with(skel(Set.of(SKELETON), true, 2, false));
        assertEquals(Event.FIGHT_START, open.step(tick(NOW, 20, first, first)));
        open.skelFacts(skel(Set.of(SKELETON, SECOND), true, 2, false));
        assertEquals(Event.FIGHT_TO_RUN, open.step(tick(NOW + 1, 20, first, first, skeleton(SECOND, 14, NEVER, true))));
        assertEquals(Why.SHOT, open.why());
    }

    // in the heal hold an arrow was a run (aWatchedPickaxeRunsOnAnArrowThatIsNotFresh); with the cover it is the cover
    @Test
    public void inTheHoldASkeletonsArrowIsTheCover() {
        CombatCommit c = inTheHold(false);
        c.skelFacts(skel(Set.of(SKELETON), false, 0, true));
        Way tools = new Way(Set.of(), false, 0, false);
        assertEquals(Event.NONE, c.step(new Tick(NOW + 2, 12, 1, 0, List.of(skeleton(14, 22, true)), null, tools, true, false, false, FED, false, 0, true, false)));
        assertTrue(c.recovering());
    }
}
