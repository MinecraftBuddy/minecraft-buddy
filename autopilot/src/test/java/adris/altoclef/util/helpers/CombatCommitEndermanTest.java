package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import baritone.api.utils.Dimension;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import java.util.List;
import org.junit.Test;

// an angry enderman is fought to the end, never run from. water at low hp is the chain's (EndermanWater)
public class CombatCommitEndermanTest {

    private static final long NEVER = CombatCommit.NEVER;

    private static Foe enderman(double distance, long sinceHit) {
        return new Foe(5, distance, false, false, sinceHit, Kind.ENDERMAN);
    }

    private static Tick tick(long now, float health, List<Foe> foes, Foe target) {
        return new Tick(now, health, 0, 0, foes, target, Way.NONE);
    }

    @Test
    public void theKindComesFromTheType() {
        assertEquals(Kind.ENDERMAN, FoeRules.kindOf(Dimension.END, "enderman"));
        assertEquals(Kind.NORMAL, FoeRules.kindOf(Dimension.END, "zombie"));
    }

    @Test
    public void anEndermanThatHitUsIsAFightEvenAtThreeHp() {
        CombatCommit c = new CombatCommit();
        Foe e = enderman(2, 0);
        assertEquals(Event.FIGHT_START, c.step(tick(0, 3, List.of(e), null)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void theFightNeverTurnsIntoARunAsTheHpDrops() {
        CombatCommit c = new CombatCommit();
        Foe e = enderman(2, 0);
        assertEquals(Event.FIGHT_START, c.step(tick(0, 20, List.of(e), null)));
        for (long t = 1; t < 50; t++) {
            float hp = Math.max(1, 20 - t);
            assertEquals(Event.NONE, c.step(tick(t, hp, List.of(e), e)));
            assertEquals(Mode.FIGHT, c.mode());
        }
    }

    @Test
    public void threeEndermenOnUsIsNotACrowdRun() {
        CombatCommit c = new CombatCommit();
        Foe a = new Foe(1, 2, false, false, 0, Kind.ENDERMAN);
        Foe b = new Foe(2, 2, false, false, NEVER, Kind.ENDERMAN);
        Foe d = new Foe(3, 2, false, false, NEVER, Kind.ENDERMAN);
        assertEquals(Event.FIGHT_START, c.step(tick(0, 20, List.of(a, b, d), null)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aZombieAtLowHpIsStillARun() {
        CombatCommit c = new CombatCommit();
        Foe z = new Foe(1, 2, false, false, 0);
        assertEquals(Event.RUN_START, c.step(tick(0, 5, List.of(z), null)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void anEndermanBeingAboutAtLowHpAndNotHittingUsStartsNothing() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(0, 4, List.of(enderman(6, NEVER)), null)));
    }

    @Test
    public void anAngryEndermanCloseEnoughToSwingIsAFightBeforeItHits() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(0, 20, List.of(enderman(4, NEVER)), null)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(5, c.targetId());
    }

    @Test
    public void anEndermanPastTheShieldRangeStillStartsNothing() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(0, 20, List.of(enderman(6, NEVER)), null)));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aWrittenOffEndermanThatHasNotHitUsIsNotAFightEvenClose() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(0, 20, List.of(enderman(10, 0)), null)));
        Event end = Event.NONE;
        long t = 1;
        for (; t < 400 && end == Event.NONE; t++) {
            end = c.step(tick(t, 20, List.of(enderman(10, 300 + t)), enderman(10, 300 + t)));
        }
        assertEquals(Mode.NONE, c.mode());
        assertEquals(Event.NONE, c.step(tick(t, 20, List.of(enderman(3, 300 + t)), null)));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void waterOnlyAtTheFloorWithABucketAndNotInTheNether() {
        double near = 3;
        assertTrue(EndermanWater.dunkNow(6, near, true, false, false));
        assertTrue(EndermanWater.dunkNow(2, near, true, false, false));
        assertFalse(EndermanWater.dunkNow(7, near, true, false, false));
        assertFalse(EndermanWater.dunkNow(4, near, false, false, false));
        assertFalse(EndermanWater.dunkNow(4, near, true, true, false));
        assertFalse(EndermanWater.dunkNow(4, near, true, false, true));
        assertFalse(EndermanWater.dunkNow(4, Double.POSITIVE_INFINITY, true, false, false));
        assertFalse(EndermanWater.dunkNow(4, EndermanWater.NEAR + 1, true, false, false));
    }

    @Test
    public void theWaterStaysWhileTheEndermanIsAbout() {
        assertTrue(EndermanWater.keepWater(3));
        assertFalse(EndermanWater.keepWater(Double.POSITIVE_INFINITY));
    }

    @Test
    public void anEndermanThatHitUsBarehandedIsStillAFightNotAnUnarmedRun() {
        CombatCommit c = new CombatCommit();
        Tick t = new Tick(0, 20, 0, 0, List.of(enderman(2, 0)), null, Way.NONE, false);
        assertEquals(Event.FIGHT_START, c.step(t));
        assertEquals(Event.RUN_START, new CombatCommit().step(new Tick(0, 20, 0, 0, List.of(new Foe(1, 2, false, false, 0)), null, Way.NONE, false)));
    }

    private static Foe zombie(double distance) {
        return new Foe(1, distance, false, false, NEVER);
    }

    @Test
    public void anEndermanThatHitUsFromAfarIsTheFightEvenAtLowHpWithAZombieAbout() {
        CombatCommit c = new CombatCommit();
        Foe e = enderman(10, 0);
        // a zombie this close at 6 hp is a run on its own (aZombieAtLowHpIsStillARun), the enderman about is what stops it
        assertEquals(Event.FIGHT_START, c.step(tick(0, 6, List.of(zombie(3), e), null)));
        assertEquals(Mode.FIGHT, c.mode());
        for (long t = 1; t < 30; t++) {
            assertEquals(Event.NONE, c.step(tick(t, 6, List.of(zombie(3), e), e)));
            assertEquals(Mode.FIGHT, c.mode());
        }
    }

    @Test
    public void theNextHitGuessDoesNotMakeThatFightARunEither() {
        CombatCommit c = new CombatCommit();
        Foe e = enderman(2, 0);
        Foe z = zombie(4);
        assertEquals(Event.FIGHT_START, c.step(tick(0, 20, List.of(z, e), null)));
        // big hits in a row: hp - the next hit is under the line long before hp is
        float hp = 20;
        for (long t = 1; t < 12; t++) {
            hp = Math.max(1, hp - 4);
            assertEquals(Event.NONE, c.step(tick(t, hp, List.of(z, e), e)));
            assertEquals(Mode.FIGHT, c.mode());
        }
    }

    @Test
    public void anAngryEndermanThatHasNotHitYetStillStopsTheLowHpRunFromOthers() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(0, 5, List.of(zombie(2), enderman(10, NEVER)), null)));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void anEndermanTooFarOffDoesNotStopTheRun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(0, 5, List.of(zombie(2), enderman(CombatCommit.FOE_RANGE + 4, NEVER)), null)));
    }

    @Test
    public void aRunTurnsIntoTheFightWhenAnEndermanHitsUs() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(0, 5, List.of(zombie(2)), null)));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(1, 5, List.of(zombie(2), enderman(12, 0)), null)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aFightOnSomethingElseSwapsToTheEndermanThatHitUs() {
        CombatCommit c = new CombatCommit();
        Foe z = new Foe(1, 2, false, false, 0);
        assertEquals(Event.FIGHT_START, c.step(tick(0, 20, List.of(z), null)));
        assertEquals(Event.FIGHT_NEXT, c.step(tick(1, 20, List.of(z, enderman(9, 0)), z)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void theWardenStillMakesItARunWithAnEndermanAbout() {
        CombatCommit c = new CombatCommit();
        Foe warden = new Foe(9, 4, false, false, NEVER, Kind.UNTOUCHABLE);
        assertEquals(Event.RUN_START, c.step(tick(0, 20, List.of(warden, enderman(6, 0)), null)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aRunTurnsIntoTheFightForAnAngryEndermanThatHasNotHitYet() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(0, 5, List.of(zombie(2)), null)));
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(1, 5, List.of(zombie(2), enderman(12, NEVER)), null)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aWitherSkeletonOnOurFloorKeepsTheRunGoingPastAnAngryEnderman() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(0, 5, List.of(zombie(2)), null)));
        Foe wither = new Foe(7, 6, false, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER).withWither(true);
        org.junit.Assert.assertNotEquals(Event.RUN_CHASED_FIGHT, c.step(tick(1, 5, List.of(zombie(2), wither, enderman(12, NEVER)), null)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aWitherSkeletonTwoFloorsDownDoesNotStopTheEndermanTurn() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(0, 5, List.of(zombie(2)), null)));
        Foe below = new Foe(7, 9, false, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, false, NEVER).withWither(true)
                .withSplit(null, false, 0, -8, 0);
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(1, 5, List.of(zombie(2), below, enderman(12, NEVER)), null)));
    }

    @Test
    public void bareHandsAndAZombieOnUsIsNotAnUnarmedRunWithAnEndermanAbout() {
        Foe z = new Foe(1, 2, false, false, 0);
        Tick lone = new Tick(0, 20, 0, 0, List.of(z), null, Way.NONE, false);
        assertEquals(Event.RUN_START, new CombatCommit().step(lone));
        Tick withEnderman = new Tick(0, 20, 0, 0, List.of(z, enderman(10, NEVER)), null, Way.NONE, false);
        assertEquals(Event.FIGHT_START, new CombatCommit().step(withEnderman));
    }

    @Test
    public void anEndermanThatWanderedOffAndWasWrittenOffStillHoldsTheRunsBack() {
        CombatCommit c = new CombatCommit();
        Foe e = enderman(10, 0);
        assertEquals(Event.FIGHT_START, c.step(tick(0, 20, List.of(e), null)));
        Event end = Event.NONE;
        long t = 1;
        // it hangs about 10 blocks off and never hits again: the fight is written off after a few seconds
        for (; t < 400 && end == Event.NONE; t++) {
            end = c.step(tick(t, 20, List.of(new Foe(5, 10, false, false, 300 + t, Kind.ENDERMAN)), new Foe(5, 10, false, false, 300 + t, Kind.ENDERMAN)));
        }
        assertEquals(Mode.NONE, c.mode());
        // hp is low and a zombie is on us, but the enderman is still within range: no run
        assertEquals(Event.NONE, c.step(tick(t, 5, List.of(zombie(2), new Foe(5, 10, false, false, 300 + t, Kind.ENDERMAN)), null)));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aRunFromAHeavyHitterIsNotTurnedIntoTheEndermanFight() {
        CombatCommit c = new CombatCommit();
        Foe brute = new Foe(7, 2, false, false, 0, Kind.HEAVY);
        assertEquals(Event.RUN_START, c.step(tick(0, 5, List.of(brute), null)));
        // past the danger radius with an enderman on us: the run goes on, the brute is still about
        Foe far = new Foe(7, 9, false, false, 0, Kind.HEAVY);
        assertEquals(Event.NONE, c.step(tick(1, 5, List.of(far, enderman(12, 0)), null)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aSkeletonShootingUsIsStillTheArmedFightWithAnEndermanAboutAndNeverARun() {
        Foe skeleton = new Foe(3, 10, true, false, 5, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
        // (one of them alone is the pinned fight even at 5 hp, a pair is the run the enderman rule holds back)
        Foe second = new Foe(4, 10, true, false, 5, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(0, 5, List.of(skeleton, second), null)));
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(0, 5, List.of(skeleton, enderman(12, NEVER)), null)));
        assertEquals(Mode.FIGHT, c.mode());
    }
}
